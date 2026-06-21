package com.versiondb.query;

import com.versiondb.query.plan.IndexScanPlan;
import com.versiondb.query.plan.LimitPlan;
import com.versiondb.query.plan.PhysicalPlan;
import com.versiondb.query.plan.ProjectionPlan;
import com.versiondb.query.plan.SeqScanPlan;
import com.versiondb.query.plan.SortPlan;
import com.versiondb.shared.ColumnDef;
import com.versiondb.shared.ColumnStats;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.TableMeta;
import com.versiondb.shared.TableStats;
import com.versiondb.shared.Value;
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

class OptimizerTest {

    @TempDir
    Path tmp;

    private DiskManager dm;
    private Catalog catalog;
    private Optimizer optimizer;

    @BeforeEach
    void setUp() {
        dm = new DiskManager(tmp.resolve("opt.db"));
        BufferPool bp = new BufferPool(64, dm, lsn -> { });
        catalog = new Catalog(bp, dm);
        TableMeta users = catalog.createTable("users", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("age", ColumnType.INT, false),
                new ColumnDef("name", ColumnType.VARCHAR, true)));
        catalog.registerIndex(users.tableId(), "age", 5); // dummy root page id
        catalog.updateStats(users.tableId(), new TableStats(10_000, 100, Map.of(
                "id", new ColumnStats(10_000, Value.ofInt(1), Value.ofInt(10_000), 0),
                "age", new ColumnStats(10_000, Value.ofInt(0), Value.ofInt(10_000), 0))));
        optimizer = new Optimizer(catalog);
    }

    @AfterEach
    void tearDown() {
        dm.close();
    }

    private PhysicalPlan plan(String sql) {
        return optimizer.optimize(Parser.parse(sql));
    }

    @Test
    void lowSelectivityEqualityPicksIndexScan() {
        PhysicalPlan p = plan("SELECT * FROM users WHERE age = 5");
        IndexScanPlan idx = assertInstanceOf(IndexScanPlan.class, p);
        assertEquals("age", idx.indexColumn());
        assertEquals(Value.ofInt(5), idx.low());
        assertEquals(Value.ofInt(5), idx.high());
        assertNotNull(idx.postFilterPredicate()); // full WHERE re-applied for correctness
    }

    @Test
    void highSelectivityRangePicksSeqScan() {
        // age > 5 matches ~99.95% of rows; sequential scan is cheaper.
        PhysicalPlan p = plan("SELECT * FROM users WHERE age > 5");
        SeqScanPlan seq = assertInstanceOf(SeqScanPlan.class, p);
        assertNotNull(seq.predicate());
    }

    @Test
    void lowSelectivityRangeUsesIndexWithOpenBound() {
        PhysicalPlan p = plan("SELECT * FROM users WHERE age < 5");
        IndexScanPlan idx = assertInstanceOf(IndexScanPlan.class, p);
        assertNull(idx.low());
        assertEquals(Value.ofInt(5), idx.high());
    }

    @Test
    void predicateOnUnindexedColumnPushesIntoSeqScan() {
        PhysicalPlan p = plan("SELECT * FROM users WHERE name = 'bob'");
        SeqScanPlan seq = assertInstanceOf(SeqScanPlan.class, p);
        assertNotNull(seq.predicate()); // pushdown: predicate lives in the scan, no FilterPlan
    }

    @Test
    void selectStarHasNoProjection() {
        assertInstanceOf(SeqScanPlan.class, plan("SELECT * FROM users"));
    }

    @Test
    void projectionWrapsScan() {
        PhysicalPlan p = plan("SELECT name FROM users");
        ProjectionPlan proj = assertInstanceOf(ProjectionPlan.class, p);
        assertInstanceOf(SeqScanPlan.class, proj.child());
    }

    @Test
    void orderByAndLimitStackOnTop() {
        PhysicalPlan p = plan("SELECT * FROM users ORDER BY age LIMIT 10");
        LimitPlan limit = assertInstanceOf(LimitPlan.class, p);
        assertEquals(10, limit.limit());
        assertInstanceOf(SortPlan.class, limit.child());
        assertEquals(10.0, limit.estimatedRows());
    }

    @Test
    void compoundPredicateUsesIndexAndKeepsFullPostFilter() {
        PhysicalPlan p = plan("SELECT * FROM users WHERE age = 5 AND name = 'x'");
        IndexScanPlan idx = assertInstanceOf(IndexScanPlan.class, p);
        assertEquals(Value.ofInt(5), idx.low());
        assertNotNull(idx.postFilterPredicate()); // the AND is re-applied after the fetch
    }

    @Test
    void deleteAndUpdateProduceAccessPlans() {
        assertInstanceOf(IndexScanPlan.class, plan2("DELETE FROM users WHERE age = 5"));
        assertInstanceOf(SeqScanPlan.class, plan2("UPDATE users SET name = 'x' WHERE id = 1"));
    }

    @Test
    void unknownTableThrows() {
        assertThrows(RuntimeException.class, () -> plan("SELECT * FROM ghost"));
    }

    private PhysicalPlan plan2(String sql) {
        return optimizer.optimize(Parser.parse(sql));
    }
}
