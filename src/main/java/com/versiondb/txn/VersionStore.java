package com.versiondb.txn;

import com.versiondb.shared.RID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory store of all {@link VersionRecord}s, implementing Option B
 * (separate version store) from {@code part3.md §6.2}. The heap file holds
 * only the current physical bytes for a slot; every historical and in-flight
 * version lives here.
 *
 * <p><b>Storage.</b> Versions are kept in a single {@code ArrayList} so each
 * version has a stable, monotonically-increasing {@code versionId} (its list
 * index). A separate {@code chainHead} map records the head (most-recent)
 * version id for each RID; chain traversal follows
 * {@link VersionRecord#prevVersionId} links.
 *
 * <p><b>Thread safety.</b> The list and map are protected by a single
 * {@link ReentrantReadWriteLock}: multiple readers share the read lock;
 * allocations and head updates require the write lock. Per-RID conflict
 * detection and xmax-stamping use the striped locks in
 * {@link MVCCManager} and rely on the volatile xmin/xmax fields in
 * {@link VersionRecord}.
 */
public final class VersionStore {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** All versions, indexed by versionId (= position in the list). */
    private final List<VersionRecord> versions = new ArrayList<>();

    /** Maps each RID to the versionId of its current (most-recent) chain head. */
    private final Map<RID, Long> chainHead = new HashMap<>();

    /**
     * Store a new version record and return its assigned {@code versionId}.
     */
    public long allocate(VersionRecord record) {
        rwLock.writeLock().lock();
        try {
            long id = versions.size();
            versions.add(record);
            return id;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Retrieve the version record with the given id.
     *
     * @throws IndexOutOfBoundsException if {@code versionId} is out of range
     */
    public VersionRecord get(long versionId) {
        rwLock.readLock().lock();
        try {
            return versions.get((int) versionId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Return the versionId of the head (most-recent) version for {@code rid},
     * or {@code -1} if no version has been registered for this RID.
     */
    public long getHeadVersionId(RID rid) {
        rwLock.readLock().lock();
        try {
            Long id = chainHead.get(rid);
            return id == null ? -1L : id;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Set (or update) the chain head for {@code rid} to {@code versionId}.
     * Called after INSERT and UPDATE to register the new latest version.
     */
    public void setHead(RID rid, long versionId) {
        rwLock.writeLock().lock();
        try {
            chainHead.put(rid, versionId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Reclaim the version record at {@code versionId}, replacing it with a
     * tombstone ({@code null}). The slot is kept so that every other version's
     * {@code versionId} (its list index) stays stable; only the record is
     * dropped, letting the JVM reclaim its memory. A no-op if the id is out of
     * range or already tombstoned.
     *
     * <p>Called by the vacuum process for versions that are invisible to all
     * active and future transactions. {@link #get} returns {@code null} for a
     * tombstoned id, which chain walkers treat as the end of the chain.
     */
    public void remove(long versionId) {
        rwLock.writeLock().lock();
        try {
            if (versionId >= 0 && versionId < versions.size()) {
                versions.set((int) versionId, null);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Return an immutable snapshot of the current {@code RID → headVersionId}
     * map. Used by the vacuum process to iterate every version chain without
     * holding the store lock for the whole sweep. The copy is taken under the
     * read lock, so it is a consistent view at one instant; chains may change
     * afterwards, which the vacuum tolerates by re-reading each head.
     */
    public Map<RID, Long> chainHeadsSnapshot() {
        rwLock.readLock().lock();
        try {
            return new HashMap<>(chainHead);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Drop the chain-head mapping for {@code rid}. Called by the vacuum process
     * when a row is fully dead (its entire version chain has been reclaimed), so
     * the RID is no longer tracked. A no-op if the RID has no head.
     */
    public void removeHead(RID rid) {
        rwLock.writeLock().lock();
        try {
            chainHead.remove(rid);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Total number of version records (across all chains). */
    public int size() {
        rwLock.readLock().lock();
        try {
            return versions.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
