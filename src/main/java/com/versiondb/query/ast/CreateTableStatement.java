package com.versiondb.query.ast;

import com.versiondb.shared.ColumnDef;

import java.util.List;
import java.util.Objects;

/**
 * A CREATE TABLE statement: a table name and its ordered column definitions
 * (reusing the shared {@link ColumnDef}).
 */
public record CreateTableStatement(String table, List<ColumnDef> columns) implements Statement {

    public CreateTableStatement {
        Objects.requireNonNull(table, "table");
        columns = List.copyOf(columns);
    }
}
