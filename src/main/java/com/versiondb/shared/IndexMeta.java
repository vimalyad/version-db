package com.versiondb.shared;

import java.util.Objects;

/**
 * Catalog metadata for one index: its id, the table and column it covers, and
 * the page id of its B+Tree root.
 */
public record IndexMeta(int indexId, int tableId, String columnName, int rootPageId) {

    public IndexMeta {
        Objects.requireNonNull(columnName, "columnName");
    }
}
