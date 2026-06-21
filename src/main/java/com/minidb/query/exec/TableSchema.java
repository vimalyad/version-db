package com.minidb.query.exec;

import com.minidb.shared.ColumnMeta;
import com.minidb.shared.ColumnType;
import com.minidb.shared.StorageException;
import com.minidb.shared.TableMeta;
import com.minidb.storage.Catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * A table's column layout, resolved from the catalog: the column names and types
 * in storage order, used to decode/encode heap tuples and to label runtime
 * {@link Tuple}s.
 */
final class TableSchema {

    final int tableId;
    final String tableName;
    final List<String> columnNames;
    final List<ColumnType> columnTypes;

    private TableSchema(int tableId, String tableName, List<String> names, List<ColumnType> types) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.columnNames = names;
        this.columnTypes = types;
    }

    static TableSchema load(Catalog catalog, String tableName) {
        TableMeta meta = catalog.getTable(tableName);
        if (meta == null) {
            throw new StorageException("unknown table " + tableName);
        }
        List<String> names = new ArrayList<>();
        List<ColumnType> types = new ArrayList<>();
        for (ColumnMeta c : catalog.getColumns(meta.tableId())) { // already in column-index order
            names.add(c.columnName());
            types.add(c.columnType());
        }
        return new TableSchema(meta.tableId(), tableName, names, types);
    }

    int indexOf(String column) {
        return columnNames.indexOf(column);
    }
}
