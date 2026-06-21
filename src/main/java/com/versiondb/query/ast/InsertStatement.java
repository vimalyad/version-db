package com.versiondb.query.ast;

import java.util.List;
import java.util.Objects;

/**
 * An INSERT statement: a target table, an optional explicit column list, and one
 * or more rows of value expressions.
 *
 * @param table   the target table name
 * @param columns the explicit column list, or empty if the statement omits it
 *                (meaning "all columns, in table order")
 * @param rows    the VALUES rows; each inner list is one row of expressions and
 *                must match the column count
 */
public record InsertStatement(String table, List<String> columns, List<List<Expression>> rows)
        implements Statement {

    public InsertStatement {
        Objects.requireNonNull(table, "table");
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    /** Whether an explicit column list was given. */
    public boolean hasColumnList() {
        return !columns.isEmpty();
    }
}
