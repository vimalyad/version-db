package com.versiondb.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.versiondb.shared.StorageException;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void concurrentWritesToTheSameByteAllLand() throws InterruptedException {
        try (CommitLog log = new CommitLog(clog())) {
            // xids 0..3 share byte 0 — concurrent writers must not clobber each other.
            int writers = 4;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writers);
            for (int i = 0; i < writers; i++) {
                final long xid = i;
                final TxStatus status = (i % 2 == 0) ? TxStatus.COMMITTED : TxStatus.ABORTED;
                new Thread(() -> {
                    try {
                        start.await();
                        for (int r = 0; r < 1000; r++) {
                            log.setStatus(xid, status);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            start.countDown();
            // 4 x 1000 status writes each fsync through to disk; on a slow disk
            // that is several thousand syncs, so allow generous headroom (the
            // lighter many-bytes test uses 15s for 2000 writes). The point of the
            // test is the final no-clobber result below, not throughput.
            assertTrue(done.await(30, TimeUnit.SECONDS));

            assertEquals(TxStatus.COMMITTED, log.getStatus(0));
            assertEquals(TxStatus.ABORTED, log.getStatus(1));
            assertEquals(TxStatus.COMMITTED, log.getStatus(2));
            assertEquals(TxStatus.ABORTED, log.getStatus(3));
        }
    }

    @Test
    void concurrentWritesAcrossManyBytesAllLand() throws InterruptedException {
        try (CommitLog log = new CommitLog(clog())) {
            int n = 2000;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final long xid = i;
                new Thread(() -> {
                    try {
                        start.await();
                        log.setStatus(xid, TxStatus.COMMITTED);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));

            for (int i = 0; i < n; i++) {
                assertTrue(log.isCommitted(i), "xid " + i + " should be committed");
            }
        }
    }

    @Test
    void reconcileMarksRecoveredTransactions() {
        try (CommitLog log = new CommitLog(clog())) {
            // Two transactions left IN_PROGRESS by a crash, now resolved by recovery.
            log.reconcile(Set.of(11L), Set.of(10L));
            assertEquals(TxStatus.COMMITTED, log.getStatus(10));
            assertEquals(TxStatus.ABORTED, log.getStatus(11));
        }
    }

    @Test
    void reconcileLetsCommittedWinOverAborted() {
        try (CommitLog log = new CommitLog(clog())) {
            // A durable COMMIT record is authoritative even if the id is in both sets.
            log.reconcile(Set.of(20L), Set.of(20L));
            assertEquals(TxStatus.COMMITTED, log.getStatus(20));
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
