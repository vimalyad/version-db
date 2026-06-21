package com.versiondb.query.ast;

import java.util.Objects;

/**
 * A binary operation: {@code left op right}. The {@code op} string is the SQL
 * spelling of the operator — a comparison ({@code =}, {@code !=}, {@code <},
 * {@code >}, {@code <=}, {@code >=}), a logical connector ({@code AND},
 * {@code OR}), or arithmetic ({@code +}, {@code -}, {@code *}, {@code /}).
 */
public record BinaryOp(Expression left, String op, Expression right) implements Expression {

    public BinaryOp {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(right, "right");
    }
}
