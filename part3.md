# MiniDB — Member 3: Transactions & MVCC

## Architecture & Design Document

---

## 1. Your Role in the System

You build the **concurrency layer**. You are responsible for ensuring that multiple transactions running simultaneously do not interfere with each other — they each see a consistent view of the data, and the database remains correct regardless of what order operations occur in.

You are also the layer that owns all write operations. When Member 2's execution engine wants to insert or delete a tuple, it calls your code. You decide how to record that change in a way that other transactions can see it correctly according to their own snapshots.

Your four components:

```
┌────────────────────────────────────────────────────────────┐
│              Transaction Manager                            │
│  Controls transaction lifecycle: begin, commit, abort      │
├────────────────────────────────────────────────────────────┤
│              Snapshot Manager                               │
│  Creates and stores consistent snapshots for each txn      │
├────────────────────────────────────────────────────────────┤
│              MVCC Manager                                   │
│  Manages version chains, performs visibility checks         │
├────────────────────────────────────────────────────────────┤
│              Commit Log                                     │
│  Persistent record of which transactions committed/aborted  │
└────────────────────────────────────────────────────────────┘
```

---

## 2. Why MVCC? — The Problem with Locking

### 2.1 The Fundamental Conflict

In a database with concurrent users, two types of operations must coexist:

- **Reads** — SELECT queries. They must see a consistent snapshot of data.
- **Writes** — INSERT, DELETE, UPDATE. They must not corrupt data.

The naive approach is **Two-Phase Locking (2PL)**:

- Reads acquire a shared lock. Multiple readers can hold shared locks simultaneously.
- Writes acquire an exclusive lock. Only one writer at a time, and writers block all readers.

This creates a fundamental problem:

```
T1: SELECT * FROM orders WHERE user_id = 5    ← acquires shared lock on orders
T2: DELETE FROM orders WHERE order_id = 99    ← waits for T1's lock
```

T2 is blocked even though it is deleting a completely different row. In OLTP systems where reads are frequent, this blocking causes severe throughput degradation.

### 2.2 MVCC's Solution

**Multi-Version Concurrency Control** eliminates reader-writer blocking entirely.

The core idea: **instead of modifying data in place, every write creates a new version of the data, leaving the old version intact**. Readers can always find a version that was current at the time their transaction started — without blocking writers, and without writers blocking them.

```
T1 starts with snapshot at time 10:
  Sees: all versions created before time 10

T2 deletes a row (time 11):
  Creates a new version marking the row deleted (xmax=T2's ID)
  Does NOT remove the old version

T1 continues reading:
  Still sees the old version (xmax > its snapshot time)
  T2's delete is invisible to T1 — as if it never happened
```

This is exactly what PostgreSQL implements, and it is what you are building.

---

## 3. Core Concept — Transaction IDs and Versions

### 3.1 Transaction IDs

Every transaction gets a unique, monotonically increasing **Transaction ID (XID)**. XIDs are assigned by the Transaction Manager when a transaction begins. They are used everywhere as the identifier for who did what.

Properties of XIDs:

- Strictly increasing: if T1 starts before T2, then `T1.xid < T2.xid`
- Never reused
- Stored persistently so recovery can reconstruct transaction states

### 3.2 Tuple Versions

In a standard database, a tuple has one version — its current value. In MVCC, every tuple can have **multiple versions**, each corresponding to a different point in time.

Each version carries two extra fields:

- **xmin** — the Transaction ID of the transaction that **created** this version (via INSERT or UPDATE)
- **xmax** — the Transaction ID of the transaction that **deleted or replaced** this version. If this version is still current, `xmax` is 0 (or a special "none" sentinel value).

These two fields together define the **lifespan** of a version:

- The version was created by transaction `xmin`
- The version was superseded (deleted or updated) by transaction `xmax`
- While `xmax = 0`, the version is still the current version

### 3.3 Version Chains

When a row is updated, MVCC does not modify the existing tuple. Instead:

1. A **new version** is created with the updated data and `xmin = current_txn_id`, `xmax = 0`
2. The **old version** is stamped with `xmax = current_txn_id`

The result is a chain of versions, newest to oldest:

```
Heap slot 5:
  Latest RID pointer ───► Version 3: {xmin=150, xmax=0,   data="Carol"}  ← current
                           prev ─────► Version 2: {xmin=100, xmax=150, data="Alice"}
                           prev ─────► Version 1: {xmin=50,  xmax=100, data="Bob"}
                           prev ─────► null
```

A reader traverses this chain, starting from the newest version, walking backward until it finds a version that was visible according to its snapshot.

---

## 4. Component 1 — Transaction Manager

### 4.1 Purpose

The Transaction Manager is the central coordinator. It is the first thing called when any transaction begins, and the last thing called when it ends. It maintains the global state of all active transactions and coordinates with all other components.

### 4.2 Global Transaction State

The Transaction Manager maintains in memory:

- **nextXid** — the next transaction ID to be assigned. Atomic, always incrementing.
- **activeTransactions** — a map from `xid → Transaction`. All transactions that have begun but not yet committed or aborted.
- **committedXids** — the set of all XIDs that have committed. This is the in-memory representation; the persistent version is the Commit Log.

### 4.3 Transaction Object

When a transaction begins, the Transaction Manager creates a **Transaction object** that travels with every operation:

- `xid` — this transaction's unique ID
- `snapshot` — the Snapshot captured at begin time (see Snapshot Manager)
- `status` — `IN_PROGRESS`, `COMMITTED`, or `ABORTED`
- `undoLog` — a list of `(RID, oldVersion)` pairs recording what this transaction changed, used if it needs to be rolled back. This is the in-memory undo log — recovery uses WAL for durability.
- `writeSet` — the set of RIDs this transaction has written. Used for conflict detection in serializable mode (optional).

### 4.4 Operations

**beginTransaction()**:

1. Atomically assign a new XID: `xid = nextXid++`
2. Ask the Snapshot Manager to create a snapshot for this XID
3. Ask the WAL Manager to log a BEGIN record
4. Create a Transaction object, add to `activeTransactions`
5. Return the Transaction object

**commitTransaction(txn)**:

1. Ask the WAL Manager to log a COMMIT record (this is synchronous — flushes to disk before returning)
2. Update the Commit Log: mark `txn.xid` as `COMMITTED`
3. Set `txn.status = COMMITTED`
4. Remove from `activeTransactions`
5. Release any resources held by this transaction

**abortTransaction(txn)**:

1. Use the in-memory undo log to reverse all changes this transaction made:
   - For each `(RID, oldVersion)` in `txn.undoLog` in reverse order:
     - Restore the old version by writing it back through the MVCC Manager
2. Ask the WAL Manager to log an ABORT record
3. Update the Commit Log: mark `txn.xid` as `ABORTED`
4. Set `txn.status = ABORTED`
5. Remove from `activeTransactions`

**isCommitted(xid)**:
Check the Commit Log. Return true if committed. This is called millions of times during visibility checks — it must be fast (O(1)).

**getOldestActiveXid()**:
Return the smallest XID in `activeTransactions`. This is the "horizon" — any version with `xmax < horizon` is invisible to all running transactions and can be garbage collected.

---

## 5. Component 2 — Snapshot Manager

### 5.1 Purpose

The Snapshot Manager creates and manages **snapshots** — a precise description of which transactions had committed at a specific point in time. A snapshot is taken when a transaction begins. The transaction's reads are based entirely on this snapshot — they never change, even if other transactions commit while the transaction is running.

This is the definition of **Snapshot Isolation**: a transaction sees the world as it was at the moment it started.

### 5.2 What a Snapshot Contains

A snapshot captures the exact state of transaction activity at one moment:

- **xmin** — the lowest active XID at snapshot time. All transactions with XID < xmin have already committed (or aborted). A version created by any of them may be visible.
- **xmax** — the value of `nextXid` at snapshot time. All transactions with XID ≥ xmax did not exist yet when this snapshot was taken. A version created by any of them is invisible.
- **inProgressXids** — the set of XIDs of all transactions that had started but not yet committed or aborted at snapshot time. Versions created by these transactions are invisible, even though their XIDs are less than xmax.

```
Snapshot taken at time T:

  xmin = 100  (transactions below 100 are all done)
  xmax = 200  (transactions from 200 onward didn't exist yet)
  inProgress = {105, 130, 175}  (these started but hadn't committed)

  Transactions 100..199 that are NOT in inProgress → committed → potentially visible
  Transactions 105, 130, 175 → in progress at snapshot time → invisible
  Transactions 200+ → future → invisible
```

### 5.3 Creating a Snapshot

When `beginTransaction()` is called:

1. Lock the Transaction Manager briefly to get a consistent view
2. Record `xmin = min(activeTransactions.keys)`, or `nextXid` if no active transactions
3. Record `xmax = nextXid` (the next XID to be assigned)
4. Record `inProgressXids = Set(activeTransactions.keys)`
5. Unlock
6. Return the Snapshot

The snapshot is immutable once created. It belongs to exactly one transaction and lives as long as that transaction does.

### 5.4 Snapshot Isolation vs Serializable Isolation

**Snapshot Isolation** prevents:

- Dirty reads (seeing uncommitted data from others)
- Non-repeatable reads (same row returning different values if read twice)
- Phantom reads (set of rows matching a condition changing between reads)

**Snapshot Isolation does NOT prevent write skew** — two transactions read overlapping data and make decisions that together are inconsistent, even though individually each transaction's reads and writes are valid.

Example of write skew:

```
T1: reads "is doctor on call?" → yes → marks T1's doctor as off-call
T2: reads "is doctor on call?" → yes → marks T2's doctor as off-call
Result: both doctors are off-call, but at least one should always be on-call
```

**Serializable Snapshot Isolation (SSI)** adds predicate tracking and conflict detection to prevent this. For the capstone, Snapshot Isolation is the target. SSI is an optional extension.

---

## 6. Component 3 — MVCC Manager

### 6.1 Purpose

The MVCC Manager implements the core multi-version logic. It manages the version chains for all tuples and enforces visibility rules. It is the component that Member 2's execution engine calls for every tuple it reads.

### 6.2 Version Storage Design

You have two options for where to store old versions. Choose based on complexity preference:

**Option A — In-heap versioning (PostgreSQL's approach)**

Old versions stay in the heap pages alongside current versions. Each tuple's slot in the heap directly stores xmin/xmax. Old versions accumulate in heap pages until a garbage collection process removes them.

Advantages: no separate version store, simpler reads.
Disadvantages: heap pages fill with dead versions, need vacuum/GC, complex page layout.

**Option B — Separate version store (cleaner for a capstone)**

The heap file stores only the current (latest) version of each tuple. Old versions are stored in a separate **Version Store** — a dedicated heap file or in-memory structure. Each version has a pointer to the previous version.

```
Heap slot 5 → pointer to latest version in Version Store

Version Store:
  v3: {xmin=150, xmax=0,   data=..., prev→v2}  ← current
  v2: {xmin=100, xmax=150, data=..., prev→v1}
  v1: {xmin=50,  xmax=100, data=..., prev→null}
```

Advantages: clean separation, easier garbage collection, heap pages stay compact.
Disadvantages: extra indirection on every read.

**Recommendation for the capstone: Option B**. The separation keeps your heap file implementation clean (Member 1 doesn't need to know about MVCC internals) and makes version chain traversal explicit and debuggable.

### 6.3 Version Record Structure

Each version record contains:

- `xmin` — transaction ID that created this version
- `xmax` — transaction ID that deleted/replaced this version. 0 = still current.
- `rid` — the RID in the heap file this version belongs to (for reverse lookup)
- `prevVersionId` — pointer to the previous version in the chain. -1 = first version.
- `data` — the raw tuple bytes for this version

### 6.4 Visibility Rules — The Core Algorithm

This is the most important thing to understand and implement correctly. Given a version `V` with fields `(xmin, xmax)` and a snapshot `S` with fields `(xmin_s, xmax_s, inProgress_s)`, the visibility rule is:

**Step 1 — Is the creator visible?**

The version is created by transaction `xmin`. For this version to be visible, `xmin` must represent a committed transaction that committed before the snapshot was taken:

```
xmin_visible = (
    xmin < S.xmin_s                         -- xmin committed before snapshot's xmin
    OR (
        xmin < S.xmax_s                     -- xmin started before snapshot was taken
        AND xmin NOT IN S.inProgress_s      -- xmin was not in-progress at snapshot time
        AND CommitLog.isCommitted(xmin)     -- xmin actually committed
    )
)
```

If `xmin_visible` is false, this version is invisible. Stop here.

**Step 2 — Was it deleted before the snapshot?**

If `xmax = 0`, the version has not been deleted. It is visible (given Step 1 passed).

If `xmax != 0`, check whether the deletion was visible to this snapshot:

```
xmax_visible = (
    xmax < S.xmin_s                         -- deleter committed before snapshot's xmin
    OR (
        xmax < S.xmax_s                     -- deleter started before snapshot
        AND xmax NOT IN S.inProgress_s      -- deleter was not in-progress
        AND CommitLog.isCommitted(xmax)     -- deleter actually committed
    )
)
```

If `xmax_visible` is true, the deletion is visible to this snapshot — the version is deleted from this transaction's perspective. Return invisible.

If `xmax_visible` is false, the deletion happened "after" the snapshot or by a transaction the snapshot does not see — so this transaction still sees the tuple as existing. Return visible.

**Combined rule in plain English:**

> A version is visible to snapshot S if: the transaction that created it committed before S was taken, AND either the version has never been deleted, OR the transaction that deleted it had not yet committed when S was taken.

### 6.5 Finding the Visible Version

Given a RID and a snapshot, to find the correct version to return:

1. Look up the RID in the version chain map to find the chain's head (latest version).
2. Start at the head. Apply the visibility rule.
3. If the current version is visible, return its data bytes.
4. If not visible, follow `prevVersionId` to the previous version.
5. Repeat until a visible version is found or the chain is exhausted.
6. If no version is visible, return null (this tuple does not exist from this transaction's perspective).

In most cases, the chain head (latest version) is visible and the traversal stops immediately. Traversal only goes deeper when reading rows that are being actively written by other concurrent transactions.

### 6.6 Insert Operation

When Member 2's execution engine executes an INSERT:

1. Serialize the tuple data (done by the execution engine, passed as raw bytes).
2. Call `heapFile.insertTuple(rawBytes)` to physically place the bytes in a heap page. This returns a RID.
3. Create a new Version record: `xmin = txn.xid`, `xmax = 0`, `data = rawBytes`, `prevVersionId = -1` (no previous version — this is a new row).
4. Register this version as the head of the chain for this RID.
5. Add `(RID, null)` to `txn.undoLog` — "null" because there is no old version to restore on rollback.
6. Call `walManager.logInsert(txn.xid, rid.pageId, rid.slotId, rawBytes)` and get back an LSN.
7. Set `page.lsn = lsn` via `bufferPool.markDirty(rid.pageId)`.

### 6.7 Delete Operation

When Member 2's execution engine executes a DELETE on a specific RID:

1. Find the current head version for this RID.
2. Verify the version is visible to the current transaction's snapshot (sanity check).
3. Save the current version's data bytes for the undo log.
4. Set `version.xmax = txn.xid` to mark this version as deleted by this transaction.
5. Add `(RID, savedBytes)` to `txn.undoLog`.
6. Call `walManager.logDelete(txn.xid, rid.pageId, rid.slotId, savedBytes)`.

The heap file's slot is not physically deleted — just the `xmax` is set. Readers who should not see the deletion (their snapshot predates it) will still see the version via the visibility rule.

### 6.8 Update Operation

An UPDATE in MVCC is implemented as a DELETE of the old version followed by an INSERT of the new version. This is called a "version replacement":

1. Mark the old version with `xmax = txn.xid` (same as delete).
2. Create a new version with the updated bytes and `xmin = txn.xid`, `xmax = 0`.
3. Insert the new version bytes into the heap via `heapFile.insertTuple`.
4. Link the new version as the chain head. Set `newVersion.prevVersionId = oldVersion.id`.
5. Log both operations to WAL.

### 6.9 Abort Handling

When a transaction aborts, all its changes must be reversed. Use the in-memory undo log:

For each `(RID, oldVersionBytes)` in `txn.undoLog`, in **reverse order** (last change first):

- If `oldVersionBytes == null`: this was an INSERT. Delete the version — set `xmax = txn.xid`, mark the slot as logically deleted. (Or simply set `xmin = ABORTED_XID` as a sentinel, so visibility rules exclude it.)
- If `oldVersionBytes != null`: this was a DELETE or UPDATE. Restore the old version by setting `xmax = 0` on it (undelete it).

After undoing all changes, the Transaction Manager logs an ABORT record to WAL and updates the Commit Log.

---

## 7. Component 4 — Commit Log

### 7.1 Purpose

The Commit Log is a persistent record of the final status of every transaction: committed or aborted. It is the authoritative source for the visibility rule's `isCommitted(xid)` check. It must be:

- **Fast to query** — visibility checks call `isCommitted` for every version of every tuple read
- **Persistent** — survives crashes; required for recovery
- **Compact** — there can be millions of transaction IDs

### 7.2 Structure

The Commit Log is stored as a file on disk: `commit_log.clog`. Its format is a large array of 2-bit status values, indexed by XID:

```
Commit Log file (conceptually):
Index:    0    1    2    3    4    5    6    7    ...
Status:  [00] [01] [10] [01] [00] [10] [10] [01] ...

Status encoding:
  00 = IN_PROGRESS
  01 = COMMITTED
  10 = ABORTED
  11 = reserved
```

Since each status is 2 bits, a single byte holds 4 transaction statuses. A 4KB page of the commit log holds statuses for 4096 × 4 = 16,384 transactions. For a capstone with moderate transaction counts, this file stays very small.

### 7.3 Access Pattern

The Commit Log must support:

- **getStatus(xid)** → `IN_PROGRESS`, `COMMITTED`, or `ABORTED`. Byte offset = `xid / 4`, bit offset = `(xid % 4) × 2`. Read 2 bits, decode.
- **setStatus(xid, status)** — write 2 bits at the appropriate position. Must flush to disk for durability (called on commit/abort).
- **isCommitted(xid)** — convenience wrapper around getStatus.

For performance, the Commit Log should be memory-mapped or cached in RAM with lazy disk writes. Since `setStatus` on COMMIT is called before any response is returned to the user, it must be synchronously flushed for durability.

### 7.4 Commit Log and Recovery

On crash recovery, the Recovery Manager (Member 1) will undo uncommitted transactions via the WAL. After recovery completes, any XID that was rolled back should be marked `ABORTED` in the Commit Log. Any XID whose COMMIT record appeared in the WAL but whose Commit Log entry shows `IN_PROGRESS` (because the Commit Log write was lost in the crash) should be marked `COMMITTED`.

Recovery and the Commit Log are tightly coupled with Member 1's ARIES implementation. Coordinate this interface carefully.

---

## 8. Version Garbage Collection (Vacuum)

### 8.1 The Problem

Versions accumulate over time. Every UPDATE creates a new version. Every DELETE leaves the old version in place. Without cleanup, the version store grows forever, and version chain traversals become O(versions_per_row) instead of O(1).

### 8.2 The Horizon

A version is **reclaimable** if it is invisible to all currently active and future transactions. The reclaim condition is:

```
version.xmax < getOldestActiveXid()
AND CommitLog.isCommitted(version.xmax)
```

If `xmax` is committed and is older than the oldest active transaction, no running or future transaction can see this version — its deletion is visible to everyone. It can be safely removed.

`getOldestActiveXid()` returns the minimum XID in `activeTransactions`. This is the **MVCC horizon** (PostgreSQL calls it the "oldest transaction").

### 8.3 Garbage Collection Process

Run garbage collection periodically (e.g., every N seconds or after N operations):

1. Get the current horizon: `h = transactionManager.getOldestActiveXid()`
2. Iterate through all version chains.
3. For each chain, walk from the tail (oldest). Remove versions where `xmax < h AND isCommitted(xmax)`.
4. Stop removing when you reach a version whose `xmax >= h` or `xmax == 0` — those must stay.

In PostgreSQL, this is called **VACUUM**. It is a background process that runs continuously. For the capstone, running it as a periodic background thread triggered every 10,000 transactions is sufficient.

---

## 9. Interaction With Member 1 (Storage)

You are the only member that performs write operations. Every INSERT, DELETE, and UPDATE goes through your code. You must coordinate with Member 1's WAL Manager for every write.

**Protocol for every write operation:**

1. Perform the operation (modify the version chain, insert/delete in heap)
2. Call the appropriate WAL log method before returning
3. Set the page's LSN to the returned LSN value

This ordering is critical. WAL records must be written before the operation is considered complete. If you write to the heap without logging first, a crash could leave data changes with no WAL record — recovery cannot undo them.

**What you call from Member 1:**

| Method                                               | When                                             |
| ---------------------------------------------------- | ------------------------------------------------ |
| `HeapFile.insertTuple(bytes)`                        | On INSERT, after creating version record         |
| `HeapFile.deleteTuple(rid)`                          | On physical deletion during GC                   |
| `HeapFile.getTuple(rid)`                             | When fetching old version bytes for undo log     |
| `WALManager.logBegin(xid)`                           | When a transaction begins                        |
| `WALManager.logInsert(xid, pageId, slotId, data)`    | After every insert                               |
| `WALManager.logDelete(xid, pageId, slotId, oldData)` | After every delete                               |
| `WALManager.logCommit(xid)`                          | On transaction commit                            |
| `WALManager.logAbort(xid)`                           | On transaction abort                             |
| `WALManager.flushToLsn(lsn)`                         | Rarely needed directly — WALManager handles this |
| `BufferPool.markDirty(pageId)`                       | After every modification to a page               |
| `Page.setLsn(lsn)`                                   | After every modification, using the LSN from WAL |

---

## 10. Interaction With Member 2 (Execution Engine)

Member 2's execution engine calls your code in two ways:

**For reads (every single tuple):**

`getVisibleVersion(rid, snapshot)` — called inside every scan operator. Given a RID and a snapshot, return the raw bytes of the visible version, or null if no version is visible. This is your most frequently called method. It must be efficient.

**For writes:**

`insert(transaction, heapFile, rawBytes)` — called by the execution engine when executing an INSERT statement. You handle WAL logging, version chain creation, and heap file insertion.

`delete(transaction, heapFile, rid)` — called by the execution engine when executing a DELETE statement. You handle WAL logging, xmax stamping, and undo log recording.

---

## 11. Concurrency and Thread Safety

Multiple transactions run concurrently. Components that are accessed by multiple threads simultaneously need synchronization:

**Transaction Manager** — `activeTransactions` map and `nextXid` counter must be protected. Use a `ReentrantLock` or `synchronized` blocks when reading/writing these.

**Snapshot creation** — must atomically read `nextXid` and `activeTransactions` together, or a transaction could be assigned an XID but not yet appear in `activeTransactions` when another snapshot is taken.

**Commit Log** — writes on commit/abort must be atomic at the byte level. Since updates are 2-bit writes within a byte, use a `ReadWriteLock` on the byte being written.

**Version chains** — concurrent modifications to the same row's version chain must be serialized. Use per-RID locks (a striped lock pool — one lock per hash bucket of RID). This avoids a global lock while preventing races on individual chains.

**getOldestActiveXid()** — reads `activeTransactions`. Must be consistent with the transaction lifecycle operations. Use the same lock as the Transaction Manager.

---

## 14. Key Scenarios to Test

**Scenario 1 — Basic isolation:**
T1 inserts a row. T2 starts before T1 commits. T2 should not see the row. T1 commits. T3 starts after T1 commits. T3 should see the row.

**Scenario 2 — Read consistency:**
T1 starts. T2 inserts 100 rows and commits. T1 reads the table. T1 should see 0 of T2's rows (T1's snapshot predates T2).

**Scenario 3 — Abort cleanup:**
T1 inserts a row. T1 aborts. T2 reads the table. T2 should see 0 rows — the aborted insert is invisible.

**Scenario 4 — Concurrent updates:**
T1 and T2 both try to update the same row at the same time. One must succeed and one must either fail or wait. Implement first-writer-wins: the second writer detects the conflict (the row's xmax is set by the first writer, and the first writer is still in-progress), and aborts.

**Scenario 5 — Version chain correctness:**
A row is updated 5 times by 5 different committed transactions. T reads the row after all 5 commits — sees version 5. T was started before commit 3 — sees version 2. Walk the chain correctly.

**Scenario 6 — GC correctness:**
After all transactions that could see an old version have committed, GC removes it. Subsequent reads still return the correct current version.

---
