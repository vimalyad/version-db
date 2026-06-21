package com.versiondb.query.exec;

import com.versiondb.shared.Value;

import java.util.List;

/**
 * The outcome of executing a statement: for a query, the output column names and
 * rows; for a write/DDL statement, the number of affected rows (with no result
 * rows).
 */
public final class ResultSet {

    private final List<String> columns;
    private final List<List<Value>> rows;
    private final int affectedRows;

    private ResultSet(List<String> columns, List<List<Value>> rows, int affectedRows) {
        this.columns = columns;
        this.rows = rows;
        this.affectedRows = affectedRows;
    }

    /** A query result with column headers and rows. */
    public static ResultSet of(List<String> columns, List<List<Value>> rows) {
        return new ResultSet(List.copyOf(columns), List.copyOf(rows), rows.size());
    }

    /** A write/DDL result reporting how many rows were affected. */
    public static ResultSet affected(int n) {
        return new ResultSet(List.of(), List.of(), n);
    }

    public List<String> columns() {
        return columns;
    }

    public List<List<Value>> rows() {
        return rows;
    }

    public int affectedRows() {
        return affectedRows;
    }

    public int size() {
        return rows.size();
    }
}
