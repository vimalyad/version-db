package com.versiondb.txn;

import com.versiondb.shared.RID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The per-transaction state object that travels with every operation. Created
 * by {@link TransactionManager#beginTransaction()} and invalidated (status set
 * to {@link TxStatus#COMMITTED} or {@link TxStatus#ABORTED}) by commit/abort.
 *
 * <p><b>Concurrency note:</b> a Transaction object is owned by a single client
 * thread for the duration of the transaction. The only field that may be read
 * by other threads concurrently is {@code status} (via {@link #getStatus()}),
 * which is declared {@code volatile} for safe publication. The undo log and
 * write set are accessed only by the owning thread and by
 * {@link TransactionManager} during abort (at which point the client thread
 * must not perform any further writes), so they need no additional locking.
 */
public final class Transaction {

    /** This transaction's unique, never-reused XID. */
    public final long xid;

    /** Isolation level fixed at begin time; decides snapshot lifetime. */
    public final IsolationLevel isolationLevel;

    /**
     * The snapshot reads are evaluated against. Under REPEATABLE READ it is the
     * begin-time snapshot and never changes; under READ COMMITTED the
     * TransactionManager refreshes it at each statement boundary.
     */
    private volatile Snapshot snapshot;

    /** Current lifecycle status. Volatile for cross-thread visibility on commit/abort. */
    private volatile TxStatus status;

    /**
     * In-memory undo log. Each entry records one write so that abort can
     * reverse it. Populated by the MVCC Manager (Phase 10).
     */
    private final List<UndoRecord> undoLog = new ArrayList<>();

    /**
     * Set of RIDs written by this transaction. Used for conflict detection
     * (first-writer-wins) in the MVCC Manager.
     */
    private final Set<RID> writeSet = new HashSet<>();

    /** Convenience constructor defaulting to the {@link IsolationLevel#DEFAULT} level. */
    Transaction(long xid, Snapshot snapshot) {
        this(xid, snapshot, IsolationLevel.DEFAULT);
    }

    Transaction(long xid, Snapshot snapshot, IsolationLevel isolationLevel) {
        this.xid = xid;
        this.snapshot = snapshot;
        this.isolationLevel = isolationLevel;
        this.status = TxStatus.IN_PROGRESS;
    }

    // ---- Accessors ----------------------------------------------------------

    public Snapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Package-private: replace the current snapshot. Used by the
     * TransactionManager to refresh a READ COMMITTED transaction's snapshot at
     * each statement boundary.
     */
    void setSnapshot(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    public TxStatus getStatus() {
        return status;
    }

    /** Package-private: set only by TransactionManager. */
    void setStatus(TxStatus status) {
        this.status = status;
    }

    // ---- Undo log (called by MVCC Manager, Phase 10) -----------------------

    /**
     * Append one undo entry. {@code oldData == null} for an INSERT; non-null
     * bytes for a DELETE or UPDATE (the prior tuple bytes to restore on rollback).
     */
    public void addUndoRecord(RID rid, byte[] oldData) {
        undoLog.add(new UndoRecord(rid, oldData));
    }

    // ---- Write set (called by MVCC Manager, Phase 10) ----------------------

    /** Record that this transaction wrote to {@code rid}. */
    public void addToWriteSet(RID rid) {
        writeSet.add(rid);
    }

    /** Whether this transaction has already written to {@code rid}. */
    public boolean inWriteSet(RID rid) {
        return writeSet.contains(rid);
    }

    // ---- Package-private: for TransactionManager ---------------------------

    /**
     * An unmodifiable, reverse-order view of the undo log, for use by
     * {@link TransactionManager#abortTransaction(Transaction)}.
     */
    List<UndoRecord> reverseUndoLog() {
        List<UndoRecord> reversed = new ArrayList<>(undoLog);
        Collections.reverse(reversed);
        return Collections.unmodifiableList(reversed);
    }

    @Override
    public String toString() {
        return "Transaction{xid=" + xid + ", status=" + status + "}";
    }
}
