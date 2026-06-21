package com.versiondb.query.ast;

/**
 * Marker interface for anything that produces a value: {@link ColumnRef},
 * {@link Literal}, {@link BinaryOp}, {@link UnaryOp}, {@link FunctionCall}, and
 * {@link Star}. All expression nodes are immutable.
 */
public interface Expression {
}
