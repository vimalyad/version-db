package com.minidb.txn;

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
 *   <li>11.2 — {@code runOnce()} chain sweep (added next)</li>
 *   <li>11.3 — background scheduler + physical heap-slot reclamation</li>
 * </ul>
 */
public final class Vacuum {

    private final VersionStore versionStore;
    private final CommitLog commitLog;
    private final TransactionManager txnManager;

    public Vacuum(VersionStore versionStore,
                  CommitLog commitLog,
                  TransactionManager txnManager) {
        this.versionStore = versionStore;
        this.commitLog = commitLog;
        this.txnManager = txnManager;
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
}
