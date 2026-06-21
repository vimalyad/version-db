package com.versiondb.query.ast;

import java.util.Objects;

/**
 * A single table in a FROM clause, with an optional alias: {@code users u} or
 * {@code users AS u} → {@code TableRef("users", "u")}; a bare {@code users} →
 * {@code TableRef("users", null)}.
 */
public record TableRef(String table, String alias) implements FromItem {

    public TableRef {
        Objects.requireNonNull(table, "table");
    }

    /** A table with no alias. */
    public static TableRef of(String table) {
        return new TableRef(table, null);
    }

    public boolean hasAlias() {
        return alias != null;
    }
}
