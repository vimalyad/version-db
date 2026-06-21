package com.versiondb.tools;

import com.versiondb.query.BPlusTree;
import com.versiondb.query.exec.QueryExecutor;
import com.versiondb.query.exec.ResultSet;
import com.versiondb.shared.ColumnMeta;
import com.versiondb.shared.Constants;
import com.versiondb.shared.IndexMeta;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.TableMeta;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.Catalog;
import com.versiondb.storage.DiskManager;
import com.versiondb.storage.HeapFile;
import com.versiondb.txn.CommitLog;
import com.versiondb.txn.MVCCManager;
import com.versiondb.txn.Transaction;
import com.versiondb.txn.TransactionManager;
import com.versiondb.txn.Vacuum;
import com.versiondb.txn.VersionRecord;
import com.versiondb.txn.VersionStore;
import com.versiondb.shared.RID;
import com.versiondb.wal.LogRecord;
import com.versiondb.wal.LogType;
import com.versiondb.wal.RecoveryManager;
import com.versiondb.wal.WALManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fully-assembled VersionDB engine: a single object that opens (or creates) a
 * database in a directory, runs crash recovery, and executes SQL through the
 * complete stack — storage, WAL/recovery, MVCC, and the query engine.
 *
 * <h2>Startup sequence (16.1)</h2>
 * <ol>
 *   <li>Open {@link DiskManager}, {@link WALManager}, {@link BufferPool}
 *       (wired to the WAL-flush callback) and {@link CommitLog}.</li>
 *   <li>Run {@link RecoveryManager#recover()} (Analysis → Redo → Undo), then
 *       {@link CommitLog#reconcile} so transaction statuses match the recovered
 *       outcome.</li>
 *   <li>Build the {@link MVCCManager}, {@link TransactionManager} (its first XID
 *       set just past the highest recovered XID), and {@link Catalog}.</li>
 *   <li>Rebuild the query layer's runtime registries — table&nbsp;→&nbsp;{@link HeapFile}
 *       and index-root&nbsp;→&nbsp;{@link BPlusTree} — from the catalog, since
 *       those maps are not persisted.</li>
 *   <li>Reseed the in-memory {@link VersionStore} from the post-recovery heaps:
 *       every live slot becomes a committed "base" version (see note below).</li>
 *   <li>Start the {@link Vacuum} background thread.</li>
 * </ol>
 *
 * <h2>Version-store reseed</h2>
 * The version store is in-memory and not persisted, so on restart it is empty
 * and the MVCC visibility check would find no versions. We rebuild it by
 * replaying the committed WAL history: every committed INSERT that was not later
 * deleted by a committed transaction becomes one committed base version, stamped
 * with a sentinel {@code xmin} below every future snapshot's {@code xmin} so the
 * row is visible to all subsequent transactions. (The heap alone is insufficient:
 * a DELETE only stamps the in-memory version store, so dead tuple bytes remain
 * physically present and would otherwise reappear after a restart.)
 *
 * <h2>Autocommit vs. explicit transactions</h2>
 * {@link #execute(String)} runs one statement in its own transaction (commit on
 * success, abort on error). {@link #begin()}, {@link #execute(String, Transaction)},
 * {@link #commit(Transaction)}, and {@link #abort(Transaction)} drive an explicit
 * multi-statement transaction.
 */
public final class VersionDb implements AutoCloseable {

    /** Default database file names within the database directory. */
    public static final String DB_FILE = "versiondb.db";
    public static final String WAL_FILE = "wal.log";

    /** Buffer-pool size in frames. */
    private static final int BUFFER_POOL_FRAMES = 256;

    /** How often the background vacuum runs, in milliseconds. */
    private static final long VACUUM_PERIOD_MILLIS = 1_000L;

    /**
     * Sentinel xmin for reseeded committed base versions. It is below
     * {@link Constants#FIRST_NORMAL_XID}-or-later snapshots taken after restart
     * (the transaction manager always resumes at a higher XID once any data
     * exists), so the visibility rule's {@code xid < snapshot.xmin} short-circuit
     * makes these rows visible to every future transaction.
     */
    private static final long BASE_VERSION_XMIN = Constants.FIRST_NORMAL_XID;

    private final DiskManager diskManager;
    private final WALManager walManager;
    private final BufferPool bufferPool;
    private final CommitLog commitLog;
    private final VersionStore versionStore;
    private final MVCCManager mvcc;
    private final TransactionManager txnManager;
    private final Catalog catalog;
    private final QueryExecutor executor;
    private final Vacuum vacuum;

    private final Map<String, HeapFile> heapFiles = new HashMap<>();
    private final Map<Integer, BPlusTree> indexes = new HashMap<>();

    private boolean closed;

    private VersionDb(Path dir, boolean startVacuum) {
        try {
            this.diskManager = new DiskManager(dir.resolve(DB_FILE));
            this.walManager = new WALManager(dir.resolve(WAL_FILE));
            this.bufferPool = new BufferPool(BUFFER_POOL_FRAMES, diskManager, walManager::flushToLsn);
            this.commitLog = new CommitLog(dir.resolve(CommitLog.FILE_NAME));

            // Crash recovery: rebuild committed state, then reconcile statuses.
            RecoveryManager recovery = new RecoveryManager(walManager, bufferPool);
            RecoveryManager.RecoveryResult result = recovery.recover();
            commitLog.reconcile(result.undone(), result.committed());

            this.versionStore = new VersionStore();
            this.mvcc = new MVCCManager(versionStore, commitLog, walManager, bufferPool);
            this.txnManager = new TransactionManager(nextXidAfter(result), walManager, commitLog);
            this.txnManager.setUndoCallback(mvcc.undoCallback());

            this.catalog = new Catalog(bufferPool, diskManager);
            rebuildRegistries();
            reseedVersionStore();

            this.executor = new QueryExecutor(catalog, bufferPool, mvcc, heapFiles, indexes);
            this.vacuum = new Vacuum(versionStore, commitLog, txnManager);
            if (startVacuum) {
                vacuum.start(VACUUM_PERIOD_MILLIS);
            }
        } catch (IOException e) {
            throw new StorageException("failed to open database in " + dir, e);
        }
    }

    /** Open (or create) a database in {@code dir}, starting the background vacuum. */
    public static VersionDb open(Path dir) {
        return new VersionDb(dir, true);
    }

    /**
     * Open without starting the background vacuum thread — useful for tests that
     * need fully deterministic version-store contents.
     */
    public static VersionDb openWithoutVacuum(Path dir) {
        return new VersionDb(dir, false);
    }

    // -------------------------------------------------------------------------
    // Statement execution
    // -------------------------------------------------------------------------

    /**
     * Execute one statement in autocommit mode: begin a transaction, run the
     * statement, and commit. On any error the transaction is aborted and the
     * exception is rethrown.
     */
    public ResultSet execute(String sql) {
        Transaction txn = txnManager.beginTransaction();
        try {
            ResultSet rs = executor.execute(sql, txn);
            txnManager.commitTransaction(txn);
            return rs;
        } catch (RuntimeException e) {
            txnManager.abortTransaction(txn);
            throw e;
        }
    }

    /** Execute one statement within an explicit, caller-managed transaction. */
    public ResultSet execute(String sql, Transaction txn) {
        return executor.execute(sql, txn);
    }

    // -------------------------------------------------------------------------
    // Explicit transaction control
    // -------------------------------------------------------------------------

    public Transaction begin() {
        return txnManager.beginTransaction();
    }

    public void commit(Transaction txn) {
        txnManager.commitTransaction(txn);
    }

    public void abort(Transaction txn) {
        txnManager.abortTransaction(txn);
    }

    // -------------------------------------------------------------------------
    // Accessors (for the CLI and tests)
    // -------------------------------------------------------------------------

    public Catalog catalog() {
        return catalog;
    }

    public TransactionManager transactionManager() {
        return txnManager;
    }

    /** Flush all dirty pages to disk (a clean checkpoint of buffered state). */
    public void flush() {
        bufferPool.flushAll();
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /** Clean shutdown: stop vacuum, flush dirty pages, and close all files. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        vacuum.stop();
        bufferPool.flushAll();
        closeFilesQuietly();
    }

    /**
     * Simulate a crash: stop vacuum and release file handles <em>without</em>
     * flushing the buffer pool, so buffered dirty pages are lost exactly as they
     * would be on a real crash. Committed data survives via the WAL (recovery
     * redoes it on the next open); uncommitted data is rolled back. Intended for
     * durability tests.
     */
    public void crash() {
        if (closed) {
            return;
        }
        closed = true;
        vacuum.stop();
        closeFilesQuietly();
    }

    private void closeFilesQuietly() {
        try {
            walManager.close();
        } catch (IOException e) {
            throw new StorageException("failed to close WAL", e);
        } finally {
            commitLog.close();
            diskManager.close();
        }
    }

    // -------------------------------------------------------------------------
    // Startup helpers
    // -------------------------------------------------------------------------

    /** First XID to assign: one past the highest XID seen during recovery. */
    private static long nextXidAfter(RecoveryManager.RecoveryResult result) {
        long max = 0L;
        for (long xid : result.committed()) {
            max = Math.max(max, xid);
        }
        for (long xid : result.undone()) {
            max = Math.max(max, xid);
        }
        return Math.max(Constants.FIRST_NORMAL_XID, max + 1);
    }

    /** Rebuild the (non-persisted) heap-file and index registries from the catalog. */
    private void rebuildRegistries() {
        for (TableMeta table : catalog.allTables()) {
            heapFiles.put(table.tableName(), new HeapFile(bufferPool, table.firstPageId()));
            for (IndexMeta index : catalog.getIndexes(table.tableId())) {
                BPlusTree tree = BPlusTree.open(bufferPool,
                        keyTypeOf(table.tableId(), index.columnName()), index.rootPageId());
                indexes.put(index.rootPageId(), tree);
            }
        }
    }

    private com.versiondb.shared.ColumnType keyTypeOf(int tableId, String columnName) {
        for (ColumnMeta col : catalog.getColumns(tableId)) {
            if (col.columnName().equals(columnName)) {
                return col.columnType();
            }
        }
        throw new StorageException("index column " + columnName + " not found on table " + tableId);
    }

    /**
     * Rebuild the version store from the committed WAL history so reads after a
     * restart see exactly the committed, not-since-deleted tuples.
     *
     * <p>The heap cannot be trusted directly: a DELETE (and the old side of an
     * UPDATE) only stamps the in-memory version store and the page LSN, so
     * recovery's redo skips it and the dead bytes remain physically present.
     * Replaying the WAL and keeping only committed INSERTs that were not later
     * deleted by a committed transaction reconstructs the correct visible set.
     * Each surviving tuple becomes a committed base version (see the class note).
     * CLRs belong to aborted (loser) transactions, which never commit, so their
     * effects are excluded by the commit-status filter.
     */
    private void reseedVersionStore() {
        Map<RID, byte[]> live = new LinkedHashMap<>();
        Iterator<LogRecord> log = walManager.readLog(0L);
        while (log.hasNext()) {
            LogRecord rec = log.next();
            if (!commitLog.isCommitted(rec.txnId)) {
                continue;
            }
            if (rec.logType == LogType.INSERT) {
                live.put(new RID(rec.pageId, rec.slotId), rec.data);
            } else if (rec.logType == LogType.DELETE) {
                live.remove(new RID(rec.pageId, rec.slotId));
            }
        }
        for (Map.Entry<RID, byte[]> entry : live.entrySet()) {
            VersionRecord base = new VersionRecord(
                    BASE_VERSION_XMIN, Constants.INVALID_XID, entry.getKey(), -1L, entry.getValue());
            long versionId = versionStore.allocate(base);
            versionStore.setHead(entry.getKey(), versionId);
        }
    }
}
