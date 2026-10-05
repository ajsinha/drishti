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
# The `drishti` command line

One Python program, `tools/drishti.py`, does everything you do from a terminal with Drishti: turn your JSON Lines into a
pack, check a pack in CI, load data, load the pack into a running server, and drive the Screen Designer's workflow
(create, check, propose, approve, ship) over REST. This guide explains the idea, installs it, shows **every command with all
its options, real examples and real output**, and ends with recipes, the JSON formats, exit codes, a troubleshooting table
and how to run it all from PyCharm.

- [Quickest path: `pack make`](#quickest-path-pack-make)
- [1. What it is](#1-what-it-is)
- [2. Install and prerequisites](#2-install-and-prerequisites)
- [3. Connecting to a server: URL, tokens and permissions](#3-connecting-to-a-server-url-tokens-and-permissions)
- [4. `sutra`: shape, design, gen, lint, test, preview](#4-sutra-shape-design-gen-lint-test-preview)
- [5. `pack`: make, new, regenerate, diff, catalogue, i18n, check, about-check, bundle, verify, deploy, rollback, publish, keygen, install](#5-pack-make-new-regenerate-diff-catalogue-i18n-check-about-check-bundle-verify-deploy-rollback-publish-keygen-install)
- [6. `data`: profile, ingest and load](#6-data-profile-ingest-and-load)
- [7. `server`: health and packs](#7-server-health-and-packs)
- [8. `design`: the Screen Designer over REST](#8-design-the-screen-designer-over-rest)
- [9. `docs`: screenshots](#9-docs-screenshots)
- [10. Recipes](#10-recipes)
- [11. JSON output formats](#11-json-output-formats)
- [12. Exit codes](#12-exit-codes)
- [13. Troubleshooting](#13-troubleshooting)
- [14. Running the tools from PyCharm (and the Java `sutra` from IntelliJ)](#14-running-the-tools-from-pycharm-and-the-java-sutra-from-intellij)

## Quickest path: `pack make`

You have a folder of JSON Lines (or one file). **One command** turns it into **one folder you take and deploy**: the pack with its
Sutras, the data in a Delta lake partitioned by business date, a checksummed bundle, a server configuration, start scripts and a
`README.txt` with your real values filled in.

```bash
uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py pack make data/jsonl \
    --kind trade --match productType --name my-bank
```

| Option | Meaning |
|---|---|
| `INPUT...` | folder(s) of `*.jsonl` and/or file(s) (required) |
| `--kind KIND` | the kind of every document, e.g. `trade` (required) |
| `--match COL[,COL]` | the column(s) whose values split the kind: **one Sutra per value** (required) |
| `--name NAME` | the pack name, lower case, digits and `-` (required) |
| `--key FIELD` | the id field. Default: **detected**: a field present and unique in every document, `<kind>Id` / `id` first; it prints what it chose and why, and **refuses** (naming `--key`) when none is unique |
| `--date FIELD` | the business-date field. Default: **detected**: a date-valued field (`businessDate`, `asOf`, `date`, ...) present in at least 95% of the documents; none found: no data folder, the pack serves its samples |
| `--title`, `--version` | the pack title; the version (default `1.0.0`) |
| `--store delta\|files` | the dated store: `delta` (default) or the File connector's files |
| `--out DIR` | the folder to create (default `build/<name>-<version>`); `--force` replaces it |
| `--fallback` / `--no-fallback` | a catch-all Sutra for documents matching no group (default on) |

What it makes (everything the server needs is in this one folder):

```text
build/my-bank-1.0.0/
├── README.txt                  step by step: what is inside, deploy, verify, LIFT DATA FROM DELTA, update, roll back, troubleshooting
├── pack/my-bank/               pack.yaml, sutras/, tests/, samples/, config/about.yaml, README.md
├── data/delta/my-bank/trade/   the documents, one Delta table, partitioned by business_date=YYYY-MM-DD   (data/files/ with --store files)
├── bundle/                     my-bank-1.0.0.tar.gz  +  .sha256     (the pack alone, for `pack deploy`)
├── config/drishti-site.yaml    a server configuration overlay (packs on; the data root explained)
├── run-server.sh               start a server on this folder alone: DRISHTI_JAR=... DRISHTI_PORT=18480 ./run-server.sh
├── run-server.ps1              the same for Windows
└── MANIFEST.json               counts per date, the chosen key and date, tool versions, checksums
```

It ends by running `pack check` and `pack verify` on what it made and prints a summary and the next command. Open `README.txt`:
deploying is "copy `pack/my-bank` into the server's packs folder (or `drishti.packs.installed-dir`), copy `data/delta` to its lake
root or set `DRISHTI_DELTA_ROOT`, switch the pack on with `DRISHTI_PACKS`". New days later:
`data ingest --from new-days --pack build/my-bank-1.0.0/pack/my-bank --lake <the lake>`. The details are in
[section 5](#pack-make-everything-in-one-folder) and [OPERATIONALISING.md](OPERATIONALISING.md#16-pack-make-one-folder-to-deploy).

## 1. What it is

`tools/drishti.py` is a thin, honest front door. It adds no new engine: each command either runs something that already
exists (the Java `sutra` tool in the server's jar, the generators and loaders under `tools/`) or calls a REST endpoint the
server already has. What it adds is one place to look, one `--help` per command, one set of exit codes and, where it helps
scripts, `--json`.

| Group | What it is for | Runs | Needs |
|---|---|---|---|
| `sutra` | shape, design, lint, test and preview Sutras; `gen` drafts one Sutra per group of JSON Lines | the Java tool in the exec jar; `tools/sutragen.py` | JDK 21+, the exec jar |
| `pack` | create a pack from JSON Lines, check it in CI, find missing help text, sign and publish it, install one from a registry | `tools/packgen/pack_from_jsonl.py`, the Java tool, `tools/packreg/packreg.py`; REST for `install` | PyYAML; a server for `install` |
| `data` | write your JSON Lines into a Delta lake or the file store; load the demo data into any store | `tools/ingest_jsonl.py`, `tools/load-<store>.sh` | `deltalake` + `pyarrow` for Delta |
| `server` | health, and pack list / load / unload / on / off on a running server | REST `/api/v1/admin/...` | a server, an administrator |
| `design` | the Screen Designer's designs: create, save, check, propose, approve, export, import, bind | REST `/api/v1/builder/designs`, `/api/v1/sutras/proposals` | a server |
| `docs` | regenerate the guides' screenshots | `tools/docs/screenshots.py` | Playwright |

Concepts used throughout (each is explained in its own guide; this one only links to them):

- A **pack** is a folder: `pack.yaml`, Sutras, samples, tests, help text.
  [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md).
- A **Sutra** is the YAML that says how one kind of document is shown. [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md).
- A **design** is a Sutra in progress in the Screen Designer, with its samples and its history of edits.
  [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md).
- A **dated store** is where a pack reads documents *per business date*: a Delta lake or the file connector's
  `<root>/<domain>/<date>/<kind>.jsonl` files.
- A pack becomes known to a running server in **three ways**: the server is started with it in `DRISHTI_PACKS` (a restart),
  an administrator loads it in **Admin → Packs** (the server restarts in place), or it is installed from a registry.
  `drishti.py pack new --load`, `server packs load` and `pack install` are the command-line forms of the last two.
  [PACKS.md#loading-a-pack-while-the-server-runs](PACKS.md#loading-a-pack-while-the-server-runs) has the rules.

Run the program from the repository root, or from anywhere with its path. These examples write `python3 tools/drishti.py`;
`drishti.py` in the output is the same program.

**`drishti.py --help`**

```text
usage: drishti.py [-h] GROUP ...

drishti: one command line for building, checking, loading and shipping Drishti packs and Sutras.

positional arguments:
  GROUP
    sutra     The Sutra tool: shape, design, lint, test, preview (Java) and
              gen (one Sutra per group of JSON Lines)
    pack      Packs: generate from JSON Lines, check in CI, find missing help
              text, publish and install
    data      Data: ingest your own JSON Lines into a lake or files store;
              load the demo data into any store
    server    A running server over REST: health and pack management
              (administrator)
    design    Screen Designer designs over REST: create, edit, check, propose,
              approve, ship (anyone may design; approving needs an approver)
    docs      Documentation tooling

options:
  -h, --help  show this help message and exit

    python3 tools/drishti.py <group> <command> [options]          (or: uv run --with pyyaml python tools/drishti.py ...)

Groups: sutra (the Java `sutra` tool, plus `sutra gen`), pack (new, check, about-check, bundle, verify, deploy, rollback, publish, keygen, install),
data (ingest your own JSON Lines, load the demo data), server (health, packs), design (the Screen Designer's designs over
REST: create, save, check, propose, approve, export, import, bind) and docs (screenshots).
Needs Python 3.10+ and PyYAML; deltalake and pyarrow only for `--store delta` ingests. The guide with every command,
recipes and real output is docs/guides/CLI_GUIDE.md.

Exit codes: 0 ok, 1 problems found (a failing check, a server that said no, an unreachable server), 2 usage (a bad option,
a missing file or jar). Commands that talk to a server read it from --server or DRISHTI_SERVER (default
http://localhost:18480) and the token from DRISHTI_TOKEN or --token-file; the token is never printed.
```

## 2. Install and prerequisites

| You need | For | How to check |
|---|---|---|
| Python 3.10 or newer | everything | `python3 --version` |
| PyYAML | `pack`, `data ingest`, `design create --from-sutra` | `python3 -c "import yaml"` |
| `deltalake` and `pyarrow` | `data ingest --store delta`, `pack new --lake` | only when you write a Delta lake |
| JDK 21 or newer | `sutra`, `pack new`, `pack check`, `pack about-check` | `java -version` (the tool prefers `JAVA_HOME`, then `/usr/lib/jvm/java-21-openjdk-amd64`, then `java`) |
| the exec jar `drishti-server/target/drishti-server-<version>-exec.jar` | the same | `./mvnw -q -DskipTests package`, or pass `--jar` / set `DRISHTI_JAR` |
| `openssl` | `pack keygen` | `openssl version` |
| Playwright | `docs shots` | see [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md) |

The program uses only the standard library plus PyYAML. It imports `deltalake` and `pyarrow` **only** when a command writes
a Delta lake, so a machine without them runs everything else.

**With `uv`** (nothing to install into your system Python; the recommended way):

```bash
uv run --with pyyaml python tools/drishti.py --help
uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py data ingest --from data/new --pack packs/my-bank --lake data/delta
```

**With a virtual environment** (what an IDE wants, see [section 14](#14-running-the-tools-from-pycharm-and-the-java-sutra-from-intellij)):

```bash
uv venv .venv-tools && uv pip install --python .venv-tools/bin/python pyyaml deltalake pyarrow
.venv-tools/bin/python tools/drishti.py --help
```

**Environment variables** the program reads:

| Variable | Meaning | Default |
|---|---|---|
| `DRISHTI_JAR` | the exec jar the Java commands run | the newest `drishti-server/target/drishti-server-*-exec.jar` |
| `JAVA_HOME` | the JDK that runs it | `/usr/lib/jvm/java-21-openjdk-amd64`, then `java` on the `PATH` |
| `DRISHTI_SERVER` | the server's base URL | `http://localhost:18480` |
| `DRISHTI_TOKEN` | the bearer token (never printed) | none |
| `DRISHTI_USER` | the user name sent when the server has sign-in off | none |

The Java commands run **from the repository root** (the engine reads `./packs` and `./sutras` relative to it), so every path
you give is made absolute first: use paths relative to *your* current directory as you normally would.

## 3. Connecting to a server: URL, tokens and permissions

Commands in `server`, `design`, `pack install` and `pack new --load` call a running server over REST.

```bash
export DRISHTI_SERVER=http://localhost:18480        # or --server URL on each command
export DRISHTI_TOKEN=...                            # or --token-file /path/to/file
python3 tools/drishti.py server health
```

The token comes from `--token-file` (the file's text, trimmed), else the `DRISHTI_TOKEN` environment variable. It is sent as
`Authorization: Bearer <token>` and **never printed**, not even in an error.

| The server | What to send |
|---|---|
| sign-in **off** (the default for local development) | nothing, or `--user NAME` (header `X-Drishti-User`) to be a named user. Every role is granted. |
| sign-in **on** | a **signed token** whose `sub` is you and whose `roles` carry what you need; see [API_GUIDE.md](API_GUIDE.md) ("Security on" and "Minting a token for a script"). |

**Personal API tokens (`drk_…`, from My account → API tokens) read by default, and write only with a scope.** A token made
without scopes only reads: it is enough for `server health`, `server packs list`, `design list|get|proposals` and
`pack install --list`. To let a tool, a CI job or a GitOps pipeline change things, make the token with the scopes it needs
and nothing more (least privilege). On **My account → API tokens** tick the scopes, set **Days** (a token with a write scope
must expire: 90 days at most by default) and copy the secret once:

| Scope | Opens | Also needs the user to hold |
|---|---|---|
| `read` (always) | everything you may read | nothing |
| `design:write` | `design create\|save\|check\|autodesign\|import\|delete\|bind\|propose` (the Designs and Studio writes) | a role with `author` |
| `design:approve` | `design approve\|reject` | a role with `approve` (or admin), and never your own proposal with four-eyes on |
| `packs:admin` | `server packs load\|unload\|on\|off`, `pack install` | the admin role |

A token never exceeds its user: the server checks the scope **and** the roles the user holds at the moment of each call, so
a scope on an author's token does not make them an approver, and a disabled or deleted user's tokens stop at once. A token can
never make tokens, manage users or change personal and collaboration state. Every write done with a token is in the audit log
(`token-write`, with the token id and never the secret; refused attempts are `token-denied`). Use it in CI like this:

```bash
# one token per pipeline, scope design:write only, 30 days; stored as a CI secret, never in the repository
echo "$DRISHTI_CI_TOKEN" > "$RUNNER_TEMP/drishti.tok"
drishti design propose my-design --server https://drishti.bank.example:18480 --token-file "$RUNNER_TEMP/drishti.tok"
```

Without the scope the server answers `403 DRS-5002 this token lacks the scope design:write`. Rotate by making a new token
and revoking the old one. A signed token (API_GUIDE "Minting a token for a script") works as before.

**Permissions follow the screens.** The command line asks the same endpoints the screens do, so it can do exactly what you
could do there and nothing more:

| Command | Who may |
|---|---|
| `design list|create|get|save|check|autodesign|export|import|delete` | any user (your own designs) |
| `design propose` | a user who may save: a role with `author`, and the server's `drishti.rachana.studio-save: true` (`DRISHTI_STUDIO_SAVE`) |
| `design proposals` | any user; `design approve|reject`: a role with `approve` (or an administrator); with sign-in on and four-eyes on (the default) not your own proposal |
| `design bind` | a user, when the server has file binding on (a development directory per user) |
| `server health`, `server packs ...`, `pack install` | an administrator |

A refusal is exit code 1 with the server's own words, for example `the server said 403 DRS-5002: ...`.

## 4. `sutra`: shape, design, gen, lint, test, preview

`sutra shape|design|lint|test|preview` run the **Java tool** in the exec jar (`java -jar ... sutra <command>`) with your
arguments; its output, its `--junit` report and its exit code (0 ok, 1 problems, 2 usage) come straight through. What each
does, `expect.yaml`, `tests/<sutra>/` and CI are in [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md#15-testing-expectyaml-sutra-linttestpreview-ci).
Options this program does not know are passed on to the Java tool unchanged.

**`drishti.py sutra lint --help`**

```text
usage: drishti.py sutra lint [-h] [--jar JAR] [--java JAVA] [--out OUT]
                             [--junit JUNIT] [--strict] [--samples SAMPLES]
                             [--kind KIND] [--each]
                             PATH [PATH ...]

sutra lint: parse and check every Sutra of a pack or file (--strict: help warnings DRS-2045..2047 fail)

positional arguments:
  PATH               a pack folder or .sutra.yaml

options:
  -h, --help         show this help message and exit
  --out OUT          write results (and for design, the Sutra) here
  --junit JUNIT      write a JUnit XML report to this file
  --strict           lint: help warnings (DRS-2045 to 2047) fail the run
  --samples SAMPLES  JSON samples to use instead of the pack's tests/ folder
                     or the Sutra's sibling .json
  --kind KIND        design: the kind to name the Sutra for
  --each             design: one Sutra per subfolder of .json samples

the Java tool:
  --jar JAR          the drishti-server exec jar (default: DRISHTI_JAR, then
                     the newest drishti-server/target/drishti-
                     server-*-exec.jar)
  --java JAVA        the java binary (default: JAVA_HOME, then JDK 21, then
                     java on the PATH)

examples:
  drishti.py sutra lint packs/market-risk --strict
  drishti.py sutra test packs/market-risk --junit build/market-risk.xml
  drishti.py sutra shape samples/ --out build
  drishti.py sutra design samples/ --kind ticket
Unknown options are passed to the Java tool. Exit codes as the Java tool: 0 ok, 1 problems, 2 usage.
```

**Lint a pack** (the shipped help-desk example; the warnings are help-text gaps, DRS-2047, not errors, so the exit code is 0):

```bash
python3 tools/drishti.py sutra lint docs/guides/examples/pack/helpdesk
```

```text
ok      docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'subject' is shown but has no glossary entry (kinds.ticket...
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'priority' is shown but has no glossary entry (kinds.ticke...
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'ageHours' is shown but has no glossary entry (kinds.ticke...
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'assignee' is shown but has no glossary entry (kinds.ticke...
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'client' is shown but has no glossary entry (kinds.ticket....
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'history.event' is shown but has no glossary entry (kinds....
```

`--strict` turns those DRS-2045 to 2047 help warnings into failures (exit 1). `sutra test <pack>` renders every Sutra against
its `tests/<sutra>/` samples and checks `expect.yaml`; `--junit report.xml` writes a JUnit file; `sutra preview <pack> --out
build/html` writes an HTML snapshot per sample.

**Infer a shape** (a JSON Schema with Drishti's role annotations) and **draft a Sutra** from sample documents:

```bash
python3 tools/drishti.py sutra shape docs/guides/examples/pack/helpdesk/samples/ticket
python3 tools/drishti.py sutra design docs/guides/examples/pack/helpdesk/samples/ticket --kind ticket
```

```text
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "ticketId" : {
      "type" : "string",
      "x-drishti" : {
        "role" : "id",
        "reason" : "named like an id, and no value repeats"
      }
    },
    "subject" : {
      "type" : "string"
    },
    "status" : {
      "type" : "string",
...
    "samples" : 3
  }
}
```

```text
rachana: 1
sutra: ticket-auto
version: 1
description: Drafted by auto-design from 3 samples. Edit freely.
match: { kind: ticket, priority: 1 }
title: { pill: "Ticket · ${$.assignee}", id: "$.ticketId" }
strip:
  - { label: "Status", bind: "$.status", tone: "status" }
  - { label: "Client", bind: "$.client" }
  - { label: "Client name", bind: "$.clientName" }
  - { label: "Age hours", bind: "$.ageHours", fmt: "amount0", emphasis: true }
  - { label: "Source", bind: "$._meta.source" }
  - { label: "Generation", bind: "$._meta.generation", fmt: "pct0" }
panels:
  - id: details
    kind: kv
    title: "Details"
    key: "F2"
...
    kind: gauge
    title: "Generation"
    area: right
    value: "$._meta.generation"
    fmt: "pct0"
    max: "1"
```

`sutra design --each DIR` drafts one Sutra per subfolder of samples in a single run (`<out>/<subfolder>.sutra.yaml`; the
kind is `--kind`, else the folder's one-line `kind` file, else the folder name).

### `sutra gen`: one Sutra per group of JSON Lines

`sutra gen` (`tools/sutragen.py`) reads folders or files of JSON Lines, splits each kind into groups by the fields you name
with `--match`, drafts one Sutra per group in one Java run, writes samples for each, and lints the result.

**`drishti.py sutra gen --help`**

```text
usage: drishti.py sutra gen [-h] [--kind KIND] [--key KEY] [--date DATE]
                            [--match MATCH] [--skip-missing]
                            [--samples SAMPLES] [--min-docs MIN_DOCS]
                            [--priority PRIORITY] [--fallback]
                            [--name-prefix NAME_PREFIX] [--jar JAR]
                            [--java JAVA] [-r] --out OUT
                            [--tests-dir TESTS_DIR] [--no-lint]
                            inputs [inputs ...]

sutra gen: one Sutra per group of JSON Lines documents, with samples (tools/sutragen.py)

positional arguments:
  inputs                folders of *.jsonl and/or single .jsonl files

options:
  -h, --help            show this help message and exit
  --kind KIND           kind for every document (default: the file stem, or
                        the envelope's kind)
  --key KEY             id field (dotted): FIELD, or kind=FIELD,kind2=FIELD2;
                        becomes the title id
  --date DATE           business-date field (dotted): FIELD or kind=FIELD,...;
                        kept out of the match, samples span dates
  --match MATCH         fields whose values split the kind: a,b or
                        kind=a,b;kind2=c ; one Sutra per combination
  --skip-missing        leave out documents lacking a match field (default: a
                        `== null` group)
  --samples SAMPLES     samples per group (default 5)
  --min-docs MIN_DOCS   skip groups with fewer documents (default 1)
  --priority PRIORITY   priority of a match-field Sutra (default 10)
  --fallback            also write <kind>-default (no where, priority 1)
  --name-prefix NAME_PREFIX
                        prefix of every Sutra name
  --jar JAR             the drishti-server exec jar (default: newest under
                        drishti-server/target)
  --java JAVA           java binary (default: JAVA_HOME, then JDK 21, then
                        java)
  -r, --recursive       read *.jsonl in subfolders too
  --out OUT             writes <out>/<kind>/<kind>-<slug>.v1.sutra.yaml and
                        <out>/tests/<sutra>/sample-*.json
  --tests-dir TESTS_DIR
                        put the samples here instead of <out>/tests
  --no-lint             do not run `sutra lint` on the result

example:
  drishti.py sutra gen data/jsonl --key trade=tradeId --match trade=productType --out build/sutras
```

```bash
python3 tools/drishti.py sutra gen data/jsonl --key trade=tradeId,counterparty=counterpartyId \
    --date businessDate --match trade=productType --out build/sutras
```

```text
sutra: build/sutras does not exist
usage: sutra <command> <path>... [options]
  lint    <pack-dir | sutra-file>      parse and check every Sutra
  test    <pack-dir | sutra-file>      render each Sutra against its samples and check expect.yaml
  preview <pack-dir | sutra-file>      render samples; --out writes HTML snapshots
  shape   <samples.json | dir>         infer the shape (JSON Schema) of the samples
  design  <samples.json | dir>         draft a Sutra from the samples (--kind names it)
  design --each <dir>                  one Sutra per subfolder of .json samples, in one run: <out>/<subfolder>.sutra.yaml;
                                       the kind is --kind, else the subfolder's one-line 'kind' file, else the folder name
options: --junit file   JUnit XML report     --out dir   write results here
         --strict       lint: help warnings (DRS-2045 to 2047) fail the run
         --samples path JSON samples to use instead of the pack's tests/ folder or the Sutra's sibling .json
exit codes: 0 ok, 1 problems, 2 usage
64 documents, 2 kind(s), 4 group(s)
drafting 4 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
counterparty    (all)                                             4       4  counterparty-default
trade           productType=bond                                 17       5  trade-bond
trade           productType=fx-forward                           24       5  trade-fx-forward
trade           productType=swap                                 19       5  trade-swap
```

Each Sutra is `build/sutras/<kind>/<kind>-<group>.v1.sutra.yaml` with its `where` clause, and
`build/sutras/tests/<sutra>/sample-*.json` holds samples drawn across the dates. The generator is described in
[SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md); `--fallback` also writes a `<kind>-default` Sutra for documents no group matches.


#### Many product types in many files: one command, one Sutra per product type

A common case: a folder of JSON Lines files where every line is a trade with a unique `tradeId` and a product type, with
the products mixed across files in any way. **One run does it all**: `sutra gen` reads every file, groups the trades by
the `--match` field across all of them, and writes **one Sutra per distinct product type**. There is no need to split the
files by product or to run the command once per product.

```bash
python3 tools/drishti.py sutra gen data/jsonl --kind trade --key tradeId --match product_type --out build/sutras
```

Two things decide whether it does what you expect:

1. **The kind comes from the file name** unless you say otherwise. `trade.jsonl` is kind `trade`; a file named
   `trades_2026-10-01.jsonl` or `book1.jsonl` would be its own kind, and the per-kind form `--key trade=tradeId` would not
   apply to it. When every line is a trade, pass **`--kind trade`**; then the plain forms `--key tradeId` and
   `--match product_type` are enough. (Loader envelope lines carry their own `kind` and need no `--kind`.)
2. **Use the field name exactly as it is in the data** (`product_type` and `productType` are different fields). A
   misspelt match field puts every trade in one group without the field, so you would get a single Sutra; add
   `--skip-missing` to leave out documents that lack a match field instead.

What comes out, for 40 product types:

| Output | Where |
|---|---|
| 40 Sutras, each with `match: { kind: trade, where: "$.product_type == 'IRS'", priority: 10 }` and the title id `$.tradeId` | `build/sutras/trade/trade-<product>.v1.sutra.yaml` |
| up to 5 sample trades per Sutra (`--samples N`; chosen deterministically, across dates with `--date`) | `build/sutras/tests/<sutra>/sample-*.json` |
| a lint of everything written | exit code 1 if any Sutra has a problem |

Options that help here: `--fallback` adds a low-priority `trade-default` Sutra so a product type that appears later still
gets a designed screen (rather than an inferred one); `--min-docs N` skips product types with fewer documents;
`--date businessDate` picks samples across business dates, newest first; several match fields
(`--match product_type,currency`) give one Sutra per combination. To get a whole pack (kinds, mnemonics, tests, About
skeleton, optionally a Delta lake or files) instead of Sutra files only, use `pack new` with the same options
([below](#pack-new-a-complete-pack-from-json-lines)).

It needs the built server jar (`./mvnw package -DskipTests`) and a JDK 21: all groups are designed in one Java run.

## 5. `pack`: make, new, regenerate, diff, catalogue, i18n, check, about-check, bundle, verify, deploy, rollback, publish, keygen, install

### `pack make`: everything in one folder

`pack make` is `pack new` + `data ingest` + `pack check` + `pack bundle` + `pack verify` in one run, writing a single
folder (the tree and the options are in [Quickest path](#quickest-path-pack-make) at the top). It adds no logic of its own to
those tools (`tools/packmake.py` only chooses the key and the date, lays the folder out and writes `README.txt`,
`config/drishti-site.yaml`, `run-server.sh`, `run-server.ps1` and `MANIFEST.json`).

```text
$ python3 tools/drishti.py pack make data/jsonl --kind trade --match productType --name my-bank
read 750 trade document(s)
key:   tradeId  (named <kind>Id, present and unique in all 750 documents)
date:  businessDate  (date-valued, named like a business date, 1 distinct date(s))
...
== pack verify
  checksums ok / schema ok / server ok / sutra ok
verified

pack make: /work/build/my-bank-1.0.0
  126 Sutra group(s) for 750 trade document(s), 1 date(s) in data/delta; pack check passed, pack verify passed
next: read /work/build/my-bank-1.0.0/README.txt, then  DRISHTI_JAR=<exec jar> DRISHTI_PORT=18480 /work/build/my-bank-1.0.0/run-server.sh
```

Try it without touching anything of yours: `DRISHTI_JAR=... DRISHTI_PORT=18974 build/my-bank-1.0.0/run-server.sh`, then
`curl 'http://localhost:18974/api/v1/views/trade/<an id>?asOf=<a date>'`. Documents of another day are added with `data ingest`
(default mode `overwrite-dates`) into the same `data/delta`. Exit codes: 0 made and both checks passed, 1 made but a check failed
(the folder is still written), 2 usage (no unique key, a missing field, `--out` exists without `--force`).

### `pack new`: a complete pack from JSON Lines

Writes `packs/<name>/` (or `--out`): `pack.yaml` (kinds, mnemonics, columns, and with a date a connector, routes and the
optional `ingest:` block), `samples/`, one Sutra per kind or `--match` group, `tests/<sutra>/` with `expect.yaml`,
`config/about.yaml` (kind titles and glossary entries with `TODO` text for you to fill in) and a `README.md`; then it runs
`sutra lint` and `sutra test` on it. With a dated store option it also **writes your documents into that store**, so the pack
has data on the day it is created.

**`drishti.py pack new --help`**

```text
usage: drishti.py pack new [-h] --name NAME [--title TITLE]
                           [--description DESCRIPTION] [--mnemonic MNEMONIC]
                           [--out OUT] [--force]
                           [--catalog-samples CATALOG_SAMPLES] [--lake LAKE]
                           [--store {delta,files}] [--files-root FILES_ROOT]
                           [--kind KIND] [--key KEY] [--date DATE]
                           [--match MATCH] [--skip-missing]
                           [--samples SAMPLES] [--min-docs MIN_DOCS]
                           [--priority PRIORITY] [--fallback]
                           [--name-prefix NAME_PREFIX] [--jar JAR]
                           [--java JAVA] [-r] [--server SERVER]
                           [--token-file TOKEN_FILE] [--user USER]
                           [--timeout TIMEOUT] [--load]
                           inputs [inputs ...]

pack new: create a complete pack from JSON Lines (kinds, Sutras, samples, tests, about text, a lake or files store)

positional arguments:
  inputs                folders of *.jsonl and/or single .jsonl files

options:
  -h, --help            show this help message and exit
  --name NAME           pack name (letters, digits, '-')
  --title TITLE         pack title (default: the name)
  --description DESCRIPTION
                        pack description
  --mnemonic MNEMONIC   kind=MNEMONIC,kind2=M2 (default: initials of the kind,
                        upper case)
  --out OUT             default packs/<name>
  --force               overwrite an existing --out folder
  --catalog-samples CATALOG_SAMPLES
                        documents per kind kept in samples/ (default 25)
  --lake LAKE           also write the documents into a Delta lake here (needs
                        --date); same as --store delta --lake
  --store {delta,files}
                        the dated store the pack reads: delta (default) or the
                        File connector's files
  --files-root FILES_ROOT
                        with --store files: also write the documents as
                        <root>/<pack>/<date>/<kind>.jsonl
  --kind KIND           kind for every document (default: the file stem, or
                        the envelope's kind)
  --key KEY             id field (dotted): FIELD, or kind=FIELD,kind2=FIELD2;
                        becomes the title id
  --date DATE           business-date field (dotted): FIELD or kind=FIELD,...;
                        kept out of the match, samples span dates
  --match MATCH         fields whose values split the kind: a,b or
                        kind=a,b;kind2=c ; one Sutra per combination
  --skip-missing        leave out documents lacking a match field (default: a
                        `== null` group)
  --samples SAMPLES     samples per group (default 5)
  --min-docs MIN_DOCS   skip groups with fewer documents (default 1)
  --priority PRIORITY   priority of a match-field Sutra (default 10)
  --fallback            also write <kind>-default (no where, priority 1)
  --name-prefix NAME_PREFIX
                        prefix of every Sutra name
  --jar JAR             the drishti-server exec jar (default: newest under
                        drishti-server/target)
  --java JAVA           java binary (default: JAVA_HOME, then JDK 21, then
                        java)
  -r, --recursive       read *.jsonl in subfolders too
  --load                afterwards ask the running server to load the pack
                        (administrator; the server restarts in place)

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)

examples:
  drishti.py pack new data/jsonl --name my-bank --key trade=tradeId --date trade=businessDate --lake data/delta
  drishti.py pack new data/jsonl --name my-bank --date businessDate --store files --files-root data/files --load --server http://localhost:18977
```

**The dated store.** `--date FIELD` names each document's business-date field. Where the documents go is `--store`:

| `--store` | Writes | The pack declares | Needs |
|---|---|---|---|
| `delta` (default) | a Delta lake `<lake>/<pack>/<kind>/` partitioned by `business_date`, with `--lake DIR` | a `delta` connector (`DRISHTI_DELTA_ROOT`) | `deltalake`, `pyarrow` |
| `files` | `<files-root>/<pack>/<date>/<kind>.jsonl` with `--files-root DIR` | a `file` connector (`DRISHTI_FILES_ROOT`) | nothing extra |

Serve it by starting the server with `DRISHTI_DELTA_ROOT=<lake>` or `DRISHTI_FILES_ROOT=<root>`. The pack is read from the
store for every date you ingested; the business-date picker and history work.

```bash
python3 tools/drishti.py pack new data/jsonl --name jsonl-demo --title "JSONL demo" \
    --key trade=tradeId,counterparty=counterpartyId --date businessDate --match trade=productType \
    --store files --files-root data/files --out packs/jsonl-demo
```

```text
64 documents, 2 kind(s), 4 group(s)
drafting 4 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
counterparty    (all)                                             4       4  counterparty-default
trade           productType=bond                                 17       5  trade-bond
trade           productType=fx-forward                           24       5  trade-fx-forward
trade           productType=swap                                 19       5  trade-swap
  counterparty 2026-10-02: 4 rows -> data/files/jsonl-demo/2026-10-02/counterparty.jsonl
  trade 2026-10-01: 30 rows -> data/files/jsonl-demo/2026-10-01/trade.jsonl
  trade 2026-10-02: 30 rows -> data/files/jsonl-demo/2026-10-02/trade.jsonl
kind                business date        rows
counterparty        2026-10-02              4
trade               2026-10-01             30
trade               2026-10-02             30
total rows: 64
Dated store (files): 64 rows written to data/files (domain jsonl-demo); 0 document(s) without a date or id skipped
Dated store (files): 64 rows written to data/files (domain jsonl-demo); 0 document(s) without a date or id skipped
pack written to packs/jsonl-demo
...
ok      packs/jsonl-demo/sutras/trade/trade-bond.v1.sutra.yaml
ok      packs/jsonl-demo/sutras/trade/trade-fx-forward.v1.sutra.yaml
```

The same, into a **Delta lake** (needs `deltalake` and `pyarrow`, so run it with `uv run --with ...`):

```bash
uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py pack new data/jsonl --name jsonl-lake \
    --key trade=tradeId,counterparty=counterpartyId --date businessDate --match trade=productType --lake data/delta
```

```text
64 documents, 2 kind(s), 4 group(s)
drafting 4 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
counterparty    (all)                                             4       4  counterparty-default
trade           productType=bond                                 17       5  trade-bond
trade           productType=fx-forward                           24       5  trade-fx-forward
trade           productType=swap                                 19       5  trade-swap
  counterparty 2026-10-02: 4 rows
  trade 2026-10-01: 30 rows
  trade 2026-10-02: 30 rows
kind                business date        rows
counterparty        2026-10-02              4
trade               2026-10-01             30
trade               2026-10-02             30
...
     trade-bond: help coverage 1/5 (20%)
ok   packs/jsonl-lake/sutras/trade/trade-bond.v1.sutra.yaml (5 samples)
     trade-fx-forward: help coverage 1/5 (20%)
ok   packs/jsonl-lake/sutras/trade/trade-fx-forward.v1.sutra.yaml (5 samples)
     trade-swap: help coverage 1/5 (20%)
ok   packs/jsonl-lake/sutras/trade/trade-swap.v1.sutra.yaml (5 samples)
```

**`--load --server URL`** asks the running server to load the pack straight away (it calls `POST /api/v1/admin/packs/<name>/load`;
you must be an administrator, and the server restarts in place). The pack has to be where the server reads packs from: its
`drishti.packs.dir` (default `./packs`) or its installed-dir. If `--out` is somewhere else the command says so.

```bash
python3 tools/drishti.py pack new data/jsonl --name jsonl-demo --date businessDate --store files \
    --files-root data/files --load --server http://localhost:18977
```

```text
...
load jsonl-demo: The server restarts in place now; live views reconnect by themselves.
```

**The `ingest:` block.** `pack new` records which field is each kind's id and which is its business date in `pack.yaml`:

```yaml
ingest:
  trade:        { key: tradeId,        date: businessDate }
  counterparty: { key: counterpartyId, date: businessDate }
```

It is an optional **tooling key**: `data ingest --pack` and `pack check` read it, the server ignores it (it is listed in the
key table of [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md)). Delete it and the pack loads exactly the same.

### `pack check`: lint + test + help coverage, for CI

For each pack: `sutra lint` (with `--strict`: help warnings fail), then `sutra test` (renders every sample, checks `expect.yaml`
and reports help coverage per Sutra), and a check that the `ingest:` block names real kinds with a `key` and a `date`.

**`drishti.py pack check --help`**

```text
usage: drishti.py pack check [-h] [--jar JAR] [--java JAVA] [--json]
                             [--strict] [--junit DIR] [--tail TAIL]
                             PACK [PACK ...]

pack check: sutra lint + sutra test (with help coverage) on packs, for CI

positional arguments:
  PACK         pack folder(s)

options:
  -h, --help   show this help message and exit
  --json       machine-readable output
  --strict     help warnings (DRS-2045 to 2047: a shown field without glossary
               text) fail the run
  --junit DIR  write DIR/<pack>-lint.xml and DIR/<pack>-test.xml
  --tail TAIL  lines of each step's output to show (default 8; 0 none)

the Java tool:
  --jar JAR    the drishti-server exec jar (default: DRISHTI_JAR, then the
               newest drishti-server/target/drishti-server-*-exec.jar)
  --java JAVA  the java binary (default: JAVA_HOME, then JDK 21, then java on
               the PATH)

examples:
  drishti.py pack check packs/my-bank --strict --junit build/reports
  drishti.py pack check packs/a packs/b --json
exit: 0 all passed, 1 any failure, 2 usage
```

```bash
python3 tools/drishti.py pack check packs/jsonl-demo --junit build/reports
```

```text
== jsonl-demo: sutra lint: exit 0
packs/jsonl-demo/sutras/trade/trade-fx-forward.v1.sutra.yaml:1 warning DRS-2047 field 'notional' is shown but has no glossary entry (kinds.trade.gl...
...
packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml:1 warning DRS-2047 field 'mtm' is shown but has no glossary entry (kinds.trade.glossary in c...
packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml:1 warning DRS-2047 field 'businessDate' is shown but has no glossary entry (kinds.trade.glos...
packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml:1 warning DRS-2047 field 'productType' is shown but has no glossary entry (kinds.trade.gloss...
== jsonl-demo: sutra test: exit 0
     counterparty-default: help coverage 2/4 (50%)
ok   packs/jsonl-demo/sutras/counterparty/counterparty-default.v1.sutra.yaml (4 samples)
     trade-bond: help coverage 1/5 (20%)
ok   packs/jsonl-demo/sutras/trade/trade-bond.v1.sutra.yaml (5 samples)
     trade-fx-forward: help coverage 1/5 (20%)
ok   packs/jsonl-demo/sutras/trade/trade-fx-forward.v1.sutra.yaml (5 samples)
     trade-swap: help coverage 1/5 (20%)
ok   packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml (5 samples)
```

The exit code is 0 here; with `--strict` the generated pack's unexplained fields fail the run:

```bash
python3 tools/drishti.py pack check packs/jsonl-demo --strict --tail 3
```

```text
== jsonl-demo: sutra lint --strict: exit 1
packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml:1 warning DRS-2047 field 'mtm' is shown but has no glossary entry (kinds.trade.glossary in c...
packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml:1 warning DRS-2047 field 'businessDate' is shown but has no glossary entry (kinds.trade.glos...
packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml:1 warning DRS-2047 field 'productType' is shown but has no glossary entry (kinds.trade.gloss...
== jsonl-demo: sutra test: exit 0
ok   packs/jsonl-demo/sutras/trade/trade-fx-forward.v1.sutra.yaml (5 samples)
     trade-swap: help coverage 1/5 (20%)
ok   packs/jsonl-demo/sutras/trade/trade-swap.v1.sutra.yaml (5 samples)
```

`--junit DIR` writes `DIR/<pack>-lint.xml` and `DIR/<pack>-test.xml`, which any CI shows as test results.

### `pack about-check`: fields shown without help text

Lists, per Sutra, the fields it shows that `config/about.yaml` does not explain (the DRS-2047 warnings of `sutra lint`), and the
`TODO` placeholders `pack new` left in `config/about.yaml`. Exit 1 when a field is unexplained; with `--strict` a `TODO` counts too.

**`drishti.py pack about-check --help`**

```text
usage: drishti.py pack about-check [-h] [--jar JAR] [--java JAVA] [--json]
                                   [--strict]
                                   PACK [PACK ...]

pack about-check: fields the Sutras show that config/about.yaml does not explain (DRS-2047), and TODO placeholders

positional arguments:
  PACK

options:
  -h, --help   show this help message and exit
  --json       machine-readable output
  --strict     placeholder 'TODO' texts in config/about.yaml count as gaps too

the Java tool:
  --jar JAR    the drishti-server exec jar (default: DRISHTI_JAR, then the
               newest drishti-server/target/drishti-server-*-exec.jar)
  --java JAVA  the java binary (default: JAVA_HOME, then JDK 21, then java on
               the PATH)

Runs `sutra lint` and lists its help warnings per Sutra. Exit 1 when any field is unexplained (with --strict, TODO placeholders count too).
```

```bash
python3 tools/drishti.py pack about-check packs/jsonl-demo
```

```text
== jsonl-demo: 14 field(s) shown without glossary text in 4 Sutra(s); 11 placeholder TODO text(s) in config/about.yaml
  counterparty-default.v1: businessDate, rating
  trade-bond.v1: notional, mtm, businessDate, productType
  trade-fx-forward.v1: notional, mtm, businessDate, productType
  trade-swap.v1: notional, mtm, businessDate, productType
  TODO: counterparty, counterparty.businessDate, counterparty.name, counterparty.rating, counterparty.country, trade, trade.notional, trade.currenc...
14 gap(s): add glossary entries under kinds.<kind>.glossary (or vocabulary) in config/about.yaml; see PACK_DEVELOPER_GUIDE.
```

Fix each by adding the field under `kinds.<kind>.glossary` (or a pack-wide `vocabulary` entry) in `config/about.yaml`; the
format is in [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md).

### `pack regenerate`: new data, new Sutras, none of your edits lost

A generated pack is a starting point you then edit (About text, labels, priorities). When the data grows (a new product type, a new
field), `pack regenerate` generates again and **three-way merges** the result into your pack. `pack new` and `pack make` store what
they generated in `<pack>/.generated/` (a `.base` copy of every generated file and the options used); that copy is the *base*, your
pack is *ours*, the fresh generation is *theirs* (`tools/packregen.py`). Commit `.generated/` with the pack; the bundle leaves it out.

| Situation | What happens |
|---|---|
| only the new generation changed a file | `refresh`: the file is replaced |
| only you changed it | `keep`: your edit stays |
| a group or file that is new | `add` |
| no longer generated, you never edited it | `remove`; edited: `stale`, kept and listed |
| both changed, in different places | `merge`: a line-level three-way merge (difflib), written |
| both changed the same lines | `conflict`: both texts in the file between `<<<<<<< yours`, `=======` and `>>>>>>> regenerated` markers; listed; **exit 1** |
| you deleted a file the generator still makes | `deleted`: it stays deleted |
| no `.generated/` (a pack made before this) | nothing is merged: differing files are `no-baseline` (kept, exit 1), new files are added |

```text
$ python3 tools/drishti.py pack new few.jsonl --name mb --kind trade --match productType --key tradeId --out mb
...
$ python3 tools/drishti.py pack regenerate mb trade.jsonl --dry-run
pack regenerate --dry-run: mb
  add         sutras/trade/trade-abs.v1.sutra.yaml
  add         sutras/trade/trade-amort-bond.v1.sutra.yaml
  add         sutras/trade/trade-autocallable.v1.sutra.yaml
  add         ... and 826 more
  refresh     README.md
  refresh     pack.yaml
  refresh     samples/catalog.json
  merge       config/about.yaml   (your edit and the new text merged cleanly)
  keep        sutras/trade/trade-accreting-swap.v1.sutra.yaml   (your edit kept; the generated text did not change)
  remove      samples/trade/MX-20000001.json   (no longer generated and you never edited it)
  remove      ... and 23 more
summary: 829 add, 1 keep, 1 merge, 3 refresh, 24 remove; 70 unchanged
(exit 0)
```

Here two files had been edited by hand (a strip label in `trade-accreting-swap`, the About sentence): the label edit is kept, the
About edit is merged with the regenerated skeleton. Without `--dry-run` the same plan is written, then `.generated/` is replaced by
what was just generated (not by the merged result, so the next run still sees your edits as yours). A real conflict, here the
generator now writes `priority: 20` on a line you changed to 15:

```text
$ python3 tools/drishti.py pack regenerate mb trade.jsonl --priority 20
pack regenerate: mb
  refresh     sutras/trade/trade-abs.v1.sutra.yaml
  ...
  conflict    sutras/trade/trade-accreting-swap.v1.sutra.yaml   (1 conflict(s): markers written, resolve them by hand)
  keep        config/about.yaml   (your edit kept; the generated text did not change)
summary: 1 conflict, 1 keep, 124 refresh; 778 unchanged
attention: 1 file(s) need you (search for '<<<<<<<'), then  drishti.py pack check mb
(exit 1)

$ grep -n -A4 '<<<<<<<' mb/sutras/trade/trade-accreting-swap.v1.sutra.yaml
<<<<<<< yours (the pack)
match: { kind: trade, where: "$.productType == 'ACCRETING_SWAP'", priority: 15 }
=======
match: { kind: trade, where: "$.productType == 'ACCRETING_SWAP'", priority: 20 }
>>>>>>> regenerated from the new data
```

Options not given on the command line (`--key`, `--date`, `--match`, `--priority`, `--samples`, `--version`...) are taken from the
ones the pack was generated with. The command takes the same options as `pack new` (it writes no data: use `data ingest` for the
lake) plus `--dry-run` and `--json`. It needs the jar like `pack new`, because it lints and tests the regenerated pack in a scratch
folder first. Exit codes: 0 merged cleanly, 1 conflicts (or `no-baseline` files), 2 usage.

### `pack diff`: what changed between two versions, and what breaks

```text
$ python3 tools/drishti.py pack diff dist/my-bank-1.0.0.tar.gz packs/my-bank
pack diff: my-bank 1.0.0 -> my-bank 1.1.0

BREAKING (1)
  mnemonic renamed           TRA   TRA -> TRD for kind trade (typed commands and saved links use it)

SELECTION (2)
  sutra removed              trade-abs   sutras/trade/trade-abs.v1.sutra.yaml: its documents fall to another Sutra (match $.productType == 'ABS')
  screen selection changes   trade-accreting-swap   priority 10 -> 20

CHANGE (3)
  version                    my-bank   1.0.0 -> 1.1.0
  sutra changed              trade-accreting-swap   content changed
  sutra changed              trade-amort-bond   content changed

summary: 1 breaking, 2 selection, 3 change  (fail-on breaking: FAIL)
```

`OLD` and `NEW` are pack folders, `pack make` output folders or bundles (`.tar.gz`). Findings have four levels: **breaking** (a kind or
a mnemonic removed or renamed: monitors, workspaces and alerts name them), **selection** (a Sutra added or removed, its match `where`,
`kind` or `priority` changed: other documents get other screens), **layout** (`connectors`, `routes`, `ingest`, `columns` of
`pack.yaml`: the data layout) and **change** (Sutra content, About text, glossary, panel notes, version). `--unified` adds the unified
diff of each changed Sutra; `--fail-on breaking|any|none` (default `breaking`) decides the exit code (1 when a finding of that level
exists); `--json` prints `{counts, findings: [{level, what, name, detail}], failed}`. Use it in CI before `pack deploy`.

### `pack catalogue`: a readable catalogue for business review

```text
$ python3 tools/drishti.py pack catalogue packs/my-bank --out build/catalogue --format html --shots
pack catalogue: 1 kind(s), 125 Sutra(s), 125 preview(s) -> build/catalogue/index.html
```

The page (or `catalogue.md` with the default `--format md`) lists, per kind, the About sentence and mnemonic, the glossary (term,
meaning, unit, sign, notes, values) and the fields the Sutras show that no entry explains yet; then every Sutra with what it shows
(description, key figures, panels with their kind and title) and the rule that selects it. The start of the Markdown:

```text
## Trade (`trade`, mnemonic TRD)

A trade booked in the front office, e.g. ${$.tradeId}.

### Fields and glossary

| Field | Term | Meaning | Unit | Sign, notes |
|---|---|---|---|---|
| assetClass | assetClass | TODO: what this field means. |  |  |
...
#### trade-accreting-swap

- **Selected when:** `$.productType == 'ACCRETING_SWAP'`, priority 10
- **Key figures:** Notional, MTM, DV01, Vega, NPV, Clean PV
- **Panels:** Details (kv), Sensitivities (area), Schedule (ladder), Pnl history (line)
```

`--shots` runs `sutra preview` over the pack (needs the jar) and links each Sutra's HTML snapshots from `previews/`. About text not yet
written shows as its `TODO` placeholder, so the catalogue doubles as a review checklist.

### `pack i18n`: translate the About text through a spreadsheet

```text
$ python3 tools/drishti.py pack i18n export packs/my-bank --lang fr --out build/fr.csv
pack i18n export: 40 texts of my-bank -> build/fr.csv  (0 already translated, 40 to do)
next: fill the 'translation' column, then  drishti.py pack i18n import packs/my-bank build/fr.csv --lang fr

$ python3 tools/drishti.py pack i18n import packs/my-bank build/fr.csv --lang fr
pack i18n import: 2 of 40 texts -> packs/my-bank/config/about.fr.yaml
  missing (shown in English): 38
    kinds/trade/glossary/assetClass/term
    ...
  EXTRA keys (not in about.yaml, ignored): 1
    kinds/ghost/title
(exit 1)
```

`export` writes a CSV with the columns `key`, `source` (the English text) and `translation` (prefilled from an existing
`config/about.<lang>.yaml`). Keys are `/`-separated paths: `kinds/<kind>/title`, `.../about`, `.../glossary/<field>/<term|means|unit|sign|note>`,
`.../glossary/<field>/values/<value>`, `.../panels/<panel>/about` and `vocabulary/<term>/...`. Keep every `${...}` expression as it is. `import`
writes `config/about.<lang>.yaml`, the overlay the server merges over `about.yaml` key by key (the same kind of file the market-risk pack ships
as `about.fr.yaml`); what is written is read back and must give exactly the imported keys. It reports **missing** keys (they show in
English; an error only with `--strict`), **extra** keys (not in `about.yaml`: an error), a **changed source** column (the English text
changed since the export) and translations whose `${...}` expressions differ from the English (an error). `--dry-run` writes nothing.
Exit codes: 0 ok, 1 problems, 2 usage (bad `--lang`, no `about.yaml`).

### `pack bundle`, `pack verify`, `pack deploy`, `pack rollback`: artifacts you ship by copying

The offline path: no server API and no token. Full guide with real output, promotion, CI, Docker and Windows:
[OPERATIONALISING.md](OPERATIONALISING.md).

```bash
drishti.py pack bundle packs/finance --out dist                 # finance-1.0.0.tar.gz + .sha256 + .manifest.json (runs pack check first)
drishti.py pack verify dist/finance-1.0.0.tar.gz                # sha256, manifest checksums, schema, server version, sutra lint + test
drishti.py pack deploy dist/finance-1.0.0.tar.gz --to /opt/drishti/packs --backup /opt/drishti/backups [--dry-run]
drishti.py pack rollback finance --to /opt/drishti/packs --backup /opt/drishti/backups [--version 1.0.0]
```

`pack verify` also takes a folder (drift check of a deployed pack) and `--no-sutra` (no Java), `--server-version V`, `--json`.
`pack deploy` copies to a temporary sibling, swaps by rename and keeps the previous version in the backup folder; it never
touches a running server. `pack verify --registry R --publisher P --public-key K` is still the registry check below.
Exit codes: 0 ok, 1 not verified or refused, 2 usage.

### `pack publish`, `pack keygen`, `pack verify --registry`: the registry (tools/packreg)

A registry is a folder (or a web location) of signed pack archives. `keygen` makes an Ed25519 key, `publish` zips a pack,
signs it and adds it, `verify` re-checks a registry against a publisher's public key.

**`drishti.py pack publish --help`**

```text
usage: drishti.py pack publish [-h] [--json] --registry REGISTRY --key KEY
                               --publisher PUBLISHER
                               pack

pack publish: sign a pack and add it to a registry folder (tools/packreg)

positional arguments:
  pack

options:
  -h, --help            show this help message and exit
  --json                machine-readable output
  --registry REGISTRY
  --key KEY             the private key (make one with `pack keygen`)
  --publisher PUBLISHER

example:
  drishti.py pack publish packs/my-bank --registry /srv/registry --key keys/me.pem --publisher me
```

```bash
python3 tools/drishti.py pack keygen --out keys/me.pem
python3 tools/drishti.py pack publish packs/jsonl-demo --registry registry --key keys/me.pem --publisher me
```

```text
private key: keys/me.pem (keep it secret; it signs packs)
public key:  MCowBQYDK2VwAyEAk8tNeCMeKT1JRMWgC2iMgT2zpmrSUfP9ReTRF5PFL6k=
servers trust it with:  drishti.packs.registry.trusted-keys.<publisher>: <public key>
```

```text
published jsonl-demo 1.0.0 (18296 bytes, sha256 59f5e76fd4ea…) signed by me
```

A server trusts a publisher by being configured with the public key
(`drishti.packs.registry.trusted-keys.<publisher>`); see [PACKS.md](PACKS.md).

### `pack install`: install from the registry on a running server

`pack install --list` shows what the server's registry offers; `pack install NAME VERSION` installs (or upgrades to) that
version after the server's checks, then loads it. Administrator only; needs a signed token (not a personal one).

**`drishti.py pack install --help`**

```text
usage: drishti.py pack install [-h] [--server SERVER]
                               [--token-file TOKEN_FILE] [--user USER]
                               [--timeout TIMEOUT] [--json] [--list]
                               [name] [version]

pack install: install a registry pack on the running server (administrator), or --list the registry

positional arguments:
  name
  version

options:
  -h, --help            show this help message and exit
  --json                machine-readable output
  --list                show the registry's packs

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)

examples:
  drishti.py pack install --list
  drishti.py pack install my-bank 1.2.0
```

```text
registry:   (not configured)
```

(Here the scratch server has no registry configured; with one, each row shows the version, whether the publisher is trusted, and
the installed and loaded versions.)

## 6. `data`: profile, ingest and load

### `data profile`: what is in your JSON Lines

Before `pack make`, look at the data. `data profile` (`tools/dataprofile.py`) reads the files once, a document at a time, and reports
per kind (the file name, or `--kind`): the document count; every field (dotted path; `[]` marks array elements, e.g.
`sensitivities[].bucket`) with its types, coverage, distinct values and examples; and the candidates for the three choices `pack make`
needs. Memory is bounded: `--max-distinct` (default 10,000) values are tracked per field; beyond it the distinct count reads
`>=10000` and the field cannot be a key or a match column.

```text
$ python3 tools/drishti.py data profile data/jsonl/trade.jsonl
data profile: 1 file(s), 750 document(s), 1 kind(s)

== trade: 750 document(s), 402 field(s)
  field                                 type            cover  distinct  examples
  tradeId                               string           100%       750  MX-20000001, MX-20000002, MX-20000003
  sourceSystem                          string           100%         6  Murex, Wall Street Systems, Calypso
  productType                           string           100%       125  IRS_FIXFLOAT, OIS, BASIS_SWAP
  tradeDate                             string           100%       385  2025-07-25, 2025-02-06, 2026-09-18
  notional                              number           100%       247  242000000.0, 110000000.0, 204000000.0
  counterparty.id                       string           100%        18  CP-MERIDIAN-RE, CP-HALCYON, CP-AURORA
  terms.fixedRate                       number             7%        54  0.040829, 0.019194, 0.015994
  sensitivities[].bucket                string           100%        10  3M, 6M, 1Y
  legs[].leg                            integer           22%         2  1, 2
  ...
  key candidates (present and unique everywhere):
    tradeId                      exact     present in all 750 documents, 750 different values
    sourceTradeId                exact     present in all 750 documents, 750 different values
  date candidates (ISO dates in at least 95% of the documents):
    tradeDate                    385 date(s) 2024-04-12 .. 2026-09-25, coverage 100%
    effectiveDate                328 date(s) 2024-04-15 .. 2026-09-28, coverage 100%
  match candidates (split the kind into screens; score 0..1):
    assetClass                   1.000  coverage 100%, 10 values over 750 documents (average 75.0 per value, evenness 0.94; largest group Rates = 156); name suggests a category
    family                       1.000  coverage 100%, 13 values over 750 documents (average 57.7 per value, evenness 0.88; largest group swap = 144); name suggests a category
    productType                  0.958  coverage 100%, 125 values over 750 documents (average 6.0 per value, evenness 1.00; largest group IRS_FIXFLOAT = 6); name suggests a category
  suggested:
    python3 tools/drishti.py pack make data/jsonl/trade.jsonl --kind trade --match assetClass --key tradeId --date tradeDate --name trade-pack
```

A **key** is a scalar present in every document with a different value in each (`exact` while the values fit in `--max-distinct`,
`probable` beyond; id-like names first). A **date** is a field whose every value is an ISO date, present in at least 95% of the
documents. A **match** column splits the kind into screens; the score (0 to 1) is `coverage x (1 - distinct/documents) x min(1,
average group size/5) x min(1, distinct/8) x (0.5 + 0.5 x evenness)`, times 1.15 when the name ends in type, class, category, kind,
family, product, segment, region or status, times 0.9 per nesting level; each line says why. It is a ranking, not a decision: here
`assetClass` gives ten broad screens, `productType` gives 125 specific ones, and you choose. `--json` prints every field and
candidate; exit 0, or 2 when nothing could be read.

### `data ingest`: your JSON Lines into a lake or files

`data ingest` (`tools/ingest_jsonl.py`) writes JSON Lines documents into the dated store a pack reads. Inputs are folders of
`*.jsonl` or single files, repeatable with `--from`. With `--pack` the domain, the columns promoted in the lake and the key and
date fields come from the pack's `pack.yaml` (including the `ingest:` block); without it give `--domain`, `--date` and
`--columns`.

**`drishti.py data ingest --help`**

```text
usage: drishti.py data ingest [-h] --from SRC [-r] [--pack PACK]
                              [--domain DOMAIN] [--kind KIND] [--key KEY]
                              [--date DATE] [--columns COLUMNS] [--lake LAKE]
                              [--store {delta,files}] [--root ROOT]
                              [--mode {overwrite-dates,append,replace}]
                              [--dry-run] [--batch-rows BATCH_ROWS]

data ingest: write JSON Lines documents into a Delta lake or the File connector's layout (tools/ingest_jsonl.py)

options:
  -h, --help            show this help message and exit
  --from SRC            a folder of *.jsonl or one .jsonl file; repeatable
  -r, --recursive
  --pack PACK           pack folder: domain, layouts, key and date fields come
                        from its pack.yaml
  --domain DOMAIN       lake domain (without --pack)
  --kind KIND           kind for every document (default: file stem or
                        envelope kind)
  --key KEY             id field: FIELD or kind=FIELD,... (default id)
  --date DATE           business-date field: FIELD or kind=FIELD,...
  --columns COLUMNS     promoted columns without --pack: a,b,c
  --lake LAKE           the Delta root (DRISHTI_DELTA_ROOT) for --store delta
  --store {delta,files}
                        delta (default) or the File connector's layout
  --root ROOT           the files root (DRISHTI_FILES_ROOT) for --store files
  --mode {overwrite-dates,append,replace}
  --dry-run
  --batch-rows BATCH_ROWS

examples:
  drishti.py data ingest --from data/new --pack packs/my-bank --lake data/delta
  drishti.py data ingest --from day1.jsonl --from day2.jsonl --pack packs/my-bank --store files --root data/files --dry-run
```

`--mode overwrite-dates` (the default) replaces only the dates in your input, so re-running a day is safe; `append` adds;
`replace` rebuilds the kind. Documents without a date or id are counted and left out, never silently dropped.

```bash
# look first
python3 tools/drishti.py data ingest --from data/jsonl --pack packs/jsonl-demo --store files --root data/files2 --dry-run
```

```text
kind                business date        rows   (dry run)
counterparty        2026-10-02              4
trade               2026-10-01             30
trade               2026-10-02             30
total rows: 64
```

```bash
python3 tools/drishti.py data ingest --from data/jsonl --pack packs/jsonl-demo --store files --root data/files2
```

```text
  counterparty 2026-10-02: 4 rows -> data/files2/jsonl-demo/2026-10-02/counterparty.jsonl
  trade 2026-10-01: 30 rows -> data/files2/jsonl-demo/2026-10-01/trade.jsonl
  trade 2026-10-02: 30 rows -> data/files2/jsonl-demo/2026-10-02/trade.jsonl
kind                business date        rows
counterparty        2026-10-02              4
trade               2026-10-01             30
trade               2026-10-02             30
total rows: 64
```

For Delta (`--store delta --lake DIR`, the default store) run it with `uv run --with pyyaml --with deltalake --with pyarrow`.

### `data load`: the demo data, at three sizes

`data load --store S` runs `tools/load-S.sh`, which builds the **shipped banking packs' demo data** (1,791 sample documents
x 10 business days) in that store, and with `--trades N` a generated trade book of N trades a day for `--days` days. Stores:
`delta`, `files`, `postgres`, `duckdb`, `iceberg`, `mongodb`, `redis`, `aerospike`. The scripts run from the repository root, so
a relative root is relative to it. Options after the known ones go to the script unchanged. Everything about the scripts, the
stores and the sizes is in [DEMO_DATA.md](../connectors/DEMO_DATA.md).

**`drishti.py data load --help`**

```text
usage: drishti.py data load [-h]
                            --store {delta,files,postgres,duckdb,iceberg,mongodb,redis,aerospike}
                            [--trades TRADES] [--days DAYS] [--dry-run]
                            [target ...]

data load: load the demo data (1,791 sample documents x 10 days, optionally a trade book) into a store (tools/load-<store>.sh)

positional arguments:
  target                the store's root, URL or database (the script's
                        default when omitted; mongodb takes a URI and a
                        database)

options:
  -h, --help            show this help message and exit
  --store {delta,files,postgres,duckdb,iceberg,mongodb,redis,aerospike}
  --trades TRADES       also generate a trade book of this many trades a day
  --days DAYS           business days the book covers (script default 3)
  --dry-run             print the command instead of running it

examples:
  drishti.py data load --store delta
  drishti.py data load --store files --trades 10000 --days 3
  drishti.py data load --store postgres jdbc:postgresql://localhost:5433/drishti --trades 10000 --user u --password p
Other options are passed to the script unchanged. Keep local runs small (10,000 trades); see docs/connectors/DEMO_DATA.md.
```

| Size | Command | Use |
|---|---|---|
| small | `data load --store files` | the samples only, a few seconds |
| medium | `data load --store files --trades 10000 --days 3` | a realistic local demo |
| large | `data load --store delta --trades 1000000 --days 3` | the scale test; **not on a laptop**: about 1.6 GB a day of Delta |

Keep local runs to 10,000 trades; a million-trade run needs a machine sized for it. `--dry-run` prints the command:

```bash
python3 tools/drishti.py data load --store files data/demo-files --trades 1000 --days 2 --dry-run
```

```text
bash tools/load-files.sh data/demo-files --trades 1000 --days 2
```

A real small run (200 trades, one day) into a scratch folder; these scripts also refresh the shipped packs' sample files and the
repository's `data/` folder, which is not tracked:

```text
wrote 1791 documents into 5 packs' samples
feeds: data/feeds/fixing/SOFR-HISTORY.csv
jsonl: 17910 rows in data/banking.jsonl (tools/load-postgres.sh and tools/load-aerospike.sh load them)
files: wrote 17,910 rows into 460 files under data/demo-files in 0 s

200 of 200 trade-days written (1 s)
200 trade-days as JSON lines in 1 s
files: wrote 200 rows into 1 files under data/demo-files in 1 s
```

## 7. `server`: health and packs

**`drishti.py server health --help`**

```text
usage: drishti.py server health [-h] [--server SERVER]
                                [--token-file TOKEN_FILE] [--user USER]
                                [--timeout TIMEOUT] [--json]

server health: GET /api/v1/admin/health (exit 1 unless the status is OK)

options:
  -h, --help            show this help message and exit
  --json                machine-readable output

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)
```

```bash
python3 tools/drishti.py server health
```

```text
status DEGRADED  version 1.16.0  java 21.0.12.1  uptime 190s
packs 2  sources 4 (degraded 0, down 2)  packs with problems 1  failed to start 0
  file                        DOWN      DOWN: no directory /srv/drishti/run/data/feeds
  genomics-store              DOWN      DOWN: cannot reach /srv/drishti/delta/genomics (engine: native)
  demo                        UP        UP
  jsonl-demo-store            UP        UP
```

The exit code is 1 unless the status is `OK` (here two sources are down, so `DEGRADED`). `--json` prints the server's own
`GET /api/v1/admin/health` document.

`server packs list|load|unload|on|off` manage packs. **load** and **unload** edit the overlay file
(`data/packs/added.yaml`) after the server checks the pack the same way it does at start, then restart the server in place;
**on**/**off** switch a loaded pack for users without a restart. Unloading only works for packs an administrator loaded
(packs in `DRISHTI_PACKS` stay).

**`drishti.py server packs load --help`**

```text
usage: drishti.py server packs load [-h] [--server SERVER]
                                    [--token-file TOKEN_FILE] [--user USER]
                                    [--timeout TIMEOUT] [--json]
                                    name

load a pack that is on disk (the server restarts in place)

positional arguments:
  name                  the pack's name

options:
  -h, --help            show this help message and exit
  --json                machine-readable output

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)
```

```bash
python3 tools/drishti.py server packs list
```

```text
pack                  version  loaded  on   added  kinds
genomics              1.0.0    yes     yes         8
jsonl-demo            1.0.0    yes     yes  yes    2
```

```bash
python3 tools/drishti.py server packs load my-bank       # POST /api/v1/admin/packs/my-bank/load
python3 tools/drishti.py server packs off  my-bank       # PUT  /api/v1/admin/packs/my-bank  {"enabled": false}
```

## 8. `design`: the Screen Designer over REST

The same designs the **Build** workbench shows, from a terminal or a pipeline: create one from samples and/or a Sutra file,
check it against every sample, save edits made in your editor, send it for review, and let an approver decide. Everything the
screens do about review is in [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md#22-saving-and-proposing); the endpoints are in
[API_GUIDE.md](API_GUIDE.md).

| Command | Does | Endpoint |
|---|---|---|
| `design list` | your designs | `GET /api/v1/builder/designs` |
| `design create` | a design from `--samples` (files or folders of `.json`) and/or `--from-sutra FILE` or `--base name@version`; `--autodesign` drafts a Sutra from the samples | `POST /designs`, `/designs/{id}/samples`, `/designs/{id}/autodesign` |
| `design get ID` | summary; `--yaml` prints only the Sutra; `-o FILE` writes it | `GET /designs/{id}` |
| `design save ID FILE` | replaces the Sutra with a file (a new revision) | `PATCH /designs/{id}` |
| `design check ID` | runs it against every sample; exit 1 on any problem | `POST /designs/{id}/check` |
| `design autodesign ID` | drafts a Sutra from the samples | `POST /designs/{id}/autodesign` |
| `design propose ID` | sends it for review (governance on), or saves it (off) | `POST /designs/{id}/propose` |
| `design proposals` | proposals, `--status pending` | `GET /api/v1/sutras/proposals` |
| `design approve|reject PROPOSAL` | decides it (`--comment`; a rejection needs one) | `POST /api/v1/sutras/proposals/{id}/approve|reject` |
| `design export ID -o f.zip` | the design as a pack-fragment zip | `GET /designs/{id}/export` |
| `design import f.zip` | zip of a pack folder (or Sutras, tests, samples) as designs | `POST /designs/import` |
| `design bind [ID]` | with no id: is file binding on; with `--file F` / `--sync` / `--save` / `--unbind` | `GET /designs/binding`, `POST /designs/{id}/bind|sync|save-file`, `DELETE .../bind` |
| `design delete ID` | deletes one | `DELETE /designs/{id}` |

**`drishti.py design create --help`**

```text
usage: drishti.py design create [-h] [--server SERVER]
                                [--token-file TOKEN_FILE] [--user USER]
                                [--timeout TIMEOUT] [--json] [--name NAME]
                                [--kind KIND] [--notes NOTES] [--base BASE]
                                [--from-sutra FILE]
                                [--samples PATH [PATH ...]] [--autodesign]

design create: a new design, from samples and/or a Sutra file

options:
  -h, --help            show this help message and exit
  --json                machine-readable output
  --name NAME
  --kind KIND
  --notes NOTES
  --base BASE           start from a live Sutra: name@version
  --from-sutra FILE     a .sutra.yaml to start from (the kind is read from it)
  --samples PATH [PATH ...]
                        JSON sample files or folders
  --autodesign          draft a Sutra from the samples afterwards

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)
```

**Create, check, hand over.** Against a scratch server on port 18977 (sign-in off, `DRISHTI_STUDIO_SAVE=true`):

```bash
export DRISHTI_SERVER=http://localhost:18977
python3 tools/drishti.py design create --name swap-demo --kind trade --samples packs/jsonl-demo/tests/trade-swap --autodesign
python3 tools/drishti.py design list
python3 tools/drishti.py design check f0bc597072b6
python3 tools/drishti.py design propose f0bc597072b6 --note "first cut"
python3 tools/drishti.py design proposals
python3 tools/drishti.py design approve P-000001 --comment "looks right"
```

```text
created f0bc597072b6  swap-demo  kind trade  status draft  rev 1
  samples: sample-1.json, sample-2.json, sample-3.json, sample-4.json, sample-5.json
f0bc597072b6  swap-demo                   trade         draft     rev 1   5 sample(s)
check of rev 1: all samples ok
proposed: proposal P-000001 for trade-auto v1 is pending; an approver decides it
P-000001  trade-auto v1  pending  by anonymous
approve: proposal P-000001: trade-auto v1 is approved
```

(`design check` exits 1 and lists each failing sample when something does not render.)

**`drishti.py design propose --help`**

```text
usage: drishti.py design propose [-h] [--server SERVER]
                                 [--token-file TOKEN_FILE] [--user USER]
                                 [--timeout TIMEOUT] [--json] [--note NOTE]
                                 id

design propose: send the design for review (or save it, when governance is off)

positional arguments:
  id                    the design's id (from `design list`)

options:
  -h, --help            show this help message and exit
  --json                machine-readable output
  --note NOTE           what changed, for the reviewer

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)
```

**`drishti.py design approve --help`**

```text
usage: drishti.py design approve [-h] [--server SERVER]
                                 [--token-file TOKEN_FILE] [--user USER]
                                 [--timeout TIMEOUT] [--json]
                                 [--comment COMMENT]
                                 proposal

design approve: approve a proposal (approver role)

positional arguments:
  proposal              the proposal id (from `design proposals` or `design
                        propose`)

options:
  -h, --help            show this help message and exit
  --json                machine-readable output
  --comment COMMENT     the reason (a rejection needs one)

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)
```

A rejection without a comment is refused by the server, and the refusal is what you see:

```text
$ python3 tools/drishti.py design reject P-000001
drishti: the server said 400 DRS-5001: DRS-5001 say why the proposal is rejected      (exit 1)
```

**Ship a design as a pack fragment** and take it somewhere else:

```bash
python3 tools/drishti.py design export f0bc597072b6 -o swap-fragment.zip
python3 tools/drishti.py design import swap-fragment.zip        # on another server, or into your own designs
```

**Work in your editor** (file binding): `design bind ID --file my.sutra.yaml` binds the design to a file in your server-side
development directory, `design bind ID --sync` reads the file (an editor save becomes a step of the design),
`design bind ID --save` writes the Sutra back. `design bind` with no id says whether binding is on:

```text
$ python3 tools/drishti.py design bind
binding off; development directory: dev-sutras/anonymous
```

**`drishti.py design bind --help`**

```text
usage: drishti.py design bind [-h] [--server SERVER] [--token-file TOKEN_FILE]
                              [--user USER] [--timeout TIMEOUT] [--json]
                              [--file FILE | --sync | --save | --unbind]
                              [id]

design bind: bind to, sync with or save to a file in your development directory

positional arguments:
  id                    the design's id (omit to show whether binding is on)

options:
  -h, --help            show this help message and exit
  --json                machine-readable output
  --file FILE           bind to this file in your development directory (an
                        existing file's text becomes the Sutra)
  --sync                read the bound file (an IDE edit becomes a step of the
                        design)
  --save                write the Sutra to the bound file
  --unbind

server connection:
  --server SERVER       the server's base URL (default: DRISHTI_SERVER, then
                        http://localhost:18480)
  --token-file TOKEN_FILE
                        a file holding the API token (default: the
                        DRISHTI_TOKEN environment variable); never printed
  --user USER           the user name to send when the server has sign-in off
                        (default: DRISHTI_USER)
  --timeout TIMEOUT     seconds to wait for the server (default 60)
```

## 9. `docs`: screenshots

`docs shots` regenerates the screenshots of the guides (Playwright, against a scratch server and console on their own ports,
never the usual ones): `--list` shows the pictures, `--guide NAME` and `--only FRAGMENT` narrow it.

**`drishti.py docs shots --help`**

```text
usage: drishti.py docs shots [-h] [--base BASE] [--guide GUIDE] [--only ONLY]
                             [--list]

docs shots: regenerate the guides' screenshots (Playwright, scratch ports; tools/docs/screenshots.py)

options:
  -h, --help     show this help message and exit
  --base BASE    the console URL (default the scratch console)
  --guide GUIDE  one guide only
  --only ONLY    comma list of picture-name fragments
  --list         list the pictures
```

## 10. Recipes

### 10.1 JSONL folder or file to a pack, a lake or files, a running server and a live view

You have JSON Lines: one file per kind (`trade.jsonl`, `counterparty.jsonl`), or a file mixing kinds with an envelope
`{"kind": "...", "doc": {...}}`; every document has an id and a business date.

```bash
# 1. look at what you have, then build the pack and put the documents in a dated store (files: nothing extra to install)
python3 tools/drishti.py pack new data/jsonl --name jsonl-demo --title "JSONL demo" \
    --key trade=tradeId,counterparty=counterpartyId --date businessDate --match trade=productType \
    --store files --files-root data/files

# 2. check it, and list the help text still to write
python3 tools/drishti.py pack check packs/jsonl-demo
python3 tools/drishti.py pack about-check packs/jsonl-demo

# 3. restart the server once with the store's root (DRISHTI_FILES_ROOT=$PWD/data/files, as in QUICKSTART or your IDE run
#    configuration), then load the pack into it without another restart of yours
python3 tools/drishti.py server packs load jsonl-demo --server http://localhost:18480
```

With a **Delta lake** instead: `--lake data/delta` in step 1 (run with `uv run --with pyyaml --with deltalake --with pyarrow`), and
start the server with `DRISHTI_DELTA_ROOT=$PWD/data/delta`.

4. Open the console, type `TRA TRD-0050` and press Enter (the mnemonic `pack new` chose is the kind's initials; see the pack's
   `README.md`). The view is read from your store. Pick the business date in the top bar: each date you ingested has its
   documents. On the scratch server of this guide the same check from a shell:

```bash
curl -s "http://localhost:18977/api/v1/views/trade/TRD-0050?asOf=2026-10-02" | head -c 160
```

```text
{"ref":{"kind":"trade","id":"TRD-0050"},"mnemonic":"TRA","title":{"pill":"Trade · fx-forward","id":"TRD-0050","with":{"text":"CP-003",...
```

`TRD-0050` is not among the pack's sample files, so this answer came **from the files store**. New documents tomorrow: run
`data ingest --from data/new --pack packs/jsonl-demo --store files --root data/files`; the default mode replaces only the dates
in the input, and the server picks the new files up on its next rescan.

**Three ways a pack becomes known** to a server (the third is section 10.4): start with `DRISHTI_PACKS=...,jsonl-demo` and a
restart; `server packs load jsonl-demo` (or **Admin → Packs → Load**; the server restarts in place and remembers it in
`data/packs/added.yaml`); install from a registry. See [PACKS.md#loading-a-pack-while-the-server-runs](PACKS.md#loading-a-pack-while-the-server-runs).

### 10.2 A CI pipeline: lint, test, JUnit

```bash
set -e
python3 tools/drishti.py pack check packs/my-bank --strict --junit build/reports       # exit 1 fails the job
python3 tools/drishti.py pack check packs/my-bank --json > build/pack-check.json       # for a dashboard
```

A GitHub Actions job (the jar is built once and reused):

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: 21 }
- run: ./mvnw -q -DskipTests -pl drishti-server -am package
- uses: astral-sh/setup-uv@v5
- run: uv run --with pyyaml python tools/drishti.py pack check packs/my-bank --strict --junit build/reports
- uses: actions/upload-artifact@v4
  if: always()
  with: { name: pack-reports, path: build/reports }
```

`--strict` also makes a field that is shown but unexplained a failure, so help text cannot rot. `build/reports/<pack>-test.xml`
is a standard JUnit file: one test case per Sutra and sample.

### 10.3 GitOps: create, propose, approve

A design is made and checked by anyone, reviewed by an approver, and only then live. From a pipeline or a terminal:

```bash
# the author (a role with `author`, on a server with DRISHTI_STUDIO_SAVE=true)
python3 tools/drishti.py design create --name trade-swap --from-sutra sutras/trade/trade-swap.v1.sutra.yaml \
    --samples tests/trade-swap --json | jq -r .id > /tmp/design-id
python3 tools/drishti.py design check "$(cat /tmp/design-id)"                       # exit 1 stops the pipeline
python3 tools/drishti.py design propose "$(cat /tmp/design-id)" --note "swap strip: add MTM"

# the approver (a role with `approve`; with four-eyes on, not the author)
python3 tools/drishti.py design proposals --status pending
python3 tools/drishti.py design approve P-000042 --comment "checked against 5 samples"
```

With review off (`drishti.governance.enabled: false`) `design propose` saves the Sutra live straight away.

### 10.4 Registry: publish and install

```bash
python3 tools/drishti.py pack keygen --out keys/me.pem                      # once; keep the private key secret
python3 tools/drishti.py pack publish packs/my-bank --registry /srv/registry --key keys/me.pem --publisher me
# the server trusts the publisher (drishti.packs.registry.trusted-keys.me = the public key keygen printed), then:
python3 tools/drishti.py pack install --list
python3 tools/drishti.py pack install my-bank 1.0.0
```

### 10.5 Demo data at sizes

```bash
python3 tools/drishti.py data load --store files                                   # the samples, 10 days: seconds
python3 tools/drishti.py data load --store delta --trades 10000 --days 3           # a realistic local demo
```

Larger books (hundreds of thousands to a million trades a day) are for a machine sized for them: see
[DEMO_DATA.md](../connectors/DEMO_DATA.md) for the sizes and what each store needs.

## 11. JSON output formats

`--json` is available where a machine reader makes sense. Output is one pretty-printed JSON document on stdout; diagnostics
stay on stderr.

| Command | Shape |
|---|---|
| `pack check --json` | `{ok, packs: [{pack, path, steps: {lint: {exit, output, error}, test: {...}}, packYaml: [problems], ok}]}` |
| `pack about-check --json` | `{ok, strict, gaps, packs: [{pack, sutras: {<sutra>: {missing: [fields], other: [warnings]}}, todo: [...], gaps, lintExit}]}` |
| `pack publish --json` | `{published, pack, registry, publisher}` |
| `server health --json`, `server packs list --json`, `pack install [--list] --json` | the server's own JSON |
| every `design ... --json` | the server's own JSON (`design export` prints `{file, bytes}`) |

```text
$ python3 tools/drishti.py pack check packs/jsonl-demo --json
{
  "ok": true,
  "packs": [
    {
      "pack": "jsonl-demo",
      "path": "packs/jsonl-demo",
      "steps": {
        "lint": {
          "exit": 0,
          "output": [
            "ok      packs/jsonl-demo/sutras/counterparty/counterparty-default.v1.sutra.yaml",
            "ok      packs/jsonl-demo/sutras/trade/trade-bond.v1.sutra.yaml",
            "ok      packs/jsonl-demo/sutras/trade/trade-fx-forward.v1.sutra.yaml"
          ],
          "error": "hutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/cli-work/packs/jsonl-demo/sutras/trade/trade-fx-for...
        },
        "test": {
          "exit": 0,
          "output": [
            "     counterparty-default: help coverage 2/4 (50%)",
            "ok   packs/jsonl-demo/sutras/counterparty/counterparty-default.v1.sutra.yaml (4 samples)",
            "     trade-bond: help coverage 1/5 (20%)"
          ],
          "error": ""
        }
      },
      "packYaml": [],
      "ok": true
    }
  ]
}
```

```text
$ python3 tools/drishti.py pack about-check packs/jsonl-demo --json
{
  "ok": false,
  "strict": false,
  "gaps": 14,
  "packs": [
    {
      "pack": "jsonl-demo",
      "sutras": {
        "counterparty-default.v1": {
          "missing": [
            "businessDate",
            "rating"
          ],
          "other": []
        },
        "trade-bond.v1": {
          "missing": [
            "notional",
            "mtm",
            "businessDate",
            "productType"
          ],
          "other": []
        },
...
```

`design check --json` is the server's matrix, for example `{"ok": true, "samples": [{"name": "sample-1.json", "layout":
"Sutra trade-auto v1", "status": "ok"}, ...], "panels": [...], "rev": 1}`.

## 12. Exit codes

| Code | Meaning | Examples |
|---|---|---|
| **0** | ok | a clean lint; a design that checks; a server whose health is `OK` |
| **1** | problems found | a failing lint or test; `--strict` warnings; an unexplained field (`about-check`); a design with a failing sample; the server refused (401, 403, 404, 400...); the server cannot be reached; `server health` not `OK` |
| **2** | usage | a missing or unknown option or file; the exec jar not found; PyYAML or `deltalake` not installed; `--load` without `--server`; a pack folder without `pack.yaml` |

The Java `sutra` commands keep their own 0/1/2 and pass them through.

## 13. Troubleshooting

| You see | Why | Do |
|---|---|---|
| `drishti: no drishti-server-*-exec.jar under drishti-server/target: build it ... or pass --jar / DRISHTI_JAR` | the Java commands need the exec jar | `./mvnw -q -DskipTests package`, or `export DRISHTI_JAR=/path/to/drishti-server-1.16.0-exec.jar` |
| `drishti: jar not found: ...` | `--jar` / `DRISHTI_JAR` names a missing file | fix the path |
| `drishti: cannot run java: ...; pass --java or set JAVA_HOME` | no JDK found | set `JAVA_HOME` to a JDK 21+ |
| `drishti: PyYAML is not installed: run with uv run --with pyyaml ...` | the system Python has no PyYAML | use `uv run --with pyyaml`, or a venv |
| `drishti: deltalake is not installed; run uv run --with pyyaml --with deltalake --with pyarrow ...` | a Delta write needs them | use that `uv run` line, or `--store files` |
| `drishti: cannot reach http://localhost:18480: [Errno 111] Connection refused; is the server running?` | nothing listens there | start the server; check `--server` / `DRISHTI_SERVER`; the port is `DRISHTI_PORT` |
| `drishti: the server said 401 ...: (no or bad token: set DRISHTI_TOKEN or --token-file)` | sign-in is on and the token is missing, expired or unsigned | send a signed token ([section 3](#3-connecting-to-a-server-url-tokens-and-permissions)) |
| `the server said 403 DRS-5002: DRS-5002 API tokens only read` | a personal `drk_` token on a write | use a signed token, or sign-in off with `--user` |
| `the server said 403 DRS-5002: DRS-5002 saving from the workbench is disabled (drishti.rachana.studio-save)` | `design propose` while saving is off | start the server with `DRISHTI_STUDIO_SAVE=true` |
| `the server said 403 DRS-5002 ...` on `server packs ...` or `pack install` | you are not an administrator | use an administrator's token |
| `the server said 404 DRS-5006: DRS-5006 no design 'x'` | a wrong or someone else's design id | `design list` |
| `the server said 400 DRS-5001: DRS-5001 say why the proposal is rejected` | a rejection needs a reason | add `--comment` |
| `the server said 400 ...: cannot load 'x': ...` | the pack on disk fails the server's start-up check | run `pack check` on it; the message names the problem |
| `pack_from_jsonl: <dir> exists; pass --force to overwrite it` | the output folder is not empty | `--force`, or another `--out` |
| `pack_from_jsonl: --lake needs --date (the business-date field)` | a dated store needs each document's date | add `--date FIELD` |
| `drishti: --load needs --server URL (or DRISHTI_SERVER)` | `pack new --load` has no server | add `--server` |
| `pack.yaml ingest: 'x' is not one of the pack's kinds` / `ingest.trade: needs key: <field> and date: <field>` | a hand-edited `ingest:` block | fix it as in [section 5](#pack-new-a-complete-pack-from-json-lines) |
| `drishti: <path> is not a pack folder (no pack.yaml)` | `pack check` needs a pack folder | pass the folder with `pack.yaml` |
| `sutra lint` shows `DRS-2047 field 'x' is shown but has no glossary entry` | a field has no help text | `pack about-check`, then `config/about.yaml` |
| a view says `DRS-1001 <pack>-store holds trade for <date> and does not list trade/<id>` | the document is not in the store for that business date | pick a date you ingested; check with `data ingest --dry-run` |

## 14. Running the tools from PyCharm (and the Java `sutra` from IntelliJ)

The tools are ordinary Python scripts, so PyCharm runs and debugs them like any other, and the Java `sutra` tool runs from IntelliJ
IDEA. (For the server and the console themselves see [IDE_GUIDE.md](IDE_GUIDE.md).) This section is the whole path, from an
empty PyCharm to a run configuration for each common task.

### 14.1 Create the interpreter

1. Open the repository root as the project (**File → Open**, the folder that holds `tools/`, `packs/`, `pom.xml`).
2. Make a virtual environment with `uv` (any virtual environment works), in a terminal at the repository root:

   ```bash
   uv venv .venv-tools
   uv pip install --python .venv-tools/bin/python pyyaml deltalake pyarrow
   ```

   `pyyaml` is enough for everything except Delta lakes; `deltalake` and `pyarrow` are for `--store delta` (the default of
   `pack make`) and `data ingest --lake`. On Windows the interpreter is `.venv-tools\Scripts\python.exe`.
3. **Settings (Ctrl+Alt+S) → Project → Python Interpreter → Add Interpreter → Add Local Interpreter → Existing**, pick
   `.venv-tools/bin/python`, **OK**. Keep it separate from the console's `drishti-console/.venv`.
4. Optional: **Settings → Project → Project Structure**: mark `tools` as *Sources* so PyCharm resolves `import sutragen` and
   `import ingest_jsonl` when you click through code.

### 14.2 A run configuration for each task

**Run → Edit Configurations → + → Python**. The fields are the same for every task; only **Name** and **Parameters** change:

| Field | Value |
|---|---|
| **Script path** (not *Module name*; switch the toggle on the left of the field) | `<repo>/tools/drishti.py` |
| **Python interpreter** | the `.venv-tools` interpreter from 14.1 |
| **Working directory** | **the repository root**: relative paths in *Parameters* resolve from here |
| **Environment variables** | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64;DRISHTI_JAR=<repo>/drishti-server/target/drishti-server-1.16.0-exec.jar;PYTHONUNBUFFERED=1`, and for server commands `DRISHTI_SERVER=http://localhost:18480;DRISHTI_TOKEN=drk_...` |

Click the folder icon next to **Environment variables** to edit them as a table (one row per variable). `JAVA_HOME` and
`DRISHTI_JAR` may be left out: the tool then looks for JDK 21 and for the newest `drishti-server/target/*-exec.jar` itself.

| Name | **Parameters** |
|---|---|
| `drishti pack make` | `pack make data/jsonl --kind trade --match productType --name my-bank --out build/my-bank --force` |
| `drishti data profile` | `data profile data/jsonl` |
| `drishti pack regenerate` | `pack regenerate packs/my-bank data/jsonl --dry-run` |
| `drishti sutra gen` | `sutra gen data/jsonl --kind trade --match productType --out build/sutras` |
| `drishti data ingest` | `data ingest --from data/new --pack build/my-bank/pack/my-bank --lake build/my-bank/data/delta --dry-run` (remove `--dry-run` to write) |
| `drishti pack check` | `pack check build/my-bank/pack/my-bank --strict --junit build/reports` |
| `drishti server packs load` | `server packs load my-bank --server http://localhost:18480` with `DRISHTI_TOKEN` set |
| `drishti sutra lint` | `sutra lint packs/my-bank` |
| `drishti server health` | `server health` |

Press **Run** (Shift+F10): the output is in the Run window, and a non-zero exit code shows as a failed run (exit 1 = a check
failed, 2 = usage; see [section 12](#12-exit-codes)). If the Run window says `no drishti-server-*-exec.jar`, build it once
(`./mvnw -q -DskipTests package`) or set `DRISHTI_JAR`.

**Templates.** **Run → Edit Configurations → Edit configuration templates... → Python** sets the defaults for every *new* Python
configuration: put the interpreter, the working directory (`$PROJECT_DIR$`), and the environment variables there once, and each new
configuration (**+ → Python**) then only needs a **Name**, the **Script path** and the **Parameters**. Duplicate an existing
configuration (Ctrl+D in the dialog) to make a variant.

**Secrets.** `DRISHTI_TOKEN` goes in the configuration's **Environment variables** (kept in `.idea/workspace.xml`, which is not committed),
or use `--token-file ~/.drishti-token` in **Parameters**. Never put a token in a shared configuration.

### 14.3 Debug a tool

The tools are plain Python, so debugging is PyCharm's normal debugging: open `tools/drishti.py` (or `tools/packmake.py`,
`tools/sutragen.py`, `tools/ingest_jsonl.py`, `tools/packgen/pack_from_jsonl.py`, `tools/packbundle.py`), click the gutter left of a
line to set a breakpoint, and press **Debug** (Shift+F9) on the run configuration instead of Run. Good first breakpoints:

- `pack make`: `detect_key` and `detect_date` in `tools/packmake.py` (why a field was chosen), and `make` (the whole flow);
- `sutra gen`: `generate` in `tools/sutragen.py` (the groups, one per `--match` value);
- `data ingest`: `ingest` in `tools/ingest_jsonl.py` (the dates and rows it will write).

The Java `sutra` command that `pack check` and `pack make` start is a separate process: breakpoints in Python do not stop it
(use 14.5 for that). **Variables** shows the parsed options (`a`), and **Evaluate expression** (Alt+F8) works on them.
To stop on an error, tick **Run → View Breakpoints → Python Exception Breakpoints → Any exception**.

### 14.4 Share the configurations with the team: `.run/`

By default a configuration lives in `.idea/workspace.xml` (personal, not committed). To share it, open it in **Edit
Configurations** and tick **Store as project file**: PyCharm writes it to `.run/<name>.run.xml` in the project, which can be
committed. This repository ships four to start from, `.run/drishti pack make.run.xml`, `.run/drishti sutra gen.run.xml`, `.run/drishti data profile.run.xml` and `.run/drishti pack regenerate.run.xml`; PyCharm
lists them under **Python** as soon as the project opens (reload with **File → Reload All from Disk** if it was open already).
The format is one `<configuration>` element in a `ProjectRunConfigurationManager` component:

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="drishti pack make" type="PythonConfigurationType" factoryName="Python">
    <module name="drishti" />
    <envs><env name="PYTHONUNBUFFERED" value="1" /></envs>
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="IS_MODULE_SDK" value="true" />
    <option name="SCRIPT_NAME" value="$PROJECT_DIR$/tools/drishti.py" />
    <option name="PARAMETERS" value="pack make data/jsonl --kind trade --match productType --name my-bank --out build/my-bank --force" />
    <method v="2" />
  </configuration>
</component>
```

(the real files list every option PyCharm writes). `$PROJECT_DIR$` is the repository root on every machine; `IS_MODULE_SDK` makes
the configuration use the project's interpreter, so nobody's local path is in the file; the `module` name is the project
folder name (`drishti`; PyCharm asks you to pick one if yours differs). Put only what is shared in them: no tokens, no home-directory
paths. Edit **Parameters** for your own input folder, or duplicate the configuration for each data set.

### 14.5 The Java `sutra` tool from IntelliJ IDEA

`sutra lint|test|preview|shape|design` is the server's own jar started with `sutra` as its first argument, so IntelliJ can run
it without the command-line program, and debug the engine while it lints:

**Run → Edit Configurations → + → Application**:

| Field | Value |
|---|---|
| Main class | `com.ash.drishti.server.DrishtiApplication` |
| *Use classpath of module* | `drishti-server` |
| JRE | JDK 21 or newer |
| **Program arguments** | `sutra lint packs/my-bank --strict` |
| **Working directory** | **the repository root** |

(or **JAR Application** with the exec jar as *Path to JAR* and the same program arguments). It prints what the command line
prints and ends with the exit code; breakpoints in `drishti-rachana` or `drishti-server/.../cli/SutraCli.java` stop as usual.
Paths in the arguments are relative to the working directory here, unlike in `drishti.py` (which makes them absolute for you).

### 14.6 Run the tools' own tests

**Run → Edit Configurations → + → Python tests → Unittest**: *Target* `Script path`, the file `tools/test_packmake.py` (or
`test_drishti_cli.py`, `test_sutragen.py`, `test_ingest_jsonl.py`, `test_pack_bundle.py`), working directory the repository root,
environment `DRISHTI_JAR=...` (the tests that run `sutra lint` are skipped without a jar).

### 14.7 Where next

[IDE_GUIDE.md](IDE_GUIDE.md) runs the server and the console; [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md) and
[PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md) are the references for what these commands produce and check.
