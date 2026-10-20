<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Write-back: editing and saving fields on a Sutra page

Status: **design, not built.** Planned for **2.7.0** (decided 2026-10-19; AI moves to 2.8.0); decisions in section 16 (2026-10-19 and
2026-10-20). Related: [DATABASE.md](DATABASE.md) (the one Drishti database: the edit log, lanes, overlays and owned data live in it;
2.7.0 item 0), [RUPAKA.md](RUPAKA.md) (Sutra and the renderer), [DRISHTI_NET.md](DRISHTI_NET.md) (several servers),
[GENUI.md](GENUI.md) section 11 (write-back forms, deferred to this design), [BI_DESIGN.md](BI_DESIGN.md) (review and proposals, reused
here), [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) (connector files, where `writes:` lives).

## Contents

1. [The ask](#1-the-ask) · 2. [Principles](#2-principles) · 3. [The idea in one picture](#3-the-idea-in-one-picture) ·
4. [Declaring what is editable](#4-declaring-what-is-editable-the-sutra) · 5. [Where an edit goes, and who writes it](#5-where-an-edit-goes-and-who-writes-it)
(5.1 [three targets](#51-three-write-targets), 5.2 [who performs the write](#52-who-performs-the-write), 5.3 [source commands: the outbox](#53-source-commands-the-outbox),
5.4 [connector `writes:`](#54-how-a-write-is-described-connector-writes)) ·
6. [The change request](#6-the-change-request) · 7. [Edit queues (lanes), the edit log and the transaction handler](#7-edit-queues-lanes-the-edit-log-and-the-transaction-handler) ·
8. [Concurrency: why a queue alone is not enough](#8-concurrency-why-a-queue-alone-is-not-enough) · 9. [Approval](#9-approval-four-eyes) ·
10. [On screen](#10-on-screen) · 11. [Several servers: ordering across a cluster](#11-several-servers-drishtinet-ordering-across-a-cluster) ·
12. [Off by default and controlled by roles](#12-off-by-default-and-controlled-by-roles) ·
13. [APIs, CLI and configuration](#13-apis-cli-and-configuration) · 14. [Build plan](#14-build-plan) · 15. [Tests](#15-tests) ·
16. [Decisions](#16-decisions-product-owner-2026-10-19-and-2026-10-20) · 17. [Risks and open questions](#17-risks-and-open-questions)

## 1. The ask

The product owner (2026-10-19): a Sutra page should let a user **edit fields on the screen and save them**. Behind the scenes there
should be a **dedicated edit queue** and a **transaction handler** that processes updates **sequentially within a queue and concurrently
across queues**, so that at one exact moment only one edit is applied to a given thing, removing the classic concurrent-update problems.

This design keeps that model and makes three refinements (sections 7 and 8): edits are ordered per **record being changed** rather
than per Sutra; the records share a **fixed number of lanes** (queues) instead of one queue each, so millions of entities never mean
millions of queues; and every edit carries **the version it was based on**.

## 2. Principles

1. **Nothing is editable unless a Sutra says so**, field by field; masks win (a field you cannot see you cannot edit).
2. **Drishti never silently changes a source system's data.** An edit is a change request; where it lands is declared (section 5).
3. **One edit at a time per record, many records at once** (the queue model, section 7).
4. **No lost updates:** an edit based on data that has since moved is refused with a diff, never applied blindly (section 8).
5. **Every change is audited and replayable**; who changed what, when, why, from which value, is visible in *How this view was built*.
6. **Same rules everywhere:** the UI, the CLI and the API make the same change requests through the same handler.
7. **Off by default, at three levels:** the site switch, the connector's `writes:` and the Sutra's `edit:`; a fresh install or an
   upgrade is read-only exactly as today (section 12).
8. **Acknowledged means durable:** a change is acknowledged only once its row is committed in the edit log, in the one Drishti
   database ([DATABASE.md](DATABASE.md)); a node that dies after the acknowledgement loses nothing (sections 7 and 11).
9. **The connector is the one place that knows a source system**, for reading and for writing (section 5.4).

## 3. The idea in one picture

```
 Browser (Sutra page)                 Server                                             Targets
 ┌──────────────────────┐   POST     ┌────────────────────────────────────────────┐
 │ edit cells / form    │ ─change──▶ │ Change API: validate (DEL), masks, role,   │
 │ pending-change bar   │  request   │ version check, approval policy             │
 │ "3 changes · Submit" │            └──────────────┬─────────────────────────────┘
 └─────────▲────────────┘                           │ lane = hash(record key) mod P
           │ live tick                ┌─────────────▼─────────────┐
           │ (applied / refused)      │ Lanes 0..P-1 (P = 64)     │  ── one worker per lane, in order
           │                          │ L17: MX-50000001 ▸ CP-9 ▸ │     lanes in parallel
           │                          │ L42: CLY-30000001 ▸       │
           │                          └─────────────┬─────────────┘
           │                                        │ apply (transaction handler)
           │                       ┌────────────────┼──────────────────────┐
           │                       ▼                ▼                      ▼
           │                 Overlay rows     Drishti-owned rows     Source command
           │                 (corrections,    (notes, limits,        (outbox → connector
           │                  annotations)     mappings)              writes: REST/JDBC/Kafka…)
           │                 └──── in the Drishti database, one transaction with ────┘
           └──────────────── the edit log row and the epoch check (audit, replay) ◀──┘
```

The lake (Delta, Iceberg) is not in this picture on purpose: it is never written per edit; the loader folds approved overlays into the
next day's lake build (section 5.2).

## 4. Declaring what is editable (the Sutra)

An `edit:` block on a strip cell, a table column or a panel field:

```yaml
strip:
  - label: Trader
    bind: $.TRADER_ID
    edit:
      type: enum                     # text | number | decimal | date | datetime | bool | enum | ref
      options: { kind: trader }      # enum from another kind (or a fixed list: [A, B, C])
      required: true
      valid: "value != ''"           # DEL, run in the browser for feedback and on the server as the authority
      roles: [trade-support]         # who may edit (beyond who may read)
      target: overlay                # overlay | owned | source (section 5); default from the kind's edit policy
      reason: required               # a free-text reason must accompany the change
panels:
  - id: legs
    type: table
    rows: $.LEGS[*]
    columns:
      - { label: Notional, bind: $.NOTIONAL, fmt: amount, edit: { type: decimal, valid: "value > 0", roles: [middle-office] } }
```

- Shared blocks carry `edit:` too, so a field made editable once is editable in every Sutra that uses the block.
- A kind may declare a default **edit policy** in its pack (`edit-policy: { target: overlay, approval: none }`); a Sutra field may only
  narrow it (stricter roles, approval), never widen it.
- `sutra lint` refuses an `edit:` on a computed binding (an expression, not a path), on a masked field, or a `target: source` whose
  `command:` is not a command in the kind's connector's `writes:` (section 5.4).

## 5. Where an edit goes, and who writes it

### 5.1 Three write targets

| Target | What it is | For | Risk |
|---|---|---|---|
| **overlay** | Drishti keeps the new value as a dated layer *on top of* the source; the page shows the merged value with a marker ("edited · source says X") | corrections, annotations, flags, comments | none to the source; the overlay can disagree with the source, and the page says so |
| **owned** | the kind is Drishti's own (Drishti is its system of record): the value is a row in the Drishti database | notes, limits, mappings, reference overrides | the store is Drishti's: backed up and versioned like any |
| **source** | a named command of the connector's **`writes:`** (section 5.4) is sent to the source system: REST call, JDBC update, Kafka message, vendor API | genuine amendments in ENDUR, CALYPSO, MUREX | real: off by default, per connector, approval recommended |

- **Overlays and as-of.** An overlay entry has a business date and a valid-from; a view of date D shows overlays valid on D. Editing a
  past date is a **restatement** and needs a stronger role (section 12). Overlays can expire (`until:`) or be withdrawn.

### 5.2 Who performs the write

**The lane owner's transaction handler does every write** (section 7 for the lane, section 11 for which node owns it). No other code,
node, page or job writes an edit's effect anywhere.

| Target | Where the handler writes | In which transaction |
|---|---|---|
| overlay | a row of `drishti_overlay` in the Drishti database ([DATABASE.md](DATABASE.md)) | **the same transaction** as the edit-log row's state change and the lane's epoch check (section 11) |
| owned | the kind's rows in the Drishti database | the same, one transaction |
| source | nothing in the source directly: the edit-log row becomes `command: pending` (the outbox, section 5.3) | the same, one transaction; the source's own write happens later, in the source's own transaction |
| the lake (Delta, Iceberg) | **never, per edit** | the loader folds approved overlays into the next day's build |

- **Readers lay overlays over the source document at read time.** The engine reads the document as today, then applies the overlays
  valid on the view's business date (one indexed lookup per entity, cached by the entity's generation and invalidated by the live
  tick of an applied change). *How this view was built* names each overlay applied.
- **The lake is never written per edit.** Approved overlays are folded into the **next day's lake build** by the loader (`lake build` /
  `lake publish`), which writes the corrected value with the change id and the source's value beside it in `_drishti_overlay` columns
  (decision 16.1). Not per edit because: (a) every edit would be one tiny file and one commit, so a busy day means thousands of small
  Parquet files and a Delta or Iceberg log that grows with every change, slowing every read until compaction; (b) the daily rebuild of
  a business date writes the date afresh from the source, so a value written between builds would be overwritten anyway. A reader of a
  folded row skips the overlay with the same change id, so a value is never applied twice.

### 5.3 Source commands: the outbox

A source system is written through its connector, in **its own transaction**, using the **outbox pattern**, so that Drishti's record
and the source never disagree about whether a command was meant:

```
 lane owner                                    Drishti database                      source system
 ──────────                                    ────────────────                      ─────────────
 1. apply ── one transaction ──────────────▶  edit_log row: command = pending
             (epoch check, section 11)          (+ the change, the epoch)
 2. sender ── reads pending, in seq order ──▶                         ── command(change id) ──▶ own transaction
 3. confirmation (reply | ack topic | next load) ──▶ command = applied | refused (+ reason) ◀──┘
 4. new owner after a failover: resends pending and sent-unconfirmed commands with the same change id ─▶ idempotent at the source
```

1. **One Drishti transaction** appends the change as `command: pending`, with the epoch check: either the change is in the log and
   will be sent, or neither.
2. **The sender** (a virtual thread on the lane owner) sends pending commands in seq order, each carrying the **change id**; it records
   `sent` with the time. Two commands for one record are never in flight together: the next waits for the previous one's confirmation,
   so the source sees them in order. A slow source never blocks the lane: the lane goes on applying overlay and owned changes.
3. **The confirmation** is what the command's `confirm:` says: the synchronous **reply** (JDBC rows affected, an HTTP status), a
   message on an **ack topic**, or the **next load** showing the value. It marks the row `applied`, or `refused` with the source's
   reason. Until then the page shows *pending at source*; past the command's `timeout:` it shows *unconfirmed* on Admin → Edits for an
   operator.
4. **A new owner resends** every pending and sent-but-unconfirmed command of its lanes with the **same change id**; the source applies
   it at most once (the idempotency clause of section 5.4). A resend never applies twice.

### 5.4 How a write is described: connector `writes:`

Three layers, each saying one thing:

| Layer | Says | Where |
|---|---|---|
| the Sutra's **`edit:`** | **WHAT** may change: the fields, their types and DEL check, the roles, the target (`overlay`, `owned`, `source`), the `command` to use and its parameters, the approval | the Sutra (section 4) |
| the connector's **`writes:`** | **HOW** to change it in that system: named command templates, each with its concurrency guard, its idempotency and how it is confirmed | the connector file in `config/connectors/` |
| the **lane owner** | **WHEN** and in what **ORDER**: the lanes, the version check, the transaction, the outbox | generic code, the same for every source |

So **the connector is the one place that knows a source system**, for reading and for writing: a Sutra never holds SQL, a URL or a
topic; a command never knows about roles or lanes.

A Sutra field that amends the source:

```yaml
panels:
  - id: legs
    type: table
    rows: $.LEGS[*]
    columns:
      - label: Notional
        bind: $.NOTIONAL
        fmt: amount
        edit:
          type: decimal
          valid: "value > 0"
          roles: [middle-office]
          target: source
          command: amend-notional          # a command of the kind's connector's writes:
          params: { leg: $.LEG_NO }          # extra parameters, read from the row being edited
          approval: one
          reason: required
```

The connector's file (an excerpt of `config/connectors/trade-murex.yaml`):

```yaml
writes:
  amend-notional:
    kind: trade
    jdbc:
      sql: >
        UPDATE MX_TRADE_LEG
           SET NOTIONAL = :value, VERSION = VERSION + 1, DRISHTI_CHANGE_ID = :changeId
         WHERE TRADE_ID = :entity AND LEG_NO = :leg AND VERSION = :basedOn
      expect-rows: 1                     # the concurrency guard: 0 rows means the record moved (conflicted)
      already-applied: >                 # the idempotency clause: a resend finds its own change id
        SELECT 1 FROM MX_TRADE_LEG WHERE TRADE_ID = :entity AND LEG_NO = :leg AND DRISHTI_CHANGE_ID = :changeId
    confirm: next-load                   # reply | ack-topic | next-load
    timeout: 30s
```

How each kind of connector says it:

| Connector | Command template | Concurrency guard | Idempotency |
|---|---|---|---|
| **JDBC** (PostgreSQL, Oracle, SQL Server …) | `sql:` an `UPDATE` / `INSERT` with `:params` | `WHERE version = :basedOn` and `expect-rows: 1` | a change-id column, and `already-applied:` that finds it |
| **REST** | `method:`, `url:` (with `{params}`), `body:` (a JSON template) | `If-Match: :basedOn` (an ETag or version); `412` is conflicted | an `Idempotency-Key: :changeId` header |
| **Kafka / MQ** | `topic:` (or queue), `key:`, `message:` (a JSON template) | the version in the message (`"basedOn": :basedOn`); the consumer refuses a stale one and says so on the ack topic | the change id as the message key; the consumer de-duplicates by it |
| **Aerospike** | a bin `put:` on the record's key | the record's generation (`generation: :basedOn`, `EXPECT_GEN_EQUAL`) | the change id in a bin, checked before the put |
| **MongoDB** | `updateOne:` with a filter and an update document | the filter includes `{ version: :basedOn }`; `matched: 1` expected | the change id in the document; the filter skips a document already holding it |

- **`SourcePlugin.write(Command)` is implemented once per plugin** (JDBC, REST, Kafka, Aerospike, MongoDB …), never per source system:
  it fills the template with the parameters and runs it. A plugin without it cannot have `writes:` (today all are read-only).
- **A command is validated when the connector loads:** every `:param` must be one Drishti supplies (`:entity`, `:kind`,
  `:businessDate`, `:value`, `:from`, `:basedOn`, `:changeId`, `:by`, `:reason`, `:at`, and the `params:` a Sutra may declare by
  name), and the **guard** and **idempotency** clauses are required. A command missing either, or naming an unknown parameter, is
  **refused**: the connector loads read-only and Admin → Connectors shows why. Parameters are always bound (prepared statements,
  encoded URLs and JSON), never pasted into text.

## 6. The change request

```json
{
  "id": "chg-01JB3Z…",                 // assigned by the server (ULID: time-ordered)
  "key": "trade:MX-50000001",          // the queue key (section 7)
  "kind": "trade", "entity": "MX-50000001", "businessDate": "2026-10-16",
  "items": [ { "path": "$.TRADER_ID", "from": "TRDR-FROSSI", "to": "TRDR-AKHAN" } ],
  "basedOn": { "version": "gen-412", "hash": "sha256:…" },   // what the user saw (section 8)
  "target": "source", "connector": "trade-murex",
  "reason": "trader moved desks", "by": "asha", "at": "2026-10-19T14:02:11Z",
  "state": "queued"                     // draft → submitted → (awaiting approval) → queued → applying → applied | refused | conflicted | withdrawn
}
```

- **Bulk edits** are one request with many items (or many entities); the handler applies them as one unit per key.
- **Drafts** live in the user's state (they follow the user across nodes like saved searches) until submitted. A submitted change
  awaiting approval is a row of `drishti_edit_request` in the Drishti database; it takes its place in a lane (a seq in the edit log)
  only when it is queued.

## 7. Edit queues (lanes), the edit log and the transaction handler

**The model (the product owner's):** a dedicated queue; one worker drains a queue **strictly in order**; different queues are drained
**in parallel**. At any instant at most one change is being applied to a given record.

**Refinement 1: the key is the record being changed, not the Sutra.**

| Key choice | Problem |
|---|---|
| per Sutra | one Sutra renders every entity of a kind (all MUREX swaps): two traders editing two different trades would wait for each other; and two Sutras can show the same trade (a generic trade page and a MUREX swap page), so their queues would *not* serialise edits to that trade |
| **per entity** (`kind:id`), default | exactly the guarantee wanted: one change at a time per entity, unrelated entities in parallel |
| per source record (`connector:table:key`) | used when one entity spans several written records, or several kinds write the same record: the key is what is actually written |

The Sutra still matters: it decides *which* fields are editable and how; the queue decides *when* a change is applied.

**Refinement 2: a fixed number of lanes, not one queue per key** (product owner, 2026-10-19: per-entity queues would be a very large
number of queues). Like a partitioned log:

- **`P` lanes** (`edit.lanes`, default 64, a power of two). Each lane is one durable ordered queue with **one worker**.
- **A key always maps to the same lane:** `lane = murmur3(key) mod P`, a stable hash, the same on every node and after a restart.
- **Sequential per record, guaranteed:** every change to a record goes through its one lane, and a lane applies one change at a time
  in arrival order. **Parallel across lanes:** up to `P` changes apply at once.
- **Bounded cost:** `P` queues and `P` workers whatever the number of entities (a hundred or fifty million).
- **The trade-off:** unrelated records hashed to one lane wait for each other. It stays small because (a) an apply is short: overlay
  and owned writes are local, and a source command is *sent* and recorded, its confirmation handled off the lane, so a slow source
  never blocks it; (b) consecutive queued changes to the same key are applied as one batch; (c) `P` is a setting, and the lane depth
  is a metric (Admin → Edits: per lane, depth, oldest wait, applies per second) with a warning when a lane's oldest wait passes
  `edit.lane-warn` (default 2 s).
- **Changing `P`** moves keys between lanes; it is done with the queues drained (a maintenance switch: new changes wait, the lanes
  empty, `P` changes, they resume), never live.

**The edit log: a durable, shared, ordered log in the Drishti database** ([DATABASE.md](DATABASE.md); decided 2026-10-20). A lane
*is* its rows in the log, so the queue survives any restart and is visible to every node that shares the database:

```sql
CREATE TABLE IF NOT EXISTS drishti_edit_lane (       -- one row per lane, P rows
    lane         INTEGER   PRIMARY KEY,
    owner_node   TEXT,                                -- the node that may append and apply (section 11)
    epoch        BIGINT    NOT NULL DEFAULT 0,        -- +1 at every change of owner: the fencing token
    lease_until  TIMESTAMP,                           -- the owner renews it; another node may take the lane only after it
    next_seq     BIGINT    NOT NULL DEFAULT 1,        -- the seq the next appended change gets
    applied_seq  BIGINT    NOT NULL DEFAULT 0         -- the last seq applied: a new owner replays from here
);
CREATE TABLE IF NOT EXISTS drishti_edit_log (
    lane         INTEGER   NOT NULL,
    seq          BIGINT    NOT NULL,                  -- the lane's order
    change_id    TEXT      NOT NULL UNIQUE,           -- the ULID of section 6: a retry or resend is recognised by it
    epoch        BIGINT    NOT NULL,                  -- the owner's epoch when appended
    state        TEXT      NOT NULL,                  -- queued | applying | applied | refused | conflicted | withdrawn
    change_key   TEXT      NOT NULL,                  -- trade:MX-50000001
    kind TEXT NOT NULL, entity TEXT NOT NULL, business_date DATE NOT NULL, target TEXT NOT NULL, connector TEXT,
    body         TEXT      NOT NULL,                  -- the change request (section 6), as JSON
    command      TEXT,                                -- source only: pending | sent | applied | refused (section 5.3)
    command_attempts INTEGER NOT NULL DEFAULT 0, command_sent_at TIMESTAMP, command_reason TEXT,
    by_user TEXT NOT NULL, submitted_at TIMESTAMP NOT NULL, applied_at TIMESTAMP, signature TEXT NOT NULL,
    PRIMARY KEY (lane, seq)
);
```

(The two schema files carry these, with the dialect's types; the parity test keeps them equal.)

- **Acknowledged only after commit.** The lane owner appends the change (takes `next_seq`, inserts the row, both conditional on its
  epoch, section 11) in one transaction; only when that commits does the user get "queued". A crash before the commit means no
  acknowledgement (the page keeps the draft and the retry carries the same change id); a crash after it loses nothing.
- **The lane's order is its seq**, not arrival time at a node nor a clock.
- **A new owner replays from `applied_seq`:** every row after it is applied in seq order. A row found `applying` is applied again
  safely: overlay and owned writes check the change id in the same transaction (an applied change id is skipped), and source commands
  are idempotent (section 5.3).

**The handler, per change taken from a lane, in seq order:**
1. re-check the role, masks and the field's `edit:` rules (the Sutra may have changed since submit; section 12);
2. read the current version of the entity and compare with `basedOn` (section 8);
3. run the server-side validation (DEL) against the *current* document with the change applied;
4. **in one Drishti database transaction:** check the lane's epoch (section 11), write the overlay or owned rows, or mark the
   command `pending` for a source target (section 5.3), set the row `applied` (or `refused`, `conflicted`) and advance `applied_seq`;
5. bump the entity's version and publish a live tick to every viewer.

**Implementation shape.** One virtual thread per owned lane, parked when its lane is empty and woken by an append (and, for appends
forwarded from other nodes, by the forward itself). No locks are held across I/O (ReentrantLock where needed, never `synchronized`
around I/O).

**Back-pressure.** A lane deeper than `edit.max-lane-depth` (default 1,000) refuses new changes with "busy, try again"; the page keeps
the draft.

## 8. Concurrency: why a queue alone is not enough

A queue serialises *writes*, but not *decisions*. Asha and Ben both open trade MX-50000001 at version 7. Asha changes the trader and
saves; it applies (version 8). Ben, still looking at version 7, changes the trader too; his change queues after Asha's and would
overwrite it without either of them knowing: a **lost update**, even though the writes never overlapped.

So every change carries **`basedOn`**, the version (and a hash of the fields it touches) the user saw:
- If the touched fields are unchanged since `basedOn`, the change applies (unrelated changes in between do not conflict: field-level).
- If any touched field moved, the change is **conflicted**: the page shows a three-way diff (what you saw, what is there now, what you
  wanted) with **Apply mine anyway** (needs the field's `override` role), **Take theirs**, or **Edit again**.
- Changes to the source outside Drishti (a load, a ticket in ENDUR) bump the version too, so they are detected the same way.

Together, **queue + version check** give: no two writes to a record at once, and no write based on stale data.

## 9. Approval (four eyes)

- Policy per kind, per field, per target: `approval: none | one | two`, approver roles, and whether self-approval is barred (default
  barred).
- An approval-needing change waits in **awaiting approval** (not in the queue); on approval it is enqueued and the version check runs
  *then*, against the data at apply time.
- **Review → Changes** lists what awaits you, with the diff, the reason and the entity page beside it; approve, reject with a reason,
  or ask for a change. It reuses the BI review and proposal machinery (Review → AI drafts already lists drafts the same way).
- A recommended default: overlays `none`, owned `none`, source `one`.

## 10. On screen

- **Edit mode toggle** on a page the user may edit (a pencil in the top bar; keyboard `e`). Off by default so reading is unchanged.
- **Inline edit:** an editable cell shows a pencil on hover; click or Enter edits in place with the field's own format; Tab moves on;
  Esc cancels.
- **Form mode:** a panel can open as a form built from its `edit:` fields (the query-by-form renderer reused).
- **Pending-change bar:** "3 changes · Review · Submit · Discard"; edited cells are marked; the page keeps ticking live, and an edited
  cell that ticks underneath shows "changed while you edited" at once (an early warning before submit).
- **After submit:** each change shows its state on the cell (queued, awaiting approval, pending at source, applied, refused, conflicted);
  applied changes tick to every viewer.
- **History:** a cell's menu shows its change history (from the edit log); *How this view was built* names overlays applied.
- Phone width: form mode only (no inline grid editing); the bar sits at the bottom.

## 11. Several servers (Drishti*net): ordering across a cluster

The guarantee (one change at a time per record, in submission order, nothing lost once acknowledged) must hold when nodes die and
when the network splits. Gossip and the ring alone cannot give it: after a split two halves can each believe they own a lane. So
(decided 2026-10-20) the edit log is **durable and shared** (section 7), ownership is **taken in the store**, and every write is
**fenced** by an epoch. This replaces the earlier "the change log replicated like user state".

```
 console ──▶ any node ──forward (signed, as the user)──▶ lane owner ──one transaction──▶ Drishti database
                 │                                       (epoch e)      append row (lane, seq, change_id, e)
                 └── reads the owner from drishti_edit_lane             ack only after commit
                                                                        apply: UPDATE … WHERE epoch = e
 node dies / splits off ──▶ lease runs out ──▶ next node on the ring claims: epoch e+1 (conditional UPDATE)
                                               replays from applied_seq; the old owner's writes now fail (stale epoch)
```

**Ownership: taken by a conditional UPDATE; the ring only suggests who should try.** The `P` lanes are placed on the member ring
(Admin → Net → Members shows each node's lanes); the node the ring names for a lane *tries* to take it, and the store decides:

```sql
-- take a lane whose lease has run out (the database's clock, never a node's)
UPDATE drishti_edit_lane
   SET owner_node = :me, epoch = epoch + 1, lease_until = <now> + :lease
 WHERE lane = :lane AND (owner_node IS NULL OR lease_until < <now>);          -- 1 row: mine, at the new epoch; 0: someone else's
-- renew (every lease/3)
UPDATE drishti_edit_lane SET lease_until = <now> + :lease
 WHERE lane = :lane AND owner_node = :me AND epoch = :myEpoch;                 -- 0 rows: I have lost it; stop the lane at once
```

Exactly one claimant's UPDATE matches; the lease (`edit.lane-lease`, default 10 s) only decides *when* someone may try. A node that
leaves cleanly releases its lanes (`lease_until = <now>`), so they move at once.

**Fencing: every append and every apply checks the epoch in the same transaction.**

```sql
UPDATE drishti_edit_lane SET next_seq = next_seq + 1
 WHERE lane = :lane AND owner_node = :me AND epoch = :myEpoch RETURNING next_seq - 1;   -- append: 0 rows, not the owner
UPDATE drishti_edit_lane SET applied_seq = :seq
 WHERE lane = :lane AND owner_node = :me AND epoch = :myEpoch;                          -- apply: 0 rows, roll back
```

If the epoch moved, the statement matches no row and the whole transaction (the overlay rows, the state, the outbox mark) rolls
back. So a **zombie owner** (a node cut off by a network split that still thinks it owns the lane, a node paused by a long GC) can
never write: its epoch is stale the moment the new owner takes the lane.

**Any console to any server works.** The receiving node computes the lane, reads its owner from `drishti_edit_lane` (cached, and
re-read when a forward is refused as "not the owner"), and forwards the change, signed and on behalf of the user, to the owner. The
owner appends and applies; the receiving node relays the acknowledgement and the live ticks. Source commands go out once, from the owner
(section 5.3).

**Where the shared log lives: the fallbacks.**

| Setup | Edit log store | Guarantee |
|---|---|---|
| **a single node** | SQLite, in the Drishti database: the same schema and the same code path (the node owns every lane; the epoch checks still run) | full, for one node |
| **a cluster with PostgreSQL** (the Drishti database is PostgreSQL) | the tables above, shared | **full**: failover, fencing, any node |
| **a cluster on AWS without PostgreSQL** | **S3 conditional writes**: one object per lane and seq, the lease an object updated by ETag (below) | full, at S3's latency (tens of ms per append) |
| **a cluster with neither** | one designated **edit primary** node holds the log in its SQLite Drishti database; the others forward every change to it | ordering full; **no failover**: if the primary is down, edits pause with a clear message and reads carry on |

*S3 layout* (`edit.s3: { bucket, prefix }`; the bucket's conditional-write support is checked at start, as for the Delta lake on S3):
- `…/edit/lanes/<lane>/lease.json` holds `{owner, epoch, lease_until}`; it is taken or renewed by a `PUT` with `If-Match: <the ETag
  read>` (`If-None-Match: *` the first time), so of two claimants exactly one succeeds.
- `…/edit/lanes/<lane>/log/<seq, 20 digits>.json` is one change, written with `If-None-Match: *`, so a seq is written once.
- A new owner changes the lease (epoch e+1) **before** it writes a *fence* entry `{fence: e+1}` at the next seq; an entry after a
  fence with a lower epoch is **void** and never applied. The old owner re-reads the lease after each `PUT` and acknowledges only if
  its epoch still holds; because the lease changed before the fence, an entry after the fence is never acknowledged.
- Without a shared database, each node keeps overlay and owned rows in its own Drishti database by **following the log** in seq order
  (idempotent by change id; `edit.follow`, default 1 s); state changes (applied, command sent) are appended as entries too.

*Edit primary* (`edit.primary: <node id>`): it owns every lane, permanently; there is **no automatic failover**, because two nodes on
either side of a split could otherwise both decide to be primary. Moving the primary is a configuration change made with the old one
stopped. While it is unreachable, every node answers an edit with *Edits are paused: the edit primary (drishti-main) is not reachable;
your changes are kept as drafts. Reading is not affected.* The other nodes follow its log for overlays, as for S3. It never risks two
writers.

**The setting:** `edit.log: auto | sqlite | postgres | s3` (in `config/edit/edit.yaml`). The **auto rule**, in order:
1. the Drishti database is PostgreSQL → `postgres`;
2. this node is alone (Drishti*net off, or the ring has one member) → `sqlite`;
3. `edit.s3` is set and its conditional-write check passes → `s3`;
4. `edit.primary` is set → `sqlite` on the primary, every other node forwards to it;
5. otherwise edits are **refused in the cluster**, with the message *this cluster has no shared edit log: use PostgreSQL for the Drishti
   database, set `edit.s3`, or name an `edit.primary`*. Reads are unaffected.

An explicit value is checked: `postgres` needs a PostgreSQL Drishti database; `s3` needs `edit.s3`; `sqlite` in a cluster needs
`edit.primary`; otherwise the server refuses to start and says which.

**Admin → Edits** shows the store in use (and why, from the auto rule), the owner, epoch and lease of every lane, and **warns** when a
cluster runs without a shared store (*edit primary: no failover*), when the primary is unreachable, or when a lane's lease is not being
renewed.

## 12. Off by default and controlled by roles

**Off at three levels.** All three must be on before anything is written:

| Level | Off by default because | Turned on by |
|---|---|---|
| **the site**: `edit.enabled: false` in the shipped `config/edit/edit.yaml` | no edit is accepted anywhere, by UI, CLI or API; no pencil is shown | an administrator setting `edit.enabled: true` (audited) |
| **the connector**: it writes only if its file declares `writes:` | none of the shipped connector files (`config/connectors.examples/`) declares one | an administrator adding `writes:` and switching it on in Admin → Connectors (administrator only, audited) |
| **the field**: editable only if a Sutra's `edit:` lists it | no shipped Sutra has a `target: source` edit | a Sutra author, through review (governance) like any Sutra change |

Overlay and owned targets need the first and third levels only; a source target needs all three.

**The roles:**

| To | You need | Notes |
|---|---|---|
| edit at all (see the pencil, submit anything) | the **`edit` power** on one of your roles (Admin → Roles) | without it, every page reads as today |
| edit a given field | one of the field's **`roles:`** in its `edit:` | narrows, never widens, the kind's edit policy (section 4) |
| approve a change | the policy's **approver role** | **never your own change** (four eyes, section 9) |
| *Apply mine anyway* on a conflict | the field's **`override`** role | audited with the overwritten value |
| edit a past business date (a restatement) | the **`restate`** role, stronger than the field's | per kind in `config/edit/policy.yaml` |
| turn on writes for a connector | **administrator** | audited; shown on Admin → Connectors and Admin → Edits |

- **Everything is re-checked at apply time**, by the lane owner, not only at submit: roles, the field's `edit:`, the masks, the packs.
- **Masks hold:** a field you cannot see you cannot edit; a change can never *reveal* a masked value (the diff shows masks for fields
  you cannot see).
- **Packs per user apply:** you can edit only kinds of the packs you are given.
- **API tokens** need the explicit **`edit`** scope (chosen when the token is created); **no token has it by default**, and an existing
  write scope does not imply it. A token never exceeds its user's current roles.
- **A fresh install or an upgrade behaves exactly as today: read-only.** Upgrading to 2.7.0 changes nothing until an administrator
  turns editing on.

**Audit.**
- The edit log is append-only, signed per entry (the same signing as audit), kept per retention policy; exports for compliance.
- Every change is audited at submit, approval, apply, and refusal, with the user, node, and the before/after values (masked per reader
  when shown).

## 13. APIs, CLI and configuration

- **API:** `POST /api/v1/changes` (submit), `GET /api/v1/changes?state=&kind=&entity=` (list), `GET /api/v1/changes/{id}`,
  `POST /api/v1/changes/{id}/approve|reject|withdraw`, `GET /api/v1/entities/{kind}/{id}/history`. A token needs the `edit` scope
  (section 12).
- **CLI:** `drishti.py change propose --kind trade --id MX-50000001 --set TRADER_ID=TRDR-AKHAN --reason "..."`, `change list`,
  `change approve <id>`, `change history trade MX-50000001`.
- **Configuration**, like connectors and Drishti*net, in its own folder `config/edit/`: `edit.yaml` (`enabled: false` as shipped,
  `log: auto`, `primary:`, `s3:`, `follow:`; section 11), `policy.yaml` (defaults per kind and target, approval, the restate role),
  `lanes.yaml` (lanes, max-lane-depth, lane-warn, lane-lease, timeouts), `retention.yaml` (the edit log, section 16). The edit-log
  tables live in the Drishti database, configured in `config/db/database.yaml` ([DATABASE.md](DATABASE.md)). Writes to a source are
  the connector file's `writes:` (section 5.4), off unless declared and switched on.

## 14. Build plan

| Phase | What | Accepted when |
|---|---|---|
| W0 | **The one Drishti database** ([DATABASE.md](DATABASE.md), 2.7.0 item 0): one pool and `config/db/database.yaml`, two complete schema files with a parity test, the schema version stamp, `drishti.py db copy` | a 2.6.5 data folder starts with its users and settings; SQLite → PostgreSQL → SQLite keeps every row; a mismatched stamp refuses to start and names `db copy` |
| W1 | `edit:` in the Sutra schema and lint; Change API; the off-by-default switches and the roles (section 12); **the edit log on a single node with SQLite**: `drishti_edit_lane` and `drishti_edit_log` in both schema files, acknowledgement after commit, replay from `applied_seq`, the epoch checks (trivially one owner); lanes and handler; **owned** kinds and **overlays** as rows in the same transaction (corrections and notes first, section 16), overlays laid over the document at read time; version check; retention; inline edit and the pending bar | two users editing the same field: the second is conflicted with a diff, never silently overwritten; 100,000 concurrent changes over 1,000,000 entities on 64 lanes apply in per-entity order with no lost update (property test); a server killed after an acknowledgement applies the change once after restart; a fresh install is read-only |
| W2 | Approval (four eyes), Review → Changes, history, CLI | a change needing approval cannot apply without it; self-approval refused |
| W3 | **The cluster:** lane ownership by conditional UPDATE (the ring suggests), **fencing** by epoch on every append and apply, forwarding from any node, failover by lease; the **PostgreSQL** shared log, the **S3** log (lease by ETag, fences), the **edit primary** fallback, `edit.log: auto` and its rule, Admin → Edits (store, owners, warnings); the loader folding approved overlays into the next day's lake build (section 16) | the three cluster acceptance tests of section 15 pass on PostgreSQL and on S3; an approved overlay appears in the next lake build and in BI |
| W4 | `SourcePlugin.write` and **connector `writes:`** (validated at load), **source commands through the outbox** (pending, sent, confirmed; resend by the new owner with the same change id): the one real-amendment pilot (one field of one kind on one connector, JDBC first); pending at source | a source command applied twice by a retry changes the source once; a command without a guard or idempotency clause is refused at load; a command pending when its owner dies is sent once by the new owner |
| W5 | Form mode, bulk edit, phone, docs and a tutorial (UI, CLI, YAML) | a tutorial edits, approves and sees the change tick on another browser |

Placement: **2.7.0** (product owner, 2026-10-19), after 2.6.0 (statements, field parameters, the Cassandra connector, drishti-iceberg;
integration moved after 3.0.0 on 2026-10-20); AI (grounding, AI UI, GenUI, AI management)
becomes 2.8.0, GenUI's write-back forms build on this. W0 comes first (2026-10-20).

## 15. Tests

- **Ordering property test:** random concurrent submissions over random keys on few lanes (so many keys share a lane); per key,
  applied order equals submission order; no change lost; versions strictly increase; no lane ever has two active workers.
- **Lost-update test:** two stale edits to one field; the second is conflicted.
- **Field-level independence:** concurrent edits to different fields of one entity both apply.
- **Restart:** queued changes survive a kill and apply in order after restart; an *applying* change is not applied twice.
- **Security:** masked fields not editable; roles re-checked at apply; audit entries for every state; a token without the `edit`
  scope is refused; with `edit.enabled: false` every edit is refused and no pencil shows.
- **Connector `writes:`:** a command with an unknown `:param`, or without its guard or idempotency clause, is refused at load and the
  connector stays read-only; a stale `basedOn` at the source comes back conflicted.
- **Browser:** inline edit, pending bar, conflict dialog, approval flow, phone form mode, the *edits are paused* message.

**Cluster acceptance tests** (W3, each on PostgreSQL and on S3; checked from the edit log):
1. **Kill the owner right after the acknowledgement.** A change is acknowledged, then its lane's owner is killed (`kill -9`) before
   it applies. The new owner applies it **exactly once**, **in order** with the lane's other changes.
2. **A network split with two claimants.** The owner is cut off from the cluster but not from the store; the ring's next node claims
   the lane. **Only one writes:** the cut-off node's next append or apply is **refused by its stale epoch**, nothing it attempts after
   the claim is applied, and nothing it acknowledged before is lost.
3. **The ordering test under failure:** 100,000 concurrent changes over 1,000,000 entities on 64 lanes across three nodes, with one
   node killed and a split made mid-run. Per entity, applied order equals submission order; no acknowledged change is lost or applied
   twice; no lane has two writers at any epoch.

## 16. Decisions (product owner, 2026-10-19 and 2026-10-20)

**2026-10-19**

1. **Approved overlays flow back into the lake.** The next lake build writes an approved overlay's value into the table (with the
   change id and the source's value kept beside it, in `_drishti_overlay` columns), so BI, search, exports and anything reading the
   lake see the corrected value. Reconciliation with the source stays possible: an **Open overlays** report lists every overlay whose
   source value still differs, and a source load that now agrees closes it.
2. **Corrections and notes first.** W1–W3 deliver overlays and Drishti-owned kinds (comments, flags, name fixes, mapping overrides);
   real amendments in a booking system start in W4 as **one pilot**: one field of one kind on one connector, approval required.
3. **The change log is kept 7 years by default, configurable** (`config/edit/retention.yaml`: `keep: 7y`; per kind may be longer, never
   shorter than the site default). Entries older than the hot window (default 90 days) are archived to compressed, signed files under
   `<data dir>/edit/archive/` (or an object store) and stay searchable through the API; deletion only past retention, audited.

**2026-10-20**

4. **One Drishti database** ([DATABASE.md](DATABASE.md)): the edit log, lanes, overlays and owned data live in the same database as
   identity; PostgreSQL long term, SQLite the default for one node; no migrations (two complete schema files, a parity test, a version
   stamp, `drishti.py db copy`); its own file `config/db/database.yaml`. Built first, as 2.7.0 item 0 (W0).
5. **A durable shared edit log replaces "replicated like user state"** (sections 7 and 11): `drishti_edit_lane` (ownership by
   conditional UPDATE; the ring only suggests) and `drishti_edit_log` (acknowledged only after commit; order by seq; replay from the
   last applied seq); every apply fenced by the epoch in the same transaction; any console to any server, forwarded to the owner.
6. **Fallbacks:** one node on SQLite; a cluster on PostgreSQL (full guarantee); a cluster on AWS without PostgreSQL on S3 conditional
   writes; a cluster with neither on one named edit primary (edits pause if it is down; never two writers). `edit.log: auto | sqlite |
   postgres | s3` with the auto rule of section 11; Admin → Edits warns when a cluster has no shared store.
7. **The lane owner's transaction handler does every write** (section 5.2). Overlays and owned data are rows in the Drishti database,
   in the same transaction as the edit-log row and the epoch check, laid over the document at read time. **The lake is never written
   per edit**; the loader folds approved overlays into the next day's build. **Source systems are written through the outbox**
   (section 5.3), in their own transaction, idempotent by the change id.
8. **Connector `writes:`** (section 5.4): the Sutra says what, the connector says how (named command templates with a required guard
   and idempotency, validated at load), the lane owner says when and in what order. `SourcePlugin.write(Command)` once per plugin.
9. **Off by default and controlled by roles** (section 12): the site switch, the connector's `writes:` and the Sutra's `edit:`; the
   edit power, field roles, approver (never your own), override, restate and administrator-only connector writes; re-checked at apply;
   masks and packs hold; API tokens need the explicit `edit` scope. A fresh install or an upgrade is read-only, as today.

## 17. Risks and open questions

1. **Writing to ENDUR/CALYPSO/MUREX** is a governance decision; source commands stay off until a bank approves a connector.
2. **Overlay drift:** an overlay that disagrees with the source for long is a reconciliation item (the Open overlays report, decision 1).
3. **Hot keys and hot lanes:** one very busy entity is serialised by design, and its lane's neighbours wait; batching and the lane
   metrics address it, and `P` can be raised.
4. **A cluster without a shared store** (the edit primary) has no failover: edits pause while the primary is down. PostgreSQL is the
   answer for production; Admin → Edits says so.
5. **S3 latency:** an append on S3 costs a conditional `PUT` and a lease read (tens of milliseconds); fine for edits made by people, not
   for bulk loads (which go through the loader).
6. **A source that cannot guard or de-duplicate** (no version, no change-id column, no idempotency key) cannot have a `writes:`
   command; such a source is edited through overlays only.
7. **Open:** the pilot's kind, field and connector for W4.
