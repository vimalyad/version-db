package com.versiondb.query.ast;

import java.util.Objects;

/**
 * One item in a SELECT projection list: an expression with an optional output
 * alias. {@code SELECT age AS years} → {@code SelectItem(ColumnRef(age), "years")};
 * {@code SELECT *} → {@code SelectItem(Star, null)}.
 */
public record SelectItem(Expression expr, String alias) {

    public SelectItem {
        Objects.requireNonNull(expr, "expr");
    }

    public static SelectItem of(Expression expr) {
        return new SelectItem(expr, null);
    }

    public boolean hasAlias() {
        return alias != null;
    }
}
