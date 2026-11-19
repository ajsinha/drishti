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
# The daily run: CSV extracts to VaR and exposure inputs

One command a day turns the day's CSV extracts into checked files of each kind and hands them to the data inbox, in the order the engines
need them. This page is the operator's; each kind's columns and rules are on its own page ([the kinds](README.md)).

## Prepare your environment

From the repository root (or the install folder), Python 3.11 or later with PyYAML; Polars and NumPy for the scenario PVs of the PVF
source:

```bash
pip install -r drishti-console/requirements.txt -r drishti-console/requirements-polars.txt
```

The scripts are run by path. Converting needs no Java, no server and no network; `--load` uses Drishti's own `lake build` (Java and the
server jar); `--inbox` needs the node that owns the data load running (`DRISHTI_DATALOAD_ENABLED`).

## 1. The files and the day file

Every day the extracts land in one folder, for example `data/mr/2026-10-29/`. A **day file** says which file is which kind, once
(`tools/mrloader/days/sample.yaml` is the sample's; a site's own lives with its settings and is given by path):

```yaml
format: 1
lake: risklake
kinds:
  legal-entity:         {csv: "legal_entity.csv"}
  desk:                 {csv: "desk.csv"}
  book:                 {csv: "portfolio_d2.csv", source: pvf}
  scenario-pv:          {run: pvf, pv: "pvf_path_{YYYYMMDD}.csv", positions: "pos_{YYYYMMDD}.csv", portfolio: "portfolio_d2.csv", fx: "fx_{YYYYMMDD}.csv"}
  var-limit:            {csv: "var_limits_{YYYYMMDD}.csv"}
  risk-factor:          {csv: "risk_factors.csv", optional: true}
  position-sensitivity: {csv: "sens_positions_{YYYYMMDD}.csv", child: {SENSITIVITIES: "sens_{YYYYMMDD}.csv"}, source-system: MUREX}
  desk-pnl:             {csv: "desk_pnl_{YYYYMMDD}.csv"}
required: [scenario-pv]
```

A kind whose extract has the bank's own column names gets `mapping: /path/to/site/mappings/var-limit.yaml` in its entry: a copy of the
shipped `tools/mrloader/kinds/<kind>.yaml` with the `from:` columns renamed (and the `csv:` block for the file's delimiter, decimal and
date format). Never edit the shipped mappings.

## 2. The one command

```bash
python3 tools/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day /path/to/site/day.yaml --dry-run   # check everything first
python3 tools/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day /path/to/site/day.yaml --inbox     # then convert and hand over
```

It converts every kind of the folder in dependency order (masters, then the scenario set and PVs, then limits and sensitivities), checks
the kinds against each other, and only then hands every file to the inbox: a day that fails leaves the lake as it was. It prints one
table:

```text
kind                   status        read  written rejected  links seconds
legal-entity           DONE             4        4        0      0       0
var-limit              DONE            13       13        0      0       0
```

`links` counts link values that resolve to nothing (a limit on a node the day's positions do not build, a sensitivity on an unknown
factor); each is listed in `mr-<date>/links-<date>.csv`. A link that changes a number (a netting set's CSA, a valuation's netting set)
refuses the day; the rest warn.

## 3. Reading a reject report

Each kind writes `mr-<date>/<kind>.<date>.rejects.csv`, one line per problem: `FILE, ROW` (as the spreadsheet shows it), `KEY`, `COLUMN`,
`FIELD`, `VALUE`, `REASON`, `WHY`, `FIX`. The commonest fixes:

| REASON | Usually | Fix |
|---|---|---|
| `BAD_DATE` | dates rewritten to the machine's locale (`29/10/2026`) | export ISO dates, or declare `csv: {dates: DD/MM/YYYY}` in the site's mapping |
| `BAD_NUMBER` | a currency symbol, or a European decimal comma | export plain numbers, or declare `decimal: ","` and `thousands: "."` |
| `MISSING_REQUIRED` | an empty cell in a column the engine needs | fill it in the source system and export again |
| `EXCEL_NUMBER_ID` | a long id turned into `2.00001E+11` | export the id column as text |
| `DUPLICATE_KEY` | the same record twice | keep one row per key; a duplicate always refuses the kind |

## 4. When a kind is refused

Masters and limits refuse the whole kind on any rejected row (`on-reject: refuse`): fix the file and run the day again. The large
kinds (sensitivities) skip a bad row up to their `max-rejects`. `--keep-going` delivers the kinds that do not depend on a refused one;
without it, nothing is delivered.

## 5. Checking the load

The inbox moves each file to `data/dataload/outbox/<day>/` with its report (`<file>.loaded.json`), or to `failed/` with the reason;
**Admin → Data loads** shows the same. Then open VaR for the date (the limits show in the Limit column) and, for exposure, a
counterparty's page.

## 6. Again, restate, remove

- **The same day again:** run the same command. Every file replaces its kind's date as a whole; unchanged files have the same bytes.
- **Restate a past date:** run it with that `--date` and the corrected files; the kinds with a `RESTATEMENT` field take
  `--restatement 1` (then 2 ...) in `csv_load.py`. To change one row, send the whole file again.
- **One kind only:** `python3 tools/mrloader/csv_load.py --kind var-limit --csv FILE --date 2026-10-29 --inbox`.
- **Remove a kind's date:** `python3 tools/drishti.py lake delete --lake risklake --kind var-limit --date 2026-10-29 --dry-run`, then
  without `--dry-run`. **Remove a whole day:** `mr_run.py --date 2026-10-29 --from DIR --day FILE --delete-plan` prints every line, vectors first.
- **A master that skips a day** is answered from its last delivery (the reference tables are read as *effective*), and the page says
  *no load for the date, showing* the earlier one. A row left out of today's master file does **not** disappear from those tables:
  remove it with `lake delete` of its earlier dates, or keep sending it with its status.

## 7. Spreadsheets

Export from the source system; do not round-trip a file through Excel. When a file must be edited in Excel, import every column as text:
Excel silently turns long ids into numbers, drops leading zeros, rewrites dates to the machine's locale and rounds numbers beyond 15
digits. The loader refuses what it can detect (`EXCEL_NUMBER_ID`, `EXCEL_ERROR`, `FORMULA`, `BAD_DATE`); rounding it cannot see.
