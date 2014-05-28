package se.kb.libris.whelks.component

import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.*
import org.elasticsearch.common.transport.*
import org.elasticsearch.common.settings.*
import org.elasticsearch.action.admin.indices.flush.*

import se.kb.libris.whelks.exception.*

abstract class BasicElasticComponent extends BasicComponent {
    Client client

    BasicElasticComponent() {
        super()
        connectClient()
    }
    BasicElasticComponent(Map settings) {
        super()
        connectClient()
    }

    void connectClient() {
        String elastichost, elasticcluster
        int elasticport
        if (System.getProperty("elastic.host")) {
            elastichost = System.getProperty("elastic.host")
            elasticcluster = System.getProperty("elastic.cluster")
            elasticport = System.getProperty("elastic.port", "9300") as int
            log.info("Connecting to $elastichost:$elasticport using cluster $elasticcluster")
            def sb = ImmutableSettings.settingsBuilder()
                .put("client.transport.ping_timeout", 30000)
                .put("client.transport.sniff", true)
            if (elasticcluster) {
                sb = sb.put("cluster.name", elasticcluster)
            }
            Settings elasticSettings = sb.build();
            client = new TransportClient(elasticSettings).addTransportAddress(new InetSocketTransportAddress(elastichost, elasticport))
            log.debug("... connected")
        } else {
            throw new WhelkRuntimeException("Unable to initialize elasticsearch. Need at least system property \"elastic.host\" and possibly \"elastic.cluster\".")
        }
    }

    def performExecute(def requestBuilder) {
        int failcount = 0
        def response = null
        while (response == null) {
            try {
                response = requestBuilder.execute().actionGet()
            } catch (NoNodeAvailableException n) {
                log.trace("Retrying server connection ...")
                if (failcount++ > WARN_AFTER_TRIES) {
                    log.warn("Failed to connect to elasticsearch after $failcount attempts.")
                }
                if (failcount % 100 == 0) {
                    log.info("Server is not responsive. Still trying ...")
                }
                Thread.sleep(RETRY_TIMEOUT + failcount > MAX_RETRY_TIMEOUT ? MAX_RETRY_TIMEOUT : RETRY_TIMEOUT + failcount)
            }
        }
        return response
    }

    void createIndexIfNotExists(String indexName) {
        if (!performExecute(client.admin().indices().prepareExists(indexName)).exists) {
            log.info("Couldn't find index by name $indexName. Creating ...")
            if (indexName.startsWith(".")) {
                // It's a meta index. No need for aliases and such.
                if (!es_settings) {
                    es_settings = loadJson("es_settings.json")
                }
                performExecute(client.admin().indices().prepareCreate(indexName).setSettings(es_settings))
            } else {
                String currentIndex = createNewCurrentIndex(indexName)
                log.debug("Will create alias $indexName -> $currentIndex")
                performExecute(client.admin().indices().prepareAliases().addAlias(currentIndex, indexName))
            }
        } else if (getRealIndexFor(indexName) == null) {
            throw new WhelkRuntimeException("Unable to find a real current index for $indexName")
        }
    }

    String getRealIndexFor(String alias) {
        def aliases = performExecute(client.admin().cluster().prepareState()).state.metaData.aliases()
        log.debug("aliases: $aliases")
        def ri = null
        if (aliases.containsKey(alias)) {
            ri = aliases.get(alias)?.keys().iterator().next()
        }
        if (ri) {
            log.trace("ri: ${ri.value} (${ri.value.getClass().getName()})")
        }
        return (ri ? ri.value : alias)
    }

    void flush() {
        log.debug("Flushing indices.")
        def flushresponse = performExecute(new FlushRequestBuilder(client.admin().indices()))
        log.debug("Flush response: $flushresponse")
    }

    void index(final List<Map<String,String>> data) throws WhelkIndexException  {
        def breq = client.prepareBulk()
        for (entry in data) {
            breq.add(client.prepareIndex(entry['index'], entry['type'], entry['id']).setSource(entry['data'].getBytes("utf-8")))
        }
        def response = performExecute(breq)
        if (response.hasFailures()) {
            log.error "Bulk entry indexing has failures."
            def fails = []
            for (re in response.items) {
                if (re.failed) {
                    log.error "Fail message for id ${re.id}, type: ${re.type}, index: ${re.index}: ${re.failureMessage}"
                    try {
                        fails << translateIndexIdTo(re.id)
                    } catch (Exception e1) {
                        log.error("TranslateIndexIdTo cast an exception", e1)
                        fails << "Failed translation for \"$re\""
                    }
                }
            }
            throw new WhelkIndexException("Failed to index entries. Reason: ${response.buildFailureMessage()}", new WhelkAddException(fails))
        } else {
            log.debug("Direct bulk request completed in ${response.tookInMillis} millseconds.")
        }
    }

    void index(byte[] data, Map params) throws WhelkIndexException  {
        try {
            def response = performExecute(client.prepareIndex(params['index'], params['type'], params['id']).setSource(data))
            log.debug("Raw byte indexer (${params.index}/${params.type}/${params.id}) indexed version: ${response.version}")
        } catch (Exception e) {
            throw new WhelkIndexException("Failed to index ${new String(data)} with params $params", e)
        }
    }

    void checkTypeMapping(indexName, indexType) {
        log.debug("Checking mappings for index $indexName, type $indexType")
        def mappings = performExecute(client.admin().cluster().prepareState()).state.metaData.index(indexName).getMappings()
        log.trace("Mappings: $mappings")
        if (!mappings.containsKey(indexType)) {
            log.debug("Mapping for $indexName/$indexType does not exist. Creating ...")
            setTypeMapping(indexName, indexType)
        }
    }


}