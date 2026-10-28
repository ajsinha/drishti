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

# Write-back guide: forms, edits, approval and saving to the source

Write-back lets people change records from a Sutra's forms, the command line (`drishti.py change`) and the API: a correction kept beside
the source, an owned record, or an amendment sent through a connector, after approval where the policy asks. This is the one guide:
sections 1 to 7 are for the **Sutra author** (forms, cells, drawers, drafts, submitting a change), sections 8 to 15 for the
**administrator** (switching it on, where a change goes, approval, the edit log, Admin → Edits, `writes:`, audit, problems). The design
and its reasons are [WRITE_BACK.md](../architecture/WRITE_BACK.md); the worked example is
[tutorial 24](../tutorials/24-correct-a-trade-and-approve-it.md).

A panel's `form:` lets people change the records it shows, or start new ones, without leaving the screen. Three ways in, chosen in
the Sutra:

| Way | Write | What people get |
|---|---|---|
| A form beside the table | `edit: beside` | Click a row: a form opens beside the table with typed fields |
| The table's cells | `edit: cells` | A ✎ marks the editable columns; people type into the cells, like a spreadsheet |
| A guided drawer | `new: drawer` | **New** opens a drawer over the dimmed page, one step at a time |

Saving keeps a **draft** for each person, on the server. A form whose Sutra has `save:` also **submits** the draft as a change: a
correction kept beside the source, an owned record, or an amendment sent through a connector, after approval where the policy asks
([section 4a](#4a-submitting-a-change-280-write-back), [WRITE_BACK.md](../architecture/WRITE_BACK.md)). The design is
[SUTRA_NEXT.md section 10](../architecture/SUTRA_NEXT.md#10-forms-and-data-entry); every key is in
[RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#forms-form-on-a-panel).

## 1. A form beside the table

The book desk's trades (`config/packs/trading/sutras/pages/book-desk.v1.sutra.yaml`) carry this form. Open
`/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk` and click a trade.

![The form beside the book desk's trades: the counterparty locked, a warning under the notional and the Draft · not saved badge](img/designer/form-beside.jpg)

```yaml
panels:
  - use: row-picker@1
    with: { id: trades, rows: $.TRADES, key: TRADE_ID, ... }
    form:
      title: Edit trade
      key: trade_id                        # the field that names a record
      edit: beside
      fields:
        - { id: trade_id, label: Trade, bind: TRADE_ID, readonly: true, reason: "set by the booking system", new: false }
        - { id: counterparty, label: Counterparty, bind: COUNTERPARTY, type: lookup, readonly: true, required: true, options: { ... } }
        - { id: notional, label: Notional, bind: NOTIONAL, type: money, currency: currency, min: 0, required: true, fmt: amount0 }
        - { id: maturity_date, label: Maturity, bind: MATURITY_DATE, type: date, min: today, required: true }
        - { id: trade_status, label: Status, bind: TRADE_STATUS, type: enum, options: [PENDING, VALIDATED, AMENDED], editable-by: [middle-office] }
        - { id: comment, label: Comment, bind: COMMENT, type: textarea, max-length: 500, placeholder: Reason for the change }
      rules:
        - { rule: "notional <= 30000000", severity: warn, fields: [notional],
            message: "Above the desk limit of 30,000,000: a second approver is needed." }
        - { rule: "not (notional > 30000000 and empty(comment))", fields: [comment],
            message: "Say why the trade is above the desk limit: the second approver reads it." }
```

What people see:

- **Typed fields.** A number or an amount of money takes `40,000,000`, `40m` or `1.5bn`; a date opens the date picker; an enum is a list; a lookup is a
  list of what its source offers; `textarea` is several lines.
- **Locked fields say why.** `readonly: true` shows the value with "(read-only: set by the booking system)", or "(locked: you can
  view, not change)" when there is no `reason`. A field whose `editable-by` names roles the person does not hold is locked the same
  way. A masked field says **Hidden for you** and is never editable.
- **Messages in words, under the field.** An error (`severity: error`, the default) stops the draft; a warning (`severity: warn`) is
  said and kept with it, for example "Above the desk limit of 30,000,000: a second approver is needed."
- **Draft · not saved** while there are changes; **Save draft** keeps them; **Discard** throws them away (and the saved draft too).
- Leaving the page with changes not saved asks first. A panel being edited is not refreshed under the person's hands.

### Money, a date and time, a linked record, a worked-out default

The same form has four fields of the richer kinds (the book desk, `book-desk.v1.sutra.yaml`):

```yaml
        - id: legal_entity
          label: Booked in
          bind: LEGAL_ENTITY
          type: entity                        # a linked record of another pack's kind
          readonly: true
          reason: "the booking entity is fixed once booked"
          step: who
          options: { source: { pack: core, kind: legal-entity }, label: NAME }
        - { id: notional, label: Notional, bind: NOTIONAL, type: money, currency: currency, min: 0, required: true, fmt: amount0 }
        - { id: currency, label: Currency, bind: CURRENCY, type: enum, options: [USD, EUR, GBP, JPY, CHF, CAD, AUD] }
        - { id: upfront_fee, label: Upfront fee, bind: UPFRONT_FEE, type: money, currency: currency, min: 0, default: "=notional * 0.001" }
        - { id: executed_at, label: Executed, bind: EXECUTED_AT, type: datetime, tz: Europe/London }
```

- **`money`** is an amount and its currency. People type `1.5m`, `250k`, `2bn` or `1,000,000`; it shows grouped (`1,500,000`) once
  they leave the field. The currency sits beside it: fixed (`currency: USD`) or the one another field holds (`currency: currency`,
  which changes as people pick a currency). `min` and `max` are numbers. A value that does not read says *Notional is an amount
  (1,000,000, 1.5m or 250k)*.
- **`datetime`** is a date and a time (`2026-10-23T14:05`), entered with the browser's date and time picker; the time zone (`tz`,
  UTC when absent) is shown beside it and with the value (*2026-10-23 14:05 Europe/London*). `min` and `max` are a date and time or
  `today`.
- **`entity`** links the record to a record of any pack's kind: a search box over the records of `options.source` (a `where` or a
  `search` narrows them), read as the viewer may see them. Type part of a name or an id, pick with the arrows and Enter (or a click);
  the record's **id** is kept and its **label** shown (*Drishti Bank Europe AG · LE-FRA*), with **Open** beside it. A record whose
  kept value is masked for the viewer is never offered, and a masked label shows the id.
  The box searches the server as people type (a quarter of a second after the last key, *Searching…* meanwhile, *No match for
  “…”* when nothing holds the text), so every record of the kind can be found, not only those the page brought; the search reads as
  the viewer too ([API_GUIDE.md](API_GUIDE.md#form-drafts), `/api/v1/forms/lookup`). When the server cannot be reached, the choices the
  page brought still answer. On saving, the server reads the kept id once: an unknown one is refused, *Booked in: no legal entity
  'LE-NOWHERE' was found*.
- **`default`** starts a new record: a value (`default: PENDING`), or an expression written `=...` over the other fields
  (`=notional * 0.001`), worked out as people type (*Worked out: notional * 0.001 · type to change it*) until they type over it. The
  server fills it too when a new record leaves the field empty, so a draft saved by a script is the same.

## 2. Editing in the cells

A credit limit's buckets (`/v/credit-limit/LIM-ALDERSHOT`) are edited straight in the table:

![Edited cells: the changed limits tinted with "was", a value below what is used in red with the reason under the row](img/designer/form-cells.jpg)

```yaml
  - id: buckets
    kind: table
    rows: $.BUCKETS
    columns:
      - { label: Bucket, bind: "@.BUCKET" }
      - { label: Limit, bind: "@.LIMIT", fmt: compact }
      - { label: Used, bind: "@.USED", fmt: compact }
    form:
      key: bucket
      edit: cells
      fields:
        - { id: bucket, label: Bucket, bind: BUCKET, readonly: true, reason: "the tenor buckets are set by the limits policy" }
        - { id: limit, label: Limit, bind: LIMIT, type: number, min: 0, required: true, fmt: amount0 }
        - { id: used, label: Used, bind: USED, type: number, readonly: true, reason: "measured by the exposure engine" }
      rules:
        - { rule: "limit >= used", fields: [limit], message: "The new limit is below what's already used: raise it, or ask the owner first." }
        - { rule: "limit <= used * 3", severity: warn, fields: [limit], message: "More than three times what's used: the credit committee reviews it." }
```

A field edits the column whose `bind` names the same record field (`LIMIT` edits the column bound to `@.LIMIT`). The keys:
**Enter** (or F2, or a click) opens a cell and **Enter** keeps the value in the draft; **Esc** undoes it; **Tab** keeps it and moves
to the next editable cell (Shift+Tab back). A changed cell is tinted and says "was 337,000,000"; a bad one has a red border and the
reason under its row. The bar above the table counts "2 changes not saved", with **Undo all** and **Review 2 changes** (which lists
every change and saves the draft; it says "Fix 1 problem first" while one is wrong). A masked cell says *Hidden for you*.

## 3. New records in a guided drawer

```yaml
    form:
      new: drawer
      new-label: New trade
      steps: [ { id: who, label: Who }, { id: what, label: What }, { id: check, label: Check } ]
      fields:
        - id: counterparty
          type: lookup
          step: who
          options:
            source: { pack: counterparty-risk, kind: credit-limit, where: "nonblank(@.COUNTERPARTY)" }
            value: COUNTERPARTY            # what is kept
            label: COUNTERPARTY_NAME       # what is shown beside it
            carry: [LIMIT, USED]           # what the hints read: counterparty.LIMIT
        - { id: notional, type: number, step: what, ... }
      hints:
        - { label: "PFE limit after this trade", value: "counterparty.USED + notional", of: "counterparty.LIMIT", fmt: amount0, step: what }
```

![The New trade drawer over the dimmed book desk: steps Who ✓, What, Check, and the limit after the trade](img/designer/form-drawer.jpg)

**New trade** opens the drawer; the page stays visible behind it, dimmed. **Next** checks the step's fields first ("Counterparty is
required"); a done step is ticked; **Back** returns; the last step (Check) shows what was entered and every message. A hint is worked
out as people type: "PFE limit after this trade: 102,400,000 of 120,000,000 (85%)". **Save as draft** keeps it from any step, checked
up to that step. A field is asked in the drawer unless it says `new: false`; a `readonly` field is one a record keeps once it exists,
so the drawer may set it.

A lookup's choices are read as the viewer: an entity whose value is masked for them is not offered, and a carried value that is masked
makes the hint say *Hidden for you*.

## 4. Drafts

Drafts are kept per person on the server (the content store), through `/api/v1/drafts` ([API_GUIDE.md](API_GUIDE.md#form-drafts)).
The page says **You have 2 drafts on this page**, with **Resume** (the form opens again where it was: the row, the cells, the step) and
**Discard** for each. Every save is checked again on the server with the same rules; a value for a field masked for the person, or a
change to a field they may not edit, is refused; each save and discard is in the audit log.

## 4a. Submitting a change (2.8.0 write-back)

A form whose Sutra says where it goes, with `save:`, also has **Submit…** (2.8.0, [WRITE_BACK.md](../architecture/WRITE_BACK.md)). Its
fields' `bind` are the record's fields the change writes:

```yaml
form:
  title: Edit trade
  key: trade_id
  edit: beside
  save:                                  # without save:, the form keeps drafts only
    kind: trade                          # the records' kind (the page's kind when absent: here the page is a book's trades)
    target: overlay                      # overlay | owned | source
    approval: one                        # none | one | two (the site's policy may ask for more, never fewer)
    reason: required                     # required | optional
    # command: amend-notional            # target source: a command of the connector's writes: (create: for a new record)
    # connector: trade-murex             # target source: the connector (the one connector of the kind that writes when absent)
  fields: [ ... ]
```

| `target` | What Submit does | Shown |
|---|---|---|
| `overlay` | keeps a **correction** in Drishti beside the source's value (the source is not changed) | every page that reads the record shows the corrected value, and *How this view was built* says **Edited here** with the change id and who made it |
| `owned` | saves the record itself (Drishti is its system of record) | every page that reads it |
| `source` | sends a command of the connector's `writes:` to the source system, through the outbox | *pending at the source* until it confirms; refused, the page shows the source's value again |

**Submit** saves the draft, then opens the **review and confirm sheet**: for each record, what you saw, what is there now and your
change, with a field that moved since you opened it flagged before you send anything; where it goes (*Correction kept … (overlay)*,
*Amend in trade-murex (command amend-notional)*); who must approve (*One approver (not you): K. Rao or M. Diaz*); what it will
recompute (the reactive connectors whose triggers fire on a change to the kind); and the reason box. **Confirm and submit** turns the draft
into one change request per record. The sheet then follows each change: *Waiting for an approver*, *Approved*, *Applied* (the draft is
closed with the change id), or *Conflicted: the record moved* / *Refused* with the reason (the draft is kept and the drafts bar says so,
to edit again). A toast says it too when the sheet is closed.

Who may submit: editing must be on (`config/edit/edit.yaml`), the person needs the **edit** power (Admin → Roles: *Submit edits*), and
every field they change must be editable for them (not masked, not read-only, their role in `editable-by`). An approver is never the
person who submitted (four eyes); they decide in **Review → Changes** (Build → Govern → Changes). The server checks everything again, and
the lane owner checks it once more when it applies the change, against the record as it is then: a field that moved is *conflicted*,
never written over. A new record from the drawer can be submitted only to `owned` or `source` (with `create:`), never as a correction.
People who watch the record see the new value at once on its page (masked for them), with *Changed by … just now*.

From the command line, the same request: `drishti.py change submit d-4ab964fa3060 --reason "booking typo"` or
`drishti.py change propose --kind trade --id MX-20000012 --set NOTIONAL=28001250 --from NOTIONAL=28000000 --reason "booking typo"`
([TOOLS.md](TOOLS.md)); the API is `POST /api/v1/changes` ([API_GUIDE.md](API_GUIDE.md#write-back-changes-280)). A worked example with
pictures is [tutorial 24](../tutorials/24-correct-a-trade-and-approve-it.md).

## 5. With the Sutra designer

- **Customize › Let people edit**: pick a table, *in a form beside it* or *in its cells* (or *not at all*), tick the columns people
  may edit, and *start new ones in a guided drawer*; **Set** writes the `form:` (the first column names the record and is read-only).
  Each column is *as guessed* or what you pick: text, a number, money, a date, a date and time, or a linked record (offered when the
  packs say the column names one, such as COUNTERPARTY). The guess: a number format or a name like NOTIONAL is a number, money when
  the table has a CURRENCY column too (in that currency); a date format or `*_DATE` a date; `*_AT` or `*_TIME` a date and time; a
  column the packs link a linked record.
- **Describe**: `TRD BOOK-RATES-2 · click a trade → cash flows · edit trades beside · new trade in a drawer` (or `edit trades in the
  cells`) gives the trades the form above, with the lookup and the limit hint. The notional is `money`: in the trades' own currency
  when they carry one (a Currency field is added and `currency: currency`), else in the book's currency (`currency: GBP`), else USD.

See [SCREEN_DESIGNER.md section 31](SCREEN_DESIGNER.md#31-the-sutra-designer-customize-describe-outline-and-grid).

## 6. The expressions

Rules and hints use a small language, the same in the browser (instantly) and on the server (the authority): the fields by id, a
lookup's carried values (`counterparty.LIMIT`), numbers (`30_000_000` and `1e6` too), `'text'`, `true`, `false`, `null`, `today`;
`+ - * /`; `== != < <= > >=` (numbers as numbers, anything else as text, so ISO dates compare); `and or not`; brackets; `empty(x)`,
`len(x)`, `abs(x)`, `min(a, b)`, `max(a, b)`. An empty value makes a sum or a comparison empty, and a rule that comes out empty holds:
`required` says what is missing, not every rule. A rule that reads a lookup's carried value is checked in the browser only; the server
checks it again when 2.8.0 writes.

## 7. Checking a form

`sutra lint` and the editor check a form as `DRS-2058`, in words: an unknown key, a type, a `min` that is not a number for a number
field, a `step` or `key` that names nothing, a rule that reads a field the form does not have (`rule 'notionl <= 30000000' reads
'notionl', which is not a field of the form`), a pattern that is not a regular expression, a money field without its currency (or a
`currency` that is neither a code nor a field), a `tz` that is not a time zone, a `default` expression that reads a missing field. The JSON schema
(`/api/v1/rachana/schema`) describes `form:` for editors.

## For administrators: write-back is off as shipped

Write-back lets people change records from a Sutra's forms, the CLI (`drishti.py change`) and the API, as change requests: checked as
the person, approved by someone else where the policy says so, applied one at a time per record, in order, and audited. The design, with
the reasons behind each choice, is [WRITE_BACK.md](../architecture/WRITE_BACK.md); how a Sutra author makes a form submit is in the forms
guide ([section 4a](#4a-submitting-a-change-280-write-back)); a worked example is
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


## 8. Switch it on

```yaml
# config/edit/edit.yaml
edit:
  enabled: true          # or leave ${DRISHTI_EDIT_ENABLED:false} and set the variable
  log: auto
```

Restart the server. The start logs `write-back: on; edit log sqlite (auto: this node is alone), 64 lanes, node local`. Give the people
who edit a role with the edit power (Admin → Roles → *Submit edits*), and the people who approve a role with the approve power (or
name `policy.approver-roles`). Every setting is in [CONFIGURATION.md](../admin/CONFIGURATION.md#write-back-configedit).

## 9. Where a change goes

A form's `save.target` says (the author chooses, the site's policy may only make it stricter):

| Target | What is written | Who sees it |
|---|---|---|
| `overlay` | a correction kept in the Drishti database beside the source's value; the source is not changed | every page that reads the record (and the page row a form edited) shows the corrected value, named in *How this view was built* (*Edited here*); Admin → Edits lists it under Open overlays with the source's value |
| `owned` | the record's value itself, in the Drishti database (it is the record's system of record) | every page that reads the record |
| `source` | a command of the connector's `writes:`, sent through the outbox in the source's own transaction | the page shows the value as pending until the source confirms it; a refusal withdraws it |

The lake is never written per edit: the next lake build folds approved overlays in (planned with the loader).

## 10. Approval

`config/edit/policy.yaml`: approvals per target (`overlay: none`, `owned: none`, `source: one` as shipped; `two` for four eyes twice),
`approver-roles` (empty: the approve power or an administrator), `self-approval: false` (nobody approves their own change), the
`restate` role for a past business date and the `override` role for *Apply mine anyway*. A change that needs approval waits in
**Review → Changes** (top bar: Build → Govern → Changes); it takes its place in its record's lane only when approved, and is checked
against the record then.

## 11. The edit log, lanes and the cluster

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

## 12. Admin → Edits

Admin → Edits shows the store in use and why (the auto rule), this node, each lane that has an owner or waiting changes (owner, epoch,
lease, depth, oldest wait), the warnings (an edit primary without failover, a lane whose oldest change waited past `lane-warn`, a lane with
waiting changes and no live owner, source commands not confirmed past `command-timeout`), each connector that declares `writes:` with
its commands, their problems and the on/off switch, and the open overlays. `drishti.py edits status` prints the same.

## 13. Writing to a source: `writes:`

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

## 14. Audit and retention

Every step is in the audit log: `edit-submitted`, `edit-approved`, `edit-rejected`, `edit-withdrawn`, `edit-applied`, `edit-conflicted`,
`edit-refused`, `edit-self-approval-refused`, `edit-confirmed`, `edit-refused-at-source`, `form-draft-closed`, `connector-writes-on`/`-off`,
`edit-overlay-withdrawn`, `edit-log-purged`. Values of fields masked for the submitter are written as the mask. Each edit-log row carries a
SHA-256 signature over its place and body. The log is kept `retention.keep` (7 years); finished entries older are deleted.

## 15. When something is wrong

| You see | Why | Do |
|---|---|---|
| `403 DRS-9301` | editing is off | `edit.enabled: true`, restart |
| `403 DRS-9302 … no Sutra's form with save: names it` | the field is not editable | a Sutra author adds `save:` to the form |
| `403 DRS-9302 … does not hold the edit power` | the person's roles | Admin → Roles: *Submit edits* |
| `503 DRS-9306 Edits are paused …` | the edit primary is down, or the cluster has no shared edit log | start the primary; for failover use PostgreSQL |
| a change stays *queued* | no node owns its lane (Admin → Edits: a lane with waiting changes and no live owner) | check the server log for `edit lanes:`; a node takes the lane when its lease runs out |
| *unconfirmed* commands | the source did not confirm within `command-timeout` | check the source; the outbox keeps resending with the same change id |
