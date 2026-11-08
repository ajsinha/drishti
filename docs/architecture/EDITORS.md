<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The editors: one pattern for every object people author

How Drishti's authoring screens look and behave: tables and virtual tables, semantic models, Python-driven tables and models, people
and roles, and API keys. One shell and one set of rules, so a person who has learned one editor knows them all. The product owner chose
each design from mock-ups (2026-11-05); the mock-ups are in [mockups/editors/](mockups/editors/) and are opened in a browser.

Which release builds what: tables, virtual tables, semantic models and Python-driven tables and models come with the BI foundation;
people, roles and API keys come with trust, RBAC and security, whose rules are settled in that release's design discussion (this
document fixes only their screens).

## Contents

1. [The shell every editor shares](#1-the-shell-every-editor-shares)
2. [Tables and virtual tables](#2-tables-and-virtual-tables)
3. [Semantic models](#3-semantic-models)
4. [Python-driven tables and models](#4-python-driven-tables-and-models)
5. [People and access](#5-people-and-access)
6. [API keys](#6-api-keys)
7. [Rules every editor follows](#7-rules-every-editor-follows)
8. [Build checklist](#8-build-checklist)

## 1. The shell every editor shares

Mock-up: [g1-editor-shell.html](mockups/editors/g1-editor-shell.html), option **C** as the home, option **B** for every *+ New*.

- **Three panes.** Left: the objects (tables, models, Python tables) with search. Middle: the editor, with the **live YAML below it**,
  the line being edited highlighted. Right: a **live preview with checks** (rows, totals computed by the pivot engine, warnings,
  impact).
- **A Visual / YAML / Both toggle**, remembered per person. YAML edits validate as typed and show back in the visual side; a layman can
  keep the YAML collapsed.
- **The header**: where you are, the name, the status with versions (*draft v4*, *approved v3*), and Validate, Preview, History,
  Impact and **Propose for review**.
- **+ New** opens guided steps in plain language (option B); when the object exists it opens in the three-pane editor.
- **As built**: the shell is drishti-designer's `DesignerShell` and the YAML its `YamlPane` over drishti-inputs' `CodeEditor`
  ([ELEMENTS.md section 8.9](ELEMENTS.md#89-as-built-drishti-designer-the-designer-kit)), the same for every designer: the table,
  model and Python editors, the Build workbench's canvas and YAML, the BI report designer's canvas, undo and YAML. Its look is mock-up
  g7's: **studio** (option A, the default: the left rail's tabs, the canvas with the YAML docked under it, the inspector, the preview),
  **side by side** (B: the canvas and the YAML side by side, the inspector a card by the selection) and **terminal** (C: a command line
  on Ctrl+K and the function keys F3 Validate, F4 YAML, F5 Preview, F7 History, F8 Impact, F9 Propose); each person's layout, mode and
  whether the YAML is open are kept by the server. A YAML edit is read on the server after a pause and each problem is marked at its
  line (a sign in the gutter, the list under the editor); the visual side keeps the last valid version.

## 2. Tables and virtual tables

Mock-up: [g2-tables.html](mockups/editors/g2-tables.html), option **A** as the editor, option **C** as its Text toggle.

- **The source and its look-ups as rows**; **columns as a checklist** with the name here, where it comes from, its type, and computed
  columns as Rachana formulas. A look-up brings one row of another table by a key (many to one: `related(table.COLUMN, on = key)`).
  A plain table is the same editor with one source and no look-up.
- **Tables use look-ups; models join** (product owner, 2026-11-06): a table enriches its rows with look-ups and never multiplies them;
  combining tables row by row (one to many, several facts) is a semantic model's relationships (section 3).
- **Text toggle**: the same table as one Rachana statement (`table … from … select … where …`, look-ups as `related(…)` columns) with
  type-ahead.
- **A look-up across connectors shows its cost** (rows each side, memory, time) and is refused above a limit until the reader filters.
- The diagram (option B) is not an editing mode; it may appear later as a read-only picture of the look-ups and a model's relationships.

## 3. Semantic models

Mock-up: [g3-models.html](mockups/editors/g3-models.html), option **A** as the editor, **B** as the Overview and the *+ New* steps,
**C** for a measure's details and the Access tab.

- **Tabs**: Overview, Tables, Fields, Measures, Hierarchies, Row rules, Masks, Access, each with a count.
- **Overview** answers four questions: what it is about, what people look at it by, what numbers it gives, who may see what. The same
  four questions are the *+ New model* steps.
- **A measure's ?** opens its card: the formula, a plain explanation, a worked test value, and where it is used.
- **Access** is a matrix of roles against rows, columns (masked or not) and editing.
- Whether a total adds up is the engine's answer, never the author's: VaR, ES and ratios are computed per total.

## 4. Python-driven tables and models

Mock-up: [g4-python.html](mockups/editors/g4-python.html), option **A** as the editor, **B** (the Notebook) to explore, **C**
(templates) for common cases; all three end in A.

- **Code beside its output contract**: the columns, types, keys and rules the code must return. The inputs, when it runs (on demand
  and cached per business date, or on each load of an input), and its compute pool and limits are on the same page; a sample run and its
  checks on the right.
- **Notebook → Turn into table**: cells explored on the server become a build function, and the contract is filled in.
- **Templates** (join and derive, resample, statistics per group, a file or feed from an approved source) write the Python and show it.
- A Python **model** is the same, with its measures declared in the contract.
- **Where it runs.** The sample run, the Notebook's cells and the table's rows when read all run on the server's compute pool `python`, in
  its strict sandbox: the inputs read as the person running it (row rules and masks), pinned libraries, the code's time and memory limits, no
  network (an approved source is read by the server and handed in as an input), files only in its own folder, every run audited; the rows are
  checked against the contract and a mismatch is refused in words ([PYTHON_COMPUTE.md section 19](PYTHON_COMPUTE.md#19-the-python-runtime-for-code-people-write)).
  Where the server cannot run Python safely (compute off, Windows) the buttons are off and say why.

## 5. People and access

Mock-up: [g5-people.html](mockups/editors/g5-people.html); all three options, as the three tabs of **Admin → People & access**.

- **People**: a searchable list; a person's page with sign-in (directory, MFA, last seen), roles, scope (packs, desks, lens), powers,
  API keys, recent activity, and **what this person sees**.
- **Roles**: every role against every power on one page; built-in roles (from configuration) are locked.
- **Check access**: *can this person do this?* answered yes or no **with the reason** (the role, the rule and the attribute that
  decided it) and how to change it.

## 6. API keys

Mock-up: [g6-apikeys.html](mockups/editors/g6-apikeys.html); all three options.

- **My keys** (everyone): what each key acts as (its owner, never more than the owner's rights), its scopes in words, its limits (only
  these models, only from this network), expiry and **last use** (when, from where, what), Rotate and Revoke.
- **+ New key** in steps: purpose, what it may do, limits and expiry, then the secret **shown once** (Drishti keeps only a fingerprint).
- **Admin → API keys**: every key with owner, scopes, expiry, last use and flags (expiring, unused, used from outside the network),
  bulk revoke, and the key policy (maximum lifetime, unused keys revoked, a rotation warning).

## 7. Rules every editor follows

- **Nothing goes live without review**: a change is proposed and approved by another person (four eyes), versioned and audited.
- **Impact before change**: every editor shows what uses the object (models, Sutras, reports, boards, keys) before it is changed.
- **One grammar**: every formula, filter, row rule and computed column is Rachana, typed in drishti-inputs' code editor (one line for a
  field, the whole text for the statement) with the function catalogue and the editor's names as its completion.
- **Built from Drishti Elements, never by hand** ([ELEMENTS.md section 3.1](ELEMENTS.md#31-standing-rule-no-bespoke-ui)): every pane of
  fields is drishti-inputs' `Form` (live, with the formula widget for a Rachana field: the table's name, rows and look-ups, the model's
  Overview and masks, the Build workbench's inspector through drishti-designer's `Inspector`); the rows of a table of settings (a model's
  tables, relationships, measures, hierarchies and row rules) move onto drishti-grid's editing.
- **One entitlement service**: row rules and masks are enforced on the server for every reader (BI, Pivot, VaR, Flight, the API).
- **Computed on the server**: previews, totals and Python runs happen on the server; the console only displays.
- **Under construction**: an editor or a part of one that depends on unbuilt work is hidden or marked from the registry
  ([UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md)).

## 8. Build checklist

Each item ships with its tests (a click test for every button), its guide section, its tutorial steps (UI, command line and YAML) and
its help entry.

| # | Editor | Built in | Done |
|---|---|---|---|
| 1 | The three-pane shell, the Visual / YAML / Both toggle, the header, + New steps | BI foundation | ☑ |
| 1a | The shell on the designer kit (DesignerShell, YamlPane) in g7's three layouts; every designer on the kit (ELEMENTS.md 12.3) | Drishti Elements | ☑ shell, YAML; ☐ the editors' own panes |
| 2 | Tables and virtual tables (grid, Text toggle, cross-connector cost) | BI foundation | ☑ |
| 3 | Semantic models (tabs, Overview, measure cards, Access matrix) | BI foundation | ☑ |
| 4 | Python-driven tables and models (code and contract, Notebook → table, templates, the sample run on the compute pool) | BI foundation | ☑ |
| 5 | People & access (People, Roles, Check access) | Trust, RBAC and security | ☐ |
| 6 | API keys (My keys, + New key, Admin overview) | Trust, RBAC and security | ☐ |
