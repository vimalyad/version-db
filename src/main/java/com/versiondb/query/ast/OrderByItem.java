package com.versiondb.query.ast;

import java.util.Objects;

/**
 * One sort key in an ORDER BY clause: an expression and a direction.
 * {@code ascending} is {@code true} for ASC (the default) and {@code false}
 * for DESC.
 */
public record OrderByItem(Expression expr, boolean ascending) {

    public OrderByItem {
        Objects.requireNonNull(expr, "expr");
    }
}
