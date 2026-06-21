package com.versiondb.query.ast;

import java.util.Objects;

/**
 * A DELETE statement: a target table and an optional WHERE predicate. A
 * {@code null} {@code where} means delete every row.
 */
public record DeleteStatement(String table, Expression where) implements Statement {

    public DeleteStatement {
        Objects.requireNonNull(table, "table");
    }
}
