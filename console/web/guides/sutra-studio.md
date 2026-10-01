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
# Tutorial 3 · Sutra Studio

Studio (`/studio`) is where screen layouts are written. You edit a Sutra on the left and see it applied to a
real entity on the right, in the same renderer the terminal uses. Nothing is saved until you press **Save** (or
**Submit for review**), so you can experiment freely.

This tutorial takes about fifteen minutes. It uses the trading pack's interest rate swap `T-10001`; any entity
works.

## The screen at a glance

| Where | What it is |
|---|---|
| Top left: **Sutra picker** | *New Sutra…*, or an existing Sutra such as `irs-fixfloat v1 · trade`. |
| Top left: two small boxes | The **kind** and **id** of the entity to preview against (`trade`, `T-10001`). |
| **Preview** (Ctrl+Enter) | Renders the Sutra against that entity. |
| **Start from inference** | Replaces the editor with what inference makes of the entity, as an editable Sutra. |
| **Save** / **Submit for review** | Only where saving is switched on and you are an author (see step 7). |
| Toolbar | **Insert…** and the other editing helpers. |
| Editor | The Sutra: one YAML document that starts with `rachana: 1`. It completes keys, panel kinds, options, formats and entity kinds as you type, from the language's schema (`GET /api/v1/rachana/schema`). |
| Problems list | Under the editor: each problem with its code and line, checked as you type. |
| Right: **Preview**, **Summary**, **Sample JSON** tabs | The rendered view; what the Sutra is (its description and notes, what it matches, its panels and keys); the data. |

## 1. Open an existing Sutra

Go to:

```text
# Studio with the swap Sutra and T-10001 already chosen
/studio?sutra=irs-fixfloat@1&kind=trade&id=T-10001
```

Or choose `irs-fixfloat v1 · trade` in the picker and type `trade` and `T-10001` in the two boxes. Press
**Ctrl+Enter**.

You should see the swap on the right exactly as the terminal shows it: the title *Rates · Interest rate swap
(fixed/float) T-10001 with Meridian Reinsurance Ltd*, eight strip figures, *Terms*, *Legs*, *Cashflows*, the
curve and the DV01 bars.

## 2. Make a change and preview it

In the editor, find the strip line for DV01:

```yaml
  - { label: DV01 (USD), bind: $.risk.dv01, fmt: signed0, tone: sign }
```

Change it to:

```yaml
  - { label: DV01, bind: $.risk.dv01, fmt: compact, tone: sign, emphasis: true }
```

Press **Ctrl+Enter**. The strip now reads *DV01 −155.2k*, highlighted. A preview takes tens of milliseconds.

Now break something on purpose: change `label:` to `labell:` on that line and preview. The **problems list**
under the editor names the problem with its code and line, for example:

```text
# What a problem looks like
DRS-2011 line 37: unknown key 'labell' in strip item
```

Try a broken expression too, such as `bind: "$.risk.dv01 +"`: you get `DRS-2101` (the expression does not
compile).

Click a problem to jump to its line. Put the line back and preview again.

## 3. Add a panel with Insert…

1. Put the cursor on a new line at the end of the `panels:` list.
2. Choose **Insert… → status**. A correctly shaped `status` panel appears.
3. Edit it to read as below.

```yaml
# the status panel, at the end of panels:
  - id: ops
    kind: status
    title: Confirmation and clearing
    fields:
      - { label: Confirmation, bind: $.confirmation.status, tone: status }
      - { label: Clearing, bind: $.clearing.status, tone: status }
      - { label: CCP, bind: $.clearing.ccp }
```

Press **Ctrl+Enter**. A new panel shows *Confirmed*, *Cleared* and *LCH SwapClear*.

**Insert…** offers a strip field and panels of twelve kinds (`kv`, `table`, `tabs`, `line`,
`area`, `hbar`, `ladder`, `status`, `gauge`, `markdown`, `links`, `provenance`). For a `surface`, copy the example
from [The thirteen panel kinds](panel-kinds#surface).

## 4. Find the right path in the data

Open the **Sample JSON** tab and press **Load entity JSON**. You should see the trade's document, exactly as the
source sent it:

```json
{
  "tradeId": "T-10001",
  "productType": "IRS_FIXFLOAT",
  "counterparty": { "id": "CP-MERIDIAN-RE", "name": "Meridian Reinsurance Ltd" },
  "execution": { "venue": "Voice", "venueMic": "XOFF", "orderId": "ORD-C00C6EF736", … },
  …
}
```

A path is the keys joined with dots: `$.execution.venue` reads *Voice*; `$.counterparty.name` reads *Meridian
Reinsurance Ltd*. Add a strip figure to prove it:

```yaml
  - { label: Venue, bind: "$.execution.venue + ' (' + $.execution.venueMic + ')'" }
```

Preview: the strip shows *Venue Voice (XOFF)*. (A strip holds at most eight figures; remove one first, or you get
`DRS-2026`.)

## 5. Design against data you paste

You can design a layout before any source is connected:

```json
{"tradeId": "T-1", "notional": 5000000, "mtm": -12500,
 "legs": [{"leg": 1, "rate": 0.031}, {"leg": 2, "index": "SOFR"}]}
```

1. In **Sample JSON**, replace the document with your own, such as the one above.
2. Tick **Preview against this JSON**.
3. Press **Ctrl+Enter**. The preview renders your document instead of fetching `T-10001`.

**Start from inference** also uses the pasted document when the box is ticked, so you can get a first layout for
data Drishti has never seen.

## 6. Start a new Sutra from inference

1. Choose *New Sutra…* in the picker.
2. Type `netting-set` and `NS-SUMMIT-NY` in the two boxes.
3. Press **Start from inference**.

The editor fills with a Sutra named `netting-set-custom`, with `match: { kind: netting-set, priority: 1 }`, a strip
and one panel per thing inference found: *Profile*, *Trades*, *By asset*, *How this view was built* and *Linked
entities*. Delete what you do not need, rename panels, add `key: F2` to the most important one, and replace the
`description:` line with one paragraph saying who the layout is for:

```yaml
description: Netting sets for the credit desk, exposure first.
notes: |
  Started from inference on NS-SUMMIT-NY.
  Trades and By asset are kept; the PFE profile leads because limits are checked against it.
```

!!! tip "Say why, in the file"
    A Sutra explains itself in two plain-text keys. `description` is one paragraph: what the layout shows and for
    which entities. `notes` is longer, a YAML block (`notes: |` and indented lines) for the next author and for
    reviewers: why the layout is what it is, what was tried. A panel can have its own `description`. None of them
    change the view, and the **Summary** tab shows them. They are plain text, not Markdown.

## Try a change on all your test entities

A layout has to work for every entity it matches, not just the one on screen. Keep a few typical and awkward ones:

1. Type a kind and an id (for example `trade` and `T-10044`) and press **Preview**.
2. Press **+** next to *Test entities*: the entity is kept for this Sutra (by its `name:`), for you, on the server.
3. Keep as many as you need (up to 30): one of each product the Sutra matches, one with missing fields, one large.
4. After a change, press **Run all**. Every kept entity is previewed with the Sutra as it is in the editor. The
   status line says *All 5 test entities render without problems*, or lists each one with a problem below, such as
   `trade T-10044 — legs: path $.legs[2].rate not found`.
5. Pick one from *Test entities* to preview it on its own.

## 7. Save, or submit for review

Saving needs two things:

- the server started with `DRISHTI_STUDIO_SAVE=true` (it is off by default, and **Save** is then disabled with
  the hint *Saving is off here, or you are not an author*);
- a role that may author: `author`, `approver` or `admin`.

What the button does depends on review, which is **on** by default:

| Review | Button | What happens |
|---|---|---|
| on (default) | **Submit for review** | Studio checks the Sutra and records a *proposal*. Nobody sees it yet. Write a note for the reviewer in *What changed?* first. |
| off (`DRISHTI_SUTRA_REVIEW=false`) | **Save** | The Sutra is written at once to the site Sutra directory as `<domain>/<name>.v<N>.sutra.yaml` (by default under `./sutras`), and views use it immediately. |

If the same `name@version` is already defined in another file (a pack's Sutra, for example), the save is refused
with `DRS-2028`. To change an existing Sutra, raise its `version` (`version: 2`): views pick the new version
straight away, and the old one stays loadable for history.

### Reviewing a proposal

1. An approver (role `approver`, or an admin) clicks **Reviews** in Studio (`/studio/reviews`). The button shows
   how many proposals are waiting.
2. Opening a proposal shows the author's note and the change as a **diff** against the live Sutra.
3. **Approve** (with an optional comment) publishes it, and views use it at once. **Reject** requires a reason,
   which is kept with the proposal for the author to read.
4. While it waits, the author can **Withdraw** it.

Rules that keep this safe:

- **Four eyes.** With sign-in switched on, nobody approves their own proposal.
- **No stale approvals.** If the live Sutra changed after the proposal was made, approval is refused; propose again
  from the live version.
- **Audited.** Every proposal, approval, rejection and withdrawal is recorded in the audit log.

!!! note "In production"
    Many teams keep saving off in production and move Sutras through version control (a pull request that adds
    the file to the pack's `sutras/` directory). Studio is then a safe place to try changes: previews never
    affect anyone else.
    Outside Studio, any editor that reads JSON Schema completes and checks Sutras the same way: point it at
    `/api/v1/rachana/schema` (with the YAML extension of VS Code, a first line
    `# yaml-language-server: $schema=http://localhost:18480/api/v1/rachana/schema`).

## Where to go next

- [The Sutra guide](sutra-guide): every part of a Sutra, with examples.
- [Tutorial 4 · Nested documents](nested-data): paths of any depth, and a complete operations Sutra to paste.
- [Rachana reference](rachana-reference): every key and problem code.
