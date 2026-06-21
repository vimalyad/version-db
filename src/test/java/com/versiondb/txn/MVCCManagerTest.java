package com.versiondb.txn;

import com.versiondb.shared.RID;
import com.versiondb.shared.TransactionConflictException;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.DiskManager;
import com.versiondb.storage.HeapFile;
import com.versiondb.wal.WALManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the MVCC layer, covering the six key scenarios from
 * {@code part3.md §14} plus unit tests for individual operations.
 */
class MVCCManagerTest {

    @TempDir
    Path tempDir;

    private DiskManager dm;
    private WALManager wal;
    private BufferPool bp;
    private CommitLog clog;
    private TransactionManager tm;
    private VersionStore vs;
    private MVCCManager mvcc;
    private HeapFile heap;

    @BeforeEach
    void setUp() throws IOException {
        dm   = new DiskManager(tempDir.resolve("test.db"));
        wal  = new WALManager(tempDir.resolve("wal.log"));
        bp   = new BufferPool(16, dm, wal::flushToLsn);
        clog = new CommitLog(tempDir.resolve(CommitLog.FILE_NAME));
        tm   = new TransactionManager(1L, wal, clog);
        vs   = new VersionStore();
        mvcc = new MVCCManager(vs, clog, wal, bp);
        tm.setUndoCallback(mvcc.undoCallback());
        heap = HeapFile.create(bp);
    }

    @AfterEach
    void tearDown() throws IOException {
        bp.flushAll();
        wal.close();
        clog.close();
        dm.close();
    }

    // ---- 10.1: VersionRecord + VersionStore ----------------------------------

    @Test
    void versionStoreAllocatesSequentialIds() {
        VersionRecord v1 = new VersionRecord(1L, 0L, new RID(0, 0), -1L, new byte[]{1});
        VersionRecord v2 = new VersionRecord(2L, 0L, new RID(0, 1), -1L, new byte[]{2});
        long id1 = vs.allocate(v1);
        long id2 = vs.allocate(v2);
        assertEquals(0L, id1);
        assertEquals(1L, id2);
        assertSame(v1, vs.get(id1));
        assertSame(v2, vs.get(id2));
    }

    @Test
    void versionStoreHeadMap() {
        RID rid = new RID(0, 0);
        assertEquals(-1L, vs.getHeadVersionId(rid));
        long id = vs.allocate(new VersionRecord(1L, 0L, rid, -1L, new byte[]{9}));
        vs.setHead(rid, id);
        assertEquals(id, vs.getHeadVersionId(rid));
    }

    // ---- 10.2: visibility rule -----------------------------------------------

    @Test
    void visibilityOwnInsertIsVisible() {
        Transaction txn = tm.beginTransaction();
        Snapshot snap = txn.getSnapshot();
        VersionRecord ver = new VersionRecord(txn.xid, 0L, new RID(0, 0), -1L, new byte[]{1});
        assertTrue(mvcc.isVisible(ver, txn.xid, snap));
        tm.commitTransaction(txn);
    }

    @Test
    void visibilityOwnDeleteIsInvisible() {
        Transaction txn = tm.beginTransaction();
        Snapshot snap = txn.getSnapshot();
        VersionRecord ver = new VersionRecord(txn.xid, txn.xid, new RID(0, 0), -1L, new byte[]{1});
        assertFalse(mvcc.isVisible(ver, txn.xid, snap), "Deleted by own txn → invisible");
        tm.commitTransaction(txn);
    }

    @Test
    void visibilityAbortedInsertSentinelInvisible() {
        Transaction txn = tm.beginTransaction();
        Snapshot snap = txn.getSnapshot();
        VersionRecord ver = new VersionRecord(0L, 0L, new RID(0, 0), -1L, new byte[]{1});
        assertFalse(mvcc.isVisible(ver, txn.xid, snap), "xmin=0 sentinel → always invisible");
        tm.commitTransaction(txn);
    }

    @Test
    void isXidVisibleBelowXmin() {
        Transaction txn = tm.beginTransaction();
        Snapshot snap = txn.getSnapshot();
        tm.commitTransaction(txn);
        // After txn commits, a fresh snapshot taken by another txn will have xmin > txn.xid
        Transaction txn2 = tm.beginTransaction();
        Snapshot snap2 = txn2.getSnapshot();
        assertTrue(mvcc.isXidVisible(txn.xid, snap2),
                "Committed xid below xmin is always visible");
        tm.commitTransaction(txn2);
    }

    @Test
    void isXidVisibleAboveXmaxInvisible() {
        Transaction txn = tm.beginTransaction();
        Snapshot snap = txn.getSnapshot();
        // xid >= xmax is a future transaction — invisible
        assertFalse(mvcc.isXidVisible(snap.xmax() + 10, snap));
        tm.commitTransaction(txn);
    }

    // ---- 10.3: getVisibleVersion ---------------------------------------------

    @Test
    void getVisibleVersionCommittedInsertIsVisible() {
        byte[] data = {42, 43};
        Transaction writer = tm.beginTransaction();
        RID rid = mvcc.insert(writer, heap, data);
        tm.commitTransaction(writer);

        Transaction reader = tm.beginTransaction();
        byte[] result = mvcc.getVisibleVersion(rid, reader.xid, reader.getSnapshot());
        assertArrayEquals(data, result);
        tm.commitTransaction(reader);
    }

    @Test
    void getVisibleVersionUncommittedInsertInvisibleToOtherTxn() {
        byte[] data = {1, 2, 3};
        Transaction writer = tm.beginTransaction();
        // reader starts BEFORE writer inserts (snapshot excludes writer)
        Transaction reader = tm.beginTransaction();

        RID rid = mvcc.insert(writer, heap, data);

        // Reader took its snapshot before the insert — must not see it
        byte[] result = mvcc.getVisibleVersion(rid, reader.xid, reader.getSnapshot());
        assertNull(result, "Uncommitted insert must be invisible to concurrent reader");

        tm.commitTransaction(writer);
        tm.commitTransaction(reader);
    }

    @Test
    void getVisibleVersionReturnsNullWhenNoVersion() {
        Transaction txn = tm.beginTransaction();
        RID absent = new RID(0, 99);
        assertNull(mvcc.getVisibleVersion(absent, txn.xid, txn.getSnapshot()));
        tm.commitTransaction(txn);
    }

    // ---- 10.4: insert --------------------------------------------------------

    @Test
    void insertCreatesVersionAndHeapEntry() {
        Transaction txn = tm.beginTransaction();
        byte[] data = {7, 8, 9};
        RID rid = mvcc.insert(txn, heap, data);
        assertNotNull(rid);

        // Version store must have a head version for the RID
        long headId = vs.getHeadVersionId(rid);
        assertTrue(headId >= 0);
        VersionRecord ver = vs.get(headId);
        assertEquals(txn.xid, ver.xmin);
        assertEquals(0L, ver.xmax);
        assertArrayEquals(data, ver.data);

        tm.commitTransaction(txn);
    }

    @Test
    void insertAddsUndoRecord() {
        Transaction txn = tm.beginTransaction();
        mvcc.insert(txn, heap, new byte[]{1});
        // Undo log must contain one entry with null oldData
        assertEquals(1, txn.reverseUndoLog().size());
        assertNull(txn.reverseUndoLog().get(0).oldData());
        tm.commitTransaction(txn);
    }

    // ---- 10.5: delete --------------------------------------------------------

    @Test
    void deleteStampsXmax() {
        Transaction writer = tm.beginTransaction();
        RID rid = mvcc.insert(writer, heap, new byte[]{10});
        tm.commitTransaction(writer);

        Transaction deleter = tm.beginTransaction();
        mvcc.delete(deleter, heap, rid);

        VersionRecord head = vs.get(vs.getHeadVersionId(rid));
        assertEquals(deleter.xid, head.xmax);

        tm.commitTransaction(deleter);
    }

    @Test
    void deleteCommittedMakesVersionInvisibleToLaterReader() {
        Transaction writer = tm.beginTransaction();
        byte[] data = {55};
        RID rid = mvcc.insert(writer, heap, data);
        tm.commitTransaction(writer);

        Transaction deleter = tm.beginTransaction();
        mvcc.delete(deleter, heap, rid);
        tm.commitTransaction(deleter);

        Transaction reader = tm.beginTransaction();
        assertNull(mvcc.getVisibleVersion(rid, reader.xid, reader.getSnapshot()),
                "Committed delete must be invisible to later reader");
        tm.commitTransaction(reader);
    }

    @Test
    void firstWriterWinsConflict() {
        Transaction writer1 = tm.beginTransaction();
        RID rid = mvcc.insert(writer1, heap, new byte[]{1});
        tm.commitTransaction(writer1);

        Transaction t1 = tm.beginTransaction();
        Transaction t2 = tm.beginTransaction();

        mvcc.delete(t1, heap, rid); // t1 stamps xmax

        // t2 tries to delete the same row while t1 is still in progress
        assertThrows(TransactionConflictException.class,
                () -> mvcc.delete(t2, heap, rid),
                "Second writer must throw TransactionConflictException");

        tm.commitTransaction(t1);
        tm.abortTransaction(t2);
    }

    // ---- 10.6: update --------------------------------------------------------

    @Test
    void updateCreatesNewRidAndOldBecomesInvisible() {
        Transaction writer = tm.beginTransaction();
        byte[] oldData = {1};
        RID oldRid = mvcc.insert(writer, heap, oldData);
        tm.commitTransaction(writer);

        byte[] newData = {2};
        Transaction updater = tm.beginTransaction();
        RID newRid = mvcc.update(updater, heap, oldRid, newData);
        tm.commitTransaction(updater);

        Transaction reader = tm.beginTransaction();
        Snapshot snap = reader.getSnapshot();

        assertNull(mvcc.getVisibleVersion(oldRid, reader.xid, snap),
                "Old version must be invisible after committed update");
        assertArrayEquals(newData,
                mvcc.getVisibleVersion(newRid, reader.xid, snap),
                "New version must be visible after committed update");

        tm.commitTransaction(reader);
    }

    @Test
    void updateOldVersionVisibleToSnapshotBeforeUpdate() {
        Transaction writer = tm.beginTransaction();
        byte[] oldData = {10};
        RID oldRid = mvcc.insert(writer, heap, oldData);
        tm.commitTransaction(writer);

        // Snapshot taken before update
        Transaction beforeReader = tm.beginTransaction();

        Transaction updater = tm.beginTransaction();
        mvcc.update(updater, heap, oldRid, new byte[]{20});
        tm.commitTransaction(updater);

        // beforeReader took its snapshot before the update committed
        assertArrayEquals(oldData,
                mvcc.getVisibleVersion(oldRid, beforeReader.xid, beforeReader.getSnapshot()),
                "Old snapshot must still see the old version");

        tm.commitTransaction(beforeReader);
    }

    // ---- 10.7: abort handling (scenarios from part3.md §14) ------------------

    /**
     * Scenario 3 — Abort cleanup: aborted insert is invisible to all readers.
     */
    @Test
    void scenario3_AbortedInsertIsInvisible() {
        Transaction writer = tm.beginTransaction();
        byte[] data = {99};
        RID rid = mvcc.insert(writer, heap, data);

        tm.abortTransaction(writer); // rolls back via UndoCallback

        Transaction reader = tm.beginTransaction();
        assertNull(mvcc.getVisibleVersion(rid, reader.xid, reader.getSnapshot()),
                "Aborted insert must be invisible");
        tm.commitTransaction(reader);
    }

    /**
     * Scenario 1 — Basic isolation: T1 inserts, T2 starts before T1 commits,
     * T2 must not see the row; T3 starts after T1 commits and must see it.
     */
    @Test
    void scenario1_BasicIsolation() {
        Transaction t1 = tm.beginTransaction();
        Transaction t2 = tm.beginTransaction();  // snapshot before t1 inserts

        byte[] data = {1};
        RID rid = mvcc.insert(t1, heap, data);

        // t2 took snapshot before insert → must not see it
        assertNull(mvcc.getVisibleVersion(rid, t2.xid, t2.getSnapshot()));

        tm.commitTransaction(t1);

        Transaction t3 = tm.beginTransaction();  // snapshot after t1 committed
        assertArrayEquals(data,
                mvcc.getVisibleVersion(rid, t3.xid, t3.getSnapshot()),
                "T3 (after commit) must see the row");

        tm.commitTransaction(t2);
        tm.commitTransaction(t3);
    }

    /**
     * Scenario 2 — Read consistency: T1 starts; T2 inserts 3 rows and commits;
     * T1 reads and sees 0 of T2's rows (T1's snapshot predates T2).
     */
    @Test
    void scenario2_ReadConsistency() {
        Transaction t1 = tm.beginTransaction();  // snapshot before t2 starts
        Transaction t2 = tm.beginTransaction();

        RID r1 = mvcc.insert(t2, heap, new byte[]{1});
        RID r2 = mvcc.insert(t2, heap, new byte[]{2});
        RID r3 = mvcc.insert(t2, heap, new byte[]{3});
        tm.commitTransaction(t2);

        // t1's snapshot predates t2 — none of t2's rows should be visible
        assertNull(mvcc.getVisibleVersion(r1, t1.xid, t1.getSnapshot()));
        assertNull(mvcc.getVisibleVersion(r2, t1.xid, t1.getSnapshot()));
        assertNull(mvcc.getVisibleVersion(r3, t1.xid, t1.getSnapshot()));

        tm.commitTransaction(t1);
    }

    /**
     * Scenario 5 — Version chain correctness: a row updated 5 times by 5
     * committed transactions. A later reader sees version 5; a reader that
     * started before commit 3 sees version 2.
     */
    @Test
    void scenario5_VersionChainCorrectness() {
        // Version 1
        Transaction t1 = tm.beginTransaction();
        RID rid = mvcc.insert(t1, heap, new byte[]{1});
        tm.commitTransaction(t1);

        // Version 2
        Transaction t2 = tm.beginTransaction();
        RID rid2 = mvcc.update(t2, heap, rid, new byte[]{2});
        tm.commitTransaction(t2);

        // Reader snapshot taken after v2 but before v3
        Transaction readerV2 = tm.beginTransaction();

        // Version 3
        Transaction t3 = tm.beginTransaction();
        RID rid3 = mvcc.update(t3, heap, rid2, new byte[]{3});
        tm.commitTransaction(t3);

        // Version 4
        Transaction t4 = tm.beginTransaction();
        RID rid4 = mvcc.update(t4, heap, rid3, new byte[]{4});
        tm.commitTransaction(t4);

        // Version 5
        Transaction t5 = tm.beginTransaction();
        RID rid5 = mvcc.update(t5, heap, rid4, new byte[]{5});
        tm.commitTransaction(t5);

        // Latest reader sees version 5
        Transaction latestReader = tm.beginTransaction();
        assertArrayEquals(new byte[]{5},
                mvcc.getVisibleVersion(rid5, latestReader.xid, latestReader.getSnapshot()));

        // Old reader (before v3) sees version 2 via rid2
        assertArrayEquals(new byte[]{2},
                mvcc.getVisibleVersion(rid2, readerV2.xid, readerV2.getSnapshot()),
                "Reader before v3 commit must see v2");
        // And must NOT see v3 via rid3 (xmin=t3.xid > snapshot.xmax)
        assertNull(mvcc.getVisibleVersion(rid3, readerV2.xid, readerV2.getSnapshot()));

        tm.commitTransaction(readerV2);
        tm.commitTransaction(latestReader);
    }

    /**
     * Scenario 4 — Concurrent updates: two transactions both try to update the
     * same row; the second throws {@link TransactionConflictException}.
     */
    @Test
    void scenario4_ConcurrentUpdateConflict() {
        Transaction writer = tm.beginTransaction();
        RID rid = mvcc.insert(writer, heap, new byte[]{1});
        tm.commitTransaction(writer);

        Transaction t1 = tm.beginTransaction();
        Transaction t2 = tm.beginTransaction();

        mvcc.update(t1, heap, rid, new byte[]{2}); // t1 stamps xmax on rid

        assertThrows(TransactionConflictException.class,
                () -> mvcc.update(t2, heap, rid, new byte[]{3}),
                "Second concurrent updater must get a conflict");

        tm.commitTransaction(t1);
        tm.abortTransaction(t2);
    }

    /**
     * Abort undo for DELETE restores the version (xmax cleared back to 0).
     */
    @Test
    void abortedDeleteRestoresVersion() {
        Transaction writer = tm.beginTransaction();
        byte[] data = {77};
        RID rid = mvcc.insert(writer, heap, data);
        tm.commitTransaction(writer);

        Transaction deleter = tm.beginTransaction();
        mvcc.delete(deleter, heap, rid);
        tm.abortTransaction(deleter); // undo: set xmax = 0

        Transaction reader = tm.beginTransaction();
        assertArrayEquals(data,
                mvcc.getVisibleVersion(rid, reader.xid, reader.getSnapshot()),
                "Row must be visible again after abort of delete");
        tm.commitTransaction(reader);
    }

    /**
     * A transaction can see its own uncommitted inserts.
     */
    @Test
    void ownInsertVisibleWithinSameTransaction() {
        Transaction txn = tm.beginTransaction();
        byte[] data = {11};
        RID rid = mvcc.insert(txn, heap, data);

        // Same transaction, same snapshot
        assertArrayEquals(data,
                mvcc.getVisibleVersion(rid, txn.xid, txn.getSnapshot()),
                "Own uncommitted insert must be visible within the same transaction");

        tm.commitTransaction(txn);
    }
}
