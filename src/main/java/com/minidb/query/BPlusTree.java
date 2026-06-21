package com.minidb.query;

import com.minidb.shared.ColumnType;
import com.minidb.shared.Constants;
import com.minidb.shared.RID;
import com.minidb.shared.Value;
import com.minidb.storage.BufferPool;
import com.minidb.storage.Page;

/**
 * A page-backed B+Tree index over a single column, giving O(log n) point lookup
 * and O(log n + k) range scan of {@code (key, RID)} pairs ({@code part2.md} §4).
 * Every node is one buffer-pool page; the tree never touches the disk directly.
 *
 * <p><b>Node access pattern.</b> Each operation fetches a page, deserializes its
 * payload into an in-memory {@link BTreeNode}, and unpins immediately. Mutations
 * serialize the node back and mark the page dirty. Working on detached node
 * objects keeps pin lifetimes short and avoids juggling pin counts across the
 * recursion in split/merge.
 *
 * <p><b>Root page id.</b> The root can move when the tree grows (root split) or
 * shrinks (root merge), so {@link #getRootPageId()} must be re-read and the
 * catalog updated after structural changes (the execution engine does this in
 * Phase 15).
 *
 * <p><b>Keys.</b> Keys are non-null {@link Value}s of the index's
 * {@link ColumnType}. VARCHAR keys are capped at {@link #MAX_VARCHAR_KEY_BYTES}
 * so a full node always fits in a page.
 */
public final class BPlusTree {

    /** Maximum UTF-8 length of a VARCHAR key; bounds worst-case node size. */
    public static final int MAX_VARCHAR_KEY_BYTES = 256;

    /** Upper bound on the per-node header (type, numKeys, parent, nextLeaf). */
    private static final int NODE_HEADER_BYTES = 16;

    private final BufferPool bufferPool;
    private final ColumnType keyType;
    /** Split threshold is {@code 2*order}; a non-root node keeps at least {@code order} keys. */
    private final int order;
    private int rootPageId;

    private BPlusTree(BufferPool bufferPool, ColumnType keyType, int rootPageId, int order) {
        this.bufferPool = bufferPool;
        this.keyType = keyType;
        this.rootPageId = rootPageId;
        this.order = order;
    }

    /** Create an empty tree (root is an empty leaf) with the order derived from the key type. */
    public static BPlusTree create(BufferPool bufferPool, ColumnType keyType) {
        return create(bufferPool, keyType, defaultOrder(keyType));
    }

    /** Create an empty tree with an explicit order (used by tests to force splits cheaply). */
    static BPlusTree create(BufferPool bufferPool, ColumnType keyType, int order) {
        Page page = bufferPool.newPage();
        int rootId = page.getPageId();
        try {
            BTreeNode root = BTreeNode.newLeaf(keyType, rootId, Constants.INVALID_PAGE_ID);
            page.writePayload(root.serialize());
        } finally {
            bufferPool.unpin(rootId, true);
        }
        return new BPlusTree(bufferPool, keyType, rootId, order);
    }

    /** Open an existing tree at {@code rootPageId} with the order derived from the key type. */
    public static BPlusTree open(BufferPool bufferPool, ColumnType keyType, int rootPageId) {
        return open(bufferPool, keyType, rootPageId, defaultOrder(keyType));
    }

    static BPlusTree open(BufferPool bufferPool, ColumnType keyType, int rootPageId, int order) {
        return new BPlusTree(bufferPool, keyType, rootPageId, order);
    }

    /** The page id of the current root. Re-read after any structural change. */
    public int getRootPageId() {
        return rootPageId;
    }

    ColumnType keyType() {
        return keyType;
    }

    int order() {
        return order;
    }

    // ---- Node IO helpers ------------------------------------------------------

    BTreeNode readNode(int pageId) {
        Page page = bufferPool.fetchPage(pageId);
        try {
            return BTreeNode.deserialize(keyType, pageId, page.readPayload());
        } finally {
            bufferPool.unpin(pageId, false);
        }
    }

    void writeNode(BTreeNode node) {
        Page page = bufferPool.fetchPage(node.pageId);
        try {
            page.writePayload(node.serialize());
        } finally {
            bufferPool.unpin(node.pageId, true);
        }
    }

    /** Allocate a fresh page and return its id; the caller writes the node. */
    int allocatePage() {
        Page page = bufferPool.newPage();
        int id = page.getPageId();
        bufferPool.unpin(id, true);
        return id;
    }

    int compare(Value a, Value b) {
        return BTreeNode.compare(keyType, a, b);
    }

    // ---- 13.2: search ---------------------------------------------------------

    /**
     * Find the RID stored under {@code key}, or {@code null} if the key is not
     * present. When several entries share the key, the first (leftmost) RID is
     * returned. O(log n).
     */
    public RID search(Value key) {
        BTreeNode leaf = findLeaf(key);
        int i = lowerBound(leaf, key);
        if (i < leaf.keyCount() && compare(leaf.keys.get(i), key) == 0) {
            return leaf.rids.get(i);
        }
        return null;
    }

    /** Descend from the root to the leaf that would contain {@code key}. */
    BTreeNode findLeaf(Value key) {
        BTreeNode node = readNode(rootPageId);
        while (!node.leaf) {
            node = readNode(node.children.get(childIndex(node, key)));
        }
        return node;
    }

    /**
     * Index of the child to descend into for {@code key}: the number of
     * separator keys {@code <= key}. Keys left of the chosen child are strictly
     * less than {@code key}; the chosen subtree holds keys {@code >= } the
     * separator to its left.
     */
    int childIndex(BTreeNode inner, Value key) {
        int lo = 0;
        int hi = inner.keyCount();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(key, inner.keys.get(mid)) < 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** First index in {@code leaf} whose key is {@code >= key} (insertion point). */
    int lowerBound(BTreeNode leaf, Value key) {
        int lo = 0;
        int hi = leaf.keyCount();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(leaf.keys.get(mid), key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ---- Order sizing ---------------------------------------------------------

    /**
     * Derive an order so that a node at maximum key size still fits in a page
     * with room to spare: a node may hold up to {@code 2*order} keys before it
     * splits, and both a full leaf and a full inner node must fit.
     */
    static int defaultOrder(ColumnType keyType) {
        int keyBytes = maxKeyBytes(keyType);
        int usable = Page.MAX_PAYLOAD_SIZE - NODE_HEADER_BYTES;
        int maxLeafEntries = usable / (keyBytes + 2 * Integer.BYTES);     // key + RID(page,slot)
        int maxInnerKeys = (usable - Integer.BYTES) / (keyBytes + Integer.BYTES); // key + child, plus trailing child
        int maxKeys = Math.min(maxLeafEntries, maxInnerKeys);
        return Math.max(maxKeys / 2, 2);
    }

    private static int maxKeyBytes(ColumnType keyType) {
        return switch (keyType) {
            case INT, FLOAT -> Long.BYTES;
            case BOOL -> 1;
            case VARCHAR -> Integer.BYTES + MAX_VARCHAR_KEY_BYTES;
        };
    }
}
