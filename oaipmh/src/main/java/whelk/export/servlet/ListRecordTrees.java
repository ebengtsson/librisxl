package whelk.export.servlet;

import org.codehaus.jackson.map.ObjectMapper;
import whelk.Document;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.sql.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;


public class ListRecordTrees
{
    // The ModificationTimes class is used as a crutch to simulate "pass by reference"-mechanics. The point of this is that (a pointer to)
    // an instance of ModificationTimes is passed around in the tree building process, being _updated_ (which a ZonedDateTime cannot be)
    // with each documents created-timestamp.
    public static class ModificationTimes
    {
        public ZonedDateTime earliestModification;
        public ZonedDateTime latestModification;
    }

    /**
     * Sends a response to a ListRecords (or ListIdentifiers) request, with a metadataPrefix tagged with :expanded
     * (meaning a tree must be built for each record, containing all other nodes linked to by that record)
     */
    public static void respond(HttpServletRequest request, HttpServletResponse response,
                                ZonedDateTime fromDateTime, ZonedDateTime untilDateTime, SetSpec setSpec,
                                String requestedFormat, boolean onlyIdentifiers)
            throws IOException, XMLStreamException, SQLException
    {
        String tableName = OaiPmh.configuration.getProperty("sqlMaintable");

        // First connection, used for iterating over the requested root nodes. ID only.
        try (Connection firstConn = DataBase.getConnection()) {

            // Construct the query
            String selectSQL = "SELECT id, manifest, deleted, modified, data#>'{@graph,1,heldBy,notation}' AS sigel FROM "
                    + tableName + " WHERE TRUE ";
            if (setSpec.getRootSet() != null)
                selectSQL += " AND manifest->>'collection' = ?";
            if (setSpec.getSubset() != null)
                selectSQL += " AND data @> '{\"@graph\":[{\"heldBy\": {\"@type\": \"Organization\", \"notation\": \"" +
                        Helpers.scrubSQL(setSpec.getSubset()) + "\"}}]}' ";

            PreparedStatement preparedStatement = firstConn.prepareStatement(selectSQL);
            preparedStatement.setFetchSize(512);

            // Assign parameters
            if (setSpec.getRootSet() != null)
                preparedStatement.setString(1, setSpec.getRootSet());

            ResultSet resultSet = preparedStatement.executeQuery();

            // Build the xml response feed
            XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newInstance();
            xmlOutputFactory.setProperty("escapeCharacters", false); // Inline xml must be left untouched.
            XMLStreamWriter writer = null;

            boolean xmlIntroWritten = false;

            while (resultSet.next())
            {
                List<String> nodeDatas = new LinkedList<String>();
                HashSet<String> visitedIDs = new HashSet<String>();

                String id = resultSet.getString("id");

                ZonedDateTime modified = ZonedDateTime.ofInstant(resultSet.getTimestamp("modified").toInstant(), ZoneOffset.UTC);
                ModificationTimes modificationTimes = new ModificationTimes();
                modificationTimes.earliestModification = modified;
                modificationTimes.latestModification = modified;

                // Use a second db connection for the embedding process
                try (Connection secondConn = DataBase.getConnection()) {
                    addNodeAndSubnodesToTree(id, visitedIDs, secondConn, nodeDatas, modificationTimes);
                }

                if (fromDateTime != null && fromDateTime.compareTo(modificationTimes.latestModification) > 0)
                    continue;
                if (untilDateTime != null && untilDateTime.compareTo(modificationTimes.earliestModification) < 0)
                    continue;

                // Do not begin writing to the response until at least one record has passed all checks. We might still need to
                // send a "noRecordsMatch".
                if (!xmlIntroWritten)
                {
                    xmlIntroWritten = true;
                    writer = xmlOutputFactory.createXMLStreamWriter(response.getOutputStream());
                    ResponseCommon.writeOaiPmhHeader(writer, request, true);
                    writer.writeStartElement("ListRecords");
                }

                Document mergedDocument = mergeDocument(id, nodeDatas);

                emitRecord(resultSet, mergedDocument, modificationTimes, writer, requestedFormat, onlyIdentifiers);
            }
            resultSet.close();
            preparedStatement.close();

            if (xmlIntroWritten)
            {
                writer.writeEndElement(); // ListRecords
                ResponseCommon.writeOaiPmhClose(writer, request);
            }
            else
            {
                ResponseCommon.sendOaiPmhError(OaiPmh.OAIPMH_ERROR_NO_RECORDS_MATCH, "", request, response);
            }
        }
    }

    /**
     * Called recursively to gather the nodes that will make up a tree. The 'data' portion of every concerned record
     * will be added to the nodeDatas list.
     */
    public static void addNodeAndSubnodesToTree(String id, Set<String> visitedIDs, Connection connection,
                                                 List<String> nodeDatas, ModificationTimes modificationTimes)
            throws SQLException, IOException
    {
        if (visitedIDs.contains(id))
            return;
        visitedIDs.add(id);

        String tableName = OaiPmh.configuration.getProperty("sqlMaintable");
        String selectSQL = "SELECT id, data, modified, manifest->>'collection' as collection FROM " + tableName + " WHERE id = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(selectSQL);
        preparedStatement.setString(1, id);
        ResultSet resultSet = preparedStatement.executeQuery();
        if (!resultSet.next())
            return;

        // Only allow one level of recursive auth posts into the tree, or we'll end up adding half the database
        // into each tree.
        String collection = resultSet.getString("collection");
        if (collection.equals("auth"))
            return;

        ObjectMapper mapper = new ObjectMapper();
        String jsonBlob = resultSet.getString("data");
        nodeDatas.add(jsonBlob);
        ZonedDateTime modified = ZonedDateTime.ofInstant(resultSet.getTimestamp("modified").toInstant(), ZoneOffset.UTC);

        if (modified.compareTo(modificationTimes.earliestModification) < 0)
            modificationTimes.earliestModification = modified;
        if (modified.compareTo(modificationTimes.latestModification) > 0)
            modificationTimes.latestModification = modified;

        Map map = mapper.readValue(jsonBlob, HashMap.class);
        resultSet.close();
        preparedStatement.close();
        parseMap(map, visitedIDs, connection, nodeDatas, modificationTimes);
    }

    @SuppressWarnings("unchecked")
    public static Document mergeDocument(String id, List<String> nodeDatas)
            throws IOException
    {
        ObjectMapper mapper = new ObjectMapper();

        // One element in the list is guaranteed.
        String rootData = nodeDatas.get(0);
        Map rootMap = mapper.readValue(rootData, HashMap.class);
        List mergedGraph = (List) rootMap.get("@graph");

        for (int i = 1; i < nodeDatas.size(); ++i)
        {
            String nodeData = nodeDatas.get(i);
            Map nodeRootMap = mapper.readValue(nodeData, HashMap.class);
            List nodeGraph = (List) nodeRootMap.get("@graph");
            mergedGraph.addAll(nodeGraph);
        }

        rootMap.replace("@graph", mergedGraph);

        return new Document(id, rootMap);
    }

    private static void parseMap(Map map, Set<String> visitedIDs, Connection connection,
                                 List<String> nodeDatas, ModificationTimes modificationTimes)
            throws SQLException, IOException
    {
        for (Object key : map.keySet())
        {
            Object value = map.get(key);

            if (value instanceof Map)
                parseMap( (Map) value, visitedIDs, connection, nodeDatas, modificationTimes );
            else if (value instanceof List)
                parseList( (List) value, visitedIDs, connection, nodeDatas, modificationTimes );
            else
                parsePotentialId( key, value, visitedIDs, connection, nodeDatas, modificationTimes );
        }
    }

    private static void parseList(List list, Set<String> visitedIDs, Connection connection,
                                  List<String> nodeDatas, ModificationTimes modificationTimes)
            throws SQLException, IOException
    {
        for (Object item : list)
        {
            if (item instanceof Map)
                parseMap( (Map) item, visitedIDs, connection, nodeDatas, modificationTimes );
            else if (item instanceof List)
                parseList( (List) item, visitedIDs, connection, nodeDatas, modificationTimes );
        }
    }

    private static void parsePotentialId(Object key, Object value, Set<String> visitedIDs, Connection connection,
                                         List<String> nodeDatas, ModificationTimes modificationTimes)
            throws SQLException, IOException
    {
        if ( !(key instanceof String) || !(value instanceof String))
            return;

        if ( ! "@id".equals(key) )
            return;

        String potentialID = (String) value;
        if ( !potentialID.startsWith("http") )
            return;

        potentialID = potentialID.replace("resource/", "");

        String sql = "SELECT id FROM lddb__identifiers WHERE identifier = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, potentialID);
        ResultSet resultSet = preparedStatement.executeQuery();
        LinkedList<String> linkedIDs = new LinkedList<String>();
        if (resultSet.next())
        {
            String id = resultSet.getString("id");
            linkedIDs.add(id);
        }
        resultSet.close();
        preparedStatement.close();
        for (String id : linkedIDs)
            addNodeAndSubnodesToTree( id, visitedIDs, connection, nodeDatas, modificationTimes );
    }

    private static void emitRecord(ResultSet resultSet, Document mergedDocument, ModificationTimes modificationTimes,
                                   XMLStreamWriter writer, String requestedFormat, boolean onlyIdentifiers)
            throws SQLException, XMLStreamException, IOException
    {
        // The ResultSet refers only to the root document. mergedDocument represents the entire tree.

        ObjectMapper mapper = new ObjectMapper();
        String manifest = resultSet.getString("manifest");
        boolean deleted = resultSet.getBoolean("deleted");
        String sigel = resultSet.getString("sigel");
        HashMap manifestmap = mapper.readValue(manifest, HashMap.class);

        writer.writeStartElement("record");

        writer.writeStartElement("header");

        if (deleted)
            writer.writeAttribute("status", "deleted");

        writer.writeStartElement("identifier");
        writer.writeCharacters(mergedDocument.getURI().toString());
        writer.writeEndElement(); // identifier

        writer.writeStartElement("datestamp");
        //ZonedDateTime modified = ZonedDateTime.ofInstant(resultSet.getTimestamp("modified").toInstant(), ZoneOffset.UTC);
        writer.writeCharacters(modificationTimes.latestModification.toString());
        writer.writeEndElement(); // datestamp

        String dataset = (String) manifestmap.get("collection");
        if (dataset != null)
        {
            writer.writeStartElement("setSpec");
            writer.writeCharacters(dataset);
            writer.writeEndElement(); // setSpec
        }

        if (sigel != null)
        {
            writer.writeStartElement("setSpec");
            // Output sigel without quotation marks (").
            writer.writeCharacters(dataset + ":" + sigel.replace("\"", ""));
            writer.writeEndElement(); // setSpec
        }

        writer.writeEndElement(); // header

        if (!onlyIdentifiers && !deleted)
        {
            writer.writeStartElement("metadata");
            ResponseCommon.writeConvertedDocument(writer, requestedFormat, mergedDocument);
            writer.writeEndElement(); // metadata
        }

        writer.writeEndElement(); // record
    }
}