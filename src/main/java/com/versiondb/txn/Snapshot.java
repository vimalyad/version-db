package com.versiondb.txn;

import java.util.Set;

/**
 * An immutable snapshot of transaction activity at a single point in time,
 * used by the visibility rule to decide which tuple versions a transaction
 * can see.
 *
 * <ul>
 *   <li>{@code xmin} — the smallest active XID at snapshot time. Every
 *       transaction with {@code xid < xmin} had already committed or aborted,
 *       so versions they created may be visible.</li>
 *   <li>{@code xmax} — the value of {@code nextXid} at snapshot time. Every
 *       transaction with {@code xid >= xmax} did not exist yet and is
 *       invisible.</li>
 *   <li>{@code inProgressXids} — XIDs that had started but not yet committed
 *       or aborted at snapshot time. Versions created by these transactions are
 *       invisible even though their XIDs fall in {@code [xmin, xmax)}.</li>
 * </ul>
 *
 * <p>Snapshot objects are created by {@link TransactionManager#beginTransaction}
 * (Phase 8 stub) and will be delegated to a dedicated SnapshotManager in
 * Phase 9.
 */
public record Snapshot(long xmin, long xmax, Set<Long> inProgressXids) {

    /**
     * Construct a snapshot. The caller must pass an unmodifiable or otherwise
     * safely owned copy of the in-progress set.
     */
    public Snapshot {
        inProgressXids = Set.copyOf(inProgressXids); // defensive copy + unmodifiable
    }

    /**
     * Whether {@code xid} was still in progress when this snapshot was taken.
     * A version created by an in-progress transaction is invisible to this
     * snapshot.
     */
    public boolean isInProgress(long xid) {
        return inProgressXids.contains(xid);
    }
}
