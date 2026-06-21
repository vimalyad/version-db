package com.versiondb.query.exec;

import com.versiondb.query.BPlusTree;
import com.versiondb.storage.Catalog;
import com.versiondb.storage.HeapFile;
import com.versiondb.txn.MVCCManager;
import com.versiondb.txn.Transaction;

import java.util.Map;

/**
 * The shared state every operator needs while running a query ({@code part2.md}
 * §6.3): the current transaction (and its snapshot), the MVCC manager for
 * visibility checks and writes, the catalog for schema lookups, and the
 * registries that map a table name to its {@link HeapFile} and a B+Tree root
 * page id to its {@link BPlusTree}.
 */
public final class ExecutionContext {

    private final Transaction transaction;
    private final MVCCManager mvccManager;
    private final Catalog catalog;
    private final Map<String, HeapFile> heapFileRegistry;
    private final Map<Integer, BPlusTree> indexRegistry;

    public ExecutionContext(Transaction transaction,
                            MVCCManager mvccManager,
                            Catalog catalog,
                            Map<String, HeapFile> heapFileRegistry,
                            Map<Integer, BPlusTree> indexRegistry) {
        this.transaction = transaction;
        this.mvccManager = mvccManager;
        this.catalog = catalog;
        this.heapFileRegistry = heapFileRegistry;
        this.indexRegistry = indexRegistry;
    }

    public Transaction transaction() {
        return transaction;
    }

    public MVCCManager mvccManager() {
        return mvccManager;
    }

    public Catalog catalog() {
        return catalog;
    }

    /** The heap file backing {@code tableName}, or {@code null} if not registered. */
    public HeapFile heapFile(String tableName) {
        return heapFileRegistry.get(tableName);
    }

    /** The B+Tree rooted at {@code rootPageId}, or {@code null} if not registered. */
    public BPlusTree index(int rootPageId) {
        return indexRegistry.get(rootPageId);
    }
}
