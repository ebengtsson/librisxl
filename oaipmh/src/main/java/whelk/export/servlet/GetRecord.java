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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import io.prometheus.client.Counter;

public class GetRecord
{
    private final static String IDENTIFIER_PARAM = "identifier";
    private final static String FORMAT_PARAM = "metadataPrefix";

    private static final Counter failedRequests = Counter.build()
            .name("oaipmh_failed_getrecord_requests_total").help("Total failed GetRecord requests.")
            .labelNames("error").register();

    /**
     * Verifies the integrity of a OAI-PMH request with the verb 'GetRecord' and sends a proper response.
     */
    public static void handleGetRecordRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException, XMLStreamException, SQLException
    {
        // Parse and verify the parameters allowed for this request
        String identifierUri = request.getParameter(IDENTIFIER_PARAM); // required
        String metadataPrefix = request.getParameter(FORMAT_PARAM); // required

        if (ResponseCommon.errorOnExtraParameters(request, response, IDENTIFIER_PARAM, FORMAT_PARAM))
            return;

        if (metadataPrefix == null)
        {
            failedRequests.labels(OaiPmh.OAIPMH_ERROR_BAD_ARGUMENT).inc();
            ResponseCommon.sendOaiPmhError(OaiPmh.OAIPMH_ERROR_BAD_ARGUMENT,
                    "metadataPrefix argument required.", request, response);
            return;
        }

        if (!OaiPmh.supportedFormats.keySet().contains(metadataPrefix))
        {
            failedRequests.labels(OaiPmh.OAIPMH_ERROR_CANNOT_DISSEMINATE_FORMAT).inc();
            ResponseCommon.sendOaiPmhError(OaiPmh.OAIPMH_ERROR_CANNOT_DISSEMINATE_FORMAT, "Unsupported format: " + metadataPrefix,
                    request, response);
            return;
        }

        if (identifierUri == null)
        {
            failedRequests.labels(OaiPmh.OAIPMH_ERROR_BAD_ARGUMENT).inc();
            ResponseCommon.sendOaiPmhError(OaiPmh.OAIPMH_ERROR_BAD_ARGUMENT,
                    "identifier argument required.", request, response);
            return;
        }

        String id = null;
        try (Connection dbconn = OaiPmh.s_postgreSqlComponent.getConnection();
             PreparedStatement preparedStatement = Helpers.prepareSameAsStatement(dbconn, identifierUri);
             ResultSet resultSet = preparedStatement.executeQuery())
        {
            if (resultSet.next())
                id = resultSet.getString("id");
        }
        if (id == null)
        {
            failedRequests.labels(OaiPmh.OAIPMH_ERROR_NO_RECORDS_MATCH).inc();
            ResponseCommon.sendOaiPmhError(OaiPmh.OAIPMH_ERROR_NO_RECORDS_MATCH, "", request, response);
            return;
        }

        try (Connection dbconn = OaiPmh.s_postgreSqlComponent.getConnection())
        {
            dbconn.setAutoCommit(false);
            try (PreparedStatement preparedStatement = Helpers.getMatchingDocumentsStatement(dbconn, null, null, null, id, false);
                 ResultSet resultSet = preparedStatement.executeQuery())
            {
                if (!resultSet.next())
                {
                    failedRequests.labels(OaiPmh.OAIPMH_ERROR_NO_RECORDS_MATCH).inc();
                    ResponseCommon.sendOaiPmhError(OaiPmh.OAIPMH_ERROR_NO_RECORDS_MATCH, "", request, response);
                    return;
                }

                // Build the xml response feed
                XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newInstance();
                xmlOutputFactory.setProperty("escapeCharacters", false); // Inline xml must be left untouched.
                XMLStreamWriter writer = xmlOutputFactory.createXMLStreamWriter(response.getOutputStream());

                ResponseCommon.writeOaiPmhHeader(writer, request, true);
                writer.writeStartElement("GetRecord");

                ResponseCommon.emitRecord(resultSet, writer, metadataPrefix, false, metadataPrefix.contains(OaiPmh.FORMAT_EXPANDED_POSTFIX));

                writer.writeEndElement(); // GetRecord
                ResponseCommon.writeOaiPmhClose(writer, request);
            } finally {
                dbconn.commit();
            }
        }
    }
}