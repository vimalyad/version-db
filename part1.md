# MiniDB — Member 1: Storage & Recovery
## Architecture & Design Document

---

## 1. Your Role in the System

You are the **foundation**. Every single byte that exists in this database — whether it is being inserted, queried, or recovered after a crash — passes through your layer. The other two members depend entirely on the abstractions you provide. If your layer is incorrect, nothing above it can be correct either.

You own five components. They form a strict vertical dependency stack — each layer sits on top of the one below it:

```
┌─────────────────────────────────────┐
│         Recovery Manager            │  ← sits on top of WAL + Buffer Pool
├─────────────────────────────────────┤
│           WAL Manager               │  ← sits on top of Buffer Pool
├─────────────────────────────────────┤
│           Buffer Pool               │  ← sits on top of Page + DiskManager
├─────────────────────────────────────┤
│    Heap File    │    Catalog         │  ← sits on top of Buffer Pool
├─────────────────────────────────────┤
│    Page Manager + DiskManager       │  ← lowest level, touches disk directly
└─────────────────────────────────────┘
```

Build and fully test each layer before starting the one above it. No layer skips another.

---

## 2. System Context — Where Your Layer Fits

The full MiniDB architecture:

```
         User SQL
            │
      [Member 2: Parser]
            │
      [Member 2: Optimizer]
            │
      [Member 2: Execution Engine]
         │        │
         │        └──► [Member 3: MVCC Manager]
         │                      │
         └──────────────────────┼──► [YOUR: Heap File]
                                │         │
                                │    [YOUR: Buffer Pool]
                                │         │
                                │    [YOUR: DiskManager]
                                │
                           [YOUR: WAL Manager]
                                │
                           [YOUR: Recovery Manager]
```

Every read of a tuple goes: Execution Engine → Heap File → Buffer Pool → Disk (if not cached).
Every write of a tuple goes: MVCC Manager → Heap File → Buffer Pool → WAL Manager → Disk.

---

## 3. Component 1 — Page Manager & DiskManager

### 3.1 Purpose

The Page and DiskManager are the absolute lowest layer. Their job is to define what a "page" looks like and how to read/write it from disk. All concepts above this layer think in terms of pages, never in terms of raw bytes or file offsets.

### 3.2 What a Page Is

A **page** is a fixed-size block of bytes — the atomic unit of I/O. You never read half a page from disk. You never write half a page. The page size is a system constant (4096 bytes or 8192 bytes — decide once and never change). Every page has a globally unique `page_id` which is its position in the database file.

A page has two regions:

**Page Header** — fixed-size prefix, always at the start of the page. Contains:
- `pageId` — which page this is
- `lsn` — the Log Sequence Number of the most recent WAL record that modified this page. This is the critical link between your storage layer and your WAL layer.
- `freeSpaceStart` — byte offset where the slot array ends and free space begins
- `freeSpaceEnd` — byte offset where tuple data starts (tuples grow backward from the end)
- `numSlots` — how many slot entries currently exist
- `nextPageId` — the page ID of the next page in the heap file's linked list (-1 if last page)
- `checksum` — a CRC32 of the page body to detect corruption on read

**Page Body** — everything after the header. Has three sub-regions:

```
┌────────────────────────────────────────────────────────────┐
│  HEADER  (fixed size, always at front)                      │
├──────────────────────┬─────────────────────────────────────┤
│  SLOT ARRAY          │                                      │
│  [slot 0][slot 1]... │     F R E E   S P A C E             │
│  grows →             │                        ← grows      │
│                      │                     [tuple 1]        │
│                      │                     [tuple 0]        │
└──────────────────────┴─────────────────────────────────────┘
```

The slot array grows rightward from `freeSpaceStart`. Tuple data grows leftward from `freeSpaceEnd`. Free space is always one contiguous block in between.

**Why this layout?** Slots are fixed-size (each slot is just two numbers: offset + length). Because they are fixed-size and indexed by position, finding a tuple by its slot number is O(1) — just compute the slot's position in the array. Tuples are variable-size and grow from the back so free space stays contiguous and inserts never require moving existing data.

### 3.3 Slot Array Design

Each slot entry contains exactly two numbers:
- `offset` — byte offset within the page where the tuple's data begins
- `length` — number of bytes the tuple occupies

If `offset` is 0, the slot is a **tombstone** — the tuple at this slot has been deleted. The data bytes are still physically present on the page but logically invisible. This is important for MVCC: old versions of tuples must remain accessible through version chains, so you must not physically reclaim their space until garbage collection.

The **slot ID** is just the index into the slot array. A `(pageId, slotId)` pair uniquely identifies any tuple in the database — this pair is called a **RID (Record ID)** and is the shared type used across all three members.

### 3.4 Page Operations

A page must support:
- **insertTuple(bytes)** — find space at the end, write tuple bytes, add a slot entry, update `freeSpaceEnd`, return the slot ID
- **getTuple(slotId)** — read the slot entry, return the bytes at that offset, return null if tombstone
- **deleteTuple(slotId)** — set the slot's offset to 0 (tombstone), do nothing else
- **getFreeSpace()** — compute `freeSpaceEnd - freeSpaceStart - slotEntrySize`. The `-slotEntrySize` accounts for the slot entry the next insert would need to add.
- **setLsn(lsn)** — update the LSN in the header. Called every time the page is modified.
- **serialize()** — convert the full page to a byte array for disk writing. Recompute checksum here.
- **deserialize(bytes)** — parse a byte array from disk into a Page object. Verify checksum here.

### 3.5 DiskManager Design

The DiskManager owns exactly one file on disk: the database file. Its layout:

```
Database file on disk:
┌──────────────────┬────────────┬────────────┬────────────┬─────
│  File Header     │   Page 0   │   Page 1   │   Page 2   │ ...
│  (total pages)   │ (4096 B)   │ (4096 B)   │ (4096 B)   │
└──────────────────┴────────────┴────────────┴────────────┴─────
```

The byte offset of page N is simply: `fileHeaderSize + N × PAGE_SIZE`.

The DiskManager is responsible for:
- **readPage(pageId)** — seek to offset, read PAGE_SIZE bytes, return a deserialized Page
- **writePage(pageId, page)** — serialize page to bytes, seek to offset, write. This should call `flush()` (or `fsync`) to ensure bytes reach the OS buffer.
- **allocatePage()** — increment the page count in the file header, write an empty page at the new offset, return the new pageId.

**Critical rule**: Only the Buffer Pool may call `writePage`. No other component touches DiskManager directly.

### 3.6 Checksum and Corruption Detection

When writing a page, compute a CRC32 over the page body (everything after the header), store it in the header's checksum field, then write. When reading a page, recompute the CRC32 and compare it against the stored value. A mismatch means the page is corrupted — throw an IOException. This catches hardware errors, partial writes from unclean shutdowns, and file system bugs.

---

## 4. Component 2 — Buffer Pool

### 4.1 Purpose

The Buffer Pool is the most important component you build. It is an in-memory cache of pages. Its fundamental rule is: **no component reads from or writes to disk except through the Buffer Pool**. This single constraint is what makes WAL correctness enforceable.

### 4.2 Structure

The buffer pool is a fixed-size array of **frames**. Each frame can hold exactly one page. The number of frames is the pool capacity (e.g., 1024 frames = 1024 pages in memory at once).

Each frame tracks:
- `pageId` — which page is currently loaded into this frame. -1 if the frame is empty.
- `page` — the actual Page object
- `pinCount` — how many threads are currently using this page. A page with pinCount > 0 cannot be evicted.
- `isDirty` — true if the page has been modified since it was last written to disk
- `refBit` — a single boolean used by the eviction algorithm (Clock)

Additionally, the Buffer Pool maintains a **page table**: a hash map from `pageId → frameIndex`. This allows O(1) lookup of whether a page is currently in the pool.

### 4.3 Core Operations

**fetchPage(pageId)**
1. Check the page table. If the page is already in the pool, increment its `pinCount`, set `refBit = true`, return the page.
2. If not in the pool, call `evict()` to find a free frame.
3. Call `diskManager.readPage(pageId)`.
4. Load the page into the evicted frame. Set `pinCount = 1`, `isDirty = false`, `refBit = true`.
5. Update the page table. Return the page.

**unpin(pageId, isDirty)**
1. Decrement `pinCount` for that frame.
2. If `isDirty` is true, mark the frame dirty.
3. Never evict a page here — eviction only happens when a new page is needed.

**markDirty(pageId)**
Set the frame's `isDirty = true`. Called by anyone who modifies a page.

**flushPage(pageId)**
1. If the frame is not dirty, return immediately.
2. **WAL Rule**: call `walManager.flushToLsn(page.lsn)` first. This ensures all WAL records describing modifications to this page are on disk before the page itself is written.
3. Call `diskManager.writePage(pageId, page)`.
4. Set `isDirty = false`.

**flushAll()**
Call `flushPage` for every dirty frame. Used by checkpointing and clean shutdown.

**newPage()**
Call `diskManager.allocatePage()` to get a new pageId, then call `fetchPage` behavior to bring it into the pool. Returns pinned.

### 4.4 Eviction — Clock Algorithm

When `fetchPage` needs a frame but the pool is full, it must evict a page.

The Clock algorithm maintains a circular `clockHand` pointer that sweeps through the frame array. At each step:
1. If the frame's `pinCount > 0`: skip it (cannot evict pinned pages)
2. If the frame's `refBit == true`: set `refBit = false` and advance (second chance)
3. If the frame's `refBit == false`: evict this frame

When a frame is evicted:
- If `isDirty`, call `flushPage` first (which enforces the WAL rule)
- Remove the frame's entry from the page table
- The frame is now free for the new page

**Why Clock over LRU?** True LRU requires maintaining a sorted access order — O(log n) per access. Clock approximates LRU in O(1) by using the single `refBit`. Every access sets `refBit = true`. The clock hand clears bits on each sweep, so frequently accessed pages keep getting their bit set before eviction.

### 4.5 The WAL Rule — Most Important Invariant

> **Before any dirty page is written to disk, all WAL records with LSN ≤ page.lsn must already be on disk.**

This is enforced in `flushPage`. Here is why it matters: if a page is written to disk before its WAL records, a crash could leave the database in a state where the data file shows a change but the WAL has no record of it. Recovery would have no way to redo or undo that change. The page's LSN is the bridge — it tells the buffer pool exactly how far WAL must be flushed.

### 4.6 Thread Safety

The buffer pool will be accessed by concurrent transactions. Use a single ReentrantLock to protect all operations on the page table and frame array. Be careful not to hold the lock while doing disk I/O — that would serialize all I/O unnecessarily. The pattern is: acquire lock → find/evict frame → release lock → do I/O → acquire lock again → update page table.

---

## 5. Component 3 — Heap File

### 5.1 Purpose

A Heap File is an unordered collection of pages that stores all tuples for one table. "Heap" means no particular ordering — tuples are stored wherever there is space. Each table in the database is one heap file.

### 5.2 Structure

Pages in a heap file are organized as a **singly-linked list**. Each page's header contains a `nextPageId` field that points to the next page. The last page has `nextPageId = -1`. The heap file object knows the `firstPageId` of its table's page chain.

```
Heap File for table "users":
firstPageId = 5

Page 5 ──nextPageId──► Page 12 ──nextPageId──► Page 19 ──nextPageId──► -1
[slots][tuples]         [slots][tuples]          [slots][tuples]
```

### 5.3 Free Space Map

Scanning all pages to find one with enough space for each insert would be O(n) and very slow. The heap file maintains a **Free Space Map (FSM)** — an in-memory map from `pageId → freeBytes`. On startup, this is rebuilt by scanning all pages once. On every insert and delete, it is updated.

When inserting, the FSM finds a page with enough free bytes. If none exists, a new page is allocated and added to both the linked list and the FSM.

### 5.4 Operations

**insertTuple(bytes)**
1. Ask FSM for a page with at least `len(bytes) + slotEntrySize` free bytes.
2. If none found, allocate a new page via `bufferPool.newPage()`, link it to the chain, add to FSM.
3. `fetchPage` from buffer pool, call `page.insertTuple(bytes)`, get back the `slotId`.
4. Mark the page dirty, unpin.
5. Update FSM with new free space for that page.
6. Return `RID(pageId, slotId)`.

**getTuple(rid)**
1. `fetchPage(rid.pageId)`.
2. Call `page.getTuple(rid.slotId)`.
3. Unpin. Return bytes (or null if deleted).

**deleteTuple(rid)**
1. `fetchPage(rid.pageId)`.
2. Call `page.deleteTuple(rid.slotId)` — sets the tombstone.
3. Mark dirty, unpin.

**scan()**
Walk the page chain from `firstPageId`. For each page, iterate through all slot IDs from 0 to `numSlots - 1`. For each slot, call `getTuple`. Yield `(RID, bytes)` for every non-null result. This is the full table scan that Member 2's execution engine calls.

### 5.5 Tuple Serialization Format

Raw bytes on disk must encode typed values. Define a binary format:
- A **null bitmap** at the front — one bit per column, 1 = null, 0 = value present
- Then the column values in schema order:
  - `INT` (64-bit): 8 bytes, big-endian signed integer
  - `FLOAT` (64-bit): 8 bytes, IEEE 754 double
  - `VARCHAR`: 4-byte length prefix followed by UTF-8 encoded bytes
  - `BOOL`: 1 byte, 0x00 or 0x01

This format is deterministic and self-describing enough for recovery and MVCC to work correctly.

---

## 6. Component 4 — Catalog

### 6.1 Purpose

The Catalog is the database's metadata store — it answers questions like "what tables exist?", "what columns does this table have?", "what indexes exist on this table?", and "what are the statistics for the optimizer?". It is the system's data dictionary.

### 6.2 Structure

The Catalog is itself stored as three special heap files at fixed, well-known page IDs:
- **Tables heap** (page 0): stores one record per table
- **Columns heap** (page 1): stores one record per column
- **Indexes heap** (page 2): stores one record per index

On startup, the catalog reads these three heaps into in-memory data structures (maps/lists) for fast lookup. All queries against the catalog are served from memory.

### 6.3 Metadata Records

**Table record** stores: `tableId`, `tableName`, `firstPageId`, `numTuples`, `numPages`

**Column record** stores: `tableId`, `columnName`, `columnType`, `columnIndex` (position in row, 0-based), `isNullable`

**Index record** stores: `indexId`, `tableId`, `columnName`, `rootPageId` (the B+Tree root, owned by Member 2)

### 6.4 Statistics

The Optimizer (Member 2) needs statistics to estimate query costs. The Catalog stores per-table and per-column statistics:

- **Table-level**: `numTuples`, `numPages`
- **Column-level**: `numDistinct` (number of unique values), `minValue`, `maxValue`, `nullCount`

Statistics are not updated on every insert/delete — that would be too expensive. Instead, the execution engine periodically calls `updateStats` to refresh them. For the capstone, updating stats after each CREATE INDEX and after every 1000 inserts is sufficient.

### 6.5 Operations

- `createTable(name, columns)` — allocate a new page for the heap file, write records to the tables and columns heaps, return TableMeta
- `getTable(name)` — look up in-memory cache, return TableMeta
- `getColumns(tableId)` — return sorted list of ColumnMeta
- `getIndexes(tableId)` — return list of IndexMeta
- `registerIndex(tableId, columnName, rootPageId)` — write to indexes heap, update cache
- `getStats(tableName)` — return TableStats (used by optimizer)
- `updateStats(tableId, stats)` — update in-memory stats cache

---

## 7. Component 5 — WAL Manager

### 7.1 Purpose

The WAL Manager is responsible for **durability** and **atomicity**. It implements Write-Ahead Logging — a technique where every modification to the database is first recorded in a sequential log file before the modification takes effect on any data page. This log is the foundation of crash recovery.

### 7.2 The WAL File

The WAL is a separate file on disk: `wal.log`. It is append-only. Records are written sequentially. The file is never rewritten — only extended. Each record has a monotonically increasing **Log Sequence Number (LSN)** which uniquely identifies it and gives its position in the log.

### 7.3 Log Record Types

Every log record has a common header and a type-specific body:

**Common header fields** (present in every record):
- `lsn` — this record's unique sequence number
- `prevLsn` — the LSN of the previous record written by the same transaction. Creates a backward-linked chain through the log that Recovery uses to undo a transaction.
- `transactionId` — which transaction wrote this record
- `logType` — one of: BEGIN, INSERT, DELETE, COMMIT, ABORT, CHECKPOINT, CLR

**Type-specific bodies:**

| Type | Additional Fields | Purpose |
|------|-------------------|---------|
| BEGIN | none | Marks transaction start |
| INSERT | pageId, slotId, tupleData (the inserted bytes) | Redo: re-insert. Undo: delete. |
| DELETE | pageId, slotId, oldData (the deleted bytes) | Redo: re-delete. Undo: re-insert. |
| COMMIT | none | Transaction committed successfully |
| ABORT | none | Transaction was rolled back |
| CHECKPOINT | active transaction table, dirty page table | Recovery starting point |
| CLR | pageId, slotId, undoNextLsn, data | Written during undo. Makes undo idempotent. |

The **CLR (Compensation Log Record)** deserves special attention. When Recovery undoes a DELETE by reinserting the old data, it writes a CLR to record that it did so. The CLR's `undoNextLsn` field skips back past the record that was just undone — so if the system crashes again mid-recovery, it knows where to resume undoing without double-undoing anything.

### 7.4 Log Record Serialization

Each record is stored on disk as:
- 4-byte total length prefix (so the reader knows how many bytes to read)
- Common header fields (fixed size)
- Body fields (variable size, depending on type)

The length prefix is critical — it allows the WAL reader to scan forward through the file even if record sizes vary.

### 7.5 WAL Manager State

In memory, the WAL Manager maintains:
- `currentLsn` — the next LSN to be assigned. Incremented atomically.
- `flushedLsn` — the highest LSN that has been flushed to disk. All records with LSN ≤ flushedLsn are guaranteed durable.
- `logBuffer` — an in-memory buffer of encoded log records not yet written to disk. This batches disk writes for efficiency.
- `txnLastLsn` — a map from `transactionId → lastLsn`. Tracks the most recent LSN for each active transaction, used to set `prevLsn` on the next record from that transaction.

### 7.6 WAL Manager Operations

**logBegin(txnId)** — append a BEGIN record, return LSN

**logInsert(txnId, pageId, slotId, tupleData)** — append an INSERT record with `prevLsn` from `txnLastLsn[txnId]`, update `txnLastLsn`, return LSN

**logDelete(txnId, pageId, slotId, oldData)** — same pattern as INSERT, return LSN

**logCommit(txnId)** — append COMMIT record, then **synchronously flush to disk** (call `flushToLsn(commitLsn)`). A transaction is only considered committed once its COMMIT record is on disk.

**logAbort(txnId)** — append ABORT record, synchronously flush.

**logClr(txnId, pageId, slotId, undoNextLsn, data)** — append a CLR, return LSN. Called by Recovery Manager during undo.

**logCheckpoint(activeTxnTable, dirtyPageTable)** — append CHECKPOINT record, synchronously flush.

**flushToLsn(targetLsn)** — write all buffered log records with LSN ≤ targetLsn to disk. Update `flushedLsn`. Called by: Buffer Pool (before evicting dirty pages), and on COMMIT/ABORT.

**readLog(startLsn)** — open the WAL file and return an iterator over all records with LSN ≥ startLsn. Used exclusively by Recovery Manager.

**readRecordAtLsn(lsn)** — return the specific record at a given LSN. Used by Recovery Manager's undo phase to fetch individual records.

### 7.7 Log Buffer and Flush Strategy

Writing to disk on every log append would be extremely slow. Instead, records are buffered in memory and flushed in batches. There are three events that force a flush:
1. COMMIT or ABORT (for durability — a committed transaction must be durable)
2. Buffer Pool evicting a dirty page (the WAL rule)
3. Buffer size exceeds a threshold (e.g., 64KB) to bound memory usage

---

## 8. Component 6 — Recovery Manager

### 8.1 Purpose

The Recovery Manager runs automatically on database startup. It detects whether the previous shutdown was clean. If not (i.e., a crash occurred), it reads the WAL and restores the database to a consistent state — as if the crash never happened for committed transactions, and as if aborted/incomplete transactions never ran at all.

### 8.2 The ARIES Algorithm

MiniDB implements **ARIES (Algorithm for Recovery and Isolation Exploiting Semantics)**, the standard crash recovery algorithm used by PostgreSQL, DB2, SQL Server, and most production databases.

ARIES has three phases:

```
CRASH
  │
  ▼
Phase 1: ANALYSIS
  ─ Scan WAL forward from last checkpoint
  ─ Rebuild: which transactions were active at crash?
  ─ Rebuild: which pages were dirty (had unflushed changes)?
  ─ Outputs: ATT (Active Transaction Table), DPT (Dirty Page Table)
  │
  ▼
Phase 2: REDO
  ─ Scan WAL forward from the earliest dirty page's first-modification LSN
  ─ Repeat ALL changes — even from aborted transactions ("repeat history")
  ─ Skip records already applied (page.lsn >= record.lsn)
  ─ Restores the database to the exact state it was in at the moment of crash
  │
  ▼
Phase 3: UNDO
  ─ Walk backward through WAL for each transaction still in ATT
  ─ Undo all operations of uncommitted transactions
  ─ Write CLR records as each operation is undone
  ─ Database is now consistent: committed data intact, uncommitted data removed
```

### 8.3 Phase 1 — Analysis

The Analysis phase starts from the last checkpoint LSN (found by scanning the log to find the most recent CHECKPOINT record). It scans forward to the end of the log, updating two tables:

**Active Transaction Table (ATT)**: maps `transactionId → lastLsn`. Initially populated from the CHECKPOINT record's embedded ATT. Updated as:
- BEGIN record: add `txnId → record.lsn`
- INSERT/DELETE/CLR record: update `txnId → record.lsn`
- COMMIT or ABORT record: remove `txnId` from ATT

At the end of Analysis, ATT contains every transaction that was in-progress at the time of the crash. These are the transactions that need to be undone.

**Dirty Page Table (DPT)**: maps `pageId → recLsn`. `recLsn` is the LSN of the first log record that dirtied this page since the last checkpoint. Initially populated from the CHECKPOINT record's embedded DPT. Updated as:
- INSERT/DELETE/CLR record: if `pageId` not already in DPT, add it with `recLsn = record.lsn`
- Pages are never removed from DPT during Analysis (removal happens by inspection during REDO)

At the end of Analysis, DPT contains every page that may have had unflushed changes at crash time. The minimum `recLsn` across all DPT entries is where REDO must start.

### 8.4 Phase 2 — REDO

REDO starts from `min(DPT.recLsn)` and scans forward to the end of the WAL. For each INSERT, DELETE, or CLR record:

**Skip conditions** (do not redo if any of these apply):
1. The record's `pageId` is not in the DPT (the page was clean at crash, no redo needed)
2. The record's `lsn < DPT[pageId].recLsn` (the change was on disk before the crash)
3. After fetching the page: `page.lsn >= record.lsn` (the change is already on disk)

If none of the skip conditions apply, **apply the operation**:
- INSERT record: write the tuple bytes into the page at the specified slot
- DELETE record: mark the specified slot as a tombstone
- CLR record: apply its described undo operation

After applying, set `page.lsn = record.lsn` and mark dirty.

**Why redo even aborted transactions?** Because REDO "repeats history." The crash happened at a specific point in time. We recreate the exact state of the database at that point, including the in-progress (not yet committed) work. UNDO then cleanly removes the uncommitted work. This separation keeps the algorithm correct even if the system crashes again during recovery itself.

### 8.5 Phase 3 — UNDO

UNDO processes all transactions remaining in ATT (from Analysis) — these never committed. It must undo their operations in **reverse chronological order** (highest LSN first).

The algorithm maintains a priority queue (max-heap by LSN) initialized with one entry per ATT transaction: `(txnId, lastLsn)`. At each step:

1. Pop the highest LSN entry from the heap
2. Fetch the log record at that LSN
3. Based on type:
   - **INSERT record**: undo by deleting the tuple. Write a CLR with `undoNextLsn = record.prevLsn`. Apply the delete to the page.
   - **DELETE record**: undo by reinserting the old data. Write a CLR with `undoNextLsn = record.prevLsn`. Apply the insert to the page.
   - **CLR record**: skip the undo (CLRs are never undone). Push `(txnId, clr.undoNextLsn)` to the heap.
   - **BEGIN record**: this transaction is fully undone. Write an ABORT log record.
4. Push `(txnId, record.prevLsn)` to the heap so the previous operation of this transaction is processed next.

**Why the CLR's `undoNextLsn` matters**: If the system crashes again during UNDO, Recovery restarts. It will REDO the CLRs that were already written (restoring the partial undo state). When it reaches UNDO phase again, the CLRs' `undoNextLsn` fields tell it to skip past already-undone records. Without CLRs, it would undo the same operations twice.

### 8.6 Checkpointing

Checkpoints limit how far back the WAL must be replayed on recovery. Without checkpoints, after running for a week, recovery would replay a week's worth of WAL records.

The checkpointing process:
1. Flush all dirty pages from the buffer pool to disk
2. Capture the current ATT (all in-progress transactions and their last LSNs)
3. Capture the current DPT (all dirty pages and their recLsns)
4. Write a CHECKPOINT log record containing the captured ATT and DPT
5. Flush the CHECKPOINT record to disk

After a checkpoint, any WAL records before the oldest `recLsn` in the DPT are no longer needed for recovery. They can eventually be archived or deleted (log truncation — optional for the capstone).

For the capstone, write a checkpoint at startup after recovery completes, and every 10,000 WAL records thereafter.

---

## 9. Shared Types — Defined by You, Used by Everyone

These types must be defined in a shared package (`minidb.shared`) and imported by all three members:

**RID** — Record ID: `(pageId, slotId)`. The permanent physical address of any tuple. B+Tree leaf nodes store RIDs. MVCC version chains reference RIDs. The execution engine returns RIDs.

**TableMeta** — `tableId, tableName, firstPageId, numTuples, numPages`

**ColumnMeta** — `tableId, columnName, columnType, columnIndex, isNullable`

**IndexMeta** — `indexId, tableId, columnName, rootPageId`

**TableStats** — `numTuples, numPages, Map<columnName, ColumnStats>`

**ColumnStats** — `numDistinct, minValue, maxValue, nullCount`

**ColumnDef** — `name, type, nullable`. Used for CREATE TABLE.

---

## 10. Interfaces You Expose to Other Members

These are the method signatures other members will call. Agree on these with the team before anyone starts implementing.

**For Member 2 (Execution Engine):**

| Method | Purpose |
|--------|---------|
| `HeapFile.scan()` | Full table scan. Returns iterator of (RID, byte[]) |
| `HeapFile.getTuple(rid)` | Fetch one tuple by RID |
| `Catalog.getTable(name)` | Get table metadata |
| `Catalog.getColumns(tableId)` | Get column schema |
| `Catalog.getStats(tableName)` | Get optimizer statistics |
| `Catalog.getIndexes(tableId)` | Get available indexes |
| `BufferPool.fetchPage(pageId)` | For B+Tree node access |
| `BufferPool.unpin(pageId, dirty)` | Release page after B+Tree access |
| `BufferPool.newPage()` | Allocate new B+Tree node |

**For Member 3 (MVCC / Transaction Manager):**

| Method | Purpose |
|--------|---------|
| `HeapFile.insertTuple(bytes)` | Raw insert, returns RID |
| `HeapFile.deleteTuple(rid)` | Mark slot as tombstone |
| `WALManager.logBegin(txnId)` | Log transaction start |
| `WALManager.logInsert(txnId, pageId, slotId, data)` | Log an insert |
| `WALManager.logDelete(txnId, pageId, slotId, oldData)` | Log a delete |
| `WALManager.logCommit(txnId)` | Log commit, synchronous flush |
| `WALManager.logAbort(txnId)` | Log abort, synchronous flush |
| `WALManager.flushToLsn(lsn)` | Force WAL to disk up to LSN |
| `BufferPool.markDirty(pageId)` | Mark a page as modified |
| `Page.setLsn(lsn)` | Update page's LSN after write |

---