package com.minidb.query.exec;

import com.minidb.query.BPlusTree;
import com.minidb.shared.ColumnDef;
import com.minidb.shared.ColumnType;
import com.minidb.shared.RID;
import com.minidb.shared.TableMeta;
import com.minidb.shared.Value;
import com.minidb.storage.BufferPool;
import com.minidb.storage.Catalog;
import com.minidb.storage.DiskManager;
import com.minidb.storage.HeapFile;
import com.minidb.storage.TupleCodec;
import com.minidb.txn.CommitLog;
import com.minidb.txn.MVCCManager;
import com.minidb.txn.Transaction;
import com.minidb.txn.TransactionManager;
import com.minidb.txn.VersionStore;
import com.minidb.wal.WALManager;

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
