package com.versiondb.query;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.Expression;
import com.versiondb.query.ast.Literal;
import com.versiondb.shared.ColumnStats;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.TableStats;
import com.versiondb.shared.Value;

/**
 * Estimates the selectivity of a predicate — the fraction of rows it passes,
 * in {@code [0, 1]} — from catalog column statistics ({@code part2.md} §5.4).
 * The optimizer multiplies selectivity by a table's row count to estimate how
 * many rows an operator produces.
 *
 * <p>Compound predicates assume column independence: {@code A AND B} →
 * {@code sel(A)·sel(B)}, {@code A OR B} → {@code sel(A)+sel(B)-sel(A)·sel(B)}.
 * Anything the estimator cannot reason about (unknown column, non-comparison,
 * NOT, column-to-column comparison) falls back to {@link #DEFAULT}.
 */
public final class SelectivityEstimator {

    /** Conservative default when a predicate cannot be estimated. */
    public static final double DEFAULT = 0.5;

    private final TableStats stats;

    public SelectivityEstimator(TableStats stats) {
        this.stats = stats;
    }

    /** Selectivity of {@code predicate}; a {@code null} predicate passes everything (1.0). */
    public double estimate(Expression predicate) {
        if (predicate == null) {
            return 1.0;
        }
        if (predicate instanceof BinaryOp b) {
            return switch (b.op()) {
                case "AND" -> estimate(b.left()) * estimate(b.right());
                case "OR" -> {
                    double l = estimate(b.left());
                    double r = estimate(b.right());
                    yield l + r - l * r;
                }
                case "=", "!=", "<", ">", "<=", ">=" -> comparison(b);
                default -> DEFAULT;
            };
        }
        return DEFAULT;
    }

    /** Selectivity of a {@code column <op> literal} comparison (either operand order). */
    private double comparison(BinaryOp b) {
        ColumnRef col;
        Literal lit;
        String op = b.op();
        if (b.left() instanceof ColumnRef c && b.right() instanceof Literal l) {
            col = c;
            lit = l;
        } else if (b.left() instanceof Literal l && b.right() instanceof ColumnRef c) {
            col = c;
            lit = l;
            op = flip(op); // normalise to column-on-left
        } else {
            return DEFAULT; // not a column-vs-literal comparison
        }
        if (lit.isNull()) {
            return DEFAULT;
        }

        ColumnStats cs = stats.columnStats().get(col.column());
        if (cs == null) {
            return DEFAULT;
        }

        return switch (op) {
            case "=" -> cs.numDistinct() > 0 ? 1.0 / cs.numDistinct() : DEFAULT;
            case "!=" -> cs.numDistinct() > 0 ? 1.0 - 1.0 / cs.numDistinct() : DEFAULT;
            default -> rangeSelectivity(op, cs, lit.value());
        };
    }

    private double rangeSelectivity(String op, ColumnStats cs, Value val) {
        if (!numeric(val) || !numeric(cs.minValue()) || !numeric(cs.maxValue())) {
            return DEFAULT;
        }
        double v = toDouble(val);
        double min = toDouble(cs.minValue());
        double max = toDouble(cs.maxValue());
        double span = max - min;
        if (span <= 0) {
            return DEFAULT;
        }
        double sel = switch (op) {
            case ">" -> (max - v) / span;
            case ">=" -> (max - v + 1) / span;
            case "<" -> (v - min) / span;
            case "<=" -> (v - min + 1) / span;
            default -> DEFAULT;
        };
        return clamp(sel);
    }

    private static String flip(String op) {
        return switch (op) {
            case ">" -> "<";
            case ">=" -> "<=";
            case "<" -> ">";
            case "<=" -> ">=";
            default -> op; // = and != are symmetric
        };
    }

    private static boolean numeric(Value v) {
        return v != null && !v.isNull() && (v.type() == ColumnType.INT || v.type() == ColumnType.FLOAT);
    }

    private static double toDouble(Value v) {
        return v.type() == ColumnType.INT ? (double) v.asInt() : v.asFloat();
    }

    private static double clamp(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
