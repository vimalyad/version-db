package com.versiondb.query.exec;

import com.versiondb.query.ast.Expression;

/**
 * Passes through only the rows of its child that satisfy {@code predicate}
 * ({@code part2.md} §6.6). Most filters are pushed into scans by the optimizer;
 * this stands alone for predicates that cannot be (e.g. above a join).
 */
public final class FilterOperator implements Operator {

    private final Operator child;
    private final Expression predicate;

    public FilterOperator(Operator child, Expression predicate) {
        this.child = child;
        this.predicate = predicate;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Tuple next() {
        Tuple t;
        while ((t = child.next()) != null) {
            if (ExpressionEvaluator.test(predicate, t)) {
                return t;
            }
        }
        return null;
    }

    @Override
    public void close() {
        child.close();
    }
}
