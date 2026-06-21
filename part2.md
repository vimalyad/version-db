# MiniDB — Member 2: Query Processing & Indexing
## Architecture & Design Document

---

## 1. Your Role in the System

You build the **intelligence layer**. You are the part of the database the user directly interacts with. You take a raw SQL string, understand its structure and meaning, decide the most efficient way to answer it, and execute that plan by coordinating with the storage layer (Member 1) and the MVCC layer (Member 3).

Your four components form a pipeline:

```
SQL String
     │
     ▼
[Parser]          ← Understands the SQL string, produces a structured AST
     │
     ▼
[Optimizer]       ← Decides HOW to execute the query (plan selection, cost estimation)
     │
     ▼
[Execution Engine]← Actually runs the plan, pulls data through operators
     │
     ├──► Calls HeapFile.scan()              (Member 1)
     ├──► Calls BufferPool.fetchPage()       (Member 1 — for B+Tree)
     └──► Calls MVCCManager.getVisible()     (Member 3 — for snapshot visibility)

[B+Tree Index]    ← Separate component, used by Optimizer + Execution Engine
```

---

## 2. System Context

```
         User SQL
            │
      [YOUR: Parser]  ─►  AST
            │
      [YOUR: Optimizer]  ─►  Physical Plan
            │
      [YOUR: Execution Engine]
         │          │
         │          └──► [Member 3: MVCCManager.getVisibleVersion(rid, snapshot)]
         │
         ├──► [Member 1: HeapFile.scan()]          (for sequential scans)
         └──► [YOUR: B+Tree] ──► [Member 1: BufferPool.fetchPage()]
```

Every SELECT query runs through all four of your components. INSERTs and DELETEs bypass the optimizer and go directly through the execution engine to Member 3.

---

## 3. Component 1 — SQL Parser

### 3.1 Purpose

The Parser converts a raw SQL string into an **Abstract Syntax Tree (AST)**. An AST is a tree of structured objects representing every part of the query — what tables are involved, what columns are selected, what conditions are applied, how tables are joined. Everything downstream works on the AST, never on the raw string.

### 3.2 Two-Stage Process: Lexing then Parsing

**Stage 1: Lexer (Tokenizer)**

The Lexer reads the raw SQL string character by character and produces a flat list of **tokens**. A token is the smallest meaningful unit of SQL — a keyword (`SELECT`), an identifier (`users`), an operator (`>`), a literal (`42` or `'Alice'`), or punctuation (`,`, `(`, `)`).

The Lexer must handle:
- **Keywords** — case-insensitive: SELECT, FROM, WHERE, INSERT, INTO, VALUES, DELETE, UPDATE, SET, CREATE, TABLE, DROP, INDEX, ON, JOIN, INNER, LEFT, RIGHT, AND, OR, NOT, ORDER, BY, ASC, DESC, LIMIT, OFFSET, NULL, TRUE, FALSE
- **Identifiers** — table names, column names: start with a letter or underscore, followed by letters/digits/underscores
- **Integer literals** — sequences of digits, optionally preceded by a minus sign
- **Float literals** — integer part, decimal point, fractional part
- **String literals** — enclosed in single quotes. An escaped single quote inside a string is written as `''` (two consecutive single quotes)
- **Operators** — `=`, `!=`, `<>`, `<`, `>`, `<=`, `>=`, `*`, `+`, `-`, `/`
- **Punctuation** — `(`, `)`, `,`, `.`, `;`
- **Whitespace** — spaces, tabs, newlines are skipped between tokens
- **Line comments** — text after `--` on the same line is skipped

Each Token carries: its `TokenType` (an enum), its raw `value` (the matched text), and its `position` in the source string (for error messages).

**Stage 2: Parser**

The Parser consumes the token list and produces an AST. The standard approach for SQL is **recursive descent parsing**: one method per grammar rule, calling each other recursively. This is straightforward to implement and produces excellent error messages.

The parser maintains a `current position` pointer into the token list and two operations:
- **peek()** — look at the current token's type without consuming it
- **advance()** — consume the current token and move the pointer forward
- **expect(tokenType)** — advance and assert the current token is of the expected type. If not, throw a ParseError with a meaningful message like "Expected column name after SELECT, found integer '42' at position 7".

### 3.3 AST Node Taxonomy

All AST nodes are immutable data classes. Group them into categories:

**Statements** — the top-level query:
- `SelectStatement` — projections, from-clause, where, order-by, limit, offset
- `InsertStatement` — table name, optional column list, list of value rows
- `DeleteStatement` — table name, where clause
- `UpdateStatement` — table name, list of (column, expression) assignments, where clause
- `CreateTableStatement` — table name, list of column definitions
- `CreateIndexStatement` — index name, table name, column name
- `DropTableStatement` — table name

**From-clause nodes:**
- `TableRef` — a single table with optional alias
- `JoinExpr` — two from-clause nodes connected by a join type (INNER, LEFT, RIGHT, CROSS) and an ON condition

**Expressions** — anything that produces a value:
- `ColumnRef` — a column reference, optionally qualified with a table name (`users.age` or just `age`)
- `Literal` — a constant value: integer, float, string, boolean, or null
- `BinaryOp` — left expression, operator string, right expression. Covers comparisons (`=`, `!=`, `<`, `>`, `<=`, `>=`) and logical connectors (`AND`, `OR`) and arithmetic (`+`, `-`, `*`, `/`)
- `UnaryOp` — `NOT` applied to an expression
- `FunctionCall` — aggregate function name (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`) with argument list
- `Star` — the `*` in `SELECT *`

**Other nodes:**
- `ColumnDef` — used in CREATE TABLE: column name, type (`INT`, `FLOAT`, `VARCHAR`, `BOOL`), nullability

### 3.4 Operator Precedence

SQL has defined operator precedence. In your recursive descent parser, you implement this by nesting grammar rules — lower-precedence operators call higher-precedence ones. The chain, lowest to highest:

```
OR
  └── AND
        └── NOT
              └── Comparison (=, !=, <, >, <=, >=)
                    └── Additive (+, -)
                          └── Multiplicative (*, /)
                                └── Unary (-)
                                      └── Primary (literal, column, function, subexpression)
```

Each level has its own `parseXxx()` method that handles its operator and calls the next level for its operands.

### 3.5 Error Handling

Every parse error must include:
- What was expected
- What was actually found
- The position in the source string

Example: `ParseError: Expected '(' after function name 'COUNT', found ',' at position 14 in "SELECT COUNT, age FROM users"`

This quality of error message is what separates a usable parser from a frustrating one.

---

## 4. Component 2 — B+ Tree Index

### 4.1 Purpose

A B+Tree index provides O(log n) point lookup and O(log n + k) range scan on a single column, where k is the number of matching results. Without an index, every query on a specific value requires a full table scan — O(n) per query. For a table with a million rows, this difference is enormous.

### 4.2 Core Properties of B+Trees

1. **All data lives in leaf nodes.** Inner nodes contain only keys that serve as routing guides to direct searches.
2. **Leaf nodes are linked left-to-right** via `nextLeaf` pointers. This makes range scans a simple linked-list walk after the initial search.
3. **The tree is always balanced.** All leaf nodes are at the same depth. Inserts and deletes maintain balance through splits and merges.
4. **Each node stores between ORDER and 2×ORDER-1 keys** (except the root). ORDER is chosen so one node fits in one page.

```
                     Inner Node: [30 | 60]
                    /              |              \
         Inner:[10|20]      Inner:[40|50]      Inner:[70|80]
          /    |    \         /    |    \         /    |    \
    Leaf[8,10] Leaf[20,25] Leaf[30,35] ... Leaf[60] ... Leaf[80,90]
         ↔          ↔           ↔                ↔              ↔
    (all leaves linked via nextLeaf pointer — the "chain")
```

### 4.3 What Leaf Nodes Store

Leaf node entries are `(key, RID)` pairs. The key is the indexed column's value for a particular tuple. The RID is the tuple's physical location in the heap file. This means the B+Tree does not store actual tuple data — it just provides a fast path to find RIDs, which the execution engine then uses to fetch the real tuple from the heap file.

### 4.4 Persistence in the Buffer Pool

Every B+Tree node (inner or leaf) is stored as a **page in the Buffer Pool** — the same buffer pool that stores heap file pages. This means:
- Nodes are fetched with `bufferPool.fetchPage(pageId)`
- Modified nodes are marked dirty via `bufferPool.markDirty(pageId)`
- Nodes are released with `bufferPool.unpin(pageId)`
- New nodes are allocated with `bufferPool.newPage()`

Each node must have a serialization and deserialization format so it can be stored in PAGE_SIZE bytes. The node's type (inner vs leaf) must be encoded as the first byte so deserialization knows what to parse.

**Inner node binary layout:**
- Node type byte (0 = inner)
- Number of keys (2 bytes)
- Parent page ID (8 bytes)
- Alternating children and keys: `[child_0][key_0][child_1][key_1]...[child_n]`

**Leaf node binary layout:**
- Node type byte (1 = leaf)
- Number of keys (2 bytes)
- Parent page ID (8 bytes)
- Next leaf page ID (8 bytes)
- Repeated entries: `[key][rid.pageId][rid.slotId]` per entry

Choose ORDER such that the serialized node fits within PAGE_SIZE bytes with room to spare.

### 4.5 Search Operation

To find the RID for a given key:
1. Start at the root page.
2. Load the node. If it is a leaf, binary search for the key in `keys[]`. If found, return the RID at the same index. If not found, return null.
3. If it is an inner node, binary search to find the largest key ≤ search key. Follow the corresponding child pointer to the next level.
4. Repeat until a leaf is reached.

Depth is O(log_ORDER(n)) — with ORDER = 100 and a million rows, that is fewer than 3 levels.

### 4.6 Range Scan Operation

To find all RIDs where key is between `low` and `high` (inclusive):
1. Perform a search for `low` to find the leaf page where values ≥ low begin.
2. Walk the leaf chain via `nextLeaf` pointers, collecting all RIDs whose key is in [low, high].
3. Stop when you reach a leaf where all keys exceed `high` or `nextLeaf = -1`.

This is the crucial advantage of B+Trees over B-Trees (without the +). The leaf chain makes range scans linear in the result size after the initial O(log n) traversal.

### 4.7 Insert Operation

To insert a `(key, RID)` pair:
1. Find the leaf node where the key belongs (same traversal as search).
2. Insert the key and RID into the leaf's arrays in sorted order.
3. If the leaf now has 2×ORDER keys (overflow), **split the leaf**:
   - Create a new leaf node.
   - The left half of entries stay in the original leaf.
   - The right half move to the new leaf.
   - The new leaf's `nextLeaf` pointer is set to the old leaf's `nextLeaf`.
   - The old leaf's `nextLeaf` is set to the new leaf's page ID.
   - The smallest key of the new leaf is **pushed up** to the parent inner node.
4. If the parent inner node also overflows, **split the inner node**:
   - The middle key is **pushed up** (not copied — it moves to the parent, unlike leaf splits).
   - Two inner nodes remain, one with keys left of the middle, one with keys right.
5. If the root splits, create a new root containing only the pushed-up key and two children. The tree grows taller by one level.

**Key distinction: leaf split vs inner split.** In a leaf split, the separator key (smallest key of the right leaf) is **copied** to the parent — it also stays in the leaf. In an inner split, the middle key is **pushed up** and removed from the inner node — it only exists in the parent. This is what distinguishes B+Tree from B-Tree.

### 4.8 Delete Operation

To delete a `(key, RID)` pair:
1. Find the leaf containing the key. Remove the entry.
2. If the leaf now has fewer than ORDER keys (underflow, except for root):
   - **Try to borrow from the left sibling**: if the left sibling has more than ORDER keys, move its last entry to the front of the current leaf. Update the separator key in the parent.
   - **Try to borrow from the right sibling**: if the right sibling has more than ORDER keys, move its first entry to the end of the current leaf. Update the separator key in the parent.
   - **Merge**: if neither sibling can lend, merge this leaf with one sibling. Remove the separator key from the parent.
3. If removing the separator key from the parent causes the parent to underflow, apply borrow/merge logic to the parent recursively.
4. If the root is left with only one child (all its keys were pushed down during merge), the root is removed and its single child becomes the new root. The tree shrinks by one level.

### 4.9 Index Creation

When `CREATE INDEX` is executed, the execution engine:
1. Creates an empty B+Tree (a root that is an empty leaf node).
2. Does a full scan of the heap file for the table.
3. For each tuple, extracts the indexed column's value and the tuple's RID.
4. Inserts `(value, RID)` into the tree.
5. Registers the index in the Catalog via `catalog.registerIndex(tableId, columnName, rootPageId)`.

---

## 5. Component 3 — Cost-Based Optimizer

### 5.1 Purpose

The Optimizer takes a parsed AST and produces a **Physical Plan** — a tree of operator descriptors specifying not just *what* to compute but *how* to compute it. It generates multiple candidate plans and selects the cheapest according to a cost model.

This is what separates a real database from a toy. Without an optimizer, every query does a sequential scan. With an optimizer, the database can choose index scans, join algorithms, and operation orderings that can make a query 1000x faster.

### 5.2 Logical Plan vs Physical Plan

**Logical plan** — what to compute, in abstract terms:
```
Project([name, age])
  Filter(age > 25)
    Scan(users)
```

**Physical plan** — how to compute it, with specific algorithms:
```
ProjectionOperator([name, age])
  IndexScanOperator(users, index=age_index, range=[26, ∞])
```

The optimizer's job is to translate from logical to physical and choose the best physical option for each operator.

### 5.3 Physical Plan Node Types

The optimizer produces a tree of these descriptor objects (not operators — operators are built by the execution engine from these descriptors):

- **SeqScanPlan** — full table scan with optional predicate. Fields: `tableName`, `predicate`
- **IndexScanPlan** — B+Tree range scan. Fields: `tableName`, `indexColumn`, `rootPageId`, `low`, `high`, `postFilterPredicate` (any remaining filter not handled by the index)
- **FilterPlan** — apply a predicate to its child's output. Fields: `predicate`, `child`
- **ProjectionPlan** — select and compute output columns. Fields: `projections`, `child`
- **NestedLoopJoinPlan** — join two inputs by nested iteration. Fields: `outer`, `inner`, `joinCondition`
- **HashJoinPlan** — join two inputs using a hash table (equality joins only). Fields: `outer`, `inner`, `joinCondition`, `outerKey`, `innerKey`
- **SortPlan** — sort output by specified columns. Fields: `child`, `orderBy` (list of column + direction pairs)
- **LimitPlan** — restrict output count. Fields: `child`, `limit`, `offset`

Each plan node also carries `estimatedCost` and `estimatedRows` — populated by the optimizer, used for plan comparison and join ordering.

### 5.4 Selectivity Estimation

Selectivity is the fraction of tuples that pass a filter predicate. It ranges from 0.0 (nothing passes) to 1.0 (everything passes). The optimizer uses this to estimate how many rows each operator produces.

For a predicate on column `col` with value `val`, using column statistics from the Catalog:

| Predicate | Selectivity Estimate |
|-----------|----------------------|
| `col = val` | `1 / numDistinct` |
| `col != val` | `1 - 1/numDistinct` |
| `col > val` | `(maxValue - val) / (maxValue - minValue)` |
| `col >= val` | `(maxValue - val + 1) / (maxValue - minValue)` |
| `col < val` | `(val - minValue) / (maxValue - minValue)` |
| `col <= val` | `(val - minValue + 1) / (maxValue - minValue)` |

For compound predicates:
- `A AND B` → `selectivity(A) × selectivity(B)` (assumes independence)
- `A OR B` → `selectivity(A) + selectivity(B) - selectivity(A) × selectivity(B)`
- Unknown predicate → 0.5 (conservative default)

### 5.5 Cost Model

Define system constants:
- `SEQ_PAGE_COST = 1.0` — cost to read one page sequentially
- `RANDOM_PAGE_COST = 4.0` — cost to read one page by random seek (much more expensive)
- `CPU_TUPLE_COST = 0.01` — cost to process one tuple in memory
- `CPU_INDEX_COST = 0.005` — cost to traverse one B+Tree comparison

**Sequential Scan cost:**
```
cost = numPages × SEQ_PAGE_COST
     + numTuples × selectivity × CPU_TUPLE_COST
```

**Index Scan cost:**
```
treeHeight   = ceil(log_ORDER(numTuples))   (B+Tree height)
matchingRows = numTuples × selectivity
cost = treeHeight × RANDOM_PAGE_COST        (traverse tree)
     + matchingRows × RANDOM_PAGE_COST      (random heap fetches for each RID)
     + matchingRows × CPU_INDEX_COST        (comparisons in tree)
```

**Nested Loop Join cost:**
```
cost = outerCost + outerRows × innerCost
```
(For each outer row, we scan the entire inner relation once)

**Hash Join cost:**
```
cost = outerCost + innerCost + innerRows × HASH_BUILD_COST
```
(Scan outer once, scan inner once to build hash table, then probe)

### 5.6 Plan Selection for Single Tables

For a single-table query with a WHERE clause:
1. Compute selectivity of the WHERE predicate.
2. Compute `seqScanCost`.
3. For each index available on the table (from Catalog):
   - Check if the index column appears in the WHERE predicate with a comparison operator.
   - If yes, compute `indexScanCost`.
   - If `indexScanCost < seqScanCost`, the index scan is better.
4. Choose the plan with the lowest cost.

**When is an index scan cheaper?** When selectivity is very low (few rows match). An index scan does a targeted lookup + random heap fetches only for matching rows. A sequential scan always reads all pages. The crossover point is roughly when more than 5-10% of rows match — beyond that, the random I/O cost of an index scan exceeds the sequential cost.

### 5.7 Join Ordering

For queries joining multiple tables, the join order significantly affects performance. The optimizer tries different orderings and picks the cheapest.

**For 2-4 tables**: enumerate all possible orderings (n! permutations). This is feasible — 4! = 24 orderings.

**For 5+ tables**: use a greedy heuristic — sort tables by estimated row count (smallest first). The intuition is that smaller outer relations mean fewer iterations of the inner scan.

For each ordering, build a **left-deep plan tree**: always join left-to-right, with the previous join result as the outer input and the next table as the inner.

```
Tables: [A, B, C] in this order:

((A ⋈ B) ⋈ C)

Cost = cost(A) + rowsA × cost(B) + rows(A⋈B) × cost(C)
```

**Join algorithm selection**: for each join, compare Hash Join vs Nested Loop Join cost. Hash Join wins for large tables with equality conditions. Nested Loop Join wins for small tables or non-equality conditions.

### 5.8 Predicate Pushdown

Predicates should be evaluated as early as possible — this reduces the number of rows flowing through the plan, reducing cost at every subsequent operator.

The rule: **push filters down as close to the scan as possible**.

For a query like `SELECT * FROM users WHERE age > 25`, the WHERE predicate is embedded directly into the SeqScanPlan rather than added as a separate FilterPlan above the scan. This means the filter is applied tuple-by-tuple during the scan, not after all tuples are read.

For join queries with a WHERE clause, split the WHERE predicate into:
- Predicates that reference only one table → push into that table's scan
- Predicates that reference two tables → these are join conditions

---

## 6. Component 4 — Execution Engine

### 6.1 Purpose

The Execution Engine takes a Physical Plan (tree of operator descriptors) and executes it, producing actual result rows. It is implemented using the **Volcano/Iterator Model**.

### 6.2 The Volcano / Iterator Model

Every operator implements the same three-method interface:
- `open(context)` — initialize the operator. Open child operators. Load any required data structures (e.g., build hash table for hash join).
- `next()` — return the next result row, or null if exhausted.
- `close()` — release resources, close child operators.

The query engine calls `open()` once, then calls `next()` repeatedly to consume rows, then calls `close()`.

**Why iterators?**

The key property is **pipelining** — a row produced by the innermost operator flows immediately through every operator above it without being stored anywhere intermediate. Consider:

```
ProjectionOp.next()
  └── FilterOp.next()
        └── SeqScanOp.next()
              └── heap.scan() → one tuple
```

When the user calls `ProjectionOp.next()`, it calls `FilterOp.next()`, which calls `SeqScanOp.next()`, which fetches one tuple from disk. If the tuple passes the filter, it flows up through projection and is returned. No intermediate list is ever built. Memory usage is O(1) per operator regardless of table size.

This is why a query like `SELECT name FROM users LIMIT 1` only reads one page of the heap — it stops as soon as one tuple passes.

### 6.3 Execution Context

Every operator receives an **execution context** when opened. The context carries:
- `transaction` — the current Transaction object (from Member 3). Contains the snapshot.
- `mvccManager` — the MVCC Manager (from Member 3). Used for visibility checks.
- `catalog` — the Catalog (from Member 1). Used for schema lookups.
- `heapFileRegistry` — a map from `tableName → HeapFile`. Operators look up their table here.
- `indexRegistry` — a map from `rootPageId → BPlusTree`. Index scan operators look up their tree here.

### 6.4 Sequential Scan Operator

The most fundamental operator. Drives a full table scan.

**open()**: retrieve the HeapFile for the table from context. Obtain the scan iterator from `heapFile.scan()`.

**next()**: advance the scan iterator. For each `(RID, rawBytes)`:
1. Call `mvccManager.getVisibleVersion(rid, transaction.snapshot)`. This returns the raw bytes of the visible version, or null if the tuple is invisible to this transaction.
2. If null: skip, advance iterator.
3. Deserialize the raw bytes into a Tuple using the table's schema.
4. If a predicate exists, evaluate it against the Tuple. If false: skip.
5. Return the Tuple.

**The MVCC visibility call is mandatory for every tuple read.** This is what makes queries snapshot-consistent. Without it, a query could see uncommitted data from other transactions.

### 6.5 Index Scan Operator

Uses a B+Tree to do a targeted range lookup rather than a full scan.

**open()**: retrieve the BPlusTree from context using `rootPageId`. Compute `low` and `high` bounds from the indexed predicate. Call `bTree.rangeScan(low, high)` to get an iterator of RIDs.

**next()**: for each RID from the range scan iterator:
1. Call `mvccManager.getVisibleVersion(rid, transaction.snapshot)`.
2. If null: skip.
3. Deserialize into a Tuple.
4. Apply `postFilterPredicate` if any (predicates that could not be satisfied by the index).
5. Return the Tuple.

### 6.6 Filter Operator

Wraps a child operator and passes only rows that satisfy a predicate.

**open()**: open child.
**next()**: call `child.next()` in a loop. For each tuple, evaluate the predicate. Return the first tuple that passes. Return null if child is exhausted.

### 6.7 Projection Operator

Reduces each row to only the requested columns, evaluating any expressions.

**open()**: open child.
**next()**: get one tuple from child. If null, return null. Otherwise, build a new Tuple containing only the requested columns' values, evaluated from the input tuple. Return the projected Tuple.

For `SELECT *`, return the tuple unchanged. For aggregate functions (`COUNT`, `SUM`, etc.), these require materializing all child output first — see Aggregation below.

### 6.8 Nested Loop Join Operator

The simple, universally applicable join algorithm.

**State**: `outerTuple` — the current row from the outer (left) input.

**open()**: open outer operator. Fetch first `outerTuple`. Open inner operator.

**next()**:
1. Call `inner.next()`.
2. If inner returns a tuple: combine `outerTuple` and `innerTuple` into one merged Tuple. Evaluate join condition. If true: return merged Tuple.
3. If inner returns null (exhausted): fetch next `outerTuple` from outer. If outer is exhausted: return null. Otherwise: close and reopen inner (reset it to the beginning). Continue.

The inner operator is reopened once per outer row — this is the "nested loop." Cost is O(outerRows × innerRows) in the worst case.

### 6.9 Hash Join Operator

Two-phase join for large tables with equality conditions.

**Phase 1 — Build** (during `open()`):
1. Open inner operator.
2. Read every tuple from inner.
3. For each tuple, compute the hash of the join key column's value.
4. Insert into an in-memory hash table: `Map<joinKeyValue, List<Tuple>>`.
5. Close inner.

**Phase 2 — Probe** (during `next()`):
1. Open outer operator (if not already open).
2. For each outer tuple:
   a. Compute hash of outer's join key.
   b. Look up in hash table.
   c. For each matching inner tuple: merge the two tuples and put in a result buffer.
3. Drain the result buffer one tuple at a time per `next()` call.
4. When buffer empty, fetch next outer tuple and probe again.
5. Return null when outer is exhausted.

Hash join is faster than NLJ for large tables because both inputs are scanned only once. The trade-off is memory: the entire inner relation's hash table must fit in memory.

### 6.10 Sort Operator

Sort is a **blocking operator** — it cannot produce any output until it has consumed all input.

**open()**: open child. Read every tuple into an in-memory list. Sort the list using a comparator derived from the `orderBy` specification (column name + ASC/DESC direction). After sorting, set an internal index to 0.

**next()**: return `sortedList[index++]` until exhausted, then return null.

For very large result sets, an external sort (merge sort using temporary disk pages) would be needed. For the capstone, in-memory sort is acceptable.

### 6.11 Limit Operator

**open()**: open child. Skip `offset` tuples by calling `child.next()` that many times.

**next()**: if `count >= limit`, return null. Otherwise, get one tuple from child, increment count, return it.

### 6.12 Aggregation

For queries with `COUNT(*)`, `SUM(col)`, `AVG(col)`, `MIN(col)`, `MAX(col)`:

Aggregation is also a blocking operator. Consume all input, accumulate running values (count, sum, min, max), then produce a single output row (or one row per GROUP BY group, if GROUP BY is supported).

For the capstone, implement at minimum `COUNT(*)` and `COUNT(col)`. GROUP BY is a stretch goal.

### 6.13 Expression Evaluator

The expression evaluator takes any AST expression and a current Tuple and returns a value. It is used by:
- Filter operators (evaluating WHERE predicates)
- Projection operators (computing output column expressions)
- Join operators (evaluating ON conditions)

The evaluator must handle:
- `ColumnRef` — look up the column's value in the Tuple's value map. Handle both qualified (`users.age`) and unqualified (`age`) references.
- `Literal` — return the literal's value directly.
- `BinaryOp` — recursively evaluate both sides, then apply the operator. For comparison operators, return a boolean. For arithmetic operators, return a numeric value.
- `UnaryOp` — evaluate the operand, then apply NOT.
- `FunctionCall` — only meaningful in aggregation context.

Column name resolution for joins: When two tables are joined, a Tuple's value map will contain both tables' columns. Prefix column names with the table name to avoid ambiguity: `users.age` and `orders.age` are different columns. The evaluator must resolve unqualified names by searching all columns.

### 6.14 INSERT and DELETE Execution

These do not go through the optimizer. The execution engine handles them directly:

**INSERT**: parse the value expressions, serialize them to raw bytes using the schema, call `mvccManager.insert(transaction, heapFile, rawBytes)` — Member 3 handles WAL logging and version chain management.

**DELETE**: do a sequential scan with the WHERE predicate. For each matching tuple, call `mvccManager.delete(transaction, heapFile, rid)` — Member 3 handles the rest.

---

## 8. Interfaces You Consume From Other Members

**From Member 1 (Storage):**

| Interface | What You Do With It |
|-----------|---------------------|
| `HeapFile.scan()` | Drive SeqScanOperator |
| `HeapFile.getTuple(rid)` | Fetch specific tuples |
| `Catalog.getTable(name)` | Schema lookup before executing any query |
| `Catalog.getColumns(tableId)` | Know column order for deserialization |
| `Catalog.getStats(tableName)` | Drive optimizer cost estimates |
| `Catalog.getIndexes(tableId)` | Know which indexes exist for plan selection |
| `Catalog.registerIndex(...)` | After CREATE INDEX completes |
| `BufferPool.fetchPage(pageId)` | Load B+Tree nodes |
| `BufferPool.unpin(pageId, dirty)` | Release B+Tree nodes |
| `BufferPool.newPage()` | Allocate new B+Tree nodes |

**From Member 3 (MVCC):**

| Interface | What You Do With It |
|-----------|---------------------|
| `MVCCManager.getVisibleVersion(rid, snapshot)` | Every tuple read in every scan operator. Returns raw bytes or null. |
| `MVCCManager.insert(txn, heapFile, bytes)` | Called during INSERT execution |
| `MVCCManager.delete(txn, heapFile, rid)` | Called during DELETE execution |

---

## 9. What You Expose to Other Members

You do not expose interfaces to Member 1. Member 3 does not call your code directly. These are team-level touchpoints:

- `QueryExecutor.execute(sql, transaction)` — top-level entry point. Member 3's transaction is passed in.
- Column schema and tuple format — agree with Member 1 on the exact binary serialization of tuples so your deserializer matches their serializer.
- RID usage — your B+Tree stores RIDs produced by Member 1's heap file. These must be opaque and stable.

---