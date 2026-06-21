package com.versiondb.txn;

import com.versiondb.shared.RID;

/**
 * Called by {@link TransactionManager#abortTransaction} for each entry in the
 * aborting transaction's undo log. The MVCC Manager implements this interface
 * (wired in Phase 10) to reverse the corresponding write in the version store.
 *
 * <p>{@code oldData == null} signals an INSERT undo (logically delete the
 * inserted version); {@code oldData != null} signals a DELETE/UPDATE undo
 * (restore the previous version by clearing its {@code xmax}).
 */
@FunctionalInterface
public interface UndoCallback {
    void apply(long txnId, RID rid, byte[] oldData);
}
