# VersionDB — Benchmark Report (Extension Track B: MVCC)

This report quantifies the three properties the MVCC track asks us to
demonstrate: **higher read throughput**, **reduced blocking under contention**,
and the **cost of version management**. All numbers are produced by the
reproducible harness `com.versiondb.bench.MvccBenchmark`.

## How to reproduce

```bash
mvn -q compile
java -cp target/classes com.versiondb.bench.MvccBenchmark
# args (optional): <durationMillis> <readers> <writers> <rows>
java -cp target/classes com.versiondb.bench.MvccBenchmark 3000 8 4 1000
```

The harness prints Markdown tables to stdout. Each scenario runs against a
fresh, throwaway database directory.

## Experimental setup

| Item | Value |
|---|---|
| Machine | Windows 11, 16 logical CPUs |
| JVM | Java 25 (HotSpot), default GC |
| Engine | VersionDB, 256-frame buffer pool, background vacuum disabled for determinism |
| Table | `bench(id INT, v INT)`, 1 000 rows, no secondary index (reads are sequential scans) |
| Measured window | 3 s per mode, after a JIT/buffer-pool warm-up run |
| Reader op | `SELECT v FROM bench WHERE id = <random key>` |
| Writer op | `UPDATE bench SET v = v + 1 WHERE id = <key in writer's own range>` |

**Why this design.** Writers operate on **disjoint key ranges**, so there are
no write–write conflicts — this isolates the variable we care about (reader vs.
writer *blocking*) from MVCC's first-writer-wins abort path. The two modes use
the *same* engine; the only difference is concurrency control:

- **MVCC (native):** each statement is an autocommit transaction. Reads take a
  consistent snapshot and acquire **no** locks; writers stamp versions under
  per-RID striped locks.
- **Lock-based baseline (~2PL):** the same engine with a single table-level
  `ReadWriteLock` layered on top — reads take the shared lock, writes the
  exclusive lock. This emulates the worst case of strict two-phase locking with
  one table lock, where any active writer blocks every reader.

## Scenario 1 — Read throughput under write contention

8 reader threads + 4 writer threads, representative run (variance noted below):

| Mode | Reads/sec | Writes/sec | Read speedup | Write conflicts |
|---|---:|---:|---:|---:|
| MVCC (snapshot reads, no read locks) | 126 | 58 | **12.1×** | 0 |
| Lock-based (table R/W lock, ~2PL)    | 10  | 126 | 1.0× | 0 |

Across three runs the MVCC read speedup ranged **6×–14×** (lock-mode read
throughput is small and therefore noisy: 8–19 reads/s; MVCC read throughput was
stable at ~120 reads/s).

### Analysis

- **Reduced blocking / higher read throughput (the headline).** Under MVCC,
  readers never wait for writers — all 12 threads make progress concurrently and
  reads sustain ~120/s. In the lock baseline the four writers continually grab
  the exclusive lock and starve the eight readers down to ~10/s. This is
  precisely the "readers don't block writers, writers don't block readers"
  property MVCC is designed to deliver.
- **Why the lock baseline shows *higher* write throughput.** When readers are
  blocked they stop consuming CPU and stop holding the lock, so the four writers
  run almost unobstructed. Under MVCC the readers compete for the same cores and
  do full table scans, so writers get a smaller CPU share. This is the expected
  trade-off: the lock scheme optimizes the writer at the direct expense of read
  availability; MVCC keeps reads available at a modest cost to write throughput.
  For a read-mostly workload (the common OLTP case) MVCC is the clear win.
- **Absolute throughput is modest by design.** Each statement re-parses,
  re-plans, opens a transaction, writes WAL (writers), and sequentially scans
  1 000 rows (reads, no index). The benchmark deliberately measures the
  end-to-end SQL path, not a micro-benchmark of the visibility check, so the
  *ratio* between modes — not the absolute rate — is the result of interest.

## Scenario 2 — Point-read latency vs. version-chain depth

A single row is updated N times (creating N committed versions), then read by a
transaction whose snapshot predates every update — forcing the visibility walk
to traverse the entire chain to the oldest visible version. Median of 2 000
samples after warm-up:

| Chain depth (versions) | Median read latency (µs) |
|---:|---:|
| 1    | ~3  |
| 8    | ~4  |
| 64   | ~8  |
| 256  | ~19 |
| 1024 | ~75 |

### Analysis

- Latency is flat for short chains (dominated by SQL parse/plan overhead) and
  grows **roughly linearly** once the chain is long, because `getVisibleVersion`
  walks `prevVersionId` links newest-to-oldest until it finds a version visible
  to the snapshot. A 1024-deep chain costs ~75 µs — about 70 ns per version
  hop on top of the fixed ~3 µs base.
- This is the standard MVCC space/time trade-off and the motivation for
  **vacuum**: VersionDB's background `Vacuum` reclaims versions no live snapshot
  can see, keeping chains short in steady state. This benchmark disables vacuum
  on purpose so the worst-case traversal cost is visible; with vacuum enabled,
  chains for hot rows stay short and latency stays near the floor.

## Threats to validity

- The lock baseline is an *emulation* of 2PL (a single table lock on the real
  engine), not a from-scratch lock manager — it is a faithful model of
  coarse-grained locking's blocking behavior, but a fine-grained
  (row-level) 2PL implementation would block less than shown here.
- Single-machine, single-JVM measurements with the default GC; absolute numbers
  will vary by hardware. The harness is deterministic in structure and the
  conclusions (read-throughput ratio, latency-vs-depth trend) reproduce across
  runs.
