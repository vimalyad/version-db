package com.versiondb.wal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * An immutable WAL log record. Every record carries a common header; the
 * type-specific fields are populated only for the relevant {@link LogType}.
 *
 * <p>Wire format (excluding the 4-byte length prefix written by WALManager):
 * <pre>
 *   lsn       : 8 bytes (long, big-endian)
 *   prevLsn   : 8 bytes (long, big-endian)
 *   txnId     : 8 bytes (long, big-endian)
 *   logType   : 1 byte
 *   body      : type-specific (see below)
 * </pre>
 *
 * <p>Bodies by type:
 * <ul>
 *   <li>BEGIN, COMMIT, ABORT — no body</li>
 *   <li>INSERT, DELETE — 4B pageId + 4B slotId + 4B dataLen + dataLen bytes</li>
 *   <li>CLR — 4B pageId + 4B slotId + 8B undoNextLsn + 4B dataLen + dataLen bytes</li>
 *   <li>CHECKPOINT — 4B attSize + attSize×(8B txnId + 8B lastLsn)
 *                  + 4B dptSize + dptSize×(4B pageId + 8B recLsn)</li>
 * </ul>
 */
public final class LogRecord {

    /** Sentinel prevLsn value meaning "no previous record for this transaction". */
    public static final long NO_PREVIOUS_LSN = -1L;

    // Common header
    public final long lsn;
    public final long prevLsn;
    public final long txnId;
    public final LogType logType;

    // INSERT / DELETE / CLR
    public final int pageId;
    public final int slotId;
    public final byte[] data;

    // CLR only
    public final long undoNextLsn;

    // CHECKPOINT only
    public final Map<Long, Long> activeTxnTable;
    public final Map<Integer, Long> dirtyPageTable;

    private LogRecord(long lsn, long prevLsn, long txnId, LogType logType,
                      int pageId, int slotId, byte[] data, long undoNextLsn,
                      Map<Long, Long> att, Map<Integer, Long> dpt) {
        this.lsn = lsn;
        this.prevLsn = prevLsn;
        this.txnId = txnId;
        this.logType = logType;
        this.pageId = pageId;
        this.slotId = slotId;
        this.data = data;
        this.undoNextLsn = undoNextLsn;
        this.activeTxnTable = att;
        this.dirtyPageTable = dpt;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static LogRecord begin(long lsn, long txnId) {
        return new LogRecord(lsn, NO_PREVIOUS_LSN, txnId, LogType.BEGIN,
                0, 0, null, 0, null, null);
    }

    public static LogRecord insert(long lsn, long prevLsn, long txnId,
                                   int pageId, int slotId, byte[] data) {
        return new LogRecord(lsn, prevLsn, txnId, LogType.INSERT,
                pageId, slotId, data, 0, null, null);
    }

    public static LogRecord delete(long lsn, long prevLsn, long txnId,
                                   int pageId, int slotId, byte[] oldData) {
        return new LogRecord(lsn, prevLsn, txnId, LogType.DELETE,
                pageId, slotId, oldData, 0, null, null);
    }

    public static LogRecord commit(long lsn, long prevLsn, long txnId) {
        return new LogRecord(lsn, prevLsn, txnId, LogType.COMMIT,
                0, 0, null, 0, null, null);
    }

    public static LogRecord abort(long lsn, long prevLsn, long txnId) {
        return new LogRecord(lsn, prevLsn, txnId, LogType.ABORT,
                0, 0, null, 0, null, null);
    }

    public static LogRecord clr(long lsn, long prevLsn, long txnId,
                                int pageId, int slotId, long undoNextLsn, byte[] data) {
        return new LogRecord(lsn, prevLsn, txnId, LogType.CLR,
                pageId, slotId, data, undoNextLsn, null, null);
    }

    public static LogRecord checkpoint(long lsn, long txnId,
                                       Map<Long, Long> att, Map<Integer, Long> dpt) {
        return new LogRecord(lsn, NO_PREVIOUS_LSN, txnId, LogType.CHECKPOINT,
                0, 0, null, 0,
                Collections.unmodifiableMap(new HashMap<>(att)),
                Collections.unmodifiableMap(new HashMap<>(dpt)));
    }

    // -------------------------------------------------------------------------
    // Serialization / deserialization
    // -------------------------------------------------------------------------

    /**
     * Serialize this record to bytes (without the 4-byte length prefix). The
     * WALManager prepends the length when writing to the log file.
     */
    public byte[] serialize() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeLong(lsn);
            dos.writeLong(prevLsn);
            dos.writeLong(txnId);
            dos.writeByte(logType.code);

            switch (logType) {
                case BEGIN, COMMIT, ABORT -> { /* no body */ }

                case INSERT, DELETE -> {
                    dos.writeInt(pageId);
                    dos.writeInt(slotId);
                    dos.writeInt(data == null ? 0 : data.length);
                    if (data != null && data.length > 0) dos.write(data);
                }

                case CLR -> {
                    dos.writeInt(pageId);
                    dos.writeInt(slotId);
                    dos.writeLong(undoNextLsn);
                    dos.writeInt(data == null ? 0 : data.length);
                    if (data != null && data.length > 0) dos.write(data);
                }

                case CHECKPOINT -> {
                    dos.writeInt(activeTxnTable.size());
                    for (Map.Entry<Long, Long> e : activeTxnTable.entrySet()) {
                        dos.writeLong(e.getKey());
                        dos.writeLong(e.getValue());
                    }
                    dos.writeInt(dirtyPageTable.size());
                    for (Map.Entry<Integer, Long> e : dirtyPageTable.entrySet()) {
                        dos.writeInt(e.getKey());
                        dos.writeLong(e.getValue());
                    }
                }
            }
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream should never throw", e);
        }
        return baos.toByteArray();
    }

    /**
     * Deserialize a record from its body bytes (the bytes after the 4-byte
     * length prefix has already been consumed).
     */
    public static LogRecord deserialize(byte[] bytes) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            long lsn = dis.readLong();
            long prevLsn = dis.readLong();
            long txnId = dis.readLong();
            LogType logType = LogType.fromCode(dis.readByte());

            return switch (logType) {
                case BEGIN, COMMIT, ABORT ->
                    new LogRecord(lsn, prevLsn, txnId, logType, 0, 0, null, 0, null, null);

                case INSERT, DELETE -> {
                    int pageId = dis.readInt();
                    int slotId = dis.readInt();
                    int dataLen = dis.readInt();
                    byte[] data = new byte[dataLen];
                    if (dataLen > 0) dis.readFully(data);
                    yield new LogRecord(lsn, prevLsn, txnId, logType, pageId, slotId, data, 0, null, null);
                }

                case CLR -> {
                    int pageId = dis.readInt();
                    int slotId = dis.readInt();
                    long undoNextLsn = dis.readLong();
                    int dataLen = dis.readInt();
                    byte[] data = new byte[dataLen];
                    if (dataLen > 0) dis.readFully(data);
                    yield new LogRecord(lsn, prevLsn, txnId, logType, pageId, slotId, data, undoNextLsn, null, null);
                }

                case CHECKPOINT -> {
                    int attSize = dis.readInt();
                    Map<Long, Long> att = new HashMap<>(attSize);
                    for (int i = 0; i < attSize; i++) {
                        att.put(dis.readLong(), dis.readLong());
                    }
                    int dptSize = dis.readInt();
                    Map<Integer, Long> dpt = new HashMap<>(dptSize);
                    for (int i = 0; i < dptSize; i++) {
                        dpt.put(dis.readInt(), dis.readLong());
                    }
                    yield new LogRecord(lsn, NO_PREVIOUS_LSN, txnId, logType,
                            0, 0, null, 0,
                            Collections.unmodifiableMap(att),
                            Collections.unmodifiableMap(dpt));
                }
            };
        } catch (IOException e) {
            throw new AssertionError("ByteArrayInputStream should never throw", e);
        }
    }

    @Override
    public String toString() {
        return "LogRecord{lsn=" + lsn + ", prevLsn=" + prevLsn
                + ", txnId=" + txnId + ", type=" + logType + "}";
    }
}
