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
# Edits: write-back for administrators

Write-back lets people change records from a Sutra's forms, the CLI (`drishti.py change`) and the API, as change requests: checked as
the person, approved by someone else where the policy says so, applied one at a time per record, in order, and audited. The design, with
the reasons behind each choice, is [WRITE_BACK.md](../architecture/WRITE_BACK.md); how a Sutra author makes a form submit is in the forms
guide ([SUTRA_FORMS_GUIDE.md](../guides/SUTRA_FORMS_GUIDE.md#4a-submitting-a-change-280-write-back)); a worked example is
[tutorial 24](../tutorials/24-correct-a-trade-and-approve-it.md).

**It is off as shipped**, at three levels, and all must be on before anything is written:

| Level | Off because | Turned on by |
|---|---|---|
| the site | `edit.enabled: false` in `config/edit/edit.yaml` | an administrator: `edit.enabled: true` (or `DRISHTI_EDIT_ENABLED=true`) and a restart |
| the field | a field is editable only when a Sutra's `form:` has `save:` | a Sutra author, through review like any Sutra change (the trading pack's book desk ships with `save:`: corrections of a trade, one approver) |
| the source | a connector writes to its source only when its file declares `writes:` and its writes are switched on | an administrator: the connector file's `writes:`, then **Switch on** in Admin → Edits (audited) |

Corrections (overlays) and owned records need the first two; an amendment sent to a source needs all three. People also need the
**edit** power on one of their roles (Admin → Roles: *Submit edits*); the shipped `admin` role has it. A personal API token needs the
`edit` scope, which no token has unless it is chosen.

- [1. Switch it on](#1-switch-it-on)
- [2. Where a change goes](#2-where-a-change-goes)
- [3. Approval](#3-approval)
- [4. The edit log, lanes and the cluster](#4-the-edit-log-lanes-and-the-cluster)
- [5. Admin → Edits](#5-admin--edits)
- [6. Writing to a source: `writes:`](#6-writing-to-a-source-writes)
- [7. Audit and retention](#7-audit-and-retention)
- [8. When something is wrong](#8-when-something-is-wrong)

## 1. Switch it on

```yaml
# config/edit/edit.yaml
edit:
  enabled: true          # or leave ${DRISHTI_EDIT_ENABLED:false} and set the variable
  log: auto
```

Restart the server. The start logs `write-back: on; edit log sqlite (auto: this node is alone), 64 lanes, node local`. Give the people
who edit a role with the edit power (Admin → Roles → *Submit edits*), and the people who approve a role with the approve power (or
name `policy.approver-roles`). Every setting is in [CONFIGURATION.md](CONFIGURATION.md#write-back-configedit).

## 2. Where a change goes

A form's `save.target` says (the author chooses, the site's policy may only make it stricter):

| Target | What is written | Who sees it |
|---|---|---|
| `overlay` | a correction kept in the Drishti database beside the source's value; the source is not changed | every page that reads the record (and the page row a form edited) shows the corrected value, named in *How this view was built* (*Edited here*); Admin → Edits lists it under Open overlays with the source's value |
| `owned` | the record's value itself, in the Drishti database (it is the record's system of record) | every page that reads the record |
| `source` | a command of the connector's `writes:`, sent through the outbox in the source's own transaction | the page shows the value as pending until the source confirms it; a refusal withdraws it |

The lake is never written per edit: the next lake build folds approved overlays in (planned with the loader).

## 3. Approval

`config/edit/policy.yaml`: approvals per target (`overlay: none`, `owned: none`, `source: one` as shipped; `two` for four eyes twice),
`approver-roles` (empty: the approve power or an administrator), `self-approval: false` (nobody approves their own change), the
`restate` role for a past business date and the `override` role for *Apply mine anyway*. A change that needs approval waits in
**Review → Changes** (top bar: Build → Govern → Changes); it takes its place in its record's lane only when approved, and is checked
against the record then.

## 4. The edit log, lanes and the cluster

Every change is a row of `drishti_edit_log` in the Drishti database, acknowledged only after its commit, in one of P lanes
(`lanes.lanes`, 64): a record always maps to the same lane, a lane applies one change at a time in its order, lanes apply in parallel.
Each lane has one owner, taken in the database by a conditional update with a lease (`lane-lease`, 10 s) and fenced by an epoch: a node
that lost its lane (cut off, paused) can no longer write anything. Which store, by `edit.log: auto`:

| Setup | The edit log | What happens when a node stops |
|---|---|---|
| one node | its SQLite Drishti database | it is the owner; after a restart it takes its lanes back and applies what was acknowledged |
| a cluster on PostgreSQL | the shared database; lanes spread over the members by the ring | another node takes the lanes after the lease, replays from the last applied change and resends unconfirmed commands |
| a cluster with `edit.primary` | the primary's SQLite; every other node forwards edits to it and copies its overlays | no failover: edits pause with a message while the primary is down; reading carries on |
| a cluster with neither | none | edits are refused in the cluster (`DRS-9306`); reading carries on |

The S3 edit log of the design is not built: on AWS use PostgreSQL (RDS, Aurora) for the Drishti database.

## 5. Admin → Edits

Admin → Edits shows the store in use and why (the auto rule), this node, each lane that has an owner or waiting changes (owner, epoch,
lease, depth, oldest wait), the warnings (an edit primary without failover, a lane whose oldest change waited past `lane-warn`, a lane with
waiting changes and no live owner, source commands not confirmed past `command-timeout`), each connector that declares `writes:` with
its commands, their problems and the on/off switch, and the open overlays. `drishti.py edits status` prints the same.

## 6. Writing to a source: `writes:`

A connector says *how* to change a record in its system; the Sutra says *what* may change; the lane owner says *when* and in what order.
In 2.8.0 the JDBC plugin writes; the file, Delta and Iceberg plugins never do (a lake is not written per edit), and the other plugins
are refused with *plugin … cannot write*. A command needs its concurrency guard and its idempotency clause:

```yaml
# config/connectors/trade-murex.yaml (excerpt)
writes:
  amend-notional:
    kind: trade
    params: [leg]
    jdbc:
      sql: >
        UPDATE MX_TRADE_LEG SET NOTIONAL = :value, VERSION = VERSION + 1, DRISHTI_CHANGE_ID = :changeId
         WHERE TRADE_ID = :entity AND LEG_NO = :leg AND VERSION = :basedOn
      expect-rows: 1
      already-applied: SELECT 1 FROM MX_TRADE_LEG WHERE TRADE_ID = :entity AND LEG_NO = :leg AND DRISHTI_CHANGE_ID = :changeId
    confirm: reply           # reply | next-load (ack-topic is read as a reply until a messaging plugin writes)
    timeout: 30s
```

A command without its guard or idempotency clause, or naming a parameter Drishti does not supply (`:entity`, `:kind`, `:businessDate`,
`:value`, `:from`, `:basedOn`, `:changeId`, `:by`, `:reason`, `:at`, `:field`) or the command does not declare in `params:`, is refused
when the connector loads: Admin → Connectors and Admin → Edits say why, and the connector stays read-only for it. Parameters are always
bound, never pasted into the SQL. A resend (after a failover) carries the same change id, so the source applies it at most once.

## 7. Audit and retention

Every step is in the audit log: `edit-submitted`, `edit-approved`, `edit-rejected`, `edit-withdrawn`, `edit-applied`, `edit-conflicted`,
`edit-refused`, `edit-self-approval-refused`, `edit-confirmed`, `edit-refused-at-source`, `form-draft-closed`, `connector-writes-on`/`-off`,
`edit-overlay-withdrawn`, `edit-log-purged`. Values of fields masked for the submitter are written as the mask. Each edit-log row carries a
SHA-256 signature over its place and body. The log is kept `retention.keep` (7 years); finished entries older are deleted.

## 8. When something is wrong

| You see | Why | Do |
|---|---|---|
| `403 DRS-9301` | editing is off | `edit.enabled: true`, restart |
| `403 DRS-9302 … no Sutra's form with save: names it` | the field is not editable | a Sutra author adds `save:` to the form |
| `403 DRS-9302 … does not hold the edit power` | the person's roles | Admin → Roles: *Submit edits* |
| `503 DRS-9306 Edits are paused …` | the edit primary is down, or the cluster has no shared edit log | start the primary; for failover use PostgreSQL |
| a change stays *queued* | no node owns its lane (Admin → Edits: a lane with waiting changes and no live owner) | check the server log for `edit lanes:`; a node takes the lane when its lease runs out |
| *unconfirmed* commands | the source did not confirm within `command-timeout` | check the source; the outbox keeps resending with the same change id |
