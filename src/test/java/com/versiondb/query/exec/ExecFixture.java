package com.versiondb.query.exec;

import com.versiondb.query.BPlusTree;
import com.versiondb.shared.ColumnDef;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.RID;
import com.versiondb.shared.TableMeta;
import com.versiondb.shared.Value;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.Catalog;
import com.versiondb.storage.DiskManager;
import com.versiondb.storage.HeapFile;
import com.versiondb.storage.TupleCodec;
import com.versiondb.txn.CommitLog;
import com.versiondb.txn.MVCCManager;
import com.versiondb.txn.Transaction;
import com.versiondb.txn.TransactionManager;
import com.versiondb.txn.VersionStore;
import com.versiondb.wal.WALManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only harness wiring the full storage + MVCC + catalog stack so execution
 * operators can be exercised end-to-end.
 */
final class ExecFixture {

    final DiskManager dm;
    final BufferPool bp;
    final WALManager wal;
    final CommitLog clog;
    final VersionStore versionStore;
    final MVCCManager mvcc;
    final TransactionManager txnManager;
    final Catalog catalog;
    final Map<String, HeapFile> heaps = new HashMap<>();
    final Map<Integer, BPlusTree> indexes = new HashMap<>();

    ExecFixture(Path dir) throws IOException {
        dm = new DiskManager(dir.resolve("exec.db"));
        wal = new WALManager(dir.resolve("wal.log"));
        bp = new BufferPool(64, dm, wal::flushToLsn);
        clog = new CommitLog(dir.resolve(CommitLog.FILE_NAME));
        versionStore = new VersionStore();
        mvcc = new MVCCManager(versionStore, clog, wal, bp);
        txnManager = new TransactionManager(1L, wal, clog);
        txnManager.setUndoCallback(mvcc.undoCallback());
        catalog = new Catalog(bp, dm);
    }

    void close() {
        try {
            wal.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        clog.close();
        dm.close();
    }

    TableMeta createTable(String name, List<ColumnDef> columns) {
        TableMeta meta = catalog.createTable(name, columns);
        heaps.put(name, new HeapFile(bp, meta.firstPageId()));
        return meta;
    }

    List<ColumnType> typesOf(String table) {
        TableSchema schema = TableSchema.load(catalog, table);
        return schema.columnTypes;
    }

    RID insert(Transaction txn, String table, List<Value> values) {
        byte[] bytes = TupleCodec.encode(values, typesOf(table));
        return mvcc.insert(txn, heaps.get(table), bytes);
    }

    /** Build a B+Tree index over one column and register it; returns the root page id. */
    int createIndex(String table, String column) {
        List<ColumnType> types = typesOf(table);
        int colIdx = TableSchema.load(catalog, table).indexOf(column);
        BPlusTree tree = BPlusTree.build(bp, heaps.get(table), types, colIdx);
        indexes.put(tree.getRootPageId(), tree);
        catalog.registerIndex(catalog.getTable(table).tableId(), column, tree.getRootPageId());
        return tree.getRootPageId();
    }

    ExecutionContext context(Transaction txn) {
        return new ExecutionContext(txn, mvcc, catalog, heaps, indexes);
    }

    QueryExecutor executor() {
        return new QueryExecutor(catalog, bp, mvcc, heaps, indexes);
    }

    /** Drain an operator into a list of rows (each row a list of values). */
    static List<List<Value>> drain(Operator op) {
        List<List<Value>> rows = new ArrayList<>();
        op.open();
        try {
            Tuple t;
            while ((t = op.next()) != null) {
                rows.add(t.values());
            }
        } finally {
            op.close();
        }
        return rows;
    }
}
