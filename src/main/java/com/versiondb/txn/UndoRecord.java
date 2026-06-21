package com.versiondb.txn;

import com.versiondb.shared.RID;

/**
 * One entry in a transaction's in-memory undo log. Recorded by the MVCC
 * Manager on every write; processed in reverse order by
 * {@link TransactionManager#abortTransaction} to roll back the transaction.
 *
 * <p>{@code oldData == null} means the original operation was an INSERT — on
 * abort, the inserted version must be logically deleted. {@code oldData != null}
 * means the original operation was a DELETE or the delete half of an UPDATE —
 * on abort, the old version must be restored (its {@code xmax} set back to 0).
 */
public record UndoRecord(RID rid, byte[] oldData) {
}
