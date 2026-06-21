package com.versiondb.query.ast;

/**
 * Marker interface for the top-level result of parsing a single SQL statement.
 * Implemented by {@link SelectStatement}, {@link InsertStatement},
 * {@link DeleteStatement}, {@link UpdateStatement}, {@link CreateTableStatement},
 * {@link CreateIndexStatement}, and {@link DropTableStatement}. All AST nodes are
 * immutable.
 */
public interface Statement {
}
