package com.minidb.query;

import com.minidb.shared.ColumnType;
import com.minidb.shared.Constants;
import com.minidb.shared.RID;
import com.minidb.shared.Value;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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

    @Test
    void defaultOrderFitsInAPage() {
        // 2*order entries at max key size must fit a node payload.
        for (ColumnType t : ColumnType.values()) {
            assertTrue(BPlusTree.defaultOrder(t) >= 2, "order too small for " + t);
        }
    }
}
