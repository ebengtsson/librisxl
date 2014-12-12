package whelk.plugin.libris

import groovy.util.logging.Slf4j as Log

import whelk.*
import whelk.plugin.*

@Log
class CleanupIndexFormatConverter extends BasicFormatConverter {
    String requiredContentType = "application/ld+json"
    String resultContentType = "application/ld+json"

    Document doConvert(Document doc) {
        def docmap = doc.dataAsMap
        def ct = docmap.remove("@context")
        def broken = docmap.remove("broken")
        if (ct || broken) {
            // Only update document if there actually was a change
            doc.withData(docmap)
        }
        return doc
    }
}