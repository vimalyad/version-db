# VersionDB

A single-node relational database engine built from scratch in **Java 17**. VersionDB
implements the core of a real database — a disk-backed storage engine, write-ahead
logging with ARIES crash recovery, PostgreSQL-style multi-version concurrency
control (MVCC), a SQL parser, a B+Tree index, a cost-based optimizer, and a
Volcano-model execution engine — and exposes it through both an embeddable API and
an interactive SQL shell.

> **Advanced DBMS Capstone — MiniDB.** Extension track: **Track B — Concurrency
> (MVCC)**.

## 1. Project Overview

**Problem statement.** Build a working relational database engine from foundational
components and integrate them into one coherent system: storage, indexing, query
processing, transactions, concurrency control, and recovery.

**Goals.**
- Correct end-to-end SQL execution (`CREATE`, `INSERT`, `SELECT` with `WHERE`/`JOIN`,
  `UPDATE`, `DELETE`) over a disk-backed, crash-safe store.
- A clean layered architecture where each component sits behind a clear interface.
- Demonstrable database internals — page allocation, buffer-pool eviction, index
  utilisation, cost-based plan choice, snapshot isolation, and ARIES recovery.

**Chosen extension track — Track B (Concurrency / MVCC).** We replace lock-based
two-phase locking with **Multi-Version Concurrency Control** under **Snapshot
Isolation**: readers never block writers, each transaction reads a consistent
point-in-time snapshot, and conflicting writers are resolved first-writer-wins.

## 2. System Architecture

VersionDB is organised in layers, each behind a clear interface:

| Package | Responsibility |
|---|---|
| `com.versiondb.shared` | Shared value types: `RID`, `Value`, `ColumnType`, metadata records, exceptions. |
| `com.versiondb.storage` | `Page`, `DiskManager`, `BufferPool`, `HeapFile`, `Catalog`, `TupleCodec`. |
| `com.versiondb.wal` | `WALManager`, `LogRecord`, and the ARIES `RecoveryManager`. |
| `com.versiondb.txn` | `TransactionManager`, `SnapshotManager`, `MVCCManager`, `VersionStore`, `CommitLog`, `Vacuum`. |
| `com.versiondb.query` | SQL `Lexer`/`Parser`, the `ast` nodes, `BPlusTree`, the `Optimizer`, and the `exec` (Volcano) operators + `QueryExecutor`. |
| `com.versiondb.tools` | `VersionDb` (the assembled engine) and `VersionDbCli` (the REPL). |
| `com.versiondb.bench` | `MvccBenchmark` (the Track-B benchmark harness). |

**Architecture diagram (top-to-bottom data flow):**

```
            SQL text
               │
        ┌──────▼───────┐
        │ Lexer/Parser │  → AST (com.versiondb.query.ast)
        └──────┬───────┘
        ┌──────▼───────┐
        │  Optimizer   │  cost + selectivity → cheapest PhysicalPlan
        └──────┬───────┘  (SeqScan vs IndexScan, join order, Hash vs NLJ)
        ┌──────▼───────┐
        │ QueryExecutor│  Volcano operators: Scan→Filter→Join→Sort→Project→Limit
        └──────┬───────┘
   ┌───────────┼─────────────────────────┐
   │           │                         │
┌──▼───┐   ┌───▼─────┐               ┌────▼─────┐
│B+Tree│   │  MVCC   │ visibility    │   WAL    │ log-before-write
│index │   │ Manager │ + versions    │ Manager  │ (ARIES recovery)
└──┬───┘   └───┬─────┘               └────┬─────┘
   │           │ VersionStore             │
   └───────────┼──────────────────────────┘
        ┌──────▼───────┐
        │  BufferPool  │  clock eviction, pin/unpin, WAL-flush callback
        └──────┬───────┘
        ┌──────▼───────┐
        │ HeapFile +   │  slotted pages, free-space map
        │ DiskManager  │  ←→ versiondb.db / wal.log / commit log
        └──────────────┘
```

**Write path.** A write goes through the buffer pool and is logged to the WAL before
its page can be flushed (the WAL rule); MVCC records the tuple version with its
creating/deleting transaction ids; commit makes the WAL record durable. On startup
the engine replays the WAL (ARIES) to restore committed state and reconstructs the
visible tuple versions.

## 3. Storage Layer

**Page format.** Fixed 8&nbsp;KB **slotted pages** (`storage/Page`). A 32-byte header
holds `pageId`, `lsn`, free-space start/end pointers, slot count, a `nextPageId`
link (heap page chain), and a CRC32 checksum. The slot array grows forward from the
header; tuple bytes grow backward from the end of the page, with one contiguous free
block between them. A slot offset of `0` is a tombstone (deleted tuple).

```
+-----------------------------------------------------------+
| HEADER (32 B): pageId, lsn, freeStart, freeEnd, #slots,   |
|                nextPageId, crc32                          |
+----------------------+------------------------------------+
| slot[0] slot[1] ...  |  free space  | ... tuple[1] tuple[0]|
| grows -->            |              |          <-- grows  |
+----------------------+------------------------------------+
```

**Heap files.** `HeapFile` chains pages via the header `nextPageId` link and keeps a
free-space map so inserts find a page with room without scanning the whole file. A
tuple's address is a `RID(pageId, slotId)`. `TupleCodec` serialises typed columns
(`INT`, `FLOAT`, `VARCHAR`, `BOOL`, `NULL`) to/from page bytes.

**Buffer pool.** `BufferPool` manages a fixed set of frames (256) over the
`DiskManager`, with **clock (second-chance) eviction**, pin/unpin reference counting,
and a dirty flag per frame. It enforces the WAL rule through a WAL-flush callback:
a dirty page cannot be written to disk until the WAL is durable up to that page's LSN.
The `Catalog` (system catalog) persists table/column/index metadata on its own pages.

## 4. Indexing

**B+Tree design.** `query/BPlusTree` is a disk-resident B+Tree; each node is one
buffer-pool page (`BTreeNode`). It supports point search, range scan, insert, and
delete, and backs the primary-key index.

**Node structure.**
```
inner: [type=0][numKeys][parent] [child_0][key_0][child_1]...[child_n]
leaf : [type=1][numKeys][parent][nextLeaf] ([key][ridPage][ridSlot])*
```
Inner nodes hold routing keys and one more child pointer than keys; leaf nodes hold
`(key, RID)` entries in ascending key order and a `nextLeaf` pointer forming a
left-to-right leaf chain for range scans.

**Search path.** Start at the root; in each inner node binary-search the routing keys
to choose the child pointer; descend until a leaf; binary-search the leaf for the key.
A point lookup returns the matching `RID`; a range scan walks the `nextLeaf` chain
from the first qualifying leaf. The executor uses the index through `IndexScanOperator`
when the optimizer selects an index scan.

## 5. Query Execution

**Parser.** A hand-written `Lexer` tokenises SQL; a recursive-descent `Parser` builds
an AST (`query.ast`) with full expression precedence (`OR` < `AND` < `NOT` <
comparisons < `+ -` < `* /`, parentheses). Supports `CREATE TABLE`/`CREATE INDEX`,
`INSERT`, `SELECT` (`WHERE`, `JOIN`, `ORDER BY`, `LIMIT`/`OFFSET`, `COUNT(*)`),
`UPDATE`, `DELETE`, `DROP TABLE`, and `BEGIN`/`COMMIT`/`ABORT`.

**Query-plan generation.** The `Optimizer` turns the AST into the cheapest
`PhysicalPlan` (`query.plan`): it pushes the `WHERE` predicate into the scan, chooses
`SeqScanPlan` vs `IndexScanPlan`, picks a join order and per-join algorithm, and stacks
projection/sort/limit on top.

**Operator execution.** The `QueryExecutor` runs the plan with the **Volcano (iterator)
model** — every operator exposes `open()/next()/close()` and pulls tuples one at a time
from its child. Operators: `SeqScanOperator`, `IndexScanOperator`, `FilterOperator`,
`ProjectionOperator`, `NestedLoopJoinOperator`, `HashJoinOperator`, `SortOperator`,
`LimitOperator`, `AggregateOperator`. Scans read tuples through the `MVCCManager` so
each operator only ever sees versions visible to the transaction's snapshot.

## 6. Optimizer

**Cost estimation.** `CostModel` scores each candidate plan from catalog statistics
(`TableStats` row counts, `ColumnStats`). A sequential scan costs the table's page
count; an index scan costs the estimated number of qualifying rows (plus index
descent). The optimizer picks the lower-cost option.

**Selectivity estimation.** `SelectivityEstimator` estimates the fraction of rows a
predicate keeps — equality predicates use distinct-value counts, range predicates use
min/max bounds — and combines conjuncts/disjuncts to size intermediate results.

**Join ordering.** For multi-table joins (`Optimizer` §14.5) the optimizer enumerates
**all permutations for up to four tables** and picks the cheapest **left-deep** order;
beyond four tables it falls back to a **greedy smallest-relation-first** order to avoid
combinatorial blow-up. For each join it chooses **Hash join vs Nested-Loop join** by
estimated cost.

## 7. Transactions & Concurrency

**Concurrency-control strategy — MVCC (Track B).** We do **not** use two-phase
locking. Instead, `MVCCManager` + `VersionStore` keep a version chain per tuple, and
`SnapshotManager` captures a snapshot `(xmin, xmax, in-progress set)` at transaction
start. `TransactionManager` assigns XIDs and captures each snapshot atomically under a
single lock so it reflects exactly which transactions were live at that instant. The
only physical locks are short **per-RID striped locks** (1024 stripes) held by writers
for the duration of a single version stamp — readers take **no** locks.

**Isolation guarantees.** **Snapshot Isolation.** A version is visible to a
transaction iff its creator (`xmin`) committed before the snapshot and its deleter
(`xmax`) is absent or not visible to the snapshot; a transaction always sees its own
uncommitted writes. Readers therefore never block writers and never see uncommitted
or "future" data.

**Conflict / "deadlock" handling.** Because readers take no locks and writers hold a
stripe only momentarily, classic lock-cycle deadlock cannot occur. Write–write
conflicts are resolved **first-writer-wins**: if a transaction tries to update/delete a
version another live transaction has already stamped, it gets a
`TransactionConflictException` and aborts. (`ConcurrencyTest` exercises basic
isolation, read consistency, abort cleanup, concurrent-update conflict, version-chain
correctness, and parallel autocommit writers.)

## 8. Recovery

**WAL design.** `WALManager` writes an append-only, length-prefixed log
(`wal.log`). The buffer pool calls back into the WAL on flush to enforce
**write-ahead logging**: a page's bytes never reach disk before the log record that
describes the change is durable. Commit forces the log to disk.

**Log records.** Each `LogRecord` carries `lsn`, `prevLsn` (per-transaction back
link), `txnId`, and a `LogType`:
- `BEGIN`, `COMMIT`, `ABORT` — no body;
- `INSERT`, `DELETE` — `pageId + slotId + data`;
- `CLR` (compensation log record) — adds `undoNextLsn` for redo-safe undo;
- `CHECKPOINT` — snapshots the Active Transaction Table and Dirty Page Table.

**Crash-recovery procedure — ARIES, three passes** (`RecoveryManager`):
1. **Analysis** — scan forward from the last checkpoint, rebuild the Active
   Transaction Table (loser transactions to roll back) and the Dirty Page Table
   (where redo must start).
2. **Redo** — replay all logged changes from the smallest `recLsn`, comparing each
   page's LSN to skip changes already on disk, restoring the exact pre-crash page
   state.
3. **Undo** — roll back the losers newest-first, writing CLRs so the undo is itself
   recoverable if the recovery crashes.

After recovery, `CommitLog.reconcile` aligns transaction statuses with the recovered
outcome, and the in-memory `VersionStore` is reseeded from the committed WAL history.
Committed transactions survive a crash; uncommitted ones are fully rolled back.

## 9. Extension Track — Track B (MVCC)

**Motivation.** Under lock-based 2PL, readers and writers contend for the same locks,
so a long write (or many writers) blocks readers and tanks read throughput. MVCC lets
readers proceed against a consistent snapshot without locking, which is the right
trade-off for the common read-mostly OLTP workload.

**Design.** Option-B *separate version store*: the heap holds current bytes; every
version lives in `VersionStore`, linked newest-to-oldest by `prevVersionId`. Each
version carries `xmin`/`xmax`. Reads walk the chain and return the first version
visible to the snapshot (`getVisibleVersion`). Writes stamp `xmax` (delete) / append a
new chain (insert); UPDATE is delete-old + insert-new on independent RID chains.
Aborts use an in-memory undo log (`xmin←0` to invalidate an inserted version,
`xmax←0` to restore a deleted one). A background `Vacuum` thread reclaims versions no
live snapshot can see, keeping chains short.

**Results.** MVCC sustained **~6–14× the read throughput** of an equivalent
lock-based (table R/W lock) baseline under concurrent writers, with readers never
blocking. Full methodology and numbers are in [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md).

## 10. Benchmarks

See [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md) for the full report. Summary:

- **Experimental setup.** `bench(id, v)` with 1 000 rows; 8 reader threads +
  4 writer threads for 3 s per mode; native MVCC vs. the same engine with a
  table-level `ReadWriteLock` (emulating coarse 2PL). Writers use disjoint key
  ranges to isolate blocking from write–write conflicts.
- **Scenario 1 — read throughput under contention.** MVCC ~120 reads/s vs. lock
  baseline ~10 reads/s → **~6–14× read speedup**; readers never blocked. The lock
  baseline shows higher *write* throughput because blocked readers free up CPU — the
  expected availability-vs-writer trade-off.
- **Scenario 2 — version-chain read latency.** Median point-read latency grows
  ~linearly with chain depth (~3 µs at depth 1 → ~75 µs at depth 1024), quantifying
  version-traversal cost and motivating vacuum.

Reproduce with:
```bash
mvn -q compile
java -cp target/classes com.versiondb.bench.MvccBenchmark
```

## 11. Limitations

- **Isolation level.** Snapshot Isolation only — **Serializable SI / write-skew
  prevention is out of scope** (no SSI conflict detection).
- **Durability of schema/page links.** The catalog and heap page-chain links are
  flushed at `close()` rather than individually WAL-logged, so a **clean shutdown** is
  needed to durably persist schema changes and large multi-page tables.
- **Version store is in-memory.** It is rebuilt from the committed WAL history at
  startup; very long histories make reseed proportionally slower.
- **Aggregation** is limited to `COUNT(*)` (no `GROUP BY`, `SUM`, `AVG`).
- **Scale.** Single node; no networking, no replication, no parallel query execution.
- **Future work.** SSI for full serializability; WAL-logged catalog; persistent
  version store / checkpointed reseed; richer aggregation and `GROUP BY`; secondary
  index maintenance under updates.

## 12. How to Run

**Dependencies.**
- Java 17+ (built and tested up to Java 25)
- Maven 3.8+

**Build & test.**
```bash
mvn compile      # build
mvn test         # run the full JUnit 5 suite (424 tests)
```

**Interactive SQL shell (REPL).**
```bash
mvn -q compile
java -cp target/classes com.versiondb.tools.VersionDbCli mydata
```
`mydata` is the directory the database files live in; it is created on first run and
reopened (with crash recovery) on subsequent runs. Example session:
```
VersionDB ready. End statements with ';'. Type 'exit' to quit.
versiondb> CREATE TABLE users (id INT, name VARCHAR);
OK, 0 row(s) affected
versiondb> INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob');
OK, 2 row(s) affected
versiondb> SELECT id, name FROM users WHERE id = 1;
id | name
1 | Alice
(1 row(s))
versiondb> BEGIN;
BEGIN
versiondb> UPDATE users SET name = 'Carol' WHERE id = 2;
OK, 1 row(s) affected
versiondb> COMMIT;
COMMIT
versiondb> exit
```
Each statement runs in **autocommit** mode unless wrapped in an explicit
`BEGIN` … `COMMIT`/`ABORT` (alias `ROLLBACK`) block.

**Run the MVCC benchmark.**
```bash
java -cp target/classes com.versiondb.bench.MvccBenchmark [durationMillis] [readers] [writers] [rows]
```

**As an embedded library.**
```java
import com.versiondb.tools.VersionDb;
import com.versiondb.query.exec.ResultSet;
import com.versiondb.txn.Transaction;
import java.nio.file.Path;

try (VersionDb db = VersionDb.open(Path.of("mydata"))) {
    db.execute("CREATE TABLE users (id INT, name VARCHAR)");
    db.execute("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')");

    ResultSet rs = db.execute("SELECT id, name FROM users ORDER BY id");
    for (var row : rs.rows()) {
        System.out.println(row.get(0).asInt() + " -> " + row.get(1).asString());
    }

    Transaction tx = db.begin();
    db.execute("UPDATE users SET name = 'Carol' WHERE id = 2", tx);
    db.commit(tx);   // or db.abort(tx);
}
```

## Supported SQL

```sql
CREATE TABLE t (id INT NOT NULL, name VARCHAR, price FLOAT, active BOOL);
CREATE INDEX idx_name ON t (name);

INSERT INTO t (id, name) VALUES (1, 'a'), (2, 'b');
INSERT INTO t VALUES (3, 'c', 9.5, TRUE);

SELECT * FROM t WHERE price >= 10 AND active = TRUE;
SELECT t.name, o.amount FROM t JOIN orders o ON t.id = o.tid ORDER BY o.amount DESC LIMIT 5;
SELECT COUNT(*) FROM t;

UPDATE t SET price = 12 WHERE id = 1;
DELETE FROM t WHERE id = 2;
```

- **Types:** `INT`, `FLOAT`, `VARCHAR`, `BOOL` (and `NULL`).
- **Predicates:** `AND`, `OR`, `NOT`, comparisons (`= != <> < > <= >=`), arithmetic
  (`+ - * /`), and parentheses, with standard precedence.
- **Joins:** `INNER`, `LEFT`, `RIGHT`, `CROSS` (and bare `JOIN`, which is inner).
- **Clauses:** `WHERE`, `ORDER BY` (`ASC`/`DESC`), `LIMIT`, `OFFSET`.
- **Aggregation:** `COUNT(*)`.
- **Transactions:** `BEGIN`, `COMMIT`, `ABORT`/`ROLLBACK`.

## License

Academic project.
