package com.versiondb.query.exec;

import com.versiondb.shared.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A runtime row flowing through the operator pipeline: an ordered list of
 * columns, each tagged with its source table (or alias) so that joined rows can
 * disambiguate same-named columns. Values are looked up by column name, with the
 * table qualifier used as a tiebreaker.
 *
 * <p>Column resolution ({@code part2.md} §6.13): an unqualified reference matches
 * the first column with that name; a qualified reference prefers an exact
 * table+column match but falls back to a name-only match (so an alias the plan
 * did not carry still resolves when names are unambiguous).
 */
public final class Tuple {

    private final List<String> tables;   // source table/alias per column (may be null)
    private final List<String> columns;  // column name per column
    private final List<Value> values;

    public Tuple(List<String> tables, List<String> columns, List<Value> values) {
        if (tables.size() != columns.size() || columns.size() != values.size()) {
            throw new IllegalArgumentException("tuple tables/columns/values length mismatch");
        }
        // Defensive copies via ArrayList (not List.copyOf): a column's table tag
        // may be null (computed/aliased columns) and values may be SQL NULL.
        this.tables = new ArrayList<>(tables);
        this.columns = new ArrayList<>(columns);
        this.values = new ArrayList<>(values);
    }

    public int size() {
        return values.size();
    }

    public Value value(int i) {
        return values.get(i);
    }

    public String column(int i) {
        return columns.get(i);
    }

    public String table(int i) {
        return tables.get(i);
    }

    public List<String> columnNames() {
        return columns;
    }

    public List<Value> values() {
        // unmodifiable, but null-tolerant (a column's value may be SQL NULL).
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * Resolve a column reference to its value. {@code table} may be {@code null}
     * for an unqualified reference.
     *
     * @throws IllegalArgumentException if no column matches
     */
    public Value get(String table, String column) {
        int nameOnly = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (!columns.get(i).equals(column)) {
                continue;
            }
            if (table == null || table.equals(tables.get(i))) {
                return values.get(i);
            }
            if (nameOnly == -1) {
                nameOnly = i; // qualifier did not match, but the name did
            }
        }
        if (nameOnly != -1) {
            return values.get(nameOnly);
        }
        throw new IllegalArgumentException(
                "unknown column " + (table == null ? column : table + "." + column));
    }

    /** Concatenate two tuples (used by joins to build a merged row). */
    public static Tuple merge(Tuple left, Tuple right) {
        List<String> tables = new ArrayList<>(left.tables);
        tables.addAll(right.tables);
        List<String> columns = new ArrayList<>(left.columns);
        columns.addAll(right.columns);
        List<Value> values = new ArrayList<>(left.values);
        values.addAll(right.values);
        return new Tuple(tables, columns, values);
    }
}
