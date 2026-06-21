package com.versiondb.query;

import com.versiondb.shared.ColumnType;
import com.versiondb.shared.Constants;
import com.versiondb.shared.RID;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.Value;
import com.versiondb.storage.Page;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory image of one B+Tree node, read from and written back to a single
 * buffer-pool page's raw payload (see {@link Page#writePayload}). A node is
 * either a <b>leaf</b> (holds {@code (key, RID)} entries and a {@code nextLeaf}
 * link forming the leaf chain) or an <b>inner</b> node (holds routing keys and
 * one more child pointer than keys).
 *
 * <p><b>Payload layout</b> (per {@code part2.md} §4.4):
 * <pre>
 * inner: [type=0][numKeys:int][parent:int] [child_0][key_0][child_1]...[child_n]
 * leaf : [type=1][numKeys:int][parent:int][nextLeaf:int] ([key][ridPage][ridSlot])*
 * </pre>
 * Page ids are 4-byte ints here (matching {@link RID} and {@link Page}), not the
 * 8-byte width the prose in the design doc uses illustratively.
 *
 * <p>The node's own page id lives in the {@link Page} header, so it is supplied
 * at {@link #deserialize} time rather than stored in the payload.
 */
final class BTreeNode {

    static final byte TYPE_INNER = 0;
    static final byte TYPE_LEAF = 1;

    final ColumnType keyType;
    boolean leaf;
    int pageId;
    int parentPageId;
    /** Next leaf in the left-to-right chain; {@code INVALID_PAGE_ID} for inner nodes / last leaf. */
    int nextLeaf = Constants.INVALID_PAGE_ID;

    /** Routing/entry keys, kept in ascending order. */
    final List<Value> keys = new ArrayList<>();
    /** Leaf only: RID parallel to {@link #keys}. */
    final List<RID> rids = new ArrayList<>();
    /** Inner only: child page ids; size is {@code keys.size() + 1}. */
    final List<Integer> children = new ArrayList<>();

    private BTreeNode(ColumnType keyType) {
        this.keyType = keyType;
    }

    static BTreeNode newLeaf(ColumnType keyType, int pageId, int parentPageId) {
        BTreeNode n = new BTreeNode(keyType);
        n.leaf = true;
        n.pageId = pageId;
        n.parentPageId = parentPageId;
        n.nextLeaf = Constants.INVALID_PAGE_ID;
        return n;
    }

    static BTreeNode newInner(ColumnType keyType, int pageId, int parentPageId) {
        BTreeNode n = new BTreeNode(keyType);
        n.leaf = false;
        n.pageId = pageId;
        n.parentPageId = parentPageId;
        return n;
    }

    int keyCount() {
        return keys.size();
    }

    // ---- Serialization --------------------------------------------------------

    byte[] serialize() {
        ByteBuffer b = ByteBuffer.allocate(Page.MAX_PAYLOAD_SIZE);
        b.put(leaf ? TYPE_LEAF : TYPE_INNER);
        b.putInt(keys.size());
        b.putInt(parentPageId);
        if (leaf) {
            b.putInt(nextLeaf);
            for (int i = 0; i < keys.size(); i++) {
                writeKey(b, keys.get(i));
                b.putInt(rids.get(i).pageId());
                b.putInt(rids.get(i).slotId());
            }
        } else {
            b.putInt(children.get(0));
            for (int i = 0; i < keys.size(); i++) {
                writeKey(b, keys.get(i));
                b.putInt(children.get(i + 1));
            }
        }
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    static BTreeNode deserialize(ColumnType keyType, int pageId, byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        byte type = b.get();
        int numKeys = b.getInt();
        int parent = b.getInt();
        if (type == TYPE_LEAF) {
            BTreeNode n = newLeaf(keyType, pageId, parent);
            n.nextLeaf = b.getInt();
            for (int i = 0; i < numKeys; i++) {
                Value key = readKey(b, keyType);
                int rp = b.getInt();
                int rs = b.getInt();
                n.keys.add(key);
                n.rids.add(new RID(rp, rs));
            }
            return n;
        } else if (type == TYPE_INNER) {
            BTreeNode n = newInner(keyType, pageId, parent);
            n.children.add(b.getInt());
            for (int i = 0; i < numKeys; i++) {
                n.keys.add(readKey(b, keyType));
                n.children.add(b.getInt());
            }
            return n;
        }
        throw new StorageException("unknown B+Tree node type " + type + " on page " + pageId);
    }

    private void writeKey(ByteBuffer b, Value key) {
        switch (keyType) {
            case INT -> b.putLong(key.asInt());
            case FLOAT -> b.putDouble(key.asFloat());
            case BOOL -> b.put((byte) (key.asBool() ? 1 : 0));
            case VARCHAR -> {
                byte[] s = key.asString().getBytes(StandardCharsets.UTF_8);
                b.putInt(s.length);
                b.put(s);
            }
        }
    }

    private static Value readKey(ByteBuffer b, ColumnType keyType) {
        return switch (keyType) {
            case INT -> Value.ofInt(b.getLong());
            case FLOAT -> Value.ofFloat(b.getDouble());
            case BOOL -> Value.ofBool(b.get() != 0);
            case VARCHAR -> {
                int len = b.getInt();
                byte[] s = new byte[len];
                b.get(s);
                yield Value.ofString(new String(s, StandardCharsets.UTF_8));
            }
        };
    }

    /** Total order over keys of {@code keyType}. */
    static int compare(ColumnType keyType, Value a, Value b) {
        return switch (keyType) {
            case INT -> Long.compare(a.asInt(), b.asInt());
            case FLOAT -> Double.compare(a.asFloat(), b.asFloat());
            case BOOL -> Boolean.compare(a.asBool(), b.asBool());
            case VARCHAR -> a.asString().compareTo(b.asString());
        };
    }
}
