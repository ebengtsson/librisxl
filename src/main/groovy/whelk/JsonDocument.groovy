package whelk

import groovy.util.logging.Slf4j as Log

import org.codehaus.jackson.map.*
import org.codehaus.jackson.annotate.JsonIgnore

import whelk.exception.*
import whelk.util.Tools

@Log
class JsonDocument extends DefaultDocument {

    // store serialized data
    @JsonIgnore
    protected Map serializedDataInMap

    JsonDocument fromDocument(Document otherDocument) {
        setEntry(otherDocument.getEntry())
        setMeta(otherDocument.getMeta())
        setData(otherDocument.getData())
        return this
    }

    void setContentType(String ctype) {
        if (!(ctype ==~ /application\/(\w+\+)*json/ || ctype ==~ /application\/x-(\w+)-json/)) {
            throw new DocumentException("JSON document can't have content-type $ctype")
        }
        super.setContentType(ctype)
    }

    @JsonIgnore
    boolean isJson() { true }

    JsonDocument withData(Map dataMap) {
        return withData(mapper.writeValueAsBytes(dataMap))
    }

    void setData(byte[] data) {
        this.serializedDataInMap = null
        super.setData(data)
    }

    @JsonIgnore
    Map getDataAsMap() {
        if (!serializedDataInMap) {
            log.trace("Serializing data as map")
            this.serializedDataInMap = mapper.readValue(new String(getData(), "UTF-8"), Map)
        }
        return serializedDataInMap
    }
}