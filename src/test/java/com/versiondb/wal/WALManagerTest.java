package com.versiondb.wal;

import com.versiondb.shared.StorageException;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.DiskManager;
import com.versiondb.storage.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WALManagerTest {

    @TempDir
    Path tempDir;

    private Path walPath;
    private WALManager wal;

    @BeforeEach
    void setUp() throws IOException {
        walPath = tempDir.resolve("wal.log");
        wal = new WALManager(walPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        wal.close();
    }

    // ---- 5.1: LogRecord serialization round-trips ----------------------------

    @Test
    void beginRoundTrip() {
        LogRecord rec = LogRecord.begin(42L, 7L);
        byte[] bytes = rec.serialize();
        LogRecord back = LogRecord.deserialize(bytes);

        assertEquals(42L, back.lsn);
        assertEquals(LogRecord.NO_PREVIOUS_LSN, back.prevLsn);
        assertEquals(7L, back.txnId);
        assertEquals(LogType.BEGIN, back.logType);
    }

    @Test
    void insertRoundTrip() {
        byte[] tupleData = {1, 2, 3, 4, 5};
        LogRecord rec = LogRecord.insert(10L, 5L, 3L, 42, 7, tupleData);
        LogRecord back = LogRecord.deserialize(rec.serialize());

        assertEquals(LogType.INSERT, back.logType);
        assertEquals(42, back.pageId);
        assertEquals(7, back.slotId);
        assertArrayEquals(tupleData, back.data);
    }

    @Test
    void deleteRoundTrip() {
        byte[] oldData = {0xA, 0xB, 0xC};
        LogRecord rec = LogRecord.delete(20L, 10L, 5L, 99, 3, oldData);
        LogRecord back = LogRecord.deserialize(rec.serialize());

        assertEquals(LogType.DELETE, back.logType);
        assertEquals(99, back.pageId);
        assertEquals(3, back.slotId);
        assertArrayEquals(oldData, back.data);
    }

    @Test
    void commitRoundTrip() {
        LogRecord rec = LogRecord.commit(100L, 50L, 1L);
        LogRecord back = LogRecord.deserialize(rec.serialize());
        assertEquals(LogType.COMMIT, back.logType);
        assertEquals(100L, back.lsn);
        assertEquals(50L, back.prevLsn);
        assertEquals(1L, back.txnId);
    }

    @Test
    void abortRoundTrip() {
        LogRecord rec = LogRecord.abort(200L, 150L, 2L);
        LogRecord back = LogRecord.deserialize(rec.serialize());
        assertEquals(LogType.ABORT, back.logType);
    }

    @Test
    void clrRoundTrip() {
        byte[] data = {9, 8, 7};
        LogRecord rec = LogRecord.clr(300L, 250L, 4L, 11, 2, 200L, data);
        LogRecord back = LogRecord.deserialize(rec.serialize());

        assertEquals(LogType.CLR, back.logType);
        assertEquals(11, back.pageId);
        assertEquals(2, back.slotId);
        assertEquals(200L, back.undoNextLsn);
        assertArrayEquals(data, back.data);
    }

    @Test
    void checkpointRoundTrip() {
        Map<Long, Long> att = new HashMap<>();
        att.put(1L, 100L);
        att.put(2L, 200L);
        Map<Integer, Long> dpt = new HashMap<>();
        dpt.put(5, 50L);
        dpt.put(10, 90L);

        LogRecord rec = LogRecord.checkpoint(500L, 0L, att, dpt);
        LogRecord back = LogRecord.deserialize(rec.serialize());

        assertEquals(LogType.CHECKPOINT, back.logType);
        assertEquals(att, back.activeTxnTable);
        assertEquals(dpt, back.dirtyPageTable);
    }

    @Test
    void emptyDataRoundTrip() {
        LogRecord rec = LogRecord.insert(1L, LogRecord.NO_PREVIOUS_LSN, 1L, 0, 0, new byte[0]);
        LogRecord back = LogRecord.deserialize(rec.serialize());
        assertNotNull(back.data);
        assertEquals(0, back.data.length);
    }

    // ---- 5.2 + 5.3: WALManager state and append ops --------------------------

    @Test
    void lsnsStrictlyIncrease() {
        long l1 = wal.logBegin(1L);
        long l2 = wal.logBegin(2L);
        long l3 = wal.logBegin(3L);

        assertTrue(l1 < l2, "LSNs must strictly increase");
        assertTrue(l2 < l3, "LSNs must strictly increase");
    }

    @Test
    void prevLsnChainPerTransaction() throws IOException {
        long begin = wal.logBegin(1L);
        long ins1  = wal.logInsert(1L, 5, 0, new byte[]{1, 2});
        long ins2  = wal.logInsert(1L, 5, 1, new byte[]{3, 4});
        long com   = wal.logCommit(1L);

        // flush so records hit disk
        wal.flushToLsn(com);

        // read records back and verify prevLsn chain
        Iterator<LogRecord> it = wal.readLog(0);
        LogRecord rBegin = it.next();
        LogRecord rIns1  = it.next();
        LogRecord rIns2  = it.next();
        LogRecord rCom   = it.next();

        assertEquals(LogRecord.NO_PREVIOUS_LSN, rBegin.prevLsn);
        assertEquals(begin, rIns1.prevLsn);
        assertEquals(ins1,  rIns2.prevLsn);
        assertEquals(ins2,  rCom.prevLsn);
    }

    @Test
    void commitForcesDurability() {
        wal.logBegin(1L);
        wal.logInsert(1L, 3, 0, new byte[]{7});
        long commitLsn = wal.logCommit(1L);

        assertTrue(wal.getFlushedLsn() >= commitLsn,
                "After commit, flushedLsn must be >= commitLsn");
    }

    @Test
    void abortForcesDurability() {
        wal.logBegin(2L);
        long abortLsn = wal.logAbort(2L);

        assertTrue(wal.getFlushedLsn() >= abortLsn,
                "After abort, flushedLsn must be >= abortLsn");
    }

    @Test
    void multipleTransactionsChainsAreIndependent() throws IOException {
        long b1 = wal.logBegin(1L);
        long b2 = wal.logBegin(2L);
        long i1 = wal.logInsert(1L, 0, 0, new byte[]{1});
        long i2 = wal.logInsert(2L, 1, 0, new byte[]{2});
        long c1 = wal.logCommit(1L);
        long c2 = wal.logCommit(2L);

        wal.flushToLsn(c2);

        // Collect records
        Iterator<LogRecord> it = wal.readLog(0);
        LogRecord[] recs = new LogRecord[6];
        for (int i = 0; i < 6; i++) recs[i] = it.next();

        // txn 1 chain: b1 → i1 → c1
        assertEquals(LogRecord.NO_PREVIOUS_LSN, recs[0].prevLsn); // b1
        assertEquals(LogRecord.NO_PREVIOUS_LSN, recs[1].prevLsn, "b2.prevLsn should be NO_PREVIOUS_LSN");
        // verify the actual txn1 chain
        LogRecord recB1 = find(recs, b1);
        LogRecord recI1 = find(recs, i1);
        LogRecord recC1 = find(recs, c1);
        assertEquals(LogRecord.NO_PREVIOUS_LSN, recB1.prevLsn);
        assertEquals(b1, recI1.prevLsn);
        assertEquals(i1, recC1.prevLsn);

        // txn 2 chain: b2 → i2 → c2
        LogRecord recB2 = find(recs, b2);
        LogRecord recI2 = find(recs, i2);
        LogRecord recC2 = find(recs, c2);
        assertEquals(LogRecord.NO_PREVIOUS_LSN, recB2.prevLsn);
        assertEquals(b2, recI2.prevLsn);
        assertEquals(i2, recC2.prevLsn);
    }

    private static LogRecord find(LogRecord[] recs, long lsn) {
        for (LogRecord r : recs) {
            if (r != null && r.lsn == lsn) return r;
        }
        throw new AssertionError("Record with LSN " + lsn + " not found");
    }

    @Test
    void checkpointLogged() throws IOException {
        wal.logBegin(1L);
        long ins = wal.logInsert(1L, 5, 0, new byte[]{42});
        Map<Long, Long> att = Map.of(1L, ins);
        Map<Integer, Long> dpt = Map.of(5, ins);
        long cpLsn = wal.logCheckpoint(att, dpt);

        wal.flushToLsn(cpLsn);

        LogRecord cpRec = wal.readRecordAtLsn(cpLsn);
        assertEquals(LogType.CHECKPOINT, cpRec.logType);
        assertEquals(att, cpRec.activeTxnTable);
        assertEquals(dpt, cpRec.dirtyPageTable);
    }

    // ---- 5.4: WAL rule — BufferPool flushes WAL before dirty page write ------

    @Test
    void walRuleHoldsOnPageFlush() throws IOException {
        Path dbPath = tempDir.resolve("test.db");
        DiskManager dm = new DiskManager(dbPath);
        WALManager realWal = new WALManager(tempDir.resolve("wal2.log"));
        BufferPool bp = new BufferPool(4, dm, realWal::flushToLsn);

        // Allocate a page and set its LSN via a logged insert
        Page page = bp.newPage();
        int pageId = page.getPageId();

        long beginLsn = realWal.logBegin(10L);
        long insertLsn = realWal.logInsert(10L, pageId, 0, new byte[]{1, 2, 3});
        page.setLsn(insertLsn);
        bp.unpin(pageId, true);

        // flushedLsn is -1 (or beginLsn at best) — WAL not yet flushed
        // Flushing the page should force WAL flush up to insertLsn
        bp.flushPage(pageId);

        assertTrue(realWal.getFlushedLsn() >= insertLsn,
                "WAL must be flushed to at least insertLsn before page write");

        realWal.close();
        dm.close();
    }

    @Test
    void walRuleHoldsOnEviction() throws IOException {
        Path dbPath = tempDir.resolve("evict.db");
        DiskManager dm = new DiskManager(dbPath);
        WALManager realWal = new WALManager(tempDir.resolve("wal3.log"));
        BufferPool bp = new BufferPool(2, dm, realWal::flushToLsn); // tiny pool

        // Fill the pool so eviction is forced
        Page p0 = bp.newPage();
        int id0 = p0.getPageId();
        long lsn0 = realWal.logBegin(20L);
        p0.setLsn(lsn0);
        bp.unpin(id0, true); // dirty

        Page p1 = bp.newPage();
        int id1 = p1.getPageId();
        long lsn1 = realWal.logInsert(20L, id1, 0, new byte[]{5});
        p1.setLsn(lsn1);
        bp.unpin(id1, true); // dirty

        // Fetching a third page evicts one of the dirty frames, triggering WAL flush
        Page p2 = bp.newPage();
        bp.unpin(p2.getPageId(), false);

        assertTrue(realWal.getFlushedLsn() >= lsn0 || realWal.getFlushedLsn() >= lsn1,
                "At least one WAL flush should have occurred during eviction");

        realWal.close();
        dm.close();
    }

    // ---- 5.5: readLog iterator + readRecordAtLsn + restart -------------------

    @Test
    void readLogReturnsRecordsInOrder() throws IOException {
        long l0 = wal.logBegin(1L);
        long l1 = wal.logInsert(1L, 3, 0, new byte[]{10});
        long l2 = wal.logCommit(1L);
        wal.flushToLsn(l2);

        Iterator<LogRecord> it = wal.readLog(0);
        long prev = -1;
        while (it.hasNext()) {
            long cur = it.next().lsn;
            assertTrue(cur > prev, "Records must come back in LSN order");
            prev = cur;
        }
    }

    @Test
    void readLogFromStartLsn() throws IOException {
        wal.logBegin(1L);
        long insertLsn = wal.logInsert(1L, 0, 0, new byte[]{1});
        long commitLsn = wal.logCommit(1L);
        wal.flushToLsn(commitLsn);

        Iterator<LogRecord> it = wal.readLog(insertLsn);
        LogRecord first = it.next();
        assertEquals(insertLsn, first.lsn,
                "readLog(startLsn) must start at the given LSN");
        // Drain the rest so the iterator closes its underlying file handle;
        // otherwise the open handle blocks @TempDir cleanup on Windows.
        it.forEachRemaining(r -> { });
    }

    @Test
    void readRecordAtLsn() throws IOException {
        wal.logBegin(5L);
        long insertLsn = wal.logInsert(5L, 7, 2, new byte[]{99, 88});
        wal.logCommit(5L);
        wal.flushToLsn(insertLsn);

        LogRecord rec = wal.readRecordAtLsn(insertLsn);
        assertEquals(LogType.INSERT, rec.logType);
        assertEquals(7, rec.pageId);
        assertEquals(2, rec.slotId);
        assertArrayEquals(new byte[]{99, 88}, rec.data);
    }

    @Test
    void walSurvivesRestart() throws IOException {
        // Write some records and close
        long b  = wal.logBegin(1L);
        long i  = wal.logInsert(1L, 2, 0, new byte[]{55});
        long c  = wal.logCommit(1L);
        // logCommit does a sync flush, so records are durable
        wal.close();

        // Re-open
        WALManager wal2 = new WALManager(walPath);
        Iterator<LogRecord> it = wal2.readLog(0);

        LogRecord rB = it.next();
        LogRecord rI = it.next();
        LogRecord rC = it.next();
        assertFalse(it.hasNext());

        assertEquals(b, rB.lsn);
        assertEquals(LogType.BEGIN, rB.logType);
        assertEquals(i, rI.lsn);
        assertEquals(LogType.INSERT, rI.logType);
        assertArrayEquals(new byte[]{55}, rI.data);
        assertEquals(c, rC.lsn);
        assertEquals(LogType.COMMIT, rC.logType);

        // LSNs continue from where they left off
        long newLsn = wal2.logBegin(2L);
        assertTrue(newLsn > c, "LSNs after restart must continue past previous max");

        wal2.close();
        // Replace wal reference so @AfterEach close() doesn't double-close
        wal = wal2;
    }
}
