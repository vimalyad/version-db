package com.minidb.query.ast;

import java.util.Objects;

/**
 * One {@code column = value} assignment in an UPDATE ... SET clause.
 */
public record Assignment(String column, Expression value) {

    public Assignment {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(value, "value");
    }
}
