package com.versiondb.txn;

import com.versiondb.shared.StorageException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A persistent record of the final status of every transaction, used by the
 * visibility rule's {@code isCommitted} check. Statuses are stored as a dense
 * array of 2-bit values indexed by transaction id ({@code xid}); four statuses
 * pack into one byte, so the file stays compact even for millions of ids.
 *
 * <p>The status for {@code xid} lives at byte {@code xid / 4}, bit offset
 * {@code (xid % 4) * 2}. The whole array is mirrored in an in-memory buffer so
 * reads ({@code getStatus}) are O(1) and never touch the disk; any id beyond the
 * end of the array is {@link TxStatus#IN_PROGRESS} by default.
 *
 * <p><b>Thread safety.</b> Because four transactions share one byte, two
 * commits/aborts can target the same byte concurrently; a read-modify-write of
 * that byte must therefore be serialized. A pool of striped read/write locks
 * guards each byte (writers take the write lock, readers the read lock), so
 * updates to <em>different</em> bytes proceed in parallel while same-byte updates
 * do not race. A separate resize lock makes growing the buffer safe against
 * concurrent access, and disk writes are serialized on the shared file handle.
 */
public final class CommitLog implements AutoCloseable {

    /** Standard commit-log file name. */
    public static final String FILE_NAME = "commit_log.clog";

    private static final int STATUSES_PER_BYTE = 4;
    private static final int BITS_PER_STATUS = 2;
    private static final int STATUS_MASK = 0b11;

    /** Number of striped byte locks; a power of two so masking selects a stripe. */
    private static final int STRIPE_COUNT = 1024;

    private final RandomAccessFile file;

    /** Guards buffer growth (write lock) vs. normal access (read lock). */
    private final ReentrantReadWriteLock resizeLock = new ReentrantReadWriteLock();

    /** Per-byte striped locks: distinct bytes update concurrently, same byte serializes. */
    private final ReentrantReadWriteLock[] stripes = new ReentrantReadWriteLock[STRIPE_COUNT];

    /** In-memory mirror of the file; index = byte offset into the status array. */
    private volatile byte[] buffer;

    /**
     * Open (or create) the commit log at the given path, loading any existing
     * status bytes into the in-memory buffer.
     */
    public CommitLog(Path path) {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new ReentrantReadWriteLock();
        }
        try {
            this.file = new RandomAccessFile(path.toFile(), "rw");
            long length = file.length();
            this.buffer = new byte[(int) length];
            if (length > 0) {
                file.seek(0);
                file.readFully(buffer);
            }
        } catch (IOException e) {
            throw new StorageException("failed to open commit log " + path, e);
        }
    }

    /**
     * The recorded status of a transaction. Ids that have never been written are
     * {@link TxStatus#IN_PROGRESS}.
     */
    public TxStatus getStatus(long xid) {
        checkXid(xid);
        int byteIndex = (int) (xid / STATUSES_PER_BYTE);
        int shift = (int) (xid % STATUSES_PER_BYTE) * BITS_PER_STATUS;
        resizeLock.readLock().lock();
        try {
            byte[] b = buffer;
            if (byteIndex >= b.length) {
                return TxStatus.IN_PROGRESS;
            }
            ReentrantReadWriteLock stripe = stripeFor(byteIndex);
            stripe.readLock().lock();
            try {
                int code = (b[byteIndex] >> shift) & STATUS_MASK;
                return TxStatus.fromCode(code);
            } finally {
                stripe.readLock().unlock();
            }
        } finally {
            resizeLock.readLock().unlock();
        }
    }

    /**
     * Record the status of a transaction, growing the array as needed. The
     * single touched byte is written through to disk and fsynced before
     * returning, so a committed/aborted status is durable immediately (the
     * commit/abort path depends on this).
     */
    public void setStatus(long xid, TxStatus status) {
        checkXid(xid);
        int byteIndex = (int) (xid / STATUSES_PER_BYTE);
        int shift = (int) (xid % STATUSES_PER_BYTE) * BITS_PER_STATUS;
        ensureCapacity(byteIndex);
        resizeLock.readLock().lock();
        try {
            byte[] b = buffer;
            ReentrantReadWriteLock stripe = stripeFor(byteIndex);
            stripe.writeLock().lock();
            try {
                int cleared = b[byteIndex] & ~(STATUS_MASK << shift);
                byte updated = (byte) (cleared | (status.code() << shift));
                b[byteIndex] = updated;
                persistByte(byteIndex, updated);
            } finally {
                stripe.writeLock().unlock();
            }
        } finally {
            resizeLock.readLock().unlock();
        }
    }

    /** O(1) convenience check used on the hot visibility path. */
    public boolean isCommitted(long xid) {
        return getStatus(xid) == TxStatus.COMMITTED;
    }

    /**
     * Reconcile the commit log with the outcome of crash recovery. After ARIES
     * recovery, transactions whose COMMIT record was in the WAL are marked
     * COMMITTED and transactions that were rolled back are marked ABORTED — this
     * repairs entries that a crash left as IN_PROGRESS because their commit-log
     * write was lost. The two id sets are expected to be disjoint; if an id
     * appears in both, the COMMITTED status wins (a durable COMMIT record is
     * authoritative).
     *
     * @param committed transaction ids that committed (from {@code RecoveryResult.committed()})
     * @param aborted   transaction ids that were undone (from {@code RecoveryResult.undone()})
     */
    public void reconcile(Set<Long> aborted, Set<Long> committed) {
        for (long xid : aborted) {
            setStatus(xid, TxStatus.ABORTED);
        }
        for (long xid : committed) {
            setStatus(xid, TxStatus.COMMITTED);
        }
    }

    @Override
    public void close() {
        resizeLock.writeLock().lock();
        try {
            file.close();
        } catch (IOException e) {
            throw new StorageException("failed to close commit log", e);
        } finally {
            resizeLock.writeLock().unlock();
        }
    }

    /** Write one status byte through to disk and fsync. Serialized on the file handle. */
    private void persistByte(int byteIndex, byte value) {
        synchronized (file) {
            try {
                file.seek(byteIndex);
                file.write(value);
                file.getFD().sync();
            } catch (IOException e) {
                throw new StorageException("failed to persist commit-log byte " + byteIndex, e);
            }
        }
    }

    /** Grow the buffer so {@code byteIndex} is addressable, under the resize write lock. */
    private void ensureCapacity(int byteIndex) {
        resizeLock.readLock().lock();
        try {
            if (byteIndex < buffer.length) {
                return;
            }
        } finally {
            resizeLock.readLock().unlock();
        }
        resizeLock.writeLock().lock();
        try {
            if (byteIndex >= buffer.length) {
                buffer = Arrays.copyOf(buffer, byteIndex + 1);
            }
        } finally {
            resizeLock.writeLock().unlock();
        }
    }

    private ReentrantReadWriteLock stripeFor(int byteIndex) {
        return stripes[byteIndex & (STRIPE_COUNT - 1)];
    }

    private static void checkXid(long xid) {
        if (xid < 0) {
            throw new StorageException("invalid transaction id: " + xid);
        }
    }
}
