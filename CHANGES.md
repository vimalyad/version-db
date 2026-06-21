# MiniDB — Change Log

This file tracks **every change** made to the project, sub-phase by sub-phase. It is the source of truth for "what is already built." Any session must read this before starting work, and update it in the **same commit** as the code it describes.

How to use it:
- Phases and sub-phases are defined in `implementation.md`.
- When you complete a sub-phase, add an entry under that phase with: date, sub-phase id, one-line summary, and the files touched.
- Mark a phase's status in the **Status board** below: `not started` → `in progress` → `done`.
- Note any deviation from `implementation.md` (changed API, design decision, deferred item) under **Decisions & deviations**.

> Reminder: this file, `CLAUDE.md`, and `implementation.md` are temporary coordination files and will be removed from git history before final submission. Do not reference any AI tooling in this file or anywhere in git (see `CLAUDE.md` §5).

---

## Status board

| Phase | Branch | Owner | Status |
|---|---|---|---|
| 0 — Foundation | `phase-00-foundation` | shared | done |
| 1 — Page & DiskManager | `phase-01-storage-page` | M1 | done |
| 2 — Buffer Pool | `phase-02-bufferpool` | M1 | done |
| 3 — Heap File | `phase-03-heapfile` | M1 | in progress |
| 4 — Catalog | `phase-04-catalog` | M1 | not started |
| 5 — WAL Manager | `phase-05-wal` | M1 | not started |
| 6 — Recovery (ARIES) | `phase-06-recovery` | M1 | not started |
| 7 — Commit Log | `phase-07-commitlog` | M3 | not started |
| 8 — Transaction Manager | `phase-08-txnmgr` | M3 | not started |
| 9 — Snapshot Manager | `phase-09-snapshot` | M3 | not started |
| 10 — MVCC Manager | `phase-10-mvcc` | M3 | not started |
| 11 — Vacuum / GC | `phase-11-vacuum` | M3 | not started |
| 12 — SQL Parser | `phase-12-parser` | M2 | not started |
| 13 — B+Tree Index | `phase-13-btree` | M2 | not started |
| 14 — Optimizer | `phase-14-optimizer` | M2 | not started |
| 15 — Execution Engine | `phase-15-execution` | M2 | not started |
| 16 — Integration & E2E | `phase-16-integration` | all | not started |

---

## Decisions & deviations

Record cross-cutting decisions here as they are made. Seeded with the ones already fixed in the plan:

- **Language/build:** Java 17, Maven, JUnit 5.
- **PAGE_SIZE:** fixed at **8192** bytes (`Constants.PAGE_SIZE`) in sub-phase 0.2. Matches PostgreSQL's default page size; never change for an existing database file.
- **Version storage:** MVCC uses **Option B — separate Version Store** (heap stays MVCC-agnostic), per `part3.md` §6.2 and `implementation.md` Phase 10.
- **Isolation default:** Snapshot Isolation (REPEATABLE READ semantics); READ COMMITTED via per-statement snapshots. SSI is out of scope.

---

## Change history

Newest entries at the top. Format per entry:

```
### Phase N — <name>  (branch: phase-NN-...)
- [YYYY-MM-DD] N.k — <summary>. Files: <paths>.
```

### Project setup  (no branch / planning)
- [2026-06-21] Added architecture design docs `part1.md`, `part2.md`, `part3.md` (Member 1 Storage & Recovery, Member 2 Query & Indexing, Member 3 Transactions & MVCC).
- [2026-06-21] Added coordination files: `CLAUDE.md` (project context + workflow + attribution rule), `implementation.md` (16-phase build plan), `CHANGES.md` (this file).

### Phase 0 — Foundation  (branch: phase-00-foundation)
- [2026-06-21] 0.1 — Maven project (Java 17 target, JUnit 5, surefire), package skeleton `com.minidb.{shared,storage,wal,txn,query}` via package-info, and a smoke test proving the harness runs. Files: `pom.xml`, `src/main/java/com/minidb/*/package-info.java`, `src/test/java/com/minidb/SmokeTest.java`.

- [2026-06-21] 0.2 — Shared `Constants` (PAGE_SIZE=8192, sentinel XID/page/slot ids) and the `RID` record (pageId, slotId) with value equality and validity check. Files: `shared/Constants.java`, `shared/RID.java`, `test/.../shared/RidTest.java`.

- [2026-06-21] 0.3 — `ColumnType` enum (INT/FLOAT/VARCHAR/BOOL), immutable `Value` (typed value or typed NULL with checked accessors), and `ColumnDef` record (name, type, nullable). Files: `shared/ColumnType.java`, `shared/Value.java`, `shared/ColumnDef.java`, `test/.../shared/ValueTest.java`.

- [2026-06-21] 0.4 — Metadata records: `TableMeta`, `ColumnMeta`, `IndexMeta`, `ColumnStats`, and `TableStats` (defensively-copied, unmodifiable column-stats map). Files: `shared/TableMeta.java`, `shared/ColumnMeta.java`, `shared/IndexMeta.java`, `shared/ColumnStats.java`, `shared/TableStats.java`, `test/.../shared/MetadataTest.java`.
- [2026-06-21] 0.5 — Exception hierarchy: `MiniDbException` (unchecked base) with `StorageException`, `ParseException`, `SerializationException`, `TransactionConflictException`. Files: `shared/MiniDbException.java` + the four subclasses, `test/.../shared/ExceptionTest.java`. **Phase 0 complete** (17 tests green).

### Phase 1 — Page & DiskManager  (branch: phase-01-storage-page)
- [2026-06-21] 1.1 — `storage/Page`: fixed 32-byte header (pageId, lsn, freeSpaceStart, freeSpaceEnd, numSlots, nextPageId, checksum) over a single PAGE_SIZE backing array; `create`, header accessors, `setLsn`/`setNextPageId`, `serialize`/`deserialize` with byte-for-byte round-trip. Files: `storage/Page.java`, `test/.../storage/PageTest.java`.
- [2026-06-21] 1.2 — Slotted tuple ops on `Page`: `insertTuple` (data grows backward, slot array forward), `getTuple` (null on tombstone), `deleteTuple` (offset-0 tombstone, bytes kept for MVCC), `getFreeSpace` (accounts for the next slot entry); out-of-range/overflow throw `StorageException`. Files: `storage/Page.java`, `test/.../storage/PageTest.java`.
- [2026-06-21] 1.3 — `storage/Crc32Util` and checksum wiring: `Page.serialize` recomputes a CRC32 over the page body (bytes after the header) into the checksum field; `Page.deserialize` recomputes and throws `StorageException` on mismatch to detect corruption. Files: `storage/Crc32Util.java`, `storage/Page.java`, `test/.../storage/PageTest.java`.
- [2026-06-21] 1.4 — `storage/DiskManager`: owns the database file (one-page file header storing the total page count, page N at offset `PAGE_SIZE+(N*PAGE_SIZE)`); `readPage`, `writePage` (with fsync), `allocatePage` (writes an empty initialised page), `getNumPages`, `close`. Page count and data persist across reopen; out-of-range access and on-disk corruption throw `StorageException`. Files: `storage/DiskManager.java`, `test/.../storage/DiskManagerTest.java`. **Phase 1 complete** (42 tests green).

<!-- Add real phase work below this line as it is implemented. -->

### Phase 2 — Buffer Pool  (branch: phase-02-bufferpool)
- [2026-06-21] 2.1 — `storage/Frame` (pageId, page, pinCount, isDirty, refBit) with all fields defaulting to empty-slot state; `storage/BufferPool` constructor: fixed-size `Frame[]` array and `HashMap<Integer,Integer>` page table; `storage/WalFlushCallback` functional interface for the WAL-rule hook (no-op stub until Phase 5). Files: `storage/Frame.java`, `storage/WalFlushCallback.java`, `storage/BufferPool.java`, `test/.../storage/BufferPoolTest.java`.
- [2026-06-21] 2.2 — `fetchPage(pageId)` (cache hit returns pinned page, miss reads from disk into first free frame), `unpin(pageId, dirty)`, `markDirty(pageId)`, `newPage()` (allocates via DiskManager, loads into free frame pinned). Eviction deferred to 2.3; full pool throws `StorageException`. Files: `storage/BufferPool.java`, `test/.../storage/BufferPoolTest.java`.
- [2026-06-21] 2.3 — Clock eviction: `evict()` sweeps the frame array with a `clockHand`, giving each unpinned frame with `refBit=true` one second chance (clear bit, continue); evicts the first unpinned frame with `refBit=false`. Dirty victim written to disk before eviction. `fetchPage` and `newPage` now call `evict()` on cache miss; all-pinned pool throws `StorageException`. Files: `storage/BufferPool.java`, `test/.../storage/BufferPoolTest.java`.
- [2026-06-21] 2.4 — `flushPage(pageId)` and `flushAll()` with the WAL rule: `walFlushCallback.flushToLsn(page.lsn)` is called before every dirty-page disk write in both `flushPage` and `evict()`'s dirty-victim path, via a shared `writeDirtyFrame(frame)` helper. `flushPage` is a no-op for clean pages; `flushAll` iterates all frames. Files: `storage/BufferPool.java`, `test/.../storage/BufferPoolTest.java`.
- [2026-06-21] 2.5 — Thread safety: single `ReentrantLock` guards all frame-array and page-table mutations; lock is released around every disk I/O (read in `fetchPage`/`newPage`, WAL+write in `writeDirtyFrame`). A dirty victim in `evict()` is temporarily pinned (`pinCount=1`) before the lock is released for I/O, preventing re-eviction by another thread. Concurrent smoke test passes. Files: `storage/BufferPool.java`, `test/.../storage/BufferPoolTest.java`. **Phase 2 complete** (62 tests green).

### Phase 3 — Heap File  (branch: phase-03-heapfile)
- [2026-06-21] 3.1 — `storage/HeapFile`: singly-linked page chain via each page's `nextPageId`. `create(bufferPool)` allocates the first page; `new HeapFile(bufferPool, firstPageId)` opens an existing heap and walks the chain to rebuild the in-memory page list; `getFirstPageId()` (persisted by the catalog) and `getPageCount()`. All page access via the buffer pool. Files: `storage/HeapFile.java`, `test/.../storage/HeapFileTest.java`.
- [2026-06-21] 3.2 — Free Space Map: `freeSpaceMap` (pageId → free bytes) built during the chain walk in `rebuild()` and queryable via the package-private `freeSpaceOf(pageId)`. Lets an insert choose a page in O(pages) without reading page contents. (Deletes tombstone a slot without reclaiming space, so free space is unchanged on delete.) Files: `storage/HeapFile.java`, `test/.../storage/HeapFileTest.java`.
