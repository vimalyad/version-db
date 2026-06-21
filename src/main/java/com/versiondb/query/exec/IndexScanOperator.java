package com.versiondb.query.exec;

import com.versiondb.query.BPlusTree;
import com.versiondb.query.ast.Expression;
import com.versiondb.shared.RID;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.Value;
import com.versiondb.txn.Transaction;

import java.util.Iterator;
import java.util.List;

/**
 * Targeted index scan ({@code part2.md} §6.5): drives a {@link BPlusTree#rangeScan}
 * over {@code [low, high]} to get candidate RIDs, then — like the sequential
 * scan — runs an MVCC visibility check on each RID, decodes the visible version,
 * and applies the optional post-filter (any part of the predicate the index
 * range did not enforce).
 */
public final class IndexScanOperator implements Operator {

    private final ExecutionContext ctx;
    private final String tableName;
    private final int rootPageId;
    private final Value low;
    private final Value high;
    private final Expression postFilter;

    private TableSchema schema;
    private Iterator<RID> rids;

    public IndexScanOperator(ExecutionContext ctx, String tableName, int rootPageId,
                             Value low, Value high, Expression postFilter) {
        this.ctx = ctx;
        this.tableName = tableName;
        this.rootPageId = rootPageId;
        this.low = low;
        this.high = high;
        this.postFilter = postFilter;
    }

    @Override
    public void open() {
        schema = TableSchema.load(ctx.catalog(), tableName);
        BPlusTree tree = ctx.index(rootPageId);
        if (tree == null) {
            throw new StorageException("no index registered for root page " + rootPageId);
        }
        List<RID> matches = tree.rangeScan(low, high);
        rids = matches.iterator();
    }

    @Override
    public Tuple next() {
        Transaction txn = ctx.transaction();
        while (rids.hasNext()) {
            RID rid = rids.next();
            byte[] visible = ctx.mvccManager().getVisibleVersion(rid, txn.xid, txn.getSnapshot());
            if (visible == null) {
                continue;
            }
            Tuple tuple = schema.toTuple(visible);
            if (postFilter == null || ExpressionEvaluator.test(postFilter, tuple)) {
                return tuple;
            }
        }
        return null;
    }

    @Override
    public void close() {
        rids = null;
    }
}
