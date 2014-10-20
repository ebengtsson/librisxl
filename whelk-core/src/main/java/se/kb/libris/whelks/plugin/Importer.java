package se.kb.libris.whelks.plugin;

import java.util.List;

public interface Importer extends WhelkAware {
    int doImport(String dataset, int maxNrOfDocsToImport);
    public void cancel();
}

