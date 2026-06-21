package com.versiondb.query.ast;

import java.util.Objects;

/**
 * A DROP TABLE statement: the name of the table to remove.
 */
public record DropTableStatement(String table) implements Statement {

    public DropTableStatement {
        Objects.requireNonNull(table, "table");
    }
}
