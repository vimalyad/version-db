package com.minidb.txn;

import com.minidb.shared.StorageException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

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
 */
public final class CommitLog implements AutoCloseable {

    /** Standard commit-log file name. */
    public static final String FILE_NAME = "commit_log.clog";

    private static final int STATUSES_PER_BYTE = 4;
    private static final int BITS_PER_STATUS = 2;
    private static final int STATUS_MASK = 0b11;

    private final RandomAccessFile file;
    private final ReentrantLock lock = new ReentrantLock();

    /** In-memory mirror of the file; index = byte offset into the status array. */
    private byte[] buffer;

    /**
     * Open (or create) the commit log at the given path, loading any existing
     * status bytes into the in-memory buffer.
     */
    public CommitLog(Path path) {
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
        lock.lock();
        try {
            int byteIndex = (int) (xid / STATUSES_PER_BYTE);
            if (byteIndex >= buffer.length) {
                return TxStatus.IN_PROGRESS;
            }
            int shift = (int) (xid % STATUSES_PER_BYTE) * BITS_PER_STATUS;
            int code = (buffer[byteIndex] >> shift) & STATUS_MASK;
            return TxStatus.fromCode(code);
        } finally {
            lock.unlock();
        }
    }

    /** Record the status of a transaction, growing the array as needed. */
    public void setStatus(long xid, TxStatus status) {
        checkXid(xid);
        lock.lock();
        try {
            int byteIndex = (int) (xid / STATUSES_PER_BYTE);
            ensureCapacity(byteIndex);
            int shift = (int) (xid % STATUSES_PER_BYTE) * BITS_PER_STATUS;
            int cleared = buffer[byteIndex] & ~(STATUS_MASK << shift);
            buffer[byteIndex] = (byte) (cleared | (status.code() << shift));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            file.close();
        } catch (IOException e) {
            throw new StorageException("failed to close commit log", e);
        } finally {
            lock.unlock();
        }
    }

    private void ensureCapacity(int byteIndex) {
        if (byteIndex >= buffer.length) {
            buffer = Arrays.copyOf(buffer, byteIndex + 1);
        }
    }

    private static void checkXid(long xid) {
        if (xid < 0) {
            throw new StorageException("invalid transaction id: " + xid);
        }
    }
}
