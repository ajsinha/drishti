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

# Testing Sutras: `expect.yaml`, `sutra lint|test|preview`, CI

This guide was section 15 of [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md#15-testing-expectyaml-sutra-linttestpreview-ci); it moved here so that guide stays readable.


The workbench checks a screen against your sample files as you type. The `sutra` command runs **exactly the same engine** (the Sutra checker, the sample checker behind the Tests tab, the shape
extractor, auto-design and the view pipeline) without a browser and without a web server, so a pack author gets the same answer in CI. Run it through the command line program, or the server jar:

```
drishti-console/.venv/bin/python tools/drishti.py sutra <command> <path>... [options]
java -jar drishti-server/target/drishti-server-<version>-exec.jar sutra <command> <path>... [options]
```

| Command | Takes | Does |
|---|---|---|
| `lint` | a pack folder, the packs root (`config/packs`: every pack, each with its own fragments), a folder of Sutras or one `.sutra.yaml` | parses and checks every Sutra (with its blocks expanded); problems print with file, line and DRS code; `--strict` makes the help warnings (`DRS-2045` to `DRS-2047`) fail the run; `--docs` adds the docstring warnings (`DRS-2055`, a Sutra or block without a complete `doc:`; never fails the run) |
| `test` | the same | renders each Sutra against its samples, checks `expect.yaml`, and reports the help coverage of the fields it shows |
| `preview` | the same | renders the samples; with `--out dir` writes one HTML snapshot per Sutra and sample |
| `shape` | JSON sample files or a folder | infers the JSON Schema with roles (to the console, or `--out dir/shape.json`) |
| `design` | JSON sample files or a folder | drafts a Sutra with auto-design (`--kind trade` names it; `--out dir` writes `trade.sutra.yaml`) |
| `design --each` | a folder of subfolders of samples | one draft per subfolder in one run (`<out>/<subfolder>.sutra.yaml`; kind: `--kind`, else the subfolder's `kind` file, else its name) |
| `gen` | folders or files of JSON Lines | `tools/sutragen.py`: one Sutra per group of documents, with samples ([below](#drafting-many-sutras-from-json-lines)) |

Options: `--junit file` writes a JUnit XML report (on every path, including a usage error, so a CI report step always finds the file); `--out dir`; `--samples path` (repeatable) to use those JSON
files instead of the Sutra's own samples; `--kind name` for a drafted Sutra; `--strict` for `lint`. **Exit codes:** `0` everything passed (a Sutra without samples is reported as skipped); `1` problems
(a Sutra that does not parse, a panel in error, an expectation not met, an unreadable or invalid sample, which is a failed test case and never a pass); `2` usage (an unknown command or option, a path
that does not exist, an unknown key in `expect.yaml`).

What it prints (a Sutra with a mistake, then a pack's own tests):

```text
$ drishti-console/.venv/bin/python tools/drishti.py sutra lint counterparty-mine/
PROBLEM counterparty-mine/counterparty-mine.v1.sutra.yaml
counterparty-mine/counterparty-mine.v1.sutra.yaml:7 DRS-2021 unknown panel kind 'chart'; expected one of kv, table, tabs, line, area, hbar, ladder, links, status, provenance, markdown, gauge, surface, waterfall, histogram, scatter, candlestick, graph, timeline, pivot, metric, map
$ echo $?
1

$ drishti-console/.venv/bin/python tools/drishti.py sutra test config/packs/market-risk
     frtb-sensitivity: help coverage 9/9 (100%)
ok   config/packs/market-risk/sutras/market-risk/frtb-sensitivity.v1.sutra.yaml (2 samples)
     pnl-explain: help coverage 4/4 (100%)
ok   config/packs/market-risk/sutras/market-risk/pnl-explain.v1.sutra.yaml (2 samples)
…    (stress-result and stress-scenario likewise)
skip config/packs/market-risk/sutras/market-risk/trade-valuation.v1.sutra.yaml (no samples)
     var: help coverage 5/5 (100%)
ok   config/packs/market-risk/sutras/market-risk/var.v1.sutra.yaml (2 samples)
```

`sutra lint config/packs/trading` prints `ok` for each of the pack's 134 Sutras (blocks expanded) in about seven seconds; `sutra test config/packs/trading` renders each against the samples in its
`tests/` folder.

### The tests convention

```
config/packs/<pack>/
  sutras/.../irs-fixfloat.v1.sutra.yaml
  tests/irs-fixfloat/         <- one folder per Sutra, named by the Sutra's name (the `sutra:` line)
    cases.jsonl               <- the cases, one a line: {"case": "MX-20000001", "document": {...}}; the kind comes from the Sutra's `match.kind`
    expect.yaml               <- optional
  tests/help-coverage.txt     <- optional: a floor of help coverage per Sutra (irs-fixfloat=0.9), never lowered
```

Since 2.9.4 a Sutra's cases are **one file, `cases.jsonl`, one case a line** (`{"case": "<name>", "document": {...}}`;
[DATA_FORMATS.md](DATA_FORMATS.md)); each case is reported by its name. A line that is not such an object is a failed case
(`cases.jsonl:3: a case is one line {"case": ...`). **The older layout, one `<name>.json` file per case, is deprecated**: it is still read
during 2.9.4, with `warning: <folder>: one .json file per case is deprecated (2.9.4) ...` on standard error, and will not be read in a
later release; `drishti.py convert` does not write cases, so convert a folder by hand or regenerate it. Other `.jsonl` files in the folder
are plain documents, one a line (reported as `file.jsonl:1`, `file.jsonl:2`...). Without a `tests/<sutra>/` folder, a Sutra file is tested
against the `.json` beside it with the same name (that is how the documented examples work). The trading pack's `tests/irs-fixfloat/expect.yaml`:

```yaml
noErrors: true            # no panel in an error state on any sample (the default)
nonEmpty: [legs, ticket]  # these panels must render with data on every sample
help: { about: true }     # the About this page text renders for every sample
```

The keys: `noErrors`, `nonEmpty` (panel ids), `samples` (per case: `PF-RATES-DESK: { nonEmpty: [definition] }`; the older `PF-RATES-DESK.json` key still matches) and `help` (`coverage`: the share, 0 to 1, of the fields the Sutra shows that must
have a glossary entry; `about: true`: the page text renders). Any other key is a usage error (exit 2), so a typo cannot silently pass. The workbench's *pack fragment export* writes this layout, so an
exported fragment passes `sutra test` unchanged. Choose samples like a tester: a rich document, a thin one that lacks the optional parts, and one with an empty list; `nonEmpty` then names the panels
that must never silently vanish. For a trading product, take the samples from the demo lakes (`curl … /entities/trade/<id>/raw`, the `data`), one per sub type.

### Drafting many Sutras from JSON Lines

`sutra design --each <dir>` drafts one Sutra per immediate subfolder of `<dir>` that holds `.json` or `.jsonl` samples, in one JVM (a JVM start costs about 5 s). `sutra gen` (`tools/sutragen.py`) builds on it: it
splits JSON Lines files into groups by the values of the `--match` fields, writes each group's samples, calls `design --each` once, edits the name, description, `match` line and title id, writes
`tests/<sutra>/cases.jsonl` (cases `sample-1` ...), and runs `sutra lint`. On 400 Endur trades of the committed test data:

```bash
mkdir -p /tmp/gen && zcat data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz | head -400 > /tmp/gen/trade.jsonl
drishti-console/.venv/bin/python tools/drishti.py sutra gen /tmp/gen --kind trade --key TRADE_ID \
    --match SOURCE_SYSTEM,PRODUCT_TYPE --date TRADE_DATE --fallback --out /tmp/genout
```

You should see, in about 20 seconds:

```text
400 documents, 1 kind(s), 6 group(s)
drafting 6 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
trade           SOURCE_SYSTEM=ENDUR, PRODUCT_TYPE=CallNot         6       5  trade-endur-callnot
…               (Cash 65, ComFut 74 and ComSwap 198 documents likewise)
trade           SOURCE_SYSTEM=ENDUR, PRODUCT_TYPE=Commodity      57       5  trade-endur-commodity
trade           (all)                                           400       5  trade-default
ok      /tmp/genout/trade/trade-default.v1.sutra.yaml
…
```

and `/tmp/genout/trade/trade-endur-comswap.v1.sutra.yaml` begins `match: { kind: trade, where: "$.SOURCE_SYSTEM == 'ENDUR' && $.PRODUCT_TYPE == 'ComSwap'", priority: 10 }`, with a `kv` of details, the
legs as tabs, a pivot and the footer panels: a draft to edit, not a finished screen (it formats a numeric `TRADE_ID` with `amount0` in the strip, for one). Remove `/tmp/gen` and `/tmp/genout` when you
are done. The whole workflow, including a complete pack and loading the data, is in [Generating a pack from JSON Lines](PACK_DEVELOPER_GUIDE.md#generating-a-pack-from-json-lines).

### In CI

```sh
drishti-console/.venv/bin/python tools/drishti.py sutra lint config/packs/my-pack --strict
drishti-console/.venv/bin/python tools/drishti.py sutra test config/packs/my-pack --junit target/sutra-tests.xml
drishti-console/.venv/bin/python tools/drishti.py sutra preview config/packs/my-pack --out target/snapshots   # attach as build artifacts
```

Publish `sutra-tests.xml` with your CI's JUnit report step. A Sutra whose panels read other entities with `source:` (section 11) needs those entities' packs enabled for the run: set
`DRISHTI_PACKS=core,market-data,trading` (the same setting as the server) in the job. The command keeps nothing: its identity, governance and design data live in a temporary folder that is removed
when it ends, and no port is opened.

`sutra test docs/guides/examples` passes with the seven packs (the `linked-sources` example reads `CP-SUMMIT`, `NS-SUMMIT-NY` and `DESK-FI` from them); without them its failure line says `NS-SUMMIT-NY
not found`, which means the pack holding that entity is not enabled. `sutra test config/packs/<pack>` reports `skip (no samples)` for a Sutra with no `tests/<sutra>/` folder; that is not a failure.

**Limits.** The CLI keeps the server's input limits: a file over `drishti.builder.max-file-mb` (5 MB by default) is refused with one line that names it, text that is not UTF-8 says so, an engine that
cannot start (for example a pack that is not found: set `DRISHTI_PACKS_DIR`) ends with `sutra: ...` and exit `1`, never a stack trace. `--kind` is a name (letters, digits, `.`, `_`, `-`, up to 64),
not a path. `test` looks for `tests/<sutra>/` only inside the paths you gave it.

**In this repository**, `SutraCliTest` runs `sutra test` over every shipped pack that has a `tests/` folder (counterparty-risk, market-risk, trading, world-economy) and over the documented examples,
so the Maven build fails when a Sutra change breaks a pack's own tests. Add a `tests/<sutra>/` folder with one or two sample documents and an `expect.yaml` to any pack to put its Sutra under the same
guard.
