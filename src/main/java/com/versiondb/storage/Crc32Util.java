package com.versiondb.storage;

import java.util.zip.CRC32;

/** Helper for computing CRC32 checksums used to detect page corruption. */
final class Crc32Util {

    private Crc32Util() {
    }

    /**
     * Compute the CRC32 of {@code length} bytes of {@code data} starting at
     * {@code offset}, returned in the low 32 bits of an {@code int}. CRC32
     * produces a 32-bit value, so storing it as an int is lossless as long as
     * both the stored and recomputed values are compared as ints.
     */
    static int compute(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }
}
