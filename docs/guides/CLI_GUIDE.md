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
- [6. `data`: profile, ingest, load, and tell the server a batch landed](#6-data-profile-ingest-and-load)
- [7. `server`: health and packs; also smoke, `doctor`, `view`, shell completion, and the `connector` commands](#7-server-health-and-packs)
  - [`connector`: list, get, apply, delete, test, plugins, enable, disable, reset, history](#connector-list-get-apply-delete-test-plugins-enable-disable-reset-history)
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
drishti-console/.venv/bin/python tools/drishti.py pack make data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz \
    --kind trade --match PRODUCT_TYPE --name my-bank
```

The input here is the committed test data: the ENDUR trades of the first business day ([data/testdata/README.md](../../data/testdata/README.md));
put your own folder of JSON Lines (or one file, `.jsonl`, `.jsonl.gz` or a `.json` array) in its place.

| Option | Meaning |
|---|---|
| `INPUT...` | folder(s) of `*.jsonl` and/or file(s) (required) |
| `--kind KIND` | the kind of every document, e.g. `trade` (required) |
| `--match COL[,COL]` | the column(s) whose values split the kind: **one Sutra per value** (required) |
| `--name NAME` | the pack name, lower case, digits and `-` (required) |
| `--key FIELD` | the id field. Default: **detected**: a field present and unique in every document, `<KIND>_ID`, `<kind>Id` or `id` first; it prints what it chose and why, and **refuses** (naming `--key`) when none is unique |
| `--date FIELD` | the business-date field. Default: **detected**: a date-valued field present in at least 95% of the documents, one named like a business date first (`BUSINESS_DATE`, `AS_OF`, `DATE`, `VALUATION_DATE`, in any case); else the date field with the fewest distinct dates, **which may not be a business date at all** (on the test data it is `START_DATE`): check the `date:` line it prints and pass `--date`. None found: no data folder, the pack serves its samples |
| `--title`, `--version` | the pack title; the version (default `1.0.0`) |
| `--store delta\|files` | the dated store: `delta` (default) or the File connector's files |
| `--out DIR` | the folder to create (default `data/build/<name>-<version>`, under `DRISHTI_DATA_DIR`); `--force` replaces it |
| `--fallback` / `--no-fallback` | a catch-all Sutra for documents matching no group (default on) |

What it makes (everything the server needs is in this one folder):

```text
data/build/my-bank-1.0.0/
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
`data ingest --from new-days --pack data/build/my-bank-1.0.0/pack/my-bank --lake <the lake>`. The details are in
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
- A pack becomes known to a running server in **four ways**: its folder is copied into the packs folder the server watches (`pack deploy`, `pack make`'s `pack/<name>`: the server checks it and loads it by itself, no restart), the server is started with it in `DRISHTI_PACKS` (a restart),
  an administrator loads it in **Admin → Packs** (at once), or it is installed from a registry.
  `drishti.py pack new --load`, `server packs load` and `pack install` are the command-line forms of the last two.
  [PACKS.md#loading-a-pack-while-the-server-runs](PACKS.md#loading-a-pack-while-the-server-runs) has the rules.

Run the program from the repository root, or from anywhere with its path, with the console's interpreter
(`drishti-console/.venv/bin/python`, which the quick start of [data/testdata/README.md](../../data/testdata/README.md) installs with PyYAML, deltalake
and pyarrow). Many examples write `python3 tools/drishti.py`: any Python 3.10+ with PyYAML runs every command that does not write a Delta lake;
`drishti.py` in the output is the same program.

**`drishti.py --help`** (captured on a fresh clone)

```text
usage: drishti.py [-h] GROUP ...

drishti: one command line for building, checking, loading and shipping Drishti packs and Sutras.

positional arguments:
  GROUP
    sutra       The Sutra tool: shape, design, lint, test, preview (Java) and
                gen (one Sutra per group of JSON Lines)
    pack        Packs: generate from JSON Lines, check in CI, find missing
                help text, publish and install
    data        Data: ingest your own JSON Lines into a lake or files store;
                load the demo data into any store
    server      A running server over REST: health and pack management
                (administrator)
    connector   Connectors over REST (Admin -> Connectors): list, get, apply a
                YAML file, test, enable, delete (administrator; a token needs
                packs:admin)
    design      Screen Designer designs over REST: create, edit, check,
                propose, approve, ship (anyone may design; approving needs an
                approver)
    docs        Documentation tooling
    doctor      doctor: is this machine ready (Java, jar, Python, directories,
                ports, and with --server the token)
    bi          Business intelligence (Rupaka) over REST: workspaces and the
                catalogue, as the token's user sees them (a write needs the
                bi:write scope)
    view        A rendered entity view in the terminal, as the caller's token
                sees it (masks apply)
    lake        Delta lakes: build one from a large JSON export (memory flat,
                safe to re-run), scan an export, status, maintain
    completion  completion: print a shell completion script (bash, zsh,
                powershell)

options:
  -h, --help    show this help message and exit

    python3 tools/drishti.py <group> <command> [options]          (or: uv run --with pyyaml python tools/drishti.py ...)

Groups: sutra (the Java `sutra` tool, plus `sutra gen`), pack (make, new, regenerate, diff, catalogue, i18n, check, about-check, bundle, verify, deploy, rollback,
publish, keygen, install), data (profile and ingest your own JSON Lines or watch a drop folder, load the demo data), server (health, packs, smoke),
lake (build a Delta lake from a large JSON export, scan, status, maintain), view (get, explain), doctor, completion, design (the Screen Designer's designs over
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
uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py data ingest --from data/new --pack config/packs/my-bank --lake data/delta
```

**With the console's environment** (what an IDE wants, see [section 14](#14-running-the-tools-from-pycharm-and-the-java-sutra-from-intellij);
the run configurations use it):

```bash
python3 -m venv drishti-console/.venv && drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt
#   or, where python3 -m venv lacks ensurepip:  uv venv --python 3.13 drishti-console/.venv && uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
drishti-console/.venv/bin/python tools/drishti.py --help
```

**Environment variables** the program reads:

| Variable | Meaning | Default |
|---|---|---|
| `DRISHTI_JAR` | the exec jar the Java commands run | the newest `drishti-server/target/drishti-server-*-exec.jar` |
| `JAVA_HOME` | the JDK that runs it | `/usr/lib/jvm/java-21-openjdk-amd64`, then `java` on the `PATH` |
| `DRISHTI_SERVER` | the server's base URL | `http://localhost:18480` |
| `DRISHTI_TOKEN` | the bearer token (never printed) | none |
| `DRISHTI_USER` | the user name sent when the server has sign-in off | none |

The Java commands run **from the repository root** (the engine reads `./config/packs` and `./sutras` relative to it), so every path
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
| `loads:write` | `data landed` | a role that opens the kind (or the admin role) |

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
  drishti.py sutra lint config/packs/market-risk --strict
  drishti.py sutra test config/packs/market-risk --junit build/market-risk.xml
  drishti.py sutra shape samples/ --out build
  drishti.py sutra design samples/ --kind ticket
Unknown options are passed to the Java tool. Exit codes as the Java tool: 0 ok, 1 problems, 2 usage.
```

**Lint a pack** (the shipped help-desk example; it prints the Sutra's absolute path, shortened here). The pack the command line names is loaded with
the packs `DRISHTI_PACKS` names (and the packs it extends), never with the packs an administrator added to the server in this folder
(`<data dir>/packs/added.yaml`; set `DRISHTI_PACKS_OVERLAY` to use another list):

```bash
python3 tools/drishti.py sutra lint docs/guides/examples/pack/helpdesk
```

```text
ok      docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml
```

A field a Sutra shows without a glossary entry in `config/about.yaml` is a warning (DRS-2047), not an error, so the exit code stays 0. With
only the `AGE_HOURS` and `PRIORITY` entries the same command adds lines such as:

```text
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'ASSIGNEE' is shown but has no glossary entry (kinds.ticket.glossary in config/about.yaml, or a vocabulary entry)
docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml:15 warning DRS-2047 field 'HISTORY.EVENT' is shown but has no glossary entry (kinds.ticket.glossary in config/about.yaml, or a vocabulary entry)
```

`--strict` turns those DRS-2045 to 2047 help warnings into failures (exit 1). `sutra test <pack>` renders every Sutra against
its `tests/<sutra>/` samples and checks `expect.yaml`; `--junit report.xml` writes a JUnit file; `sutra preview <pack> --out
build/html` writes an HTML snapshot per sample.

**Infer a shape** (a JSON Schema with Drishti's role annotations) and **draft a Sutra** from sample documents (a folder of JSON
files, one document each: here the help-desk example's test tickets):

```bash
python3 tools/drishti.py sutra shape docs/guides/examples/pack/helpdesk/tests/ticket
python3 tools/drishti.py sutra design docs/guides/examples/pack/helpdesk/tests/ticket --kind ticket
```

```text
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "TICKET_ID" : {
      "type" : "string",
      "x-drishti" : {
        "role" : "link",
        "reason" : "every value is the id of a ticket",
        "kind" : "ticket"
      }
    },
    "SUBJECT" : {
      "type" : "string"
    },
    "STATUS" : {
      "type" : "string",
      "x-drishti" : {
        "role" : "status",
        "reason" : "named 'STATUS' with 2 distinct value(s)"
...
  "required" : [ "TICKET_ID", "SUBJECT", "STATUS", "PRIORITY", "ASSIGNEE", "CLIENT", "CLIENT_NAME", "AGE_HOURS", "HISTORY", "_meta" ],
  "x-drishti" : {
    "samples" : 2
  }
}
```

```text
rachana: 1
sutra: ticket-auto
version: 1
description: Drafted by auto-design from 2 samples. Edit freely.
match: { kind: ticket, priority: 1 }
title: { pill: "Ticket · ${$.PRIORITY}", id: "'ticket'", with: "link($.TICKET_ID, 'ticket')" }
strip:
  - { label: "Status", bind: "$.STATUS", tone: "status" }
  - { label: "Client name", bind: "$.CLIENT_NAME" }
  - { label: "Age (h)", bind: "$.AGE_HOURS", fmt: "age0", emphasis: true }
  - { label: "Generation", bind: "$._meta.generation", fmt: "pct0" }
panels:
  - id: details
    kind: kv
    title: "Details"
    key: "F2"
...
  - id: history
    kind: timeline
    title: "History"
    key: "F3"
    rows: "$.HISTORY"
    date: "DATE"
    label: "EVENT"
...
```

A draft is a start, not a screen: here the id was read as a link to a ticket, so the title shows the word `ticket` and links the id;
change it to `id: $.TICKET_ID` before you keep it.

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
  drishti.py sutra gen data/testdata/trades/endur --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/sutras
```

On the three trade sources of the test data (every `*.jsonl` and `*.jsonl.gz` of each folder: the first day's snapshot and the six days of
changes), one run:

```bash
drishti-console/.venv/bin/python tools/drishti.py sutra gen data/testdata/trades/endur data/testdata/trades/calypso \
    data/testdata/trades/murex --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/sutras
```

```text
19523 documents, 1 kind(s), 130 group(s)
drafting 130 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
trade           PRODUCT_TYPE=ABS                                131       5  trade-abs
trade           PRODUCT_TYPE=ACCRETING_SWAP                      91       5  trade-accreting-swap
trade           PRODUCT_TYPE=AMORT_BOND                         129       5  trade-amort-bond
...
trade           PRODUCT_TYPE=ZC_INFL_SWAP                        89       5  trade-zc-infl-swap
trade           PRODUCT_TYPE=ZC_SWAP                             92       5  trade-zc-swap
trade           PRODUCT_TYPE=ZERO_BOND                          136       5  trade-zero-bond
...
ok      data/build/sutras/trade/trade-zc-swap.v1.sutra.yaml
ok      data/build/sutras/trade/trade-zero-bond.v1.sutra.yaml
```

19,523 documents are the 15,000 trades and their amended versions of the later days (a trade counts once per file it is in). Each
Sutra is `data/build/sutras/<kind>/<kind>-<group>.v1.sutra.yaml` with its `where` clause, and `data/build/sutras/tests/<sutra>/sample-*.json`
holds samples drawn across the dates (with `--date FIELD`; the test data's trades carry no business-date field, their business date is the
file's day). The `--out` folder is created when missing; it must not hold files of yours. A per-kind form names the field for each kind when
one run reads several kinds: `--key trade=TRADE_ID,counterparty=COUNTERPARTY_ID`. The generator is described in
[SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md); `--fallback` also writes a `<kind>-default` Sutra for documents no group matches.


#### Many product types in many files: one command, one Sutra per product type

A common case: a folder of JSON Lines files where every line is a trade with a unique `TRADE_ID` and a product type, with
the products mixed across files in any way. **One run does it all**: `sutra gen` reads every file, groups the trades by
the `--match` field across all of them, and writes **one Sutra per distinct product type**. There is no need to split the
files by product or to run the command once per product.

```bash
drishti-console/.venv/bin/python tools/drishti.py sutra gen data/testdata/trades/endur data/testdata/trades/calypso \
    data/testdata/trades/murex --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/sutras
```

Two things decide whether it does what you expect:

1. **The kind comes from the file name** unless you say otherwise. `trade.jsonl` is kind `trade`; the test data's
   `snapshot-2026-09-22.jsonl.gz` would be kind `snapshot-2026-09-22`, and the per-kind form `--key trade=TRADE_ID` would not
   apply to it. When every line is a trade, pass **`--kind trade`**; then the plain forms `--key TRADE_ID` and
   `--match PRODUCT_TYPE` are enough. (Loader envelope lines carry their own `kind` and need no `--kind`.)
2. **Use the field name exactly as it is in the data** (`PRODUCT_TYPE`, `product_type` and `productType` are three different fields). A
   misspelt match field puts every trade in one group without the field, so you would get a single Sutra; add
   `--skip-missing` to leave out documents that lack a match field instead.

What comes out, for the 130 product types of the three sources:

| Output | Where |
|---|---|
| 130 Sutras, each with `match: { kind: trade, where: "$.PRODUCT_TYPE == 'IRS_FIXFLOAT'", priority: 10 }` and the title id `$.TRADE_ID` | `data/build/sutras/trade/trade-<product>.v1.sutra.yaml` |
| up to 5 sample trades per Sutra (`--samples N`; chosen deterministically, across dates with `--date`) | `data/build/sutras/tests/<sutra>/sample-*.json` |
| a lint of everything written | exit code 1 if any Sutra has a problem |

Options that help here: `--fallback` adds a low-priority `trade-default` Sutra so a product type that appears later still
gets a designed screen (rather than an inferred one); `--min-docs N` skips product types with fewer documents;
`--date BUSINESS_DATE` (your data's business-date field) picks samples across business dates, newest first; several match fields
(`--match PRODUCT_TYPE,CURRENCY`) give one Sutra per combination. To get a whole pack (kinds, mnemonics, tests, About
skeleton, optionally a Delta lake or files) instead of Sutra files only, use `pack new` with the same options
([below](#pack-new-a-complete-pack-from-json-lines)).

It needs the built server jar (`./mvnw package -DskipTests`) and a JDK 21: all groups are designed in one Java run.

## 5. `pack`: make, new, regenerate, diff, catalogue, i18n, check, about-check, bundle, verify, deploy, rollback, publish, keygen, install

### `sutra design --schema` and `pack make --schema`

Both take **JSON Schema** (draft 2020-12 or 07, JSON or YAML) and run the rules of [Schema to pack](SCHEMA_TO_PACK.md): kind, key, links, labels, formats, match column, strip, About text,
tests. `sutra design --schema FILE... [--kind K] [--out DIR]` prints the Sutras (or writes `<out>/<kind>/<name>.v1.sutra.yaml` and `<out>/tests/`); `pack make --schema FILE|DIR... [DATA] --name N`
writes the same folder as `pack make` below, for many kinds, with synthetic samples (named `sample-synthetic-N.json`) where no real documents are given. A JSON Lines file or folder after `--schema` is data.
Flags: `--key`, `--date`, `--match` (as `kind=FIELD`; `--match none`), `--mnemonic`, `--strip N`, `--count N` (design) / `--samples N` (make), `--code`, `--description`, `--connector`, `--yes` (never ask which
field is the key). The example schemas are in `docs/guides/examples/schemas/`; real output is in [Schema to pack](SCHEMA_TO_PACK.md#the-command-line).

### `pack make`: everything in one folder

`pack make` is `pack new` + `data ingest` + `pack check` + `pack bundle` + `pack verify` in one run, writing a single
folder (the tree and the options are in [Quickest path](#quickest-path-pack-make) at the top). It adds no logic of its own to
those tools (`tools/packmake.py` only chooses the key and the date, lays the folder out and writes `README.txt`,
`config/drishti-site.yaml`, `run-server.sh`, `run-server.ps1` and `MANIFEST.json`).

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack make data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank
read 4980 trade document(s)
key:   TRADE_ID  (named <kind>_id, present and unique in all 4980 documents)
date:  START_DATE  (the date-valued field with the fewest distinct dates (26); pass --date to choose another)
4980 documents, 1 kind(s), 6 group(s)
drafting 6 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
trade           PRODUCT_TYPE=CallNot                             55       5  trade-callnot
trade           PRODUCT_TYPE=Cash                               815       5  trade-cash
trade           PRODUCT_TYPE=ComFut                             994       5  trade-comfut
trade           PRODUCT_TYPE=ComSwap                           2266       5  trade-comswap
trade           PRODUCT_TYPE=Commodity                          850       5  trade-commodity
trade           (all)                                          4980       5  trade-default
...
== pack bundle
bundled my-bank 1.0.0: 47 files
...
== pack verify
  checksums ok / schema ok / server ok / sutra ok
verified

pack make: data/build/my-bank-1.0.0
  6 Sutra group(s) for 4980 trade document(s), 26 date(s) in data/delta; pack check passed, pack verify passed
next: read data/build/my-bank-1.0.0/README.txt; for a new server  DRISHTI_JAR=<exec jar> ...
```

Read the `date:` line. The test data's trades carry no business-date field (a file is one business day), so `pack make` took the date
field with the fewest distinct values, `START_DATE`, and stored each trade under its start date: 26 "business dates" that are not business
dates. For such snapshots, build the lake with [`lake build --as-of`](TOOLS.md) (or the demo lakes, `drishti.py lake demo`) and use
`pack make` for the pack alone; for documents that carry their business date, pass `--date` with that field.

Try it without touching anything of yours: `DRISHTI_JAR=... DRISHTI_PORT=18974 data/build/my-bank-1.0.0/run-server.sh`, then
`curl 'http://localhost:18974/api/v1/views/trade/<an id>?asOf=<a date>'`. Documents of another day are added with `data ingest`
(default mode `overwrite-dates`) into the same `data/delta`. Exit codes: 0 made and both checks passed, 1 made but a check failed
(the folder is still written), 2 usage (no unique key, a missing field, `--out` exists without `--force`).

### `pack new`: a complete pack from JSON Lines

Writes `config/packs/<name>/` (or `--out`): `pack.yaml` (kinds, mnemonics, columns, and with a date a connector, routes and the
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
  --out OUT             default config/packs/<name>
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
                        (administrator; takes effect at once, no restart)

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
  drishti.py pack new data/testdata/trades/murex --name murex-book --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/murex-book
  drishti.py pack new your-folder/ --name my-bank --date BUSINESS_DATE --store files --files-root data/files --load --server http://localhost:18977
```

On the MUREX snapshot of the test data (it has no business-date field, so no `--date`: the pack serves its samples and no dated store
is written), with `--out` outside `config/packs` so nothing of the server's changes:

```bash
drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/trades/murex/snapshot-2026-09-22.jsonl.gz --name murex-book \
    --title "MUREX book" --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/murex-book
```

```text
4933 documents, 1 kind(s), 74 group(s)
drafting 74 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
trade           PRODUCT_TYPE=ACCRETING_SWAP                      72       5  trade-accreting-swap
trade           PRODUCT_TYPE=AMORT_SWAP                          72       5  trade-amort-swap
...
trade           PRODUCT_TYPE=RANGE_ACCRUAL_NOTE                  66       5  trade-range-accrual-note
...
pack written to data/build/murex-book
sutra lint: exit 0
sutra test: exit 0
```

**The dated store.** `--date FIELD` names each document's business-date field (in the examples below `BUSINESS_DATE` stands for yours). Where the documents go is `--store`:

| `--store` | Writes | The pack declares | Needs |
|---|---|---|---|
| `delta` (default) | a Delta lake `<lake>/<pack>/<kind>/` partitioned by `business_date`, with `--lake DIR` | a `delta` connector (`DRISHTI_DELTA_ROOT`) | `deltalake`, `pyarrow` |
| `files` | `<files-root>/<pack>/<date>/<kind>.jsonl` with `--files-root DIR` | a `file` connector (`DRISHTI_FILES_ROOT`) | nothing extra |

Serve it by starting the server with `DRISHTI_DELTA_ROOT=<lake>` or `DRISHTI_FILES_ROOT=<root>`. The pack is read from the
store for every date you ingested; the business-date picker and history work.

The interest-rate curves of the test data carry their business date in `AS_OF` (seven files, one a day, 28 curves each), so they
fill a dated store:

```bash
drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/market-data/ir-curve --name curves-demo --title "Curves demo" \
    --kind ir-curve --key CURVE_ID --date AS_OF --match CURVE_TYPE --store files --files-root data/build/files --out data/build/curves-demo
```

```text
196 documents, 1 kind(s), 4 group(s)
drafting 4 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
ir-curve        CURVE_TYPE=Cross-currency basis                  49       5  ir-curve-cross-currency-basis
ir-curve        CURVE_TYPE=Discount (OIS)                        49       5  ir-curve-discount-ois
ir-curve        CURVE_TYPE=Government                            49       5  ir-curve-government
ir-curve        CURVE_TYPE=Projection                            49       5  ir-curve-projection
  ir-curve 2026-09-22: 28 rows -> data/build/files/curves-demo/2026-09-22/ir-curve.jsonl
  ir-curve 2026-09-23: 28 rows -> data/build/files/curves-demo/2026-09-23/ir-curve.jsonl
  ...
  ir-curve 2026-09-30: 28 rows -> data/build/files/curves-demo/2026-09-30/ir-curve.jsonl
kind                business date        rows
ir-curve            2026-09-22             28
...
ir-curve            2026-09-30             28
total rows: 196
Dated store (files): 196 rows written to data/build/files (domain curves-demo); 0 document(s) without a date or id skipped
pack written to data/build/curves-demo
sutra lint: exit 0
sutra test: exit 0
```

The same, into a **Delta lake** (`deltalake` and `pyarrow` are in the console's requirements):

```bash
drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/market-data/ir-curve --name curves-lake --kind ir-curve \
    --key CURVE_ID --date AS_OF --match CURVE_TYPE --lake data/build/delta --out data/build/curves-lake
```

```text
196 documents, 1 kind(s), 4 group(s)
...
  ir-curve 2026-09-22: 28 rows
  ...
  ir-curve 2026-09-30: 28 rows
total rows: 196
Dated store (delta): 196 rows written to data/build/delta (domain curves-lake); 0 document(s) without a date or id skipped
pack written to data/build/curves-lake
sutra lint: exit 0
sutra test: exit 0
```

**`--load --server URL`** asks the running server to load the pack straight away (it calls `POST /api/v1/admin/packs/<name>/load`;
you must be an administrator, and it takes effect at once, with no restart: the server answers `Applied now; no restart.`). The pack has
to be where the server reads packs from: its `drishti.packs.dir` (default `./config/packs`) or its installed-dir. If `--out` is somewhere
else the command says so.

```bash
drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/market-data/ir-curve --name curves-demo --kind ir-curve \
    --key CURVE_ID --date AS_OF --match CURVE_TYPE --store files --files-root data/files --load --server http://localhost:18480
```

**The `ingest:` block.** `pack new` records which field is each kind's id and which is its business date in `pack.yaml` (here,
`data/build/curves-demo/pack.yaml`):

```yaml
ingest:
  ir-curve:
    key: CURVE_ID
    date: AS_OF
```

It is an optional **tooling key**: `data ingest --pack` and `pack check` read it, the server ignores it (it is listed in the
key table of [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md)). Delete it and the pack loads exactly the same.

### `pack check`: lint + test + help coverage, for CI

For each pack: `sutra lint` (with `--strict`: help warnings fail), then `sutra test` (renders every sample, checks `expect.yaml`
and reports help coverage per Sutra), and a check of the pack.yaml tooling keys: the `ingest:` block names real kinds with a `key` and a `date`, and the `form:` section (the search form of each kind, see [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#form-the-search-form-of-a-kind)) is valid: a path that is not a path, an unknown
`type`, an operator the type lacks, a duplicate field, `values` on a field that is not an `enum` or a kind the pack does not have is reported with the kind and the field, and fails the run:

```text
pack.yaml form.trade.fields[2] (mtm): type 'money' is not one of text, number, amount, date, enum, id, bool
pack.yaml form.trade.fields[0] (counterparty.name): operator 'between' does not suit type text (allowed: equals, contains, starts, regex)
```

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
  drishti.py pack check config/packs/my-bank --strict --junit build/reports
  drishti.py pack check config/packs/a config/packs/b --json
exit: 0 all passed, 1 any failure, 2 usage
```

```bash
drishti-console/.venv/bin/python tools/drishti.py pack check data/build/curves-demo --junit data/build/reports
```

```text
== curves-demo: sutra lint: exit 0
data/build/curves-demo/sutras/ir-curve/ir-curve-government.v1.sutra.yaml:1 warning DRS-2047 field 'POINTS.ZERO_RATE' is shown but has no glossary entry (kinds.ir-curve.gl...
...
data/build/curves-demo/sutras/ir-curve/ir-curve-projection.v1.sutra.yaml:1 warning DRS-2047 field 'POINTS.DF' is shown but has no glossary entry (kinds.ir-curve.glossary...
== curves-demo: sutra test: exit 0
     ir-curve-cross-currency-basis: help coverage 9/15 (60%)
ok   data/build/curves-demo/sutras/ir-curve/ir-curve-cross-currency-basis.v1.sutra.yaml (5 samples)
     ir-curve-discount-ois: help coverage 9/15 (60%)
ok   data/build/curves-demo/sutras/ir-curve/ir-curve-discount-ois.v1.sutra.yaml (5 samples)
     ir-curve-government: help coverage 9/15 (60%)
ok   data/build/curves-demo/sutras/ir-curve/ir-curve-government.v1.sutra.yaml (5 samples)
     ir-curve-projection: help coverage 9/15 (60%)
ok   data/build/curves-demo/sutras/ir-curve/ir-curve-projection.v1.sutra.yaml (5 samples)
```

The exit code is 0 here; with `--strict` the generated pack's unexplained fields fail the run:

```bash
drishti-console/.venv/bin/python tools/drishti.py pack check data/build/curves-demo --strict --tail 3
```

```text
== curves-demo: sutra lint --strict: exit 1
data/build/curves-demo/sutras/ir-curve/ir-curve-projection.v1.sutra.yaml:1 warning DRS-2047 field 'POINTS.QUOTE' is shown but has no glossary entry (kinds.ir-curve.glossa...
data/build/curves-demo/sutras/ir-curve/ir-curve-projection.v1.sutra.yaml:1 warning DRS-2047 field 'POINTS.ZERO_RATE' is shown but has no glossary entry (kinds.ir-curve.gl...
data/build/curves-demo/sutras/ir-curve/ir-curve-projection.v1.sutra.yaml:1 warning DRS-2047 field 'POINTS.DF' is shown but has no glossary entry (kinds.ir-curve.glossary...
== curves-demo: sutra test: exit 0
ok   data/build/curves-demo/sutras/ir-curve/ir-curve-government.v1.sutra.yaml (5 samples)
     ir-curve-projection: help coverage 9/15 (60%)
ok   data/build/curves-demo/sutras/ir-curve/ir-curve-projection.v1.sutra.yaml (5 samples)
```

`--junit DIR` writes `DIR/<pack>-lint.xml` and `DIR/<pack>-test.xml` (here `data/build/reports/curves-demo-lint.xml` and
`curves-demo-test.xml`), which any CI shows as test results.

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
drishti-console/.venv/bin/python tools/drishti.py pack about-check data/build/curves-demo
```

```text
== curves-demo: 24 field(s) shown without glossary text in 4 Sutra(s); 10 placeholder TODO text(s) in config/about.yaml
  ir-curve-cross-currency-basis.v1: POINTS.TENOR, POINTS.MATURITY, POINTS.INSTRUMENT, POINTS.QUOTE, POINTS.ZERO_RATE, POINTS.DF
  ir-curve-discount-ois.v1: POINTS.TENOR, POINTS.MATURITY, POINTS.INSTRUMENT, POINTS.QUOTE, POINTS.ZERO_RATE, POINTS.DF
  ir-curve-government.v1: POINTS.TENOR, POINTS.MATURITY, POINTS.INSTRUMENT, POINTS.QUOTE, POINTS.ZERO_RATE, POINTS.DF
  ir-curve-projection.v1: POINTS.TENOR, POINTS.MATURITY, POINTS.INSTRUMENT, POINTS.QUOTE, POINTS.ZERO_RATE, POINTS.DF
  TODO: ir-curve, ir-curve.CURVE_TYPE, ir-curve.INDEX, ir-curve.TEN_Y, ir-curve.SLOPE_2S_10S, ir-curve.AS_OF, ir-curve.BUILT_AT, ir-curve.CURRENCY, ir-curve.INTERPOLATION, ir-curve.DAY_COUNT
24 gap(s): add glossary entries under kinds.<kind>.glossary (or vocabulary) in config/about.yaml; see PACK_DEVELOPER_GUIDE.
(exit 1)
```

Fix each by adding the field under `kinds.<kind>.glossary` (or a pack-wide `vocabulary` entry) in `config/about.yaml`; the
format is in [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md).

### `pack regenerate`: new data, new Sutras, none of your edits lost

A generated pack is a starting point you then edit (About text, labels, priorities). When the data grows (a new product type, a new
field), `pack regenerate` generates again and **three-way merges** the result into your pack. `pack new` and `pack make` store what
they generated in `<pack>/generated/` (a `.base` copy of every generated file and the options used); that copy is the *base*, your
pack is *ours*, the fresh generation is *theirs* (`tools/packregen.py`). Commit `generated/` with the pack; the bundle leaves it out.

| Situation | What happens |
|---|---|
| only the new generation changed a file | `refresh`: the file is replaced |
| only you changed it | `keep`: your edit stays |
| a group or file that is new | `add` |
| no longer generated, you never edited it | `remove`; edited: `stale`, kept and listed |
| both changed, in different places | `merge`: a line-level three-way merge (difflib), written |
| both changed the same lines | `conflict`: both texts in the file between `<<<<<<< yours`, `=======` and `>>>>>>> regenerated` markers; listed; **exit 1** |
| you deleted a file the generator still makes | `deleted`: it stays deleted |
| no `generated/` (a pack made before this) | nothing is merged: differing files are `no-baseline` (kept, exit 1), new files are added |

A worked run on the test data: a pack made from the ENDUR snapshot (five product types), two hand edits (the `ComSwap` Sutra's
priority set to 15, the About sentence of the kind written), then the data grows: the CALYPSO snapshot joins it.

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz --name mb \
    --kind trade --match PRODUCT_TYPE --key TRADE_ID --out data/build/mb
4980 documents, 1 kind(s), 5 group(s)
...
$ drishti-console/.venv/bin/python tools/drishti.py pack regenerate data/build/mb data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz \
    data/testdata/trades/calypso/snapshot-2026-09-22.jsonl.gz --dry-run
pack regenerate --dry-run: data/build/mb
  add         sutras/trade/trade-abs.v1.sutra.yaml
  add         sutras/trade/trade-amort-bond.v1.sutra.yaml
  add         sutras/trade/trade-buy-sell-back.v1.sutra.yaml
  add         sutras/trade/trade-call-account.v1.sutra.yaml
  add         sutras/trade/trade-callable-bond.v1.sutra.yaml
  add         sutras/trade/trade-cds-index.v1.sutra.yaml
  add         ... and 351 more
  refresh     README.md
  refresh     pack.yaml
  refresh     samples/catalog.json
  refresh     samples/trade.jsonl
  merge       config/about.yaml   (your edit and the new text merged cleanly)
  keep        sutras/trade/trade-comswap.v1.sutra.yaml   (your edit kept; the generated text did not change)
summary: 357 add, 1 keep, 1 merge, 4 refresh; 34 unchanged
(exit 0)
```

The priority edit is kept, the About edit is merged with the regenerated skeleton. Without `--dry-run` the same plan is written, then
`generated/` is replaced by what was just generated (not by the merged result, so the next run still sees your edits as yours). A real
conflict, here the generator now writes `priority: 20` on the line you changed to 15:

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack regenerate data/build/mb data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz \
    data/testdata/trades/calypso/snapshot-2026-09-22.jsonl.gz --priority 20
pack regenerate: data/build/mb
  add         sutras/trade/trade-abs.v1.sutra.yaml
  ...
  add         ... and 351 more
  refresh     README.md
  refresh     pack.yaml
  refresh     sutras/trade/trade-callnot.v1.sutra.yaml
  refresh     sutras/trade/trade-cash.v1.sutra.yaml
  refresh     sutras/trade/trade-comfut.v1.sutra.yaml
  refresh     sutras/trade/trade-commodity.v1.sutra.yaml
  refresh     ... and 2 more
  merge       config/about.yaml   (your edit and the new text merged cleanly)
  conflict    sutras/trade/trade-comswap.v1.sutra.yaml   (1 conflict(s): markers written, resolve them by hand)
summary: 357 add, 1 conflict, 1 merge, 8 refresh; 30 unchanged
attention: 1 file(s) need you (search for '<<<<<<<'), then  drishti.py pack check data/build/mb
(exit 1)

$ grep -n -A4 '<<<<<<<' data/build/mb/sutras/trade/trade-comswap.v1.sutra.yaml
5:<<<<<<< yours (the pack)
6-match: { kind: trade, where: "$.PRODUCT_TYPE == 'ComSwap'", priority: 15 }
7-=======
8-match: { kind: trade, where: "$.PRODUCT_TYPE == 'ComSwap'", priority: 20 }
9->>>>>>> regenerated from the new data
```

Options not given on the command line (`--key`, `--date`, `--match`, `--priority`, `--samples`, `--version`...) are taken from the
ones the pack was generated with. The command takes the same options as `pack new` (it writes no data: use `data ingest` for the
lake) plus `--dry-run` and `--json`. It needs the jar like `pack new`, because it lints and tests the regenerated pack in a scratch
folder first. Exit codes: 0 merged cleanly, 1 conflicts (or `no-baseline` files), 2 usage.

### `pack diff`: what changed between two versions, and what breaks

The same pack, bundled before the CALYPSO data joined it (`pack bundle data/build/mb --out data/build/dist`), against the folder
after `pack regenerate ... --priority 20 --version 1.1.0` (no hand edits this time, so no conflict):

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack diff data/build/dist/mb-1.0.0.tar.gz data/build/mb
pack diff: mb 1.0.0 -> mb 1.1.0

SELECTION (56)
  sutra added                trade-abs   sutras/trade/trade-abs.v1.sutra.yaml: match $.PRODUCT_TYPE == 'ABS', priority 20
  sutra added                trade-amort-bond   sutras/trade/trade-amort-bond.v1.sutra.yaml: match $.PRODUCT_TYPE == 'AMORT_BOND', priority 20
  ...
  screen selection changes   trade-callnot   priority 10 -> 20
  screen selection changes   trade-cash   priority 10 -> 20
  screen selection changes   trade-comfut   priority 10 -> 20
  screen selection changes   trade-commodity   priority 10 -> 20
  screen selection changes   trade-comswap   priority 10 -> 20

CHANGE (12)
  version                    mb   1.0.0 -> 1.1.0
  sutra changed              trade-callnot   content changed
  ...
  glossary added             trade.COUNTERPARTY_COUNTRY_OF_RISK
  glossary added             trade.COUNTERPARTY_UEN
  ...
summary: 56 selection, 12 change  (fail-on breaking: ok)
(exit 0)
```

A pack with conflict markers still in it cannot be diffed (`drishti: ...: while scanning a simple key`, exit 2): resolve them first.

`OLD` and `NEW` are pack folders, `pack make` output folders or bundles (`.tar.gz`). Findings have four levels: **breaking** (a kind or
a mnemonic removed or renamed: monitors, workspaces and alerts name them), **selection** (a Sutra added or removed, its match `where`,
`kind` or `priority` changed: other documents get other screens), **layout** (`connectors`, `routes`, `ingest`, `columns` of
`pack.yaml`: the data layout) and **change** (Sutra content, About text, glossary, panel notes, version). `--unified` adds the unified
diff of each changed Sutra; `--fail-on breaking|any|none` (default `breaking`) decides the exit code (1 when a finding of that level
exists); `--json` prints `{counts, findings: [{level, what, name, detail}], failed}`. Use it in CI before `pack deploy`.

### `pack catalogue`: a readable catalogue for business review

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack catalogue data/build/curves-demo --out data/build/catalogue
pack catalogue: 1 kind(s), 4 Sutra(s) -> data/build/catalogue/catalogue.md
```

The page (or `catalogue.md` with the default `--format md`; `--format html` writes `index.html`) lists, per kind, the About sentence and
mnemonic, the glossary (term, meaning, unit, sign, notes, values) and the fields the Sutras show that no entry explains yet; then every
Sutra with what it shows (description, key figures, panels with their kind and title) and the rule that selects it. The start of the
Markdown:

```text
# Curves demo

Pack `curves-demo` version 1.0.0. Generated from ir-curve by tools/packgen/pack_from_jsonl.py.

1 kind(s), 4 Sutra(s). Text in `${...}` is filled in from the document when a screen is shown.

## Ir curve (`ir-curve`, mnemonic IC)

TODO: one sentence about a ir-curve, e.g. ${$.CURVE_ID}.

### Fields and glossary

| Field | Term | Meaning | Unit | Sign, notes |
|---|---|---|---|---|
| AS_OF | AS_OF | TODO: what this field means. |  |  |
| BUILT_AT | BUILT_AT | TODO: what this field means. |  |  |
...

Shown but not yet explained: `CURVE_ID`

### Screens for ir-curve

#### ir-curve-cross-currency-basis

Sutra for ir-curve documents where CURVE_TYPE=Cross-currency basis.

- **Selected when:** `$.CURVE_TYPE == 'Cross-currency basis'`, priority 10
- **Key figures:** Curve type, Index, Ten y, Slope 2s 10s, As of, Built at
- **Panels:** Details (kv), Points (ladder), Forwards (line), How this view was built (provenance), Linked entities (links)
```

`--shots` runs `sutra preview` over the pack (needs the jar) and links each Sutra's HTML snapshots from `previews/`. About text not yet
written shows as its `TODO` placeholder, so the catalogue doubles as a review checklist.

### `pack i18n`: translate the About text through a spreadsheet

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack i18n export data/build/curves-demo --lang fr --out data/build/fr.csv
pack i18n export: 20 texts of curves-demo -> data/build/fr.csv  (0 already translated, 20 to do)
next: fill the 'translation' column, then  drishti.py pack i18n import data/build/curves-demo data/build/fr.csv --lang fr

$ drishti-console/.venv/bin/python tools/drishti.py pack i18n import data/build/curves-demo data/build/fr.csv --lang fr
pack i18n import: 0 of 20 texts -> data/build/curves-demo/config/about.fr.yaml
  missing (shown in English): 20
    kinds/ir-curve/title
    kinds/ir-curve/about
    kinds/ir-curve/glossary/AS_OF/term
    kinds/ir-curve/glossary/AS_OF/means
    ...
    ... and 12 more
(exit 0)
```

(Imported before any translation was filled in: every text is missing, which is a warning, so the exit code is 0; an extra key or a
`${...}` expression changed in a translation is an error, exit 1.)

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
drishti.py pack bundle config/packs/trading --out dist                  # trading-1.0.0.tar.gz + .sha256 + .manifest.json (runs pack check first)
drishti.py pack verify dist/trading-1.0.0.tar.gz                # sha256, manifest checksums, schema, server version, sutra lint + test
drishti.py pack deploy dist/trading-1.0.0.tar.gz --to /opt/drishti/packs --backup /opt/drishti/backups [--dry-run]
drishti.py pack rollback trading --to /opt/drishti/packs --backup /opt/drishti/backups [--version 1.0.0]
# real output of each, on the shipped trading pack: OPERATIONALISING.md sections 3 to 5 and 10
```

`pack bundle` also takes `--no-check` (skip the `pack check` that runs first), `--requires-server VERSION` (the minimum server version written into the manifest; default this checkout's) and `--json`.
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
  drishti.py pack publish config/packs/my-bank --registry /srv/registry --key keys/me.pem --publisher me
```

```bash
drishti-console/.venv/bin/python tools/drishti.py pack keygen --out data/build/keys/me.pem
drishti-console/.venv/bin/python tools/drishti.py pack publish data/build/curves-demo --registry data/build/registry \
    --key data/build/keys/me.pem --publisher me
```

```text
private key: data/build/keys/me.pem (keep it secret; it signs packs)
public key:  MCowBQYDK2VwAyEAaCsj0I61FgZt234CuN6tg+juf4VrdSiinu2kgDgYXJ0=
servers trust it with:  drishti.packs.registry.trusted-keys.<publisher>: <public key>
```

```text
published curves-demo 1.0.0 (53987 bytes, sha256 d0c68128a431…) signed by me
```

(Your key, and so the public key and the checksum, differ: a new key pair is made each time.)

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
$ drishti-console/.venv/bin/python tools/drishti.py data profile data/testdata/trades/murex/snapshot-2026-09-22.jsonl.gz --kind trade
data profile: 1 file(s), 4933 document(s), 1 kind(s)

== trade: 4933 document(s), 107 field(s)
  field                                           type            cover  distinct  examples
  SOURCE_SYSTEM                                   string           100%         1  MUREX
  SOURCE_UPDATE_TIME                              string           100%         1  2026-09-22T18:30:00Z
  TRADE_DATE                                      string           100%       292  2025-07-25, 2025-02-06, 2026-09-18
  INPUT_DATE                                      string           100%       292  2025-07-25, 2025-02-06, 2026-09-18
  TRADE_ID                                        string           100%      4933  MX-20000001, MX-20000002, MX-20000003
  TRADE_STATUS                                    string           100%         3  AMENDED, VALIDATED, MATURED
  ...
  LEGS[].CASH_FLOWS[].RESETS                      array            100%         0
  EVENTS                                          array            100%         0
  ...
  key candidates (present and unique everywhere):
    TRADE_ID                     exact     present in all 4933 documents, 4933 different values
    TRANSACTION_ID               exact     present in all 4933 documents, 4933 different values
  date candidates (ISO dates in at least 95% of the documents):
    SOURCE_UPDATE_TIME           1 date(s) 2026-09-22 .. 2026-09-22, coverage 100%
    TRADE_DATE                   292 date(s) 2024-04-16 .. 2026-09-22, coverage 100%
    INPUT_DATE                   292 date(s) 2024-04-16 .. 2026-09-22, coverage 100%
    START_DATE                   259 date(s) 2024-04-18 .. 2026-09-24, coverage 100%
    MATURITY_DATE                361 date(s) 2026-09-30 .. 2038-02-01, coverage 100%
  match candidates (split the kind into screens; score 0..1):
    PRODUCT_TYPE                 1.000  coverage 100%, 74 values over 4933 documents (average 66.7 per value, evenness 1.00; largest group IRS_FIXFLOAT = 72); name suggests a category
    PRODUCT_SUB_TYPE             1.000  coverage 100%, 74 values over 4933 documents (average 66.7 per value, evenness 1.00; largest group Interest rate swap (fixed/float) = 72); name suggests a category
    COUNTERPARTY                 0.985  coverage 100%, 18 values over 4933 documents (average 274.1 per value, evenness 0.98; largest group CP-SUMMIT = 759)
    COUNTERPARTY_UEN             0.985  coverage 100%, 18 values over 4933 documents (average 274.1 per value, evenness 0.98; largest group UEN0100592 = 759)
    PORTFOLIO                    0.962  coverage 100%, 15 values over 4933 documents (average 328.9 per value, evenness 0.93; largest group BOOK-RATES-2 = 635)
    TT_BOOK                      0.962  coverage 100%, 15 values over 4933 documents (average 328.9 per value, evenness 0.93; largest group BOOK-RATES-2 = 635)
  suggested:
    python3 tools/drishti.py pack make data/testdata/trades/murex/snapshot-2026-09-22.jsonl.gz --kind trade --match PRODUCT_TYPE --key TRADE_ID --date SOURCE_UPDATE_TIME --name trade-pack
```

Here `SOURCE_UPDATE_TIME` (the extract's time stamp: one date in the file, the snapshot's business day) is the right business date, and
`PRODUCT_TYPE` the match column; on the ENDUR file, whose `SOURCE_UPDATE_TIME` is empty, no field holds the business date (see the
`pack make` note above).

A **key** is a scalar present in every document with a different value in each (`exact` while the values fit in `--max-distinct`,
`probable` beyond; id-like names first). A **date** is a field whose every value is an ISO date, present in at least 95% of the
documents. A **match** column splits the kind into screens; the score (0 to 1) is `coverage x (1 - distinct/documents) x min(1,
average group size/5) x min(1, distinct/8) x (0.5 + 0.5 x evenness)`, times 1.15 when the name ends in type, class, category, kind,
family, product, segment, region or status, times 0.9 per nesting level; each line says why. It is a ranking, not a decision: here
`PRODUCT_TYPE` gives 74 screens (one per product), `COUNTERPARTY` 18 and `PORTFOLIO` 15, and you choose. `--json` prints every field and
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
  drishti.py data ingest --from data/new --pack config/packs/my-bank --lake data/delta
  drishti.py data ingest --from day1.jsonl --from day2.jsonl --pack config/packs/my-bank --store files --root data/files --dry-run
```

`--mode overwrite-dates` (the default) replaces only the dates in your input, so re-running a day is safe; `append` adds;
`replace` rebuilds the kind. Documents without a date or id are counted and left out, never silently dropped.

The curves of the test data into the files store of the `curves-demo` pack made [above](#pack-new-a-complete-pack-from-json-lines)
(its `ingest:` block gives the key `CURVE_ID` and the date `AS_OF`; `--kind` because the file names are not kinds):

```bash
# look first
drishti-console/.venv/bin/python tools/drishti.py data ingest --from data/testdata/market-data/ir-curve --kind ir-curve \
    --pack data/build/curves-demo --store files --root data/build/files2 --dry-run
```

```text
kind                business date        rows   (dry run)
ir-curve            2026-09-22             28
ir-curve            2026-09-23             28
...
ir-curve            2026-09-30             28
total rows: 196
```

```bash
drishti-console/.venv/bin/python tools/drishti.py data ingest --from data/testdata/market-data/ir-curve --kind ir-curve \
    --pack data/build/curves-demo --store files --root data/build/files2
```

```text
  ir-curve 2026-09-22: 28 rows -> data/build/files2/curves-demo/2026-09-22/ir-curve.jsonl
  ir-curve 2026-09-23: 28 rows -> data/build/files2/curves-demo/2026-09-23/ir-curve.jsonl
  ...
  ir-curve 2026-09-30: 28 rows -> data/build/files2/curves-demo/2026-09-30/ir-curve.jsonl
kind                business date        rows
ir-curve            2026-09-22             28
...
ir-curve            2026-09-30             28
total rows: 196
```

For Delta (`--store delta --lake DIR`, the default store) the console's interpreter has `deltalake` and `pyarrow` already.

### `data ingest --watch`: a drop folder that feeds the lake

Instead of `--from`, point `--watch` at a folder where files arrive (an export job, an SFTP drop). The watcher polls for
`*.jsonl`, waits until a file has stopped growing (size and mtime unchanged for `--stable-seconds`, default 2, so a file still being
written is never read), ingests each file **once** with the options you gave (`--pack` or `--domain`, `--store`, `--lake` or
`--root`...), and writes a state file (path, size, mtime, sha256) **only after the ingest succeeded**: a crash re-ingests the file
that was in flight and never skips one. A file whose content later changes (another sha256) is ingested again; one that was only
touched is not. A file that fails (a bad option, a full disk) is left where it is, logged, and tried again when it changes or on
the next run; bad lines are logged and make `--once` exit 1.

| Option | Meaning |
|---|---|
| `--watch DIR` | the drop folder (`-r` also looks in subfolders) |
| `--once` | one pass and exit, for cron; exit 1 if any file failed or had bad lines |
| `--interval S` | seconds between passes when watching (default 10) |
| `--stable-seconds S` | how long a file must stay unchanged before it is read (default 2) |
| `--done-dir DIR` | move each finished file here (a name clash gets a timestamp); without it the file stays and the state file marks it |
| `--state FILE` | the state file (default `<lake or files root>/.drishti-ingest-state.json`) |

The default mode of a watcher is `append` (each file is a new batch); use `--mode overwrite-dates` when every file holds complete
days, so a corrected file replaces its dates instead of adding to them. Real output:

```bash
python3 tools/drishti.py data ingest --watch drop --once --domain demo --key id --date bd --store files --root files --done-dir done
2026-10-05T16:44:48 INFO  watching drop for *.jsonl (mode append, state files/.drishti-ingest-state.json)
2026-10-05T16:44:48 INFO  new file trade.jsonl (72 bytes): ingesting
2026-10-05T16:44:48 INFO  trade.jsonl: 2 row(s) ingested
2026-10-05T16:44:48 INFO  trade.jsonl: moved to done/trade.jsonl
2026-10-05T16:44:48 INFO  pass finished: 1 file(s) ingested, 0 with problems
```

Cron: `*/5 * * * * cd /opt/drishti && python3 tools/drishti.py data ingest --watch /data/drop --once --pack config/packs/my-bank --lake /data/delta --done-dir /data/done`.
As a service run it without `--once`; Ctrl-C stops it cleanly.

### `data landed` and `data loads`: tell the server a batch landed

Your ETL loads the data; Drishti reads it where it lives. When a batch is in place, **one call** makes the server refresh what it caches, check
the data is readable for the date, evaluate the alert rules on it and tell the people the pack names. See
[DATA_LOADS.md](DATA_LOADS.md) for the concepts, the API, expectations and late data, and recipes (Airflow, cron, DishtaYantra).

```
drishti.py data landed --pack P --kind K --date D [--status ready|failed] [--rows N] [--rejected N]
                       [--batch ID] [--source S] [--note TEXT] [--json]
drishti.py data loads  --pack P [--kind K] [--date D] [--status ready|failed] [--limit N] [--expectations] [--json]
```

`--date` is `yyyy-MM-dd`, or `today` / `yesterday`. The same pack, kind, date and batch announced twice is recorded once (safe to retry). Exit
`1` when a `ready` load is **not verified** (the data is not readable for that date) or the server refuses it; `0` otherwise. It needs an
administrator, or a personal token with the `loads:write` scope (`403 DRS-5002 this token lacks the scope loads:write` otherwise).

```
$ python tools/drishti.py data landed --pack market-risk --kind stress-result --date 2026-10-05 --rows 840 --rejected 0 --batch eod-20261005-1
stress-result for 2026-10-05 loaded: 840 rows, 0 rejected; 0 alerts fired
  load muwrjavp-5  status ready  verified verified  attempt 1
  record  ok      recorded
  refresh ok      caches dropped: demo, file, risk-store
  verify  ok      80 stress-result entities on 2026-10-05
  alerts  ok      0 alerts fired
  smoke   ok      opened 2 of 2 sample entities
  notices ok      1 person told
$ python tools/drishti.py data loads --pack market-risk --limit 2 --expectations
received             kind              date        status        rows    rej  verified  alerts told  batch / source
2026-10-06 14:17:28  stress-result     2026-10-05  ready          840      0  verified       0    1  eod-20261005-1
...
expectations:
  2026-10-06  var               by 23:59 America/New_York    failed
```

> `data ingest`, `data ingest --watch` and `pack make` stage data for a demo, a small team or a proof of concept. They are **not** a production
> ETL: in production your own ETL loads the store and ends by calling `data landed`.

### `testdata capture-market`: the market snapshot for offline demos

On a machine with internet, captures the news-and-markets pack's real quotes (the twenty companies, a year of daily bars) and
headlines (the companies and the fifteen topics) through Drishti's own `yfinance` and `news` connectors, on a scratch server it starts
from the built jar (`--server URL` uses yours), two seconds apart with backoff on 429, into
`data/testdata/news-and-markets/{quote,news}/snapshot-<date>.jsonl.gz`; `lake demo` then builds them into the tables `news-store`
serves offline (`DRISHTI_OFFLINE=true`). `--dry-run` lists the 91 ids without fetching. Everything else:
[data/testdata/README.md](../../data/testdata/README.md#the-market-snapshot-news-and-markets-offline).

```bash
drishti-console/.venv/bin/python tools/drishti.py testdata capture-market
```

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
drishti-console/.venv/bin/python tools/drishti.py server health
```

On the quick-start server (the demo lakes, sign-in on, the token in `DRISHTI_TOKEN`) after tutorials 4 and 5, which add the
`desk-quotes` pack and its connectors, and with a connector file of [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) that points
at a folder this machine does not have:

```text
status DEGRADED  version 2.5.0  java 21.0.12.1  uptime 2111s
packs 8  sources 17 (degraded 0, down 2)  packs with problems 1  failed to start 0
  desk-quotes-store           DOWN      DOWN: cannot reach <repository>/data/delta/desk-quotes (engine: native)
  orders-lake                 DOWN      DOWN: no directory /srv/lake/orders
  civic-store                 UP        UP (engine: native)
  collateral-store            UP        UP (engine: native)
  credit-store                UP        UP (engine: native)
  desk-quotes                 UP        UP
  file                        UP        UP
  legal-store                 UP        UP (engine: native)
  macro-store                 UP        UP (engine: native)
  market-store                UP        UP (engine: native)
  news-store                  UP        UP (engine: native)
  reference-store             UP        UP (engine: native)
  risk-store                  UP        UP (engine: native)
  trade-calypso               UP        UP (engine: native)
  trade-endur                 UP        UP (engine: native)
  trade-murex                 UP        UP (engine: native)
  trade-pages                 UP        UP
(exit 1)
```

The other 14 sources are the seven packs' own, all `UP`; [TOOLS.md section 5.4](TOOLS.md#54-server-and-doctor) has another capture.

The exit code is 1 unless the status is `OK` (here two sources are down, so `DEGRADED`). `--json` prints the server's own
`GET /api/v1/admin/health` document.

**`drishti.py server caches --help`**

```text
usage: drishti.py server caches [-h] [--server SERVER]
                                [--token-file TOKEN_FILE] [--user USER]
                                [--timeout TIMEOUT] [--json]
                                [{list,purge}] [name]

server caches [purge NAME|all]: list the caches, or drop one (grouped pages on disk are rebuilt at once)

positional arguments:
  {list,purge}
  name                  the cache to purge: a connector's name, engine, or all
```

`server caches` (or `server caches list`) prints one line per cache (the engine's and each connector's) with its type and its
counts and sizes, the same as **Admin → Caches** (`GET /api/v1/admin/caches`); `--json` prints the server's document. `server caches
purge NAME` drops one: for a Delta connector its entries in the shared memory caches, its document cache on disk, its id map files
and the parsed Parquet footers; for a derived connector (`trade-pages`) its grouped pages, the latest date rebuilt from the lake at
once while the old pages serve. `purge all` drops every cache. Everything purged is rebuilt from the data at the next read, so a
purge is safe at any time; it needs an administrator and is written to the audit log.

```bash
drishti-console/.venv/bin/python tools/drishti.py server caches purge trade-pages
```

`server packs list|load|unload|on|off` manage packs. **load** and **unload** edit the overlay file
(`data/packs/added.yaml`) after the server checks the pack the same way it does at start, then put the pack to use at once, with no restart;
**on**/**off** switch a loaded pack for users without a restart. Unloading only works for packs an administrator loaded
(packs in `DRISHTI_PACKS` stay).

**`drishti.py server packs load --help`**

```text
usage: drishti.py server packs load [-h] [--server SERVER]
                                    [--token-file TOKEN_FILE] [--user USER]
                                    [--timeout TIMEOUT] [--json]
                                    name

load a pack that is on disk (it takes effect at once, with no restart)

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
drishti-console/.venv/bin/python tools/drishti.py server packs list
```

```text
pack                  version  loaded  on   added  kinds
core                  1.0.0    yes     yes         10
market-data           1.0.0    yes     yes         20
trading               1.0.0    yes     yes         5
counterparty-risk     1.0.0    yes     yes         10
market-risk           1.0.0    yes     yes         6
world-economy         1.0.0    yes     yes         13
news-and-markets      1.0.0    yes     yes         4
desk-quotes           1.0.0    yes     yes  yes    1
(exit 0)
```

The seven packs come from `DRISHTI_PACKS`; `desk-quotes` was **added** by an administrator (tutorial 5) and is in the overlay file.

```bash
python3 tools/drishti.py server packs load my-bank       # POST /api/v1/admin/packs/my-bank/load
python3 tools/drishti.py server packs off  my-bank       # PUT  /api/v1/admin/packs/my-bank  {"enabled": false}
```

### `server smoke`: does the server serve what it should

One command that opens real views: health `UP`; each pack (`--pack`, repeatable; default every enabled pack) loaded and enabled;
no Sutra problems (the list the Admin pages show); then, for every kind of those packs, `--ids N` sample ids (from the search API)
opened on the latest business date and on `--dates N - 1` earlier ones, each expected to answer 200 with at least one panel that has
content. Prints a table (timings per view) or `--json`; exit 1 on any failure. A view that does not exist on an *earlier* date is
a skip, not a failure. The pack list is the administrator's API; with a read-only token pass `--kind K` instead.

```
$ python3 tools/drishti.py server smoke --pack counterparty-risk --ids 1 --dates 2
OK   health                      status UP
OK   pack counterparty-risk      loaded, enabled, v1.0.0, 8 kinds, no Sutra problems

kind                  id                        date        panels  empty  ms       result
netting-set           NS-ALDERSHOT-FRA          2026-10-05  5       0      61.4     OK
netting-set           NS-ALDERSHOT-FRA          2026-10-02  5       0      27.5     OK
credit-limit          LIM-ALDERSHOT             2026-10-05  4       0      9.2      OK
credit-limit          LIM-ALDERSHOT             2026-10-02  4       0      9.0      OK
...

16 views, p50 10.2 ms, max 61.4 ms
smoke: passed
```

### `doctor`: is this machine ready

Green, yellow and red lines, each with a fix; exit 1 on any red. It checks Java (on `JAVA_HOME` or the PATH, 21 or newer, vendor),
the server jar (and its version), Python and the optional libraries (`pyyaml`, `deltalake`, `pyarrow`, with install hints), the
repository layout (`config/packs/`, `drishti-console/.venv`), the directories you configured (`DRISHTI_DELTA_ROOT`, `DRISHTI_FILES_ROOT`,
`DRISHTI_PACKS_INSTALLED`, `DRISHTI_PACKS_DIR`: readable, writable), and ports 18480 and 17480 (override with `DRISHTI_DOCTOR_PORTS=18971:server,17971:console` to check other ports instead; free, or in use by Drishti; only a
GET of `/actuator/health` on localhost, nothing is killed or changed). With `--server` it also checks reachability, the version
and whether the token is accepted, with the exact id, scopes and expiry of the token the call used (`GET /api/v1/me/token`; a signed-in session has none).

```
$ python3 tools/drishti.py doctor --server http://localhost:18971
GREEN  java                    /usr/lib/jvm/java-21-openjdk-amd64/bin/java: Java 21.0 (Ubuntu)
GREEN  server jar              ~/drishti/drishti-server/target/drishti-server-2.6.0-exec.jar (version 2.5.0)
GREEN  python                  /usr/bin/python3: Python 3.14.4
GREEN  python pyyaml           installed
YELLOW python deltalake        not installed (needed for data ingest --store delta)
                               fix: pip install deltalake   or run through uv: uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py ...
YELLOW python pyarrow          not installed (needed for data ingest --store delta)
                               fix: pip install pyarrow   or run through uv: uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py ...
GREEN  repo config/packs/             ~/drishti/packs
YELLOW console venv            ~/drishti/drishti-console/.venv
                               fix: cd drishti-console && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
GREEN  configured directories  none of DRISHTI_DELTA_ROOT, DRISHTI_FILES_ROOT, DRISHTI_PACKS_INSTALLED is set
GREEN  port 18971              in use by Drishti: status UP
GREEN  port 17971              free (the default console port)
YELLOW server                  http://localhost:18971: version 2.6.0, java 21.0.12.1, status DEGRADED
YELLOW token                   none given: calls run anonymously or as --user
                               fix: set DRISHTI_TOKEN or --token-file (My account, API tokens)
doctor: 0 red, 5 yellow
```

### `view get` and `view explain`: a view in the terminal

`view get KIND/ID [--date D] [--table|--json]` prints the strip and every panel as text tables (key/value fields, tables and ladders, timelines,
waterfalls and bars, series summarised as first/last/min/max, graphs and links); `--json` prints the server's view unchanged.
`view explain KIND/ID [--date D] [--panel ID]` prints the About answer: what you are looking at, the glossary of every field
shown, where the data came from, and why this layout (the Sutra that matched and the match trace). Both are the **caller's**
view: field masks and entitlements of your token apply exactly as in the browser.

```
$ python3 tools/drishti.py view get counterparty/CP-ALDERSHOT
Counterparty and legal · Counterparty  CP-ALDERSHOT   [CPTY]

Key figures
  figure    value
  --------  -----------------------
  Name      Aldershot Pension Trust
  LEI       549300H5KZ2QJHEAMB55
  Rating    AA-
  Sector    Pensions
  Country   GB
  Type      Pension fund
  Net MTM   +13,812,754
  PFE peak  145.5m

Netting sets  (table, nettingSets, F2)
  Netting set       Agreement           Trades      Net MTM
  ----------------  ------------------  ------  -----------
  NS-ALDERSHOT-FRA  AGR-ALDERSHOT-ISDA       3     −339,550
  NS-ALDERSHOT-LDN  AGR-ALDERSHOT-ISDA      20   −4,886,451
  NS-ALDERSHOT-NY   AGR-ALDERSHOT-ISDA      22  +18,471,892
  NS-ALDERSHOT-TKY  AGR-ALDERSHOT-ISDA       1     +566,863

KYC and classification  (kv, kyc)
  field                value
  -------------------  ----------
  Status               Approved
...
$ python3 tools/drishti.py view explain counterparty/CP-ALDERSHOT
What you are looking at: counterparty CP-ALDERSHOT
  A legal entity the bank trades with: identifiers, rating, sector, country and parent group.

Glossary
  field        shown in     means
  -----------  -----------  --------------------------------------------------------------------
  Name         strip        What the record is called, for people to read.
  Country      strip        The country, as a two-letter ISO 3166 code where the data holds one.
  Type         strip        The class of thing the record is, within its kind.
  Netting set  nettingSets  The value that names this record uniquely within its kind.
  Status       kyc          Where the record is in its life, such as open, closed or pending.

Where the data came from
  item        value
  ----------  ------------------------------
  source      counterparty-master
  generation  1
  fetchedAt   2026-10-05T21:47:04.727572297Z
  updatedAt   2026-10-05T21:47:04.411564680Z
  current     True
  live        False
  stale       False
  health      up

Why this layout: Sutra counterparty v1 + inference (a Sutra plus inference for what it left out)
  Sutra counterparty v1 of pack core, priority 10, where (no where: matches every document of the kind)
  Match trace: counterparty (priority 10) is the chosen Sutra; 0 other Sutra(s) were tried and did not match (first 10 shown)
  sutra           priority  where  result
  --------------  --------  -----  ------
  * counterparty  10               true

Next: F2 Netting sets; F3 Group hierarchy; F7 Counterparty group; F8 Impact; F9 Raw JSON
```

### `server packs deploy`, `history`, `rollback` and `datasource`

Admin → Packs from the terminal ([the whole story with pictures: OPERATIONALISING.md, section 17](OPERATIONALISING.md#17-deploy-from-admin--packs-connectors-history-and-roll-back)).
You need an administrator; a personal API token needs the **packs:admin** scope. The archive carries the pack only, never data.

**`server packs deploy ARCHIVE [--preview] [--accept-breaking] [--sha256 HEX] [--signature-file F --publisher P] [--wait SECONDS]`**
uploads the `.tar.gz` (or `.zip`) as the raw request body and prints what the server checked (archive, checksum, signature, unpack safety, `pack.yaml`,
manifest and every file's checksum, the server version it needs, Sutra lint and tests, the packs it extends) and the **preview**: the difference from the
running version at four levels, breaking first. A `.sha256` file beside the archive (`pack bundle` writes it) is sent too, so the server checks it arrived intact.

- `--preview` stops there and discards the upload: nothing changes.
- Without it the pack is deployed: the replaced version is kept, the new version is put to use at once (no restart) and the old files are put back if it cannot be used. A **breaking** change
  (a kind or mnemonic removed or renamed) makes the command print the preview and exit 1 until you add `--accept-breaking`.
- `--wait N` waits up to N seconds for the server to answer again after the restart (exit 1 if it does not).
- Exit: 0 deployed or previewed; 1 refused by a check, breaking changes not accepted, or the server not back; 2 usage (no such file).
- `--json` prints `{verification, result}`; the verification is the server's own answer ([API_GUIDE.md](API_GUIDE.md#deploying-a-pack-archive-and-the-packs-connectors)).

```text
$ python3 tools/drishti.py server packs deploy dist/helpdesk-0.2.0.tar.gz
archive helpdesk-0.2.0.tar.gz (5819 bytes, sha256 aee6b807bb8149e8...)
  ok    archive         5819 bytes, sha256 aee6b807bb8149e8...
  ok    checksum        matches the sha256 you gave
  warn  signature       not signed; sign it with a publisher key listed in drishti.packs.registry.trusted-keys to prove where it came from
  ok    unpack          21 files, 18 KB; no path leaves the pack folder, no links
  ok    pack.yaml       helpdesk 0.2.0
  ok    manifest        20 files listed; every checksum matches; nothing unlisted
  ok    server version  needs >=1.0.0, this is 2.6.0
  ok    sutra lint      1 Sutra file(s) passed
  ok    sutra test      1 Sutra file(s) passed
  ok    dependencies    extends no other pack

Preview: helpdesk 0.1.0 (loaded) -> 0.2.0 (newer)
  BREAKING (2)   <- monitors, workspaces, alerts or saved links may stop working
    kind removed               agent-load   monitors, workspaces and alerts that name it stop working
    mnemonic removed           LOAD   kind agent-load
  CHANGE (1)
    version                    helpdesk   0.1.0 -> 0.2.0
  summary: 2 breaking, 1 change

not deployed: 2 breaking change(s); read them above, then run again with --accept-breaking
$ python3 tools/drishti.py server packs deploy dist/helpdesk-0.2.0.tar.gz --accept-breaking --wait 90
...
deployed helpdesk 0.2.0 (replacing 0.1.0): Applied now; no restart.
the server is back
```

**`server packs history [--pack P] [--limit N]`** lists deployments, rollbacks and reverted attempts, newest first, and the versions kept for a rollback;
**`server packs rollback NAME [--version V]`** puts a kept version back (`--version shipped`: the copy that ships with the server; none: the newest kept).

```text
$ python3 tools/drishti.py server packs history
  when (UTC)           action  pack      version  replaced  by
  -------------------  ------  --------  -------  --------  ---------
  2026-10-05 21:45:22  deploy  helpdesk  0.2.0    0.1.0     anonymous
  2026-10-05 21:45:13  deploy  helpdesk  0.1.0              anonymous
  kept for rollback, helpdesk: 0.1.0
$ python3 tools/drishti.py server packs rollback helpdesk --version 0.1.0
rolled helpdesk back to 0.1.0 (replacing 0.2.0): Applied now; no restart.
```

**`server packs datasource get PACK`** is the pack's connectors, **read only**: which connectors the pack names, the state of each, the plugin, the kinds
this pack takes from it and what defines it (a file in the connector folder, the pack's own template, the server's configuration, or nothing). A pack names its
connectors and routes its kinds to them; the settings belong to the site, in one YAML file per connector in `config/connectors/`, and are changed with the
[`connector` commands](#connector-list-get-apply-delete-test-plugins-enable-disable-reset-history) below or in Admin → Connectors. The earlier `datasource set`, `test` and
`reset` are **removed** (so is the `PUT`, `DELETE` and `.../test` of `/api/v1/admin/packs/{name}/datasource` they called); the settings saved by them are moved into
connector files at the first start of the new server ([OPERATIONALISING.md, 17.5](OPERATIONALISING.md#175-where-a-pack-reads-its-data-connectors)).
Exit: 0 every connector the pack names is configured; **1 while one is not** (or the server refused); 2 usage.

```text
$ python3 tools/drishti.py server packs datasource get desk-demo
connectors of desk-demo   [files in /srv/drishti/config/connectors; change them with `drishti.py connector ...`]
  connector     state           plugin  kinds from this pack  defined by          problems
  ------------  --------------  ------  --------------------  ------------------  ----------------------------------------
  desk-quotes   running         file    quote                 file
  desk-archive  not configured          quote-archive         nothing defines it  connector desk-archive is not configured
  desk-archive is not configured: create it with `drishti.py connector apply desk-archive.yaml` or in Admin -> Connectors
[exit 1]
```

### `connector`: list, get, apply, delete, test, plugins, enable, disable, reset, history

Admin → Connectors from the terminal ([the model, the file format and every store's example: CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)).
A **connector** is one YAML file in the connector folder (`config/connectors/`, `drishti.sources.connectors-dir`), and **the file name is the connector's name**.
Packs only name the connectors they read through. You need an administrator; a personal API token needs the **`packs:admin`** scope. All commands take the usual
`--server`, `--token-file`, `--user`, `--timeout` and `--json` (machine-readable output). Exit: **0** done; **1** refused by the server (with the `DRS-` code) or, for `test`, the
connector does not work; **2** usage (no such file, unknown option). The server applies a change to that one connector without a restart.

| Command | Does | Options |
|---|---|---|
| `connector list` | every connector: where it is defined, plugin, kinds, state, which packs use it, problems | `--origin file\|pack\|application`, `--status running\|disabled\|failed\|idle\|not_loaded` |
| `connector get NAME` | the effective settings (credentials masked), the file's path, kinds, state and **version** (the ETag) | `--yaml` prints only the file's text |
| `connector apply FILE` | create or change a connector from a YAML file; the name is the file name without `.yaml` | `--name N` (override), `--if-match V` (the version you expect; default: read it now), `--test-first` (try the settings on the real source and apply only if they work), `--confirm` (go ahead although it switches off a connector packs use) |
| `connector delete NAME` | remove the file (its text is kept in `<data dir>/connectors/history/`); refused for a connector a pack's template suggests (reset or disable it) | `--if-match V`, `--confirm` |
| `connector test [NAME]` | start a throwaway instance on the saved settings, or the settings of `--file`, ask it what it holds, close it; exit 1 when it fails | `--file F` |
| `connector plugins [NAME]` | the installed plugins; with a name, the settings that plugin reads (type, default, required, secret) | |
| `connector enable NAME`, `disable NAME` | switch on or off, keeping the rest of the file | `--confirm` |
| `connector reset NAME` | put the pack's suggested default back as the file (your text is kept in the history) | |
| `connector history NAME` | the kept earlier versions of the file (restore them in the page or with `POST .../restore/{id}`) | |

`apply` reads the connector's current version and sends it as `If-Match`, so it is safe in a GitOps job: someone else's edit in between makes the job fail with
`DRS-5032` instead of being overwritten. A new connector needs no version. A literal credential in the file is refused with `DRS-5031`; write `${ENV_VAR}` or `file:/path`.

```text
$ drishti-console/.venv/bin/python tools/drishti.py connector list
24 connector(s) in <repo>/config/connectors   (watching: WATCHING)
  name               origin  plugin    kinds                                                        state           used by            problems
  -----------------  ------  --------  -----------------------------------------------------------  --------------  -----------------  --------
  civic-store        file    delta     jurisdiction,party,office-holder +3                          running         world-economy
  collateral-store   file    delta     collateral-balance,margin-call,simm                          running         counterparty-risk
  credit-store       file    delta     netting-set,credit-limit,exposure-profile +2                 running         counterparty-risk
  desk-ledger        file    jdbc      ledger-entry                                                 down, retrying
  desk-quotes        file    file      desk-quote                                                   running
  desk-quotes-store  file    delta     desk-quote                                                   down, retrying  desk-quotes
  ecb-estr-feed      file    feed      rate-fixing                                                  disabled        market-data
  ecb-fx-feed        file    feed      fx-spot                                                      disabled        market-data
  fred-feed          file    feed      rate-fixing                                                  disabled        market-data
  legal-store        file    delta     agreement,csa                                                running         counterparty-risk
  macro-store        file    delta     economy,macro-indicator,economic-forecast +4                 running         world-economy
  market-store       file    delta     ir-curve,repo-curve,fx-spot +17                              running         market-data
  news               file    news      news                                                         disabled        news-and-markets
  news-store         file    delta     news-topic,listed-company                                    running         news-and-markets
  nyfed-sofr-feed    file    feed      rate-fixing                                                  disabled        market-data
  reference-store    file    delta     counterparty,counterparty-group,issuer +7                    running         core
  risk-store         file    delta     var,stress-scenario,stress-result +3                         running         market-risk
  trade-calypso      file    delta     trade                                                        running         trading
  trade-endur        file    delta     trade                                                        running         trading
  trade-murex        file    delta     trade                                                        running         trading
  trade-pages        file    derived   counterparty-trades,portfolio-trades,legal-entity-trades +1  running         trading
  trading-stream     file    kafka     trade                                                        disabled        trading
  us-treasury-feed   file    feed      ir-curve                                                     disabled        market-data
  yfinance           file    yfinance  quote                                                        disabled        news-and-markets
```

(Captured on a fresh clone after `lake demo` with the seven packs, and two packs a tutorial added: `desk-quotes` and its JDBC `desk-ledger` are down
because their stores are not there. The feeds, `news`, `yfinance` and `trading-stream` (Kafka) ship disabled.) The `origin` is `file` (a file in the folder), `pack` (a
pack's template that has no file yet) or `application` (the deprecated `drishti.sources.connectors` of the server's own configuration, then flagged
`deprecated` under the table).

```text
$ python3 tools/drishti.py connector get site-quotes
connector site-quotes   origin file   plugin file   running   version 3e4b312691e95f5c
  file /srv/drishti/config/connectors/site-quotes.yaml
  kinds quote
  settings (credentials masked):
  setting        value
  -------------  ---------------------
  root           /srv/desk/lake
  lookback-days  10
$ python3 tools/drishti.py connector get site-quotes --yaml
# A site connector: no pack suggests it.
plugin: file
kinds: [quote]
description: Quotes from the desk's drop folder
settings:
  root: /srv/desk/lake
  lookback-days: '10'
```

```text
$ python3 tools/drishti.py connector plugins
  plugin     tls  settings
  ---------  ---  --------
  derived         0
  demo            7
  file            12
  delta           26
  aerospike  yes  26
  redis      yes  36
  mongodb    yes  31
  iceberg         23
  duckdb          17
  feed       yes  19
  kafka      yes  22
  activemq   yes  18
  rabbitmq   yes  19
  s3         yes  23
  rest       yes  17
  jdbc       yes  32
$ python3 tools/drishti.py connector plugins file
plugin file
  setting            type                default    what it is
  -----------------  --------  --------  -------    ------------------------------------------------------------------------------
  root               path      required             Folder of documents
  domain             string                         Domain
  id-field           string                         Field holding the entity id
  rescan-seconds     int                            Rescan interval
  lookback-days      int                            Days of history to index
  max-load-rows      int                            Documents to load at most
  max-document-mb    int                            Largest document
  max-nesting-depth  int                            Deepest nesting
  index-cache-mb     int                            Index cache size
  source-name        string                         The name documents carry as their provenance (default: the connector's name)
  mode.*             string                         Per-kind read mode (mode.<kind>)
  stale-after        duration                       Mark the source stale when it has received nothing for this long (15m, 2h, 1d)
```

`test` reports whether the source is reachable, its health and how long it took, then per kind the newest business dates and how many entities the source holds
(`≥` marks a count that hit the search cap). A failure is explained in words, with a hint for TLS and network errors, and exits 1:

```text
$ python3 tools/drishti.py connector test site-quotes
site-quotes (file): reachable, health UP, 7 ms
  kind   business date  rows  note
  -----  -------------  ----  ----
  quote  2026-10-05     4
  quote  2026-10-02     3
$ python3 tools/drishti.py connector test ledger-db
ledger-db (jdbc): PROBLEM: DOWN: Connection to 127.0.0.1:59997 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections. (reconnecting)
  note: kinds: kind 'ledger-entry' is not owned by a loaded pack (loaded: agreement, bond, book, calendar, cap-vol-surface, ccp, clearing-account, commodity, and 28 more)
[exit 1]
```

Applying a file. The first file below holds a literal password and is refused before anything is written; with a reference it is accepted, and applying it
again with a changed value says which settings changed:

```text
$ python3 tools/drishti.py connector apply orders-lake.yaml
drishti: the server said 422 DRS-5031: DRS-5031 the connector is not valid: settings.password: a credential is never written into a connector file; use an environment reference such as ${PASSWORD} or a file: reference such as file:/run/secrets/password
[exit 1]
$ python3 tools/drishti.py connector apply orders-lake.yaml --test-first
applied orders-lake: running; changed (new connector), root; version 3694fd093d970a61
$ python3 tools/drishti.py connector get orders-lake
connector orders-lake   origin file   plugin file   running   version 3694fd093d970a61
  file /srv/drishti/config/connectors/orders-lake.yaml
  kinds quote
  settings (credentials masked):
  setting  value
  -------  -----
  root     /tmp
$ python3 tools/drishti.py connector apply orders-lake.yaml
applied orders-lake: running; changed lookback-days; version 3ec110fbdf22d988
$ python3 tools/drishti.py connector history orders-lake
  version             kept at                         bytes
  ------------------  ------------------------------  -----
  20261010T140558502  2026-10-10T14:05:58.501804365Z  90
```

Switching off or deleting a connector packs use asks first (`DRS-5032`, exit 1) and goes ahead with `--confirm`; a connector a pack's template suggests is never deleted:

```text
$ drishti-console/.venv/bin/python tools/drishti.py connector disable trade-endur
drishti: the server said 409 DRS-5032: DRS-5032 disabling connector 'trade-endur' would stop serving pack trading (trade); repeat with confirm=true to go ahead
[exit 1]
$ drishti-console/.venv/bin/python tools/drishti.py connector disable trade-endur --confirm
trade-endur is off (disabled)
$ drishti-console/.venv/bin/python tools/drishti.py connector enable trade-endur
trade-endur is on (running)
$ drishti-console/.venv/bin/python tools/drishti.py connector delete trade-endur
drishti: the server said 409 DRS-5032: DRS-5032 connector 'trade-endur' is named by pack trading's template, so it is not deleted: reset it to the pack's default, or disable it
[exit 1]
$ python3 tools/drishti.py connector delete orders-lake
deleted orders-lake (its text is kept in the connector folder's history/ folder)
```

`connector reset NAME` prints `NAME is back to the pack's default (running)` and keeps your previous text in `connector history`. With `--json` every command prints
the server's answer unchanged. Errors: `DRS-5031` invalid (422), `DRS-5032` stale version, existing file without `--if-match`, or in use without `--confirm`
(409), `DRS-5033` no such connector (404); see [API_GUIDE.md](API_GUIDE.md#connectors-apiv1adminconnectors).

### `net`: Drishti*net's config/net/ folder

`drishti.py net list | check | add-peer | set` work on the folder of Drishti*net's files ([NET.md](../admin/NET.md)) on this machine, with no
server needed: they check every file as the server does (the same rules, the same line numbers) and refuse to write a file the server would
refuse. `net members` reads the member table of a running server. The folder is `--dir`, else `DRISHTI_NET_DIR`, else `./config/net` (the server's
`drishti.net.dir`); a write is atomic and keeps the previous text under `<data dir>/net/history/` (`--data-dir`, else `DRISHTI_DATA_DIR`, else
`./data`), and a running server picks it up by itself. The cluster key is only ever written as a reference; a key in clear is refused.

| Command | What it does |
|---|---|
| `net list [--json]` | The files (in force, refused, absent), whether the cluster is on, this node's address, tags and key state, the seeds |
| `net check [FILE ...] [--json]` | Checks every file (or those named); exit 1 when one would be refused, with each problem's file, line and setting |
| `net add-peer NAME URL [--tags a,b]` | Writes `peers/NAME.yaml`, a seed (`URL` is `host:port` or an address) |
| `net set KEY [VALUE]` | Changes one setting: `node.enabled true`, `node.tags region:emea,tier:risk`, `gossip.interval 2s`, `access.roles.risk.nodes n-a,region:emea`, `peers.drishti-a.url http://host:port`; an empty value removes it |
| `net members` | The member table of a running server (`GET /api/v1/net/members`; an administrator's token) |

Real output, on an empty folder:

```text
$ drishti.py net list
config/net at ~/drishti/config/net: Drishti*net is off (a single server, as without the cluster)
  node.yaml     absent (defaults)
  gossip.yaml   absent (defaults)
  routing.yaml  absent (defaults)
  access.yaml   absent (defaults)

$ drishti.py net set node.enabled true
drishti: node.yaml was not written: enabled: true needs the cluster key: secret: ${DRISHTI_NET_SECRET} with the variable set (at least 32 bytes)
  error   node.yaml  secret: enabled: true needs the cluster key: secret: ${DRISHTI_NET_SECRET} with the variable set (at least 32 bytes)
(exit 1)

$ drishti.py net set node.secret '${DRISHTI_NET_SECRET}'
wrote ~/drishti/config/net/node.yaml
$ drishti.py net set node.advertise-url http://127.0.0.1:18784
drishti: node.yaml was not written: 'http://127.0.0.1:18784' is a loopback or wildcard address: the address this node advertises must be an address other machines can call (this machine's address is 192.168.1.20: use http://192.168.1.20:18784); or leave advertise-url out and the node advertises that address with its port
  error   node.yaml:3  advertise-url: 'http://127.0.0.1:18784' is a loopback or wildcard address: [the same message]
(exit 1)
$ drishti.py net set node.advertise-url http://192.168.1.20:18784
wrote ~/drishti/config/net/node.yaml
$ drishti.py net set node.tags region:emea,tier:risk
wrote ~/drishti/config/net/node.yaml
$ export DRISHTI_NET_SECRET=...            # at least 32 bytes, from your secret store
$ drishti.py net set node.enabled true
wrote ~/drishti/config/net/node.yaml
$ drishti.py net add-peer drishti-a 192.168.1.20:18783 --tags region:emea
wrote ~/drishti/config/net/peers/drishti-a.yaml

$ drishti.py net check
3 file(s) checked in ~/drishti/config/net: 1 error(s); those files would be refused
  error   gossip.yaml:1  fanout: 'lots' is not a whole number
(exit 1)
```

Members advertise only a real address (2.6.0, [NET.md section 2](../admin/NET.md#2-a-cluster-of-three)): `localhost`, `127.x.x.x`, `::1`,
`0.0.0.0` and `::` are refused in `advertise-url` and in a seed's URL, and the message names this machine's address. `advertise-url` may be
left out: the node then advertises this machine's primary IPv4 address with the server's port (Admin → Net → Members shows it as *detected*).

The file `net set` wrote:

```yaml
# config/net/node.yaml (Drishti*net, docs/admin/NET.md)
secret: ${DRISHTI_NET_SECRET}
advertise-url: http://192.168.1.20:18784
tags:
- region:emea
- tier:risk
enabled: true
```

Exit: 0 done, 1 refused or a file has an error, 2 usage (a bad peer name, a key that names no file).

### `bi`: workspaces and the catalogue (Rupaka)

Business intelligence over REST ([BI_GUIDE.md](BI_GUIDE.md), [API_GUIDE.md](API_GUIDE.md#business-intelligence-rupaka-apiv1bi)), as the token's user sees it: the
server shows only what that person may open. Reads need no scope; `publish` needs a token with `bi:write` (and the user the `bi-author` power and the member role).

```
drishti.py bi workspace list|get [ID]                      the workspaces you may open; one with its members and items ('me' is your personal one)
drishti.py bi catalogue list [--q T] [--kind K] [--workspace W] [--owner O] [--endorsement E] [--tag T] [--sort use|date|title] [--fresh DAYS] [--limit N]
drishti.py bi catalogue get ID [--body]                    one item (--body also prints its YAML)
drishti.py bi catalogue publish ID [--note TEXT]           publish the draft as the next version
```

All take `--json`, `--server`, `--token-file` and `--user` like the other REST commands. With a review-required workspace, `publish` answers `403 DRS-9002`; propose
and approve in the BI page. Real output from a scratch server with a reader's token:

```
$ drishti.py bi workspace list
id                            role          items  name
u-cat-7fd6                    admin             0  My workspace  [personal]
rates-desk                    viewer            4  Rates Desk
shipped                       viewer            2  Shipped content
$ drishti.py bi catalogue list
id                           kind     endorsed  status    ver  title
bi-27def70913                report   certified published v1   Desk P&L  (Rates Desk, ann)
bi-cd5f8b2cc5                board    promoted  published v1   Counterparty exposure  (Rates Desk, ann)
file:report:liquidity-daily  report   promoted  published v1   Liquidity daily  (Shipped content, treasury)
bi-0185621d8e                report   none      published v1   Risk ladder  (Rates Desk, ann)
file:dataset:trades          dataset  none      published v1   Trades (shipped)  (Shipped content, treasury)
5 item(s)
$ drishti.py bi catalogue get bi-27def70913 --body
bi-27def70913   report   certified published v1   Desk P&L  (Rates Desk, ann)
  Daily P&L by book and trader, with the day's movers
  tags: pnl, daily   views: 0   audience: {'users': [], 'roles': ['bi-reader', 'bi-steward'], 'packs': []}
pages:
  - title: Overview

$ drishti.py bi catalogue publish bi-27def70913 --note x
drishti: the server said 403 DRS-9002: DRS-9002 cat does not hold the bi-author power (an administrator grants it on a role)
```

`drishti.py pack check` also refuses a pack that defines a mnemonic named `RUPAKA` or `FORM` (reserved command words).

### `bi expr`, `bi migrate`, `bi sql`: the BI languages

The Drishti expression language ([EXPRESSIONS.md](EXPRESSIONS.md)) and Drishti SQL over REST, as the token's user (the server owns the one parser of
each; [API_GUIDE.md](API_GUIDE.md#expressions-apiv1biexpr)). Reads need no scope.

```
drishti.py bi expr functions [--scope row|rule|aggregate]                 the DEL function catalogue, by family (a later wave's are marked)
drishti.py bi expr validate EXPR [--scope S] [--model M] [--alias A]       check one expression as you see the model; exit 1 on an error
drishti.py bi expr convert EXPR                                           the DEL v2 form of an old-syntax expression
drishti.py bi migrate --expressions FILE|DIR... [--write] [--check]      convert every expression of BI item files (comments kept, expressions: 2 set)
drishti.py bi migrate --expressions [--dry-run]                          the same for the server's STORED items (bi-admin; audited, history kept)
drishti.py bi migrate --models DIR [--dry-run] [--out D] [--keep-old] [--report F]   rachana: 1 datasets of a BI folder to datasets and models, reports rebound
drishti.py bi migrate --models [--dry-run] [--report F]                  the same for the server's stored items (bi-admin)
drishti.py bi migrate --boards FILE|DIR... [--write] [--keep-old]          2.3 boards to reports with board pages (2.4.0): writes reports/<name>.report.yaml, removes the board file
drishti.py bi migrate --boards [--dry-run]                              the same for the server's stored boards (bi-admin; the server does it at start too)
drishti.py bi sql "SELECT ..." --model M [--explain] [--format table|json|csv] [--file F]   Drishti SQL over a model
```

`bi migrate` reads `*.dataset|model|report|board|notebook.yaml` under a folder; without `--write` it prints what would change; `--check` changes nothing and
exits `1` while a file still has the old syntax (for CI). The converter is the server's: the old parser's tree printed as DEL v2 and checked to compile to
the same SQL; an expression it cannot convert is listed (`DRS-9190`) and left as it was.

Without FILEs, `bi migrate --expressions` works on the server's **stored** items (`POST /api/v1/bi/migrate/expressions`, bi-admin): `--dry-run` lists
each item with old-syntax expressions (draft and published changes, and what cannot be converted) and writes nothing; without it the items are converted
the way the server does at start (`drishti.bi.migrate.on-start`): the text before kept as a saved state, a changed published text published again as the
next version, each change audited. It exits `1` while an item cannot be converted (it keeps its text; Admin, BI settings lists it). The captured output
is in [EXPRESSIONS.md section 10](EXPRESSIONS.md#10-the-old-syntax-and-the-converter).

`bi migrate --models DIR` is the dataset-to-model converter ([BI_MODEL_SPEC.md section 8.3](../architecture/BI_MODEL_SPEC.md#83-the-converter)) over a
BI folder: it sends every `datasets|models|reports|boards|notebooks|python/*.yaml` to the server's converter together (a report needs its dataset's
plan) and writes what comes back: per `rachana: 1` dataset, one `datasets/<old>-<table>.dataset.yaml` per table and `models/<old>.model.yaml`; reports
rebound (`model: <old>@<major>`, comments kept). In place it removes the old dataset files (`--keep-old` keeps them; a live dataset's file is always
kept, its reports stay on it until W3); `--out D` writes under another folder and removes nothing; `--dry-run` writes nothing; `--report F` writes the
plan (every item, every rename, every behaviour difference and manual step) as Markdown. It exits `1` when an item is refused. Captured over the shipped
samples:

```
$ drishti.py bi migrate --models config/bi --dry-run
datasets/desk-pnl-vs-exposure.dataset.yaml: removed -> folded into the model trading-risk@1
datasets/counterparty-exposure.dataset.yaml: converted -> model counterparty-exposure@1 over [counterparty-exposure-netting-sets, counterparty-exposure-counterparties]
    writes datasets/counterparty-exposure-netting-sets.dataset.yaml
    writes datasets/counterparty-exposure-counterparties.dataset.yaml
    writes models/counterparty-exposure.model.yaml
...
notebooks/pnl-vs-exposure-pandas.notebook.yaml: manual
    manual: the notebook reads the dataset file:dataset:trading-risk, now the model trading-risk@1: rebind it to the model (`dataset: mq:trading-risk@1`, and each extract's `dataset:` the same way, its fields as alias.column), as the sample notebook pnl-vs-exposure-pandas does
...
26 file(s) read, 16 converted, 0 refused; would write 32 file(s)
```

Without DIR, `bi migrate --models` converts the server's **stored** datasets and rebinds their reports (`POST /api/v1/bi/migrate/models`, bi-admin), as
the server does at start: a dataset item becomes the model item (same id), each table a new dataset item, reports are published again; restricted roles
are never given rows they did not see ([TROUBLESHOOTING.md](TROUBLESHOOTING.md#bi-migrations-22-wave-a)).

### `bi model`, `bi mq`: models and model queries

What the model designer and a report's visuals do, over REST as the token's user (the server checks every model and answers every query with your row
rules and masks; [BI_GUIDE.md section 23](BI_GUIDE.md#23-models-and-pack-datasets-the-model-layer-wave-w1)). Reads need no scope.

```
drishti.py bi model list [--kind model|implicit|compat]          the models and datasets you may read, with the name@major a query uses
drishti.py bi model describe REF                                 a model as you see it: fields with type, meaning, display name, folder, format, masks; measures; perspectives
drishti.py bi model check FILE                                   the server's Check of a model or dataset YAML; exit 1 on an error (the safeguard's findings listed)
drishti.py bi model consumers REF                                what reads the model: reports, boards, alerts, notebooks, datasets
drishti.py bi mq FILE                                            ask one model query written as YAML or JSON
drishti.py bi mq --model REF --select F,... --measures M,... [--define "Name=DEL"]... [--where field=value]... [--sort[=-]BY]... [--limit N] [--as-of DATE]
```

`REF` is `name@major` (`trading-risk@1`), a model item id or `dataset:<name>` (a dataset's implicit model). `bi mq --define` adds a measure of this query
only, in DEL (`--define "Live MTM=sum(trades.MTM, where=trades.TRADE_STATUS != 'MATURED')" --measures "Live MTM"`), as a report's own measure travels. `--sort BY`
sorts by a field or measure, `--sort=-BY` descending (the `=` keeps the minus from reading as an option), repeatable: `--sort=-MTM --limit 3` is the top three. The answer is
a table with its plan, its as-of date and the columns masked for you; `--json` prints the server's answer as it is.

### `bi transform`: a Transform's inputs as you, and the snippets

(2.4.0) What the Transform editor's inputs and snippets picker do ([PYTHON_BI.md section 17](PYTHON_BI.md#17-data-prep-the-transform-editor-240)). `input`
asks the server, as the token's user, for each input of a Python dataset (`POST /api/v1/bi/python/input`: a model query, or a pack kind read through a
connector) and prints its rows, what is masked for you and a short profile of every column; `snippets` and `snippet` read the console's library
(`drishti-console/config/prep-snippets.yaml`, no server) and print the step a snippet makes, each value written as a literal.

```
drishti.py bi transform input FILE [--input NAME] [--limit N] [--as-of DATE] [--rows N] [--json]   each input of FILE.dataset.yaml as you
drishti.py bi transform snippets [--json]                                                     the data-prep snippets
drishti.py bi transform snippet ID [NAME=VALUE ...] [--inputs a,b]                              one step (a list: a,b; a mapping: from:to,from:to)
```

Captured, the steps of [tutorial 14, step 12](../tutorials/14-python-and-analyse.md#step-12--prepare-raw-trades-step-by-step-the-transform-editor-240):

```text
$ python3 tools/drishti.py bi transform input docs/tutorials/data/endur-trades-cleaned.dataset.yaml --rows 2
trades: 1,000 rows of dataset:pack.trading.trade as of 2026-10-16
  CURRENCY                     text      empty     0  distinct      3  CAD .. USD
  MATURITY_DATE                text      empty     0  distinct     81  2024-11-01 .. 2029-10-31
  NOTIONAL                     float64   empty     0  distinct    971  2,855.8 .. 2.46923e+08
  ...
$ python3 tools/drishti.py bi transform snippet filter-values column=TRADE_STATUS values=MATURED keep=false
# step: Filter rows by values (TRADE_STATUS)
df = df[df['TRADE_STATUS'].isin(['MATURED']) == False].reset_index(drop=True)
```

The steps themselves run in the console's sandbox (the editor), or on the server when the dataset is read; save the drafts with `bi item new`.

### `bi item`, `bi dataset`: items from files, tests and refresh

What My workspace's **New item**, the item page's **Edit**, **Check**'s expected values and a dataset page's **Refresh now** do, over REST as the token's
user. A write needs the `bi:write` scope and the `bi-author` power; the server checks every text and decides who may do what.

```
drishti.py bi item new WORKSPACE FILE [--kind K] [--title T] [--audience ROLE,...]   a draft from a YAML file; the kind is read from the file name (x.model.yaml)
drishti.py bi item save ID FILE [--message M]                                       a new saved state of the draft (the history shows the message)
drishti.py bi item tests ID [--role R]                                              run the item's expected values (tests:) as you or a role you hold; exit 1 on a failure
drishti.py bi dataset refresh ID [--now] [--wait S]                                 the refresh state; --now refreshes and waits until it ends (a rachana 2 dataset: through the models that read it)
```

Publishing is `bi catalogue publish ID` (above). Captured, the steps of [tutorial 11](../tutorials/11-bi-that-runs-itself.md):

```text
$ python3 tools/drishti.py bi item new economics-desk docs/tutorials/data/trades-daily.dataset.yaml
bi-03f88abb6f  dataset  Trades refreshed daily  draft in Economics desk
$ python3 tools/drishti.py bi dataset refresh bi-03f88abb6f --now
scheduled  (America/New_York, calendar USNY)
  dataset   business-days 06:30   ok  last run 2026-10-13 06:23:07 UTC by manual (drishti-dev-admin): 750 rows in 13 ms
$ python3 tools/drishti.py bi item tests bi-6027da28fe
pass  The book holds 750 trades: expected 750, found 750
fail  Close to 100 trades: expected 100 ± 1%, found 750
...
3 passed, 1 failed
```

### `bi statement`

A report's statement pages ([STATEMENTS.md](STATEMENTS.md), 2.6.0) made for the token's user, with their own masks and row rules: what the BI Studio's Paper
tab previews and a subscription mails.

```
drishti.py bi statement preview REPORT|--file F [--page P] [--date D] [--param NAME=VALUE ...]   the pages: count, paper, period, each page's header and footer
drishti.py bi statement file REPORT|--file F [--format pdf|xlsx] [-o OUT] [--page P] [--date D] [--param NAME=VALUE ...]   the PDF (page-exact) or the Excel workbook
```

`REPORT` is the report's id (`file:report:<name>` for a shipped file); `--file` sends a draft's YAML (a BI author). `--date` is the statement's date: its
`period` is worked out from it. `--param` gives a parameter of the report's model (a field parameter's choice, a what-if value). Captured, tutorial 20:

```text
$ python3 tools/drishti.py bi statement preview bi-39eb12fe47 --date 2026-10-08
Monthly statement: 3 pages, A4 portrait, October 2026, 66 rows
  page 1: Monthly statement | October 2026 | For wes  ...  Run 2026-10-19 · by DESK | Page 1 of 3  (80 cells)
  page 2: Monthly statement | October 2026 | For wes  ...  Run 2026-10-19 · by DESK | Page 2 of 3  (160 cells)
  page 3: Monthly statement | October 2026 | For wes  ...  Run 2026-10-19 · by DESK | Page 3 of 3  (6 cells)
$ python3 tools/drishti.py bi statement file bi-39eb12fe47 --date 2026-10-08 -o statement.pdf
wrote statement.pdf (5,000 bytes)
```

### Shell completion

`drishti.py completion bash|zsh|powershell` prints a completion script generated from the command tree (groups, commands,
options, the choices of options that have them, files for options that take a value, and the folders of `config/packs/` for `--pack`),
so it is always current. Install:

```bash
# bash: for this shell, or save the output as ~/.local/share/bash-completion/completions/drishti.py
source <(python3 tools/drishti.py completion bash)
# zsh: in ~/.zshrc (uses bashcompinit)
source <(python3 tools/drishti.py completion zsh)
# PowerShell: add this line to $PROFILE
python tools/drishti.py completion powershell | Out-String | Invoke-Expression
```

The script is registered for the names `drishti.py` and `drishti`, so an alias or a symlink called `drishti` completes too.

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

**Create, check, hand over.** Against the quick-start server (sign-in on, `DRISHTI_STUDIO_SAVE=true`, the development administrator's
token in `DRISHTI_TOKEN`), with the all-panels showcase document of the Sutra examples as the sample:

```bash
export DRISHTI_SERVER=http://localhost:18480
python3 tools/drishti.py design create --name swap-demo --kind trade --samples docs/guides/examples/all-panels-showcase.json --autodesign
python3 tools/drishti.py design list
python3 tools/drishti.py design check bdc51fed0ce7
python3 tools/drishti.py design propose bdc51fed0ce7 --note "first cut"
python3 tools/drishti.py design proposals
python3 tools/drishti.py design reject P-000002 --comment "not this time"      # or: design approve P-000002 --comment "looks right"
```

```text
created bdc51fed0ce7  swap-demo  kind trade  status draft  rev 1
  samples: all-panels-showcase.json
bdc51fed0ce7  swap-demo                   trade         draft     rev 1   1 sample(s)
...
check of rev 1: all samples ok
proposed: proposal P-000002 for trade-auto v1 is pending; an approver decides it
P-000002  trade-auto v1  pending  by drishti-dev-admin
P-000001  counterparty-mine v1  withdrawn  by drishti-dev-admin
reject: proposal P-000002: trade-auto v1 is rejected
```

`design list` also lists your other designs (the drafts of **Build → New pack** are designs too); the ids and proposal numbers are your
own. `design approve` answers the same way (`approve: proposal P-000002: trade-auto v1 is approved`) and puts the Sutra live: on a
shared server, reject or delete what you only tried (`design delete bdc51fed0ce7`).

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
`{"kind": "...", "doc": {...}}`, or (as here) files named by day with `--kind`; every document has an id and a business date. Run on the
quick-start server (the demo lakes, sign-in on, an administrator's token in `DRISHTI_TOKEN`) with the test data's interest-rate curves
as a pack of its own. Its kind is `demo-curve`: a kind name is unique across the loaded packs, and `ir-curve` is market-data's.

```bash
# 1. build the pack in the server's packs folder and put the documents in the files store the server reads (<data>/files)
drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/market-data/ir-curve --name curves-demo --title "Curves demo" \
    --kind demo-curve --key CURVE_ID --date AS_OF --match CURVE_TYPE --store files --files-root data/files

# 2. check it, and list the help text still to write
drishti-console/.venv/bin/python tools/drishti.py pack check config/packs/curves-demo
drishti-console/.venv/bin/python tools/drishti.py pack about-check config/packs/curves-demo
```

```text
196 documents, 1 kind(s), 4 group(s)
drafting 4 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
demo-curve      CURVE_TYPE=Cross-currency basis                  49       5  demo-curve-cross-currency-basis
demo-curve      CURVE_TYPE=Discount (OIS)                        49       5  demo-curve-discount-ois
demo-curve      CURVE_TYPE=Government                            49       5  demo-curve-government
demo-curve      CURVE_TYPE=Projection                            49       5  demo-curve-projection
...
Dated store (files): 196 rows written to data/files (domain curves-demo); 0 document(s) without a date or id skipped
pack written to config/packs/curves-demo
sutra lint: exit 0
sutra test: exit 0
== curves-demo: sutra lint: exit 0
config/packs/curves-demo/sutras/demo-curve/demo-curve-government.v1.sutra.yaml:15 warning DRS-2047 field 'POINTS.ZERO_RATE' is shown but has no glossary entry (kinds.demo-curve.glossary in config/about.yaml, or a vocabulary entry)
...
== curves-demo: sutra test: exit 0
```

3. **The server picks the pack up by itself.** It watches its packs folder (`drishti.packs.dir`, default `./config/packs`): a new
   pack folder is loaded within seconds, with no restart and no command. `server packs load` is for a pack on disk the server did not
   load (one switched off, or one in `drishti.packs.installed-dir`); for this one it answers that it is loaded already:

```text
$ drishti-console/.venv/bin/python tools/drishti.py server packs load curves-demo
drishti: the server said 400 DRS-5001: DRS-5001 'curves-demo' is already loaded
```

With a **Delta lake** instead: `--lake data/delta` in step 1 (the server's `DRISHTI_DELTA_ROOT`, default `<data>/delta`).

4. Open the console, type `DC CRV-USD-OIS` and press Enter (`DC` is the mnemonic `pack new` chose, the kind's initials; see the pack's
   `README.md`). Pick a business date in the top bar: each date you ingested has its documents. The same from a shell:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "$DRISHTI_SERVER/api/v1/views/demo-curve/CRV-USD-OIS?asOf=2026-09-30" | head -c 300
```

```text
{"ref":{"kind":"demo-curve","id":"CRV-USD-OIS"},"mnemonic":"DC","title":{"pill":"Demo curve · USD","id":"CRV-USD-OIS","with":{"text":"CRV-USD-OIS","link":{"kind":"ir-curve","id":"CRV-USD-OIS","mnemonic":"CRV"}}},"strip":[{"label":...
```

The title links to market-data's own `ir-curve` of the same id (the link graph knows `CURVE_ID` values are curves). New documents
tomorrow: `data ingest --from new-day/ --kind demo-curve --pack config/packs/curves-demo --store files --root data/files`; the default
mode replaces only the dates in the input, and the server picks the new files up on its next rescan. To take the pack away again:
`server packs unload curves-demo` (`unload curves-demo: Applied now; no restart.`), then delete `config/packs/curves-demo` and
`data/files/curves-demo`.

**Three ways a pack becomes known** to a server (the third is section 10.4): start with `DRISHTI_PACKS=...,curves-demo` and a
restart, or put it in the watched packs folder as above; `server packs load curves-demo` (or **Admin → Packs → Load**; it takes effect at
once, with no restart, and is remembered in `data/packs/added.yaml`); install from a registry. See
[PACKS.md#loading-a-pack-while-the-server-runs](PACKS.md#loading-a-pack-while-the-server-runs).

### 10.2 A CI pipeline: lint, test, JUnit

```bash
set -e
python3 tools/drishti.py pack check config/packs/my-bank --strict --junit data/build/reports  # exit 1 fails the job
python3 tools/drishti.py pack check config/packs/my-bank --json > data/build/pack-check.json  # for a dashboard
```

A GitHub Actions job (the jar is built once and reused):

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: 21 }
- run: ./mvnw -q -DskipTests -pl drishti-server -am package
- uses: astral-sh/setup-uv@v5
- run: uv run --with pyyaml python tools/drishti.py pack check config/packs/my-bank --strict --junit build/reports
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
python3 tools/drishti.py pack publish config/packs/my-bank --registry /srv/registry --key keys/me.pem --publisher me
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
| `pack check --json` | `{ok, packs: [{pack, path, steps: {lint: {exit, output, error}, test: {...}}, packYaml: [problems], ok}]}` (`output` is the step's text, lines joined) |
| `pack about-check --json` | `{ok, strict, gaps, packs: [{pack, sutras: {<sutra>: {missing: [fields], other: [warnings]}}, todo: [...], gaps, lintExit}]}` |
| `pack publish --json` | `{published, pack, registry, publisher}` |
| `server health --json`, `server packs list --json`, `pack install [--list] --json` | the server's own JSON |
| every `design ... --json` | the server's own JSON (`design export` prints `{file, bytes}`) |

On the help-desk example pack (`docs/guides/examples/pack/helpdesk`, fully explained):

```text
$ python3 tools/drishti.py pack check docs/guides/examples/pack/helpdesk --json
{
  "ok": true,
  "packs": [
    {
      "pack": "helpdesk",
      "path": "docs/guides/examples/pack/helpdesk",
      "steps": {
        "lint": {
          "exit": 0,
          "output": "ok      docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml",
          "error": ""
        },
        "test": {
          "exit": 0,
          "output": "     ticket: help coverage 8/8 (100%)\nok   docs/guides/examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml (2 samples)",
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
$ python3 tools/drishti.py pack about-check docs/guides/examples/pack/helpdesk --json
{
  "ok": true,
  "strict": false,
  "gaps": 0,
  "packs": [
    {
      "pack": "helpdesk",
      "sutras": {},
      "todo": [],
      "gaps": 0,
      "lintExit": 0
    }
  ]
}
```

On a generated pack (the `curves-demo` of section 5) `about-check --json` has `"ok": false`, `"gaps": 24` and, per Sutra,
`{"missing": ["POINTS.TENOR", ...], "other": []}`.

`design check --json` is the server's matrix, for example `{"ok": true, "samples": [{"name": "sample-1.json", "layout":
"Sutra trade-auto v1", "status": "ok"}, ...], "panels": [...], "rev": 1}`.

## 12. Exit codes

| Code | Meaning | Examples |
|---|---|---|
| **0** | ok | a clean lint; a design that checks; a server whose health is `OK`; a `connector apply` that was saved |
| **1** | problems found | a failing lint or test; `--strict` warnings; an unexplained field (`about-check`); a design with a failing sample; the server refused (401, 403, 404, 400...); the server cannot be reached; `server health` not `OK`; `server smoke` with a failing view or pack; `doctor` with a red line; `connector test` that cannot reach the source; `server packs datasource get` while a connector is not configured; a `connector` change refused (`DRS-5031`, `5032`, `5033`); `data ingest --watch --once` with a failed file |
| **2** | usage | a missing or unknown option or file (a `view` reference that is not KIND/ID); the exec jar not found; PyYAML or `deltalake` not installed; `--load` without `--server`; a pack folder without `pack.yaml` |

The Java `sutra` commands keep their own 0/1/2 and pass them through.

## 13. Troubleshooting

| You see | Why | Do |
|---|---|---|
| `drishti: no drishti-server-*-exec.jar under drishti-server/target: build it ... or pass --jar / DRISHTI_JAR` | the Java commands need the exec jar | `./mvnw -q -DskipTests package`, or `export DRISHTI_JAR=/path/to/drishti-server-2.6.0-exec.jar` |
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

1. Open the repository root as the project (**File → Open**, the folder that holds `tools/`, `config/packs/`, `pom.xml`).
2. Make a virtual environment with `uv` (any virtual environment works), in a terminal at the repository root:

   ```bash
   uv venv --python 3.13 drishti-console/.venv
   uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
   ```

   This is the console's own environment, the one the quick start installs and the shipped run configurations (`drishti-console/run-configs/`) use: it has
   `pyyaml`, `deltalake` and `pyarrow` (the last two for `--store delta`, the default of `pack make`, `data ingest --lake` and `lake build`).
   On Windows the interpreter is `drishti-console\.venv\Scripts\python.exe`.
3. **Settings (Ctrl+Alt+S) → Project → Python Interpreter → Add Interpreter → Add Local Interpreter → Existing**, pick
   `drishti-console/.venv/bin/python`, **OK**.
4. Optional: **Settings → Project → Project Structure**: mark `tools` as *Sources* so PyCharm resolves `import sutragen` and
   `import ingest_jsonl` when you click through code.

### 14.2 A run configuration for each task

**Run → Edit Configurations → + → Python**. The fields are the same for every task; only **Name** and **Parameters** change:

| Field | Value |
|---|---|
| **Script path** (not *Module name*; switch the toggle on the left of the field) | `<repo>/tools/drishti.py` |
| **Python interpreter** | the `drishti-console/.venv` interpreter from 14.1 |
| **Working directory** | **the repository root**: relative paths in *Parameters* resolve from here |
| **Environment variables** | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64;DRISHTI_JAR=<repo>/drishti-server/target/drishti-server-2.6.0-exec.jar;PYTHONUNBUFFERED=1`, and for server commands `DRISHTI_SERVER=http://localhost:18480;DRISHTI_TOKEN=drk_...` |

Click the folder icon next to **Environment variables** to edit them as a table (one row per variable). `JAVA_HOME` and
`DRISHTI_JAR` may be left out: the tool then looks for JDK 21 and for the newest `drishti-server/target/*-exec.jar` itself.

| Name | **Parameters** |
|---|---|
| `drishti pack make` | `pack make data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force` |
| `drishti data profile` | `data profile data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz` |
| `drishti pack regenerate` | `pack regenerate data/build/my-bank/pack/my-bank data/testdata/trades/endur/changes-2026-09-23.jsonl.gz --dry-run` |
| `drishti sutra gen` | `sutra gen data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz --kind trade --match PRODUCT_TYPE --out data/build/sutras` |
| `drishti data ingest` | `data ingest --from your-new-days/ --pack data/build/my-bank/pack/my-bank --lake data/build/my-bank/data/delta --dry-run` (remove `--dry-run` to write) |
| `drishti pack check` | `pack check data/build/my-bank/pack/my-bank --strict --junit data/build/reports` |
| `drishti server packs load` | `server packs load my-bank --server http://localhost:18480` with `DRISHTI_TOKEN` set |
| `drishti sutra lint` | `sutra lint config/packs/my-bank` |
| `drishti server health` | `server health` |

Press **Run** (Shift+F10): the output is in the Run window, and a non-zero exit code shows as a failed run (exit 1 = a check
failed, 2 = usage; see [section 12](#12-exit-codes)). If the Run window says `no drishti-server-*-exec.jar`, build it once
(`./mvnw -q -DskipTests package`) or set `DRISHTI_JAR`.

**Templates.** **Run → Edit Configurations → Edit configuration templates... → Python** sets the defaults for every *new* Python
configuration: put the interpreter, the working directory (`$PROJECT_DIR$/..`, the repository root), and the environment variables there once, and each new
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

### 14.4 Share the configurations with the team: `drishti-console/run-configs/`

By default a configuration lives in `.idea/workspace.xml` (personal, not committed). To share it, open it in **Edit
Configurations** and tick **Store as project file**: PyCharm (opened on `drishti-console/`) writes it to `drishti-console/run-configs/<name>.run.xml`, which can be
committed; `$PROJECT_DIR$/..` in it is the repository root. This repository ships several to start from, among them `drishti pack make`, `drishti sutra gen`, `drishti data profile` and `drishti pack regenerate`; PyCharm
lists them under **Python** as soon as the project opens (reload with **File → Reload All from Disk** if it was open already).
The format is one `<configuration>` element in a `ProjectRunConfigurationManager` component:

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="drishti pack make" type="PythonConfigurationType" factoryName="Python">
    <module name="drishti-console" />
    <envs><env name="PYTHONUNBUFFERED" value="1" /></envs>
    <option name="SDK_HOME" value="" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$/.." />
    <option name="IS_MODULE_SDK" value="false" />
    <option name="SCRIPT_NAME" value="$PROJECT_DIR$/../tools/drishti.py" />
    <option name="PARAMETERS" value="pack make data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force" />
    <method v="2" />
  </configuration>
</component>
```

(the real files list every option PyCharm writes). `$PROJECT_DIR$` is `drishti-console/` (PyCharm's project), so `$PROJECT_DIR$/..` is the repository root on every machine; an empty `SDK_HOME` with `IS_MODULE_SDK`
false is PyCharm's *Project Default* interpreter, so nobody's local path is in the file and the same file works on Windows
(`tools/test_run_configs.py` guards it); the `module` name does not decide the interpreter, so a clone in a folder of another name works too. Put only what is shared in them: no tokens, no home-directory
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
| **Program arguments** | `sutra lint config/packs/my-bank --strict` |
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

## 15. The headless Sutra tool

`sutra lint|test|preview|shape|design` is the Java tool behind `drishti.py sutra` (section 4): it needs no running server and is what CI runs. The `tests/<sutra>/` convention, `expect.yaml`, the exit codes and
CI wiring are section 15 of the [Sutra developer guide](SUTRA_DEVELOPER_GUIDE.md#15-testing-expectyaml-sutra-linttestpreview-ci); the workbench's own use of the same command is in
[SCREEN_DESIGNER.md](SCREEN_DESIGNER.md#26-the-command-line-and-ci). This section replaces the former `SUTRA_CLI.md`.
