package com.versiondb.query.exec;

import com.versiondb.query.ast.FunctionCall;
import com.versiondb.query.ast.SelectItem;
import com.versiondb.query.ast.Star;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal aggregation ({@code part2.md} §6.12): a blocking operator that consumes
 * all input and emits a single summary row. Supports {@code COUNT(*)} (count all
 * rows) and {@code COUNT(col)} (count rows where the column is non-NULL).
 * GROUP BY and other aggregates are out of scope.
 */
public final class AggregateOperator implements Operator {

    private final Operator child;
    private final List<SelectItem> projections;
    private boolean produced;

    public AggregateOperator(Operator child, List<SelectItem> projections) {
        this.child = child;
        this.projections = List.copyOf(projections);
    }

    /** Whether a projection list contains an aggregate function (so this operator applies). */
    public static boolean isAggregate(List<SelectItem> projections) {
        for (SelectItem item : projections) {
            if (item.expr() instanceof FunctionCall) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void open() {
        child.open();
        produced = false;
    }

    @Override
    public Tuple next() {
        if (produced) {
            return null;
        }
        produced = true;

        long[] counts = new long[projections.size()];
        boolean[] countAll = new boolean[projections.size()];
        validate();

        Tuple t;
        while ((t = child.next()) != null) {
            for (int i = 0; i < projections.size(); i++) {
                FunctionCall fn = (FunctionCall) projections.get(i).expr();
                if (fn.args().get(0) instanceof Star) {
                    counts[i]++;
                } else if (ExpressionEvaluator.eval(fn.args().get(0), t) != null) {
                    counts[i]++; // COUNT(col): non-null only
                }
            }
        }

        List<String> tables = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < projections.size(); i++) {
            tables.add(null);
            columns.add(projections.get(i).alias() != null ? projections.get(i).alias() : "count");
            values.add(Value.ofInt(counts[i]));
        }
        return new Tuple(tables, columns, values);
    }

    @Override
    public void close() {
        child.close();
    }

    private void validate() {
        for (SelectItem item : projections) {
            if (!(item.expr() instanceof FunctionCall fn)) {
                throw new StorageException("aggregate query: non-aggregate projection without GROUP BY");
            }
            if (!fn.name().equalsIgnoreCase("COUNT") || fn.args().size() != 1) {
                throw new StorageException("unsupported aggregate " + fn.name());
            }
        }
    }
}
