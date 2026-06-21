package com.versiondb.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CostModelTest {

    @Test
    void seqScanCostGrowsWithPagesAndRows() {
        double small = CostModel.seqScanCost(10, 100, 0.5);
        double morePages = CostModel.seqScanCost(20, 100, 0.5);
        double moreRows = CostModel.seqScanCost(10, 1000, 0.5);
        assertTrue(morePages > small);
        assertTrue(moreRows > small);
    }

    @Test
    void indexScanCostGrowsWithSelectivity() {
        double low = CostModel.indexScanCost(10_000, 0.001, 128);
        double high = CostModel.indexScanCost(10_000, 0.5, 128);
        assertTrue(high > low);
    }

    @Test
    void indexBeatsSeqOnlyAtLowSelectivity() {
        long pages = 100;
        long tuples = 10_000;
        int order = 128;
        // Very selective: index scan should win.
        double seqSel = CostModel.seqScanCost(pages, tuples, 0.001);
        double idxSel = CostModel.indexScanCost(tuples, 0.001, order);
        assertTrue(idxSel < seqSel, "index should win at 0.1% selectivity");
        // Not selective: sequential scan should win (random I/O dominates).
        double seqAll = CostModel.seqScanCost(pages, tuples, 0.5);
        double idxAll = CostModel.indexScanCost(tuples, 0.5, order);
        assertTrue(idxAll > seqAll, "seq should win at 50% selectivity");
    }

    @Test
    void treeHeightIsLogarithmic() {
        assertEquals(1, CostModel.treeHeight(1, 100));
        assertEquals(1, CostModel.treeHeight(100, 128));   // < one full level
        assertTrue(CostModel.treeHeight(1_000_000, 128) <= 3);
        assertTrue(CostModel.treeHeight(1_000_000, 4) > CostModel.treeHeight(1_000_000, 128));
    }

    @Test
    void hashJoinBeatsNestedLoopForLargeInner() {
        double outerCost = 100, outerRows = 1000, innerCost = 100, innerRows = 1000;
        double nlj = CostModel.nestedLoopJoinCost(outerCost, outerRows, innerCost);
        double hash = CostModel.hashJoinCost(outerCost, innerCost, innerRows);
        assertTrue(hash < nlj, "hash join should win when the inner is rescanned many times");
    }
}
