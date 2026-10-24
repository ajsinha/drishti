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
# Forms: letting people edit records

A panel's `form:` lets people change the records it shows, or start new ones, without leaving the screen. Three ways in, chosen in
the Sutra:

| Way | Write | What people get |
|---|---|---|
| A form beside the table | `edit: beside` | Click a row: a form opens beside the table with typed fields |
| The table's cells | `edit: cells` | A ✎ marks the editable columns; people type into the cells, like a spreadsheet |
| A guided drawer | `new: drawer` | **New** opens a drawer over the dimmed page, one step at a time |

Saving keeps a **draft** for each person, on the server. Nothing is sent to the source system yet: write-back through the connectors
arrives in 2.8.0 ([WRITE_BACK.md](../architecture/WRITE_BACK.md)). The design is
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
