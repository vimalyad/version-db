package com.versiondb.txn;

import com.versiondb.shared.RID;

import java.util.ArrayList;
import java.util.List;

/**
 * Version garbage collection (PostgreSQL calls it VACUUM), per
 * {@code part3.md §8}. Over time every UPDATE creates a new version and every
 * DELETE leaves the old version in place, so the {@link VersionStore} grows
 * without bound and version-chain traversal degrades from O(1) toward
 * O(versions_per_row). Vacuum reclaims versions that are invisible to every
 * currently-active and future transaction.
 *
 * <p><b>The horizon.</b> {@link TransactionManager#getOldestActiveXid()} is the
 * MVCC horizon: the smallest XID that is still in progress (or the next XID to
 * be assigned when nothing is active). A version's deletion is visible to
 * <em>everyone</em> once the deleting transaction has committed and is older
 * than the horizon, so the version can never again be read and is safe to drop.
 *
 * <p>This class is built up across the Phase 11 sub-phases:
 * <ul>
 *   <li>11.1 — {@link #isReclaimable} reclaim predicate + {@link #currentHorizon}</li>
 *   <li>11.2 — {@link #runOnce()} chain sweep</li>
 *   <li>11.3 — background scheduler ({@link #start}/{@link #stop}) + physical
 *       heap-slot reclamation for fully-dead rows</li>
 * </ul>
 */
public final class Vacuum {

    private final VersionStore versionStore;
    private final CommitLog commitLog;
    private final TransactionManager txnManager;
    private final HeapReclaimer heapReclaimer;

    /** Background worker; non-null only while the scheduler is running. */
    private volatile Thread worker;
    private volatile boolean running;

    /**
     * Create a vacuum that reclaims versions only. Physical heap slots for
     * fully-dead rows are left in place (no-op reclaimer).
     */
    public Vacuum(VersionStore versionStore,
                  CommitLog commitLog,
                  TransactionManager txnManager) {
        this(versionStore, commitLog, txnManager, HeapReclaimer.NO_OP);
    }

    /**
     * Create a vacuum that also physically frees heap slots for fully-dead rows
     * via {@code heapReclaimer}.
     */
    public Vacuum(VersionStore versionStore,
                  CommitLog commitLog,
                  TransactionManager txnManager,
                  HeapReclaimer heapReclaimer) {
        this.versionStore = versionStore;
        this.commitLog = commitLog;
        this.txnManager = txnManager;
        this.heapReclaimer = heapReclaimer;
    }

    /**
     * The current MVCC horizon — the oldest XID that any running transaction
     * could still observe. Versions deleted by a committed transaction older
     * than this are unreachable.
     */
    public long currentHorizon() {
        return txnManager.getOldestActiveXid();
    }

    /**
     * Whether {@code version} can be reclaimed given the supplied
     * {@code horizon}. Implements the predicate from {@code part3.md §8.2}:
     *
     * <pre>
     * version.xmax != 0
     *   AND version.xmax &lt; horizon
     *   AND CommitLog.isCommitted(version.xmax)
     * </pre>
     *
     * <ul>
     *   <li>{@code xmax == 0} — the version is the live head; never reclaim.</li>
     *   <li>{@code xmax >= horizon} — some active transaction may still see the
     *       version (its deletion is not yet visible to all); keep it.</li>
     *   <li>deleter not committed — the deletion may still be rolled back
     *       (abort resets {@code xmax} to 0); keep it.</li>
     * </ul>
     */
    public boolean isReclaimable(VersionRecord version, long horizon) {
        long xmax = version.xmax;
        return xmax != 0
                && xmax < horizon
                && commitLog.isCommitted(xmax);
    }

    // =========================================================================
    // 11.2 — GC pass over version chains
    // =========================================================================

    /**
     * Run one garbage-collection pass over every version chain. Computes the
     * horizon once, then sweeps each chain, removing the reclaimable versions.
     *
     * @return the total number of versions reclaimed in this pass
     */
    public long runOnce() {
        long horizon = currentHorizon();
        long reclaimed = 0;
        for (RID rid : versionStore.chainHeadsSnapshot().keySet()) {
            reclaimed += gcChain(rid, horizon);
        }
        return reclaimed;
    }

    /**
     * Reclaim the oldest, contiguous run of reclaimable versions from a single
     * chain, following the procedure in {@code part3.md §8.3}: walk from the
     * tail (oldest) toward the head, removing versions while
     * {@link #isReclaimable} holds, and stop at the first version that must be
     * kept (live head, in-progress deleter, or deletion newer than the
     * horizon). The surviving oldest version becomes the new tail and has its
     * {@code prevVersionId} reset to {@code -1}.
     *
     * <p>Removing only a contiguous tail run is safe under concurrency:
     * reclaimability is monotonic (once a deleter is committed and older than
     * the horizon it stays that way), and a reader that has already followed a
     * link into the removed run simply hits a tombstone and stops — every
     * version in that run is invisible to it anyway.
     *
     * @return the number of versions removed from this chain
     */
    private long gcChain(RID rid, long horizon) {
        // Snapshot the chain newest (index 0) → oldest.
        List<Long> ids = new ArrayList<>();
        List<VersionRecord> recs = new ArrayList<>();
        long cur = versionStore.getHeadVersionId(rid);
        while (cur != -1) {
            VersionRecord v = versionStore.get(cur);
            if (v == null) break; // already reclaimed — end of chain
            ids.add(cur);
            recs.add(v);
            cur = v.prevVersionId;
        }
        if (recs.isEmpty()) return 0;

        // From the tail upward, find the first version that must be kept. Every
        // version at index >= firstRemoved is a reclaimable tail version.
        int firstRemoved = recs.size();
        for (int i = recs.size() - 1; i >= 0; i--) {
            if (isReclaimable(recs.get(i), horizon)) {
                firstRemoved = i;
            } else {
                break;
            }
        }
        if (firstRemoved == recs.size()) return 0; // nothing reclaimable

        long removed = 0;
        for (int i = firstRemoved; i < ids.size(); i++) {
            versionStore.remove(ids.get(i));
            removed++;
        }

        if (firstRemoved > 0) {
            // Some versions survive: detach the surviving oldest as the new tail.
            recs.get(firstRemoved - 1).prevVersionId = -1L;
        } else {
            // The head itself was reclaimable: the row is fully dead. Physically
            // free its heap slot and stop tracking the RID. Safe because the
            // deletion is visible to every transaction, so no reader will ever
            // resolve a version for this RID again.
            heapReclaimer.freeSlot(rid);
            versionStore.removeHead(rid);
        }
        return removed;
    }

    // =========================================================================
    // 11.3 — background scheduler
    // =========================================================================

    /**
     * Start a background daemon thread that runs {@link #runOnce()} every
     * {@code periodMillis} milliseconds. Idempotent: a second call while already
     * running has no effect. In production this is triggered periodically (e.g.
     * every N transactions); a fixed period keeps the capstone simple.
     */
    public synchronized void start(long periodMillis) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(periodMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!running) break;
                try {
                    runOnce();
                } catch (RuntimeException e) {
                    // A transient failure must not kill the vacuum thread; the
                    // next tick retries.
                }
            }
        }, "versiondb-vacuum");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Stop the background scheduler and wait briefly for the worker to exit. */
    public synchronized void stop() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    /** Whether the background scheduler is currently running. */
    public boolean isRunning() {
        return running;
    }
}
