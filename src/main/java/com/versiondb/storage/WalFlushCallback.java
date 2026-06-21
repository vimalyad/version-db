package com.versiondb.storage;

/**
 * Called by the buffer pool before writing any dirty page to disk. The
 * implementor must ensure all WAL records with LSN &le; {@code lsn} are
 * durable before returning, so the page write never overtakes its log records.
 */
@FunctionalInterface
public interface WalFlushCallback {
    void flushToLsn(long lsn);
}
