package com.versiondb.query.exec;

import com.versiondb.query.ast.OrderByItem;
import com.versiondb.shared.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Sort operator ({@code part2.md} §6.10). Blocking: {@code open()} reads the whole
 * child input into memory and sorts it by the {@code orderBy} keys (NULLs sort
 * last in either direction); {@code next()} then yields rows in order. In-memory
 * sort is sufficient for the capstone.
 */
public final class SortOperator implements Operator {

    private final Operator child;
    private final List<OrderByItem> orderBy;
    private final List<Tuple> buffer = new ArrayList<>();
    private int index;

    public SortOperator(Operator child, List<OrderByItem> orderBy) {
        this.child = child;
        this.orderBy = List.copyOf(orderBy);
    }

    @Override
    public void open() {
        child.open();
        try {
            Tuple t;
            while ((t = child.next()) != null) {
                buffer.add(t);
            }
        } finally {
            child.close();
        }
        buffer.sort(this::compareRows);
        index = 0;
    }

    @Override
    public Tuple next() {
        return index < buffer.size() ? buffer.get(index++) : null;
    }

    @Override
    public void close() {
        buffer.clear();
    }

    private int compareRows(Tuple a, Tuple b) {
        for (OrderByItem item : orderBy) {
            Value va = ExpressionEvaluator.eval(item.expr(), a);
            Value vb = ExpressionEvaluator.eval(item.expr(), b);
            int c;
            if (va == null && vb == null) {
                c = 0;
            } else if (va == null) {
                c = 1;  // nulls last
            } else if (vb == null) {
                c = -1;
            } else {
                c = ExpressionEvaluator.compare(va, vb);
                if (!item.ascending()) {
                    c = -c;
                }
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
