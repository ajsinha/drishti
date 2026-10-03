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
# The headless Sutra tool: lint, test, shape, design and preview from a terminal or CI

The workbench checks a screen against your sample files as you type. The `sutra` command runs exactly the same engine
(the Sutra checker, the sample checker behind the Tests tab, the shape extractor, auto-design and the view pipeline) without a
browser and without a web server, so a pack author can run it in CI and get the same answer.

```
java -jar drishti-server-<version>-exec.jar sutra <command> <path>... [options]
```

| Command | Takes | Does |
|---|---|---|
| `lint` | a pack folder, a folder of Sutras or one `.sutra.yaml` | parses and checks every Sutra; problems print with file, line and DRS code |
| `test` | the same | renders each Sutra against its samples and checks `expect.yaml` |
| `preview` | the same | renders the samples; with `--out dir` writes one HTML snapshot per Sutra and sample |
| `shape` | JSON sample files or a folder of them | infers the JSON Schema with roles (to the console, or `--out dir/shape.json`) |
| `design` | JSON sample files or a folder of them | drafts a Sutra with auto-design (`--kind deal` names it; `--out dir` writes `deal.sutra.yaml`) |

Options: `--junit file` writes a JUnit XML report; `--out dir` writes results there; `--samples path` (repeatable) uses those
JSON files or folders instead of the Sutra's own samples; `--kind name` is the entity kind of a drafted Sutra.

**Exit codes.** `0` everything passed (Sutras without samples are reported as skipped). `1` problems: a Sutra that does
not parse, a panel in error, an expectation not met, an unreadable file. `2` usage: an unknown command or option, a path
that does not exist, an unknown key in `expect.yaml`.

`--junit` is written on every path, including an exit-2 usage error and an unreadable sample, so a CI report step always finds the file. A sample that is not valid JSON, a file with no content, or a name under `samples:` in `expect.yaml` that is not a file of the Sutra, is a **failed test case** (not a crash and never a pass); a UTF-8 byte-order mark is ignored. `tests/<sutra>/` may hold `.json` files (one document) and `.jsonl` files (one document per line, reported as `file.jsonl:1`, `file.jsonl:2`...). `design` prints the draft with one trailing newline.

## The tests convention

```
packs/<pack>/
  sutras/.../var.v1.sutra.yaml
  tests/var/                  <- one folder per Sutra, named by the Sutra's name (the `sutra:` line)
    VAR-COMM.json             <- plain entity documents; the entity kind comes from the Sutra's `match.kind`
    VAR-FX.json
    expect.yaml               <- optional
```

Without a `tests/<sutra>/` folder, a Sutra file is tested against the `.json` beside it with the same name (that is how the
ten documented examples in `docs/guides/examples` work).

`expect.yaml`:

```yaml
noErrors: true            # no panel in an error state on any sample (the default)
nonEmpty: [pnl, limits]   # these panels must render with data on every sample
samples:                  # optional: more expectations for one sample file
  VAR-COMM.json: { nonEmpty: [backtest] }
```

Any other key is a usage error (exit 2), so a typo cannot silently pass. The workbench's pack fragment export writes this
layout, so an exported fragment passes `sutra test` unchanged.

## In CI

```sh
java -jar drishti-server-exec.jar sutra lint packs/my-pack
java -jar drishti-server-exec.jar sutra test packs/my-pack --junit target/sutra-tests.xml
java -jar drishti-server-exec.jar sutra preview packs/my-pack --out target/snapshots   # attach as build artifacts
```

Publish `sutra-tests.xml` with your CI's JUnit report step. A Sutra whose panels read other entities with `source:` needs
those entities' packs enabled for the run: set `DRISHTI_PACKS=market-risk,counterparty-risk` (the same setting as the
server) in the job. The command keeps nothing: its identity, governance and design data live in a temporary folder that is
removed when it ends, and no port is opened.

## In this repository

`SutraCliTest` runs `sutra test` over every shipped pack that has a `tests/` folder and over the documented examples, so the
Maven build fails when a Sutra change breaks a pack's own tests. Add a `tests/<sutra>/` folder with one or two sample
documents and an `expect.yaml` to any pack to put its Sutra under the same guard.
