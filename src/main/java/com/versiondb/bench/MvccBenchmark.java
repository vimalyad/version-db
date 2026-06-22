package com.versiondb.bench;

import com.versiondb.query.exec.ResultSet;
import com.versiondb.shared.TransactionConflictException;
import com.versiondb.tools.VersionDb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Benchmark harness for the MVCC extension track (Track&nbsp;B). It quantifies
 * the three claims the track asks us to demonstrate:
 *
 * <ol>
 *   <li><b>Higher read throughput</b> and <b>reduced blocking under
 *       contention</b> — scenario&nbsp;1 runs concurrent readers alongside
 *       writers and compares VersionDB's native MVCC against the <em>same</em>
 *       engine with a coarse table-level reader/writer lock layered on top to
 *       emulate lock-based concurrency control (the worst case of strict 2PL
 *       with a single table lock). MVCC readers never take a lock, so they do
 *       not block on writers; the lock baseline serializes every writer against
 *       every reader.</li>
 *   <li><b>Concurrent transaction behaviour</b> — scenario&nbsp;1 also reports
 *       writer throughput and the count of first-writer-wins conflicts.</li>
 *   <li><b>Version-management cost</b> — scenario&nbsp;2 measures point-read
 *       latency as a function of version-chain depth, the price MVCC pays for
 *       keeping old versions live for old snapshots.</li>
 * </ol>
 *
 * <p>The lock baseline is deliberately the same MVCC engine plus an external
 * {@link ReadWriteLock}: it isolates the effect of <em>blocking</em> from every
 * other implementation difference, so the read-throughput delta is attributable
 * to concurrency control alone rather than to two different storage engines.
 *
 * <p>Run with:
 * <pre>{@code
 *   mvn -q compile
 *   java -cp target/classes com.versiondb.bench.MvccBenchmark
 *   # optional: java ... MvccBenchmark <durationMillis> <readers> <writers> <rows>
 * }</pre>
 *
 * <p>Results print as Markdown tables on standard output, ready to paste into
 * the benchmark report.
 */
public final class MvccBenchmark {

    /** One row of throughput results for a single concurrency-control mode. */
    private record ThroughputResult(String mode, long reads, long writes,
                                    long conflicts, double seconds) {
        double readsPerSec() {
            return reads / seconds;
        }

        double writesPerSec() {
            return writes / seconds;
        }
    }

    private final long durationMillis;
    private final int readers;
    private final int writers;
    private final int rows;

    public MvccBenchmark(long durationMillis, int readers, int writers, int rows) {
        this.durationMillis = durationMillis;
        this.readers = readers;
        this.writers = writers;
        this.rows = rows;
    }

    public static void main(String[] args) throws Exception {
        long duration = args.length > 0 ? Long.parseLong(args[0]) : 3_000L;
        int readers = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int writers = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        int rows = args.length > 3 ? Integer.parseInt(args[3]) : 1_000;

        MvccBenchmark bench = new MvccBenchmark(duration, readers, writers, rows);
        bench.printHeader();
        bench.runContentionScenario();
        bench.runVersionChainScenario();
    }

    private void printHeader() {
        System.out.println("# VersionDB — MVCC Benchmark Results");
        System.out.println();
        System.out.printf("Runtime: %s, Java %s, %d CPUs%n",
                System.getProperty("os.name"),
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("Workload: %d reader thread(s), %d writer thread(s), %d rows, %.1fs measured per mode%n",
                readers, writers, rows, durationMillis / 1000.0);
        System.out.println();
    }

    // =========================================================================
    // Scenario 1 — read throughput / blocking under write contention
    // =========================================================================

    private void runContentionScenario() throws Exception {
        System.out.println("## Scenario 1 — Read throughput under write contention");
        System.out.println();
        System.out.println("Readers run point SELECTs; writers run point UPDATEs on disjoint key");
        System.out.println("ranges (so writes never conflict with each other). The two modes differ");
        System.out.println("only in concurrency control.");
        System.out.println();

        // Warm up the JIT and the buffer pool before the timed runs.
        ThroughputResult warm = measureContention(true);
        ThroughputResult mvcc = measureContention(false);
        ThroughputResult lock = measureContentionLocked();

        System.out.println("| Mode | Reads/sec | Writes/sec | Read speedup | Write conflicts |");
        System.out.println("|---|---:|---:|---:|---:|");
        System.out.printf("| MVCC (snapshot reads, no read locks) | %,.0f | %,.0f | %.2f× | %d |%n",
                mvcc.readsPerSec(), mvcc.writesPerSec(), mvcc.readsPerSec() / lock.readsPerSec(), mvcc.conflicts());
        System.out.printf("| Lock-based (table R/W lock, ~2PL) | %,.0f | %,.0f | 1.00× | %d |%n",
                lock.readsPerSec(), lock.writesPerSec(), lock.conflicts());
        System.out.println();
        System.out.printf("> MVCC delivered **%.2f×** the read throughput of the lock-based baseline "
                        + "under %d concurrent writer(s); readers never blocked. (warmup: %,.0f reads/s)%n%n",
                mvcc.readsPerSec() / lock.readsPerSec(), writers, warm.readsPerSec());
    }

    /** Native MVCC: every statement is an independent autocommit transaction. */
    private ThroughputResult measureContention(boolean warmup) throws Exception {
        Path dir = Files.createTempDirectory("vdb-bench-mvcc");
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            seed(db);
            return drive(db, null, warmup ? "WARMUP" : "MVCC", warmup);
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * Lock-based baseline: the same engine, but reads take a shared lock and
     * writes take the exclusive lock, so any active writer blocks all readers.
     */
    private ThroughputResult measureContentionLocked() throws Exception {
        Path dir = Files.createTempDirectory("vdb-bench-lock");
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            seed(db);
            return drive(db, new ReentrantReadWriteLock(), "LOCK", false);
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * Drive {@code readers} reader threads and {@code writers} writer threads
     * against {@code db} for {@link #durationMillis}. If {@code lock} is non-null
     * each read holds the shared lock and each write the exclusive lock.
     */
    private ThroughputResult drive(VersionDb db, ReadWriteLock lock, String mode, boolean warmup) throws Exception {
        int threads = readers + writers;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong readOps = new AtomicLong();
        AtomicLong writeOps = new AtomicLong();
        AtomicLong conflicts = new AtomicLong();
        running.set(true);

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                ready.countDown();
                await(go);
                while (running.get()) {
                    int key = ThreadLocalRandom.current().nextInt(rows);
                    if (lock != null) lock.readLock().lock();
                    try {
                        ResultSet rs = db.execute("SELECT v FROM bench WHERE id = " + key);
                        rs.size();
                    } finally {
                        if (lock != null) lock.readLock().unlock();
                    }
                    readOps.incrementAndGet();
                }
            });
        }
        for (int w = 0; w < writers; w++) {
            final int writerId = w;
            pool.submit(() -> {
                ready.countDown();
                await(go);
                while (running.get()) {
                    // Each writer owns a disjoint key range → no write-write conflicts.
                    int span = Math.max(1, rows / Math.max(1, writers));
                    int key = writerId * span + ThreadLocalRandom.current().nextInt(span);
                    if (key >= rows) key = rows - 1;
                    if (lock != null) lock.writeLock().lock();
                    try {
                        db.execute("UPDATE bench SET v = v + 1 WHERE id = " + key);
                        writeOps.incrementAndGet();
                    } catch (TransactionConflictException e) {
                        conflicts.incrementAndGet();
                    } finally {
                        if (lock != null) lock.writeLock().unlock();
                    }
                }
            });
        }

        ready.await();
        long start = System.nanoTime();
        go.countDown();
        Thread.sleep(durationMillis);
        running.set(false);
        pool.shutdown();
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("benchmark workers did not finish");
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        return new ThroughputResult(mode, readOps.get(), writeOps.get(), conflicts.get(), seconds);
    }

    /** Shared stop flag for the worker threads (runs execute one at a time). */
    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    // =========================================================================
    // Scenario 2 — read latency vs version-chain depth
    // =========================================================================

    private void runVersionChainScenario() throws Exception {
        System.out.println("## Scenario 2 — Point-read latency vs. version-chain depth");
        System.out.println();
        System.out.println("A single row is updated N times (N committed versions), then read by a");
        System.out.println("transaction whose snapshot predates all of them — forcing a full walk to");
        System.out.println("the oldest visible version. This is MVCC's version-traversal cost.");
        System.out.println();
        System.out.println("| Chain depth (versions) | Median read latency (µs) |");
        System.out.println("|---:|---:|");

        for (int depth : new int[]{1, 8, 64, 256, 1024}) {
            double medianMicros = measureChainReadLatency(depth);
            System.out.printf("| %d | %.2f |%n", depth, medianMicros);
        }
        System.out.println();
    }

    private double measureChainReadLatency(int depth) throws Exception {
        Path dir = Files.createTempDirectory("vdb-bench-chain");
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE chain (id INT, v INT)");
            db.execute("INSERT INTO chain VALUES (1, 0)");

            // Open the old-snapshot reader BEFORE the updates so it must walk to
            // the base version every time.
            var reader = db.begin();
            db.execute("SELECT v FROM chain WHERE id = 1", reader); // materialize snapshot

            for (int i = 1; i < depth; i++) {
                db.execute("UPDATE chain SET v = " + i + " WHERE id = 1");
            }

            int warm = 200;
            for (int i = 0; i < warm; i++) {
                db.execute("SELECT v FROM chain WHERE id = 1", reader).size();
            }

            int samples = 2_000;
            long[] times = new long[samples];
            for (int i = 0; i < samples; i++) {
                long t0 = System.nanoTime();
                db.execute("SELECT v FROM chain WHERE id = 1", reader).size();
                times[i] = System.nanoTime() - t0;
            }
            db.commit(reader);

            List<Long> sorted = new ArrayList<>(samples);
            for (long t : times) sorted.add(t);
            sorted.sort(Comparator.naturalOrder());
            return sorted.get(samples / 2) / 1_000.0;
        } finally {
            deleteTree(dir);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void seed(VersionDb db) {
        db.execute("CREATE TABLE bench (id INT, v INT)");
        StringBuilder sb = new StringBuilder("INSERT INTO bench VALUES ");
        for (int i = 0; i < rows; i++) {
            if (i > 0) sb.append(',');
            sb.append('(').append(i).append(", 0)");
        }
        db.execute(sb.toString());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of the temp directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
