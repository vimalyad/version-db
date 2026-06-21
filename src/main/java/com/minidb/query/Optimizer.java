package com.minidb.query;

import com.minidb.query.ast.BinaryOp;
import com.minidb.query.ast.ColumnRef;
import com.minidb.query.ast.DeleteStatement;
import com.minidb.query.ast.Expression;
import com.minidb.query.ast.FromItem;
import com.minidb.query.ast.Literal;
import com.minidb.query.ast.SelectItem;
import com.minidb.query.ast.SelectStatement;
import com.minidb.query.ast.Star;
import com.minidb.query.ast.Statement;
import com.minidb.query.ast.TableRef;
import com.minidb.query.ast.UpdateStatement;
import com.minidb.query.plan.IndexScanPlan;
import com.minidb.query.plan.LimitPlan;
import com.minidb.query.plan.PhysicalPlan;
import com.minidb.query.plan.ProjectionPlan;
import com.minidb.query.plan.SeqScanPlan;
import com.minidb.query.plan.SortPlan;
import com.minidb.shared.ColumnMeta;
import com.minidb.shared.ColumnType;
import com.minidb.shared.IndexMeta;
import com.minidb.shared.StorageException;
import com.minidb.shared.TableMeta;
import com.minidb.shared.TableStats;
import com.minidb.shared.Value;
import com.minidb.storage.Catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Cost-based optimizer: turns a parsed {@link Statement} into the cheapest
 * {@link PhysicalPlan} ({@code part2.md} §5). It estimates selectivity and cost
 * from catalog statistics, pushes the WHERE predicate into the scan, chooses
 * between a sequential and an index scan, and stacks projection/sort/limit on
 * top.
 *
 * <p>Multi-table join ordering is added in sub-phase 14.5; this class handles
 * single-table SELECT/DELETE/UPDATE.
 */
public final class Optimizer {

    private final Catalog catalog;

    public Optimizer(Catalog catalog) {
        this.catalog = catalog;
    }

    /** Produce the cheapest physical plan for {@code statement}. */
    public PhysicalPlan optimize(Statement statement) {
        if (statement instanceof SelectStatement s) {
            return optimizeSelect(s);
        }
        if (statement instanceof DeleteStatement d) {
            return scanForTable(d.table(), d.where());
        }
        if (statement instanceof UpdateStatement u) {
            return scanForTable(u.table(), u.where());
        }
        throw new StorageException("optimizer: unsupported statement " + statement.getClass().getSimpleName());
    }

    private PhysicalPlan optimizeSelect(SelectStatement select) {
        FromItem from = select.from();
        if (!(from instanceof TableRef tableRef)) {
            // Joins are planned in 14.5.
            throw new StorageException("optimizer: multi-table queries not yet supported");
        }

        PhysicalPlan plan = scanForTable(tableRef.table(), select.where());

        if (!isSelectStar(select.projections())) {
            double cost = plan.estimatedCost() + plan.estimatedRows() * CostModel.CPU_TUPLE_COST;
            plan = new ProjectionPlan(select.projections(), plan, plan.estimatedRows(), cost);
        }
        if (!select.orderBy().isEmpty()) {
            double rows = plan.estimatedRows();
            double sortCost = plan.estimatedCost() + rows * Math.max(1.0, log2(rows)) * CostModel.CPU_TUPLE_COST;
            plan = new SortPlan(plan, select.orderBy(), rows, sortCost);
        }
        if (select.limit() != null || select.offset() != null) {
            double rows = limitedRows(plan.estimatedRows(), select.limit(), select.offset());
            plan = new LimitPlan(plan, select.limit(), select.offset(), rows, plan.estimatedCost());
        }
        return plan;
    }

    /**
     * Choose the cheapest access path for a single table with an optional
     * predicate: a sequential scan with the predicate pushed in, versus an index
     * scan over any index whose column appears in a comparison in the predicate.
     */
    private PhysicalPlan scanForTable(String tableName, Expression predicate) {
        TableMeta meta = catalog.getTable(tableName);
        if (meta == null) {
            throw new StorageException("optimizer: unknown table " + tableName);
        }
        TableStats stats = catalog.getStats(tableName);
        SelectivityEstimator selectivity = new SelectivityEstimator(stats);

        double overallSel = selectivity.estimate(predicate);
        double seqRows = stats.numTuples() * overallSel;
        double seqCost = CostModel.seqScanCost(stats.numPages(), stats.numTuples(), overallSel);
        PhysicalPlan best = new SeqScanPlan(tableName, predicate, seqRows, seqCost);

        List<Expression> conjuncts = splitConjuncts(predicate);
        for (IndexMeta index : catalog.getIndexes(meta.tableId())) {
            BinaryOp bound = findBound(conjuncts, index.columnName());
            if (bound == null) {
                continue;
            }
            ColumnType keyType = columnType(meta.tableId(), index.columnName());
            if (keyType == null) {
                continue;
            }
            double boundSel = selectivity.estimate(bound);
            int order = BPlusTree.defaultOrder(keyType);
            double idxCost = CostModel.indexScanCost(stats.numTuples(), boundSel, order);
            if (idxCost < best.estimatedCost()) {
                Value[] range = bounds(bound, index.columnName());
                best = new IndexScanPlan(tableName, index.columnName(), index.rootPageId(),
                        range[0], range[1], predicate, seqRows, idxCost);
            }
        }
        return best;
    }

    // ---- predicate helpers ----------------------------------------------------

    /** Flatten the top-level AND tree into its conjuncts (a non-AND predicate is one conjunct). */
    static List<Expression> splitConjuncts(Expression predicate) {
        List<Expression> out = new ArrayList<>();
        collectConjuncts(predicate, out);
        return out;
    }

    private static void collectConjuncts(Expression e, List<Expression> out) {
        if (e == null) {
            return;
        }
        if (e instanceof BinaryOp b && b.op().equals("AND")) {
            collectConjuncts(b.left(), out);
            collectConjuncts(b.right(), out);
        } else {
            out.add(e);
        }
    }

    /** First conjunct that is a comparison between {@code column} and a literal, or null. */
    private static BinaryOp findBound(List<Expression> conjuncts, String column) {
        for (Expression e : conjuncts) {
            if (e instanceof BinaryOp b && isComparison(b.op()) && columnLiteral(b, column)) {
                return b;
            }
        }
        return null;
    }

    private static boolean columnLiteral(BinaryOp b, String column) {
        return (refersTo(b.left(), column) && b.right() instanceof Literal r && !r.isNull())
                || (refersTo(b.right(), column) && b.left() instanceof Literal l && !l.isNull());
    }

    private static boolean refersTo(Expression e, String column) {
        return e instanceof ColumnRef c && c.column().equals(column);
    }

    /** Inclusive [low, high] bounds for the index range; either may be null (open). */
    private static Value[] bounds(BinaryOp b, String column) {
        String op;
        Value val;
        if (refersTo(b.left(), column)) {
            op = b.op();
            val = ((Literal) b.right()).value();
        } else {
            op = flip(b.op());
            val = ((Literal) b.left()).value();
        }
        return switch (op) {
            case "=" -> new Value[]{val, val};
            case ">", ">=" -> new Value[]{val, null};
            case "<", "<=" -> new Value[]{null, val};
            default -> new Value[]{null, null};
        };
    }

    private static String flip(String op) {
        return switch (op) {
            case ">" -> "<";
            case ">=" -> "<=";
            case "<" -> ">";
            case "<=" -> ">=";
            default -> op;
        };
    }

    private static boolean isComparison(String op) {
        return switch (op) {
            case "=", "<", ">", "<=", ">=" -> true; // != cannot drive a range scan
            default -> false;
        };
    }

    private ColumnType columnType(int tableId, String columnName) {
        for (ColumnMeta c : catalog.getColumns(tableId)) {
            if (c.columnName().equals(columnName)) {
                return c.columnType();
            }
        }
        return null;
    }

    private static boolean isSelectStar(List<SelectItem> projections) {
        return projections.size() == 1 && projections.get(0).expr() instanceof Star;
    }

    private static double limitedRows(double rows, Integer limit, Integer offset) {
        double r = rows;
        if (offset != null) {
            r = Math.max(0, r - offset);
        }
        if (limit != null) {
            r = Math.min(r, limit);
        }
        return r;
    }

    private static double log2(double x) {
        return x <= 1 ? 0 : Math.log(x) / Math.log(2);
    }
}
