package com.versiondb.query.ast;

import java.util.Objects;

/**
 * Two from-items joined by a {@link JoinType} with an {@code ON} condition.
 * {@code on} is {@code null} for a {@link JoinType#CROSS} join (which has no
 * condition) and non-null otherwise.
 */
public record JoinExpr(FromItem left, FromItem right, JoinType type, Expression on)
        implements FromItem {

    public JoinExpr {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(type, "type");
    }
}
