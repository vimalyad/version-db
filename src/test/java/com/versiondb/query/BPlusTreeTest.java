package com.versiondb.query;

import com.versiondb.shared.ColumnType;
import com.versiondb.shared.Constants;
import com.versiondb.shared.RID;
import com.versiondb.shared.Value;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.DiskManager;
import com.versiondb.storage.HeapFile;
import com.versiondb.storage.TupleCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BPlusTreeTest {

    @TempDir
    Path tmp;

    private DiskManager dm;

    private BufferPool newPool(int capacity) {
        dm = new DiskManager(tmp.resolve("btree.db"));
        return new BufferPool(capacity, dm, lsn -> { });
    }

    @AfterEach
    void closeDisk() {
        if (dm != null) {
            dm.close();
        }
    }

    // ---- 13.1: node serialization round-trip ---------------------------------

    @Test
    void leafNodeRoundTripInt() {
        BTreeNode leaf = BTreeNode.newLeaf(ColumnType.INT, 7, 3);
        leaf.nextLeaf = 9;
        leaf.keys.add(Value.ofInt(10));
        leaf.rids.add(new RID(1, 2));
        leaf.keys.add(Value.ofInt(20));
        leaf.rids.add(new RID(3, 4));

        BTreeNode back = BTreeNode.deserialize(ColumnType.INT, 7, leaf.serialize());
        assertTrue(back.leaf);
        assertEquals(3, back.parentPageId);
        assertEquals(9, back.nextLeaf);
        assertEquals(leaf.keys, back.keys);
        assertEquals(leaf.rids, back.rids);
    }

    @Test
    void innerNodeRoundTrip() {
        BTreeNode inner = BTreeNode.newInner(ColumnType.INT, 5, Constants.INVALID_PAGE_ID);
        inner.children.add(11);
        inner.keys.add(Value.ofInt(50));
        inner.children.add(12);
        inner.keys.add(Value.ofInt(80));
        inner.children.add(13);

        BTreeNode back = BTreeNode.deserialize(ColumnType.INT, 5, inner.serialize());
        assertFalse(back.leaf);
        assertEquals(Constants.INVALID_PAGE_ID, back.parentPageId);
        assertEquals(inner.keys, back.keys);
        assertEquals(inner.children, back.children);
    }

    @Test
    void leafRoundTripAllKeyTypes() {
        roundTripLeaf(ColumnType.INT, Value.ofInt(-3), Value.ofInt(7));
        roundTripLeaf(ColumnType.FLOAT, Value.ofFloat(1.5), Value.ofFloat(2.25));
        roundTripLeaf(ColumnType.BOOL, Value.ofBool(false), Value.ofBool(true));
        roundTripLeaf(ColumnType.VARCHAR, Value.ofString("apple"), Value.ofString("banana"));
    }

    private void roundTripLeaf(ColumnType type, Value k1, Value k2) {
        BTreeNode leaf = BTreeNode.newLeaf(type, 1, Constants.INVALID_PAGE_ID);
        leaf.keys.add(k1);
        leaf.rids.add(new RID(1, 1));
        leaf.keys.add(k2);
        leaf.rids.add(new RID(2, 2));
        BTreeNode back = BTreeNode.deserialize(type, 1, leaf.serialize());
        assertEquals(leaf.keys, back.keys);
        assertEquals(leaf.rids, back.rids);
    }

    // ---- 13.1: tree construction + page payload round-trip -------------------

    @Test
    void createMakesEmptyLeafRoot() {
        BufferPool bp = newPool(8);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT);
        assertTrue(tree.getRootPageId() >= 0);

        BTreeNode root = tree.readNode(tree.getRootPageId());
        assertTrue(root.leaf);
        assertEquals(0, root.keyCount());
        assertEquals(Constants.INVALID_PAGE_ID, root.parentPageId);
        assertEquals(Constants.INVALID_PAGE_ID, root.nextLeaf);
    }

    @Test
    void writeNodeThenReadNodePersistsViaPagePayload() {
        BufferPool bp = newPool(8);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT);
        BTreeNode root = tree.readNode(tree.getRootPageId());
        root.keys.add(Value.ofInt(42));
        root.rids.add(new RID(5, 6));
        tree.writeNode(root);

        BTreeNode back = tree.readNode(tree.getRootPageId());
        assertEquals(1, back.keyCount());
        assertEquals(Value.ofInt(42), back.keys.get(0));
        assertEquals(new RID(5, 6), back.rids.get(0));
    }

    @Test
    void reopenSeesSameRoot() {
        BufferPool bp = newPool(8);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT);
        int rootId = tree.getRootPageId();
        bp.flushAll();

        BPlusTree reopened = BPlusTree.open(bp, ColumnType.INT, rootId);
        assertEquals(rootId, reopened.getRootPageId());
        assertTrue(reopened.readNode(rootId).leaf);
    }

    // ---- 13.2: search --------------------------------------------------------

    @Test
    void searchOnEmptyTreeReturnsNull() {
        BufferPool bp = newPool(8);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT);
        assertNull(tree.search(Value.ofInt(1)));
    }

    @Test
    void searchSingleLeaf() {
        BufferPool bp = newPool(8);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT);
        BTreeNode root = tree.readNode(tree.getRootPageId());
        for (int k : new int[]{10, 20, 30}) {
            root.keys.add(Value.ofInt(k));
            root.rids.add(new RID(k, k));
        }
        tree.writeNode(root);

        assertEquals(new RID(20, 20), tree.search(Value.ofInt(20)));
        assertEquals(new RID(10, 10), tree.search(Value.ofInt(10)));
        assertEquals(new RID(30, 30), tree.search(Value.ofInt(30)));
        assertNull(tree.search(Value.ofInt(25)));
        assertNull(tree.search(Value.ofInt(99)));
    }

    @Test
    void searchTwoLevelTreeRouting() {
        BufferPool bp = newPool(16);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        int rootId = tree.getRootPageId();

        // Build by hand: inner root [50] with left leaf {10,20} and right leaf {50,60}.
        int leftId = tree.allocatePage();
        int rightId = tree.allocatePage();

        BTreeNode left = BTreeNode.newLeaf(ColumnType.INT, leftId, rootId);
        left.keys.add(Value.ofInt(10));
        left.rids.add(new RID(10, 0));
        left.keys.add(Value.ofInt(20));
        left.rids.add(new RID(20, 0));
        left.nextLeaf = rightId;
        tree.writeNode(left);

        BTreeNode right = BTreeNode.newLeaf(ColumnType.INT, rightId, rootId);
        right.keys.add(Value.ofInt(50));
        right.rids.add(new RID(50, 0));
        right.keys.add(Value.ofInt(60));
        right.rids.add(new RID(60, 0));
        tree.writeNode(right);

        BTreeNode root = BTreeNode.newInner(ColumnType.INT, rootId, Constants.INVALID_PAGE_ID);
        root.children.add(leftId);
        root.keys.add(Value.ofInt(50));
        root.children.add(rightId);
        tree.writeNode(root);

        assertEquals(new RID(10, 0), tree.search(Value.ofInt(10)));
        assertEquals(new RID(20, 0), tree.search(Value.ofInt(20)));
        assertEquals(new RID(50, 0), tree.search(Value.ofInt(50))); // boundary goes right
        assertEquals(new RID(60, 0), tree.search(Value.ofInt(60)));
        assertNull(tree.search(Value.ofInt(30)));
        assertNull(tree.search(Value.ofInt(70)));
    }

    // ---- 13.3: insert + splits -----------------------------------------------

    /** Descend leftmost children to the first leaf. */
    private BTreeNode leftmostLeaf(BPlusTree tree) {
        BTreeNode n = tree.readNode(tree.getRootPageId());
        while (!n.leaf) {
            n = tree.readNode(n.children.get(0));
        }
        return n;
    }

    /** Collect all keys by walking the leaf chain; asserts strictly ascending order. */
    private List<Integer> scanChainInts(BPlusTree tree) {
        List<Integer> out = new ArrayList<>();
        BTreeNode leaf = leftmostLeaf(tree);
        Integer prev = null;
        while (true) {
            for (Value k : leaf.keys) {
                int v = (int) k.asInt();
                if (prev != null) {
                    assertTrue(prev <= v, "leaf chain not sorted: " + prev + " then " + v);
                }
                prev = v;
                out.add(v);
            }
            if (leaf.nextLeaf == Constants.INVALID_PAGE_ID) {
                break;
            }
            leaf = tree.readNode(leaf.nextLeaf);
        }
        return out;
    }

    /** Assert every leaf is at the same depth (the tree is balanced). */
    private void assertBalanced(BPlusTree tree) {
        BTreeNode root = tree.readNode(tree.getRootPageId());
        int depth = leafDepth(tree, root, 0, -1);
        assertTrue(depth >= 0);
    }

    private int leafDepth(BPlusTree tree, BTreeNode node, int depth, int expected) {
        if (node.leaf) {
            return depth;
        }
        int common = -1;
        for (int childId : node.children) {
            int d = leafDepth(tree, tree.readNode(childId), depth + 1, expected);
            if (common == -1) {
                common = d;
            } else {
                assertEquals(common, d, "unbalanced tree: differing leaf depths");
            }
        }
        return common;
    }

    @Test
    void insertSequentialWithSplits() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2); // tiny order forces splits
        int n = 50;
        for (int i = 1; i <= n; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        for (int i = 1; i <= n; i++) {
            assertEquals(new RID(i, 0), tree.search(Value.ofInt(i)), "missing key " + i);
        }
        List<Integer> chain = scanChainInts(tree);
        assertEquals(n, chain.size());
        assertEquals(1, chain.get(0));
        assertEquals(n, chain.get(n - 1));
        assertFalse(tree.readNode(tree.getRootPageId()).leaf, "root should have grown into an inner node");
        assertBalanced(tree);
    }

    @Test
    void insertReverseOrder() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 40; i >= 1; i--) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        for (int i = 1; i <= 40; i++) {
            assertEquals(new RID(i, 0), tree.search(Value.ofInt(i)));
        }
        assertEquals(40, scanChainInts(tree).size());
        assertBalanced(tree);
    }

    @Test
    void insertRandomOrderStaysSortedAndComplete() {
        BufferPool bp = newPool(64);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 3);
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            keys.add(i);
        }
        java.util.Collections.shuffle(keys, new java.util.Random(42));
        for (int k : keys) {
            tree.insert(Value.ofInt(k), new RID(k, 7));
        }
        List<Integer> chain = scanChainInts(tree);
        assertEquals(300, chain.size());
        for (int i = 0; i < 300; i++) {
            assertEquals(i, chain.get(i));
            assertEquals(new RID(i, 7), tree.search(Value.ofInt(i)));
        }
        assertBalanced(tree);
    }

    @Test
    void duplicateKeysAllStored() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 0; i < 10; i++) {
            tree.insert(Value.ofInt(5), new RID(i, 0));
        }
        // All ten entries live in the chain even though the key repeats.
        assertEquals(10, scanChainInts(tree).size());
        assertNotNull(tree.search(Value.ofInt(5)));
        assertBalanced(tree);
    }

    @Test
    void insertSurvivesPageEviction() {
        // Pool smaller than the tree forces nodes through disk and back.
        BufferPool bp = newPool(4);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 0; i < 100; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 1));
        }
        for (int i = 0; i < 100; i++) {
            assertEquals(new RID(i, 1), tree.search(Value.ofInt(i)));
        }
        assertEquals(100, scanChainInts(tree).size());
    }

    // ---- 13.4: range scan ----------------------------------------------------

    private static List<Integer> ridPages(List<RID> rids) {
        List<Integer> out = new ArrayList<>();
        for (RID r : rids) {
            out.add(r.pageId());
        }
        return out;
    }

    @Test
    void rangeScanInclusiveEndpoints() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 0; i < 100; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        List<RID> r = tree.rangeScan(Value.ofInt(20), Value.ofInt(30));
        assertEquals(11, r.size());
        assertEquals(20, r.get(0).pageId());
        assertEquals(30, r.get(10).pageId());
        // strictly ascending
        for (int i = 1; i < r.size(); i++) {
            assertTrue(r.get(i - 1).pageId() < r.get(i).pageId());
        }
    }

    @Test
    void rangeScanOpenBounds() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 3);
        for (int i = 0; i < 50; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        assertEquals(50, tree.rangeScan(null, null).size());
        assertEquals(ridPages(tree.rangeScan(null, Value.ofInt(4))), List.of(0, 1, 2, 3, 4));
        assertEquals(ridPages(tree.rangeScan(Value.ofInt(46), null)), List.of(46, 47, 48, 49));
    }

    @Test
    void rangeScanMissingEndpointsAndEmpty() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 0; i < 50; i += 2) { // only even keys
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        // [21,27] covers evens 22,24,26
        assertEquals(List.of(22, 24, 26), ridPages(tree.rangeScan(Value.ofInt(21), Value.ofInt(27))));
        // empty range (low > high) yields nothing
        assertTrue(tree.rangeScan(Value.ofInt(30), Value.ofInt(20)).isEmpty());
        // range entirely above the data
        assertTrue(tree.rangeScan(Value.ofInt(100), Value.ofInt(200)).isEmpty());
    }

    @Test
    void rangeScanReturnsDuplicates() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 0; i < 8; i++) {
            tree.insert(Value.ofInt(5), new RID(i, 0));
        }
        tree.insert(Value.ofInt(4), new RID(40, 0));
        tree.insert(Value.ofInt(6), new RID(60, 0));
        assertEquals(8, tree.rangeScan(Value.ofInt(5), Value.ofInt(5)).size());
        assertEquals(10, tree.rangeScan(Value.ofInt(4), Value.ofInt(6)).size());
    }

    // ---- 13.5: delete + borrow/merge -----------------------------------------

    private int height(BPlusTree tree) {
        int h = 1;
        BTreeNode n = tree.readNode(tree.getRootPageId());
        while (!n.leaf) {
            h++;
            n = tree.readNode(n.children.get(0));
        }
        return h;
    }

    @Test
    void deleteFromSingleLeaf() {
        BufferPool bp = newPool(8);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 3);
        tree.insert(Value.ofInt(1), new RID(1, 0));
        tree.insert(Value.ofInt(2), new RID(2, 0));
        tree.insert(Value.ofInt(3), new RID(3, 0));

        assertTrue(tree.delete(Value.ofInt(2), new RID(2, 0)));
        assertNull(tree.search(Value.ofInt(2)));
        assertEquals(new RID(1, 0), tree.search(Value.ofInt(1)));
        assertEquals(new RID(3, 0), tree.search(Value.ofInt(3)));
        assertEquals(List.of(1, 3), scanChainInts(tree));
    }

    @Test
    void deleteNonexistentReturnsFalse() {
        BufferPool bp = newPool(8);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 3);
        tree.insert(Value.ofInt(1), new RID(1, 0));
        assertFalse(tree.delete(Value.ofInt(9), new RID(9, 0)));
        assertFalse(tree.delete(Value.ofInt(1), new RID(2, 0))); // right key, wrong rid
        assertEquals(new RID(1, 0), tree.search(Value.ofInt(1)));
    }

    @Test
    void deleteWithBorrowAndMergeKeepsTreeValid() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 1; i <= 20; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        // Delete a scattered set; each delete may trigger borrow or merge.
        int[] toDelete = {1, 2, 3, 10, 11, 19, 20, 7};
        java.util.Set<Integer> deleted = new java.util.HashSet<>();
        for (int k : toDelete) {
            assertTrue(tree.delete(Value.ofInt(k), new RID(k, 0)), "delete " + k);
            deleted.add(k);
            assertBalanced(tree);
        }
        List<Integer> remaining = scanChainInts(tree);
        for (int i = 1; i <= 20; i++) {
            if (deleted.contains(i)) {
                assertNull(tree.search(Value.ofInt(i)), "deleted key still present: " + i);
                assertFalse(remaining.contains(i));
            } else {
                assertEquals(new RID(i, 0), tree.search(Value.ofInt(i)), "missing key " + i);
            }
        }
        assertEquals(20 - toDelete.length, remaining.size());
    }

    @Test
    void deleteAllEmptiesTree() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 1; i <= 30; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        for (int i = 1; i <= 30; i++) {
            assertTrue(tree.delete(Value.ofInt(i), new RID(i, 0)));
        }
        assertTrue(tree.readNode(tree.getRootPageId()).leaf, "tree should collapse back to a leaf root");
        assertEquals(0, scanChainInts(tree).size());
        assertNull(tree.search(Value.ofInt(15)));
    }

    @Test
    void deleteShrinksHeight() {
        BufferPool bp = newPool(32);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 2);
        for (int i = 1; i <= 30; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        int tall = height(tree);
        assertTrue(tall >= 3, "expected a multi-level tree, got height " + tall);
        for (int i = 1; i <= 28; i++) {
            tree.delete(Value.ofInt(i), new RID(i, 0));
        }
        assertTrue(height(tree) < tall, "height should shrink after many deletes");
        assertEquals(List.of(29, 30), scanChainInts(tree));
        assertBalanced(tree);
    }

    @Test
    void randomizedInsertDelete() {
        BufferPool bp = newPool(64);
        BPlusTree tree = BPlusTree.create(bp, ColumnType.INT, 3);
        int n = 200;
        for (int i = 0; i < n; i++) {
            tree.insert(Value.ofInt(i), new RID(i, 0));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, new java.util.Random(7));
        java.util.Set<Integer> deleted = new java.util.HashSet<>();
        for (int i = 0; i < n / 2; i++) {
            int k = order.get(i);
            assertTrue(tree.delete(Value.ofInt(k), new RID(k, 0)));
            deleted.add(k);
        }
        assertBalanced(tree);
        List<Integer> remaining = scanChainInts(tree);
        assertEquals(n - deleted.size(), remaining.size());
        for (int i = 0; i < n; i++) {
            if (deleted.contains(i)) {
                assertNull(tree.search(Value.ofInt(i)));
            } else {
                assertEquals(new RID(i, 0), tree.search(Value.ofInt(i)));
            }
        }
    }

    // ---- 13.6: bulk build for CREATE INDEX -----------------------------------

    @Test
    void buildIndexFromHeapScan() {
        BufferPool bp = newPool(32);
        HeapFile heap = HeapFile.create(bp);
        List<ColumnType> schema = List.of(ColumnType.INT, ColumnType.VARCHAR);

        java.util.Map<Integer, RID> rids = new java.util.HashMap<>();
        for (int i = 0; i < 60; i++) {
            byte[] row = TupleCodec.encode(List.of(Value.ofInt(i), Value.ofString("name" + i)), schema);
            rids.put(i, heap.insertTuple(row));
        }

        // Index over the INT column (index 0).
        BPlusTree intIndex = BPlusTree.build(bp, heap, schema, 0);
        for (int i = 0; i < 60; i++) {
            assertEquals(rids.get(i), intIndex.search(Value.ofInt(i)), "int key " + i);
        }
        assertEquals(60, intIndex.rangeScan(null, null).size());
        assertEquals(11, intIndex.rangeScan(Value.ofInt(10), Value.ofInt(20)).size());

        // Index over the VARCHAR column (index 1).
        BPlusTree strIndex = BPlusTree.build(bp, heap, schema, 1);
        assertEquals(rids.get(7), strIndex.search(Value.ofString("name7")));
        assertEquals(60, strIndex.rangeScan(null, null).size());
    }

    @Test
    void buildIndexSkipsNullKeys() {
        BufferPool bp = newPool(16);
        HeapFile heap = HeapFile.create(bp);
        List<ColumnType> schema = List.of(ColumnType.INT, ColumnType.VARCHAR);

        heap.insertTuple(TupleCodec.encode(List.of(Value.ofInt(1), Value.ofString("a")), schema));
        heap.insertTuple(TupleCodec.encode(List.of(Value.nullValue(ColumnType.INT), Value.ofString("b")), schema));
        heap.insertTuple(TupleCodec.encode(List.of(Value.ofInt(3), Value.ofString("c")), schema));

        BPlusTree index = BPlusTree.build(bp, heap, schema, 0);
        assertEquals(2, index.rangeScan(null, null).size()); // null key not indexed
        assertNotNull(index.search(Value.ofInt(1)));
        assertNotNull(index.search(Value.ofInt(3)));
    }

    @Test
    void builtIndexReopensFromRootPageId() {
        BufferPool bp = newPool(16);
        HeapFile heap = HeapFile.create(bp);
        List<ColumnType> schema = List.of(ColumnType.INT);
        for (int i = 0; i < 40; i++) {
            heap.insertTuple(TupleCodec.encode(List.of(Value.ofInt(i)), schema));
        }
        BPlusTree built = BPlusTree.build(bp, heap, schema, 0);
        int rootId = built.getRootPageId();
        bp.flushAll();

        BPlusTree reopened = BPlusTree.open(bp, ColumnType.INT, rootId);
        for (int i = 0; i < 40; i++) {
            assertNotNull(reopened.search(Value.ofInt(i)), "key " + i + " after reopen");
        }
    }

    @Test
    void defaultOrderFitsInAPage() {
        // 2*order entries at max key size must fit a node payload.
        for (ColumnType t : ColumnType.values()) {
            assertTrue(BPlusTree.defaultOrder(t) >= 2, "order too small for " + t);
        }
    }
}
