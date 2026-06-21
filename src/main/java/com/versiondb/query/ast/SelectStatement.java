package com.versiondb.query.ast;

import java.util.List;
import java.util.Objects;

/**
 * A SELECT query: a projection list, a FROM clause, and optional WHERE, ORDER
 * BY, LIMIT, and OFFSET.
 *
 * @param projections the select list (at least one item); a single
 *                    {@link Star} projection represents {@code SELECT *}
 * @param from        the FROM clause (a {@link TableRef} or {@link JoinExpr})
 * @param where       the WHERE predicate, or {@code null} if absent
 * @param orderBy     the ORDER BY keys (empty if absent)
 * @param limit       the LIMIT count, or {@code null} if absent
 * @param offset      the OFFSET count, or {@code null} if absent
 */
public record SelectStatement(List<SelectItem> projections, FromItem from, Expression where,
                              List<OrderByItem> orderBy, Integer limit, Integer offset)
        implements Statement {

    public SelectStatement {
        projections = List.copyOf(projections);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        Objects.requireNonNull(from, "from");
    }
}
