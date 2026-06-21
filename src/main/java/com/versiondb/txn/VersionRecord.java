package com.versiondb.txn;

import com.versiondb.shared.RID;

/**
 * One version of a tuple's data, as stored in the {@link VersionStore}.
 *
 * <p>Every write operation (INSERT, DELETE, UPDATE) produces or modifies a
 * version record rather than updating the heap in place, so concurrent readers
 * always have a consistent version to read.
 *
 * <p>{@code xmin} and {@code xmax} are {@code volatile} so that readers on
 * other threads always see the latest stamped value. Writes to these fields
 * are always performed while holding the appropriate per-RID stripe lock in
 * {@link MVCCManager}; the volatile merely ensures readers do not cache a
 * stale value.
 *
 * <p><b>Sentinel values:</b>
 * <ul>
 *   <li>{@code xmin = 0} — "aborted insert" sentinel; set during abort undo of
 *       an INSERT so the visibility rule treats the version as permanently
 *       invisible.</li>
 *   <li>{@code xmax = 0} — "not deleted"; the version is the current head.</li>
 *   <li>{@code prevVersionId = -1} — no prior version in this chain (this is
 *       the first version of the row).</li>
 * </ul>
 */
public final class VersionRecord {

    /** XID of the transaction that created this version. {@code 0} = aborted-insert sentinel. */
    public volatile long xmin;

    /** XID of the transaction that deleted/replaced this version. {@code 0} = still live. */
    public volatile long xmax;

    /** Physical address of the heap slot that holds this version's raw bytes. */
    public final RID rid;

    /**
     * Index of the previous (next-older) version in this chain inside the
     * {@link VersionStore}, or {@code -1} if this is the first version of the
     * row.
     *
     * <p>Mutable and {@code volatile}: when the vacuum process reclaims the
     * oldest versions of a chain, the surviving oldest version becomes the new
     * tail and has its {@code prevVersionId} reset to {@code -1}. Readers walk
     * the chain via this link, so the volatile ensures they never observe a
     * stale predecessor id.
     */
    public volatile long prevVersionId;

    /** The raw tuple bytes for this version. */
    public final byte[] data;

    public VersionRecord(long xmin, long xmax, RID rid, long prevVersionId, byte[] data) {
        this.xmin = xmin;
        this.xmax = xmax;
        this.rid = rid;
        this.prevVersionId = prevVersionId;
        this.data = data;
    }
}
