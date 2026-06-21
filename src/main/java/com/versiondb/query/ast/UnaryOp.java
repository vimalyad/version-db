package com.versiondb.query.ast;

import java.util.Objects;

/**
 * A unary operation: {@code op operand}. Covers logical {@code NOT} and numeric
 * negation ({@code -}).
 */
public record UnaryOp(String op, Expression operand) implements Expression {

    public UnaryOp {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(operand, "operand");
    }
}
