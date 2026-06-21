package com.versiondb.wal;

import com.versiondb.shared.StorageException;
import com.versiondb.storage.WalFlushCallback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Write-Ahead Log manager. Provides durable, append-only logging with
 * monotonically increasing LSNs, batched flushes, and the WAL-rule callback
 * ({@link WalFlushCallback}) used by the Buffer Pool.
 *
 * <p><b>Wire format</b> (on disk): each record is preceded by a 4-byte
 * big-endian length, followed by the serialized {@link LogRecord} bytes.
 *
 * <p><b>Thread safety</b>: a single {@link ReentrantLock} guards all state
 * mutations. The lock is released around actual disk I/O to avoid serializing
 * concurrent callers.
 */
public final class WALManager implements WalFlushCallback {

    /** Flush the buffer when it exceeds this threshold. */
    private static final int FLUSH_THRESHOLD = 64 * 1024;

    private final Path walPath;
    private final RandomAccessFile walFile;
    private final ReentrantLock lock = new ReentrantLock();

    // ---- State (guarded by lock) ----
    /** Next LSN to assign. */
    private long currentLsn = 0;
    /** Highest LSN whose record is guaranteed on disk. */
    private long flushedLsn = -1;
    /** In-memory buffer of serialized records not yet written to disk. */
    private final ByteArrayOutputStream logBuffer = new ByteArrayOutputStream();
    /** Highest LSN currently sitting in logBuffer (or -1 if buffer is empty). */
    private long maxBufferedLsn = -1;
    /** Byte offset of the next write in the file (updated before I/O unlock). */
    private long fileWritePosition = 0;
    /** Maps LSN → byte offset of its record (including its 4-byte length prefix). */
    private final Map<Long, Long> lsnToFileOffset = new HashMap<>();
    /** Most-recent LSN written by each active transaction. */
    private final Map<Long, Long> txnLastLsn = new HashMap<>();

    /**
     * Open (or create) the WAL file at {@code walPath}. If the file already
     * contains records, the in-memory state is rebuilt by scanning them so the
     * manager can continue appending after a restart.
     */
    public WALManager(Path walPath) throws IOException {
        this.walPath = walPath;
        boolean existed = Files.exists(walPath);
        this.walFile = new RandomAccessFile(walPath.toFile(), "rw");
        if (existed && walFile.length() > 0) {
            rebuildState();
        }
    }

    // -------------------------------------------------------------------------
    // Append operations (5.3)
    // -------------------------------------------------------------------------

    /**
     * Log a BEGIN record for {@code txnId}. Returns the assigned LSN.
     */
    public long logBegin(long txnId) {
        lock.lock();
        try {
            long lsn = nextLsn();
            LogRecord rec = LogRecord.begin(lsn, txnId);
            txnLastLsn.put(txnId, lsn);
            appendToBuffer(rec);
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Log an INSERT record. Returns the assigned LSN which the caller must set
     * on the modified page via {@code page.setLsn(lsn)}.
     */
    public long logInsert(long txnId, int pageId, int slotId, byte[] tupleData) {
        lock.lock();
        try {
            long lsn = nextLsn();
            LogRecord rec = LogRecord.insert(lsn, prevLsnFor(txnId), txnId,
                    pageId, slotId, tupleData);
            txnLastLsn.put(txnId, lsn);
            appendToBuffer(rec);
            flushIfOverThreshold();
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Log a DELETE record with the old tuple bytes (needed for undo). Returns
     * the assigned LSN.
     */
    public long logDelete(long txnId, int pageId, int slotId, byte[] oldData) {
        lock.lock();
        try {
            long lsn = nextLsn();
            LogRecord rec = LogRecord.delete(lsn, prevLsnFor(txnId), txnId,
                    pageId, slotId, oldData);
            txnLastLsn.put(txnId, lsn);
            appendToBuffer(rec);
            flushIfOverThreshold();
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Log a COMMIT record and synchronously flush to disk. A transaction is
     * committed only once this method returns successfully.
     */
    public long logCommit(long txnId) {
        lock.lock();
        try {
            long lsn = nextLsn();
            LogRecord rec = LogRecord.commit(lsn, prevLsnFor(txnId), txnId);
            txnLastLsn.remove(txnId);
            appendToBuffer(rec);
            flushUnlocked(); // synchronous flush for commit durability
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Log an ABORT record and synchronously flush to disk.
     */
    public long logAbort(long txnId) {
        lock.lock();
        try {
            long lsn = nextLsn();
            LogRecord rec = LogRecord.abort(lsn, prevLsnFor(txnId), txnId);
            txnLastLsn.remove(txnId);
            appendToBuffer(rec);
            flushUnlocked(); // synchronous flush for abort durability
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Log a CLR (Compensation Log Record) during the undo phase of recovery.
     * {@code undoNextLsn} is the prevLsn of the record being undone — it tells
     * a future recovery pass to skip past already-undone records.
     */
    public long logClr(long txnId, int pageId, int slotId, long undoNextLsn, byte[] data) {
        lock.lock();
        try {
            long lsn = nextLsn();
            LogRecord rec = LogRecord.clr(lsn, prevLsnFor(txnId), txnId,
                    pageId, slotId, undoNextLsn, data);
            txnLastLsn.put(txnId, lsn);
            appendToBuffer(rec);
            flushIfOverThreshold();
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Log a CHECKPOINT record containing the current Active Transaction Table
     * and Dirty Page Table, then synchronously flush.
     */
    public long logCheckpoint(Map<Long, Long> activeTxnTable, Map<Integer, Long> dirtyPageTable) {
        lock.lock();
        try {
            long lsn = nextLsn();
            LogRecord rec = LogRecord.checkpoint(lsn, 0L, activeTxnTable, dirtyPageTable);
            appendToBuffer(rec);
            flushUnlocked();
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Flush (5.4 — WAL-rule callback)
    // -------------------------------------------------------------------------

    /**
     * Ensure all WAL records with LSN ≤ {@code targetLsn} are durable on disk.
     * Called by the Buffer Pool before writing any dirty page (WAL rule), and
     * on COMMIT/ABORT for transaction durability.
     */
    @Override
    public void flushToLsn(long targetLsn) {
        lock.lock();
        try {
            if (targetLsn <= flushedLsn) {
                return; // already durable
            }
            flushUnlocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return the highest LSN that is guaranteed to be on disk. Useful for
     * testing and for the Recovery Manager.
     */
    public long getFlushedLsn() {
        lock.lock();
        try {
            return flushedLsn;
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Read side (5.5)
    // -------------------------------------------------------------------------

    /**
     * Return a forward iterator over all log records with {@code lsn >= startLsn}.
     * The buffer is flushed first so every record is on disk. The caller must
     * not append new records while iterating (no external synchronization is
     * provided between iteration and mutation).
     */
    public Iterator<LogRecord> readLog(long startLsn) {
        flushToLsn(Long.MAX_VALUE);
        return new WalIterator(walPath, startLsn);
    }

    /**
     * Read and return the single log record whose LSN equals {@code lsn}.
     * The buffer is flushed first to guarantee the record is on disk.
     *
     * @throws StorageException if no record with that LSN exists
     */
    public LogRecord readRecordAtLsn(long lsn) {
        flushToLsn(lsn);

        Long offset;
        lock.lock();
        try {
            offset = lsnToFileOffset.get(lsn);
        } finally {
            lock.unlock();
        }

        if (offset == null) {
            throw new StorageException("No WAL record found for LSN " + lsn);
        }

        try (RandomAccessFile reader = new RandomAccessFile(walPath.toFile(), "r")) {
            reader.seek(offset);
            int len = reader.readInt();
            byte[] bytes = new byte[len];
            reader.readFully(bytes);
            return LogRecord.deserialize(bytes);
        } catch (IOException e) {
            throw new StorageException("readRecordAtLsn failed for LSN " + lsn + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Flush any remaining buffered records and close the WAL file. */
    public void close() throws IOException {
        flushToLsn(Long.MAX_VALUE);
        walFile.close();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Must be called with lock held. Assigns and returns the next LSN. */
    private long nextLsn() {
        return currentLsn++;
    }

    /** Must be called with lock held. Returns the prevLsn for a transaction. */
    private long prevLsnFor(long txnId) {
        return txnLastLsn.getOrDefault(txnId, LogRecord.NO_PREVIOUS_LSN);
    }

    /**
     * Serialize {@code rec}, record its file offset, and append to the buffer.
     * Must be called with lock held.
     */
    private void appendToBuffer(LogRecord rec) {
        byte[] body = rec.serialize();
        long offset = fileWritePosition + logBuffer.size();
        lsnToFileOffset.put(rec.lsn, offset);

        // Write length prefix (4 bytes, big-endian) + body
        logBuffer.write((byte) (body.length >>> 24));
        logBuffer.write((byte) (body.length >>> 16));
        logBuffer.write((byte) (body.length >>> 8));
        logBuffer.write((byte)  body.length);
        logBuffer.write(body, 0, body.length);

        if (rec.lsn > maxBufferedLsn) {
            maxBufferedLsn = rec.lsn;
        }
    }

    /**
     * Flush buffer if it has grown past the threshold. Must be called with lock
     * held; may release and reacquire.
     */
    private void flushIfOverThreshold() {
        if (logBuffer.size() >= FLUSH_THRESHOLD) {
            flushUnlocked();
        }
    }

    /**
     * Write the current log buffer to disk and sync, then update
     * {@code fileWritePosition} and {@code flushedLsn}. Must be called with
     * lock held; releases and reacquires the lock around I/O.
     */
    private void flushUnlocked() {
        if (logBuffer.size() == 0) {
            return;
        }
        long target = maxBufferedLsn;
        byte[] data = logBuffer.toByteArray();
        logBuffer.reset();
        maxBufferedLsn = -1;

        // Update position BEFORE releasing lock so concurrent flushes use the
        // correct write position.
        long writePos = fileWritePosition;
        fileWritePosition += data.length;

        lock.unlock();
        try {
            synchronized (walFile) {
                walFile.seek(writePos);
                walFile.write(data);
                walFile.getFD().sync();
            }
        } catch (IOException e) {
            throw new StorageException("WAL flush failed: " + e.getMessage());
        } finally {
            lock.lock();
        }

        if (target > flushedLsn) {
            flushedLsn = target;
        }
    }

    /**
     * Scan the WAL file to rebuild {@code lsnToFileOffset}, {@code txnLastLsn},
     * {@code currentLsn}, {@code flushedLsn}, and {@code fileWritePosition}
     * after a restart. Called from the constructor when the file is non-empty.
     */
    private void rebuildState() throws IOException {
        long pos = 0;
        long fileLen = walFile.length();
        long maxLsn = -1;

        walFile.seek(0);
        while (pos < fileLen) {
            if (pos + 4 > fileLen) break; // truncated length prefix
            int len = walFile.readInt();
            if (len < 0 || pos + 4 + len > fileLen) break; // truncated record

            byte[] bytes = new byte[len];
            walFile.readFully(bytes);

            LogRecord rec = LogRecord.deserialize(bytes);
            lsnToFileOffset.put(rec.lsn, pos);
            if (rec.lsn > maxLsn) maxLsn = rec.lsn;

            // Maintain txnLastLsn (won't be fully accurate for completed txns,
            // but the Recovery Manager rebuilds ATT properly in Phase 6).
            if (rec.txnId != 0) {
                txnLastLsn.merge(rec.txnId, rec.lsn, Math::max);
            }

            pos += 4L + len;
        }

        if (maxLsn >= 0) {
            currentLsn = maxLsn + 1;
            flushedLsn = maxLsn;
        }
        fileWritePosition = pos;
    }

    // -------------------------------------------------------------------------
    // Lazy file-scanning iterator (5.5)
    // -------------------------------------------------------------------------

    private static final class WalIterator implements Iterator<LogRecord> {

        private final RandomAccessFile reader;
        private final long startLsn;
        private long pos = 0;
        private long fileLen;
        private LogRecord pending;
        private boolean done = false;

        WalIterator(Path walPath, long startLsn) {
            this.startLsn = startLsn;
            try {
                this.reader = new RandomAccessFile(walPath.toFile(), "r");
                this.fileLen = reader.length();
            } catch (IOException e) {
                throw new StorageException("Cannot open WAL for reading: " + e.getMessage());
            }
            advance();
        }

        @Override
        public boolean hasNext() {
            return pending != null;
        }

        @Override
        public LogRecord next() {
            if (pending == null) throw new NoSuchElementException();
            LogRecord result = pending;
            advance();
            return result;
        }

        private void advance() {
            if (done) {
                pending = null;
                return;
            }
            try {
                while (pos < fileLen) {
                    if (pos + 4 > fileLen) break;
                    int len = reader.readInt();
                    if (len < 0 || pos + 4 + len > fileLen) break;

                    byte[] bytes = new byte[len];
                    reader.readFully(bytes);
                    pos += 4L + len;

                    LogRecord rec = LogRecord.deserialize(bytes);
                    if (rec.lsn >= startLsn) {
                        pending = rec;
                        return;
                    }
                }
                // No more records
                reader.close();
                done = true;
                pending = null;
            } catch (IOException e) {
                done = true;
                pending = null;
                throw new StorageException("WAL read error: " + e.getMessage());
            }
        }
    }
}
