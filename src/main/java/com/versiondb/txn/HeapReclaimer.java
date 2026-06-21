package com.versiondb.txn;

import com.versiondb.shared.RID;

/**
 * Hook the vacuum process uses to physically free a heap slot once a row is
 * fully dead (every version of it has been reclaimed). Logical deletion in the
 * MVCC layer only stamps {@code xmax}; the heap bytes linger until garbage
 * collection removes them, which is what this callback does.
 *
 * <p>It is injected rather than referenced directly because there is no global
 * RID→{@code HeapFile} registry until the execution engine is built (Phase 15);
 * tests and integration wire it to {@code HeapFile::deleteTuple}.
 */
@FunctionalInterface
public interface HeapReclaimer {

    /** Physically free the heap slot addressed by {@code rid}. */
    void freeSlot(RID rid);

    /** A reclaimer that does nothing — used when physical reclamation is not wired. */
    HeapReclaimer NO_OP = rid -> { };
}
