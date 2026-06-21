package com.versiondb.query;

/**
 * The optimizer's cost constants and per-operator cost formulas
 * ({@code part2.md} §5.5). Costs are in abstract units; only their relative
 * magnitudes matter for plan comparison. Sequential page reads are cheap,
 * random (seek) reads much dearer — this is what makes an index scan win only
 * when it touches few rows.
 */
public final class CostModel {

    /** Cost to read one page sequentially. */
    public static final double SEQ_PAGE_COST = 1.0;
    /** Cost to read one page by random seek. */
    public static final double RANDOM_PAGE_COST = 4.0;
    /** Cost to process one tuple in memory. */
    public static final double CPU_TUPLE_COST = 0.01;
    /** Cost of one B+Tree key comparison. */
    public static final double CPU_INDEX_COST = 0.005;
    /** Cost to insert one tuple into a hash table when building a hash join. */
    public static final double HASH_BUILD_COST = 0.02;

    private CostModel() {
    }

    /** Sequential scan: read every page, then process the matching tuples. */
    public static double seqScanCost(long numPages, long numTuples, double selectivity) {
        return numPages * SEQ_PAGE_COST
                + numTuples * selectivity * CPU_TUPLE_COST;
    }

    /**
     * Index scan: descend the tree, then do a random heap fetch per matching
     * RID. {@code order} is the B+Tree order (fan-out) used to estimate height.
     */
    public static double indexScanCost(long numTuples, double selectivity, int order) {
        double matchingRows = numTuples * selectivity;
        return treeHeight(numTuples, order) * RANDOM_PAGE_COST
                + matchingRows * RANDOM_PAGE_COST
                + matchingRows * CPU_INDEX_COST;
    }

    /** Nested-loop join: scan the inner input once per outer row. */
    public static double nestedLoopJoinCost(double outerCost, double outerRows, double innerCost) {
        return outerCost + outerRows * innerCost;
    }

    /** Hash join: scan both inputs once, build a hash table on the inner side. */
    public static double hashJoinCost(double outerCost, double innerCost, double innerRows) {
        return outerCost + innerCost + innerRows * HASH_BUILD_COST;
    }

    /** B+Tree height: ceil(log_order(numTuples)), at least 1. */
    static int treeHeight(long numTuples, int order) {
        if (numTuples <= 1 || order < 2) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(Math.log(numTuples) / Math.log(order)));
    }
}
