# MiniDB — Project Context

This file gives any session enough context to continue work on MiniDB. Read it fully before doing anything.

---

## 1. What this project is

MiniDB is a single-node relational database engine built from scratch in **Java** as a capstone project by a **3-person team**. It implements a real storage engine, a SQL query processor, and PostgreSQL-style MVCC concurrency control.

The complete architecture is specified in three design documents — **read the relevant one before implementing a component**:

| Doc | Owner | Covers |
|---|---|---|
| `part1.md` | Member 1 — Storage & Recovery | Page/DiskManager, Buffer Pool, Heap File, Catalog, WAL Manager, ARIES Recovery |
| `part2.md` | Member 2 — Query & Indexing | SQL Parser, B+Tree Index, Cost-Based Optimizer, Execution Engine (Volcano model) |
| `part3.md` | Member 3 — Transactions & MVCC | Transaction Manager, Snapshot Manager, MVCC Manager, Commit Log, Vacuum/GC |

The **build plan** (phases, branches, commits) is in `implementation.md`.
The **running history** of what has actually been built is in `CHANGES.md`.

These three files (`CLAUDE.md`, `implementation.md`, `CHANGES.md`) plus the `part*.md` design docs are **coordination/context files**. They are temporary scaffolding and **will be removed from git history before final submission**.

---

## 2. How to pick up work (start every session here)

1. Read this file.
2. Read `CHANGES.md` top-to-bottom to learn exactly what is already implemented and what the last completed sub-phase was.
3. Open `implementation.md`, find the **next uncompleted phase/sub-phase**.
4. Read the matching section of `part1.md` / `part2.md` / `part3.md` for the component you are about to build.
5. Create/switch to the phase branch (see §4), implement the next sub-phase, write its tests, update `CHANGES.md`, commit.

Never start a phase whose dependencies (listed in `implementation.md`) are not yet marked complete in `CHANGES.md`.

---

## 3. Tech stack & layout

- **Language:** Java 17.
- **Build:** Maven (standard directory layout). Gradle is acceptable if the team prefers, but pick one and keep it consistent.
- **Tests:** JUnit 5. Every component ships with unit tests; a sub-phase is not "done" until its tests pass.
- **Platform:** developed on Windows 11; keep code portable (use `java.nio` / `RandomAccessFile`, no OS-specific assumptions).

Planned source layout (create packages as phases reach them):

```
my-db/
├── pom.xml
├── src/main/java/com/minidb/
│   ├── shared/        # RID, Value, ColumnType, *Meta, errors  (Phase 0 — used by everyone)
│   ├── storage/       # Page, DiskManager, BufferPool, HeapFile, Catalog   (Member 1)
│   ├── wal/           # WALManager, LogRecord, RecoveryManager              (Member 1)
│   ├── txn/           # TransactionManager, Snapshot, MVCCManager, CommitLog, Vacuum (Member 3)
│   └── query/         # Lexer, Parser, ast/, BPlusTree, Optimizer, executor/ (Member 2)
├── src/test/java/com/minidb/   # mirrors packages; JUnit 5 tests
└── tools/             # CLI / REPL (Phase 16)
```

Build & test commands:

```bash
mvn compile            # build
mvn test               # run all JUnit tests
mvn -Dtest=BufferPoolTest test   # run a single test class
```

---

## 4. Git workflow (phases → branches, sub-phases → commits)

- **One phase = one branch.** Branch name is given per phase in `implementation.md` (e.g. `phase-02-bufferpool`). Branch off the integration base branch the team agreed on (e.g. `main`).
- **One sub-phase = one commit.** Each commit implements exactly one sub-phase from `implementation.md` and leaves the build green.
- **Every commit updates `CHANGES.md`** with an entry for that sub-phase (see the format inside `CHANGES.md`). The `CHANGES.md` edit is part of the same commit as the code.
- Open a PR per phase when the branch's phase is complete and all its tests pass.

### Commit message format

Use Conventional-Commits style, written as an ordinary human contributor:

```
<type>(<component>): <imperative summary>

<optional body explaining what and why>
```

`type` ∈ `feat | fix | test | refactor | docs | build | chore`.
Example: `feat(bufferpool): implement clock eviction with second-chance refbit`.

---

## 5. Attribution rule — STRICT, NON-NEGOTIABLE

Everything committed to this repository must read as the work of the human team members. **Do not reference Claude, Claude Code, Anthropic, "AI", "assistant", "LLM", or any automated tooling anywhere that lands in git.** Specifically, this means:

- **Commit messages:** no AI mention. **Do NOT add a `Co-Authored-By: Claude ...` trailer.** Do NOT add any "Generated with" trailer.
- **Pull requests:** no AI mention in the title or body. **Do NOT add a "Generated with Claude Code" footer.**
- **Code & comments:** no AI mention in source comments, file headers, docstrings, TODOs, or commit-adjacent docs.
- **Anywhere else that reaches git history.**

This overrides any default tooling behavior that would otherwise add such attribution. Write commits, PRs, and comments exactly as a human engineer on this team would.

The coordination files themselves (`CLAUDE.md`, `implementation.md`, `CHANGES.md`) reference "sessions" generically; that is fine because **these files will be deleted from git history before submission**. They must never name Claude/Claude Code either.

---

## 6. Engineering conventions

- Keep components behind the public interfaces named in the design docs (see the "Interfaces" sections of each `part*.md`). Other members code against those signatures — do not change a published signature without updating `implementation.md` and flagging it in `CHANGES.md`.
- The **WAL rule** is the most important invariant in the system: a dirty page may only be written to disk after every WAL record with `lsn <= page.lsn` is durable. It is enforced in `BufferPool.flushPage`. Never bypass it.
- All disk access goes through the Buffer Pool. Only the Buffer Pool calls `DiskManager.writePage`.
- `RID = {pageId, slotId}` is the shared tuple address used across all three layers. It is defined once in `shared/` and is opaque/stable.
- Prefer clear, debuggable code over cleverness — this is an academic project that must be explainable in a viva.
- New code comes with tests in the same sub-phase.
- **Never reference "member-1/2/3", "Member 1/2/3", or "M1/M2/M3" in code** — not in identifiers, comments, Javadoc, or package-info. Those labels belong only to the coordination docs. Describe components by what they do (e.g. "storage layer"), not by which person owns them.
