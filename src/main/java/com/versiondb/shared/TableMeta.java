package com.versiondb.shared;

import java.util.Objects;

/**
 * Catalog metadata for one table: its id, name, the first page of its heap
 * file's page chain, and cached row/page counts.
 */
public record TableMeta(int tableId, String tableName, int firstPageId, long numTuples, int numPages) {

    public TableMeta {
        Objects.requireNonNull(tableName, "tableName");
    }
}
