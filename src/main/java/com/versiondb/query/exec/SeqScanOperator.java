package com.versiondb.query.exec;

import com.versiondb.query.ast.Expression;
import com.versiondb.shared.RID;
import com.versiondb.shared.StorageException;
import com.versiondb.storage.HeapFile;
import com.versiondb.txn.Transaction;

import java.util.Iterator;

/**
 * Full table scan ({@code part2.md} §6.4). Walks the heap, and for <em>every</em>
 * RID consults {@link com.versiondb.txn.MVCCManager#getVisibleVersion} so the scan
 * sees exactly the version visible to its transaction's snapshot — invisible
 * tuples (uncommitted, or deleted as of the snapshot) are skipped. The optional
 * pushed-down predicate is applied tuple-by-tuple.
 */
public final class SeqScanOperator implements Operator {

    private final ExecutionContext ctx;
    private final String tableName;
    private final Expression predicate;

    private TableSchema schema;
    private HeapFile heap;
    private Iterator<HeapFile.Entry> scan;

    public SeqScanOperator(ExecutionContext ctx, String tableName, Expression predicate) {
        this.ctx = ctx;
        this.tableName = tableName;
        this.predicate = predicate;
    }

    @Override
    public void open() {
        schema = TableSchema.load(ctx.catalog(), tableName);
        heap = ctx.heapFile(tableName);
        if (heap == null) {
            throw new StorageException("no heap file registered for table " + tableName);
        }
        scan = heap.scan();
    }

    @Override
    public Tuple next() {
        Transaction txn = ctx.transaction();
        while (scan.hasNext()) {
            HeapFile.Entry entry = scan.next();
            RID rid = entry.rid();
            byte[] visible = ctx.mvccManager().getVisibleVersion(rid, txn.xid, txn.getSnapshot());
            if (visible == null) {
                continue; // not visible to this snapshot
            }
            Tuple tuple = schema.toTuple(visible);
            if (predicate == null || ExpressionEvaluator.test(predicate, tuple)) {
                return tuple;
            }
        }
        return null;
    }

    @Override
    public void close() {
        scan = null;
    }
}
