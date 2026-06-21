package com.versiondb.query.ast;

import java.util.Objects;

/**
 * A CREATE INDEX statement: the index name, the table it is built on, and the
 * single column it indexes.
 */
public record CreateIndexStatement(String indexName, String table, String column)
        implements Statement {

    public CreateIndexStatement {
        Objects.requireNonNull(indexName, "indexName");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
    }
}
