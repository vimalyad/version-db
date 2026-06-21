package com.minidb.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minidb.shared.StorageException;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitLogTest {

    @TempDir
    Path tmp;

    private Path clog() {
        return tmp.resolve(CommitLog.FILE_NAME);
    }

    @Test
    void unknownTransactionIsInProgress() {
        try (CommitLog log = new CommitLog(clog())) {
            assertEquals(TxStatus.IN_PROGRESS, log.getStatus(0));
            assertEquals(TxStatus.IN_PROGRESS, log.getStatus(12345));
        }
    }

    @Test
    void everyStatusRoundTrips() {
        try (CommitLog log = new CommitLog(clog())) {
            log.setStatus(7, TxStatus.COMMITTED);
            assertEquals(TxStatus.COMMITTED, log.getStatus(7));

            log.setStatus(7, TxStatus.ABORTED);
            assertEquals(TxStatus.ABORTED, log.getStatus(7));

            log.setStatus(7, TxStatus.IN_PROGRESS);
            assertEquals(TxStatus.IN_PROGRESS, log.getStatus(7));
        }
    }

    @Test
    void fourStatusesShareOneByteIndependently() {
        try (CommitLog log = new CommitLog(clog())) {
            // xids 0..3 all live in byte 0 — setting one must not disturb the others.
            log.setStatus(0, TxStatus.COMMITTED);
            log.setStatus(1, TxStatus.ABORTED);
            log.setStatus(2, TxStatus.COMMITTED);
            log.setStatus(3, TxStatus.ABORTED);

            assertEquals(TxStatus.COMMITTED, log.getStatus(0));
            assertEquals(TxStatus.ABORTED, log.getStatus(1));
            assertEquals(TxStatus.COMMITTED, log.getStatus(2));
            assertEquals(TxStatus.ABORTED, log.getStatus(3));
        }
    }

    @Test
    void statusCrossesByteBoundary() {
        try (CommitLog log = new CommitLog(clog())) {
            // xid 3 is the last status in byte 0, xid 4 the first in byte 1.
            log.setStatus(3, TxStatus.COMMITTED);
            log.setStatus(4, TxStatus.ABORTED);
            assertEquals(TxStatus.COMMITTED, log.getStatus(3));
            assertEquals(TxStatus.ABORTED, log.getStatus(4));
            assertEquals(TxStatus.IN_PROGRESS, log.getStatus(5));
        }
    }

    @Test
    void highTransactionIdGrowsTheArray() {
        try (CommitLog log = new CommitLog(clog())) {
            log.setStatus(100_000, TxStatus.COMMITTED);
            assertEquals(TxStatus.COMMITTED, log.getStatus(100_000));
            assertEquals(TxStatus.IN_PROGRESS, log.getStatus(99_999));
        }
    }

    @Test
    void negativeTransactionIdIsRejected() {
        try (CommitLog log = new CommitLog(clog())) {
            assertThrows(StorageException.class, () -> log.getStatus(-1));
            assertThrows(StorageException.class, () -> log.setStatus(-1, TxStatus.COMMITTED));
        }
    }

    @Test
    void statusSurvivesReopen() {
        Path path = clog();
        try (CommitLog log = new CommitLog(path)) {
            log.setStatus(1, TxStatus.COMMITTED);
            log.setStatus(2, TxStatus.ABORTED);
            log.setStatus(9000, TxStatus.COMMITTED);
        }
        try (CommitLog log = new CommitLog(path)) {
            assertEquals(TxStatus.COMMITTED, log.getStatus(1));
            assertEquals(TxStatus.ABORTED, log.getStatus(2));
            assertEquals(TxStatus.COMMITTED, log.getStatus(9000));
            assertEquals(TxStatus.IN_PROGRESS, log.getStatus(3));
        }
    }

    @Test
    void isCommittedReflectsStatus() {
        try (CommitLog log = new CommitLog(clog())) {
            assertFalse(log.isCommitted(5));            // never set
            log.setStatus(5, TxStatus.COMMITTED);
            assertTrue(log.isCommitted(5));
            log.setStatus(5, TxStatus.ABORTED);
            assertFalse(log.isCommitted(5));
        }
    }

    @Test
    void statusCodesAreStableTwoBitValues() {
        assertEquals(0, TxStatus.IN_PROGRESS.code());
        assertEquals(1, TxStatus.COMMITTED.code());
        assertEquals(2, TxStatus.ABORTED.code());
        assertEquals(TxStatus.COMMITTED, TxStatus.fromCode(1));
        assertThrows(StorageException.class, () -> TxStatus.fromCode(3));
    }
}
