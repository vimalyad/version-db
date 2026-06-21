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
| 0 — Foundation | `phase-00-foundation` | shared | in progress |
| 1 — Page & DiskManager | `phase-01-storage-page` | M1 | not started |
| 2 — Buffer Pool | `phase-02-bufferpool` | M1 | not started |
| 3 — Heap File | `phase-03-heapfile` | M1 | not started |
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
- **PAGE_SIZE:** _to be fixed in sub-phase 0.2_ (choose 4096 or 8192 and record the value here).
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

<!-- Add real phase work below this line as it is implemented. -->
