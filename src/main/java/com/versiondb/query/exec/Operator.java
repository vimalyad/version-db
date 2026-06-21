package com.versiondb.query.exec;

/**
 * A node in a running query plan, following the Volcano / iterator model
 * ({@code part2.md} §6.2): {@link #open()} once, {@link #next()} repeatedly until
 * it returns {@code null}, then {@link #close()}. Rows are pipelined — a tuple
 * produced by a leaf flows up through every parent without being materialized,
 * so memory is O(1) per operator (except blocking operators like sort/hash-build
 * that must buffer their input).
 *
 * <p>The execution context is supplied to each operator at construction time, so
 * {@code open()} takes no argument.
 */
public interface Operator {

    /** Initialize this operator and its children. */
    void open();

    /** The next result row, or {@code null} when the operator is exhausted. */
    Tuple next();

    /** Release resources and close children. Safe to call after exhaustion. */
    void close();
}
