package com.versiondb.query.ast;

/**
 * A reference to a column, optionally qualified by a table name or alias:
 * {@code users.age} → {@code ColumnRef("users", "age")}, or just {@code age} →
 * {@code ColumnRef(null, "age")}.
 */
public record ColumnRef(String table, String column) implements Expression {

    /** An unqualified column reference. */
    public static ColumnRef of(String column) {
        return new ColumnRef(null, column);
    }

    public boolean isQualified() {
        return table != null;
    }
}
