package whelk.component

import groovy.util.logging.Slf4j as Log

import org.codehaus.jackson.map.*
import org.codehaus.jackson.*

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.action.search.SearchType
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.*
import org.elasticsearch.common.transport.*
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.common.settings.*
import org.elasticsearch.search.highlight.*
import org.elasticsearch.action.admin.indices.flush.*
import org.elasticsearch.action.count.CountResponse
import org.elasticsearch.search.facet.FacetBuilders
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.query.*
import org.elasticsearch.search.sort.FieldSortBuilder
import org.elasticsearch.search.sort.SortOrder
import org.elasticsearch.index.query.FilterBuilders.*
import org.elasticsearch.action.delete.*
import org.elasticsearch.action.get.*
import org.elasticsearch.action.search.*

import org.elasticsearch.common.io.stream.*
import org.elasticsearch.common.xcontent.*

import static org.elasticsearch.index.query.QueryBuilders.*
import static org.elasticsearch.common.xcontent.XContentFactory.*

import whelk.*
import whelk.plugin.*
import whelk.result.*
import whelk.exception.*

import static whelk.util.Tools.*

@Log
class ElasticSearchClient extends ElasticSearch implements Index {

    ElasticSearchClient(Map params) {
        super(params)
    }
}

@Log
abstract class ElasticSearch extends BasicElasticComponent implements Index {

    String defaultType = "record"
    Map<String,String> configuredTypes
    List<String> availableTypes


    ElasticSearch(Map settings) {
        super(settings)
        configuredTypes = (settings ? settings.get("typeConfiguration", [:]) : [:])
        availableTypes = (settings ? settings.get("availableTypes", []) : [])
        if (settings.batchUpdateSize) {
            this.batchUpdateSize = settings.batchUpdateSize
        }
    }

    @Override
    void componentBootstrap(String indexName) {
        createIndexIfNotExists(indexName)
        flush()
        def realIndex = getRealIndexFor(indexName)
        availableTypes.each {
            checkTypeMapping(realIndex, it)
        }
        shapeComputer = plugins.find { it instanceof ElasticShapeComputer }
    }

    String getLatestIndex(String prefix) {
        def indices = performExecute(client.admin().cluster().prepareState()).state.metaData.indices
        def li = new TreeSet<String>()
        for (idx in indices.keys()) {
            if (idx.value.startsWith(prefix+"-")) {
                li << idx.value
            }
        }
        log.debug("Latest index is ${li.last()}")
        return li.last()
    }

    String createNewCurrentIndex(String indexName) {
        assert (indexName != null)
        log.info("Creating index ...")
        es_settings = loadJson("es_settings.json")
        String currentIndex = "${indexName}-" + new Date().format("yyyyMMdd.HHmmss")
        log.debug("Will create index $currentIndex.")
        performExecute(client.admin().indices().prepareCreate(currentIndex).setSettings(es_settings))
        setTypeMapping(currentIndex, defaultType)
        availableTypes.each {
            setTypeMapping(currentIndex, it)
        }
        return currentIndex
    }

    void reMapAliases(String indexAlias) {
        String oldIndex = getRealIndexFor(indexAlias)
        String currentIndex = getLatestIndex(indexAlias)
        log.debug("Resetting alias \"$indexAlias\" from \"$oldIndex\" to \"$currentIndex\".")
        performExecute(client.admin().indices().prepareAliases().addAlias(currentIndex, indexAlias).removeAlias(oldIndex, indexAlias))
    }


    def loadJson(String file) {
        def json
        try {
            json = getClass().classLoader.getResourceAsStream(file).withStream {
                mapper.readValue(it, Map)
            }
        } catch (NullPointerException npe) {
            log.trace("File $file not found.")
        }
        return json
    }

    @Override
    void remove(String identifier) {
        String indexName = this.whelk.id
        log.debug("Peforming deletebyquery to remove documents extracted from $identifier")
        def delQuery = termQuery("extractedFrom.@id", identifier)
        log.debug("DelQuery: $delQuery")

        def response = performExecute(client.prepareDeleteByQuery(indexName).setQuery(delQuery))

        log.debug("Delbyquery response: $response")
        for (r in response.iterator()) {
            log.debug("r: $r success: ${r.successfulShards} failed: ${r.failedShards}")
        }

        log.debug("Deleting object with identifier ${shapeComputer.toElasticId(identifier)}.")

        client.delete(new DeleteRequest(indexName, shapeComputer.calculateTypeFromIdentifier(identifier), shapeComputer.toElasticId(identifier)))

            // Kanske en matchall-query filtrerad på _type och _id?
    }

    /*
    @Override
    void index(Document doc) {
        String indexName = this.whelk.id
        if (doc && doc.isJson()) {
            createIndexIfNotExists(indexName)
            addDocuments([doc], indexName)
        }
    }
    */

    /*
    @Override
    protected void batchLoad(List<Document> docs) {
        String indexName = this.whelk.id
        createIndexIfNotExists(indexName)
        addDocuments(docs, indexName)
    }
    */

    @Override
    SearchResult query(Query q) {
        String indexName = this.whelk.id
        def indexTypes = []
        if (q instanceof ElasticQuery) {
            for (t in q.indexTypes) {
                if (configuredTypes[t]) {
                    log.debug("Adding configuredTypes for ${t}: ${configuredTypes[t]}")
                    indexTypes.add(t)
                    indexTypes.addAll(configuredTypes[t])
                } else {
                    indexTypes.add(t)
                }
            }
        } else {
            indexTypes = [defaultType]
        }
        log.debug("Assembled indexTypes: $indexTypes")
        return query(q, indexName, indexTypes as String[])
    }

    SearchResult query(Query q, String indexName, String[] indexTypes) {
        log.trace "Querying index $indexName and indextype $indexTypes"
        log.trace "Doing query on $q"
        def idxlist = [indexName]
        if (indexName.contains(",")) {
            idxlist = indexName.split(",").collect{it.trim()}
        }
        log.trace("Searching in indexes: $idxlist")
        def jsonDsl = q.toJsonQuery()
        def response = client.search(new SearchRequest(idxlist as String[], jsonDsl.getBytes("utf-8")).searchType(SearchType.DFS_QUERY_THEN_FETCH).types(indexTypes)).actionGet()
        log.trace("SearchResponse: " + response)

        def results = new SearchResult(0)

        if (response) {
            log.trace "Total hits: ${response.hits.totalHits}"
            results.numberOfHits = response.hits.totalHits
            response.hits.hits.each {
                if (q.highlights) {
                    results.addHit(createResultDocumentFromHit(it, indexName), convertHighlight(it.highlightFields))
                } else {
                    results.addHit(createResultDocumentFromHit(it, indexName))
                }
            }
            if (q.facets) {
                results.facets = convertFacets(response.facets.facets(), q)
            }
        }
        return results
    }

    /*
    String determineDocumentType(Document doc, String indexName) {
        def idxType = doc.entry['dataset']?.toLowerCase()
        log.trace("dataset in entry is ${idxType} for ${doc.identifier}")
        if (!idxType) {
            idxType = shapeComputer.calculateShape(doc.identifier)
        }
        log.trace("Using type $idxType for document ${doc.identifier}")
        return idxType
    }

    void addDocuments(documents, indexName) {
        String currentIndex = getRealIndexFor(indexName)
        log.debug("Using $currentIndex for indexing.")
        if (documents) {
            def breq = client.prepareBulk()

            def checkedTypes = [defaultType]

            log.debug("Bulk request to index " + documents?.size() + " documents.")

            for (doc in documents) {
                if (doc.timestamp < 1) {
                    throw new DocumentException("Document with 0 timestamp? Not buying it.")
                }
                log.trace("Working on ${doc.identifier}")
                    if (doc && doc.isJson()) {
                        //def indexType = determineDocumentType(doc, indexName)
                        def indexType = shapeComputer.calculateShape(doc.identifier)
                            def checked = indexType in checkedTypes
                            if (!checked) {
                                checkTypeMapping(currentIndex, indexType)
                                checkedTypes << indexType
                            }
                        def elasticIdentifier = translateIdentifier(doc.identifier)
                            breq.add(client.prepareIndex(indexName, indexType, elasticIdentifier).setSource(doc.data))
                    } else {
                        log.debug("Doc is null or not json (${doc.contentType})")
                    }
            }
            def response = performExecute(breq)
            if (response.hasFailures()) {
                log.error "Bulk import has failures."
                def fails = []
                boolean throwException = true
                for (re in response.items) {
                    if (re.failed) {
                        if (re.failureMessage.startsWith("MapperParsingException")) {
                            // TODO: figure out a way to reindex using alternate type or something ...
                            throwException = false
                        }
                        log.error "Fail message for id ${re.id}, type: ${re.type}, index: ${re.index}: ${re.failureMessage}"
                        if (log.isTraceEnabled()) {
                            for (doc in documents) {
                                if (doc.identifier.toString() == "/"+re.index+"/"+re.id) {
                                    log.trace("Failed document: ${doc.dataAsString}")
                                }
                            }
                        }
                        try {
                            fails << shapeComputer.translateIndexIdTo(re.id)
                        } catch (Exception e1) {
                            log.error("TranslateIndexIdTo cast an exception", e1)
                            fails << "Failed translation for \"$re\""
                        }
                    }
                }
                if (throwException) {
                    throw new WhelkAddException(fails)
                }
            }
        }
    }
    */

    def Map<String, String[]> convertHighlight(Map<String, HighlightField> hfields) {
        def map = new TreeMap<String, String[]>()
        hfields.each {
            map.put(it.value.name, it.value.fragments)
        }
        return map
    }

    def convertFacets(eFacets, query) {
        def facets = new HashMap<String, Map<String, Integer>>()
        for (def f : eFacets) {
            def termcounts = [:]
            try {
                for (def entry : f.entries) {
                    termcounts[entry.term] = entry.count
                }
                facets.put(f.name, termcounts.sort { a, b -> b.value <=> a.value })
            } catch (MissingMethodException mme) {
                def group = query.facets.find {it.name == f.name}.group
                termcounts = facets.get(group, [:])
                if (f.count) {
                    termcounts[f.name] = f.count
                }
                facets.put(group, termcounts.sort { a, b -> b.value <=> a.value })
            }
        }
        return facets
    }

    Document createResultDocumentFromHit(hit, queriedIndex) {
        log.trace("creating document. ID: ${hit?.id}, index: $queriedIndex")
        def metaEntryMap = null //getMetaEntry(hit.id, queriedIndex)
        if (metaEntryMap) {
            return new Document(metaEntryMap).withData(hit.source())
        } else {
            log.trace("Meta entry not found for document. Will assume application/json for content-type.")
            return new Document().withData(hit.source()).withContentType("application/json").withIdentifier(shapeComputer.fromElasticId(hit.id))
        }
    }

    private Map getMetaEntry(id, queriedIndex) {
        //def emei = ".$queriedIndex"
        def emei = this.whelk.primaryStorage.indexName
        log.trace("Requested id: $id")
        id = shapeComputer.translateIndexIdTo(id)
        log.trace("Translated id: $id")
        try {
            def grb = new GetRequestBuilder(client, emei).setType(METAENTRY_INDEX_TYPE).setId(shapeComputer.toElasticId(id))
            def result = performExecute(grb)
            if (result.exists) {
                return result.sourceAsMap
            }
        } catch (org.elasticsearch.indices.IndexMissingException ime) {
            log.debug("Meta entry index $emei does not exist.")
        }
        return null
    }
}
