# MiniDB

A single-node relational database engine built from scratch in **Java 17**. MiniDB
implements the core of a real database — a disk-backed storage engine, write-ahead
logging with ARIES crash recovery, PostgreSQL-style multi-version concurrency
control (MVCC), a SQL parser, a B+Tree index, a cost-based optimizer, and a
Volcano-model execution engine — and exposes it through both an embeddable API and
an interactive SQL shell.

## Features

- **Storage engine** — 8&nbsp;KB slotted pages, a clock-eviction buffer pool, heap
  files with a free-space map, and a system catalog.
- **Durability** — a write-ahead log (WAL) and **ARIES** recovery (Analysis → Redo →
  Undo) so committed transactions survive a crash and uncommitted ones are rolled
  back.
- **Concurrency** — MVCC with **Snapshot Isolation**: readers never block writers,
  each transaction sees a consistent point-in-time snapshot, and conflicting writers
  are resolved first-writer-wins.
- **SQL** — a lexer and recursive-descent parser, a cost-based optimizer, and a
  pipelined (Volcano) executor supporting scans, filters, projections, joins,
  sorting, limits, and aggregation.
- **Indexing** — a disk-resident B+Tree for point and range lookups.

## Requirements

- Java 17+
- Maven 3.8+

## Build & test

```bash
mvn compile      # build
mvn test         # run the full JUnit 5 test suite
```

## Using MiniDB

### As an interactive SQL shell (REPL)

```bash
mvn -q compile
java -cp target/classes com.minidb.tools.MiniDbCli mydata
```

`mydata` is the directory the database files live in; it is created on first run and
reopened (with crash recovery) on subsequent runs. Example session:

```
MiniDB ready. End statements with ';'. Type 'exit' to quit.
minidb> CREATE TABLE users (id INT, name VARCHAR);
OK, 0 row(s) affected
minidb> INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob');
OK, 2 row(s) affected
minidb> SELECT id, name FROM users WHERE id = 1;
id | name
1 | Alice
(1 row(s))
minidb> BEGIN;
BEGIN
minidb> UPDATE users SET name = 'Carol' WHERE id = 2;
OK, 1 row(s) affected
minidb> COMMIT;
COMMIT
minidb> exit
```

Each statement runs in **autocommit** mode unless wrapped in an explicit
`BEGIN` … `COMMIT`/`ABORT` (alias `ROLLBACK`) block.

### As an embedded library

```java
import com.minidb.tools.MiniDb;
import com.minidb.query.exec.ResultSet;
import com.minidb.txn.Transaction;
import java.nio.file.Path;

try (MiniDb db = MiniDb.open(Path.of("mydata"))) {
    db.execute("CREATE TABLE users (id INT, name VARCHAR)");
    db.execute("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')");

    // Query: ResultSet exposes column names and rows of typed Values.
    ResultSet rs = db.execute("SELECT id, name FROM users ORDER BY id");
    for (var row : rs.rows()) {
        System.out.println(row.get(0).asInt() + " -> " + row.get(1).asString());
    }

    // Explicit transaction spanning several statements.
    Transaction tx = db.begin();
    db.execute("UPDATE users SET name = 'Carol' WHERE id = 2", tx);
    db.commit(tx);   // or db.abort(tx);
}
```

`MiniDb.open` starts a background vacuum thread; `close()` performs a clean shutdown
(flushing buffered pages). A `ResultSet` provides `columns()`, `rows()`
(`List<List<Value>>`), `affectedRows()`, and `size()`; a `Value` provides
`asInt()`, `asFloat()`, `asString()`, `asBool()`, and `isNull()`.

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

## Architecture

MiniDB is organized in layers, each behind a clear interface:

| Package | Responsibility |
|---|---|
| `com.minidb.shared` | Shared value types: `RID`, `Value`, `ColumnType`, metadata records, exceptions. |
| `com.minidb.storage` | `Page`, `DiskManager`, `BufferPool`, `HeapFile`, `Catalog`, `TupleCodec`. |
| `com.minidb.wal` | `WALManager`, `LogRecord`, and the ARIES `RecoveryManager`. |
| `com.minidb.txn` | `TransactionManager`, `SnapshotManager`, `MVCCManager`, `VersionStore`, `CommitLog`, `Vacuum`. |
| `com.minidb.query` | SQL `Lexer`/`Parser`, the `ast` nodes, `BPlusTree`, the `Optimizer`, and the `exec` (Volcano) operators + `QueryExecutor`. |
| `com.minidb.tools` | `MiniDb` (the assembled engine) and `MiniDbCli` (the REPL). |

A write goes through the buffer pool and is logged to the WAL before its page can be
flushed (the WAL rule); MVCC records the tuple version with its creating/deleting
transaction ids; commit makes the WAL record durable. On startup the engine replays
the WAL to restore committed state and reconstruct the visible tuple versions.

## Durability & limitations

- Committed transactions are durable: their WAL records are flushed on commit and
  replayed by ARIES recovery on the next open.
- The version store is rebuilt at startup from the committed WAL history.
- **Clean shutdown matters:** the catalog and heap page-chain links are flushed at
  `close()` rather than being individually WAL-logged, so a clean shutdown is needed
  to durably persist schema changes and large multi-page tables.
- Scope: single node; Snapshot Isolation (Serializable SI / write-skew prevention is
  out of scope); background vacuum reclaims version-store entries.

## License

Academic project.
