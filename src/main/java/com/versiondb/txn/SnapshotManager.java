package com.versiondb.txn;

/**
 * Creates {@link Snapshot}s — point-in-time descriptions of which transactions
 * had committed when the snapshot was taken. A snapshot is the basis for every
 * read: under Snapshot Isolation a transaction sees the world exactly as it was
 * the moment its snapshot was captured, regardless of later commits.
 *
 * <p>The capture must be <em>atomic</em> with respect to XID assignment and the
 * active-transaction set: if a snapshot recorded {@code xmax} (the next XID)
 * without the matching active-set membership, a concurrent {@code begin} could
 * produce a view in which an XID is below {@code xmax} yet absent from
 * {@code inProgressXids} despite never having committed. To avoid that race the
 * actual capture is delegated to {@link TransactionManager#captureSnapshot()},
 * which performs it while holding the manager's lock.
 */
public final class SnapshotManager {

    private final TransactionManager txnManager;

    SnapshotManager(TransactionManager txnManager) {
        this.txnManager = txnManager;
    }

    /**
     * Capture an immutable snapshot of current transaction activity:
     * <ul>
     *   <li>{@code xmin} = the smallest active XID, or {@code nextXid} if none;</li>
     *   <li>{@code xmax} = {@code nextXid} (the next XID to be assigned);</li>
     *   <li>{@code inProgressXids} = a copy of the currently active XIDs.</li>
     * </ul>
     * The three fields are read under the Transaction Manager's lock so they
     * describe one consistent instant.
     */
    public Snapshot createSnapshot() {
        return txnManager.captureSnapshot();
    }
}
