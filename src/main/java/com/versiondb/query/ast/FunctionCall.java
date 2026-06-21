package com.versiondb.query.ast;

import java.util.List;
import java.util.Objects;

/**
 * A function application such as an aggregate: {@code COUNT(*)}, {@code SUM(x)},
 * {@code AVG(price)}. {@code name} is the function's spelling as written;
 * {@code args} is its argument list (a single {@link Star} for {@code COUNT(*)}).
 */
public record FunctionCall(String name, List<Expression> args) implements Expression {

    public FunctionCall {
        Objects.requireNonNull(name, "name");
        args = List.copyOf(args); // defensive, unmodifiable
    }
}
