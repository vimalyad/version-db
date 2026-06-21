# MiniDB — Implementation Plan

This is the build plan for MiniDB. It decomposes the whole system (specified in `part1.md`, `part2.md`, `part3.md`) into **phases** and **sub-phases**.

- **Each phase = one git branch** (branch name given per phase).
- **Each sub-phase = one commit** that leaves the build green and includes its own tests.
- **Every commit updates `CHANGES.md`.**
- Read `CLAUDE.md` first for workflow and the strict attribution rule.

Language: **Java 17** · Build: **Maven** · Tests: **JUnit 5**.

---

## Legend & conventions

- **Owner** = which team member's design doc the phase comes from (M1 = `part1.md` Storage, M2 = `part2.md` Query, M3 = `part3.md` MVCC). Any session can implement any phase.
- **Depends on** = phases that must be complete (per `CHANGES.md`) before starting.
- **DoD** = Definition of Done: the phase is finished only when all these hold.
- A sub-phase labelled `N.k` becomes the commit subject scope, e.g. commit `feat(bufferpool): 2.3 clock eviction`.
- Where a phase needs a not-yet-built dependency, it uses a **temporary stub/interface** (noted in the sub-phase) so members can work in parallel; the stub is replaced when the real phase lands.

## Dependency overview

```
Phase 0  Foundation (shared types, build, test harness)
   │
   ├──────────────► M1 storage stack ───────────────► M1 durability
   │   P1 Page/Disk → P2 BufferPool → P3 HeapFile → P4 Catalog → P5 WAL → P6 Recovery
   │
   ├──────────────► M3 transactions (needs P3 HeapFile, P5 WAL)
   │   P7 CommitLog → P8 TxnManager → P9 Snapshot → P10 MVCCManager → P11 Vacuum
   │
   └──────────────► M2 query (needs P2 BufferPool, P3/P4, P10 MVCC)
       P12 Parser → P13 B+Tree → P14 Optimizer → P15 Execution Engine
                                                       │
                                                  P16 Integration & End-to-End
```

Recommended parallelism for 3 members:
- **M1** drives the critical path: P0 → P1 → P2 → P3 → P4 → P5 → P6.
- **M3** starts P7 (CommitLog) and P8/P9 against stubs right after P0, fully integrates once P3+P5 land.
- **M2** starts P12 (Parser) and P13 (B+Tree) right after P0; P13 needs P2, P14/P15 need P3/P4/P10.

---

# Phase 0 — Project Foundation
- **Branch:** `phase-00-foundation` · **Owner:** shared · **Depends on:** none
- **Goal:** a compiling Maven project, the shared types every layer imports, and a working test harness.

**Sub-phases (commits):**
- **0.1** Maven project: `pom.xml` (Java 17, JUnit 5, surefire), package skeleton `com.minidb.{shared,storage,wal,txn,query}`, a trivial passing test to prove the harness runs.
- **0.2** `shared/RID.java` — immutable `{int pageId, int slotId}` with `equals`/`hashCode`/`toString`. `shared/Constants.java` — `PAGE_SIZE` (decide 4096 or 8192, fix forever), sentinel XIDs (`INVALID_XID=0`).
- **0.3** `shared/ColumnType.java` (INT, FLOAT, VARCHAR, BOOL), `shared/Value.java` (tagged union of a typed value + null), `shared/ColumnDef.java`.
- **0.4** Metadata records: `shared/TableMeta`, `ColumnMeta`, `IndexMeta`, `TableStats`, `ColumnStats` (fields per `part1.md` §9).
- **0.5** Error types: `shared/MiniDbException` hierarchy (`StorageException`, `ParseException`, `SerializationException`, `TransactionConflictException`).

**Data structures:** RID, Value, ColumnType, *Meta, exception hierarchy.
**API:** the shared types above (no behavior beyond accessors).
**Tests:** RID equality/hashing; Value round-trip of each type incl. null.
**DoD:** `mvn test` green; all five shared packages importable; PAGE_SIZE chosen and documented in `CHANGES.md`.

---

# Phase 1 — Page & DiskManager  (M1, `part1.md` §3)
- **Branch:** `phase-01-storage-page` · **Depends on:** P0
- **Goal:** define the on-disk page (slotted layout) and raw page I/O.

**Sub-phases:**
- **1.1** `storage/Page.java` — header fields (`pageId, lsn, freeSpaceStart, freeSpaceEnd, numSlots, nextPageId, checksum`) + slotted body layout; `serialize()`/`deserialize()` to/from a `PAGE_SIZE` byte array.
- **1.2** Slot array + tuple ops: `insertTuple(bytes)→slotId`, `getTuple(slotId)`, `deleteTuple(slotId)` (tombstone = offset 0), `getFreeSpace()`, `setLsn(lsn)`.
- **1.3** `storage/Crc32Util` + checksum write on `serialize`, verify on `deserialize` (throw on mismatch).
- **1.4** `storage/DiskManager.java` — owns the DB file; `readPage(pageId)`, `writePage(pageId, page)` (+ fsync), `allocatePage()`, file header with total page count.

**Data structures:** slotted page (header + slot array growing forward, tuples growing backward).
**API:** `Page.*`, `DiskManager.{readPage,writePage,allocatePage}`.
**Tests:** insert/get/delete on a page; free-space accounting; serialize→deserialize identity; checksum mismatch detection; allocate-then-read a page through DiskManager.
**DoD:** a page survives a serialize/write/read/deserialize round-trip byte-for-byte; corruption is detected.

---

# Phase 2 — Buffer Pool  (M1, `part1.md` §4)
- **Branch:** `phase-02-bufferpool` · **Depends on:** P1
- **Goal:** the in-memory page cache that mediates ALL disk access and enforces the WAL rule.

**Sub-phases:**
- **2.1** `storage/Frame` (pageId, page, pinCount, isDirty, refBit) + frame array + `pageId→frameIndex` page table.
- **2.2** `fetchPage(pageId)` / `unpin(pageId, dirty)` / `newPage()` / `markDirty(pageId)`.
- **2.3** Clock eviction (`refBit` second-chance), evicting a dirty victim flushes it first.
- **2.4** `flushPage(pageId)` / `flushAll()` with the **WAL rule hook**: before writing a dirty page, call an injected `WalFlushCallback.flushToLsn(page.lsn)`. Until P5 exists, inject a no-op stub.
- **2.5** Thread safety: single lock for page-table/frame-array mutations; release lock around disk I/O (per `part1.md` §4.6).

**Data structures:** frame array, page table (HashMap), clock hand.
**API:** `fetchPage, unpin, newPage, markDirty, flushPage, flushAll`; constructor takes a `WalFlushCallback`.
**Tests:** cache hit increments pin; eviction skips pinned/refBit frames; dirty victim is flushed before eviction; WAL callback invoked before a dirty write; concurrent fetch/unpin smoke test.
**DoD:** no component other than BufferPool calls DiskManager; WAL hook fires on every dirty-page write.

---

# Phase 3 — Heap File  (M1, `part1.md` §5)
- **Branch:** `phase-03-heapfile` · **Depends on:** P2
- **Goal:** unordered tuple storage per table, with a free-space map and a full scan, plus the shared tuple binary format.

**Sub-phases:**
- **3.1** `storage/HeapFile` page-chain (linked list via `nextPageId`, knows `firstPageId`).
- **3.2** In-memory Free Space Map (`pageId→freeBytes`), rebuilt on open, updated on insert/delete.
- **3.3** `insertTuple(bytes)→RID` (FSM pick page or allocate new), `getTuple(rid)`, `deleteTuple(rid)`.
- **3.4** `scan()` — iterator over `(RID, bytes)` walking the page chain and slots, skipping tombstones.
- **3.5** `storage/TupleCodec` — the shared tuple serialization format (null bitmap + columns per `part1.md` §5.5). This is the contract M2/M3 (de)serialize against.

**Data structures:** page chain, FSM map, scan iterator.
**API:** `HeapFile.{insertTuple,getTuple,deleteTuple,scan}`, `TupleCodec.{encode,decode}`.
**Tests:** insert across multiple pages triggers allocation; scan returns all live tuples and skips deleted; FSM correctness after deletes; TupleCodec round-trip incl. nulls and VARCHAR.
**DoD:** a table of N tuples spanning several pages scans back exactly the live set; tuple format documented and stable.

---

# Phase 4 — Catalog  (M1, `part1.md` §6)
- **Branch:** `phase-04-catalog` · **Depends on:** P3
- **Goal:** the metadata dictionary (tables, columns, indexes, stats) persisted in fixed system heaps.

**Sub-phases:**
- **4.1** System heaps at fixed page ids (tables=0, columns=1, indexes=2); load into in-memory caches on startup.
- **4.2** `createTable(name, columns)`, `getTable(name)`, `getColumns(tableId)`.
- **4.3** Index metadata: `registerIndex(tableId, column, rootPageId)`, `getIndexes(tableId)`.
- **4.4** Statistics: `getStats(tableName)`, `updateStats(tableId, stats)` (table + per-column).

**API:** `Catalog.{createTable,getTable,getColumns,getIndexes,registerIndex,getStats,updateStats}`.
**Tests:** create then look up a table; column order preserved; index registration visible to `getIndexes`; stats round-trip; catalog survives reopen (reads back from system heaps).
**DoD:** catalog fully reconstructs from disk on restart.

---

# Phase 5 — WAL Manager  (M1, `part1.md` §7)
- **Branch:** `phase-05-wal` · **Depends on:** P2 (and wires back into it)
- **Goal:** durable, append-only write-ahead log with LSNs, batched flush, and the real WAL-rule callback.

**Sub-phases:**
- **5.1** `wal/LogRecord` types + common header (`lsn, prevLsn, txnId, logType`) and bodies for BEGIN, INSERT, DELETE, COMMIT, ABORT, CHECKPOINT, CLR; length-prefixed serialization.
- **5.2** `wal/WALManager` state: `currentLsn`, `flushedLsn`, in-memory `logBuffer`, `txnLastLsn` map.
- **5.3** Append ops: `logBegin, logInsert, logDelete, logCommit (sync flush), logAbort (sync flush), logClr, logCheckpoint`.
- **5.4** `flushToLsn(targetLsn)` + flush triggers (commit/abort, buffer threshold). **Replace P2's stub**: inject the real `flushToLsn` as BufferPool's `WalFlushCallback`.
- **5.5** Read side: `readLog(startLsn)` iterator and `readRecordAtLsn(lsn)` (for Recovery).

**API:** all `WALManager.log*`, `flushToLsn`, `readLog`, `readRecordAtLsn`.
**Tests:** LSNs strictly increase; `prevLsn` chains per transaction; commit forces durability (`flushedLsn >= commitLsn`); records read back in order; WAL rule holds (BufferPool flush triggers a WAL flush to the page's lsn).
**DoD:** every data-change op is loggable and replayable; BufferPool now flushes WAL before dirty pages for real.

---

# Phase 6 — Recovery (ARIES)  (M1, `part1.md` §8)
- **Branch:** `phase-06-recovery` · **Depends on:** P5
- **Goal:** crash recovery — Analysis, Redo, Undo — plus checkpointing.

**Sub-phases:**
- **6.1** `wal/RecoveryManager` Analysis pass: rebuild ATT (`txnId→lastLsn`) and DPT (`pageId→recLsn`) from last checkpoint forward.
- **6.2** Redo pass: from `min(DPT.recLsn)`, repeat history with the three skip conditions; set `page.lsn` after apply.
- **6.3** Undo pass: max-heap by LSN over ATT; undo INSERT→delete / DELETE→reinsert; write CLRs with `undoNextLsn`; emit ABORT at BEGIN.
- **6.4** Checkpointing: flush dirty pages, capture ATT+DPT, write+flush CHECKPOINT; run on startup-after-recovery and every 10k records.
- **6.5** Commit-log reconciliation hook (coordinate with P7): mark recovered txns COMMITTED/ABORTED.

**API:** `RecoveryManager.recover()` (run on startup), `checkpoint()`.
**Tests:** crash-before-commit ⇒ change undone; crash-after-commit ⇒ change redone and durable; crash during undo ⇒ CLRs prevent double-undo; checkpoint shortens replay. (Simulate crashes by discarding the buffer pool and replaying WAL.)
**DoD:** committed work survives simulated crashes; uncommitted work disappears; recovery is idempotent (re-running it is a no-op).

---

# Phase 7 — Commit Log  (M3, `part3.md` §7)
- **Branch:** `phase-07-commitlog` · **Depends on:** P0 (integrates with P6)
- **Goal:** persistent 2-bit transaction status array (`IN_PROGRESS/COMMITTED/ABORTED`).

**Sub-phases:**
- **7.1** `txn/CommitLog` file `commit_log.clog`; 2 bits/xid packing (byte offset `xid/4`, bit offset `(xid%4)*2`).
- **7.2** `getStatus(xid)`, `setStatus(xid, status)` (sync flush on set), `isCommitted(xid)` (O(1)); in-RAM cache with lazy writes.
- **7.3** Byte-level write safety (lock the touched byte) per `part3.md` §11.

**API:** `CommitLog.{getStatus,setStatus,isCommitted}`.
**Tests:** set/get every status; durability across reopen; packing correctness at byte boundaries; concurrent setStatus on distinct xids in the same byte.
**DoD:** status survives restart; `isCommitted` is O(1).

---

# Phase 8 — Transaction Manager  (M3, `part3.md` §4)
- **Branch:** `phase-08-txnmgr` · **Depends on:** P7 (uses P5 WAL; stub WAL until P5 lands)
- **Goal:** transaction lifecycle and global transaction state.

**Sub-phases:**
- **8.1** `txn/Transaction` object (`xid, snapshot, status, undoLog, writeSet`) + `TxStatus` enum.
- **8.2** `TransactionManager` state: atomic `nextXid`, `activeTransactions` map, in-memory `committedXids`.
- **8.3** `beginTransaction()` (assign xid, take snapshot [stub until P9], log BEGIN, register active).
- **8.4** `commitTransaction(txn)` (log COMMIT sync, CommitLog=COMMITTED, drop from active) and `abortTransaction(txn)` (undo via in-memory undo log, log ABORT, CommitLog=ABORTED).
- **8.5** `isCommitted(xid)` (delegates to CommitLog), `getOldestActiveXid()` (the GC horizon).

**API:** `TransactionManager.{beginTransaction,commitTransaction,abortTransaction,isCommitted,getOldestActiveXid}`.
**Tests:** xids strictly increase and never reuse; active set tracks lifecycle; horizon equals min active xid; commit/abort update CommitLog; thread-safe begin under contention.
**DoD:** lifecycle correct and thread-safe; horizon usable by Vacuum.

---

# Phase 9 — Snapshot Manager  (M3, `part3.md` §5)
- **Branch:** `phase-09-snapshot` · **Depends on:** P8
- **Goal:** immutable snapshots capturing `{xmin, xmax, inProgressXids}` atomically.

**Sub-phases:**
- **9.1** `txn/Snapshot` immutable type + `isInProgress(xid)` helper.
- **9.2** `SnapshotManager.createSnapshot()` taken atomically with the TxnManager lock (xmin=min active or nextXid; xmax=nextXid; inProgress=copy of active keys). Wire into `beginTransaction` (replace 8.3 stub).
- **9.3** Isolation-level switch: per-statement snapshot (READ COMMITTED) vs per-transaction snapshot (REPEATABLE READ / SI default).

**API:** `SnapshotManager.createSnapshot()`, `Snapshot.isInProgress(xid)`.
**Tests:** a txn that commits after a snapshot stays "in progress" for it; xmin/xmax boundaries; snapshot immutability; atomicity (no xid assigned-but-missing-from-active race).
**DoD:** snapshots are stable for their owner's lifetime and consistent with the active set.

---

# Phase 10 — MVCC Manager (version store, visibility, writes)  (M3, `part3.md` §6)
- **Branch:** `phase-10-mvcc` · **Depends on:** P3, P5, P9
- **Goal:** version chains, the visibility algorithm, and all write operations. **This is the core of Member 3.**
- **Design choice:** use **Option B (separate Version Store)** from `part3.md` §6.2 — heap holds nothing MVCC-specific; old versions live in a version store with `prevVersionId` links.

**Sub-phases:**
- **10.1** `txn/VersionRecord` (`xmin, xmax, rid, prevVersionId, data`) + `txn/VersionStore` (allocate/get version, chain-head map `RID→headVersionId`).
- **10.2** Visibility rule `isVisible(version, snapshot)` exactly per `part3.md` §6.4 (xmin-visible AND not xmax-visible), using `CommitLog.isCommitted`.
- **10.3** `getVisibleVersion(rid, snapshot)` — walk chain head→prev, return first visible `data` or null. (The hot read path for M2.)
- **10.4** `insert(txn, heapFile, bytes)` — heap insert, new head version `xmin=txn.xid, xmax=0, prev=-1`, undo-log `(rid,null)`, WAL logInsert, set page lsn.
- **10.5** `delete(txn, heapFile, rid)` — stamp head `xmax=txn.xid`, undo-log `(rid, oldBytes)`, WAL logDelete.
- **10.6** `update` = delete old + insert new linked via `prevVersionId`; WAL both.
- **10.7** Abort handling: reverse undo log (INSERT→invalidate version, DELETE/UPDATE→`xmax=0` restore). First-writer-wins conflict: second writer on an in-progress `xmax` aborts (`TransactionConflictException`).
- **10.8** Per-RID striped locks for chain mutations (`part3.md` §11).

**API:** `MVCCManager.{getVisibleVersion,insert,delete,update}`.
**Tests:** the 6 scenarios in `part3.md` §14 (basic isolation, read consistency, abort cleanup, concurrent-update conflict, version-chain correctness across 5 commits, GC-safe reads).
**DoD:** every read goes through visibility; snapshots see exactly the right version; aborts leave no visible trace; concurrent same-row updates resolve deterministically.

---

# Phase 11 — Vacuum / Garbage Collection  (M3, `part3.md` §8)
- **Branch:** `phase-11-vacuum` · **Depends on:** P10
- **Goal:** reclaim versions invisible to all current/future transactions.

**Sub-phases:**
- **11.1** Reclaim predicate: `xmax != 0 AND isCommitted(xmax) AND xmax < getOldestActiveXid()`.
- **11.2** GC pass: per chain, remove reclaimable versions from the tail; stop at first `xmax >= horizon` or `xmax == 0`.
- **11.3** Background trigger (periodic thread / every N txns); physically free heap slots for fully-dead rows via `HeapFile.deleteTuple`.

**API:** `Vacuum.runOnce()`, background scheduler start/stop.
**Tests:** old versions removed once horizon passes; a long-running transaction holds the horizon and prevents reclamation; reads remain correct after GC.
**DoD:** version store stops growing under steady churn; no reachable version is ever removed.

---

# Phase 12 — SQL Parser  (M2, `part2.md` §3)
- **Branch:** `phase-12-parser` · **Depends on:** P0
- **Goal:** SQL string → AST via lexer + recursive-descent parser.

**Sub-phases:**
- **12.1** `query/Lexer` + `Token`/`TokenType` (keywords case-insensitive, identifiers, int/float/string literals with `''` escape, operators, punctuation, `--` comments).
- **12.2** `query/ast/*` node taxonomy (statements, from-clause, expressions) per `part2.md` §3.3.
- **12.3** `query/Parser` recursive descent with `peek/advance/expect`; statements: SELECT, INSERT, DELETE, UPDATE, CREATE TABLE, CREATE INDEX, DROP TABLE.
- **12.4** Expression parsing with precedence chain (OR→AND→NOT→comparison→additive→multiplicative→unary→primary).
- **12.5** Error reporting with expected/found/position messages.

**API:** `Parser.parse(sql) → Statement`.
**Tests:** tokenize each literal/operator; parse each statement type; precedence correctness (`a OR b AND c`); informative errors on malformed input.
**DoD:** all supported statements parse to correct ASTs; errors carry position.

---

# Phase 13 — B+Tree Index  (M2, `part2.md` §4)
- **Branch:** `phase-13-btree` · **Depends on:** P2
- **Goal:** page-backed B+Tree giving O(log n) lookup and range scan over one column, storing `(key, RID)` in leaves.

**Sub-phases:**
- **13.1** Node layout + serialize/deserialize (type byte; inner vs leaf binary layout per `part2.md` §4.4); nodes are buffer-pool pages.
- **13.2** `search(key) → RID`.
- **13.3** `insert(key, rid)` with leaf split (copy-up) and inner split (push-up); root split grows height.
- **13.4** `rangeScan(low, high)` via leaf `nextLeaf` chain.
- **13.5** `delete(key, rid)` with borrow/merge + root shrink.
- **13.6** Bulk build for `CREATE INDEX` (scan heap, insert all).

**API:** `BPlusTree.{search,insert,rangeScan,delete}`.
**Tests:** insert→search many keys; forced splits keep tree balanced/sorted; range scan endpoints; delete with borrow and with merge; height grows/shrinks correctly; survives page eviction (persistence through buffer pool).
**DoD:** correctness under randomized insert/delete; range scans return sorted RIDs.

---

# Phase 14 — Cost-Based Optimizer  (M2, `part2.md` §5)
- **Branch:** `phase-14-optimizer` · **Depends on:** P4, P13
- **Goal:** AST → cheapest Physical Plan using selectivity + a cost model.

**Sub-phases:**
- **14.1** Physical plan node types (SeqScan, IndexScan, Filter, Projection, NestedLoopJoin, HashJoin, Sort, Limit) with `estimatedCost/estimatedRows`.
- **14.2** Selectivity estimation from catalog stats (`part2.md` §5.4).
- **14.3** Cost model constants + per-operator cost formulas (§5.5).
- **14.4** Single-table plan selection (seq vs index) + predicate pushdown into scans.
- **14.5** Join ordering (enumerate ≤4 tables, greedy for 5+) building left-deep trees; per-join Hash vs NLJ choice.

**API:** `Optimizer.optimize(Statement) → PhysicalPlan`.
**Tests:** low-selectivity predicate picks index scan, high-selectivity picks seq scan; pushdown places filter in scan; join order prefers smaller outer; cost monotonicity sanity checks.
**DoD:** plans are valid and the cheaper access path is chosen at the documented crossover.

---

# Phase 15 — Execution Engine  (M2, `part2.md` §6)
- **Branch:** `phase-15-execution` · **Depends on:** P10, P14
- **Goal:** run a Physical Plan with the Volcano (open/next/close) iterator model and execute writes through MVCC.

**Sub-phases:**
- **15.1** `Operator` interface (`open(ctx)/next()/close()`) + `ExecutionContext` (transaction, mvccManager, catalog, heapFileRegistry, indexRegistry).
- **15.2** `SeqScanOperator` — calls `mvccManager.getVisibleVersion` for **every** tuple, decodes via TupleCodec, applies pushed predicate.
- **15.3** `IndexScanOperator` — drives `BPlusTree.rangeScan`, visibility check, post-filter.
- **15.4** Filter, Projection operators + `ExpressionEvaluator` (ColumnRef qualified/unqualified, Literal, BinaryOp, UnaryOp).
- **15.5** Joins: NestedLoopJoin, HashJoin (build/probe).
- **15.6** Sort, Limit, and minimal Aggregation (`COUNT(*)`, `COUNT(col)`).
- **15.7** INSERT/DELETE/UPDATE execution → `mvccManager.{insert,delete,update}`; CREATE TABLE/INDEX → Catalog + bulk B+Tree build.
- **15.8** `QueryExecutor.execute(sql, transaction)` top-level entry: parse → optimize → build operators → drive iterator → return rows.

**API:** `QueryExecutor.execute(sql, txn) → ResultSet`.
**Tests:** pipelining (`LIMIT 1` reads minimal pages); every scan respects visibility; NLJ vs HashJoin produce identical results; projection/aggregate correctness; INSERT then SELECT within and across transactions.
**DoD:** SELECT/INSERT/DELETE/UPDATE/CREATE run end-to-end against real storage + MVCC.

---

# Phase 16 — Integration & End-to-End
- **Branch:** `phase-16-integration` · **Depends on:** P6, P11, P15
- **Goal:** assemble the full database, a CLI/REPL, durability + concurrency end-to-end tests.

**Sub-phases:**
- **16.1** `tools/MiniDb` bootstrap: open DiskManager → BufferPool → WAL → run RecoveryManager.recover() → load Catalog → start Vacuum thread.
- **16.2** REPL/CLI: read SQL, begin/commit per statement (autocommit) or explicit `BEGIN/COMMIT/ABORT`.
- **16.3** End-to-end SQL tests: create/insert/select/update/delete/join/index across sessions.
- **16.4** Durability E2E: write, simulate crash (drop buffer pool), reopen, assert committed data present and uncommitted gone.
- **16.5** Concurrency E2E: the `part3.md` §14 scenarios driven through the full stack.

**DoD:** a fresh checkout builds, recovers cleanly, runs the SQL test suite green, and passes durability + concurrency scenarios.

---

## Phase completion checklist (apply to every phase)

- [ ] All sub-phase commits merged on the phase branch; build green (`mvn test`).
- [ ] Component tests cover the listed cases.
- [ ] Public API matches the signatures other members depend on (per the design docs); any change flagged in `CHANGES.md`.
- [ ] `CHANGES.md` updated for each sub-phase.
- [ ] No attribution leak anywhere in git (see `CLAUDE.md` §5).
- [ ] PR opened for the phase.
