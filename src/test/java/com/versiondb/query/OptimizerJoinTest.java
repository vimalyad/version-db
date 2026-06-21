package com.versiondb.query;

import com.versiondb.query.plan.HashJoinPlan;
import com.versiondb.query.plan.IndexScanPlan;
import com.versiondb.query.plan.NestedLoopJoinPlan;
import com.versiondb.query.plan.PhysicalPlan;
import com.versiondb.query.plan.SeqScanPlan;
import com.versiondb.shared.ColumnDef;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.TableMeta;
import com.versiondb.shared.TableStats;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.Catalog;
import com.versiondb.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OptimizerJoinTest {

    @TempDir
    Path tmp;

    private DiskManager dm;
    private Optimizer optimizer;

    @BeforeEach
    void setUp() {
        dm = new DiskManager(tmp.resolve("optjoin.db"));
        BufferPool bp = new BufferPool(64, dm, lsn -> { });
        Catalog catalog = new Catalog(bp, dm);

        TableMeta a = catalog.createTable("a", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("age", ColumnType.INT, false)));
        TableMeta b = catalog.createTable("b", List.of(
                new ColumnDef("aid", ColumnType.INT, false),
                new ColumnDef("val", ColumnType.INT, false)));
        TableMeta c = catalog.createTable("c", List.of(
                new ColumnDef("bval", ColumnType.INT, false)));

        catalog.updateStats(a.tableId(), new TableStats(10, 2, Map.of()));
        catalog.updateStats(b.tableId(), new TableStats(1000, 40, Map.of()));
        catalog.updateStats(c.tableId(), new TableStats(50, 5, Map.of()));
        optimizer = new Optimizer(catalog);
    }

    @AfterEach
    void tearDown() {
        dm.close();
    }

    private PhysicalPlan plan(String sql) {
        return optimizer.optimize(Parser.parse(sql));
    }

    private static String tableOf(PhysicalPlan p) {
        if (p instanceof SeqScanPlan s) {
            return s.tableName();
        }
        if (p instanceof IndexScanPlan i) {
            return i.tableName();
        }
        return null;
    }

    @Test
    void equiJoinChoosesHashJoin() {
        PhysicalPlan p = plan("SELECT * FROM a JOIN b ON a.id = b.aid");
        assertInstanceOf(HashJoinPlan.class, p);
    }

    @Test
    void joinOrderPrefersSmallerOuterForNestedLoop() {
        // Non-equi condition forces a nested-loop join; the smaller table (a) should be the outer.
        PhysicalPlan p = plan("SELECT * FROM a JOIN b ON a.id > b.aid");
        NestedLoopJoinPlan nlj = assertInstanceOf(NestedLoopJoinPlan.class, p);
        assertEquals("a", tableOf(nlj.outer()), "smaller table should drive the outer loop");
        assertEquals("b", tableOf(nlj.inner()));
    }

    @Test
    void singleTablePredicateIsPushedIntoItsScan() {
        PhysicalPlan p = plan("SELECT * FROM a JOIN b ON a.id = b.aid WHERE b.val = 7");
        HashJoinPlan hj = assertInstanceOf(HashJoinPlan.class, p);
        // b carries the pushed predicate; find b's scan among the inputs.
        PhysicalPlan bScan = "b".equals(tableOf(hj.outer())) ? hj.outer() : hj.inner();
        assertEquals("b", tableOf(bScan));
        assertInstanceOf(SeqScanPlan.class, bScan);
        assertNotNull(((SeqScanPlan) bScan).predicate());
    }

    @Test
    void threeTableJoinIsLeftDeep() {
        PhysicalPlan p = plan(
                "SELECT * FROM a JOIN b ON a.id = b.aid JOIN c ON b.val = c.bval");
        // Top join's outer is itself a join (left-deep), inner is a base scan.
        if (p instanceof HashJoinPlan hj) {
            assertTrue(hj.outer() instanceof HashJoinPlan || hj.outer() instanceof NestedLoopJoinPlan,
                    "left-deep: outer of the top join is another join");
            assertNotNull(tableOf(hj.inner()));
        } else {
            NestedLoopJoinPlan nlj = assertInstanceOf(NestedLoopJoinPlan.class, p);
            assertTrue(nlj.outer() instanceof HashJoinPlan || nlj.outer() instanceof NestedLoopJoinPlan);
        }
    }

    @Test
    void crossJoinHasNoCondition() {
        PhysicalPlan p = plan("SELECT * FROM a CROSS JOIN b");
        NestedLoopJoinPlan nlj = assertInstanceOf(NestedLoopJoinPlan.class, p);
        assertNull(nlj.joinCondition());
    }
}
