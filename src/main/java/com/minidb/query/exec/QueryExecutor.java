package com.minidb.query.exec;

import com.minidb.query.BPlusTree;
import com.minidb.query.Optimizer;
import com.minidb.query.Parser;
import com.minidb.query.plan.PhysicalPlan;
import com.minidb.query.ast.Assignment;
import com.minidb.query.ast.CreateIndexStatement;
import com.minidb.query.ast.CreateTableStatement;
import com.minidb.query.ast.DeleteStatement;
import com.minidb.query.ast.DropTableStatement;
import com.minidb.query.ast.Expression;
import com.minidb.query.ast.InsertStatement;
import com.minidb.query.ast.SelectStatement;
import com.minidb.query.ast.Statement;
import com.minidb.query.ast.UpdateStatement;
import com.minidb.shared.ColumnType;
import com.minidb.shared.RID;
import com.minidb.shared.StorageException;
import com.minidb.shared.TableMeta;
import com.minidb.shared.Value;
import com.minidb.storage.BufferPool;
import com.minidb.storage.Catalog;
import com.minidb.storage.HeapFile;
import com.minidb.storage.TupleCodec;
import com.minidb.txn.MVCCManager;
import com.minidb.txn.Transaction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Top-level execution entry point ({@code part2.md} §6.14): parses a SQL string
 * and runs it against storage and MVCC, returning a {@link ResultSet}. Reads are
 * planned by the optimizer and run through the Volcano operators (added in 15.8);
 * writes and DDL are handled directly here.
 *
 * <p>It owns the table and index registries so CREATE statements can extend them
 * at runtime.
 */
public final class QueryExecutor {

    private final Catalog catalog;
    private final BufferPool bufferPool;
    private final MVCCManager mvcc;
    private final Map<String, HeapFile> heapFiles;
    private final Map<Integer, BPlusTree> indexes;

    public QueryExecutor(Catalog catalog, BufferPool bufferPool, MVCCManager mvcc,
                         Map<String, HeapFile> heapFiles, Map<Integer, BPlusTree> indexes) {
        this.catalog = catalog;
        this.bufferPool = bufferPool;
        this.mvcc = mvcc;
        this.heapFiles = heapFiles;
        this.indexes = indexes;
    }

    /** Parse and execute {@code sql} within {@code txn}. */
    public ResultSet execute(String sql, Transaction txn) {
        Statement stmt = Parser.parse(sql);
        if (stmt instanceof InsertStatement i) {
            return executeInsert(i, txn);
        }
        if (stmt instanceof DeleteStatement d) {
            return executeDelete(d, txn);
        }
        if (stmt instanceof UpdateStatement u) {
            return executeUpdate(u, txn);
        }
        if (stmt instanceof CreateTableStatement c) {
            return executeCreateTable(c);
        }
        if (stmt instanceof CreateIndexStatement c) {
            return executeCreateIndex(c);
        }
        if (stmt instanceof DropTableStatement d) {
            throw new StorageException("DROP TABLE is not supported");
        }
        if (stmt instanceof SelectStatement s) {
            return executeSelect(s, txn); // implemented in 15.8
        }
        throw new StorageException("unsupported statement " + stmt.getClass().getSimpleName());
    }

    /** Optimize a SELECT, build the operator tree, and drive it to a ResultSet. */
    ResultSet executeSelect(SelectStatement select, Transaction txn) {
        PhysicalPlan plan = new Optimizer(catalog).optimize(select);
        ExecutionContext ctx = new ExecutionContext(txn, mvcc, catalog, heapFiles, indexes);
        Operator op = PlanBuilder.build(plan, ctx);

        List<String> columns = null;
        List<List<Value>> rows = new ArrayList<>();
        op.open();
        try {
            Tuple t;
            while ((t = op.next()) != null) {
                if (columns == null) {
                    columns = new ArrayList<>(t.columnNames());
                }
                rows.add(t.values());
            }
        } finally {
            op.close();
        }
        if (columns == null) {
            columns = headerForEmptyResult(select); // no rows produced
        }
        return ResultSet.of(columns, rows);
    }

    /** Best-effort column headers when a query returns no rows. */
    private List<String> headerForEmptyResult(SelectStatement select) {
        List<String> cols = new ArrayList<>();
        for (var item : select.projections()) {
            if (item.expr() instanceof com.minidb.query.ast.Star) {
                if (select.from() instanceof com.minidb.query.ast.TableRef ref) {
                    cols.addAll(TableSchema.load(catalog, ref.table()).columnNames);
                }
            } else if (item.alias() != null) {
                cols.add(item.alias());
            } else if (item.expr() instanceof com.minidb.query.ast.ColumnRef ref) {
                cols.add(ref.column());
            } else {
                cols.add("col" + (cols.size() + 1));
            }
        }
        return cols;
    }

    // ---- 15.7: writes & DDL ---------------------------------------------------

    private ResultSet executeInsert(InsertStatement insert, Transaction txn) {
        TableSchema schema = TableSchema.load(catalog, insert.table());
        HeapFile heap = heap(insert.table());

        // Map each VALUES position to a column index in storage order.
        int[] target = new int[schema.columnNames.size()];
        if (insert.columns() == null || insert.columns().isEmpty()) {
            for (int i = 0; i < target.length; i++) {
                target[i] = i; // all columns, in order
            }
        } else {
            for (int i = 0; i < target.length; i++) {
                target[i] = -1;
            }
        }

        int count = 0;
        for (List<Expression> rowExprs : insert.rows()) {
            Value[] values = new Value[schema.columnNames.size()];
            if (insert.columns() == null || insert.columns().isEmpty()) {
                if (rowExprs.size() != values.length) {
                    throw new StorageException("INSERT value count " + rowExprs.size()
                            + " does not match column count " + values.length);
                }
                for (int i = 0; i < values.length; i++) {
                    values[i] = coerce(eval(rowExprs.get(i)), schema.columnTypes.get(i));
                }
            } else {
                for (int i = 0; i < values.length; i++) {
                    values[i] = Value.nullValue(schema.columnTypes.get(i)); // unspecified → NULL
                }
                List<String> cols = insert.columns();
                for (int i = 0; i < cols.size(); i++) {
                    int idx = schema.indexOf(cols.get(i));
                    if (idx < 0) {
                        throw new StorageException("unknown column " + cols.get(i) + " in INSERT");
                    }
                    values[idx] = coerce(eval(rowExprs.get(i)), schema.columnTypes.get(idx));
                }
            }
            byte[] bytes = TupleCodec.encode(List.of(values), schema.columnTypes);
            mvcc.insert(txn, heap, bytes);
            count++;
        }
        return ResultSet.affected(count);
    }

    private ResultSet executeDelete(DeleteStatement delete, Transaction txn) {
        TableSchema schema = TableSchema.load(catalog, delete.table());
        HeapFile heap = heap(delete.table());
        List<RID> toDelete = matchingRids(schema, heap, delete.where(), txn);
        for (RID rid : toDelete) {
            mvcc.delete(txn, heap, rid);
        }
        return ResultSet.affected(toDelete.size());
    }

    private ResultSet executeUpdate(UpdateStatement update, Transaction txn) {
        TableSchema schema = TableSchema.load(catalog, update.table());
        HeapFile heap = heap(update.table());

        // Collect (rid, newBytes) first so we don't re-scan rows we just wrote.
        List<RID> rids = new ArrayList<>();
        List<byte[]> newRows = new ArrayList<>();
        Iterator<HeapFile.Entry> scan = heap.scan();
        while (scan.hasNext()) {
            HeapFile.Entry entry = scan.next();
            byte[] visible = mvcc.getVisibleVersion(entry.rid(), txn.xid, txn.getSnapshot());
            if (visible == null) {
                continue;
            }
            Tuple tuple = schema.toTuple(visible);
            if (update.where() != null && !ExpressionEvaluator.test(update.where(), tuple)) {
                continue;
            }
            Value[] values = new Value[schema.columnNames.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = nullSafe(tuple.value(i), schema.columnTypes.get(i));
            }
            for (Assignment a : update.assignments()) {
                int idx = schema.indexOf(a.column());
                if (idx < 0) {
                    throw new StorageException("unknown column " + a.column() + " in UPDATE");
                }
                values[idx] = coerce(ExpressionEvaluator.eval(a.value(), tuple), schema.columnTypes.get(idx));
            }
            rids.add(entry.rid());
            newRows.add(TupleCodec.encode(List.of(values), schema.columnTypes));
        }
        for (int i = 0; i < rids.size(); i++) {
            mvcc.update(txn, heap, rids.get(i), newRows.get(i));
        }
        return ResultSet.affected(rids.size());
    }

    private ResultSet executeCreateTable(CreateTableStatement create) {
        TableMeta meta = catalog.createTable(create.table(), create.columns());
        heapFiles.put(create.table(), new HeapFile(bufferPool, meta.firstPageId()));
        return ResultSet.affected(0);
    }

    private ResultSet executeCreateIndex(CreateIndexStatement create) {
        TableSchema schema = TableSchema.load(catalog, create.table());
        int colIdx = schema.indexOf(create.column());
        if (colIdx < 0) {
            throw new StorageException("unknown column " + create.column() + " for index");
        }
        BPlusTree tree = BPlusTree.build(bufferPool, heap(create.table()), schema.columnTypes, colIdx);
        indexes.put(tree.getRootPageId(), tree);
        catalog.registerIndex(schema.tableId, create.column(), tree.getRootPageId());
        return ResultSet.affected(0);
    }

    // ---- helpers --------------------------------------------------------------

    /** RIDs of rows visible to {@code txn} that satisfy {@code where} (null = all). */
    private List<RID> matchingRids(TableSchema schema, HeapFile heap, Expression where, Transaction txn) {
        List<RID> out = new ArrayList<>();
        Iterator<HeapFile.Entry> scan = heap.scan();
        while (scan.hasNext()) {
            HeapFile.Entry entry = scan.next();
            byte[] visible = mvcc.getVisibleVersion(entry.rid(), txn.xid, txn.getSnapshot());
            if (visible == null) {
                continue;
            }
            if (where == null || ExpressionEvaluator.test(where, schema.toTuple(visible))) {
                out.add(entry.rid());
            }
        }
        return out;
    }

    private HeapFile heap(String table) {
        HeapFile heap = heapFiles.get(table);
        if (heap == null) {
            throw new StorageException("no heap file registered for table " + table);
        }
        return heap;
    }

    private static Value eval(Expression e) {
        // INSERT VALUES are constant expressions, evaluated against no row.
        return ExpressionEvaluator.eval(e, new Tuple(List.of(), List.of(), List.of()));
    }

    /** Convert an evaluator result (Java null = SQL NULL) into a storable Value of the column type. */
    private static Value coerce(Value v, ColumnType type) {
        if (v == null) {
            return Value.nullValue(type);
        }
        if (type == ColumnType.FLOAT && v.type() == ColumnType.INT) {
            return Value.ofFloat((double) v.asInt());
        }
        return v;
    }

    private static Value nullSafe(Value v, ColumnType type) {
        return v == null ? Value.nullValue(type) : v;
    }
}
