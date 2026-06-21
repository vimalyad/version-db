package com.versiondb.query.exec;

import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.SelectItem;
import com.versiondb.query.ast.Star;
import com.versiondb.shared.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Reduces each input row to the requested output columns/expressions
 * ({@code part2.md} §6.7). Each output column's name is its alias, else the
 * referenced column name, else a generated {@code colN}; a {@code *} item
 * expands to all of the input's columns.
 */
public final class ProjectionOperator implements Operator {

    private final Operator child;
    private final List<SelectItem> projections;

    public ProjectionOperator(Operator child, List<SelectItem> projections) {
        this.child = child;
        this.projections = List.copyOf(projections);
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Tuple next() {
        Tuple in = child.next();
        if (in == null) {
            return null;
        }
        List<String> tables = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < projections.size(); i++) {
            SelectItem item = projections.get(i);
            if (item.expr() instanceof Star) {
                for (int c = 0; c < in.size(); c++) {
                    tables.add(in.table(c));
                    columns.add(in.column(c));
                    values.add(in.value(c));
                }
                continue;
            }
            values.add(ExpressionEvaluator.eval(item.expr(), in));
            if (item.alias() != null) {
                columns.add(item.alias());
                tables.add(null);
            } else if (item.expr() instanceof ColumnRef ref) {
                columns.add(ref.column());
                tables.add(ref.table());
            } else {
                columns.add("col" + (i + 1));
                tables.add(null);
            }
        }
        return new Tuple(tables, columns, values);
    }

    @Override
    public void close() {
        child.close();
    }
}
