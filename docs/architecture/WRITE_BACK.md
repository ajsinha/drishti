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

Status: **design, not built.** Planned for **2.7.0** (decided 2026-10-19; AI moves to 2.8.0); decisions in section 16. Related: [RUPAKA.md](RUPAKA.md) (Sutra and the
renderer), [DRISHTI_NET.md](DRISHTI_NET.md) (several servers), [GENUI.md](GENUI.md) section 11 (write-back forms, deferred to this
design), [BI_DESIGN.md](BI_DESIGN.md) (review and proposals, reused here).

## Contents

1. [The ask](#1-the-ask) · 2. [Principles](#2-principles) · 3. [The idea in one picture](#3-the-idea-in-one-picture) ·
4. [Declaring what is editable](#4-declaring-what-is-editable-the-sutra) · 5. [Where an edit goes](#5-where-an-edit-goes-three-write-targets) ·
6. [The change request](#6-the-change-request) · 7. [Edit queues (lanes) and the transaction handler](#7-edit-queues-lanes-and-the-transaction-handler) ·
8. [Concurrency: why a queue alone is not enough](#8-concurrency-why-a-queue-alone-is-not-enough) · 9. [Approval](#9-approval-four-eyes) ·
10. [On screen](#10-on-screen) · 11. [Several servers](#11-several-servers-drishtinet) · 12. [Security and audit](#12-security-masks-and-audit) ·
13. [APIs, CLI and configuration](#13-apis-cli-and-configuration) · 14. [Build plan](#14-build-plan) · 15. [Tests](#15-tests) ·
16. [Decisions](#16-decisions-product-owner-2026-10-19) · 17. [Risks and open questions](#17-risks-and-open-questions)

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
7. **Off by default:** writing to a source system is switched on per connector by an administrator.

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
           │                 Overlay store    Drishti-owned kind     Source command
           │                 (corrections,    (notes, limits,        (connector write:
           │                  annotations)     mappings)              REST/JDBC/Kafka…)
           └──────────────── change log (audit, replay) ◀──────────────────┘
```

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
- `sutra lint` refuses an `edit:` on a computed binding (an expression, not a path), on a masked field, or a `target: source` where
  the kind's connector has no write capability.

## 5. Where an edit goes: three write targets

| Target | What it is | For | Risk |
|---|---|---|---|
| **overlay** | Drishti keeps the new value as a dated layer *on top of* the source; the page shows the merged value with a marker ("edited · source says X") | corrections, annotations, flags, comments | none to the source; the overlay can disagree with the source, and the page says so |
| **owned** | the kind is Drishti's own (Drishti is its system of record): the value is written to Drishti's store | notes, limits, mappings, reference overrides | the store is Drishti's: backed up and versioned like any |
| **source** | the connector's **write capability** sends a named command (`amend-field`) to the source system: REST call, JDBC update, Kafka message, vendor API | genuine amendments in ENDUR, CALYPSO, MUREX | real: off by default, per connector, approval recommended |

- **Overlays and as-of.** An overlay entry has a business date and a valid-from; a view of date D shows overlays valid on D. Editing a
  past date is a **restatement** and needs a stronger role. Overlays can expire (`until:`) or be withdrawn.
- **Source commands confirm asynchronously.** The handler records *sent*; the change is *applied* when the source confirms (reply,
  acknowledgement topic, or the next load showing the value). Until then the page shows *pending at source*. A command the source
  rejects is *refused* with its reason. Commands are **idempotent**: each carries the change id, so a resend never applies twice.
- **A new SPI method**, `SourcePlugin.write(Command)`, with a capability flag `writes: [amend-field, ...]`; plugins without it are
  read-only (today all are).

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
- **Drafts** live in the user's state (they follow the user across nodes like saved searches) until submitted.

## 7. Edit queues (lanes) and the transaction handler

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

**The handler, per change taken from a queue:**
1. re-check the role, masks and the field's `edit:` rules (the Sutra may have changed since submit);
2. read the current version of the entity and compare with `basedOn` (section 8);
3. run the server-side validation (DEL) against the *current* document with the change applied;
4. apply to the target (section 5) in one transaction where the target supports it (owned store, overlay store, JDBC); for a source
   command, send it and record *sent*;
5. append to the **change log** (durable, before acknowledging), bump the entity's version, publish a live tick to every viewer.

**Implementation shape.** One virtual thread per lane, parked when its lane is empty; each lane's queue is durable (its entries are
the change log's, appended before the submit is acknowledged), so a restart resumes every lane in order. No locks are held across
I/O (ReentrantLock where needed, never `synchronized` around I/O).

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
- **History:** a cell's menu shows its change history (from the change log); *How this view was built* names overlays applied.
- Phone width: form mode only (no inline grid editing); the bar sits at the bottom.

## 11. Several servers (Drishti*net)

- **Lanes are owned, cluster-wide.** The `P` lanes are placed on the member ring (the hash ring on Admin → Net → Members, which
  shows each node's lanes); each lane has exactly one owner node. Any node accepts a change, computes its lane, and forwards it, signed
  and on behalf of the user, to the lane's owner, whose worker applies it. So the one-at-a-time guarantee holds across the cluster.
- When a node leaves, its lanes move to the next nodes on the ring; the new owner resumes each lane from the change log (replicated
  like user state), and a change *applying* at the moment of failure is resolved by idempotency (the change id) on retry. A lane is
  never worked by two nodes: ownership changes only through the ring's agreed membership, and the new owner waits for the old owner's
  lease (`edit.lane-lease`, default 10 s) to expire.
- Overlays and owned kinds replicate like other Drishti-owned state; source commands go out once, from the owner.

## 12. Security, masks and audit

- Edit roles are separate from read roles; the Change API re-checks everything at apply time.
- A masked field is never editable; a change can never *reveal* a masked value (the diff shows masks for fields you cannot see).
- The change log is append-only, signed per entry (the same signing as audit), kept per retention policy; exports for compliance.
- Every change is audited at submit, approval, apply, and refusal, with the user, node, and the before/after values (masked per reader
  when shown).

## 13. APIs, CLI and configuration

- **API:** `POST /api/v1/changes` (submit), `GET /api/v1/changes?state=&kind=&entity=` (list), `GET /api/v1/changes/{id}`,
  `POST /api/v1/changes/{id}/approve|reject|withdraw`, `GET /api/v1/entities/{kind}/{id}/history`.
- **CLI:** `drishti.py change propose --kind trade --id MX-50000001 --set TRADER_ID=TRDR-AKHAN --reason "..."`, `change list`,
  `change approve <id>`, `change history trade MX-50000001`.
- **Configuration**, like connectors and Drishti*net, in its own folder `config/edit/`: `policy.yaml` (defaults per kind and target,
  approval), `lanes.yaml` (lanes, max-lane-depth, lane-warn, lane-lease, timeouts), `retention.yaml` (the change log, section 16), and per-connector write switches (`writes: enabled` in the connector's
  file, off unless set).

## 14. Build plan

| Phase | What | Accepted when |
|---|---|---|
| W1 | `edit:` in the Sutra schema and lint; Change API; lanes and handler (single server); **owned** kinds and **overlays** (corrections and notes first, section 16); version check; change log with retention; inline edit and the pending bar | two users editing the same field: the second is conflicted with a diff, never silently overwritten; 100,000 concurrent changes over 1,000,000 entities on 64 lanes apply in per-entity order with no lost update (property test) |
| W2 | Approval (four eyes), Review → Changes, history, CLI | a change needing approval cannot apply without it; self-approval refused |
| W3 | Drishti*net lane ownership on the ring, forwarding, lane failover with leases; overlays written into the lake build (section 16) | the per-record guarantee holds with three nodes and one killed mid-run (soak); an approved overlay appears in the next lake build and in BI |
| W4 | `SourcePlugin.write` and **source commands**: the one real-amendment pilot (one field of one kind on one connector, JDBC first); pending at source; idempotency | a source command applied twice by a retry changes the source once |
| W5 | Form mode, bulk edit, phone, docs and a tutorial (UI, CLI, YAML) | a tutorial edits, approves and sees the change tick on another browser |

Placement: **2.7.0** (product owner, 2026-10-19), after 2.6.0 (statements, field parameters, the Cassandra connector, drishti-iceberg;
integration moved after 3.0.0 on 2026-10-20); AI (grounding, AI UI, GenUI, AI management)
becomes 2.8.0, GenUI's write-back forms build on this.

## 15. Tests

- **Ordering property test:** random concurrent submissions over random keys on few lanes (so many keys share a lane); per key,
  applied order equals submission order; no change lost; versions strictly increase; no lane ever has two active workers.
- **Lost-update test:** two stale edits to one field; the second is conflicted.
- **Field-level independence:** concurrent edits to different fields of one entity both apply.
- **Restart:** queued changes survive a kill and apply in order after restart; an *applying* change is not applied twice.
- **Cluster:** ownership moves on a node's death; no key ever has two active workers (checked from the change log).
- **Security:** masked fields not editable; roles re-checked at apply; audit entries for every state.
- **Browser:** inline edit, pending bar, conflict dialog, approval flow, phone form mode.

## 16. Decisions (product owner, 2026-10-19)

1. **Approved overlays flow back into the lake.** The next lake build writes an approved overlay's value into the table (with the
   change id and the source's value kept beside it, in `_drishti_overlay` columns), so BI, search, exports and anything reading the
   lake see the corrected value. Reconciliation with the source stays possible: an **Open overlays** report lists every overlay whose
   source value still differs, and a source load that now agrees closes it.
2. **Corrections and notes first.** W1–W3 deliver overlays and Drishti-owned kinds (comments, flags, name fixes, mapping overrides);
   real amendments in a booking system start in W4 as **one pilot**: one field of one kind on one connector, approval required.
3. **The change log is kept 7 years by default, configurable** (`config/edit/retention.yaml`: `keep: 7y`; per kind may be longer, never
   shorter than the site default). Entries older than the hot window (default 90 days) are archived to compressed, signed files under
   `<data dir>/edit/archive/` (or an object store) and stay searchable through the API; deletion only past retention, audited.

## 17. Risks and open questions

1. **Writing to ENDUR/CALYPSO/MUREX** is a governance decision; source commands stay off until a bank approves a connector.
2. **Overlay drift:** an overlay that disagrees with the source for long is a reconciliation item (the Open overlays report, decision 1).
3. **Hot keys and hot lanes:** one very busy entity is serialised by design, and its lane's neighbours wait; batching and the lane
   metrics address it, and `P` can be raised.
4. **Open:** the pilot's kind, field and connector for W4.
