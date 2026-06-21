package com.versiondb.shared;

/**
 * Record IDentifier: the permanent physical address of a tuple, expressed as a
 * {@code (pageId, slotId)} pair. RIDs are produced by the heap file, stored in
 * B+Tree leaves, and referenced by MVCC version chains. They are opaque and
 * stable: a tuple keeps its RID for life.
 */
public record RID(int pageId, int slotId) {

    /** A RID that points to nothing. */
    public static final RID INVALID = new RID(Constants.INVALID_PAGE_ID, Constants.INVALID_SLOT_ID);

    /** @return true if this RID addresses a real page and slot. */
    public boolean isValid() {
        return pageId != Constants.INVALID_PAGE_ID && slotId != Constants.INVALID_SLOT_ID;
    }

    @Override
    public String toString() {
        return "RID(" + pageId + "," + slotId + ")";
    }
}
