package com.versiondb.query;

import com.versiondb.shared.ColumnType;
import com.versiondb.shared.Constants;
import com.versiondb.shared.RID;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.Value;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.HeapFile;
import com.versiondb.storage.Page;
import com.versiondb.storage.TupleCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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

    /**
     * Bulk-build an index over one column by scanning a heap file (the
     * {@code CREATE INDEX} path, {@code part2.md} §4.9). Each live tuple is
     * decoded with {@link TupleCodec} against {@code rowSchema}, and its
     * indexed-column value (when non-null) is inserted with the tuple's RID.
     * NULL keys are skipped — they are not indexed. The caller registers the
     * resulting {@link #getRootPageId()} in the catalog.
     *
     * @param rowSchema   column types of the table, in order
     * @param columnIndex position of the indexed column within {@code rowSchema}
     */
    public static BPlusTree build(BufferPool bufferPool, HeapFile heap,
                                  List<ColumnType> rowSchema, int columnIndex) {
        ColumnType keyType = rowSchema.get(columnIndex);
        BPlusTree tree = create(bufferPool, keyType);
        Iterator<HeapFile.Entry> it = heap.scan();
        while (it.hasNext()) {
            HeapFile.Entry entry = it.next();
            Value key = TupleCodec.decode(entry.data(), rowSchema).get(columnIndex);
            if (key != null && !key.isNull()) {
                tree.insert(key, entry.rid());
            }
        }
        return tree;
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

    // ---- 13.3: insert + splits ------------------------------------------------

    /** A node overflows (must split) once it holds more than this many keys. */
    private int maxKeys() {
        return 2 * order;
    }

    /**
     * Insert a {@code (key, RID)} entry, splitting nodes as needed and growing
     * the tree's height when the root splits. Duplicate keys are allowed (the
     * index column may not be unique); each maps to its own RID.
     */
    public void insert(Value key, RID rid) {
        validateKey(key);
        BTreeNode leaf = findLeaf(key);
        int pos = lowerBound(leaf, key);
        leaf.keys.add(pos, key);
        leaf.rids.add(pos, rid);

        if (leaf.keyCount() > maxKeys()) {
            splitLeaf(leaf);
        } else {
            writeNode(leaf);
        }
    }

    private void splitLeaf(BTreeNode leaf) {
        int n = leaf.keyCount();
        int mid = n / 2;

        int rightId = allocatePage();
        BTreeNode right = BTreeNode.newLeaf(keyType, rightId, leaf.parentPageId);
        right.keys.addAll(new ArrayList<>(leaf.keys.subList(mid, n)));
        right.rids.addAll(new ArrayList<>(leaf.rids.subList(mid, n)));
        leaf.keys.subList(mid, n).clear();
        leaf.rids.subList(mid, n).clear();

        // Splice the new leaf into the leaf chain.
        right.nextLeaf = leaf.nextLeaf;
        leaf.nextLeaf = rightId;

        Value separator = right.keys.get(0); // smallest of right is copied up
        writeNode(leaf);
        writeNode(right);
        insertIntoParent(leaf, separator, right);
    }

    private void splitInner(BTreeNode node) {
        int n = node.keyCount();
        int mid = n / 2;
        Value pushUp = node.keys.get(mid); // middle key moves up (not copied)

        int rightId = allocatePage();
        BTreeNode right = BTreeNode.newInner(keyType, rightId, node.parentPageId);
        right.keys.addAll(new ArrayList<>(node.keys.subList(mid + 1, n)));
        right.children.addAll(new ArrayList<>(node.children.subList(mid + 1, n + 1)));
        node.keys.subList(mid, n).clear();
        node.children.subList(mid + 1, n + 1).clear();

        // The moved children now belong to the new right node.
        reparent(right.children, rightId);

        writeNode(node);
        writeNode(right);
        insertIntoParent(node, pushUp, right);
    }

    /**
     * Insert {@code separator} (with {@code right} as the new child to the right
     * of {@code left}) into {@code left}'s parent, creating a new root if
     * {@code left} was the root.
     */
    private void insertIntoParent(BTreeNode left, Value separator, BTreeNode right) {
        if (left.parentPageId == Constants.INVALID_PAGE_ID) {
            int newRootId = allocatePage();
            BTreeNode root = BTreeNode.newInner(keyType, newRootId, Constants.INVALID_PAGE_ID);
            root.children.add(left.pageId);
            root.keys.add(separator);
            root.children.add(right.pageId);
            left.parentPageId = newRootId;
            right.parentPageId = newRootId;
            writeNode(left);
            writeNode(right);
            writeNode(root);
            rootPageId = newRootId;
            return;
        }

        BTreeNode parent = readNode(left.parentPageId);
        int leftIdx = parent.children.indexOf(left.pageId);
        parent.keys.add(leftIdx, separator);
        parent.children.add(leftIdx + 1, right.pageId);
        right.parentPageId = parent.pageId;
        writeNode(right);

        if (parent.keyCount() > maxKeys()) {
            splitInner(parent);
        } else {
            writeNode(parent);
        }
    }

    /** Point each child page's parent pointer at {@code parentId}. */
    private void reparent(List<Integer> children, int parentId) {
        for (int childId : children) {
            BTreeNode child = readNode(childId);
            child.parentPageId = parentId;
            writeNode(child);
        }
    }

    // ---- 13.4: range scan -----------------------------------------------------

    /**
     * Return the RIDs of all entries whose key is in {@code [low, high]}
     * (inclusive), in ascending key order. A {@code null} bound is open: a null
     * {@code low} scans from the smallest key, a null {@code high} to the
     * largest. After an O(log n) descent to the starting leaf, this is a linear
     * walk of the {@code nextLeaf} chain — O(log n + k) for k results.
     */
    public List<RID> rangeScan(Value low, Value high) {
        List<RID> out = new ArrayList<>();
        BTreeNode leaf = (low == null) ? leftmostLeaf() : findLeafForLowerBound(low);
        int i = (low == null) ? 0 : lowerBound(leaf, low);
        while (leaf != null) {
            for (; i < leaf.keyCount(); i++) {
                if (high != null && compare(leaf.keys.get(i), high) > 0) {
                    return out;
                }
                out.add(leaf.rids.get(i));
            }
            int next = leaf.nextLeaf;
            leaf = (next == Constants.INVALID_PAGE_ID) ? null : readNode(next);
            i = 0;
        }
        return out;
    }

    /** Descend leftmost children to the first leaf. */
    private BTreeNode leftmostLeaf() {
        BTreeNode node = readNode(rootPageId);
        while (!node.leaf) {
            node = readNode(node.children.get(0));
        }
        return node;
    }

    /**
     * Descend to the leftmost leaf that could contain {@code key}. Unlike
     * {@link #findLeaf} (which routes right on an equal separator, fine for point
     * lookup), this routes left on equality so a range scan does not skip equal
     * keys that live in an earlier leaf — duplicates of a key can straddle the
     * separator. The forward chain walk then collects all of them.
     */
    private BTreeNode findLeafForLowerBound(Value key) {
        BTreeNode node = readNode(rootPageId);
        while (!node.leaf) {
            int lo = 0;
            int hi = node.keyCount();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (compare(key, node.keys.get(mid)) <= 0) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            node = readNode(node.children.get(lo));
        }
        return node;
    }

    // ---- 13.5: delete + borrow/merge ------------------------------------------

    /** A non-root node underflows (must borrow or merge) below this many keys. */
    private int minKeys() {
        return order;
    }

    /**
     * Remove the entry {@code (key, rid)}. Returns {@code false} if no such
     * entry exists. Leaf underflow is repaired by borrowing from a sibling when
     * one has a spare entry, otherwise by merging; the fix-up propagates up the
     * tree and shrinks the root by one level when it is left with a single
     * child.
     *
     * <p>Merged-away pages are orphaned (the disk manager has no free list); this
     * is a deliberate capstone simplification — correctness holds, only the file
     * may retain unused pages until rebuilt.
     */
    public boolean delete(Value key, RID rid) {
        BTreeNode leaf = locateLeaf(key, rid);
        if (leaf == null) {
            return false;
        }
        int idx = indexOfEntry(leaf, key, rid);
        leaf.keys.remove(idx);
        leaf.rids.remove(idx);
        writeNode(leaf);

        if (leaf.pageId != rootPageId && leaf.keyCount() < minKeys()) {
            handleLeafUnderflow(leaf);
        }
        return true;
    }

    /** Find the leaf holding the exact {@code (key, rid)} entry, or null. */
    private BTreeNode locateLeaf(Value key, RID rid) {
        BTreeNode leaf = findLeafForLowerBound(key);
        while (leaf != null) {
            for (int i = 0; i < leaf.keyCount(); i++) {
                int c = compare(leaf.keys.get(i), key);
                if (c > 0) {
                    return null; // walked past the key
                }
                if (c == 0 && leaf.rids.get(i).equals(rid)) {
                    return leaf;
                }
            }
            leaf = (leaf.nextLeaf == Constants.INVALID_PAGE_ID) ? null : readNode(leaf.nextLeaf);
        }
        return null;
    }

    private int indexOfEntry(BTreeNode leaf, Value key, RID rid) {
        for (int i = 0; i < leaf.keyCount(); i++) {
            if (compare(leaf.keys.get(i), key) == 0 && leaf.rids.get(i).equals(rid)) {
                return i;
            }
        }
        return -1;
    }

    private void handleLeafUnderflow(BTreeNode leaf) {
        BTreeNode parent = readNode(leaf.parentPageId);
        int idx = parent.children.indexOf(leaf.pageId);

        // Borrow from the left sibling.
        if (idx > 0) {
            BTreeNode left = readNode(parent.children.get(idx - 1));
            if (left.keyCount() > minKeys()) {
                int last = left.keyCount() - 1;
                leaf.keys.add(0, left.keys.remove(last));
                leaf.rids.add(0, left.rids.remove(last));
                parent.keys.set(idx - 1, leaf.keys.get(0));
                writeNode(left);
                writeNode(leaf);
                writeNode(parent);
                return;
            }
        }
        // Borrow from the right sibling.
        if (idx < parent.children.size() - 1) {
            BTreeNode right = readNode(parent.children.get(idx + 1));
            if (right.keyCount() > minKeys()) {
                leaf.keys.add(right.keys.remove(0));
                leaf.rids.add(right.rids.remove(0));
                parent.keys.set(idx, right.keys.get(0));
                writeNode(right);
                writeNode(leaf);
                writeNode(parent);
                return;
            }
        }
        // Merge with a sibling.
        if (idx > 0) {
            BTreeNode left = readNode(parent.children.get(idx - 1));
            left.keys.addAll(leaf.keys);
            left.rids.addAll(leaf.rids);
            left.nextLeaf = leaf.nextLeaf;
            parent.keys.remove(idx - 1);
            parent.children.remove(idx);
            writeNode(left);
            afterChildRemoved(parent);
        } else {
            BTreeNode right = readNode(parent.children.get(idx + 1));
            leaf.keys.addAll(right.keys);
            leaf.rids.addAll(right.rids);
            leaf.nextLeaf = right.nextLeaf;
            parent.keys.remove(idx);
            parent.children.remove(idx + 1);
            writeNode(leaf);
            afterChildRemoved(parent);
        }
    }

    private void handleInnerUnderflow(BTreeNode node) {
        BTreeNode parent = readNode(node.parentPageId);
        int idx = parent.children.indexOf(node.pageId);

        // Borrow from the left sibling: rotate the separator down, lift left's last key up.
        if (idx > 0) {
            BTreeNode left = readNode(parent.children.get(idx - 1));
            if (left.keyCount() > minKeys()) {
                int lastKey = left.keyCount() - 1;
                int lastChild = left.children.size() - 1;
                node.keys.add(0, parent.keys.get(idx - 1));
                node.children.add(0, left.children.remove(lastChild));
                parent.keys.set(idx - 1, left.keys.remove(lastKey));
                reparentOne(node.children.get(0), node.pageId);
                writeNode(left);
                writeNode(node);
                writeNode(parent);
                return;
            }
        }
        // Borrow from the right sibling.
        if (idx < parent.children.size() - 1) {
            BTreeNode right = readNode(parent.children.get(idx + 1));
            if (right.keyCount() > minKeys()) {
                node.keys.add(parent.keys.get(idx));
                node.children.add(right.children.remove(0));
                parent.keys.set(idx, right.keys.remove(0));
                reparentOne(node.children.get(node.children.size() - 1), node.pageId);
                writeNode(right);
                writeNode(node);
                writeNode(parent);
                return;
            }
        }
        // Merge with a sibling, pulling the separator down between them.
        if (idx > 0) {
            BTreeNode left = readNode(parent.children.get(idx - 1));
            left.keys.add(parent.keys.get(idx - 1));
            left.keys.addAll(node.keys);
            left.children.addAll(node.children);
            reparent(node.children, left.pageId);
            parent.keys.remove(idx - 1);
            parent.children.remove(idx);
            writeNode(left);
            afterChildRemoved(parent);
        } else {
            BTreeNode right = readNode(parent.children.get(idx + 1));
            node.keys.add(parent.keys.get(idx));
            node.keys.addAll(right.keys);
            node.children.addAll(right.children);
            reparent(right.children, node.pageId);
            parent.keys.remove(idx);
            parent.children.remove(idx + 1);
            writeNode(node);
            afterChildRemoved(parent);
        }
    }

    /**
     * After a key/child was removed from {@code parent}: shrink the tree if the
     * root is now empty, otherwise persist and recurse if the parent underflows.
     */
    private void afterChildRemoved(BTreeNode parent) {
        if (parent.pageId == rootPageId) {
            if (!parent.leaf && parent.keyCount() == 0) {
                int onlyChild = parent.children.get(0);
                BTreeNode child = readNode(onlyChild);
                child.parentPageId = Constants.INVALID_PAGE_ID;
                writeNode(child);
                rootPageId = onlyChild;
            } else {
                writeNode(parent);
            }
            return;
        }
        writeNode(parent);
        if (parent.keyCount() < minKeys()) {
            handleInnerUnderflow(parent);
        }
    }

    private void reparentOne(int childId, int parentId) {
        BTreeNode child = readNode(childId);
        child.parentPageId = parentId;
        writeNode(child);
    }

    private void validateKey(Value key) {
        if (key == null || key.isNull()) {
            throw new StorageException("B+Tree keys must be non-null");
        }
        if (keyType == ColumnType.VARCHAR
                && key.asString().getBytes(StandardCharsets.UTF_8).length > MAX_VARCHAR_KEY_BYTES) {
            throw new StorageException("VARCHAR key exceeds " + MAX_VARCHAR_KEY_BYTES + " bytes");
        }
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
        // order chosen so even the transient (2*order + 1)-key node during a
        // split still fits within maxKeys entries.
        return Math.max((maxKeys - 1) / 2, 2);
    }

    private static int maxKeyBytes(ColumnType keyType) {
        return switch (keyType) {
            case INT, FLOAT -> Long.BYTES;
            case BOOL -> 1;
            case VARCHAR -> Integer.BYTES + MAX_VARCHAR_KEY_BYTES;
        };
    }
}
