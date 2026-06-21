package com.versiondb.wal;

/** Discriminator byte stored in every WAL record header. */
public enum LogType {
    BEGIN((byte) 1),
    INSERT((byte) 2),
    DELETE((byte) 3),
    COMMIT((byte) 4),
    ABORT((byte) 5),
    CHECKPOINT((byte) 6),
    CLR((byte) 7);

    public final byte code;

    LogType(byte code) {
        this.code = code;
    }

    public static LogType fromCode(byte code) {
        for (LogType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("Unknown log type code: " + code);
    }
}
