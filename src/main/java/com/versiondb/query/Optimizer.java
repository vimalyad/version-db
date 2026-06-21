package com.versiondb.query;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.DeleteStatement;
import com.versiondb.query.ast.Expression;
import com.versiondb.query.ast.FromItem;
import com.versiondb.query.ast.FunctionCall;
import com.versiondb.query.ast.JoinExpr;
import com.versiondb.query.ast.Literal;
import com.versiondb.query.ast.SelectItem;
import com.versiondb.query.ast.SelectStatement;
import com.versiondb.query.ast.Star;
import com.versiondb.query.ast.Statement;
import com.versiondb.query.ast.TableRef;
import com.versiondb.query.ast.UnaryOp;
import com.versiondb.query.ast.UpdateStatement;
import com.versiondb.query.plan.HashJoinPlan;
import com.versiondb.query.plan.IndexScanPlan;
import com.versiondb.query.plan.LimitPlan;
import com.versiondb.query.plan.NestedLoopJoinPlan;
import com.versiondb.query.plan.PhysicalPlan;
import com.versiondb.query.plan.ProjectionPlan;
import com.versiondb.query.plan.SeqScanPlan;
import com.versiondb.query.plan.SortPlan;
import com.versiondb.shared.ColumnMeta;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.IndexMeta;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.TableMeta;
import com.versiondb.shared.TableStats;
import com.versiondb.shared.Value;
import com.versiondb.storage.Catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        PhysicalPlan plan = (from instanceof TableRef tableRef)
                ? scanForTable(tableRef.table(), select.where())
                : optimizeJoin(from, select.where());

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

    // ---- 14.5: join ordering --------------------------------------------------

    /** One base table participating in a join, with its pushed single-table predicate. */
    private static final class BaseTable {
        final String id;            // alias if present, else table name
        final TableMeta meta;
        final TableStats stats;
        final Set<String> columns;
        Expression pushed;          // AND of single-table conjuncts, or null

        BaseTable(String id, TableMeta meta, TableStats stats, Set<String> columns) {
            this.id = id;
            this.meta = meta;
            this.stats = stats;
            this.columns = columns;
        }
    }

    /**
     * Plan a multi-table query. Collects the base tables and join conditions
     * (explicit ON clauses plus multi-table WHERE conjuncts), pushes
     * single-table conjuncts into each table's scan, then chooses the cheapest
     * left-deep join order — enumerating all permutations for up to four tables,
     * or a greedy smallest-first order beyond that — picking Hash vs Nested-Loop
     * per join.
     */
    private PhysicalPlan optimizeJoin(FromItem from, Expression where) {
        List<BaseTable> tables = new ArrayList<>();
        collectBaseTables(from, tables);

        List<Expression> joinConds = new ArrayList<>();
        collectJoinOns(from, joinConds);

        // Split WHERE: single-table conjuncts are pushed, multi-table ones join.
        for (Expression conj : splitConjuncts(where)) {
            Set<String> refs = tableSet(conj, tables);
            if (refs.size() >= 2) {
                joinConds.add(conj);
            } else {
                BaseTable t = refs.isEmpty() ? tables.get(0) : byId(tables, refs.iterator().next());
                t.pushed = and(t.pushed, conj);
            }
        }

        List<List<BaseTable>> orderings = enumerateOrderings(tables);
        PhysicalPlan best = null;
        for (List<BaseTable> order : orderings) {
            PhysicalPlan candidate = buildLeftDeep(order, joinConds);
            if (best == null || candidate.estimatedCost() < best.estimatedCost()) {
                best = candidate;
            }
        }
        return best;
    }

    private PhysicalPlan buildLeftDeep(List<BaseTable> order, List<Expression> joinConds) {
        PhysicalPlan acc = scanForTable(order.get(0).meta.tableName(), order.get(0).pushed);
        Set<String> accIds = new LinkedHashSet<>();
        accIds.add(order.get(0).id);

        for (int i = 1; i < order.size(); i++) {
            BaseTable t = order.get(i);
            PhysicalPlan inner = scanForTable(t.meta.tableName(), t.pushed);

            // Conditions fully covered by the accumulated set plus this table,
            // and actually connecting the two sides.
            List<Expression> applicable = new ArrayList<>();
            for (Expression c : joinConds) {
                Set<String> refs = tableSet(c, order);
                if (refs.contains(t.id) && anyIn(refs, accIds)
                        && accIds.containsAll(without(refs, t.id))) {
                    applicable.add(c);
                }
            }
            Expression cond = andAll(applicable);
            acc = makeJoin(acc, inner, cond, accIds, t, order);
            accIds.add(t.id);
        }
        return acc;
    }

    private PhysicalPlan makeJoin(PhysicalPlan outer, PhysicalPlan inner, Expression cond,
                                  Set<String> accIds, BaseTable innerTable, List<BaseTable> all) {
        double outerRows = outer.estimatedRows();
        double innerRows = inner.estimatedRows();
        Expression[] equi = equiKeys(cond, accIds, innerTable.id, all);

        double joinSel;
        if (cond == null) {
            joinSel = 1.0;                                   // cross product
        } else if (equi != null) {
            joinSel = 1.0 / Math.max(1.0, Math.max(outerRows, innerRows));
        } else {
            joinSel = 0.1;                                   // generic non-equi guess
        }
        double rows = Math.max(0.0, outerRows * innerRows * joinSel);

        double nljCost = CostModel.nestedLoopJoinCost(outer.estimatedCost(), outerRows, inner.estimatedCost());
        if (equi != null) {
            double hashCost = CostModel.hashJoinCost(outer.estimatedCost(), inner.estimatedCost(), innerRows);
            if (hashCost <= nljCost) {
                return new HashJoinPlan(outer, inner, cond, equi[0], equi[1], rows, hashCost);
            }
        }
        return new NestedLoopJoinPlan(outer, inner, cond, rows, nljCost);
    }

    /** {outerKey, innerKey} of an equality conjunct that bridges the two sides, or null. */
    private Expression[] equiKeys(Expression cond, Set<String> accIds, String innerId, List<BaseTable> all) {
        for (Expression c : splitConjuncts(cond)) {
            if (c instanceof BinaryOp b && b.op().equals("=")
                    && b.left() instanceof ColumnRef l && b.right() instanceof ColumnRef r) {
                String lt = resolve(l, all);
                String rt = resolve(r, all);
                if (accIds.contains(lt) && innerId.equals(rt)) {
                    return new Expression[]{l, r};
                }
                if (accIds.contains(rt) && innerId.equals(lt)) {
                    return new Expression[]{r, l};
                }
            }
        }
        return null;
    }

    private void collectBaseTables(FromItem from, List<BaseTable> out) {
        if (from instanceof TableRef ref) {
            TableMeta meta = catalog.getTable(ref.table());
            if (meta == null) {
                throw new StorageException("optimizer: unknown table " + ref.table());
            }
            Set<String> cols = new HashSet<>();
            for (ColumnMeta c : catalog.getColumns(meta.tableId())) {
                cols.add(c.columnName());
            }
            String id = ref.alias() != null ? ref.alias() : ref.table();
            out.add(new BaseTable(id, meta, catalog.getStats(ref.table()), cols));
        } else if (from instanceof JoinExpr j) {
            collectBaseTables(j.left(), out);
            collectBaseTables(j.right(), out);
        }
    }

    private void collectJoinOns(FromItem from, List<Expression> out) {
        if (from instanceof JoinExpr j) {
            collectJoinOns(j.left(), out);
            collectJoinOns(j.right(), out);
            if (j.on() != null) {
                out.add(j.on());
            }
        }
    }

    /** All permutations for <=4 tables; a single greedy smallest-first order otherwise. */
    private List<List<BaseTable>> enumerateOrderings(List<BaseTable> tables) {
        List<List<BaseTable>> result = new ArrayList<>();
        if (tables.size() <= 4) {
            permute(tables, 0, result);
        } else {
            List<BaseTable> greedy = new ArrayList<>(tables);
            greedy.sort((a, b) -> Long.compare(a.stats.numTuples(), b.stats.numTuples()));
            result.add(greedy);
        }
        return result;
    }

    private void permute(List<BaseTable> tables, int k, List<List<BaseTable>> out) {
        if (k == tables.size()) {
            out.add(new ArrayList<>(tables));
            return;
        }
        for (int i = k; i < tables.size(); i++) {
            swap(tables, k, i);
            permute(tables, k + 1, out);
            swap(tables, k, i);
        }
    }

    private static void swap(List<BaseTable> list, int i, int j) {
        BaseTable tmp = list.get(i);
        list.set(i, list.get(j));
        list.set(j, tmp);
    }

    /** The set of base-table ids referenced by a predicate's columns. */
    private Set<String> tableSet(Expression e, List<BaseTable> tables) {
        List<ColumnRef> refs = new ArrayList<>();
        collectColumnRefs(e, refs);
        Set<String> ids = new LinkedHashSet<>();
        for (ColumnRef c : refs) {
            String id = resolve(c, tables);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String resolve(ColumnRef c, List<BaseTable> tables) {
        if (c.table() != null) {
            for (BaseTable t : tables) {
                if (t.id.equals(c.table()) || t.meta.tableName().equals(c.table())) {
                    return t.id;
                }
            }
            return null;
        }
        for (BaseTable t : tables) {
            if (t.columns.contains(c.column())) {
                return t.id;
            }
        }
        return null;
    }

    private static void collectColumnRefs(Expression e, List<ColumnRef> out) {
        if (e instanceof ColumnRef c) {
            out.add(c);
        } else if (e instanceof BinaryOp b) {
            collectColumnRefs(b.left(), out);
            collectColumnRefs(b.right(), out);
        } else if (e instanceof UnaryOp u) {
            collectColumnRefs(u.operand(), out);
        } else if (e instanceof FunctionCall f) {
            for (Expression arg : f.args()) {
                collectColumnRefs(arg, out);
            }
        }
    }

    private static BaseTable byId(List<BaseTable> tables, String id) {
        for (BaseTable t : tables) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return tables.get(0);
    }

    private static boolean anyIn(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> without(Set<String> set, String id) {
        Set<String> copy = new HashSet<>(set);
        copy.remove(id);
        return copy;
    }

    private static Expression and(Expression a, Expression b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new BinaryOp(a, "AND", b);
    }

    private static Expression andAll(List<Expression> conjuncts) {
        Expression result = null;
        for (Expression e : conjuncts) {
            result = and(result, e);
        }
        return result;
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
