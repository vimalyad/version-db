package com.minidb.txn;

import com.minidb.wal.WALManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central coordinator for transaction lifecycle. It assigns XIDs, captures
 * snapshots, drives WAL logging, updates the commit log, and coordinates
 * in-memory undo on abort.
 *
 * <p><b>Thread safety.</b> A single {@link ReentrantLock} ({@code txnLock})
 * guards the XID counter, the active-transaction map, and snapshot creation so
 * that every snapshot is an atomic view of exactly which transactions were
 * in-progress at the moment the snapshot was taken. WAL logging and commit-log
 * writes are done outside this lock (they involve disk I/O and have their own
 * internal synchronization).
 *
 * <p><b>Undo.</b> The in-memory undo log ({@link Transaction#reverseUndoLog()})
 * is populated by the MVCC Manager (Phase 10). The optional {@link UndoCallback}
 * is applied during {@link #abortTransaction} to reverse version-store changes;
 * WAL-based undo during recovery is handled separately by the Recovery Manager.
 *
 * <p><b>Snapshot stub.</b> Snapshot creation in {@link #beginTransaction()} is
 * inlined for Phase 8. The SnapshotManager (Phase 9) will wrap and replace this
 * logic with isolation-level switching without changing the public API.
 */
public final class TransactionManager {

    private final WALManager walManager;
    private final CommitLog commitLog;

    /**
     * Guards {@code nextXid}, {@code activeTransactions}, and snapshot creation.
     * Released before any disk I/O.
     */
    private final ReentrantLock txnLock = new ReentrantLock();

    /** Next XID to assign. Read and incremented only while holding {@code txnLock}. */
    private final AtomicLong nextXid;

    /** All transactions that have begun but not yet committed or aborted. */
    private final Map<Long, Transaction> activeTransactions = new HashMap<>();

    /**
     * Optional callback for reversing version-store changes on abort. Null until
     * wired by the MVCC Manager (Phase 10). When null, abort still logs and marks
     * the commit log — WAL-based recovery handles the physical undo on restart.
     */
    private volatile UndoCallback undoCallback = null;

    /**
     * @param startXid   the first XID to assign; use 1 for a fresh database, or
     *                   {@code maxRecoveredXid + 1} after crash recovery
     * @param walManager WAL manager for logging BEGIN/COMMIT/ABORT
     * @param commitLog  persistent status store updated on every commit/abort
     */
    public TransactionManager(long startXid, WALManager walManager, CommitLog commitLog) {
        if (startXid < 1) {
            throw new IllegalArgumentException("startXid must be >= 1, got " + startXid);
        }
        this.nextXid = new AtomicLong(startXid);
        this.walManager = walManager;
        this.commitLog = commitLog;
    }

    /**
     * Wire the undo callback. Must be called before any transaction aborts.
     * Idempotent; the last setter wins.
     */
    public void setUndoCallback(UndoCallback callback) {
        this.undoCallback = callback;
    }

    // -------------------------------------------------------------------------
    // 8.3 — beginTransaction
    // -------------------------------------------------------------------------

    /**
     * Begin a new transaction. Atomically assigns an XID, captures an
     * immutable snapshot of current transaction activity, logs a WAL BEGIN
     * record, and registers the transaction as active.
     *
     * <p>The snapshot reflects the exact set of active XIDs at the moment the
     * XID was assigned, so no concurrent begin/commit can produce a
     * partially-visible view.
     */
    public Transaction beginTransaction() {
        long xid;
        Snapshot snapshot;

        txnLock.lock();
        try {
            xid = nextXid.getAndIncrement();
            snapshot = captureSnapshot();
            // Register as active before releasing the lock so concurrent
            // snapshots include this XID in their inProgressXids.
            activeTransactions.put(xid, null); // placeholder; replaced after WAL
        } finally {
            txnLock.unlock();
        }

        // WAL I/O outside the lock — the placeholder entry ensures snapshot
        // consistency while we do the disk write.
        walManager.logBegin(xid);

        Transaction txn = new Transaction(xid, snapshot);

        txnLock.lock();
        try {
            activeTransactions.put(xid, txn); // replace placeholder
        } finally {
            txnLock.unlock();
        }

        return txn;
    }

    // -------------------------------------------------------------------------
    // 8.4 — commitTransaction / abortTransaction
    // -------------------------------------------------------------------------

    /**
     * Commit a transaction. Synchronously flushes a WAL COMMIT record (so the
     * commit is durable before returning), then marks the XID COMMITTED in the
     * commit log and removes it from the active set.
     */
    public void commitTransaction(Transaction txn) {
        walManager.logCommit(txn.xid); // synchronous flush — durable before return
        commitLog.setStatus(txn.xid, TxStatus.COMMITTED);
        txn.setStatus(TxStatus.COMMITTED);

        txnLock.lock();
        try {
            activeTransactions.remove(txn.xid);
        } finally {
            txnLock.unlock();
        }
    }

    /**
     * Abort a transaction. Applies the in-memory undo log in reverse order via
     * the {@link UndoCallback} (if wired), then synchronously flushes a WAL
     * ABORT record and marks the XID ABORTED in the commit log.
     */
    public void abortTransaction(Transaction txn) {
        // Reverse in-memory changes via the MVCC Manager callback (Phase 10).
        UndoCallback cb = undoCallback;
        if (cb != null) {
            List<UndoRecord> entries = txn.reverseUndoLog();
            for (UndoRecord entry : entries) {
                cb.apply(txn.xid, entry.rid(), entry.oldData());
            }
        }

        walManager.logAbort(txn.xid); // synchronous flush
        commitLog.setStatus(txn.xid, TxStatus.ABORTED);
        txn.setStatus(TxStatus.ABORTED);

        txnLock.lock();
        try {
            activeTransactions.remove(txn.xid);
        } finally {
            txnLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // 8.5 — isCommitted / getOldestActiveXid
    // -------------------------------------------------------------------------

    /**
     * Whether {@code xid} has committed, according to the commit log. O(1).
     * Called on the hot visibility path by the MVCC Manager (Phase 10).
     */
    public boolean isCommitted(long xid) {
        return commitLog.isCommitted(xid);
    }

    /**
     * The MVCC horizon: the smallest XID still in-progress, or {@code nextXid}
     * if no transactions are currently active. Any version with
     * {@code xmax < horizon AND isCommitted(xmax)} is invisible to all running
     * and future transactions and can be garbage-collected.
     */
    public long getOldestActiveXid() {
        txnLock.lock();
        try {
            return activeTransactions.keySet().stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElseGet(nextXid::get);
        } finally {
            txnLock.unlock();
        }
    }

    /**
     * A snapshot of the current active transaction table suitable for embedding
     * in a WAL CHECKPOINT record. Maps {@code xid → 0} (lastLsn is tracked by
     * the WAL Manager, not here).
     */
    public Map<Long, Long> activeTransactionTableForCheckpoint() {
        txnLock.lock();
        try {
            Map<Long, Long> att = new HashMap<>();
            for (Long xid : activeTransactions.keySet()) {
                att.put(xid, 0L);
            }
            return Collections.unmodifiableMap(att);
        } finally {
            txnLock.unlock();
        }
    }

    /**
     * The set of XIDs currently active. Used by tests and the SnapshotManager.
     */
    public Set<Long> activeXids() {
        txnLock.lock();
        try {
            return new HashSet<>(activeTransactions.keySet());
        } finally {
            txnLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Capture an immutable snapshot of the current active set. Must be called
     * while holding {@code txnLock}. The snapshot's {@code xmax} is the current
     * {@code nextXid} value (the next XID to be assigned), so any transaction
     * whose XID is >= xmax did not exist when this snapshot was taken.
     */
    private Snapshot captureSnapshot() {
        Set<Long> inProgress = new HashSet<>(activeTransactions.keySet());
        long xmax = nextXid.get();
        long xmin = inProgress.stream().mapToLong(Long::longValue).min().orElse(xmax);
        return new Snapshot(xmin, xmax, inProgress);
    }
}
