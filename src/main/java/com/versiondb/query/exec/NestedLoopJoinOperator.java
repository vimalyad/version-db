package com.versiondb.query.exec;

import com.versiondb.query.ast.Expression;

/**
 * Nested-loop join ({@code part2.md} §6.8): for each outer row, rescan the entire
 * inner input and emit merged rows that satisfy the join condition. The inner
 * operator is closed and reopened once per outer row. A {@code null} condition is
 * a cross join.
 */
public final class NestedLoopJoinOperator implements Operator {

    private final Operator outer;
    private final Operator inner;
    private final Expression condition;

    private Tuple outerTuple;
    private boolean innerOpen;

    public NestedLoopJoinOperator(Operator outer, Operator inner, Expression condition) {
        this.outer = outer;
        this.inner = inner;
        this.condition = condition;
    }

    @Override
    public void open() {
        outer.open();
        outerTuple = outer.next();
        inner.open();
        innerOpen = true;
    }

    @Override
    public Tuple next() {
        while (outerTuple != null) {
            Tuple innerTuple = inner.next();
            if (innerTuple != null) {
                Tuple merged = Tuple.merge(outerTuple, innerTuple);
                if (condition == null || ExpressionEvaluator.test(condition, merged)) {
                    return merged;
                }
                continue;
            }
            // Inner exhausted for this outer row: advance outer, reset inner.
            inner.close();
            innerOpen = false;
            outerTuple = outer.next();
            if (outerTuple == null) {
                return null;
            }
            inner.open();
            innerOpen = true;
        }
        return null;
    }

    @Override
    public void close() {
        if (innerOpen) {
            inner.close();
            innerOpen = false;
        }
        outer.close();
    }
}
