package com.versiondb.shared;

import java.util.Objects;

/**
 * Catalog metadata for one column of a table: which table it belongs to, its
 * name and type, its zero-based position in a row, and whether it is nullable.
 */
public record ColumnMeta(int tableId, String columnName, ColumnType columnType, int columnIndex,
        boolean isNullable) {

    public ColumnMeta {
        Objects.requireNonNull(columnName, "columnName");
        Objects.requireNonNull(columnType, "columnType");
    }
}
