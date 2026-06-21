package com.minidb.tools;

import com.minidb.query.BPlusTree;
import com.minidb.query.exec.QueryExecutor;
import com.minidb.query.exec.ResultSet;
import com.minidb.shared.ColumnMeta;
import com.minidb.shared.Constants;
import com.minidb.shared.IndexMeta;
import com.minidb.shared.StorageException;
import com.minidb.shared.TableMeta;
import com.minidb.storage.BufferPool;
import com.minidb.storage.Catalog;
import com.minidb.storage.DiskManager;
import com.minidb.storage.HeapFile;
import com.minidb.txn.CommitLog;
import com.minidb.txn.MVCCManager;
import com.minidb.txn.Transaction;
import com.minidb.txn.TransactionManager;
import com.minidb.txn.Vacuum;
import com.minidb.txn.VersionRecord;
import com.minidb.txn.VersionStore;
import com.minidb.wal.RecoveryManager;
import com.minidb.wal.WALManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The fully-assembled MiniDB engine: a single object that opens (or creates) a
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
 * and the MVCC visibility check would find no versions. ARIES recovery, however,
 * has already reconstructed the committed physical state in the heap (committed
 * inserts are present, uncommitted inserts and committed deletes are tombstoned).
 * We therefore scan each heap after recovery and register one committed base
 * version per live slot, stamped with a sentinel {@code xmin} below every future
 * snapshot's {@code xmin} so the row is visible to all subsequent transactions.
 *
 * <h2>Autocommit vs. explicit transactions</h2>
 * {@link #execute(String)} runs one statement in its own transaction (commit on
 * success, abort on error). {@link #begin()}, {@link #execute(String, Transaction)},
 * {@link #commit(Transaction)}, and {@link #abort(Transaction)} drive an explicit
 * multi-statement transaction.
 */
public final class MiniDb implements AutoCloseable {

    /** Default database file names within the database directory. */
    public static final String DB_FILE = "minidb.db";
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

    private MiniDb(Path dir, boolean startVacuum) {
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
    public static MiniDb open(Path dir) {
        return new MiniDb(dir, true);
    }

    /**
     * Open without starting the background vacuum thread — useful for tests that
     * need fully deterministic version-store contents.
     */
    public static MiniDb openWithoutVacuum(Path dir) {
        return new MiniDb(dir, false);
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

    private com.minidb.shared.ColumnType keyTypeOf(int tableId, String columnName) {
        for (ColumnMeta col : catalog.getColumns(tableId)) {
            if (col.columnName().equals(columnName)) {
                return col.columnType();
            }
        }
        throw new StorageException("index column " + columnName + " not found on table " + tableId);
    }

    /**
     * Register a committed base version for every live heap slot, so reads after
     * a restart see the recovered committed data (see the class note).
     */
    private void reseedVersionStore() {
        for (HeapFile heap : heapFiles.values()) {
            Iterator<HeapFile.Entry> scan = heap.scan();
            while (scan.hasNext()) {
                HeapFile.Entry entry = scan.next();
                VersionRecord base = new VersionRecord(
                        BASE_VERSION_XMIN, Constants.INVALID_XID, entry.rid(), -1L, entry.data());
                long versionId = versionStore.allocate(base);
                versionStore.setHead(entry.rid(), versionId);
            }
        }
    }
}
