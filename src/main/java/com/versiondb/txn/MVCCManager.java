package com.versiondb.txn;

import com.versiondb.shared.RID;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.TransactionConflictException;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.HeapFile;
import com.versiondb.storage.Page;
import com.versiondb.wal.WALManager;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Multi-version concurrency control: manages version chains, enforces
 * visibility rules, and routes all tuple writes through WAL logging.
 *
 * <p><b>Design (Option B — separate version store).</b> The heap file holds
 * the current physical bytes of each slot. Every version of every tuple lives
 * in the {@link VersionStore}; {@link VersionRecord#prevVersionId} links
 * versions newest-to-oldest for traversal.
 *
 * <p><b>Sub-phase mapping:</b>
 * <ul>
 *   <li>10.2 — {@link #isVisible} / {@link #isXidVisible}</li>
 *   <li>10.3 — {@link #getVisibleVersion}</li>
 *   <li>10.4 — {@link #insert}</li>
 *   <li>10.5 — {@link #delete}</li>
 *   <li>10.6 — {@link #update}</li>
 *   <li>10.7 — {@link #undoCallback()} (abort undo)</li>
 *   <li>10.8 — per-RID striped locks</li>
 * </ul>
 *
 * <p><b>UPDATE chains.</b> An UPDATE is a DELETE of the old RID followed by
 * an INSERT at a new RID; the two chains are independent (no
 * {@code prevVersionId} link across RIDs). Old readers scanning the old RID
 * see the old data (xmax not yet visible to them); new readers scanning the
 * new RID see the new data; neither sees a phantom cross-chain version.
 *
 * <p><b>Same-transaction visibility.</b> A version created by the current
 * transaction ({@code xmin == txn.xid}) is always visible to that transaction
 * if it has not been deleted by the same transaction ({@code xmax == txn.xid}).
 * This lets SELECT within the same transaction see its own uncommitted INSERTs.
 *
 * <p><b>INSERT undo sentinel.</b> Aborting an INSERT sets {@code version.xmin
 * = 0}. The visibility rule treats {@code xmin = 0} as permanently invisible
 * (no transaction has xid 0 and isCommitted(0) is always false).
 */
public final class MVCCManager {

    private static final int STRIPE_COUNT = 1024;

    private final VersionStore versionStore;
    private final CommitLog commitLog;
    private final WALManager walManager;
    private final BufferPool bufferPool;

    /** Per-RID striped locks (10.8). Writers hold the stripe for the RID. */
    private final ReentrantLock[] stripes;

    public MVCCManager(VersionStore versionStore,
                       CommitLog commitLog,
                       WALManager walManager,
                       BufferPool bufferPool) {
        this.versionStore = versionStore;
        this.commitLog = commitLog;
        this.walManager = walManager;
        this.bufferPool = bufferPool;
        this.stripes = new ReentrantLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    // =========================================================================
    // 10.2 — Visibility rule
    // =========================================================================

    /**
     * Whether {@code version} is visible to a transaction with XID
     * {@code txnXid} and the given {@code snapshot}.
     *
     * <p>Same-transaction shortcut: a version created by this transaction is
     * immediately visible unless this same transaction also deleted it.
     *
     * <p>Standard rule (from {@code part3.md §6.4}):
     * <ol>
     *   <li>xmin must be visible (the creator committed before the snapshot).</li>
     *   <li>xmax must be 0, or the deleter must NOT be visible (the deletion
     *       happened after or by an uncommitted transaction).</li>
     * </ol>
     */
    public boolean isVisible(VersionRecord version, long txnXid, Snapshot snapshot) {
        // Same-transaction shortcut
        if (version.xmin == txnXid) {
            // Visible unless the current transaction itself deleted it
            return version.xmax == 0 || version.xmax != txnXid;
        }

        // xmin == 0 is the aborted-insert sentinel — permanently invisible
        if (version.xmin == 0) return false;

        // Step 1: was the creator visible when the snapshot was taken?
        if (!isXidVisible(version.xmin, snapshot)) return false;

        // Step 2: is the deletion (if any) also visible?
        if (version.xmax == 0) return true;         // not deleted
        if (version.xmax == txnXid) return false;  // deleted by current txn

        return !isXidVisible(version.xmax, snapshot); // visible iff deleter is invisible
    }

    /**
     * Whether the transaction {@code xid} had committed by the time
     * {@code snapshot} was captured. Matches the formula in
     * {@code part3.md §6.4}:
     *
     * <pre>
     * xid_visible = (xid &lt; snapshot.xmin)
     *            OR (xid &lt; snapshot.xmax
     *                AND xid NOT IN snapshot.inProgressXids
     *                AND commitLog.isCommitted(xid))
     * </pre>
     */
    boolean isXidVisible(long xid, Snapshot snapshot) {
        if (xid <= 0) return false; // sentinel / invalid
        if (xid < snapshot.xmin()) return true;
        if (xid >= snapshot.xmax()) return false;
        if (snapshot.isInProgress(xid)) return false;
        return commitLog.isCommitted(xid);
    }

    // =========================================================================
    // 10.3 — getVisibleVersion (the hot read path)
    // =========================================================================

    /**
     * Walk the version chain for {@code rid} (newest-to-oldest) and return the
     * raw bytes of the first version that is visible to the given snapshot.
     * Returns {@code null} if no version is visible (the tuple does not exist
     * from this snapshot's perspective).
     *
     * @param rid      physical address of the tuple in the heap
     * @param txnXid   the reading transaction's XID (for same-txn visibility)
     * @param snapshot the snapshot to evaluate visibility against
     */
    public byte[] getVisibleVersion(RID rid, long txnXid, Snapshot snapshot) {
        long currentId = versionStore.getHeadVersionId(rid);
        while (currentId != -1) {
            VersionRecord ver = versionStore.get(currentId);
            // The vacuum process tombstones reclaimed versions to null. A
            // reclaimed version is invisible to every active transaction, so a
            // null here is the effective end of the chain for this reader.
            if (ver == null) break;
            if (isVisible(ver, txnXid, snapshot)) {
                return ver.data;
            }
            currentId = ver.prevVersionId;
        }
        return null;
    }

    // =========================================================================
    // 10.4 — insert
    // =========================================================================

    /**
     * Insert a new tuple. Physically writes the bytes into the heap, creates a
     * version record ({@code xmin=txn.xid, xmax=0}), logs a WAL INSERT, sets
     * the page LSN, and records an undo entry so abort can invalidate the
     * insert.
     *
     * @return the RID assigned to the new tuple by the heap file
     */
    public RID insert(Transaction txn, HeapFile heapFile, byte[] data) {
        // 1. Physical write into the heap
        RID rid = heapFile.insertTuple(data);

        // 2. Create version record
        VersionRecord ver = new VersionRecord(txn.xid, 0L, rid, -1L, data);
        long versionId = versionStore.allocate(ver);
        versionStore.setHead(rid, versionId);

        // 3. Undo log: null oldData signals INSERT (undo = invalidate version)
        txn.addUndoRecord(rid, null);
        txn.addToWriteSet(rid);

        // 4. WAL + LSN
        long lsn = walManager.logInsert(txn.xid, rid.pageId(), rid.slotId(), data);
        setPageLsn(rid.pageId(), lsn);

        return rid;
    }

    // =========================================================================
    // 10.5 — delete
    // =========================================================================

    /**
     * Delete the current version of the tuple at {@code rid}. Stamps
     * {@code xmax = txn.xid} on the head version, logs a WAL DELETE, records
     * an undo entry, and enforces first-writer-wins conflict detection.
     *
     * @throws TransactionConflictException if another in-progress transaction
     *         has already stamped {@code xmax} on this version
     */
    public void delete(Transaction txn, HeapFile heapFile, RID rid) {
        ReentrantLock stripe = stripeFor(rid);
        stripe.lock();
        try {
            long headId = versionStore.getHeadVersionId(rid);
            if (headId == -1) {
                throw new StorageException("No version chain for RID " + rid);
            }
            VersionRecord head = versionStore.get(headId);

            // First-writer-wins: abort if another live transaction holds xmax
            checkNoConflict(head, txn.xid);

            byte[] oldData = head.data;
            head.xmax = txn.xid;

            txn.addUndoRecord(rid, oldData);
            txn.addToWriteSet(rid);

            long lsn = walManager.logDelete(txn.xid, rid.pageId(), rid.slotId(), oldData);
            setPageLsn(rid.pageId(), lsn);
        } finally {
            stripe.unlock();
        }
    }

    // =========================================================================
    // 10.6 — update
    // =========================================================================

    /**
     * Update the tuple at {@code oldRid} with {@code newData}. This is a
     * logical DELETE of the old version followed by an INSERT of the new one
     * at a freshly-allocated heap slot.
     *
     * <p>The two RID chains are kept <em>independent</em>: the new version's
     * {@code prevVersionId} is {@code -1} (it starts a fresh chain at
     * {@code newRid}). This avoids cross-RID chain traversal that would make
     * readers looking up {@code newRid} accidentally see old-version data.
     *
     * @return the RID of the newly inserted version
     */
    public RID update(Transaction txn, HeapFile heapFile, RID oldRid, byte[] newData) {
        ReentrantLock stripe = stripeFor(oldRid);
        stripe.lock();
        try {
            long headId = versionStore.getHeadVersionId(oldRid);
            if (headId == -1) {
                throw new StorageException("No version chain for RID " + oldRid);
            }
            VersionRecord head = versionStore.get(headId);

            checkNoConflict(head, txn.xid);

            byte[] oldData = head.data;

            // --- Delete old version ---
            head.xmax = txn.xid;
            txn.addUndoRecord(oldRid, oldData);
            txn.addToWriteSet(oldRid);
            long lsnDel = walManager.logDelete(txn.xid, oldRid.pageId(), oldRid.slotId(), oldData);
            setPageLsn(oldRid.pageId(), lsnDel);

            // --- Insert new version (independent chain) ---
            RID newRid = heapFile.insertTuple(newData);
            VersionRecord newVer = new VersionRecord(txn.xid, 0L, newRid, -1L, newData);
            long newVerId = versionStore.allocate(newVer);
            versionStore.setHead(newRid, newVerId);
            txn.addUndoRecord(newRid, null); // INSERT undo for the new slot
            txn.addToWriteSet(newRid);
            long lsnIns = walManager.logInsert(txn.xid, newRid.pageId(), newRid.slotId(), newData);
            setPageLsn(newRid.pageId(), lsnIns);

            return newRid;
        } finally {
            stripe.unlock();
        }
    }

    // =========================================================================
    // 10.7 — UndoCallback (abort handling)
    // =========================================================================

    /**
     * Returns the {@link UndoCallback} that the {@link TransactionManager}
     * should use. Wire it via
     * {@link TransactionManager#setUndoCallback(UndoCallback)} after construction.
     *
     * <p>On abort, entries in the undo log are processed newest-first:
     * <ul>
     *   <li>{@code oldData == null} → INSERT undo: set {@code xmin = 0} on the
     *       head version so the visibility rule permanently excludes it.</li>
     *   <li>{@code oldData != null} → DELETE/UPDATE undo: set {@code xmax = 0}
     *       on the head version to restore it as the live version.</li>
     * </ul>
     */
    public UndoCallback undoCallback() {
        return (txnId, rid, oldData) -> {
            ReentrantLock stripe = stripeFor(rid);
            stripe.lock();
            try {
                long headId = versionStore.getHeadVersionId(rid);
                if (headId == -1) return; // nothing to undo
                VersionRecord head = versionStore.get(headId);
                if (oldData == null) {
                    // INSERT undo: sentinel xmin=0 makes the version permanently invisible
                    head.xmin = 0;
                } else {
                    // DELETE/UPDATE undo: clear xmax to restore the version as live
                    head.xmax = 0;
                }
            } finally {
                stripe.unlock();
            }
        };
    }

    // =========================================================================
    // Internal helpers (10.8 — striped locks)
    // =========================================================================

    /** Return the stripe lock for {@code rid}. */
    private ReentrantLock stripeFor(RID rid) {
        return stripes[Math.abs(rid.hashCode() % STRIPE_COUNT)];
    }

    /**
     * Enforce first-writer-wins: if {@code head.xmax} is set by an in-progress
     * transaction that is not the current one, throw
     * {@link TransactionConflictException}.
     */
    private void checkNoConflict(VersionRecord head, long txnXid) {
        if (head.xmax != 0
                && head.xmax != txnXid
                && !commitLog.isCommitted(head.xmax)) {
            throw new TransactionConflictException(
                    "Concurrent write conflict on RID " + head.rid
                    + ": held by xid " + head.xmax);
        }
    }

    /**
     * Fetch the page, update its LSN to {@code lsn}, mark it dirty, and unpin.
     */
    private void setPageLsn(int pageId, long lsn) {
        Page page = bufferPool.fetchPage(pageId);
        try {
            page.setLsn(lsn);
        } finally {
            bufferPool.unpin(pageId, true);
        }
    }
}
