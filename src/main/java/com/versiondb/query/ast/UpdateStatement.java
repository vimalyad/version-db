package com.versiondb.query.ast;

import java.util.List;
import java.util.Objects;

/**
 * An UPDATE statement: a target table, one or more {@code column = value}
 * assignments, and an optional WHERE predicate. A {@code null} {@code where}
 * means update every row.
 */
public record UpdateStatement(String table, List<Assignment> assignments, Expression where)
        implements Statement {

    public UpdateStatement {
        Objects.requireNonNull(table, "table");
        assignments = List.copyOf(assignments);
    }
}
