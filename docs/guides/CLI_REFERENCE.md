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

# Command line

For pack authors, data engineers, risk users who script, and the people who run Drishti: everything you do with Drishti from a
terminal, a scheduler or a CI job. It explains the one command-line program, `tools/drishti.py`, installs it, connects it to a
server, and then takes every command group in turn with real examples and real output: Sutras and packs, your JSON Lines into a
lake, the lake builder for large exports, the server and its connectors, views and history, designs and approvals, BI, the risk
engines, the store loaders, the generators and the developer tools. It ends with recipes, the JSON formats, the exit codes and a
troubleshooting table. Every command also prints its own options: `drishti.py <group> <command> --help`.

Scheduling these tools, lake maintenance, the console pages that run them and sizing a large load are in
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md). The console's own command line (the mnemonics you type in the browser, such as
`TRD 1000015`) is a different thing: every one of its commands is in [COMMAND_CATALOG.md](COMMAND_CATALOG.md).

All the data in this guide is invented, and every example runs on a fresh clone as it is: the files are the committed test data
under `data/testdata/` (the ENDUR trade book `data/testdata/trades/endur/`, a day-one snapshot and one file of changes per later day,
JSON Lines gzipped; trades with legs, cash flows, resets and events in the UPPER_SNAKE attributes of the trading pack, blanks spelt
four ways: the *shape* of a booking-system export, none of its values), the interpreter is the console's,
`drishti-console/.venv/bin/python` (the five-step quick start of [data/testdata/README.md](../../data/testdata/README.md) installs it),
and the commands run from the repository root. Scratch output (`lake`, `lake2`, `data/build/...`) goes into the clone; delete it
afterwards.

## Contents

1. [What it is](#1-what-it-is)
2. [Install and prerequisites](#2-install-and-prerequisites)
3. [Connecting to a server: URL, tokens and permissions](#3-connecting-to-a-server-url-tokens-and-permissions)
4. [`sutra`: shape, design, gen, lint, test, preview](#4-sutra-shape-design-gen-lint-test-preview)
5. [`pack`: make, new, regenerate, diff, catalogue, i18n, check, about-check, bundle, verify, deploy, rollback, publish, keygen, install](#5-pack-make-new-regenerate-diff-catalogue-i18n-check-about-check-bundle-verify-deploy-rollback-publish-keygen-install)
6. [`data`: profile, ingest and load](#6-data-profile-ingest-and-load)
7. [`lake`: a large JSON export into a Delta lake](#7-lake-a-large-json-export-into-a-delta-lake)
8. [`server`: health and packs](#8-server-health-and-packs)
9. [`connector` and `net`](#9-connector-and-net)
10. [`view`, `history` and `convert`](#10-view-history-and-convert)
11. [`design`: the Screen Designer over REST](#11-design-the-screen-designer-over-rest)
12. [`bi` and `tables`](#12-bi-and-tables)
13. [The risk engines: `var`, `exposure`, `sens`, `frtb`, `simm`, `pivot`](#13-the-risk-engines-var-exposure-sens-frtb-simm-pivot)
14. [`compute`, `contract`, `elements` and `feed`](#14-compute-contract-elements-and-feed)
15. [Store loaders: `tools/load-*.sh`](#15-store-loaders-toolsload-sh)
16. [Generators: `tools/packgen`, `tools/samplegen`, `tools/realdata`](#16-generators-toolspackgen-toolssamplegen-toolsrealdata)
17. [Developer tools](#17-developer-tools)
18. [Recipes](#18-recipes)
19. [JSON output, exit codes and troubleshooting](#19-json-output-exit-codes-and-troubleshooting)

## 1. What it is

`tools/drishti.py` is a thin, honest front door. It adds no new engine: each command either runs something that already exists
(the Java `sutra` and `tables` tools in the server's jar, the lake builder, the generators and loaders under `tools/`) or calls a
REST endpoint the server already has. What it adds is one place to look, one `--help` per command
(`drishti.py <group> --help` lists a group's commands, `<group> <command> --help` every option with examples), one set of exit
codes and, where it helps scripts, `--json`.

| Group | Commands | Runs | Section |
|---|---|---|---|
| `sutra` | `shape`, `design`, `lint`, `test`, `preview`, `gen` | the Java tool in the exec jar; `tools/sutragen.py` (JDK 21+) | [4](#4-sutra-shape-design-gen-lint-test-preview) |
| `pack` | `make`, `new`, `regenerate`, `diff`, `catalogue`, `i18n`, `check`, `about-check`, `bundle`, `verify`, `deploy`, `rollback`, `publish`, `keygen`, `install` | `tools/packgen/pack_from_jsonl.py`, the Java tool, `tools/packreg/packreg.py`; REST for `install` | [5](#5-pack-make-new-regenerate-diff-catalogue-i18n-check-about-check-bundle-verify-deploy-rollback-publish-keygen-install) |
| `data` | `profile`, `ingest`, `load`, `landed`, `loads`, `get` | `tools/ingest_jsonl.py`, `tools/load-<store>.sh`; REST for `landed`, `loads`, `get` | [6](#6-data-profile-ingest-and-load) |
| `dataload` | `status`, `run`, `retry`, `undo` (the data inbox) | REST | [6](#dataload-the-data-inbox) |
| `testdata` | `capture-market` | a scratch server and the market connectors | [6](#testdata-capture-market-the-market-snapshot-for-offline-demos) |
| `lake` | `build`, `scan`, `status`, `publish`, `demo`, `show`, `check`, `migrate-config`, `maintain`, `relocate`, `undo`, `memory` | `tools/lake/`; the server jar's writer and parallel loader | [7](#7-lake-a-large-json-export-into-a-delta-lake) |
| `server`, `admin` | `server health`, `caches`, `packs`, `commands`, `smoke`, `engines`, `blas`; `admin capacity` (also `admin engines`, `admin blas`) | REST `/api/v1/admin/...` (an administrator) | [8](#8-server-health-and-packs) |
| `doctor` | `drishti.py doctor` | this machine, and with `--server` the server | [8](#doctor-is-this-machine-ready) |
| `connector` | `list`, `get`, `apply`, `delete`, `test`, `plugins`, `enable`, `disable`, `reset`, `history` | REST (Admin → Connectors) | [9](#connector-list-get-apply-delete-test-plugins-enable-disable-reset-history) |
| `net` | `list`, `check`, `add-peer`, `set`, `members` | the `config/net/` folder; REST for `members` | [9](#net-drishtinets-confignet-folder) |
| `view`, `history`, `convert` | `view get`, `view explain`; `history KIND ID`; `convert FILE` | REST; `tools/convertcmd.py` | [10](#10-view-history-and-convert) |
| `design` | `list`, `create`, `get`, `save`, `check`, `autodesign`, `propose`, `proposals`, `export`, `import`, `bind`, `delete` | REST `/api/v1/builder/designs` | [11](#11-design-the-screen-designer-over-rest) |
| `approvals`, `change`, `edits` | `approvals` list, show, approve, reject, withdraw, ask; `change` propose, submit, list, show, history; `edits status` | REST | [11](#approvals-the-one-approvals-queue) |
| `bi`, `tables` | `bi workspace`, `catalogue`, `expr`, `migrate`, `sql`, `model`, `mq`, `transform`, `item`, `dataset`, `runs`, `statement`; `tables` list, check, preview, lineage, offer, migrate | REST; the Java `tables` tool | [12](#12-bi-and-tables) |
| `var`, `exposure`, `sens`, `frtb`, `simm`, `play`, `pivot` | the VaR, exposure, FRTB-SA, SIMM and pivot engines | REST | [13](#13-the-risk-engines-var-exposure-sens-frtb-simm-pivot) |
| `compute`, `contract`, `elements`, `feed` | reactive connectors; data contracts; Drishti Elements bundles; the data feeds | REST; `tools/contracts.py`; `tools/elements/`; `tools/feeds/` | [14](#14-compute-contract-elements-and-feed) |
| `docs` | `docs shots` | `tools/docs/screenshots.py` (Playwright) | [17](#docs-shots-screenshots) |
| `completion` | `completion bash`, `zsh`, `powershell` | the command tree | [2](#shell-completion) |

Concepts used throughout (each is explained in its own guide; this one only links to them):

- A **pack** is a folder: `pack.yaml`, Sutras, samples, tests, help text.
  [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md).
- A **Sutra** is the YAML that says how one kind of document is shown. [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md).
- A **design** is a Sutra in progress in the Screen Designer, with its samples and its history of edits.
  [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench).
- A **dated store** is where a pack reads documents *per business date*: a Delta lake or the file connector's
  `<root>/<domain>/<date>/<kind>.jsonl` files.
- A pack becomes known to a running server in **four ways**: its folder is copied into the packs folder the server watches (`pack deploy`, `pack make`'s `pack/<name>`: the server checks it and loads it by itself, no restart), the server is started with it in `DRISHTI_PACKS` (a restart),
  an administrator loads it in **Admin → Packs** (at once), or it is installed from a registry.
  `drishti.py pack new --load`, `server packs load` and `pack install` are the command-line forms of the last two.
  [PACK_DEVELOPER_GUIDE.md#loading-a-pack-while-the-server-runs-1](PACK_DEVELOPER_GUIDE.md#loading-a-pack-while-the-server-runs-1) has the rules.

### Which tool for which job

The browser column names the console page that runs the same engine; those pages are described in
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md).

| You want to | In a browser | In a terminal or a scheduler |
|---|---|---|
| Turn a large JSON export (an array or JSON Lines, gzipped or not) into a Delta lake a pack reads | Admin → Data loads → **Load a file into a lake** (`/admin/lakes`) | `drishti.py lake build` |
| Load millions of JSON Lines rows (scenario PVs) into Delta or Iceberg, from a folder or S3, on every core | | `drishti.py lake build --engine java`, or the loader on its own ([7.13](#713-the-parallel-lake-loader)) |
| See what an export holds and which fields make a good id, date and classifier | the same page, step 2 | `drishti.py lake scan`, `drishti.py data profile` |
| Make a pack from schema files or JSON | Build → **New pack** (`/build/pack/new`) | `drishti.py pack make`, `drishti.py pack new` |
| Check a pack in CI | | `drishti.py pack check --junit` |
| Ship a pack to a server | Admin → Packs → Deploy | `drishti.py pack bundle`, `pack deploy`, `server packs deploy` |
| Tell the server that a batch landed | the Lakes page does it after a build | `drishti.py data landed`, or `lake build --signal` |
| Take a day of a dataset out as a file | Views → Export data (`EXPORT <GO>`): columns, filter lines, Download | `drishti.py data get <dataset> --date D --columns ... --where ...` |
| Change where a connector reads | Admin → **Connectors** | `drishti.py connector apply` |
| Install or uninstall a pack, connector or BI file safely | Admin → **Artifacts** | `drishti.py server packs`, `connector delete` |
| Keep a lake small and fast | | `drishti.py lake maintain` (on a timer) |
| Load the demo data into Postgres, DuckDB, MongoDB, Redis, Aerospike, Cassandra, Iceberg, files or Delta | | `tools/load-*.sh`, `drishti.py data load` |
| Check a machine before a demo | Admin → **Health** | `drishti.py doctor`, `drishti.py server smoke` |
| Run VaR's many-scope sums on the bundled OpenBLAS or on the pure-Java path, live | Admin → **Engines** → Linear algebra: OpenBLAS | `drishti.py server blas status`, `server blas java --reason ...`, `server blas native --reason ...` (also as `admin blas`; [OPERATIONS_GUIDE.md section 2.1](OPERATIONS_GUIDE.md#149-native-libraries-blas)) |
| Make, check or remove the AWS side of the S3 Tables or Delta Lake on S3 connector (bucket, namespace or prefix, IAM policy) | | `deploy/aws/s3-tables/s3-tables.sh`, `deploy/aws/delta-s3/delta-s3.sh` (`.ps1` on Windows; [7.14](#714-the-aws-connectors-tool-scripts-s3-tables-and-delta-lake-on-s3)) |
| See or check the shape of a command's data (its data contract), or draft the contract of a new kind | | `drishti.py contract list`, `contract show`, `contract check`, `contract docs`, `contract bootstrap` |
| Pin the Python libraries code people write may use on the server's compute pool `python` (every file's sha256, for `pip --require-hashes`) | | `python tools/python_lock.py config/compute/locks/python.in --out config/compute/locks/python.lock` ([PYTHON_COMPUTE.md section 19](../architecture/PYTHON_COMPUTE.md#19-the-python-runtime-for-code-people-write)) |

Use the page for a one-off file, a first look at new data, or when you want to see each choice before it is made. Use the command
line for anything that repeats, runs at night, or lives in a pipeline: it has exit codes, a lock, a log line and a JSON summary,
and it is the same engine the page runs.

### Quickest path: `pack make`

You have a folder of JSON Lines (or one file). **One command** turns it into **one folder you take and deploy**: the pack with its
Sutras, the data in a Delta lake partitioned by business date, a checksummed bundle, a server configuration, start scripts and a
`README.txt` with your real values filled in.

```bash
drishti-console/.venv/bin/python tools/drishti.py pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz \
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
[section 5](#pack-make-everything-in-one-folder) and [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#812-pack-make-one-folder-to-deploy).

## 2. Install and prerequisites

| You need | For | How to check |
|---|---|---|
| Python 3.10 or newer | everything | `python3 --version` |
| PyYAML | `pack`, `data ingest`, `design create --from-sutra` | `python3 -c "import yaml"` |
| `deltalake` and `pyarrow` | `data ingest --store delta`, `pack new --lake` | only when you write a Delta lake |
| JDK 21 or newer | `sutra`, `pack new`, `pack check`, `pack about-check` | `java -version` (the tool prefers `JAVA_HOME`, then `/usr/lib/jvm/java-21-openjdk-amd64`, then `java`) |
| the exec jar `drishti-server/target/drishti-server-<version>-exec.jar` | the same | `./mvnw -q -DskipTests package`, or pass `--jar` / set `DRISHTI_JAR` |
| `openssl` | `pack keygen` | `openssl version` |
| Playwright | `docs shots` | see [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench) |

The program uses only the standard library plus PyYAML. It imports `deltalake` and `pyarrow` **only** when a command writes
a Delta lake, so a machine without them runs everything else.

**With `uv`** (nothing to install into your system Python; the recommended way):

```bash
uv run --with pyyaml python tools/drishti.py --help
uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py data ingest --from data/new --pack config/packs/my-bank --lake data/delta/demo
```

**With the console's environment** (what an IDE wants, see [Running the tools from PyCharm](#running-the-tools-from-pycharm-and-the-java-sutra-from-intellij);
the run configurations use it):

```bash
python3 -m venv drishti-console/.venv && drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt
#   or, where python3 -m venv lacks ensurepip:  uv venv --python 3.13 drishti-console/.venv && uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
drishti-console/.venv/bin/python tools/drishti.py --help
```

Run the program from the repository root, or from anywhere with its path, with the console's interpreter
(`drishti-console/.venv/bin/python`, which the quick start of [data/testdata/README.md](../../data/testdata/README.md) installs with
PyYAML, deltalake and pyarrow). Many examples write `python3 tools/drishti.py`: any Python 3.10+ with PyYAML runs every command that
does not write a Delta lake; `drishti.py` in the output is the same program. The shell tools under `tools/` are bash, with PowerShell
twins under `tools/windows/`.

The Java commands (`sutra`, `pack check`, `tables` and Drishti's own lake writer) run from the server's exec jar, found as `--jar`,
then `DRISHTI_JAR`, then the newest `drishti-server/target/drishti-server-*-exec.jar` (build it with `./mvnw -q -DskipTests package`).

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

```
$ drishti-console/.venv/bin/python tools/drishti.py completion bash | head -n 3
# bash completion for drishti.py: generated by `drishti.py completion bash`
#   source <(drishti.py completion bash)         or save it as ~/.local/share/bash-completion/completions/drishti.py
_drishti_complete() {
```

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
| `design:approve` | `approvals approve\|reject sutra` | a role with `approve` (or admin), and never your own proposal with four-eyes on |
| `approve` | `approvals approve\|reject\|withdraw` for every kind your roles let you decide (report sign-offs, stress windows and compute packages stay a person's) | the kind's own rules |
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
and revoking the old one. A signed token (API_GUIDE "Minting a token for a script") works too.

**Permissions follow the screens.** The command line asks the same endpoints the screens do, so it can do exactly what you
could do there and nothing more:

| Command | Who may |
|---|---|
| `design list|create|get|save|check|autodesign|export|import|delete` | any user (your own designs) |
| `design propose` | a user who may save: a role with `author`, and the server's `drishti.rachana.studio-save: true` (`DRISHTI_STUDIO_SAVE`) |
| `design proposals` | any user |
| `approvals approve|reject sutra` | a role with `approve` (or an administrator); with sign-in on and four-eyes on (the default) not your own proposal |
| `design bind` | a user, when the server has file binding on (a development directory per user) |
| `server health`, `server packs ...`, `pack install` | an administrator |

A refusal is exit code 1 with the server's own words, for example `the server said 403 DRS-5002: ...`.

## 4. `sutra`: shape, design, gen, lint, test, preview

`sutra shape|design|lint|test|preview` run the **Java tool** in the exec jar (`java -jar ... sutra <command>`) with your
arguments; its output, its `--junit` report and its exit code (0 ok, 1 problems, 2 usage) come straight through. What each
does, `expect.yaml`, `tests/<sutra>/` and CI are [below](#testing-sutras-expectyaml-the-tests-convention-and-ci).
Options this program does not know are passed on to the Java tool unchanged.

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

**Lint every pack at once:** `sutra lint config/packs` takes the whole packs root. Each pack is read with its own fragments under
its namespace (`trading/deal-ticket@1` resolves, beside the UI kit's `config/ui-kit/` fragments), each pack's `tests/` folder is
skipped and each pack's `history:` block is checked. `--docs` adds the docstring warnings (`DRS-2055`).

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
Sutra is `data/build/sutras/<kind>/<kind>-<group>.v1.sutra.yaml` with its `where` clause, and `data/build/sutras/tests/<sutra>/cases.jsonl`
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
   `trade_endur.2026-07-08.jsonl.gz` is kind `trade_endur` (the name's first part, the load rules' table), and the per-kind form
   `--key trade=TRADE_ID` would not apply to it. When every line is a trade, pass **`--kind trade`**; then the plain forms `--key TRADE_ID` and
   `--match PRODUCT_TYPE` are enough. (Loader envelope lines carry their own `kind` and need no `--kind`.)
2. **Use the field name exactly as it is in the data** (`PRODUCT_TYPE`, `product_type` and `productType` are three different fields). A
   misspelt match field puts every trade in one group without the field, so you would get a single Sutra; add
   `--skip-missing` to leave out documents that lack a match field instead.

What comes out, for the 130 product types of the three sources:

| Output | Where |
|---|---|
| 130 Sutras, each with `match: { kind: trade, where: "$.PRODUCT_TYPE == 'IRS_FIXFLOAT'", priority: 10 }` and the title id `$.TRADE_ID` | `data/build/sutras/trade/trade-<product>.v1.sutra.yaml` |
| up to 5 sample trades per Sutra (`--samples N`; chosen deterministically, across dates with `--date`) | `data/build/sutras/tests/<sutra>/cases.jsonl` (cases `sample-1` ...) |
| a lint of everything written | exit code 1 if any Sutra has a problem |

Options that help here: `--fallback` adds a low-priority `trade-default` Sutra so a product type that appears later still
gets a designed screen (rather than an inferred one); `--min-docs N` skips product types with fewer documents;
`--date BUSINESS_DATE` (your data's business-date field) picks samples across business dates, newest first; several match fields
(`--match PRODUCT_TYPE,CURRENCY`) give one Sutra per combination. To get a whole pack (kinds, mnemonics, tests, About
skeleton, optionally a Delta lake or files) instead of Sutra files only, use `pack new` with the same options
([below](#pack-new-a-complete-pack-from-json-lines)).

It needs the built server jar (`./mvnw package -DskipTests`) and a JDK 21: all groups are designed in one Java run.

### Testing Sutras: `expect.yaml`, the tests convention and CI

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

$ drishti-console/.venv/bin/python tools/drishti.py sutra test config/packs/banking/market-risk
     frtb-sensitivity: help coverage 9/9 (100%)
ok   config/packs/banking/market-risk/sutras/market-risk/frtb-sensitivity.v1.sutra.yaml (2 samples)
     pnl-explain: help coverage 4/4 (100%)
ok   config/packs/banking/market-risk/sutras/market-risk/pnl-explain.v1.sutra.yaml (2 samples)
…    (stress-result and stress-scenario likewise)
skip config/packs/banking/market-risk/sutras/market-risk/trade-valuation.v1.sutra.yaml (no samples)
     var: help coverage 5/5 (100%)
ok   config/packs/banking/market-risk/sutras/market-risk/var.v1.sutra.yaml (2 samples)
```

`sutra lint config/packs/banking/trading` prints `ok` for each of the pack's 134 Sutras (blocks expanded) in about seven seconds; `sutra test config/packs/banking/trading` renders each against the samples in its
`tests/` folder.

#### The tests convention

```
config/packs/<domain>/<pack>/
  sutras/.../irs-fixfloat.v1.sutra.yaml
  tests/irs-fixfloat/         <- one folder per Sutra, named by the Sutra's name (the `sutra:` line)
    cases.jsonl               <- the cases, one a line: {"case": "MX-20000001", "document": {...}}; the kind comes from the Sutra's `match.kind`
    expect.yaml               <- optional
  tests/help-coverage.txt     <- optional: a floor of help coverage per Sutra (irs-fixfloat=0.9), never lowered
```

A Sutra's cases are **one file, `cases.jsonl`, one case a line** (`{"case": "<name>", "document": {...}}`;
[DATA_GUIDE.md](DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)); each case is reported by its name. A line that is not such an object is a failed case
(`cases.jsonl:3: a case is one line {"case": ...`). **The older layout, one `<name>.json` file per case, is deprecated**: it is still read,
with `warning: <folder>: one .json file per case is deprecated ...` on standard error; `drishti.py convert` does not write cases, so convert a folder by hand or regenerate it. Other `.jsonl` files in the folder
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

#### Drafting many Sutras from JSON Lines

`sutra design --each <dir>` drafts one Sutra per immediate subfolder of `<dir>` that holds `.json` or `.jsonl` samples, in one JVM (a JVM start costs about 5 s). `sutra gen` (`tools/sutragen.py`) builds on it: it
splits JSON Lines files into groups by the values of the `--match` fields, writes each group's samples, calls `design --each` once, edits the name, description, `match` line and title id, writes
`tests/<sutra>/cases.jsonl` (cases `sample-1` ...), and runs `sutra lint`. On 400 Endur trades of the committed test data:

```bash
mkdir -p /tmp/gen && zcat data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz | head -400 > /tmp/gen/trade.jsonl
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

#### In CI

```sh
drishti-console/.venv/bin/python tools/drishti.py sutra lint config/packs/my-pack --strict
drishti-console/.venv/bin/python tools/drishti.py sutra test config/packs/my-pack --junit target/sutra-tests.xml
drishti-console/.venv/bin/python tools/drishti.py sutra preview config/packs/my-pack --out target/snapshots   # attach as build artifacts
```

Publish `sutra-tests.xml` with your CI's JUnit report step. A Sutra whose panels read other entities with `source:` needs those entities' packs enabled for the run: set
`DRISHTI_PACKS=core,market-data,trading` (the same setting as the server) in the job. The command keeps nothing: its identity, governance and design data live in a temporary folder that is removed
when it ends, and no port is opened.

`sutra test docs/guides/examples` passes with the seven packs (the `linked-sources` example reads `CP-SUMMIT`, `NS-SUMMIT-NY` and `DESK-FI` from them); without them its failure line says `NS-SUMMIT-NY
not found`, which means the pack holding that entity is not enabled. `sutra test config/packs/<domain>/<pack>` reports `skip (no samples)` for a Sutra with no `tests/<sutra>/` folder; that is not a failure.

**Limits.** The CLI keeps the server's input limits: a file over `drishti.builder.max-file-mb` (5 MB by default) is refused with one line that names it, text that is not UTF-8 says so, an engine that
cannot start (for example a pack that is not found: set `DRISHTI_PACKS_DIR`) ends with `sutra: ...` and exit `1`, never a stack trace. `--kind` is a name (letters, digits, `.`, `_`, `-`, up to 64),
not a path. `test` looks for `tests/<sutra>/` only inside the paths you gave it.

**In this repository**, `SutraCliTest` runs `sutra test` over every shipped pack that has a `tests/` folder (counterparty-risk, market-risk, trading, world-economy) and over the documented examples,
so the Maven build fails when a Sutra change breaks a pack's own tests. Add a `tests/<sutra>/` folder with one or two sample documents and an `expect.yaml` to any pack to put its Sutra under the same
guard.

### The headless Sutra tool

`sutra lint|test|preview|shape|design` is the Java tool behind `drishti.py sutra` (section 4): it needs no running server and is what CI runs.
It writes nothing to the site it runs in, even from the repository folder beside a running server: the engine starts with a scratch data
folder made for the command and deleted after it (identity database, content store, caches, derived pages, temp files; its JVM temp
folder is `DRISHTI_TMP_DIR`, else a fresh folder in the system temp folder), the packs' connector templates are read in memory (nothing
in `config/connectors/`), and the official VaR and exposure captures, the demo ticker, hot reload, compute workers, write-back and
Drishti*net are off. The site's lakes (`DRISHTI_DELTA_ROOT`, `DRISHTI_ICEBERG_ROOT`, `DRISHTI_FILES_ROOT`, `DRISHTI_FEEDS`, each
defaulting under `DRISHTI_DATA_DIR` or `data/`) and its added and installed packs are still read. The `tests/<sutra>/` convention, `expect.yaml`, the exit codes and
CI wiring are also in section 15 of the [Sutra developer guide](SUTRA_DEVELOPER_GUIDE.md#15-testing-expectyaml-sutra-linttestpreview-ci); the workbench's own use of the same command is in
[RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#26-the-command-line-and-ci).

## 5. `pack`: make, new, regenerate, diff, catalogue, i18n, check, about-check, bundle, verify, deploy, rollback, publish, keygen, install

| Command | What it does | In the console |
|---|---|---|
| `pack make` | JSON Lines (or `--schema`) in, ONE deployable folder out: the pack, Delta data, a bundle, server config, run scripts | Build → New pack |
| `pack new` | a complete pack from JSON Lines (kinds, Sutras, samples, tests, About text, a lake or files store; `--load` loads it) | Build → New pack |
| `pack regenerate` | new data, new Sutras, none of your edits lost | |
| `pack diff` | what changed between two versions of a pack, and what breaks | |
| `pack catalogue` | a readable catalogue (Markdown or HTML) for business review | |
| `pack i18n` | `pack i18n export` / `pack i18n import`: translate the About text through a spreadsheet | |
| `pack check` | `sutra lint` + `sutra test` + help coverage, JUnit for CI | |
| `pack about-check` | fields the Sutras show that the About text does not explain | |
| `pack bundle` / `pack verify` / `pack deploy` / `pack rollback` | a versioned, checksummed archive; verify offline; copy into a packs folder atomically; put the previous one back | Admin → Packs → Deploy |
| `pack publish` / `pack keygen` / `pack install` | sign into a registry (`tools/packreg/packreg.py`); make a key; install from the registry on a server | Admin → Packs |

`pack check` belongs in CI (exit `1` fails the build; `--junit DIR` for the report); `pack deploy` is idempotent (the same bundle twice
changes nothing) and keeps the replaced version for `pack rollback`. Schema files to a pack: [SCHEMA_TO_PACK_GUIDE.md](SCHEMA_TO_PACK_GUIDE.md).

### `sutra design --schema` and `pack make --schema`

Both take **JSON Schema** (draft 2020-12 or 07, JSON or YAML) and run the rules of [Schema to pack](SCHEMA_TO_PACK_GUIDE.md): kind, key, links, labels, formats, match column, strip, About text,
tests. `sutra design --schema FILE... [--kind K] [--out DIR]` prints the Sutras (or writes `<out>/<kind>/<name>.v1.sutra.yaml` and `<out>/tests/`); `pack make --schema FILE|DIR... [DATA] --name N`
writes the same folder as `pack make` below, for many kinds, with synthetic samples (named `sample-synthetic-N.json`) where no real documents are given. A JSON Lines file or folder after `--schema` is data.
Flags: `--key`, `--date`, `--match` (as `kind=FIELD`; `--match none`), `--mnemonic`, `--strip N`, `--count N` (design) / `--samples N` (make), `--code`, `--description`, `--connector`, `--yes` (never ask which
field is the key). The example schemas are in `docs/guides/examples/schemas/`; real output is in [Schema to pack](SCHEMA_TO_PACK_GUIDE.md#the-command-line).

### `pack make`: everything in one folder

`pack make` is `pack new` + `data ingest` + `pack check` + `pack bundle` + `pack verify` in one run, writing a single
folder (the tree and the options are in [Quickest path](#quickest-path-pack-make) at the top). It adds no logic of its own to
those tools (`tools/packmake.py` only chooses the key and the date, lays the folder out and writes `README.txt`,
`config/drishti-site.yaml`, `run-server.sh`, `run-server.ps1` and `MANIFEST.json`).

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank
read 4407 trade document(s)
key:   TRADE_ID  (named <kind>_id, present and unique in all 4407 documents)
date:  START_DATE  (the date-valued field with the fewest distinct dates (24); pass --date to choose another)
4407 documents, 1 kind(s), 6 group(s)
drafting 6 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
trade           PRODUCT_TYPE=CallNot                             50       5  trade-callnot
trade           PRODUCT_TYPE=Cash                               723       5  trade-cash
trade           PRODUCT_TYPE=ComFut                             883       5  trade-comfut
trade           PRODUCT_TYPE=ComSwap                           1994       5  trade-comswap
trade           PRODUCT_TYPE=Commodity                          757       5  trade-commodity
trade           (all)                                          4407       5  trade-default
...
== pack bundle
bundled my-bank 1.0.0: 47 files
...
== pack verify
  checksums ok / schema ok / server ok / sutra ok
verified

pack make: data/build/my-bank-1.0.0
  6 Sutra group(s) for 4407 trade document(s), 24 date(s) in data/delta; pack check passed, pack verify passed
next: read data/build/my-bank-1.0.0/README.txt; for a new server  DRISHTI_JAR=<exec jar> ...
```

Read the `date:` line. The test data's trades carry no business-date field (a file is one business day), so `pack make` took the date
field with the fewest distinct values, `START_DATE`, and stored each trade under its start date: 24 "business dates" that are not business
dates. For such snapshots, build the lake with [`lake build --as-of`](#7-lake-a-large-json-export-into-a-delta-lake) (or the demo lakes, `drishti.py lake demo`) and use
`pack make` for the pack alone; for documents that carry their business date, pass `--date` with that field.

Try it without touching anything of yours: `DRISHTI_JAR=... DRISHTI_PORT=18974 data/build/my-bank-1.0.0/run-server.sh`, then
`curl 'http://localhost:18974/api/v1/views/trade/<an id>?asOf=<a date>'`. Documents of another day are added with `data ingest`
(default mode `overwrite-dates`) into the same `data/delta`. Exit codes: 0 made and both checks passed, 1 made but a check failed
(the folder is still written), 2 usage (no unique key, a missing field, `--out` exists without `--force`).

### `pack new`: a complete pack from JSON Lines

Writes `config/packs/<domain>/<name>/` (or `--out`): `pack.yaml` (kinds, mnemonics, columns, and with a date a connector, routes and the
optional `ingest:` block), `samples/`, one Sutra per kind or `--match` group, `tests/<sutra>/` with `expect.yaml`,
`config/about.yaml` (kind titles and glossary entries with `TODO` text for you to fill in) and a `README.md`; then it runs
`sutra lint` and `sutra test` on it. With a dated store option it also **writes your documents into that store**, so the pack
has data on the day it is created.

On the MUREX snapshot of the test data (it has no business-date field, so no `--date`: the pack serves its samples and no dated store
is written), with `--out` outside `config/packs` so nothing of the server's changes:

```bash
drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/trades/murex/trade_murex.2026-07-08.jsonl.gz --name murex-book \
    --title "MUREX book" --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/murex-book
```

```text
4198 documents, 1 kind(s), 68 group(s)
drafting 68 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
trade           PRODUCT_TYPE=ACCRETING_SWAP                      72       5  trade-accreting-swap
trade           PRODUCT_TYPE=AMORT_SWAP                          72       5  trade-amort-swap
...
trade           PRODUCT_TYPE=RANGE_ACCRUAL_NOTE                  55       5  trade-range-accrual-note
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
| no `generated/` (a pack made without it) | nothing is merged: differing files are `no-baseline` (kept, exit 1), new files are added |

A worked run on the test data: a pack made from the ENDUR snapshot (five product types), two hand edits (the `ComSwap` Sutra's
priority set to 15, the About sentence of the kind written), then the data grows: the CALYPSO snapshot joins it.

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack new data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --name mb \
    --kind trade --match PRODUCT_TYPE --key TRADE_ID --out data/build/mb
4407 documents, 1 kind(s), 5 group(s)
...
$ drishti-console/.venv/bin/python tools/drishti.py pack regenerate data/build/mb data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz \
    data/testdata/trades/calypso/trade_calypso.2026-07-08.jsonl.gz --dry-run
pack regenerate --dry-run: data/build/mb
  add         sutras/trade/trade-abs.v1.sutra.yaml
  add         sutras/trade/trade-amort-bond.v1.sutra.yaml
  add         sutras/trade/trade-callable-bond.v1.sutra.yaml
  add         sutras/trade/trade-cds-index.v1.sutra.yaml
  add         sutras/trade/trade-cds-single.v1.sutra.yaml
  add         sutras/trade/trade-cds-swaption.v1.sutra.yaml
  add         ... and 267 more
  refresh     README.md
  refresh     pack.yaml
  refresh     samples/catalog.jsonl
  refresh     samples/trade.jsonl
  merge       config/about.yaml   (your edit and the new text merged cleanly)
  keep        sutras/trade/trade-comswap.v1.sutra.yaml   (your edit kept; the generated text did not change)
summary: 273 add, 1 keep, 1 merge, 4 refresh; 34 unchanged
(exit 0)
```

The priority edit is kept, the About edit is merged with the regenerated skeleton. Without `--dry-run` the same plan is written, then
`generated/` is replaced by what was just generated (not by the merged result, so the next run still sees your edits as yours). A real
conflict, here the generator now writes `priority: 20` on the line you changed to 15:

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack regenerate data/build/mb data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz \
    data/testdata/trades/calypso/trade_calypso.2026-07-08.jsonl.gz --priority 20
pack regenerate: data/build/mb
  add         sutras/trade/trade-abs.v1.sutra.yaml
  ...
  add         ... and 267 more
  refresh     README.md
  refresh     pack.yaml
  refresh     sutras/trade/trade-callnot.v1.sutra.yaml
  refresh     sutras/trade/trade-cash.v1.sutra.yaml
  refresh     sutras/trade/trade-comfut.v1.sutra.yaml
  refresh     sutras/trade/trade-commodity.v1.sutra.yaml
  refresh     ... and 2 more
  merge       config/about.yaml   (your edit and the new text merged cleanly)
  conflict    sutras/trade/trade-comswap.v1.sutra.yaml   (1 conflict(s): markers written, resolve them by hand)
summary: 273 add, 1 conflict, 1 merge, 8 refresh; 30 unchanged
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
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#8-deploy-promote-and-roll-back-by-copying).

```bash
drishti.py pack bundle config/packs/banking/trading --out dist                  # trading-1.0.0.tar.gz + .sha256 + .manifest.json (runs pack check first)
drishti.py pack verify dist/trading-1.0.0.tar.gz                # sha256, manifest checksums, schema, server version, sutra lint + test
drishti.py pack deploy dist/trading-1.0.0.tar.gz --to /opt/drishti/packs --backup /opt/drishti/backups [--dry-run]
drishti.py pack rollback trading --to /opt/drishti/packs --backup /opt/drishti/backups [--version 1.0.0]
# real output of each, on the shipped trading pack: OPERATIONS_GUIDE.md sections 3 to 5 and 10
```

`pack bundle` also takes `--no-check` (skip the `pack check` that runs first), `--requires-server VERSION` (the minimum server version written into the manifest; default this checkout's) and `--json`.
`pack verify` also takes a folder (drift check of a deployed pack) and `--no-sutra` (no Java), `--server-version V`, `--json`.
`pack deploy` copies to a temporary sibling, swaps by rename and keeps the previous version in the backup folder; it never
touches a running server. `pack verify --registry R --publisher P --public-key K` is still the registry check below.
Exit codes: 0 ok, 1 not verified or refused, 2 usage.

### `pack publish`, `pack keygen`, `pack verify --registry`: the registry (tools/packreg)

A registry is a folder (or a web location) of signed pack archives. `keygen` makes an Ed25519 key, `publish` zips a pack,
signs it and adds it, `verify` re-checks a registry against a publisher's public key.

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
(`drishti.packs.registry.trusted-keys.<publisher>`); see [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which).

### `pack install`: install from the registry on a running server

`pack install --list` shows what the server's registry offers; `pack install NAME VERSION` installs (or upgrades to) that
version after the server's checks, then loads it. Administrator only; needs a signed token (not a personal one).

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
$ drishti-console/.venv/bin/python tools/drishti.py data profile data/testdata/trades/murex/trade_murex.2026-07-08.jsonl.gz --kind trade
data profile: 1 file(s), 4198 document(s), 1 kind(s)

== trade: 4198 document(s), 107 field(s)
  field                                           type            cover  distinct  examples
  SOURCE_SYSTEM                                   string           100%         1  MUREX
  SOURCE_UPDATE_TIME                              string           100%         1  2026-07-08T18:30:00Z
  TRADE_DATE                                      string           100%       258  2025-07-25, 2025-02-06, 2025-04-28
  INPUT_DATE                                      string           100%       258  2025-07-25, 2025-02-06, 2025-04-28
  TRADE_ID                                        string           100%      4198  MX-20000001, MX-20000002, MX-20000004
  TRADE_STATUS                                    string           100%         3  AMENDED, VALIDATED, MATURED
  ...
  LEGS[].CASH_FLOWS[].RESETS                      array            100%         0
  EVENTS                                          array            100%         0
  ...
  key candidates (present and unique everywhere):
    TRADE_ID                     exact     present in all 4198 documents, 4198 different values
    TRANSACTION_ID               exact     present in all 4198 documents, 4198 different values
  date candidates (ISO dates in at least 95% of the documents):
    SOURCE_UPDATE_TIME           1 date(s) 2026-07-08 .. 2026-07-08, coverage 100%
    TRADE_DATE                   258 date(s) 2024-04-16 .. 2026-07-07, coverage 100%
    INPUT_DATE                   258 date(s) 2024-04-16 .. 2026-07-07, coverage 100%
    START_DATE                   227 date(s) 2024-04-18 .. 2026-07-09, coverage 100%
    MATURITY_DATE                308 date(s) 2026-09-30 .. 2038-02-01, coverage 100%
  match candidates (split the kind into screens; score 0..1):
    PRODUCT_TYPE                 1.000  coverage 100%, 68 values over 4198 documents (average 61.7 per value, evenness 1.00; largest group OIS = 72); name suggests a category
    PRODUCT_SUB_TYPE             1.000  coverage 100%, 68 values over 4198 documents (average 61.7 per value, evenness 1.00; largest group Overnight index swap = 72); name suggests a category
    COUNTERPARTY                 0.993  coverage 100%, 18 values over 4198 documents (average 233.2 per value, evenness 1.00; largest group CP-SUMMIT = 385)
    COUNTERPARTY_UEN             0.993  coverage 100%, 18 values over 4198 documents (average 233.2 per value, evenness 1.00; largest group UEN0100592 = 385)
    PORTFOLIO                    0.963  coverage 100%, 15 values over 4198 documents (average 279.9 per value, evenness 0.93; largest group BOOK-RATES-3 = 526)
    TT_BOOK                      0.963  coverage 100%, 15 values over 4198 documents (average 279.9 per value, evenness 0.93; largest group BOOK-RATES-3 = 526)
  suggested:
    python3 tools/drishti.py pack make data/testdata/trades/murex/trade_murex.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --key TRADE_ID --date SOURCE_UPDATE_TIME --name trade-pack
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
`*.jsonl` or single files, repeatable with `--from`. With `--pack` the domain, the columns promoted in the lake and the key field come
from the pack's `pack.yaml` (including the `ingest:` block); without it give `--domain` (and `--key`, `--columns`). Each file goes under
its business date by the load rules ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo)): `--as-of`, else its `.ready` file's
`business-date:`, else the date in its name (`trade.2026-11-03.jsonl`, kind `trade`), else the business today; the run says which, in
`lake build`'s words (*trade.2026-11-03.jsonl: business date 2026-11-03 from the file name*). `--date FIELD` (or the pack's ingest
`date`) is optional: it names the field the documents carry their own date in, and rows that say another date are only counted.

`--mode overwrite-dates` (the default) replaces only the dates in your input, so re-running a day is safe; `append` adds;
`replace` rebuilds the kind. Documents without a date or id are counted and left out, never silently dropped.

**The business date from outside.** Each file is one kind and one business date: `--as-of`, else its `.ready` file's `business-date:`,
else its name's date (`trade.2026-11-03.jsonl`: kind `trade`, 2026-11-03), else the business today. A document's own date field never
decides; documents whose own date differs are loaded and counted (*1 row of trade.2026-11-03.jsonl carried another date in AS_OF; loaded
under 2026-11-03*). `--date-from name` refuses a file whose name carries no date. `--mode` defaults to `append`. `lake build` and the data
inbox follow the same rules ([DATA_GUIDE.md section 2.8](DATA_GUIDE.md#8-feeds-the-table-and-business-date-of-a-file)).

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
python3 tools/drishti.py data ingest --watch drop --once --domain demo --key id --store files --root files --done-dir done
2026-10-05T16:44:48 INFO  watching drop for *.jsonl (mode append, state files/.drishti-ingest-state.json)
2026-10-05T16:44:48 INFO  new file trade.2026-10-05.jsonl (72 bytes): ingesting
2026-10-05T16:44:48 INFO  trade.2026-10-05.jsonl: 2 row(s) ingested
2026-10-05T16:44:48 INFO  trade.2026-10-05.jsonl: moved to done/trade.2026-10-05.jsonl
2026-10-05T16:44:48 INFO  pass finished: 1 file(s) ingested, 0 with problems
```

Cron: `*/5 * * * * cd /opt/drishti && python3 tools/drishti.py data ingest --watch /data/drop --once --pack config/packs/my-bank --lake /data/delta --done-dir /data/done`.
As a service run it without `--once`; Ctrl-C stops it cleanly.

### `data landed` and `data loads`: tell the server a batch landed

Your ETL loads the data; Drishti reads it where it lives. When a batch is in place, **one call** makes the server refresh what it caches, check
the data is readable for the date, evaluate the alert rules on it and tell the people the pack names. See
[DATA_GUIDE.md](DATA_GUIDE.md#1-telling-drishti-new-data-has-landed) for the concepts, the API, expectations and late data, and recipes (Airflow, cron, DishtaYantra).

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

### `data get`: a dataset as `.jsonl.gz` (Export data)

Export data (`EXPORT <GO>`) from a terminal: a dataset's rows on a date, the columns you choose and filter lines joined by AND, streamed to
a `.jsonl.gz` file (never held whole), as the token's user sees them. The rules (operators, masks, internal-only datasets, pushdown, the
audit) are in [DATA_GUIDE.md](DATA_GUIDE.md#6-export-data-download-a-dataset).

```
drishti.py data get [--json]                                    the datasets you may download: pack, format, dates held
drishti.py data get DATASET [--date D] [--columns A,B,C] [--where "LINE"]... [-o FILE | -o -] [--count [--json]] [--wait]
```

- `--date` is `yyyy-MM-dd` (or `today` / `yesterday`), one the dataset holds; without it, its latest date.
- `--columns` comma-separated; without it, every column you may see (a masked column is left out).
- `--where` once per line: `"DESK = DESK-COMM"`, `"STATUS != DEAD"`, `"PRODUCT_TYPE in ComSwap,ComFut"`, `"NAME contains bank"`,
  `"PORTFOLIO starts BOOK-COMM"`, `"MTM > 1000000"`, `"PNL_1D < 0"`, `"DV01 between 1 and 5"`, `"NETTING_SET is empty"`,
  `"COUNTERPARTY is not empty"`.
- `-o` the file (default `<dataset>_<date>.jsonl.gz`; `-` writes to standard output). The file appears only when the download is
  complete (it is written as `<file>.part` first); a refusal leaves no file.
- `--count` prints the selection in words, the SQL-like line, the row count and the estimated size, and downloads nothing.
- `--wait`: when the server is busy (`503 DRS-9503`, busy protection) the download waits the server's `Retry-After` and starts again by
  itself, three times at most, saying so in the server's words. `DRISHTI_WAIT_BUSY=1` does the same for every command
  ([OPERATIONS_GUIDE.md section 12.5](OPERATIONS_GUIDE.md#147-busy-protection-refuse-politely-never-fall-over)).

```
$ python tools/drishti.py data get trade-valuation --date 2026-09-30 --columns TRADE_ID,PRODUCT_TYPE,DESK,MTM \
      --where "DESK = DESK-COMM" --where "PRODUCT_TYPE in ComSwap,ComFut" --where "MTM > 1000000" --count
From trade-valuation on 2026-09-30: 4 columns, rows where DESK is DESK-COMM, PRODUCT_TYPE is one of ComSwap or ComFut, and MTM is greater than 1000000.
SELECT TRADE_ID, PRODUCT_TYPE, DESK, MTM
FROM trade-valuation  -- 2026-09-30
WHERE DESK = 'DESK-COMM' AND PRODUCT_TYPE IN ('ComSwap', 'ComFut') AND MTM > 1000000
rows 412 of 12,076; about 9,870 bytes as .jsonl.gz; pushed down to the connector
$ python tools/drishti.py data get trade-valuation --date 2026-09-30 --columns TRADE_ID,PRODUCT_TYPE,DESK,MTM \
      --where "DESK = DESK-COMM" --where "PRODUCT_TYPE in ComSwap,ComFut" --where "MTM > 1000000"
trade-valuation_2026-09-30.jsonl.gz: 9,912 bytes. From trade-valuation on 2026-09-30: 4 columns, rows where DESK is DESK-COMM, ...
```

Exit `0`, `1` when the server refuses (an internal-only dataset where it is refused: `403 DRS-5002`; a date the dataset does not hold:
`400 DRS-4003`; a line it cannot read: `400 DRS-5001`; the server busy and no `--wait`, or still busy after three tries: `503 DRS-9503`),
`2` for a usage error. Every download is audited on the server.

### `testdata capture-market`: the market snapshot for offline demos

On a machine with internet, captures the news-and-markets pack's real quotes (the twenty companies, a year of daily bars) and
headlines (the companies and the fifteen topics) through Drishti's own `yfinance` and `news` connectors, on a scratch server it starts
from the built jar (`--server URL` uses yours), two seconds apart with backoff on 429, into
`data/testdata/news-and-markets/{quote,news}/<kind>.<date>.jsonl.gz` (`quote.2026-10-18.jsonl.gz`); `lake demo` then builds them into the tables `news-store`
serves offline (`DRISHTI_OFFLINE=true`). `--dry-run` lists the 91 ids without fetching. Everything else:
[data/testdata/README.md](../../data/testdata/README.md#the-market-snapshot-news-and-markets-offline).

```bash
drishti-console/.venv/bin/python tools/drishti.py testdata capture-market
```

### `data load`: the demo data, at three sizes

`data load --store <store>` runs `tools/load-<store>.sh`, which builds the **shipped banking packs' demo data** (1,791 sample documents
x 10 business days) in that store, and with `--trades N` a generated trade book of N trades a day for `--days` days. Stores:
`delta`, `files`, `postgres`, `duckdb`, `iceberg`, `mongodb`, `redis`, `aerospike`, `cassandra`. The scripts run from the repository root, so
a relative root is relative to it. Options after the known ones go to the script unchanged. Everything about the scripts, the
stores and the sizes is in [DATA_GUIDE.md section 11](DATA_GUIDE.md#11-the-demo-data).

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

A real small run (200 trades, one day) into a scratch folder; the scripts write under the data folder (`DRISHTI_DATA_DIR`, else
the repository's `data/`, which is not tracked) and never rewrite the shipped packs' samples. Each loader's heap is
`DRISHTI_LOADER_HEAP` (default `4g`). Its input is loader rows by the load rules ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo)):
`make_data.py` and `bulk_trades.py` write tars of `<kind>/<YYYY-MM-DD>/` folders under `data/build/loader-rows/`, removed after the
load, and the loader files each row under its folder's date and says so:

```text
feeds: data/feeds/fixing.jsonl
jsonl: 25430 rows in data/build/loader-rows/banking.tar (<kind>/<YYYY-MM-DD>/rows.jsonl; tools/load-postgres.sh, load-aerospike.sh and the other store loaders load it)
files: banking.tar: business dates 2026-09-17 to 2026-09-30 (10 dates) from the archive's folders
files: wrote 25,430 rows into 450 files under data/demo-files in 1 s

200 of 200 trade-days written (3 s)
200 trade-days as JSON lines in 3 s
files: bulk-trades.tar: business date 2026-09-30 from the archive's folders
files: wrote 200 rows into 1 files under data/demo-files in 0 s
```

**Which loader when.** `data ingest` is for a demo or a small team: use [`lake build`](#7-lake-a-large-json-export-into-a-delta-lake) instead for JSON
arrays, large files, classifier partitions or a lock and a run summary, and `data ingest --store files` for the File connector. The
store loaders `data load` runs are listed in [section 15](#15-store-loaders-toolsload-sh). From cron, `data ingest --watch DIR --once`
is a drop folder polled every few minutes (exit `1` when a file failed); `data landed` is the last step of any ETL job that loads a
store itself.

### `dataload`: the data inbox

The data inbox from the command line ([DATA_GUIDE.md section 7](DATA_GUIDE.md#7-the-data-inbox-drop-files-and-they-load): files
dropped in a folder, loaded by the server; module `tools/dataloadcli.py`). Administrators; a token needs `loads:write` for `run`,
`retry` and `undo`.

| Command | What it does |
|---|---|
| `dataload status [--json]` | the inbox on or off, the node, the next cycle, the folder lock's holder; every file waiting and why (ready, not stable yet, no `.ready` yet, refused at the next cycle), markers without a file; the failed files with their reasons; the last cycles (done, skipped and why, failed) with their files' outcomes and notices |
| `dataload run` | Load now: a cycle starts at once; while a run still loads none starts and it says *skipped: run 20261103T150000Z-drishti-main still loading (12 min)* (runs never overlap) |
| `dataload retry FILE` | a failed file and its `.ready` back into the inbox (an empty `.ready` when it had none); the next cycle, or `run`, loads it |
| `dataload undo LOAD [--lake NAME] [--yes]` | removes one load's rows from the lake it went to, found in the ledger |

```bash
drishti-console/.venv/bin/python tools/drishti.py dataload status
drishti-console/.venv/bin/python tools/drishti.py dataload run
drishti-console/.venv/bin/python tools/drishti.py dataload retry trades-endur-2026-11-03.jsonl.gz
```

Exit codes: `0` done, `1` the server said no (a file that is not in `failed/`), `2` usage, `3` the inbox is off on every server (`DRS-1020`).

## 7. `lake`: a large JSON export into a Delta lake

| Command | What it does |
|---|---|
| `lake build` | a JSON export of any size into a Delta (or Iceberg) table, as a stream, with a lock, a log line and a summary (this section) |
| `lake scan` | what an export holds, and which fields make a good id, date and classifier ([7.1](#71-look-first-lake-scan)) |
| `lake status` | a lake's tables, its lock and its last runs ([7.7](#77-exit-codes-the-lock-the-log-and-the-summary)) |
| `lake publish` | a built day committed into a Delta table in S3 ([7.11](#711-lake-publish-a-built-day-into-a-delta-table-in-s3)) |
| `lake demo` | every demo lake from the committed test data ([7.10](#710-the-demo-lakes-lake-demo)) |
| `lake show`, `lake check`, `lake migrate-config` | the lakes and tables as the server reads them; connectors that name known lakes and lake files that match the disk; lake files from connector files that still describe their lake |
| `lake maintain` | keeps a lake small and fast, on a timer ([OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md)) |
| `lake relocate` | a lake built in the older layout moved once into its own folder, `<data>/delta/<lake>` ([DATA_LAKE_GUIDE.md section 7](DATA_LAKE_GUIDE.md#7-the-demo-lakes-one-time-move)) |
| `lake undo` | one load's rows removed by its load id ([DATA_LAKE_GUIDE.md section 5.5](DATA_LAKE_GUIDE.md#55-undo-a-load)) |
| `lake memory` | the in-memory lake's stores, jobs and refills (`status`, `jobs`, `refill`; [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#13-the-in-memory-lake)) |

`drishti.py lake build` reads an export of any size (a JSON array, pretty-printed or on one line, JSON Lines, either gzipped, one
file or a folder of them, or a `.zip`, `.tar.gz`, `.tgz` or `.tar` archive of them) **as a stream**, and writes it as a Delta table
the Delta connector reads:

* one row per document: `id` (`--key`, a dotted path), `doc` (the **whole** document as JSON: nested arrays such as `LEGS[]` and
  `LEGS[].CASH_FLOWS[]` stay inside it, as the Sutras expect), `business_date` (each file's: `--as-of`, else its `.ready` file's
  `business-date:`, else its name's date, `trades.2026-11-03.jsonl.gz`, else the business today; a document's own `--date` field is only
  counted when it differs: [DATA_GUIDE.md section 2.8](DATA_GUIDE.md#8-feeds-the-table-and-business-date-of-a-file));
* partitioned by `business_date` and then any number of **classifier** columns (`--partition-by PRODUCT_TYPE[,DESK...]`, up to
  four), as Hive folders `business_date=2026-10-16/PRODUCT_TYPE=ComSwap/`;
* **promoted** columns (`--promote COUNTERPARTY,PORTFOLIO,QUANTITY`): real columns a reader filters on without parsing the
  document (DOUBLE when every value is a number, else STRING);
* at `<lake>/<domain>/<kind>/`, the folder a Delta connector with `root: <lake>` and `domain: <domain>` reads for that kind.

**With a lake file** (`--lake NAME`, a lake of `config/lakes/`: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md)) the table's place, partitions, promoted
columns, vectors, id, date, latest-by and load mode come from the file, read by the one lake reader (`tools/lake/lakeconfig.py` runs
drishti-lakeconfig's `LakesCli`). A pack's own recommended entries (its `pack.yaml` `lake:` block) and its templates' `lake: demo` and `lake-tables:` are read and written by `tools/packlake.py`, the one module the pack generators and every tool that reads a template's layout share. A `--partition-by`, `--promote`, `--vector` or `--key` that says otherwise is refused, naming both; a kind
the file has no entry for is refused. `--lake-root DIR` writes the same layout under another folder (a scratch or test lake). `--lake DIR`
alone is the older form: the options and the pack decide. A table whose lake file says `load-mode: append` is written by the parallel
loader only (`--engine java --batch-id ID`: the batch recorded in each date's commit, never appended twice); the Python path refuses it.

```bash
python3 tools/drishti.py lake build --from drop/endur-2026-10-28.jsonl.gz --as-of 2026-10-28 --pack config/packs/banking/trading \
    --connector trade-endur --lake demo                  # partitioned by business_date and PRODUCT_TYPE, as config/lakes/demo.yaml says
python3 tools/drishti.py lake show                       # every lake and table as the server reads them
python3 tools/drishti.py lake check                      # connectors name known lakes; lake files match the tables on disk
python3 tools/drishti.py lake migrate-config [--write]   # lake files from connector files that still describe their lake
```

Memory stays flat: documents are decoded one at a time, staged on disk per business date and partition, and written in batches
bounded by rows (`--batch-rows`, 50,000) and bytes (`--batch-mb`, 128). The staged rows, and the Java writer's own temporary
files, go in a folder beside the lake (never the system temp folder), removed when the run ends. Sizing a large load: [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md).

**Archives.** An archive is read in place, its JSON Lines members in name order, by the rule every loader shares
([DATA_GUIDE.md section 2.7](DATA_GUIDE.md#7-archives-zip-and-tarballs)): other members are skipped with a note; a build writes one
table, so an archive's folders, if it has any, must all be that table's name (`--table`, else the kind), and any other folder is
refused and named; unsafe names, links, nested archives and zip bombs are refused before a row is read. A reject names its member:
`trades.zip!/part-2.jsonl:14`. `tools/build_demo_lakes.py --endur-dir` takes the same feeds (`trade_endur.2026-10-28.jsonl.zip`, or a tar
of `trade_endur/<date>/` folders), and `tools/samplegen/load_rows.py --rows` a tar of lake rows, a `<table>/<YYYY-MM-DD>/` folder per table and date.

### 7.1 Look first: lake scan

`lake scan` reads the export as a stream and lists every field (dotted paths to depth 3; arrays are counted, not entered), how
often it is present, its types and most frequent values, then suggests an id, a business date and classifiers:

```
$ drishti-console/.venv/bin/python tools/drishti.py lake scan data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --limit 500 | tail -n 8
PRECIOUS_METAL_FLAG                    500         2  text  (456), Y(44)
TT_GROUP                               500         1  text  (500)
TT_BOOK                                500         1  text  (500)
LEASE_PERPETUITY_FLAG                  500         3  text  (477), N(12), Y(11)
SETTLEMENT_DATE                        500         1  text  (500)
LEGS                                   500         0  array
EVENTS                                 500         0  array
suggested --key TRADE_ID   --partition-by: PRODUCT_TYPE, PRODUCT_SUB_TYPE, PORTFOLIO   the rows' date field: START_DATE (counted only: the business date is the file name's or --as-of)
```

`(456)` is a blank value counted 456 times. The id is a field present in (nearly) every document with every value different;
the rows' date field ranks names such as *business*, *cob*, *as of* and *snapshot* first, and is only reported: a load takes its
business date from outside the rows ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo)), the file's name
(`trade_endur.2026-07-08`) or `--as-of`; the classifiers are text fields rarely blank with 2 to 40 values, *product* and *type* first.
The walk-through below uses `--as-of` for another day, 2026-09-22.

### 7.2 The first load

```
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz \
    --kind trade --key TRADE_ID --as-of 2026-09-22 --partition-by PRODUCT_TYPE --promote COUNTERPARTY,PORTFOLIO,QUANTITY \
    --lake lake --domain trading
engine: drishti (Drishti's Java writer in drishti-server-<version>-exec.jar)
business date  PRODUCT_TYPE           rows
2026-09-22     CallNot                  50
2026-09-22     Cash                    723
2026-09-22     ComFut                  883
2026-09-22     ComSwap                1994
2026-09-22     Commodity               757
documents read: 4407, rows written: 4407, rejected: 0
summary: lake/_drishti-lake/runs/20261017T174352-1339862.json
2026-10-17T17:43:55-04:00 lake-build ok exit=0 run=20261017T174352-1339862 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.614
```

The last line is **the** log line of the run (also appended to `<lake>/_drishti-lake/runs.log`): one line per run, whatever
happened, for a scheduler's log and for `grep`. `--dry-run` reads and counts and writes nothing; `-q` prints only the log line;
`--json` prints the whole summary. With the server jar built (a fresh clone after step 2 of the quick start) the builder picks
Drishti's own writer; without it, `deltalake` (section 7.6).

The run also leaves `<data>/bi-store/lake-rebuilt.json` (it was given no `--signal`): a server started afterwards clears the BI columns and
rollups it had read from a lake, so it never answers from the data before this build; `--no-stamp` leaves none (section 7.8).

### 7.3 The next day, with rejects

The next file of the book holds the day's changes (159 trades). Two bad lines added at its end show what a reject looks like:

```
$ (zcat data/testdata/trades/endur/trade_endur.2026-09-23.jsonl.gz; echo '{"PRODUCT_TYPE": "Cash"}'; echo 'not json') | gzip > day2.jsonl.gz
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from day2.jsonl.gz --kind trade --key TRADE_ID --as-of 2026-09-23 --partition-by PRODUCT_TYPE \
    --promote COUNTERPARTY,PORTFOLIO,QUANTITY --lake lake --domain trading
engine: drishti (Drishti's Java writer in drishti-server-<version>-exec.jar)
business date  PRODUCT_TYPE           rows
2026-09-23     Cash                     27
2026-09-23     ComFut                   29
2026-09-23     ComSwap                  75
2026-09-23     Commodity                28
documents read: 161, rows written: 159, rejected: 2
  rejected 1: no id (field 'TRADE_ID' is missing or empty)
  rejected 1: not JSON (Expecting value)
    e.g. day2.jsonl.gz:160: no id (field 'TRADE_ID' is missing or empty)
    e.g. day2.jsonl.gz:161: not JSON (Expecting value)
summary: lake/_drishti-lake/runs/20261017T174355-1339986.json
2026-10-17T17:43:57-04:00 lake-build partial exit=2 run=20261017T174355-1339986 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=159 rejected=2 dates=1 partitions=4 seconds=2.201
```

A reject never stops the run: it is counted per reason, the first 50 are kept with where they were (`file:line` for JSON Lines,
`file[n]` for an element of an array), and the run exits `2` so a scheduler can tell "loaded, look at the rejects" from "all
good". The reasons: not JSON, not a JSON object, no id, an id that is an object, no business date, a date that is not a date
(ISO dates and date-times, `yyyymmdd` as text or a number are dates).

**Duplicates: one rule for both paths.** When the same record (the same id on the same business date) appears more than once in
one load, **the latest wins**: the copy with the latest `COMPUTED_AT` is kept (`--latest-by FIELD` or the pack's `ingest.<kind>.latest-by`
names another ISO-timestamp field; `--latest-by ''` turns it off); without one, or on a tie, **the last one read** is kept, in one
deterministic order: the input files sorted by their path (whatever order `--from` names them; `day.jsonl` sorts before
`day.jsonl.gz`), then line order. A copy with a timestamp beats one without. The other copies are dropped and **counted**:
`duplicates` (per date) in the summary, with up to ten `duplicateExamples` (`date`, `kept` and `dropped` as `file:line`), and a line
each on the console. Duplicates **never fail a load** and do not change the exit code; `accepted` still counts every valid document,
`rows` counts what was written. The parallel loader (section 7.13) applies the identical rule, so both paths write the same table.
The timestamp is ISO-8601 text (a date, or a date-time with optional seconds, fraction and offset; no offset is UTC); any other value
counts as none.
(The table holds what each file holds: the changes file is a day's changes, so the 2026-09-23 date has 159 trades. `lake demo`
applies each day's changes to the previous day by key and writes the whole book every day: section 7.10.)

### 7.4 Re-running a day replaces that day only

```
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz ... --lake lake --domain trading -q
2026-10-17T17:44:01-04:00 lake-build ok exit=0 run=20261017T174358-1340108 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.14
$ find lake -maxdepth 4 -type d | sort
lake
lake/_drishti-lake
lake/_drishti-lake/runs
lake/trading
lake/trading/trade
lake/trading/trade/business_date=2026-09-22
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=CallNot
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=Cash
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=ComFut
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=Commodity
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=ComSwap
lake/trading/trade/business_date=2026-09-23
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=Cash
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=ComFut
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=Commodity
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=ComSwap
lake/trading/trade/_delta_log
```

| `--mode` | What a run does to the dates in its input | Re-run safe |
|---|---|---|
| `overwrite-dates` (default) | replaces every partition of each business date present; every other date is kept | yes |
| `overwrite-partitions` | replaces only the (date, classifier values) present: a file per product type per day replaces only its own product | yes |
| `append` | adds the rows | no: a re-run adds them again |

A table keeps the partitioning it was created with; a run that asks for another fails before reading anything:

```
$ ... lake build --from data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --key TRADE_ID --as-of 2026-09-22 --partition-by PORTFOLIO --lake lake --domain trading
lake build: lake/trading/trade is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)
summary: lake/_drishti-lake/runs/20261017T174401-1340283.json
2026-10-17T17:44:01-04:00 lake-build failed exit=1 run=20261017T174401-1340283 domain=trading kind=trade mode=overwrite-dates seconds=0.0 error="lake/trading/trade is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)"
```

### 7.5 Iceberg instead of Delta: `--format iceberg`

Delta is the default format of `lake build` and of every pack. `--format iceberg` writes the same staged rows into an Apache Iceberg
table `<lake>/<domain>/<table>` with Drishti's Iceberg loader (in the server's exec jar; `DRISHTI_ICEBERG_CLASSPATH` in development):
the documents, the promoted columns and the vector columns (`--vector PNL`: `list<double>`) exactly as for Delta. Only
`overwrite-dates` (each business date of the input replaces that date in one Iceberg commit); `--partition-by` columns are kept as
columns, the table being partitioned by business date alone. `--lake s3://bucket/prefix` writes to S3 (staged in a local scratch
folder, no run record in the lake), and `--iceberg-set KEY=VALUE` (repeatable) passes any loader setting (`s3.region`, or a REST catalog
such as AWS Glue: `catalog=rest`, `uri=…`, `warehouse=…`, `catalog.rest.signing-name=glue`). The build record makes the table Drishti's
to maintain ([OPERATIONS_GUIDE.md › Lake maintenance: Admin → Lakes → Maintain and `lake maintain`, section 8](OPERATIONS_GUIDE.md#163-iceberg-lakes)).

```bash
python3 tools/drishti.py lake build --from data/testdata/market-risk/pnl-vector/ --pack config/packs/banking/market-risk --connector risk-store \
    --kind pnl-vector --lake data/iceberg --format iceberg
```

### 7.6 The two engines

`--engine auto` (the default) uses **Drishti's own Java writer** when it finds the server's exec jar with the writer in it, and the
Python `deltalake` package otherwise; the first line of the output says which ran and why. `--engine drishti` or
`--engine deltalake` insists on one. A third, `--engine java`, runs the whole build, reading included, in the parallel lake
loader, for Delta and Iceberg alike, only when you ask for it: the Python path stays the default (section 7.13).

| | Drishti's writer (`drishti`) | `deltalake` (delta-rs) |
|---|---|---|
| Runs as | `java -jar drishti-server-<v>-exec.jar lake write --manifest M` (started by the builder) | the builder's own Python |
| Needs | Java 21 and the server jar | `deltalake` and `pyarrow` (in the console's environment) |
| Commits | **one** per table per run: the replaced files are removed and the new ones added in the same version, so a reader sees all of the load or none of it | one per batch; the first batch of a replaced date replaces it |
| Where | local disk (Windows paths too, no `winutils.exe`) | local disk and object storage |
| Heap / memory | `DRISHTI_LAKE_HEAP` (default `1g`) | the batch (`--batch-mb`) plus Arrow's copy |
| Row groups and pages | `DRISHTI_LAKE_ROW_GROUP_KB` (default `256`; `DRISHTI_LAKE_ROW_GROUP_MB` also read) and `DRISHTI_LAKE_PAGE_KB` (default `64`): a single entity's read decodes its row group, so small groups make first reads cheap (256 KB: 13 % more reads a second than 1 MB on 362,804 trades, 2026-10-18) | delta-rs defaults |
| Flattened fields | the pack's `ingest.<kind>.flatten` (`NAME: LEGS[0].FIELD`): each a plain field of the document (an existing field is kept) and a promoted column. An existing table keeps its columns: delete it and load again to add them | the same (staged before either engine writes) |

The same day through `deltalake`, into a second lake; then the table Drishti's writer made (one commit per run: versions 0 to 2 for
the three runs above), read back with deltalake: the two engines write the same table.

```
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --key TRADE_ID \
    --as-of 2026-09-22 --partition-by PRODUCT_TYPE --promote COUNTERPARTY,PORTFOLIO,QUANTITY --lake lake2 --domain trading --engine deltalake
engine: deltalake (the Python deltalake package)
business date  PRODUCT_TYPE           rows
2026-09-22     CallNot                  50
2026-09-22     Cash                    723
2026-09-22     ComFut                  883
2026-09-22     ComSwap                1994
2026-09-22     Commodity               757
documents read: 4407, rows written: 4407, rejected: 0
summary: lake2/_drishti-lake/runs/20261017T174401-1340285.json
2026-10-17T17:44:02-04:00 lake-build ok exit=0 run=20261017T174401-1340285 domain=trading kind=trade engine=deltalake mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=0.828
$ ls lake/trading/trade/_delta_log lake2/trading/trade/_delta_log
lake/trading/trade/_delta_log:
00000000000000000000.crc  00000000000000000000.json  00000000000000000001.crc  00000000000000000001.json  00000000000000000002.crc  00000000000000000002.json

lake2/trading/trade/_delta_log:
00000000000000000000.json  00000000000000000001.json  00000000000000000002.json  00000000000000000003.json  00000000000000000004.json
$ drishti-console/.venv/bin/python -c "from deltalake import DeltaTable; t = DeltaTable('lake/trading/trade'); d = t.to_pyarrow_dataset(); \
    print('version', t.version(), 'partitions', t.metadata().partition_columns, 'rows', d.count_rows()); print(d.schema)"
version 2 partitions ['business_date', 'PRODUCT_TYPE'] rows 5169
id: string
doc: string
business_date: date32[day]
PRODUCT_TYPE: string
COUNTERPARTY: string
PORTFOLIO: string
QUANTITY: double
```

(Read a table with `to_pyarrow_dataset()` as above.)

### 7.7 Exit codes, the lock, the log and the summary

| Exit | Meaning | What a scheduler should do |
|---|---|---|
| `0` | written, nothing rejected (and, with `--signal`, the server was told) | nothing |
| `2` | written, but some documents were rejected, or the data-load signal failed | alert someone to read the summary's `rejects` (or re-send the signal: `drishti.py data landed`) |
| `1` | failed: nothing was committed for the table (a bad option, an unreadable file, another partitioning, the engine failed) | alert; fix and re-run (safe) |
| `3` | another load of the same table is running (or `lake maintain` holds the lake) | try again later; never kill the other run |

**The lock.** One load per **table** at a time; loads of different tables of one lake run side by side (ENDUR's and CALYPSO's
trades as they land, under one root). A load takes a shared lock on `<lake>/_drishti-lake/lock` and an exclusive one on
`<lake>/_drishti-lake/locks/<domain>/<table>.lock`; `lake maintain` takes the lake's lock exclusively, so it waits for every load
and no load starts while it runs. The operating system releases a lock when the process ends, so a run that crashed or was killed
never blocks the next one; the table's lock file names the holder. On Windows, whose file locks have no shared mode, a load takes
the whole lake (loads there run one after another). A second load of the same table started 0.5 s after the first
(captured 2026-10-18):

```
2026-10-18T14:52:08-04:00 lake-build locked exit=3 run=20261018T145208-2322240 domain=trading kind=trade mode=overwrite-dates seconds=0.026 error="table trading/trade_endur is locked by {\"pid\": 2322204, \"host\": \"oryxpro\", \"since\": \"2026-10-18T14:52:08-04:00\"}"
```

and ENDUR and CALYPSO loaded side by side into one lake, finishing a second apart:

```
2026-10-18T14:52:00-04:00 lake-build ok exit=0 run=20261018T145157-2322020 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.31
2026-10-18T14:52:01-04:00 lake-build ok exit=0 run=20261018T145158-2322023 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4936 rejected=0 dates=1 partitions=51 seconds=3.624
```

**The log and the summary.** Every run appends its log line to `<lake>/_drishti-lake/runs.log` and writes its summary to
`<lake>/_drishti-lake/runs/<run>.json` and `<lake>/_drishti-lake/last-run.json` (and `--summary FILE`). Staged rows go to
`<lake>/_drishti-lake/staging/<run>/` while the run lasts and are removed after it (`--keep-staging` keeps them); a crashed run's are
removed by the next run, which holds the lock. With `--lake NAME` (a lake of `config/lakes/`) a run refused before its table is known (a `--partition-by` that contradicts the lake file, a kind it has no entry for, a lake file that cannot be read) keeps its summary in the lake's own folder (`--lake-root`, else the root of the lake file) or, when that is not known, under `<data>/lakes/<name>/_drishti-lake/`: never in a folder of the current directory. A summary (the run of 4.3, two of its four partitions shown):

```json
{
 "run": "20261017T174355-1339986",
 "status": "partial",
 "mode": "overwrite-dates",
 "pack": null,
 "domain": "trading",
 "kind": "trade",
 "key": "TRADE_ID",
 "promote": ["COUNTERPARTY", "PORTFOLIO", "QUANTITY"],
 "partitionBy": ["PRODUCT_TYPE"],
 "asOf": "2026-09-23",
 "table": "lake/trading/trade",
 "rows": {"2026-09-23": 159},
 "partitions": [
  {"date": "2026-09-23", "values": {"PRODUCT_TYPE": "Cash"}, "rows": 27},
  {"date": "2026-09-23", "values": {"PRODUCT_TYPE": "ComFut"}, "rows": 29}
 ],
 "documents": 161, "accepted": 159, "rejected": 2,
 "rejects": {
  "byReason": {"no id (field 'TRADE_ID' is missing or empty)": 1, "not JSON (Expecting value)": 1},
  "first": [{"where": "day2.jsonl.gz:160", "reason": "no id (field 'TRADE_ID' is missing or empty)"},
            {"where": "day2.jsonl.gz:161", "reason": "not JSON (Expecting value)"}]
 },
 "duplicates": {}, "duplicateExamples": [], "latestBy": "COMPUTED_AT",
 "files": [{"file": "day2.jsonl.gz", "bytes": 148726}],
 "sampleId": "1000029", "sampleDate": "2026-09-23",
 "timings": {"readSeconds": 0.035, "writeSeconds": 2.159, "totalSeconds": 2.201},
 "engine": "drishti",
 "engineWhy": "Drishti's Java writer in drishti-server-<version>-exec.jar",
 "batchRows": 10565, "averageDocumentBytes": 12703,
 "commit": {"commits": 1, "version": 1, "files": 4},
 "columns": {"partition": ["PRODUCT_TYPE"], "promoted": {"COUNTERPARTY": "string", "PORTFOLIO": "string", "QUANTITY": "double"}},
 "stamp": "<data>/bi-store/lake-rebuilt.json",
 "exitCode": 2,
 "peakMemoryMb": 35.6
}
```

`lake status` reads them back, lists the lake's tables and says whether a run holds the lock now:

```
$ drishti-console/.venv/bin/python tools/drishti.py lake status --lake lake
tables (1):
  table          rows   files  size    partitions        dates  latest date  last build
  trading/trade  5,169  9      7.5 MB  9 (PRODUCT_TYPE)  2      2026-09-23   2026-10-17 21:54:10Z
lock: free
runs (5 logged, last 5):
  2026-10-17T17:43:55-04:00 lake-build ok exit=0 run=20261017T174352-1339862 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.614
  2026-10-17T17:43:57-04:00 lake-build partial exit=2 run=20261017T174355-1339986 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=159 rejected=2 dates=1 partitions=4 seconds=2.201
  2026-10-17T17:44:01-04:00 lake-build ok exit=0 run=20261017T174358-1340108 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.14
  2026-10-17T17:44:01-04:00 lake-build failed exit=1 run=20261017T174401-1340283 domain=trading kind=trade mode=overwrite-dates seconds=0.0 error="lake/trading/trade is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)"
  2026-10-17T17:54:10-04:00 lake-build ok exit=0 run=20261017T175408-1353448 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=2.575
last run: ok (exit 0), summary lake/_drishti-lake/last-run.json
```

### 7.8 A pack, a connector and the data-load signal

With `--pack DIR` the builder takes from the pack's `pack.yaml` what it declares: the domain and the promoted columns of its Delta
connector (`settings.domain`, `settings.layout.<kind>.columns`), and the key, date and flattened fields of its `ingest:` block
(written by `pack make` and `pack new`; the trading pack has `ingest.trade.key: TRADE_ID`, so `--key` may be left out, and
`ingest.trade.flatten` adds `NOTIONAL` and `INDEX_NAME` from the first leg, [7.6](#76-the-two-engines)). Options on the command line win.

```yaml
# config/packs/banking/trading/pack.yaml (the parts lake build reads; one Delta connector per source system)
pack: trading
connectors:
  trade-endur:
    plugin: delta
    kinds: [trade]
    settings:
      root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}"
      domain: trading
      table.trade: trade_endur
      layout.trade.columns: "TRADE_ID,SOURCE_SYSTEM,PRODUCT_TYPE,...,COUNTERPARTY,LEGAL_ENTITY,PORTFOLIO,TRADER_ID"
      union: "true"
  trade-calypso: ...
  trade-murex: ...
```

The pack has three Delta connectors for `trade`, so the run names one with `--connector` (it picks the table, `trade_endur`, and the
sixteen promoted columns); captured as a dry run:

```bash
drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --pack config/packs/banking/trading \
    --kind trade --connector trade-endur --key TRADE_ID --as-of 2026-09-22 --partition-by PRODUCT_TYPE --lake lake3 --dry-run --json
# "pack": "trading", "domain": "trading", "kind": "trade", "table": "lake3/trading/trade_endur", "promote": ["TRADE_ID", "SOURCE_SYSTEM",
# "PRODUCT_TYPE", ... "TRADER_ID"] (16), "documents": 4407, "status": "dry-run", "exitCode": 0
```

In production the same run names the real lake and tells the server (the ETL's last step):

```bash
drishti-console/.venv/bin/python tools/drishti.py lake build --from /srv/drop/endur-2026-10-16.json.gz --pack config/packs/banking/trading --kind trade --connector trade-endur \
    --key TRADE_ID --as-of 2026-10-16 --partition-by PRODUCT_TYPE --lake /srv/drishti/delta \
    --signal http://drishti.internal:18480 --token-file /etc/drishti/loads.token
```

The connector reads the lake once its file says so (Admin → Connectors, or `drishti.py connector apply trade-endur.yaml`):

```yaml
# config/connectors/trade-endur.yaml (written from the pack's template at the first start; yours to edit)
plugin: delta
kinds: [trade]
settings:
  root: /srv/drishti/delta
  domain: trading
  table.trade: trade_endur
  union: "true"
```

`--signal URL` then tells **that** server (it is never a default) that each business date landed: `POST /api/v1/packs/{pack}/loads`
with the rows, the rejects and the run as the batch id, so the server refreshes, verifies, checks its expectations and alerts, and
tells people ([DATA_GUIDE.md](DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)). The pack's name comes from `--pack`, or `--pack-name` without a pack folder. The
token needs the `loads:write` scope; it is read from `--token-file` or `DRISHTI_TOKEN` and never printed or written to the summary.
A signal that fails leaves the data written and makes the run exit `2`. Without `--signal`, or when it fails, the run leaves
`<bi-store>/lake-rebuilt.json` (the BI store: `DRISHTI_BI_STORE`, else `<data>/bi-store`), so the server's next start clears the BI columns
and rollups it read from the lakes; a running server also notices a rebuilt date by itself (its Delta files changed) and reads it again.
`--no-stamp` leaves no stamp.

### 7.9 The same in the browser

Admin → Data loads → **Load a file into a lake** ([OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md)) runs this builder on the console's machine with the choices made on
the page, then binds the lake to the pack's connector and sends the signal as the administrator who started it.

### 7.10 The demo lakes: lake demo

`drishti.py lake demo` (run configuration **Build demo lakes**) is `lake build` run for every dataset and business day of the
committed test data, `data/testdata`: the seven packs' tables under `data/delta/demo` (the demo lake's own folder) in about 15 minutes (943 s measured, about 3.4 GB), the sixty days moved to end on today's
business date (`--as-of data` keeps the files' own dates), the connector files checked, the next steps printed. `--only PREFIX` builds
some datasets, `--keep-other-dates` keeps a dated table's other business dates (each date written is replaced) instead of
rebuilding the table (the VaR data's three dates are 2026-09-28 to 2026-09-30; `--as-of 2026-09-25 --keep-other-dates --only market-risk/...`
adds them again moved back, as 2026-09-23 to 2026-09-25, the scenario window moved with it: [tutorial 2, step 16](../tutorials/02-var-from-the-bank-to-a-trade.md#step-16--the-scenario-matrix-scenarios-across-the-days)), `--lake DIR` another folder (the connector files then point there), `--with-real FILE[,FILE]` adds real extracts to the
local lake only. The sample feed the `file` connector serves (`feeds/`) and the staging go to the server's data folder: `--data-dir DIR`, else
`DRISHTI_DATA_DIR`, else the folder holding the lakes' `delta/` folder (`--lake /srv/data/delta/demo`: `/srv/data`), else `data/`, so a
server on another data folder finds its feeds (`make_data.py --data-dir DIR` does the same). It then tells the server the lakes changed: the data-load signal for every table and date to `--server` (default
`DRISHTI_SERVER`, then `http://localhost:18480`; a token with `loads:write` from `--token-file` or `DRISHTI_TOKEN`), or, with no server
there, the stamp of section 7.8; `--no-signal` does neither, and `--signal-once` tells each table once, for its newest date, instead of
each table and date (what `tools/build_demo_lakes.py --server URL` uses; a running server reads the older dates again by itself). The
summary says how many signals the server took, how many it **refused** (an HTTP error; the token hint only for 401 and 403) and how
many it **did not answer** within 30 s (busy, not a refusal), and how long the signals took beside the build (`built in 193 s, the
server told in 12 s`). `tools/build_demo_lakes.py` tells no server unless `--server` names one. Its closing line says what came of the signals: *was told once per table it reads* only when every signal was taken; *was not told: nothing answered there* (with the stamp left for its next start), *was not told: it refused the signals* (a token with `loads:write`), *did not answer the signals in time* (busy) or *was told of only some tables* otherwise, and *would be told* in a dry run. Everything about the data and the five-step quick start is in [data/testdata/README.md](../../data/testdata/README.md).

**The exposure run.** `--exposure demo` (the default) does not load the committed exposure run of eight netting sets: it
generates the run `XR-2026-09-30-01` for every netting set of the counterparties that fit in 9,500 trades, smallest first and always
whole (23 of the 24 counterparties, 74 netting sets, 8,022 live trades on the data's date, 200 paths, about a minute and 100 MB in the staging folder,
nothing downloaded, never committed) with `tools/packgen/ccr/make_ccr_data.py --demo` from the committed netting sets and CSAs. Each
trade's paths are seeded by its id, so the eight committed sets keep exactly their committed figures; CP-SUMMIT and CP-NORTHPLAINS stay
without paths (the 10,000-trade local limit). `--exposure committed` loads the committed run alone. See
[EXPOSURE_GUIDE.md section 23](EXPOSURE_GUIDE.md#23-test-data-and-generators).

**Each source system in Delta or Iceberg.** `tools/build_demo_lakes.py --store <system>=delta|iceberg` (repeatable; `all=`
first, then each system over it) chooses where each source system's trades are stored:

```bash
drishti-console/.venv/bin/python tools/build_demo_lakes.py --store calypso=iceberg                     # CALYPSO in Iceberg, ENDUR and MUREX in Delta
drishti-console/.venv/bin/python tools/build_demo_lakes.py --store all=iceberg                         # every system in Iceberg
drishti-console/.venv/bin/python tools/build_demo_lakes.py --only trades/calypso --store calypso=iceberg --as-of 2026-10-16
drishti-console/.venv/bin/python tools/build_demo_lakes.py --source-dir ~/calypso --system calypso --files-only --store calypso=iceberg
drishti-console/.venv/bin/python tools/build_demo_lakes.py --store all=delta                           # every system back in Delta
```

A system in Iceberg is built as Delta first (`lake demo`, or `lake build` of your files into `<data>/build/iceberg-stage`), so its
documents and promoted columns are exactly a Delta build's, then copied by `tools/lake/stores.py` with Drishti's own Iceberg loader
(`IcebergLoader`, `engine: native`, from the server's exec jar: build it first; `DRISHTI_LOADER_HEAP`, default 2g) into
`<data>/iceberg/trading/trade_<system>` (beside `--lake`'s folder; `DRISHTI_ICEBERG_ROOT` moves it). Its Delta table is removed, and its
connector file `trade-<system>.yaml` is written with `plugin: iceberg`, `engine: native` and `table: {trade: trade_<system>}` (back to the
pack's Delta template when the system returns to Delta; the replaced text is kept in `<data>/connectors/history/`), so one connector
serves each system, never two. A full build without `--store` puts every system in Delta; a partial one (`--only`,
`--files-only`) keeps each system it does not name in the store it has, and `--only` with a `--store` that moves another system adds that
system's `trades/<system>` to the rebuild. `--files-only` cannot move a system it does not load (it says to use `--only trades/<system>`
first). The loader's own progress lines (`iceberg: … rows read`, `written in … s`) and the 30-second heartbeat show while it copies; the
closing lines list the store and table of every system and what was done to each connector file. A running server restarts a switched
connector by itself (live reload); with `--server` it is also told once (trading, `trade`, the newest date). The equality test,
`tools/lake/stores_equality.py --work <empty folder> --port <port>`, builds the same synthetic days all-Delta and mixed and compares
trade pages, `TRD <id> 10`, counterparty-trades pages, searches and a BI query through a running server
([DATA_GUIDE.md section 11](../guides/DATA_GUIDE.md#112-mixed-delta-and-iceberg-storage)).

`drishti.py testdata capture-market` refreshes the committed market snapshot that `lake demo` builds for offline demos
(`data/testdata/news-and-markets/{quote,news}`): it starts a scratch server (or uses `--server`) and fetches the twenty companies'
quotes with a year of daily history (`--range`) and the companies' and topics' headlines through Drishti's own yfinance and news
connectors, politely (`--delay`); `--only quote|news` takes one of the two, `--dry-run` says what it would fetch. It needs internet;
the snapshot it writes is real data from Yahoo Finance and Google News, for internal demos only.

A nightly `lake build` from cron or a systemd timer, safe re-runs and what a scheduler does with each exit code are in
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md).

### 7.11 lake publish: a built day into a Delta table in S3

`lake build` writes tables on local disk or a network file system (EFS) only: its lock, its staging and Drishti's Java writer are
local. A lake in Amazon S3 is loaded in two steps: `lake build` into a fresh scratch lake, then the scratch table's data files are
copied to the S3 table's folder (`aws s3 sync`, without `_delta_log/`) and `lake publish` commits them:

```bash
drishti-console/.venv/bin/python tools/drishti.py lake publish --from /scratch/run-1/trading/trade_endur \
    --to s3://acme-drishti-prod-lake/lake/trading/trade_endur --dates 2026-10-20 --storage-option AWS_REGION=eu-west-2
lake publish: ok exit=0 published <n> file(s), <bytes> bytes, dates 2026-10-20 to s3://acme-drishti-prod-lake/lake/trading/trade_endur version=<v>
```

It reads the scratch table's log (paths, sizes, partition values, row counts, the id and promoted-column statistics), checks that
every file is in the target with its size, and makes **one** commit: with `--mode overwrite-dates` (the default) the dates' old
files are removed and the new ones added, so a reader sees the old day or the new day; `overwrite-partitions` replaces only the
partitions carried; `append` adds. A new table is created with the scratch table's schema, partitioning and statistics columns, a
checkpoint every `--checkpoint-interval` (10) commits, and `--log-retention-days` (30) and `--deleted-file-retention-days` (7).
Commits use S3 conditional writes (deltalake 1.x; no DynamoDB lock) and retry a lost race `--commit-retries` (20) times;
`AWS_S3_ALLOW_UNSAFE_RENAME=true` is refused. A day whose columns differ from the table's is refused, naming the columns.
`--dry-run` checks everything and commits nothing; `--list-files` prints the data files the publish needs; `--signal` sends the
data-load signal as `lake build` does. Exit: 0 ok, 1 failure (nothing committed), 2 committed but the signal failed, 3 nothing to
publish for the dates asked.

`deploy/aws/delta-lake/load-day.sh` runs the three steps per table under a per-table lock; the whole AWS setup, sizing past 200 TB
and the runbooks are [CLUSTER_GUIDE.md](CLUSTER_GUIDE.md#13-the-delta-lake-in-s3-at-scale). Tested by `tools/lake/test_publish.py` (a local
folder stands in for S3) and on S3 simulators by `tools/lake/test_s3_simulators.py` (section 7.12). With `--storage-option
AWS_ENDPOINT_URL=…` (MinIO, an S3-compatible store) the file check goes to that endpoint with those keys, as the commit does.

### 7.12 The S3 tests: S3Mock, moto and MinIO

`tools/lake/s3sim.py` starts the S3 simulators (moto, MinIO) for these tests.

Three S3 simulators, none needing Docker. Each suite prints which conditional writes its simulator covered
(`[S3 conditional writes] …: create-only covered, update-if-match covered, delete-if-match covered, racing writers …`) and skips,
with the reason, what the simulator does not honour.

| Simulator | What it tests | Runs on |
|---|---|---|
| Adobe S3Mock 4.12.4, in the test JVM (Maven test dependency) | the content store's S3 contract; the Iceberg native engine's table on S3 | Linux and Windows |
| moto 5.2.3 (`moto[server]`, pure Python, started with the venv's own python) | Delta on S3 through deltalake's storage options; `lake publish` on S3 (new table, a day restated, a missing file refused, three publishes at once) | Linux and Windows |
| the MinIO server binary | all of the above, the most faithful | Linux only: skipped on Windows, offline, or when no binary can be had |

```bash
# Java: the content store and the Iceberg table on S3Mock (and MinIO when it can run)
./mvnw -q -pl drishti-identity,plugins/drishti-plugin-iceberg -am test \
  -Dtest='S3ContentStoreTest,S3MockContentStoreTest,MinioContentStoreTest,NativeOnS3SimulatorTest' -Dsurefire.failIfNoSpecifiedTests=false
# Python: moto (once: drishti-console/.venv/bin/python -m pip install "moto[server]==5.2.3", or uv pip install --python …)
drishti-console/.venv/bin/python -m unittest -v tools/lake/test_s3_simulators.py
```

On Windows: `mvnw.cmd` and `drishti-console\.venv\Scripts\python.exe` instead (see the QA guide). MinIO: give the tests a binary
with `DRISHTI_MINIO_BIN=/path/to/minio` or put one in `tools/cache/minio/` (git-ignored); MinIO no longer serves its community
binaries, so the default download answers `410 Gone` and the MinIO tests skip saying so. The drill runs all three (the Java ones in its
verify, the Python one with uv).

### 7.13 The parallel lake loader

For a load too large for the Python path (the bank's scenario PVs: 2.3 million positions of 510 scenarios, 20+ GB of JSON Lines), the
**parallel lake loader** (module `drishti-lakeload`, in the server's exec jar) does the whole build in Java: readers, parsers and writers
on every core, memory bounded by `memory.budget-mb` whatever the input's size (rows that do not fit are sorted and spilled to disk), Delta
or Iceberg, from a folder or `s3://`, **one commit per business date**. It writes exactly the tables `lake build` writes (the same
documents byte for byte, columns and vectors; the connectors and the VaR loader read them unchanged). The design, the memory model and
the 2.3M sizing: [PARALLEL_LOADER.md](../architecture/DATA_ARCHITECTURE.md#10-the-parallel-lake-loader-drishti-lakeload). `tools/lake/javaload.py` is `lake build`'s side of it.

**Through lake build** (the lock, the run log, the summary, the signal as always):

```bash
python3 tools/drishti.py lake build --engine java --from /data/pv/2026-10-28/ --as-of 2026-10-28 --pack config/packs/banking/market-risk --kind scenario-pv \
    --key SCENARIO_PV_ID --mode overwrite-dates --lake /var/lib/drishti/delta \
    --loader-set memory.budget-mb=16384 --loader-set memory.spill-dir=/mnt/nvme/spill
```

**The Python path is the default; the loader runs only when you ask for it** with `--engine java` (the same command line otherwise).
`--engine auto` (the default) never picks the loader on its own; with `--java-above-mb N` it does, for at least N MB of JSON Lines in mode
`overwrite-dates` (smaller loads, JSON arrays and the other modes go the Python way). `--loader-set KEY=VALUE` (repeatable)
sets any key below; `DRISHTI_LAKE_LOADER_HEAP` is the JVM's heap (default `4g`); `--from s3://…` and a Delta `--lake s3://…` need
`--engine java`.

**Through a lake file**: `target.lake: <name>` and `target.kind` (and `target.table` where a kind has several tables) take the table's
location, partitions, promoted columns, vectors, id, date, latest-by, file layout and load mode from `config/lakes/<name>.yaml` (the
folder: `-Ddrishti.lakes.dir` or `DRISHTI_LAKES_DIR`); a key of the load file that says otherwise is refused with both named.
`load-mode: append` in the lake file (or `target.mode: append`) adds the rows instead of replacing the dates, and needs
`target.batch-id`: the batch is recorded in each date's commit and a batch already committed for a date is skipped, never appended twice
([DATA_LAKE_GUIDE.md section 5.3](DATA_LAKE_GUIDE.md#53-modes-replace-and-append)). A `target.lake` that is a folder is the older form (one warning).

**On its own** (a scheduler, a container, a machine without the console's Python):

```bash
java -Xmx40g -Djava.io.tmpdir=/mnt/nvme/tmp -Dloader.main=com.ash.drishti.lakeload.LakeLoad \
    -jar drishti-server/target/drishti-server-<version>-exec.jar --config /etc/drishti/load-pv.yaml
# development: java -cp <the module's class path> com.ash.drishti.lakeload.LakeLoad --config load.yaml
```

```powershell
# Windows (PowerShell; Java only, no .exe)
java -Xmx8g "-Djava.io.tmpdir=C:\drishti\data\tmp\server" "-Dloader.main=com.ash.drishti.lakeload.LakeLoad" `
    -jar C:\drishti\drishti-server\target\drishti-server-<version>-exec.jar --config C:\drishti\load-pv.yaml
```

Options: `--config FILE`, `--set key.path=value` (repeatable; overrides the file), `--summary FILE`, `--progress FILE`, `--dry-run`
(read and count only), `--dataset NAME` (`source.dataset`), `--no-lock`, `--no-run-log`, `--json` (the summary on standard output), `-q`. Exit codes as section 7.7: `0`,
`2` (rejects), `1` (failed: no date that was not committed changed), `3` (locked). A local lake gets the run log and summary under
`<lake>/_drishti-lake` and the lock of section 7.7.

**Every key** (YAML or JSON; `${ENV}` and `${ENV:-default}` are expanded; an unknown key is refused with the list of the section's keys):

| Key | Default | Meaning |
|---|---|---|
| `source.files` | (required) | a file, a folder (its `*.jsonl`, `*.ndjson`, `*.json`, `*.gz`, `*.zst` and archives `*.zip`, `*.tar.gz`, `*.tgz`, `*.tar`), or a glob (`/data/pv/*.jsonl.gz`, `C:\pv\cob-*.jsonl`, `**` for any depth; on S3 in the last segment: `s3://bucket/pv/2026-10-28/*.jsonl.gz`); a list for several. An archive is read in place by the archive rule ([DATA_GUIDE.md section 2.7](DATA_GUIDE.md#7-archives-zip-and-tarballs)): its JSON Lines members in name order, one reader taking the archive in one pass |
| `source.dataset` | the archive's name | an archive without folders is one dataset named after the file (`trade.tar.gz` → `trade`); this names it instead (`--dataset NAME`) |
| `source.archive.max-mb`, `max-ratio`, `max-members` | `DRISHTI_ARCHIVE_MAX_MB` (102,400), `_MAX_RATIO` (200), `_MAX_MEMBERS` (100,000) | the zip-bomb caps: an archive's declared and expanded sizes at most this many MB and this many times its own size (1 MB always allowed), at most this many entries |
| `source.archive.validate` | `rows` | with several datasets: `rows` reads and checks every dataset (a dry run) before the first commit, so one that would fail stops the run with nothing loaded; `structure` checks the archives' listing only |
| `datasets` | none | the datasets an archive's folders may hold, each loaded into its own table `<lake>/<domain>/<name>` (its `target.kind` the name): a list (`[trade, position]`) or a mapping of name to its own `source:`, `target:` and `rejects:` keys over the file's. A folder (or a folderless archive's name) not listed is refused, every one named; without `datasets:` an archive's folders must all be the one table's. Each dataset's summary, manifest, progress and rejects files get its name (`summary-trade.json`); `report.summary` is the run's, every dataset in it; the run prints each dataset's rows, files and version |
| `source.recursive` | `false` | a folder's subfolders too |
| `source.storage-options` | none | S3: `region`, `endpoint`, `path-style`, `read-block-kb`, `tls.*`, `proxy.*`. Credentials only from the AWS default chain (environment, profile, instance or task role); a key here is refused |
| `source.format` | `jsonl` | JSON Lines (the data standard); `.gz` and `.zst` decompressed as streams, multi-member gzip included |
| `source.pack`, `source.connector` | none | a pack folder (as `--pack`): the connector's domain and table, `layout.<kind>.columns`, `vectors`, `flatten`, `ingest.<kind>.key` and `date`; the keys below win |
| `source.id` | `id` (or the pack's) | the id field (`--key`), a dotted path |
| `source.as-of` | none | one business date for every file of the input; else each file's: its `.ready` file's `business-date:`, its name's date (`<table>.<YYYY-MM-DD>.jsonl.gz`), the business today; a tar's members their folders' ([DATA_GUIDE.md section 2.8](DATA_GUIDE.md#8-feeds-the-table-and-business-date-of-a-file)) |
| `source.date` | the pack's | a row's own date field: never the partition, only counted when it differs from its file's date (`fileDates`, `otherDateRows`, `dateNotes` in the summary) |
| `--as-of`, `--table`, `--pack`, `--kind`, `--mode`, `--key`, `--date-from`, `--plan`, `--run-id` | | the command line's options over the file: `source.as-of`, `target.table`, `source.pack`, `target.kind`, `target.mode` (`append`, the default, or `replace`), `source.id`, `source.date-from`, a dry run; `--run-id` gives the run's id (its rows' `_load_id` is `<run-id>.<n>`; the data inbox passes one for every table of an archive) |
| `source.date-from` | `auto` | `auto` as above; `name`: a file whose name carries no date (and no `as-of`, no `.ready` date) refuses the load |
| `source.promote` | the pack's | fields kept as columns (`layout.<kind>.columns`) |
| `source.vectors` (`vector`) | the pack's | list-of-numbers fields as `list<double>` columns, the document keeping a `{"$vector": …}` marker (VAR_GUIDE.md section 29.3) |
| `source.flatten` | the pack's | `NAME: LEGS[0].NOTIONAL`: fields added to the document and promoted |
| `source.partition-by` | none | Delta partition folders after `business_date` (Iceberg: keep them as `promote`) |
| `source.latest-by` | `COMPUTED_AT` (or the pack's `ingest.<kind>.latest-by`) | duplicates (one id twice on one date): the copy with the latest of this ISO timestamp is kept, else the last read (files sorted by path, then line order); the others dropped and counted (`duplicates`, `duplicateExamples`), never a failure; `''` keeps the last read. The same rule as lake build's Python path (section 7.3) |
| `target.format` | `delta` | `delta` or `iceberg` (Iceberg through the native engine: no Hadoop) |
| `target.lake`, `target.domain`, `target.kind`, `target.table` | (lake, domain, kind) | the table `<lake>/<domain>/<table or kind>`; `lake` a folder or `s3://bucket/prefix` |
| `target.path` | none | the table's location instead of the four above |
| `target.storage-options` | `source.storage-options` | the target's S3 options |
| `target.mode` | `replace-date` | each business date of the input replaced in one commit (`overwrite-dates` is the same word) |
| `target.properties` | none | Delta table properties for a new table (`delta.logRetentionDuration`, …) |
| `target.iceberg` | none | Iceberg catalog settings as the connector's: `catalog: rest`, `uri`, `warehouse`, `token: ${ICEBERG_TOKEN}` (a secret only as `${VAR}`) |
| `target.layout.sort-by` | the pack's `layout.<kind>.sort-by`, else `[LEGAL_ENTITY, DESK, id]` | the order of each date's rows (those of the fields the rows promote; `id` the row id); `[id]` is lake build's order |
| `target.layout.file-size-mb` | `256` | Parquet files of about this size |
| `target.layout.shard-aligned` | `true` | also a new file where `LEGAL_ENTITY`/`DESK` (the sort fields before the id) change, once a file holds a quarter of its size |
| `target.layout.row-group-mb` | the format's | Parquet row groups (Delta: `DRISHTI_LAKE_ROW_GROUP_KB`, 256 KB; Iceberg: the table's, 1 MB) |
| `target.layout.compression` | the format's | `snappy` (Delta), `zstd` (Iceberg) |
| `target.layout.vector-compression` | `zstd` | the vector column's codec |
| `target.layout.byte-stream-split` | `true` | the vector column BYTE_STREAM_SPLIT encoded (ZSTD packs it far better) |
| `parallel.readers` | the cores | files (or ranges) read at once; never more files open than this |
| `parallel.parsers` | the cores | parser threads |
| `parallel.writers` | 8 (at most the cores) | writer threads (key ranges written at once) |
| `parallel.split-mb` | `256` | a plain file larger than this is read as byte ranges in parallel (ranged GETs on S3) |
| `parallel.queue-mb` | 15 % of the budget | lines read but not parsed yet (back-pressure) |
| `memory.budget-mb` | half of `-Xmx` | everything the load holds: the queue, the sort buffers, the writers |
| `memory.sort-buffer-mb` | 55 % of the budget | the parsers' rows before they are sorted and spilled |
| `memory.spill-dir` | `java.io.tmpdir` | where sorted runs spill (local disk, EBS, Fargate ephemeral storage); removed however the load ends |
| `memory.check-spill-space` | `true` | stop before reading when the spill folder has less free space than the estimate |
| `rejects.file` | none | every reject as a JSON line: `where` (`file:line`), `reason`, `line` |
| `rejects.max-pct` | `100` | more of the documents rejected than this: nothing is written, exit 1 |
| `rejects.keep` | `50` | rejects kept in the summary |
| `report.summary` | `<lake>/_drishti-lake/runs/<run>.json` | the JSON summary (lake build's keys, plus `throughput` per stage and `memory`) |
| `report.manifest` | beside the summary | the load manifest: per date and file, rows and the first and last sort key (PARALLEL_LOADER.md section 7) |
| `report.progress` | none | a JSON file rewritten twice a second (phase, bytes, documents, rejects, dates) |
| `report.run-log`, `report.dry-run`, `lock` | `true`, `false`, `true` | as `--no-run-log`, `--dry-run`, `--no-lock` |

**Local folder to S3, Delta** (the scenario PVs of the day, the pack's layout):

```yaml
source:
  files: /data/pv/2026-10-28/*.jsonl.gz
  pack: config/packs/banking/market-risk
  id: SCENARIO_PV_ID
  as-of: 2026-10-28                       # the day's folder: its business date, given (a row's own date is only counted)
target:
  format: delta
  lake: s3://bank-lake/delta
  kind: scenario-pv                       # the table s3://bank-lake/delta/risk/scenario-pv (domain from the pack)
  mode: replace                           # the date replaced on a re-run (append is the default)
  storage-options: {region: us-east-1}
  layout: {sort-by: [LEGAL_ENTITY, DESK, id], file-size-mb: 256, shard-aligned: true}
parallel: {writers: 16}
memory: {budget-mb: 32768, spill-dir: /mnt/nvme/spill}
rejects: {file: /var/log/drishti/rejects-2026-10-28.jsonl, max-pct: 1}
```

**S3 to S3, Iceberg** (MinIO here; on AWS leave out `endpoint` and `path-style`):

```yaml
source:
  files: s3://landing/pv/2026-10-28/*.jsonl.zst
  storage-options: {region: us-east-1, endpoint: '${MINIO_ENDPOINT}', path-style: true}
  id: POSITION_ID
  as-of: 2026-10-28
  promote: [LEGAL_ENTITY, DESK, TT_BOOK, CCY]
  vectors: [PV]
target:
  format: iceberg
  lake: s3://bank-lake/iceberg
  domain: risk
  kind: scenario-pv
memory: {budget-mb: 8192, spill-dir: /scratch}
```

**A REST catalog** (AWS Glue's Iceberg endpoint): add `target.iceberg: {catalog: rest, uri: 'https://glue.us-east-1.amazonaws.com/iceberg',
warehouse: '<account id>', catalog.rest.signing-name: glue}` (the domain is the namespace).

**An archive with a folder per dataset** (`bundle.tar.gz` holding `trade/2026-10-28/…jsonl.gz` and `position/2026-10-28/…jsonl`: each
table folder its own table, each date folder its business date ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo) section 5.2);
every dataset read and checked before the first commit; a folder not listed under `datasets:` refuses the run):

```yaml
source:
  files: /landing/2026-10-28/bundle.tar.gz
  id: id
target:
  lake: /var/lib/drishti/delta
  domain: trading
datasets:
  trade: {}                               # trading/trade
  position: {source: {id: POSITION_ID}}   # trading/position, its own id field
```

It ends with a line per dataset:

```
datasets: 2
  position             3 rows  1 file  version 0  (ok)
  trade                4 rows  2 files  versions 0 to 1  (ok)
```

**On Windows** (`C:\drishti\load-pv.yaml`; paths in single quotes keep their backslashes):

```yaml
source:
  files: 'C:\drishti-private\pv\scenario-pv.2026-10-28.jsonl.gz'      # dated by its name
  pack: 'C:\drishti\config\packs\banking\market-risk'
  id: SCENARIO_PV_ID
target:
  format: delta
  lake: 'C:\drishti\data\delta'
  kind: scenario-pv
memory: {budget-mb: 4096, spill-dir: 'C:\drishti\data\tmp\spill'}
```

What it prints: the market-risk pack's scenario PVs of `data/testdata` (three gzip files, 1,339 positions), the first config above
pointed at them and at a scratch lake, `-Xmx2g`, `budget-mb: 512`, captured 2026-10-28 (a cold JVM: a load this small is mostly start-up;
warm rates are in PARALLEL_LOADER.md section 11). `-q` leaves only the last line.

```
loader: delta table /scratch/demo/lake/risk/scenario-pv
  2026-09-30  1,339 rows  (version 0, 1 files)
documents read: 1,339, rows written: 1,339, rejected: 0
  read         3 threads       0.0 s    1,585.3 MB/s in
  decompress   3 threads       0.0 s    141.6 MB/s in
  parse       24 threads       0.2 s  5,666 rows/s  31.4 MB/s in
  sortSpill   24 threads       0.0 s
  write        8 threads       0.4 s  3,005 rows/s  6.6 MB/s out
  commit       1 threads       0.1 s  16,038 rows/s
  memory: budget 512 MB, peak heap used 150 MB of 2,048 MB, spilled 0 MB
summary: /scratch/demo/lake/_drishti-lake/runs/20261028T085451-1591296.json
load manifest: /scratch/demo/lake/_drishti-lake/runs/20261028T085451-1591296.manifest.json
2026-10-28T08:54:52-04:00 lake-build ok exit=0 run=20261028T085451-1591296 domain=risk kind=scenario-pv engine=java-loader mode=overwrite-dates rows=1339 rejected=0 dates=1 partitions=1 seconds=1.42
```

(The scratch folder's path shortened to `/scratch`.)

The tests: `./mvnw -o -q -pl drishti-lakeload test` (equality with the Python path on both formats, through the connectors; one commit a
date; a stopped load; S3 on S3Mock, a zip and a .tgz on S3 included; gz, multi-member gz, many gz, zstd, byte ranges, 1 vs many threads,
a tiny budget: identical tables; the 10K throughput; `ArchivesTest`: the shared archive cases, datasets, refusals) and
`python3 -m pytest tools/lake/test_javaload.py tools/lake/test_archives.py`.

### 7.14 The AWS connectors' tool scripts: S3 Tables and Delta Lake on S3

Two Python scripts make, check and remove the AWS resources of the two AWS connectors, with `boto3` (no AWS CLI, so Windows works the
same); `.sh` and `.ps1` wrappers start them with the console's Python environment (`drishti-console/.venv`), else `DRISHTI_PYTHON`
or `python3`:

| Script | Connector | `create` | `check` | `remove` |
|---|---|---|---|---|
| `deploy/aws/s3-tables/s3-tables.sh` (`.ps1`) | `iceberg` with `catalog: s3tables` ([ICEBERG_CONNECTOR.md section 20](../../plugins/drishti-plugin-iceberg/README.md#aws-s3-tables)) | the table bucket, a namespace, the policy `drishti-s3tables-<bucket>` | the resources, then one table read end to end through the Iceberg REST endpoint signed as Drishti signs it, its metadata, manifests and first data file | the namespace and the policy; tables with `--delete-tables`, the bucket with `--delete-bucket` |
| `deploy/aws/delta-s3/delta-s3.sh` (`.ps1`) | `delta` with `root: s3://<bucket>/<prefix>` ([DELTA_CONNECTOR.md section 17](../../plugins/drishti-plugin-delta/README.md#delta-lake-on-aws-s3)) | a private, encrypted bucket, the prefix, the policy `drishti-delta-s3-<bucket>` | the resources, then one table read end to end: the log replayed from its last checkpoint, the first live file's footer and first row group | the policy; the prefix's objects with `--purge`, the bucket with `--delete-bucket` |

Each also has `policy` (the IAM document printed, no AWS call). Every command takes `--region` and `--profile` (else `AWS_REGION`
and `AWS_PROFILE`), `--env FILE` and `--dry-run`; a removal needs `--yes`. Credentials come from the AWS default chain and are never
printed. Exit codes: 0 done or the check passed, 1 a step failed (named), 2 usage.

```bash
deploy/aws/s3-tables/s3-tables.sh create --bucket drishti-risk --namespace risk --role drishti-server --dry-run
deploy/aws/delta-s3/delta-s3.sh check --bucket acme-drishti-lake --prefix delta --table risk/pnl-vector
```

Their tests need no AWS account (`python -m pytest -q -p no:cacheprovider deploy/aws/s3-tables deploy/aws/delta-s3`: botocore's
Stubber, an in-memory S3, Delta tables written by the deltalake package). They have not been run against AWS: no AWS account is
available to the build, and the live run waits for an AWS sandbox account. The guides: [`deploy/aws/s3-tables/README.md`](../../deploy/aws/s3-tables/README.md)
and [`deploy/aws/delta-s3/README.md`](../../deploy/aws/delta-s3/README.md).

## 8. `server`: health and packs

The Admin pages of a running server from the terminal (an administrator; a personal token needs the scope each write names):

| Command | What it does | In the console |
|---|---|---|
| `server health` | the server's health, every source's state; exit `1` unless OK | Admin → Health |
| `server caches [list\|purge NAME\|all]` | what the engine and each connector hold; drop a cache | Admin → Caches |
| `admin capacity` | the single-node capacity planner, evaluated (`--live` with this server's figures), or its formula workbook | Admin → Capacity |
| `server packs list\|load\|unload\|on\|off\|deploy\|history\|rollback\|datasource` | packs on a running server | Admin → Packs |
| `server commands list\|off\|on\|history` | the commands of Functions (`FUNC`) switched on or off | Functions |
| `server engines list\|show\|on\|off\|history` (also `admin engines`) | the compute engines on or off | Admin → Engines |
| `server blas status\|java\|native` (also `admin blas`) | VaR's linear algebra on the bundled OpenBLAS or the pure-Java path, live | Admin → Engines → Linear algebra |
| `server smoke` | opens sample views of every kind on two dates; exit `1` on any failure | |
| `doctor` | is this machine ready (no server needed) | Admin → Health |

`server smoke --json` every few minutes is a cheap synthetic monitor (exit `1` pages someone), and `server health` fits a load
balancer's check script.

### `server health`

```bash
drishti-console/.venv/bin/python tools/drishti.py server health
```

On the quick-start server (the demo lakes, sign-in on, the token in `DRISHTI_TOKEN`) after tutorials 10 and 22, which add the
`desk-quotes` pack and its connectors, and with a connector file of [DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors) that points
at a folder this machine does not have:

```text
status DEGRADED  version <version>  java 21.0.12.1  uptime 2111s
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

The other 14 sources are the seven packs' own, all `UP`.

The exit code is 1 unless the status is `OK` (here two sources are down, so `DEGRADED`). `--json` prints the server's own
`GET /api/v1/admin/health` document.

### `server caches`

`server caches` (or `server caches list`) prints the memory and disk totals, then one line per cache (also VaR's,
exposure's, BI's and pack access) with where it lives and its size against its limit, the same as **Admin → Caches**
(`GET /api/v1/admin/caches`); `--json` prints the server's document. `server caches --dates var.hot-files` lists the business dates a
cache holds (open or on disk, size, data versions, digest); `server caches purge var.hot-files --date 2026-09-29` purges one date only,
and `--cluster` purges on every live member too. `server caches
purge NAME` drops one: for a Delta connector its entries in the shared memory caches, its document cache on disk, its id map files
and the parsed Parquet footers; for a derived connector (`trade-pages`) its grouped pages, the latest date rebuilt from the lake at
once while the old pages serve. `purge all` drops every cache. Everything purged is rebuilt from the data at the next read, so a
purge is safe at any time; it needs an administrator and is written to the audit log.

```bash
drishti-console/.venv/bin/python tools/drishti.py server caches purge trade-pages
drishti-console/.venv/bin/python tools/drishti.py server caches purge var.hot-files --date 2026-09-29 --cluster
```

### `admin capacity`: Admin → Capacity from a terminal

`admin capacity` is **Admin → Capacity** from a terminal ([OPERATIONS_GUIDE.md 12.4](OPERATIONS_GUIDE.md#146-admin--capacity-today-against-the-plan)): it reads the planner the server serves (`GET /api/v1/admin/capacity`, administrators) and evaluates it here with the evaluator the workbook is checked with, then prints the answers (`-Xmx`, the collector, `drishti.var.open-heap-mb`, RAM, disk, cores), the warnings that are not OK, the YAML block of the settings for `./application.local.yaml` and the whole JVM command line. `--set Name=value` changes any input by its workbook name (repeat it); `--live` puts this server's measured figures into the inputs and prints them (`GET /api/v1/admin/capacity/live`); `--all` prints every result line band by band; `--json` the inputs, the live figures and every result. `--xlsx FILE` downloads the formula workbook instead (`--inputs plan`, `live` or `both`), audited on the server as `capacity-workbook-downloaded`.

```bash
drishti-console/.venv/bin/python tools/drishti.py admin capacity --set Trades=450000 --set Positions=450000
drishti-console/.venv/bin/python tools/drishti.py admin capacity --live
drishti-console/.venv/bin/python tools/drishti.py admin capacity --xlsx capacity.xlsx --inputs both
```

### `server packs`: list, load, unload, on and off

`server packs list|load|unload|on|off` manage packs. **load** and **unload** edit the overlay file
(`data/packs/added.yaml`) after the server checks the pack the same way it does at start, then put the pack to use at once, with no restart;
**on**/**off** switch a loaded pack for users without a restart. Unloading only works for packs an administrator loaded
(packs in `DRISHTI_PACKS` stay).

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

The seven packs come from `DRISHTI_PACKS`; `desk-quotes` was **added** by an administrator (tutorial 22, Part A) and is in the overlay file.

```bash
python3 tools/drishti.py server packs load my-bank       # POST /api/v1/admin/packs/my-bank/load
python3 tools/drishti.py server packs off  my-bank       # PUT  /api/v1/admin/packs/my-bank  {"enabled": false}
```

### `server commands`, `server engines` and `server blas`: the administrator's switches

`server commands list|off|on|history` are the administrator's switches of Functions (`FUNC`) from the terminal: commands, or with
`--pack` whole packs, on or off for everyone, each change with `--reason`
([ADMIN_GUIDE.md (Functions)](ADMIN_GUIDE.md#20-functions-func-every-command-and-turning-one-on-or-off)). As the admin
endpoints are never for a personal token, it runs as an administrator's signed token, or where sign-in is off with `--user`.
`server engines list|show|on|off|history` (also `admin engines`) switches a compute engine on or off for this instance, and
`server blas status`, `server blas java --reason ...` and `server blas native --reason ...` (also `admin blas`) run VaR's many-scope
sums on the bundled OpenBLAS or on the pure-Java path, live ([OPERATIONS_GUIDE.md section 2.1](OPERATIONS_GUIDE.md#149-native-libraries-blas)).

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
GREEN  server jar              ~/drishti/drishti-server/target/drishti-server-<version>-exec.jar (version <version>)
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
YELLOW server                  http://localhost:18971: version <version>, java 21.0.12.1, status DEGRADED
YELLOW token                   none given: calls run anonymously or as --user
                               fix: set DRISHTI_TOKEN or --token-file (My account, API tokens)
doctor: 0 red, 5 yellow
```

### `server packs deploy`, `history`, `rollback` and `datasource`

Admin → Packs from the terminal ([the whole story with pictures: OPERATIONS_GUIDE.md, section 17](OPERATIONS_GUIDE.md#9-admin--packs-deploy-an-archive-history-and-roll-back)).
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
  ok    server version  needs >=1.0.0, this is <version>
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
[`connector` commands](#connector-list-get-apply-delete-test-plugins-enable-disable-reset-history) below or in Admin → Connectors. More: [OPERATIONS_GUIDE.md, 17.5](OPERATIONS_GUIDE.md#66-connectors-where-a-pack-reads-its-data).
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

## 9. `connector` and `net`

### `connector`: list, get, apply, delete, test, plugins, enable, disable, reset, history

Admin → Connectors from the terminal ([the model, the file format and every store's example: DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors)).
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
  credit-store       file    delta     netting-set,credit-limit,cva +1                            running         counterparty-risk
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

**Try a connector file before you apply it.** `connector test --file F` starts a throwaway instance on the server with that file's
settings and saves nothing. The lake of [section 7](#7-lake-a-large-json-export-into-a-delta-lake), tried as a connector:

```
$ cat tools-trade.yaml
plugin: delta
description: the ENDUR trades of the command-line guide (lake build)
kinds: [trade]
settings:
  root: <repo>/lake
  domain: trading
$ drishti-console/.venv/bin/python tools/drishti.py connector test --file tools-trade.yaml --server http://127.0.0.1:19590
tools-trade (delta): reachable, health UP (engine: native), 38 ms
  kind   business date  rows  note
  -----  -------------  ----  ----
  trade  2026-09-23     159
  trade  2026-09-22     4407
```

Applying that file would add a fourth connector serving `trade` beside the three of the trading pack; to read the lake in place of the
demo lake, edit `trade-endur.yaml`'s `root` instead (Admin → Connectors, or `connector get trade-endur --yaml`, edit, `connector apply`).

### `net`: Drishti*net's config/net/ folder

`drishti.py net list | check | add-peer | set` work on the folder of Drishti*net's files ([OPERATIONS_GUIDE.md](CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)) on this machine, with no
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
drishti: node.yaml was not written: 'http://127.0.0.1:18784' is a loopback or wildcard address (loopback): the address this node advertises must be an address other machines can call (this machine's address is 192.168.1.20: use http://192.168.1.20:18784); or leave advertise-url out and the node advertises that address with its port
  error   node.yaml:3  advertise-url: 'http://127.0.0.1:18784' is a loopback or wildcard address (loopback): [the same message]
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

Members advertise only a real address ([OPERATIONS_GUIDE.md › Drishti*net: running several servers as one, section 2](CLUSTER_GUIDE.md#2-a-cluster-of-three)): `localhost`, `127.x.x.x`, `::1`,
`0.0.0.0` and `::` are refused in `advertise-url` and in a seed's URL, and the message names this machine's address. `advertise-url` may be
left out: the node then advertises this machine's primary IPv4 address with the server's port (Admin → Net → Members shows it as *detected*).

The file `net set` wrote:

```yaml
# config/net/node.yaml (Drishti*net, docs/guides/OPERATIONS_GUIDE.md)
secret: ${DRISHTI_NET_SECRET}
advertise-url: http://192.168.1.20:18784
tags:
- region:emea
- tier:risk
enabled: true
```

Exit: 0 done, 1 refused or a file has an error, 2 usage (a bad peer name, a key that names no file).

## 10. `view`, `history` and `convert`

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

### `history`: an entity over time

`drishti.py history KIND ID` prints one row per business day the entity has data, newest first, a changed cell marked `*`: the same
answer as the console's history page (`TRD 1000015 30 <GO>`, [USER_GUIDE › History](USER_GUIDE.md#history-an-entity-over-time)),
read from `GET /api/v1/history/{kind}/{id}` with your token, so masks apply.

```text
drishti.py history trade 1000015 --days 30                        the last 30 business days with data (the default)
drishti.py history equity EQ-CSCA --range 3M --fields PRICE,CHANGE_1D
drishti.py history trade 1000015 --from 2026-09-01 --to 2026-09-30 --diff
drishti.py history trade 1000015 --days 30 --changes-only --json
drishti.py history trade 1000015 --days 30 --csv > trade-1000015.csv
drishti.py history trade 1000015 --days 30 --jsonl > trade-1000015.jsonl  # a day a line, keyed as the CSV's header
```

| Option | Does |
|---|---|
| `--days N` | the last N business days on which the entity has data |
| `--range R` | a calendar range: `30D`, `2W`, `3M`, `1Y`, `YTD`, `MTD`, `QTD` |
| `--from D`, `--to D` | between two business dates (`--to` defaults to the latest) |
| `--fields A,B` | only these top-level fields |
| `--known-at T` | the history as it was known at an instant (stores with time travel: Delta, Iceberg) |
| `--diff` | each day's changes against the day before, listed after the table |
| `--changes-only` | only the days on which something changed |
| `--json`, `--csv`, `--jsonl` | the server's answer as JSON; the table as CSV (UTF-8 with a BOM, CRLF, formulas kept as text); the table as JSON Lines (a day a line, the CSV's column names, numbers as numbers) |

`drishti.py history trade 1000015 --days 30 --changes-only` (the contract's fixture trade):

```text
TRD 1000015  HISTORY  2026-09-07 -> 2026-10-19  30 days with data, 7 changed, no data on 2026-09-14
ComSwap ENGY-SWAP · CP-HARBORPT
Date           Trade status  Trade version  Quantity  Price        Maturity date  Portfolio     Counterparty  Trader  Legs  Events
-------------  ------------  -------------  --------  -----------  -------------  ------------  ------------  ------  ----  ------
2026-10-19     VALIDATED     3              72K       2,341.0500*  2028-07-31     BOOK-COMM-2   CP-HARBORPT   •••     2     5
2026-10-16     VALIDATED     3              72K*      2,336.1200*  2028-07-31     BOOK-COMM-2   CP-HARBORPT   •••     2     5
2026-10-15 ⟲2  VALIDATED     3*             75K*      2,338.4000*  2028-07-31     BOOK-COMM-2   CP-HARBORPT   •••     2     5*
2026-10-07     VALIDATED     2              72K       2,332.9131   2028-07-31     BOOK-COMM-2*  CP-HARBORPT   •••     2     4
2026-10-01     VALIDATED     2              72K       2,332.9131   2028-07-31     BOOK-COMM-3*  CP-HARBORPT   •••     2     4
2026-09-22     VALIDATED     2*             72K       2,332.9131   2028-07-31     BOOK-COMM-2   CP-HARBORPT   •••     2     4*
2026-09-09     VALIDATED*    1              72K       2,332.9131   2028-07-31     BOOK-COMM-2   CP-HARBORPT   •••     2     3
7 of 30 days changed
```

`⟲2` marks a day the store kept two versions of (the latest shown); `•••` is a field masked for you. Exit codes: 0; 1 when the server
refused (the code and its message on standard error) or some days could not be read (`partial`); 2 for usage.

### `convert`: CSV, a JSON array or a folder of `.json` files into JSON Lines

JSON Lines is Drishti's data format ([DATA_GUIDE.md](DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)), and **CSV input is deprecated**. `drishti.py convert FILE` (`tools/convertcmd.py`) turns what you have into JSON Lines, one object a line, streaming (a large
JSON array is never read whole):

```text
drishti.py convert trades.csv                          trades.jsonl: one line per CSV row, cells typed (numbers, true/false; empty: null)
drishti.py convert book.json --out book.jsonl.gz       a JSON array: one line per element (one object: one line); .gz gzips
drishti.py convert data/feeds/settlement               settlement.jsonl beside the folder: one line per <id>.json, the file name as "id"
drishti.py convert data/feeds/fixing/SOFR-HISTORY.csv --rows-document --out data/feeds/fixing.jsonl
                                                       one line {"id": "SOFR-HISTORY", "rows": [...]}: the file connector's reading of a
                                                       CSV feed file, so FIX SOFR-HISTORY opens the same document
drishti.py convert big.json --out -                    to standard output
drishti.py convert trade.tar.gz                        trade.jsonl: an archive's JSON Lines, its members in name order
drishti.py convert book.zip --dataset trade            trade.jsonl: the archive's one dataset, named
drishti.py convert bundle.zip                          bundle/trade.jsonl, bundle/position.jsonl: one file per folder (each a dataset)
```

| Option | Does |
|---|---|
| `--out FILE` | where to write (default: the input's name with `.jsonl`; a folder: `<folder>.jsonl` beside it; `-`: standard output) |
| `--rows-document` | a CSV file becomes one line, `{"id": <file name>, "rows": [...]}`, as the file connector serves a CSV feed file |
| `--force` | replace an existing output file |
| `--dataset NAME` | an archive without folders: the name of its one dataset, so of the output (default: the archive's name, `trade.tar.gz` → `trade.jsonl`) |

An archive (`.zip`, `.tar.gz`, `.tgz`, `.tar`) is read by the loaders' archive rule ([DATA_GUIDE.md section 2.7](DATA_GUIDE.md#7-archives-zip-and-tarballs)):
without folders, its JSON Lines members become one file; with folders one level deep, one file per folder in the folder `--out` names
(default: the archive's name beside it); other members are skipped with a note, and an unsafe or mixed archive is refused.

`drishti.py convert data/feeds/fixing/SOFR-HISTORY.csv --rows-document --out data/feeds/fixing.jsonl` prints
`data/feeds/fixing.jsonl: 1 lines from data/feeds/fixing/SOFR-HISTORY.csv`. A line that cannot be read stops the run and names it
(`bad.ndjson:2: ...`); nothing is written then. Exit codes: 0 converted; 1 a file could not be read or the output exists (without
`--force`); 2 usage or no such file.

## 11. `design`: the Screen Designer over REST

The same designs the **Build** workbench shows, from a terminal or a pipeline: create one from samples and/or a Sutra file,
check it against every sample, save edits made in your editor, send it for review, and let an approver decide. Everything the
screens do about review is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#22-saving-and-proposing); the endpoints are in
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
| `approvals approve|reject sutra PROPOSAL` | decides it in the one queue (`--comment`; a rejection needs one) | `POST /api/v1/approvals/sutra/{id}/approve|reject` |
| `design export ID -o f.zip` | the design as a pack-fragment zip | `GET /designs/{id}/export` |
| `design import f.zip` | zip of a pack folder (or Sutras, tests, samples) as designs | `POST /designs/import` |
| `design bind [ID]` | with no id: is file binding on; with `--file F` / `--sync` / `--save` / `--unbind` | `GET /designs/binding`, `POST /designs/{id}/bind|sync|save-file`, `DELETE .../bind` |
| `design delete ID` | deletes one | `DELETE /designs/{id}` |

**Create, check, hand over.** Against the quick-start server (sign-in on, `DRISHTI_STUDIO_SAVE=true`, the development administrator's
token in `DRISHTI_TOKEN`), with the all-panels showcase document of the Sutra examples as the sample:

```bash
export DRISHTI_SERVER=http://localhost:18480
python3 tools/drishti.py design create --name swap-demo --kind trade --samples docs/guides/examples/all-panels-showcase.json --autodesign
python3 tools/drishti.py design list
python3 tools/drishti.py design check bdc51fed0ce7
python3 tools/drishti.py design propose bdc51fed0ce7 --note "first cut"
python3 tools/drishti.py design proposals
python3 tools/drishti.py approvals reject sutra P-000002 --comment "not this time"      # or: approvals approve sutra P-000002 --comment "looks right"
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
sutra P-000002  REJECTED
```

`design list` also lists your other designs (the drafts of **Build → New pack** are designs too); the ids and proposal numbers are your
own. `approvals approve sutra` answers the same way (`sutra P-000002  APPROVED`) and puts the Sutra live: on a
shared server, reject or delete what you only tried (`design delete bdc51fed0ce7`).

(`design check` exits 1 and lists each failing sample when something does not render.)

A rejection without a comment is refused before it is sent (and the server would refuse it too):

```text
$ python3 tools/drishti.py approvals reject sutra P-000001
drishti: say why it is rejected: --comment "..."      (exit 2)
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

### `approvals`: the one Approvals queue

The one Approvals queue from the terminal (`tools/approvalscli.py`; [USER_GUIDE.md › Approvals](USER_GUIDE.md#approvals), the API
[API_GUIDE.md › Approvals](API_GUIDE.md#approvals-apiv1approvals)), as the caller: every four-eyes kind is decided here (`sutra`,
`change`, `bi`, `compute`, `function`, `stress-window`, `var-report`). A token needs the kind's scope (`edit`, `design:approve`, `bi:approve`,
`pivot:approve`) or `approve`; report sign-offs, stress windows, compute packages and questions are a person's, never a token's.

- `approvals list [--box inbox|sent|done|all] [--group data|screens|risk|access] [--kind KIND]`: waiting for you (the default), sent by you,
  decided, or everything; due-soon items first (marked `!`), then the newest.
- `approvals show KIND ID`: what changes, its effect if approved, the rules, the questions and what you may do.
- `approvals approve|reject|withdraw KIND ID [--comment TEXT] [--param NAME=VALUE ...]`: decide (a reason is required to reject; never your
  own) or withdraw your own. `--param` carries what a kind needs: `scope=group:rates` (a pivot function), `step=checker` (a report run),
  `force=true` or `applyRenames=true` (a BI item).
- `approvals ask KIND ID TEXT`: a question on the item (or an answer); the maker is told.

Exit codes are `0`, `1` (the server said no) and `2` (usage; a rejection without `--comment` is refused before it is sent).

```bash
drishti-console/.venv/bin/python tools/drishti.py approvals list
drishti-console/.venv/bin/python tools/drishti.py approvals show sutra P-000042
drishti-console/.venv/bin/python tools/drishti.py approvals approve function asha:wret@1 --comment reviewed --param scope=group:rates
drishti-console/.venv/bin/python tools/drishti.py approvals ask change chg-01M5V6GM7NCKSR506RQ86M7YVV "Which ticket?"
```

### `change` and `edits`: write-back

Write-back from the terminal (`tools/changecli.py`, [WRITE_BACK.md](WRITE_BACK.md#16-the-design-and-its-reasons),
[WRITE_BACK.md section 4a](WRITE_BACK.md#4a-submitting-a-change-write-back)), as the caller's token (it needs the `edit` scope):

- `change propose --kind KIND --id ID --set FIELD=VALUE [...] [--from FIELD=VALUE ...] [--reason TEXT] [--sutra S --panel P] [--date YYYY-MM-DD] [--override]`
  proposes a change to a record's fields: the same request as a form's Submit. A field must be editable by a Sutra's form with `save:`;
  `--from` is the value you saw (a field that moved since is conflicted, never written over).
- `change submit DRAFT [--reason TEXT]` submits one of your form drafts (`d-...`): one change per record.
- `change list [--state awaiting|queued|applied|refused|conflicted|rejected|withdrawn] [--kind] [--entity] [--mine]` lists changes, newest first.
- `change show CHANGE` shows one change (exit `1` when it was refused, conflicted or rejected).
- `approvals approve change CHANGE [--comment TEXT]`, `approvals reject change CHANGE --comment TEXT`, `approvals withdraw change CHANGE`:
  decide (an approver, never your own change) or withdraw your own while it awaits approval.
- `change history KIND ID` lists a record's changes from the edit log and its version.
- `edits status` shows Admin → Edits: where the edit log lives and why, the lanes, warnings, unconfirmed source commands and each
  connector's `writes:` (administrators).

Exit codes are `0`, `1` (refused, conflicted, or the server said no) and `2` (usage).

```bash
drishti-console/.venv/bin/python tools/drishti.py change propose --kind trade --id MX-20000012 --set NOTIONAL=28001250 --from NOTIONAL=28000000 --reason "booking typo"
drishti-console/.venv/bin/python tools/drishti.py change list --state awaiting
drishti-console/.venv/bin/python tools/drishti.py approvals approve change chg-01M5V6GM7NCKSR506RQ86M7YVV --comment "ticket 4411"
drishti-console/.venv/bin/python tools/drishti.py edits status
```

## 12. `bi` and `tables`

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

`drishti.py pack check` also refuses a pack that defines a mnemonic named `BI`, `RUPAKA`, `FORM`, `FUNC`, `WHAT`, `DATAMAP`, `DATA`, `EXPORT` or `ADMIN` (reserved command words).

### `bi expr`, `bi migrate`, `bi sql`: the BI languages

Rachana, BI's expression language ([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#rachana-el-expressions); the functions in [FUNCTIONS.md](FUNCTIONS.md)),
and Drishti SQL over REST, as the token's user (the server owns the one parser of each; [API_GUIDE.md](API_GUIDE.md#expressions-apiv1biexpr)). Reads
need no scope.

```
drishti.py bi expr functions [--scope row|rule|aggregate]                 the function catalogue, by family
drishti.py bi expr validate EXPR [--scope S] [--model M] [--alias A]       check one expression as you see the model; exit 1 on an error
drishti.py bi migrate --boards FILE|DIR... [--write] [--keep-old]          boards of the older form to reports with board pages: writes reports/<name>.report.yaml, removes the board file
drishti.py bi migrate --boards [--dry-run]                              the same for the server's stored boards (bi-admin; the server does it at start too)
drishti.py bi sql "SELECT ..." --model M [--explain] [--format table|json|csv|jsonl] [--file F]   Drishti SQL over a model
```

BI's own dataset and model files (`rachana: 1` and `2`) and their old expression syntax are migrated into tables and models of the table layer by
`drishti.py tables migrate` ([TABLES_GUIDE.md](TABLES_GUIDE.md), the migration); BI refuses them where it reads (`DRS-9196`).

```bash
drishti-console/.venv/bin/python tools/drishti.py bi expr validate "sum(trades.NOTIONAL) / count(trades)" --model trading-risk
drishti-console/.venv/bin/python tools/drishti.py tables migrate --check        # CI: exit 1 while a BI file is in the old form
```

Captured, with a lower-case name the second time (the model's columns are the packs' UPPER_SNAKE names):

```
valid  (del2, aggregate scope, type decimal)
INVALID  (del2, aggregate scope, type any)
  error   DRS-9152  at 4-19: no field 'trades.notional'; did you mean trades.NOTIONAL?
```

The expressions themselves: [RACHANA_REFERENCE.md › Rachana-EL expressions](RACHANA_REFERENCE.md#rachana-el-expressions). In the
console the migration's state is on Admin → **BI** (items that could not be converted, with why).

### `bi model`, `bi mq`: models and model queries

What the model designer and a report's visuals do, over REST as the token's user (the server checks every model and answers every query with your row
rules and masks; [BI_GUIDE.md section 23](BI_GUIDE.md#23-models-and-pack-datasets-the-model-layer)). Reads need no scope.

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

What the Transform editor's inputs and snippets picker do (`tools/bitransform.py`, loaded by `drishti.py`; [BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17](BI_GUIDE.md#17-data-prep-the-transform-editor)). `input`
asks the server, as the token's user, for each input of a Python dataset (`POST /api/v1/bi/python/input`: a model query, or a pack kind read through a
connector) and prints its rows, what is masked for you and a short profile of every column; `snippets` and `snippet` read the console's library
(`drishti-console/config/prep-snippets.yaml`, no server) and print the step a snippet makes, each value written as a literal.

```
drishti.py bi transform input FILE [--input NAME] [--limit N] [--as-of DATE] [--rows N] [--json]   each input of FILE.dataset.yaml as you
drishti.py bi transform snippets [--json]                                                     the data-prep snippets
drishti.py bi transform snippet ID [NAME=VALUE ...] [--inputs a,b]                              one step (a list: a,b; a mapping: from:to,from:to)
```

Captured, the steps of [tutorial 9, step 12](../tutorials/09-notebook-and-python.md#step-12--prepare-raw-trades-step-by-step-the-transform-editor):

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

The steps themselves run on the server's compute pool (the editor's step-by-step run, and the dataset when it is read); save the drafts with `bi item new`.

### `bi item`, `bi dataset`: items from files, tests and refresh

What My workspace's **New item**, the item page's **Edit**, **Check**'s expected values and a dataset page's **Refresh now** do, over REST as the token's
user. A write needs the `bi:write` scope and the `bi-author` power; the server checks every text and decides who may do what.

```
drishti.py bi item new WORKSPACE FILE [--kind K] [--title T] [--audience ROLE,...]   a draft from a YAML file; the kind is read from the file name (x.model.yaml)
drishti.py bi item save ID FILE [--message M]                                       a new saved state of the draft (the history shows the message)
drishti.py bi item tests ID [--role R]                                              run the item's expected values (tests:) as you or a role you hold; exit 1 on a failure
drishti.py bi dataset refresh ID [--now] [--wait S]                                 the refresh state; --now refreshes and waits until it ends (a rachana 2 dataset: through the models that read it)
```

Publishing is `bi catalogue publish ID` (above). Captured, the steps of [tutorial 1, Part D, Part D](../tutorials/08-reports-and-dashboards.md#part-d--bi-that-runs-itself):

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

### `bi runs`, `bi mq --async`, `bi sql --async`: BI runs

`drishti.py bi runs list|get|submit|estimate|wait|result|cancel|rerun|export|share|unshare|promote|keep|schedule|schedules|storage|suggestions`
does what the BI Runs tab does, as the token's user (`tools/biruns.py`); `bi mq` and `bi sql` take `--async [--wait 2s] [--yes]`, which
send `Prefer: respond-async` and print the run's id when the answer is a `202`.

- `bi runs wait` exits 0 done, 3 failed, 4 cancelled and 5 when its `--timeout` passes with the run still going; `bi runs submit` without
  `--yes` exits 2 on a large question and prints its estimate.
- `bi runs share RUN --to WHO` prints each recipient's mode (sees the same, re-masked, recomputed as them; a refusal on stderr) and
  `unshare RUN WHO...` removes recipients.
- `bi runs schedule RUN "weekly mon 08:00"` and `schedules [list|edit ID WHEN|disable|enable|delete ID]` use the subscription grammar.
- `bi runs export RUN --format csv|xlsx --out FILE`.
- `bi runs storage` is the bi-admin's view of the result store and the queue; `bi runs suggestions [--model M] [accept|dismiss ID]` is the
  bi-admin's list of rollup suggestions.

```bash
drishti-console/.venv/bin/python tools/drishti.py bi runs submit --mq year.mq.json --yes --wait 0
drishti-console/.venv/bin/python tools/drishti.py bi runs wait r-java-2-01JA... --timeout 30m && drishti-console/.venv/bin/python tools/drishti.py bi runs result r-java-2-01JA... --out pnl.csv
```

The guide with output: [BI_GUIDE.md section 28](BI_GUIDE.md#on-the-command-line).

### `bi statement`

A report's statement pages ([BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 24](BI_GUIDE.md#24-statements-paginated-reports-page-exact-one-per-reader)) made for the token's user, with their own masks and row rules: what the BI Studio's Paper
tab previews and a subscription mails.

```
drishti.py bi statement preview REPORT|--file F [--page P] [--date D] [--param NAME=VALUE ...]   the pages: count, paper, period, each page's header and footer
drishti.py bi statement file REPORT|--file F [--format pdf|xlsx|jsonl] [-o OUT] [--page P] [--date D] [--param NAME=VALUE ...]   the PDF (page-exact), the Excel workbook or the rows as JSON Lines
```

`REPORT` is the report's id (`file:report:<name>` for a shipped file); `--file` sends a draft's YAML (a BI author). `--date` is the statement's date: its
`period` is worked out from it. `--param` gives a parameter of the report's model (a field parameter's choice, a what-if value). Captured, tutorial 1, Part D, Part C:

```text
$ python3 tools/drishti.py bi statement preview bi-39eb12fe47 --date 2026-10-08
Monthly statement: 3 pages, A4 portrait, October 2026, 66 rows
  page 1: Monthly statement | October 2026 | For wes  ...  Run 2026-10-19 · by DESK | Page 1 of 3  (80 cells)
  page 2: Monthly statement | October 2026 | For wes  ...  Run 2026-10-19 · by DESK | Page 2 of 3  (160 cells)
  page 3: Monthly statement | October 2026 | For wes  ...  Run 2026-10-19 · by DESK | Page 3 of 3  (6 cells)
$ python3 tools/drishti.py bi statement file bi-39eb12fe47 --date 2026-10-08 -o statement.pdf
wrote statement.pdf (5,000 bytes)
```

A table that stops at `drishti.distribution.statements.max-rows` (10,000) is named on a `cut:` line by both commands, as the PDF's note
above the table says it, so a script can tell a whole statement from a cut one:

```text
$ python3 tools/drishti.py bi statement file bi-39eb12fe47 -o full.pdf
wrote full.pdf (646,896 bytes)
  cut: Positions: The first 10,000 rows only: the table goes on (drishti.distribution.statements.max-rows).
```

A date no data covers is named on a `hint:` line by both commands (the preview's `hint`, the file's `X-Statement-Hint`), as the designer shows it beside
the page count: the dates the statement read and the nearest dates with data. Counts read in the singular for one (*1 page*, *1 row*, *1 cell*):

```text
$ python3 tools/drishti.py bi statement preview bi-39eb12fe47 --date 9999-12-31
Monthly statement: 2 pages, A4 portrait, December 9999, 3 rows
  hint: No data is held for 9999-12-31, the date this December 9999 statement reads: every band is empty. The nearest date with data is 2026-10-20 (before). Choose a date the data covers.
  page 1: Monthly statement | December 9999 | For wes  ...  Run 2026-10-20 · by DESK | Page 1 of 2  (1 cell)
  page 2: Monthly statement | December 9999 | For wes  ...  Run 2026-10-20 · by DESK | Page 2 of 2  (1 cell)
$ python3 tools/drishti.py bi statement file bi-39eb12fe47 --date 9999-12-31 -o empty.pdf
wrote empty.pdf (2,093 bytes)
  hint: No data is held for 9999-12-31, the date this December 9999 statement reads: every band is empty. The nearest date with data is 2026-10-20 (before). Choose a date the data covers.
```

### `tables`: virtual tables and models

The table layer's definitions under `config/tables` (`tools/tablescli.py`; [TABLES_GUIDE.md](TABLES_GUIDE.md)): listed, checked, previewed over sample documents,
traced, and BI's datasets and models migrated into them. The commands run in Java from the server's exec jar
(`java -jar drishti-server-*-exec.jar tables ...`) without starting a server and write nothing under the data folder; paths are read from
the folder you run them in (`--root` for another catalogue folder). Every command takes `--json`.

```
drishti.py tables list                                              every table and model: source, grain, columns, materialisation
drishti.py tables check [--sample TABLE=FILE.jsonl]... [--stored NAME,...]   every definition and the catalogue; exit 1 on an error
drishti.py tables preview TABLE --sample FILE.jsonl [--related TABLE=FILE.jsonl]... [--fx FILE.jsonl] [--limit N]
                                                                    the table's first rows over sample documents (JSONL, .gz too);
                                                                    related() reads another table's sample, fx() spot rates (PAIR_NAME, MID)
drishti.py tables lineage NAME                                      who uses a table or a model
drishti.py tables offer NAME --sample FILE.jsonl (--kind KIND | --lake LAKE --table TABLE) [--grain '$.LIST[*]'] [--write]
                                                                    the flat virtual table offered for a new table, columns from a sample
drishti.py tables migrate [--bi DIR] [--dry-run | --check] [--report FILE] [--audit FILE] [--who NAME] [--moved-to DIR]
```

`tables migrate --dry-run` prints the unified diff of every file it would write under `config/tables/bi/` and the report (every item, every
expression before and after, every refusal); `--check` exits 1 when anything would change; without either it writes the files, one audit
line each with `--audit`. A second run changes nothing. Captured on the shipped samples:

```text
$ drishti.py tables migrate --dry-run --report migration.txt
--- /dev/null
+++ b/bi/counterparty_exposure_counterparties.table.yaml
...
items: 34 (converted 34, unchanged 0, refused 0)
expressions: 103 (rewritten 22, refused 0)
files to write: 32
$ drishti.py tables migrate --audit data/audit/tables-migrate.jsonl
...
34 file(s) written under /path/to/drishti/config/tables
$ drishti.py tables migrate --check
nothing to migrate
```

## 13. The risk engines: `var`, `exposure`, `sens`, `frtb`, `simm`, `pivot`

Every figure these commands print is the server's, computed for the token's user with their nodes, counterparties and masks; the
terminal only displays it.

### `var`: the VaR engine

The VaR engine from the terminal (`tools/varcli.py`; [VAR_GUIDE.md](VAR_GUIDE.md), the API `/api/v1/risk/var`), as the token's user sees
it (their nodes, their masks). A scope is the scope grammar's text: quote it in the shell (`'BANK - DESK:CREDIT'`). `--date YYYY-MM-DD`
picks the business date (default the newest with vectors); `--json` prints the server's answer.
- `var show SCOPE` prints the headline: VaR and ES with their ranks, the limit and its use, the parts and diversification, the backtest,
  the trades counted and hidden, the vectors' batch and where the vector came from (`node aggregate (float64)`, `hot file …`).
- `var parts SCOPE [--by child|node|position|trade|desk|book] [--scenario DATE] [--top N]` prints the contributions: standalone,
  component (and its share of the VaR), incremental VaR and component ES per part.
- `var path SCOPE [--page N] [--size N]` prints the scenario days worst first, and the part that lost most on each.
- `var history SCOPE` prints the history and backtest (the official record for a node, else the replay of today's vector) with the
  traffic light, Kupiec and Christoffersen.
- `var tree [--hierarchy MGMT|LEGAL]` prints every node with its VaR, ES and limit use, indented.
- `var stress-window [SCOPE] [--hierarchy H] [--date D] [--length DAYS] [--confidence Q] [--propose [--first D] [--note T]] [--proposals]`: the stress window selection ([VAR_GUIDE.md section 18.9](VAR_GUIDE.md#189-choosing-the-stress-window)): every window of the node's long-history P&L as a stressed VaR, the current and the worst, the alert, the candidates and every node's own worst window; `--propose` freezes it and proposes a window, `--decide` approves or rejects a proposal (another person than its proposer).
- `var matrix ID [--days N] [--by trade [--scenario D]] [--confidence Q] [--es-confidence Q] [--window N] [--top N] [--csv]`: the
  scenario matrix of a trade, a position, a P&L vector's or scenario PV's id, or a scope over its last N business dates (20; at most 60):
  each date's source (`open`, `lake`, `official`, `engine`), VaR with its rank, ES, base PV and gap, then the scenarios worst on the
  newest date (`*` in a tail, `—` no value); `--csv` writes the whole matrix as the console's export does
  ([VAR_GUIDE.md section 3.9](VAR_GUIDE.md#39-the-scenario-matrix-smat)).
- `--ccy EUR` (`show`, `parts`, `path`, `history`, `tree`, `capture`, `official`, `whatif`) asks every figure in a reporting
  currency; the headline says the currency and where its FX came from ([VAR_GUIDE.md section 15.10](VAR_GUIDE.md#1510-reporting-currency)).
- `var status` prints the engine's settings, its tiered memory (hot files on disk, dates open, how this JVM maps) and the dates loaded.
- `var shards [--date D] [--json]`: VaR sharding across Drishti*net: the settings, the members, the shards held here, and the
  date's shard map with each shard's range, owners and the members ready for it ([DATA_ARCHITECTURE.md section 13](../architecture/DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built));
  `--drain` / `--undrain` (administrators) hand this member's shards over before it stops, or take shards again.
- `var capture SCOPE [--note TEXT]`, `var captures [--scope SCOPE]`, `var compare A [B|now]`: captures (writing needs `risk:write`).
- `var basket NAME SCOPE [--title] [--description] [--share USER|role:NAME ...]`, `var baskets`: saved baskets; `var baskets --all [--owner TEXT]`: every person's (administrators). `var basket-delete NAME [--owner USER] [--reason TEXT] [--version N]`: deletes your basket, or with `--owner` anyone's (administrators; the reason goes to the audit log and the owner's inbox); captures of it are kept ([VAR_GUIDE.md section 3.5](VAR_GUIDE.md#35-construct-your-basket)).
- `var official [--date] [--capture]`: a date's official end-of-day record; `--capture` takes it now (administrators).
  `--synthetic-history DAYS` seeds a demo server's synthetic official history (VAR_GUIDE.md section 18.10).
- `var backtest [SCOPE] [--date] [--xlsx FILE]`: the regulatory backtest of a node (VAR_GUIDE.md section 18.11).
- `var stats [SCOPE] [--period FY2026] [--measure var]`: a node's period statistics over the official records (VAR_GUIDE.md section 18.10).
- `var explain SCOPE [--from D] [--to D] [--confidence Q] [--matrix] [--xlsx FILE]`: why VaR moved (`VAR EXPLAIN`, VAR_GUIDE.md section 3.11): the change between two business dates (default the one before the newest, and the newest) in steps that add up exactly, printed as sentences, the waterfall as aligned lines, the states and each step's drivers; `--matrix` every child node, the steps across; `--xlsx` the working as a workbook of formulas.
- `var playground [SCOPE | --ids ...] [--estimate] [--xlsx FILE --count N]`: the VaR Playground (`PLAY VAR`, VAR_GUIDE.md section 3.10): the scope's VaR worked out position by position; `--estimate` states a workbook's size and recalculation time first, `--xlsx` writes the workbook of formulas.
- `var report list|run|show|sign|file [REPORT] [PERIOD]`: report runs, their sign-off (`--step`, `--do`, `--comment`) and files
  (`--format`, `--draft`) (VAR_GUIDE.md section 18.14).
- `var reload [--date D]`: drops the loaded dates (administrators); with `--date` only that business date.
- `var intraday [--date D]`: each loaded date's intraday layer (updates, trades, batches, what was ignored or refused); `var intraday --load FILE.jsonl [--date D] [--lake DIR] [--no-signal] [--dry-run]` appends an intraday batch to the lake (`lake build --mode append`) and raises its data-load signal ([VAR_GUIDE.md section 8.4](VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes)); a file mixing scenario PVs and P&L vectors is routed by each record's `VALUE_TYPE`.
- `var convert FILE.jsonl [--set FIELD=VALUE ...] [--infer] [--out F]`: makes an older PV or vector file say what each record is (`VALUE_TYPE`, `GRAIN`, `CURRENCY`, `SOURCE_SYSTEM`, leading each line), e.g. `--set VALUE_TYPE=PNL --set GRAIN=TRADE --set CURRENCY=USD --set SOURCE_SYSTEM=MUREX`; `--infer` takes `VALUE_TYPE` from the payload and `GRAIN` from the id; exit 1 when a record still lacks one ([VAR_GUIDE.md section 15.13](VAR_GUIDE.md#1513-inputs-any-form-any-grain)).
- `var whatif SCOPE [--copy TRADE=FACTOR ...] [--trades FILE.json]`: the scope's VaR, ES and stressed VaR before and after
  hypothetical trades, by the server's VaR engine (`POST /api/v1/risk/var/what-if`, no compute workers needed): `--copy` a booked trade or any
  scope scaled (`--copy MX-30000121=-1`, its unwind; `--copy BOOK:BOOK-RATES-3=-0.5`), `--trades` trades priced elsewhere (a JSON list of
  `{LABEL, PNL: [one number per scenario day], FACTOR}`); each trade's standalone, incremental and component VaR
  ([VAR_GUIDE.md section 8.2](VAR_GUIDE.md#82-the-what-if-the-java-engine)).

Examples on the demo data:

```text
drishti.py var show 'DESK:RATES'                                   the headline: VaR, ES, limit use, parts, backtest, provenance and tier
drishti.py var show 'BANK - DESK:CREDIT' --date 2026-09-30         any scope of the grammar (+ - & and parentheses)
drishti.py var parts 'DESK:RATES' --by position --top 10           standalone, component, incremental VaR and component ES per part
drishti.py var path 'DESK:RATES' --size 10                         the scenario days worst first, and what lost most on each
drishti.py var history 'DESK:RATES'                                VaR history, exceptions, traffic light, Kupiec and Christoffersen
drishti.py var tree --hierarchy LEGAL                              every node with its VaR, ES and limit use
drishti.py var stress-window BANK                                 the stress window selection (SWIN): the worst 250-day window against the current, the candidates
drishti.py var stress-window BANK --propose --first 2007-11-30     freeze the analysis and propose that window; another person decides it
drishti.py approvals approve stress-window SWP-000001   approve a proposal (not its proposer)
drishti.py var matrix 'DESK:RATES' --days 20                       the scenario matrix (SMAT): each date's VaR, ES, source and gap, the worst scenarios
drishti.py var matrix POS-BOOK-1~SEC-A --by trade                  a position's trades under the newest VaR scenario, across the dates
drishti.py var matrix MX-30000121 --days 30 --csv > t.csv          the whole matrix as CSV (a gap's cells empty, never 0)
drishti.py var explain BANK                                        why VaR moved since the previous business date: steps that add up exactly, their drivers
drishti.py var explain DESK:DESK-RATES --from 2026-08-31 --to 2026-09-30   a month's change; --matrix: every child node; --xlsx FILE: the working in formulas
drishti.py var playground BOOK:BOOK-RATES-1                        the VaR Playground (PLAY VAR): the headline, the checks, the largest positions' figures
drishti.py var playground BANK --estimate                          each export size: file size, recalculation time, the applications that open it
drishti.py var playground BANK --count 1000 --xlsx bank.xlsx       the workbook of real formulas (100, 500, 1000, 10000 or all; the rest in Rest of scope)
drishti.py var status                                              settings, the tiered memory (hot files, open dates) and loaded dates
drishti.py var backtest BANK                                       the regulatory backtest: exceptions at 99% and 97.5%, zone, multiplier, the desks
drishti.py var stats BANK --period FY2026                          period end, average, high, low; the 60-day and 12-week stressed averages
drishti.py var report run annual-report-var FY2026                 generate and freeze a report run (checks, lineage); show, sign, file, list
drishti.py var official --synthetic-history 259                    a demo server's synthetic official history (administrators)
drishti.py var shards --date 2026-09-30                            VaR sharding: the date's shard map, owners, members ready
drishti.py var shards --drain                                      hand this member's shards over before it stops (administrators)
drishti.py var basket hedges "WHERE:'hedge' in TAGS" --share role:market-risk
drishti.py var basket-delete hedges                                delete your own basket (captures of it are kept)
drishti.py var basket-delete unwind --owner asha --reason 'left the desk'   an administrator deletes anyone's, audited, the owner told
drishti.py var baskets --all --owner asha                          every person's baskets (administrators), filtered by owner
drishti.py var capture 'BASKET:asha/hedges' --note 'before the unwind'
drishti.py var compare CAP-20260930-7 now
drishti.py var whatif 'DESK:RATES' --trades new-swap.json --stress-window 250   the server's engine: before and after hypothetical trades
drishti.py var show BANK --horizon 10d                             the 10-day VaR by the default method (ten-day-method: sqrt10)
drishti.py var show BANK --horizon 10d --method sum                the sum of 10 consecutive days: 500 overlapping windows of 509
drishti.py var show BANK --horizon 10d --method sqrt10             the 1-day figures × √10
drishti.py var show BANK --confidence 95                           VaR at 95% (one of drishti.var.confidences), ES with --es-confidence
drishti.py var parts DESK:RATES --horizon 10d --method sum         parts, path, history, tree and capture take the same lens
drishti.py var show BANK --stressed                                stressed VaR: the date's stress scenario set, in USD (213,637,794 on the demo)
drishti.py var show NODE:MGMT~BANK --stressed --horizon 10d --method sum   the same at 10 days; a node's 12-week average printed too
drishti.py var show DESK:DESK-RATES                                prints the parametric line under the historical one (PVAR's cross-check, never official)
drishti.py var show BANK --no-parametric                           leaves it out (parametric=false)
drishti.py var parts DESK:DESK-RATES --matrix                      the parametric components (they add up to the parametric VaR) and the parts' correlations
drishti.py var parts DESK:DESK-RATES --no-parametric               the historical columns only
drishti.py var playground DESK:DESK-RATES --measure parametric     the Parametric tab: the parametric steps; with --xlsx FILE the workbook gains its Parametric tab
```

While parametric VaR is on (`drishti.var.parametric.enabled`), `show` prints `parametric VaR 58,409,229 USD = z·σ − μ = 2.3263 ×
24,792,909 − (-732,297)   ES 58,693,229   fat-tail gap +16,224,451 (+27.8%)   a cross-check, not official` for `DESK:DESK-RATES` on the
demo data; `parts` adds the *Param. component* and *ρ* columns and, with `--matrix`, the correlation matrix and the engine that computed
it (`openblas` or `java`); `--json` carries the API's `parametric` block ([tutorial 2, Part B](../tutorials/02-var-from-the-bank-to-a-trade.md#part-b--parametric-var-beside-historical-the-fat-tail-gap)).

`--horizon`, `--method`, `--confidence` and `--es-confidence` read any answer through a lens: the headline then says
`horizon    10-day (sum)` and `VaR 99%   274,544,283 USD   (the 5th worst of 500 overlapping windows)` on the demo data; limit use is
printed only when the lens is the limit's own (else *limit is for 1-day 99%: not comparable*), the backtest only at 1 day. `show`
with `--window` keeps the comparison ([VAR_GUIDE.md section 18.6](VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen)).

Exit codes: 0; 1 when the server said no (the code and its message on standard error) or a what-if was refused or failed; 2 for usage.
[Tutorial 2, Part A](../tutorials/02-var-from-the-bank-to-a-trade.md#part-a--historical-var-from-the-bank-to-a-trade) walks through them with the demo data.

### `exposure`: the exposure engine

The exposure engine from the terminal (`tools/expcli.py`; [EXPOSURE_GUIDE.md](EXPOSURE_GUIDE.md), the API `/api/v1/risk/exposure`),
as the token's user sees it (their counterparties, groups and legal entities; trades, aggregate or none). A scope is the scope grammar's
text (`BANK`, `LE:LE-NY`, `GROUP:GRP-ALDERSHOT`, `CPTY:CP-ALDERSHOT`, `NSET:NS-ALDERSHOT-LDN`, `TRD:<id>`, `DESK:`, `BOOK:`,
`WHERE:<rule>`, with `+ - &`): quote it in the shell. The scoped subcommands take `--date YYYY-MM-DD` (default the newest date with
paths), `--q Q` (the PFE confidence, default 0.95) and `--collateral lagged|current|none` (default `lagged`); every subcommand takes
`--json` (the server's answer).
- `exposure show SCOPE` prints the figures (peak PFE and its date, EPE, EEPE, CE, EAD under the internal model, informational), the
  stored credit limit's use where one applies, and the profile (EE, ENE, EEE, PFE, PFE 99%, EE without collateral per primary date).
- `exposure parts SCOPE [--by child|netting-set|counterparty|legal-entity|trade|asset] [--figure PFE|EE|EPE] [--at TENOR|DATE]
  [--top N]` prints the contributions (to the peak PFE they add up; EE at a date; EPE) with the incremental PFE of each part.
- `exposure tree [--date] [--q]` prints the bank, legal entities, counterparties and netting sets with peak PFE, EPE and limit use.
- `exposure profile SCOPE [--csv]` prints the profile at every primary date (`--csv`: `tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll`).
- `exposure distribution SCOPE [--at TENOR|DATE]` prints the exposure distribution at a primary date (the peak by default) and the
  path fan at every date.
- `exposure status` prints the engine's settings, its hot files and the dates loaded.
- `exposure capture SCOPE [--note TEXT]`, `exposure captures [--scope SCOPE]`, `exposure compare A [B|now]`: captures, immutable
  (writing needs `risk:write`).
- `exposure official [--date] [--capture]`: a date's official end-of-day record; `--capture` takes it now (administrators).
- `exposure retention`: what the retention keeps of each date held, and the dates whose trade paths are beyond their window.
- `exposure reload [--date D]`: drops the loaded dates (administrators), or with `--date` only that business date; the next
  request maps the hot file again or reads the lake.
- `exposure whatif SCOPE [--like TRADE] [--factor F] [--set NETTING_SET] [--candidates FILE.json] [--method paths|addon]`: the
  scope's exposure before and after candidate trades. `--like` copies a booked trade's paths on the run (`--factor -1` is its unwind),
  `--set` the netting set it joins (default a set of its own); `--candidates` is a JSON list of `{tradeId, nettingSet, paths:
  {copyOf, factor} or {runId, mtm}, assetClass, notional, maturityDate, mtm}`. `paths` (the default) is exact, the candidates' paths
  added to their netting sets path by path; `addon` is the conservative proxy; both computed by the server's exposure engine.
- `exposure predeal WORDS… [--date D]`: the pre-deal check, the command as typed (`PREDEAL <counterparty> LIKE <trade> [<notional>]
  [REVERSE] [IN <netting set>]`, or `PREDEAL <counterparty> <asset class> <notional> <maturity> [MTM <amount>]`; the word `PREDEAL` may
  be left out): the server's decision approve, refer or decline with its reasons, the method, the template, the limit and its tenor
  buckets before and after ([EXPOSURE_GUIDE.md section 18.9](EXPOSURE_GUIDE.md#189-the-pre-deal-check)).

Examples on the demo data:

```text
drishti.py exposure show CPTY:CP-ALDERSHOT                           peak PFE, EPE, EEPE, CE, EAD, limit use and the profile
drishti.py exposure show NSET:NS-ALDERSHOT-LDN --q 0.99 --date 2026-09-30
drishti.py exposure parts CPTY:CP-ALDERSHOT                          contributions to the peak PFE by netting set (they add up)
drishti.py exposure parts NSET:NS-ALDERSHOT-NY --by trade --figure EE --top 10
drishti.py exposure tree                                             bank, legal entities, counterparties, netting sets
drishti.py exposure profile NSET:NS-ALDERSHOT-NY --csv > ns-ny.csv   the profile as CSV
drishti.py exposure profile NSET:NS-ALDERSHOT-NY --jsonl > ns-ny.jsonl   the profile as JSON Lines (a grid date a line)
drishti.py exposure distribution NSET:NS-ALDERSHOT-NY --at 1Y        the distribution at a date and the path fan
drishti.py exposure status                                           settings, hot files and loaded dates
drishti.py exposure capture CPTY:CP-ALDERSHOT --note 'before the unwind'
drishti.py exposure captures --scope CPTY:CP-ALDERSHOT
drishti.py exposure compare XCAP-20260930-1 now
drishti.py exposure official --date 2026-09-30                       the official end-of-day record (--capture: administrators)
drishti.py exposure retention                                        what is kept of each date held
drishti.py exposure reload [--date D]                                drop the loaded dates, or one date (administrators)
drishti.py exposure whatif CPTY:CP-ALDERSHOT --like CLY-4003028 --factor -1 --set NS-ALDERSHOT-FRA   exact, the paths method
drishti.py exposure whatif CPTY:CP-ALDERSHOT --candidates new-swap.json --method addon               the conservative proxy
drishti.py exposure predeal "PREDEAL CP-ALDERSHOT LIKE CLY-4003028 250M"   the pre-deal check: approve, refer or decline (EXPOSURE_GUIDE.md 18.9)
drishti.py exposure predeal "PREDEAL CP-ALDERSHOT RATES 250M 5Y"           described, the proxy: it only refers
```

Exit codes: 0; 1 when the server said no (the code and its message on standard error); 2 for usage. Both what-if methods (`--method
paths`, the default and exact, and `addon`) are the server's exposure engine's.
[Tutorial 3](../tutorials/03-exposure-from-the-bank-to-a-trade.md) walks through them with the test data.

### `sens`, `frtb` and `play frtb`

Position sensitivities and FRTB-SA as the token's user sees them (`tools/frtbcli.py`; [FRTB_SA_GUIDE.md](FRTB_SA_GUIDE.md)):

- `sens show SCOPE [--by RISK_FACTOR|BUCKET|RISK_CLASS|TENOR|MEASURE|<attribute>]`: a scope's sensitivities summed in the reporting currency;
  `sens factors [--q TEXT]`: the factor dictionary, its buckets and source names, and the load's refusals.
- `frtb sa SCOPE`: SBM per class, measure and scenario, the binding one, DRC, RRAO and the total; `frtb explain SCOPE [--class GIRR]`: the
  buckets, factors, WS, K_b and S_b; `frtb contributions SCOPE [--by DESK]`: contributions that add up to the total. Every one takes the
  switches (`--curvature-source`, `--girr-curvature-shock`, `--fx-curvature-divisor`, `--girr-correlation`, `--scenario`), `--override
  PATH=VALUE` (repeatable; not the regulatory figure), `--ccy` and `--date`. `EXAMPLE` is the design's hand-built cases.
- `play frtb SCOPE [--compare SWITCH=VALUE] [--xlsx FILE] [--save]`: the playground: the switches, a comparison explained by step, the
  workbook of live formulas, a recipe; `play frtb --recipe FR-...` replays a recipe and exits 1 when it does not reproduce to the cent.

```bash
drishti-console/.venv/bin/python tools/drishti.py frtb sa DESK:DESK-RATES
drishti-console/.venv/bin/python tools/drishti.py play frtb EXAMPLE --compare girr-correlation=exact
drishti-console/.venv/bin/python tools/drishti.py play frtb EXAMPLE --xlsx frtb-cases.xlsx
```

### `simm` and `play simm`

ISDA SIMM as the token's user sees it (`tools/simmcli.py`; [SIMM_GUIDE.md](SIMM_GUIDE.md)):

- `simm show NETTING-SET`: IM to collect and post, by product class and by risk class (delta, vega, curvature), the binding regimes, the
  add-ons; `EXAMPLE` is the hand-built examples, any VaR scope an analytic. `simm list [--counterparty CP-...]`: every netting set
  (computed, stored and labelled, or why not computed) and the sums. `simm explain NETTING-SET [--direction post] [--class Equity]`: the
  buckets, factors, CR, WS, K_b and S_b. `simm against NETTING-SET`: the margin against the counterparty's call. `simm versions`: the
  installed parameter files and the refused ones with why. Every one takes `--version`, `--concentration on|off`, `--hvr on|off`,
  `--override KEY=VALUE` (repeatable; not the regulatory figure) and `--date`.
- `play simm NETTING-SET [--side post] [--compare SWITCH=VALUE] [--xlsx FILE] [--save]`: the playground: the switches, a comparison in
  words, the workbook of live formulas (a recipe saved with it), a recipe; `play simm --recipe SM-...` replays a recipe and exits 1 when it
  does not reproduce to the cent.

```bash
drishti-console/.venv/bin/python tools/drishti.py simm versions
drishti-console/.venv/bin/python tools/drishti.py simm show NS-ALDERSHOT-LDN
drishti-console/.venv/bin/python tools/drishti.py play simm NS-ALDERSHOT-LDN --compare concentration=off
drishti-console/.venv/bin/python tools/drishti.py play simm NS-ALDERSHOT-LDN --side post --xlsx aldershot.xlsx
```

### `pivot`: the pivot engine

The pivot engine as the token's user sees it (`tools/pivotcli.py`; [PIVOT_GUIDE.md](PIVOT_GUIDE.md)); every figure is the server's:

- `pivot sources`: the tables (and `var`) you may pivot. `pivot fields SOURCE [--date]`: its fields with their types, its own measures,
  the built-in functions, what is masked for you.
- `pivot run`: a view's answer, the rows as shown (indented by level, `+` on a closed member), every column's figures and the totals. The
  view is `--view owner/name` (a saved one), `--json-view FILE` (the API's JSON), or flags: `--source`, `--date`, `--rows A,B` (a date
  groups as `TRADE_DATE:month`), `--cols`, `--values "sum(X)"` (repeatable), `--filter FIELD=V1,V2`, `--open DIM=V[/DIM=V]` (a node
  opened, lazily computed), `--level N`, `--top FIELD=N`. `--csv FILE` writes the grid; `--json` the whole answer. `--exact` asks
  every sketch exactly (exact on request); `--approximate` answers distinct counts and percentiles by their sketches at any size and
  chooses a level's top N by a count with a Space-Saving summary; `--no-rollups` reads the table even when a rollup covers the view; the
  line `computed as:` says how the answer was computed
  ([PIVOT_GUIDE.md sections 23 and 24](PIVOT_GUIDE.md#24-the-screens-switches-exact-or--progressive-rollups)).
- `pivot live start [view flags] [--approximate]`: a live pivot (its id, the node that holds it, how it is kept current);
  `pivot live list` (yours: recomputes, unchanged checks, streaming or not); `pivot live stop ID`
  ([PIVOT_GUIDE.md section 25](PIVOT_GUIDE.md#25-live-pivots)).
- `pivot comments OWNER/NAME [--cell DIM=V,...] [--open]`: a saved view's cell comments; `--add TEXT --cell DIM=V,... [--measure M]`
  adds one (`--cell '*'`: the grand total); `--resolve ID`, `--reopen ID`, `--delete ID` (its author or the view's owner)
  ([PIVOT_GUIDE.md section 26](PIVOT_GUIDE.md#26-cell-comments)).
- `pivot estimate`: what the view reads before it runs.
- `pivot export --format xlsx|csv|arrow [--what pivot|rows|definition] [--cell DIM=V,...] -o FILE`: the Excel workbook with its outline
  and `SUM` formulas only where a figure adds up and a Provenance sheet; CSV; Arrow; a cell's rows; the view as YAML. Audited.
- `pivot views`; `pivot save NAME [view flags] [--share role:risk]`.
- `--option KEY=V` (repeatable): a provider's option; the VaR source's (the MR cube, [PIVOT_GUIDE.md section 29](PIVOT_GUIDE.md#29-the-mr-cube-preset)) are `scope`,
  `horizon`, `method`, `confidence`, `esConfidence`, `set` and `ccy`.
- `pivot mr headline|cell|versions|explain|ladder|heat|whatif`: the MR cube's lens (`/api/v1/risk/mr`): the view's headline, a cell's
  tail (`--cell DIM=V,...`), the slider's dates, why a scope moved (`--scope`, `--from`, `--to`), the DV01 ladder (`--grid`), the heat
  map (`--by QUALIFIER,TENOR`), the what-if side by side (`--copy TRADE --factor -1`).
- `pivot functions list`; `pivot functions test FILE --sample ROWS.csv [--group COL]` (the harness's checks and the states part by part;
  exit 1 when it fails); `pivot functions save FILE --sample ROWS.csv`; `pivot functions submit|approve|revoke owner/name@v [--reason]
  [--scope everyone|group:NAME]` (`tools/pivotfncli.py`; a function file is YAML or JSON: `name, language, code, entry, inputs, kind`).
- `pivot explain [view flags] --cell DIM=V [--other-cell DIM=V | --from DATE --to DATE] --by FIELD [--order member|size]
  [--price P --quantity Q]`: the steps, the residual and the difference.
- `pivot whatif [view flags] [--exclude FIELD=V1,V2] [--scale FIELD=X@DIM=V] [--set FIELD=X@DIM=V] [--add-json ROWS.json]`: the cells
  that change, what-if minus original.
- `pivot rollups list` (the rollups, their rows, version and last refresh, and the advice); `pivot rollups define ID --source S --grain
  DESK,TRADE_DATE:month --functions "sum(NOTIONAL_USD)" [--functions ...] [--dates latest|all]`, `pivot rollups refresh ID [--date]`,
  `pivot rollups remove ID` (administrators; audited).

```bash
drishti-console/.venv/bin/python tools/drishti.py pivot fields trade
drishti-console/.venv/bin/python tools/drishti.py pivot run --source trade --rows productType,counterparty --values "count()" --open productType=IRS
drishti-console/.venv/bin/python tools/drishti.py pivot run --source var --rows DESK --cols CURRENCY --values "var()" --values "es()"
drishti-console/.venv/bin/python tools/drishti.py pivot export --view asha/desk-risk --format xlsx -o desk-risk.xlsx
drishti-console/.venv/bin/python tools/drishti.py pivot run --source var --rows MGMT.DESK --cols RISK_CLASS --values "comp_var()" --option horizon=10d
drishti-console/.venv/bin/python tools/drishti.py pivot mr ladder --scope DESK:RATES --grid FRTB-GIRR
drishti-console/.venv/bin/python tools/drishti.py pivot functions test wret.yaml --sample rows.csv --group DESK
drishti-console/.venv/bin/python tools/drishti.py pivot live start --view asha/desk-risk
drishti-console/.venv/bin/python tools/drishti.py pivot comments asha/desk-risk --cell DESK=RATES --add "checked with the desk" --measure Notional
drishti-console/.venv/bin/python tools/drishti.py pivot explain --source trade --rows productType --values "sum(notional)" --cell productType=IRS --other-cell productType=FXFWD --by counterparty
```

## 14. `compute`, `contract`, `elements` and `feed`

### `compute`: reactive connectors

Reactive connectors from the terminal (`tools/computecli.py`, [REACTIVE_CONNECTOR.md](../connectors/types/reactive/README.md#the-http-api)), as
the caller's token:
- `compute run CONNECTOR KIND ID [--param NAME=VALUE ...] [--wait MS] [--date YYYY-MM-DD]` asks for a computed kind. It prints the answer, or
  `computing` with the request id when the result is not ready within `--wait` (default 5000 ms).
- `compute cancel REQUEST` cancels one of the caller's requests.
- `compute event TYPE ID [--kind] [--entity] [--date] [--payload NAME=VALUE ...] [--watermark LANE=SEQ ...]` sends an event to the
  connectors' triggers (`write-back`, `live`, `data-version`, `schedule`). Administrators only; an id already processed is ignored.
- `compute sandboxes` lists the caller's what-if sandboxes.
- `compute reactive` shows Admin's view of the reactive connectors: kinds, engine, cache, computations and latency.

Examples:

```text
drishti.py compute run path-sum-java path-sum-java BOOK-FX --param delay-ms=20     the answer: state, request id, headline fields, stamp
drishti.py compute run path-sum-python path-sum BOOK-RATES --wait 15000 --json      the whole answer
drishti.py compute cancel req-mvnbo42x-554223c23f7e
drishti.py compute event write-back chg-77 --kind path-strip --entity STRIP-FX-001 --payload BOOK_ID=BOOK-FX --watermark L3=12
drishti.py compute sandboxes
drishti.py compute reactive
```

```text
path-sum-java BOOK-FX~delay-ms=900  REFUSED  (req-mvncbpgc-b05a980336d9)
  delay-ms for path-sum-java is 900: it must be at least 0 and at most 500
```

Exit codes: 0 (`ready`, `computing` or `stale`); 1 when the answer is `failed` or `refused`, or the server said no; 2 for usage.
[Tutorial 19, Part C](../tutorials/19-build-a-pack-and-a-screen.md#part-c--computed-panels-a-reactive-connector) walks through them.

### `contract`: data contracts and the command catalog

The data contract of every command (`tools/contracts.py`, also run on its own as `python3 tools/contracts.py ...`;
[docs/contracts/README.md section 16](../contracts/README.md#16-contracts-of-every-command)). Each mnemonic's contract is
`config/packs/<domain>/<pack>/contracts/<kind>.contract.yaml`, checked against `tools/contracts.schema.json`. No server is needed.
- `contract list` prints every command with its kind, pack, class, status and the number of points still to review.
- `contract show NSET` prints one contract (by command or kind; `--json` as JSON).
- `contract check` fails when a command has no contract, a contract is invalid or no longer matches its pack (identity, id pattern,
  links, the retirements in `tools/contracts.policy.yaml`) and its samples and test data (fields added or gone, a required field empty,
  a value outside an enum, a changed type). `--no-data` skips the data and takes a second.
- `contract docs` writes `docs/contracts/packs/<pack>.md` and the index `docs/contracts/packs/README.md`; `--check` fails when they are stale.
- `contract bootstrap KIND` drafts a contract from the pack, its samples and test data, its `config/about.yaml` glossary and its Sutras,
  and prints it; `--write` saves it (`--force` replaces one that exists), `--all` drafts every command without one. What the draft infers
  rather than reads is listed under `review` for a person to confirm.

Exit codes are `0`, `1` (a problem found, or the contract exists) and `2` (usage). `--packs DIR` and `--testdata DIR` point at other folders.

```bash
python3 tools/drishti.py contract show NSET
python3 tools/drishti.py contract check
python3 tools/drishti.py contract bootstrap my-kind --write && python3 tools/drishti.py contract docs
```

**The command catalog.** `python3 tools/command_catalog.py` writes [COMMAND_CATALOG.md](COMMAND_CATALOG.md): every command of the command
line with what it does and an example, from the packs, their contracts and `tools/commands.builtin.yaml` (the command line's own words and
forms). `--check` fails when it is stale; a console test runs the same check.

### `elements`: Drishti Elements for other applications

Drishti Elements (the JavaScript and CSS libraries) for other applications (`tools/elements/cli.py`, with `dist.py`, `pack.py` and
`dts.py` beside it; the guide [ELEMENTS_IN_YOUR_APP.md](ELEMENTS_IN_YOUR_APP.md)). Offline, Python only; nothing is published.

- `elements pack --libs core,grid,chart [--theme dark|light|<theme>] [--out DIR] [--include-domain banking] [--no-engines] [--maps]`:
  one `drishti-elements.js` and one `drishti-elements.css` with exactly the named libraries and the ones they require (deepest
  first), the vendored engines they need (ECharts) unless `--no-engines`, and `drishti-elements-pack.json` (what is inside, with SRI
  hashes). The output and its first comment name every library and version inside. A library licensed with a domain module
  (drishti-risk: banking) is refused (exit 1) unless `--include-domain banking`; an unknown library is refused too. `--libs all`:
  every library the licence allows. Default folder `target/elements/pack`.
- `elements dist [--out DIR] [--include-domain banking] [--only zip|npm]`: the release archive `drishti-elements-<version>.zip` and the
  npm tarballs `@drishti/elements-<name>` (`npm/drishti-elements-<name>-<version>.tgz`); with banking the archive is
  `drishti-elements-<version>+banking.zip` and drishti-risk's tarball is added. Default folder `target/elements`.
- `elements webjar-tree --out DIR [--version V]`: the WebJar's files; the Maven profile runs it
  (`./mvnw -P elements -pl drishti-elements package`).

```bash
python3 tools/drishti.py elements pack --libs core,grid,chart --theme dark --out build/vendor/drishti
python3 tools/drishti.py elements dist
```

### `feed`: the data feeds

drishti-feeds, the data feeds that pull external data and load it into the lakes (`tools/feeds/`, one file a feed in `config/feeds/`;
the guide [EXTERNAL_DATA.md](EXTERNAL_DATA.md)). The pull is Python; the load is `lake build` (the parallel loader by default) or the data
inbox. The wrappers `tools/feeds/feed.sh` and `tools/windows/feed.ps1` run the same with the console's Python.

- `feed list [--json]`: every feed: its lake, load route, schedule, licence (internal only or not) and datasets.
- `feed run <feed>|--all [--date D | --since D] [--load java|python|inbox|none] [--lake L] [--dry-run] [--snapshot [--internal-ok]]
  [--offline] [--set NAME=VALUE] [-- SOURCE ARGS]`: pull (incrementally, from the feed's state), write
  `<data>/datafeeds/<feed>/<date>/<table>.<date>.jsonl.gz` with a manifest, load into the feed's lake, replacing the dates it brings; one
  log line and one ledger line (`<data>/datafeeds/ledger.jsonl`) per run. `--snapshot` refreshes the committed demo snapshot in
  `data/testdata` instead. Exit codes: 0 ok, 1 failed, 2 refused, 3 locked, 4 partial.
- `feed status [<feed>] [--runs N] [--json]`: per feed the last run, the last success, rows, duration and failures (exit 1 when a
  feed's last run failed).
- `feed schedule --cron | --systemd | --windows [<feed> ...] [--log-dir DIR] [--root DIR]`: ready-to-use daily entries, one per enabled
  feed (`--root`: the Drishti folder on the machine that will run them).

```bash
python3 tools/drishti.py feed list
python3 tools/drishti.py feed run gleif --dry-run
python3 tools/drishti.py feed run --all
python3 tools/drishti.py feed schedule --cron
```

## 15. Store loaders: `tools/load-*.sh`

Each loader writes the banking packs' demo data (1,791 sample documents x 10 business days, and with `--trades N --days D` a
generated trade book) into one store, **in the layout that store's connector reads at scale**. `drishti.py data load
--store S` runs them with the same options. Keep local runs small (`--trades 10000`).

| Script | Store and layout | Retention |
|---|---|---|
| `tools/load-delta.sh` | Delta Lake under `data/delta/demo` (the demo lake), the pack's layout (`tools/windows/load-delta.ps1` on Windows) | `drishti.py lake maintain` |
| `tools/load-files.sh` | the File connector's JSON Lines, `<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl`, each file replaced whole | delete old day folders |
| `tools/load-postgres.sh` | PostgreSQL, `<domain>.entities` partitioned by month, promoted columns | `--keep-months N` |
| `tools/load-duckdb.sh` | one DuckDB file, written beside and renamed over the old one | `--keep-days N` |
| `tools/load-mongodb.sh` | MongoDB, a collection per domain, zstd, the `day_ids` index | `--keep-days N` or `--ttl-days N` |
| `tools/load-redis.sh` | Redis, a compressed document per entity per day, each day replaced atomically | `--ttl-days N` |
| `tools/load-aerospike.sh` | Aerospike, a set per domain, promoted fields as bins | `--ttl-days N` |
| `tools/load-cassandra.sh` | Apache Cassandra or ScyllaDB, a keyspace per domain, a partition per day per bucket, each day under a new generation (appears whole); `--create` makes the tables (`tools/windows/load-cassandra.ps1` on Windows) | `--ttl-days N` or `--keep-days N` |
| `tools/load-iceberg.sh` | Apache Iceberg tables (a folder or a REST catalog), sorted by id, external sort with `--spill-dir`; `--engine native` writes with drishti-iceberg, no Hadoop (`tools/windows/load-iceberg.ps1` on Windows, native by default) | `--keep-days N` |

```bash
tools/load-duckdb.sh /tmp/demo.duckdb --trades 10000 --days 3
drishti-console/.venv/bin/python tools/drishti.py data load --store files --trades 1000 --days 1 -- /tmp/demo-files
```

**Dates.** The scripts' generators write loader rows as tars of `<kind>/<YYYY-MM-DD>/` folders under `<data folder>/build/loader-rows/`
(removed after the load), and every loader files each row under its folder's date, or a plain file's (`--as-of`, its `.ready` file,
its name, the business today), never the row's own `date`, which it only counts when it differs: the load rules of
[DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo), the same as `lake build`'s.

**Scheduling.** The loaders guard dates (a tar folder dated in the future refuses the archive; a business date after tomorrow is not loaded and the run fails naming it), replace each day
they reach in one step, and refuse to drop more than half a table (`--max-drop-share`, `--force-drop`), so a nightly cron entry
that re-runs is safe; the details are at the top of each script and in [DATA_GUIDE.md section 11](../guides/DATA_GUIDE.md#11-the-demo-data). The connectors.
guides are listed in [DATA_GUIDE.md section 8](../guides/DATA_GUIDE.md#8-connectors).

## 16. Generators: `tools/packgen`, `tools/samplegen`, `tools/realdata`

| Tool | What it makes |
|---|---|
| `tools/packgen/comswap_synth.py` | **The synthetic Endur-like commodity-swap book**: deals with 1 to 4 legs, monthly cash flows, resets, events, packages and novations; upper-case keys in a fixed order, digit-string codes that must stay text, blanks spelt as `""`, `null`, `0` and a missing key. `--mix` makes a whole book (ComSwap, ComFut, Cash, Commodity and a rare CallNot by `PRODUCT_TYPE`). Deterministic (`--seed`); at most 10,000 deals. Every value is invented. |
| `tools/packgen/note_synth.py` | a synthetic structured note of another shape (camelCase keys; note, coupon periods, observations, fixings; a call schedule and a basket of underlyings), to show the pack generator is generic. Every value is invented. |
| `tools/packgen/pack_from_jsonl.py` | the engine of `drishti.py pack new`: a complete pack from a folder of JSON Lines |
| `tools/contracts.py` | the data contract of every command (`drishti.py contract`, [section 14](#contract-data-contracts-and-the-command-catalog)): drafts one from a pack and its data, checks them all, writes `docs/contracts/packs`; the schema `tools/contracts.schema.json` and the product owner's decisions `tools/contracts.policy.yaml` beside it |
| `tools/knowledge.py` | the Knowledge pages' glossaries (`docs/knowledge/`): writes each page's terms and the home's A to Z from `docs/knowledge/glossary.yaml`; `--check` fails when a page differs or the glossary is invalid (`tools/test_knowledge_pages.py`) |
| `tools/packgen/readme.py` | a pack's `README.md` (the README standard, [PACK_DEVELOPER_GUIDE.md Part IV](PACK_DEVELOPER_GUIDE.md#the-readme-standard)) for a pack without a generator of its own; `--all --check` fails on any pack whose README is missing, stale or breaks the standard |
| `tools/packgen/common/guide_data.py` | every pack guide's *Data contracts* and *Data retrieval* sections (from the contracts and `data_retrieval.yaml`; the pack generators call it); run alone it writes compute-demo's guide and the generated block of the news-and-markets guide; `--check` fails when they are stale ([PACK_DEVELOPER_GUIDE.md Part IV](PACK_DEVELOPER_GUIDE.md#the-readme-standard)) |
| `tools/connector_readmes.py` | the settings tables of every connector README (from `plugin-settings.yaml`) and the file tables of the template folders' READMEs; `--check` fails when one is stale ([CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#the-readme-standard)) |
| `tools/packgen/banking/make_data.py`, `make_packs.py`, `make_sutras.py`, `make_docs.py` | the banking packs: their sample data and lake, manifests, Sutras and guides, from one taxonomy |
| `tools/packgen/climate/make.py`, `liquidity/make.py`, `oprisk/make.py`, `retail/make.py` | the climate-risk, liquidity-risk, operational-risk and retail-banking packs, built on the banking data |
| `tools/packgen/genomics/make.py` | the genomics pack (public reference facts, synthetic samples) |
| `tools/packgen/world/make.py` (the halves `economics/make.py`, `politics/make.py`) with `tools/realdata/realdata.py` | the world-economy pack from real public data (World Bank, IMF; internal demos only) |
| `tools/packgen/trades/make_trades.py` | **The trading pack's trades of three source systems** in the common schema (upper-snake, `LEGS` → `CASH_FLOWS` → `RESETS`, `EVENTS`): `--trades N` per system (ENDUR, CALYPSO, MUREX), `--days D` business days with about 6% amended each day, one `<out>/<system>/trade_<system>.<date>.jsonl.gz` a day; with `--lake <root>` each goes through `lake build --connector trade-<system>` into that system's table (`trade_endur`, `trade_calypso`, `trade_murex`). Deterministic; 10,000 trades or fewer on a laptop. `drishti-console/.venv/bin/python tools/packgen/trades/make_trades.py --trades 50 --days 2 --out data/build/trades` prints `ENDUR: 50 trades x 2 days -> data/build/trades/endur` and the same for CALYPSO and MUREX. |
| `tools/packgen/var/make_var_data.py` | **The VaR engine's inputs** ([VAR_GUIDE.md Part II](VAR_GUIDE.md#14-the-contract-at-a-glance)) from the committed trades and desks, as an upstream risk batch delivers them: one 509-scenario P&L vector per live trade (every one: statuses NEW, VALIDATED, AMENDED, not matured; 9,572 trades) with the terms the server derives positions from (a synthetic `SECURITY_ID` shared by several trades for securities and listed products, by the market-risk pack's `positions:` rules), the scenario set, the MGMT and LEGAL hierarchies with limits, and 28 portfolio definitions (a desk's, a legal entity's, rules and baskets, overlapping), written to `data/testdata/market-risk/<kind>/` with their `testdata.yaml` entries, and the pack's samples and Sutra tests. No position, VaR or node history is written: the server derives and computes them. `--budget N` values at most N trades per book (default 0: every live trade), `--out` writes elsewhere, and `--check` fails when the committed files differ. The form is a choice per system: `--pv-systems ENDUR` (the default: Endur's trades as scenario PVs in their own currency, with the scenario FX), `--pnl-systems` (as P&L vectors), `--all-vectors` (every trade a P&L vector: the bank 112,035,906 / 160,132,107). `--position-systems MUREX` sends those systems per position (`GRAIN: POSITION`, naming their trades in `TRADE_IDS`); `--inputs-config` prints the `drishti.var.inputs` pins matching the data. Deterministic; `tools/packgen/var/test_var_data.py` checks the data. |
| `tools/packgen/sensitivity/make_sensitivity_data.py` | **The demo position sensitivities and the factor dictionary** (the planned kinds `position-sensitivity` and `risk-factor`, [SENSITIVITIES_AND_FRTB_SA.md](FRTB_SA_GUIDE.md)) from the VaR factor model, into `data/testdata/market-risk/`; run by `tools/testdata/make_testdata.py` |
| `tools/pvgen/morning.py`, `intraday.py`, `publish_kafka.py`, `check.py` (each with a `.sh` beside it and a PowerShell form: `pvgen-morning.ps1`, `pvgen-intraday.ps1`, `pvgen-publish-kafka.ps1`, `pvgen-check.ps1` in `tools/windows`) | **A stand-in pricing system** ([VAR_GUIDE.md section 15.12](VAR_GUIDE.md#1512-producing-pl-vectors-and-scenario-pvs)): JSONL only, every line one row of its kind's contract. Every record leads with what it is (`VALUE_TYPE`, `GRAIN`, its id, `CURRENCY`, `SOURCE_SYSTEM`); `--grain position` (or `--position-systems SYS`) sends per position instead of per trade. `morning.py` writes a full day's `scenario-pv`, `pnl-vector` and `fx-scenario` (per-system forms as above, `--date`, `--lake`, `--server`); `intraday.py` N small batches of amended, new and cancelled trades (`--batches 20 --trades-per-batch 15`, `--seed`, `--first` for a second run, `--lake`, `--server`, `--every 30s`); `publish_kafka.py` the same to the PV topic, each message JSONL text of `--per-message` records, a cancel a cancel line (`--rate`, `--seconds`; kafka-python); `check.py` recomputes the bank's and the desks' VaR and ES from the files and compares them with the server. Each has `--help` and `--dry-run`; `tools/pvgen/test_pvgen.py` checks them. `drishti-console/.venv/bin/python tools/pvgen/morning.py --dry-run` prints `morning run of 2026-09-30: 1,339 trades as scenario PVs (ENDUR), 8,233 as P&L vectors, scenario FX for 6 currencies; computed at 2026-09-30T06:00:00Z`. |
| `tools/packgen/ccr/make_ccr_data.py` | **The exposure engine's inputs** ([EXPOSURE_GUIDE.md](EXPOSURE_GUIDE.md)) from the committed netting sets, as an upstream exposure simulator delivers them: a simulation run (`simulation-run`: 200 paths, 13 primary dates 1W .. 10Y and their 10-business-day look-back dates) and every live member trade's MtM on every path at every grid date up to its maturity (`exposure-path`) for eight netting sets, written to `data/testdata/counterparty-risk/<kind>/` with their `testdata.yaml` entries, the pack's samples and `drishti-compute`'s parity reference (`ccrmath.py`'s figures). No exposure figure is written. `--check` fails when the committed files differ, `--full --out DIR` writes every netting set that fits in 10,000 trades with 500 paths (local benchmarks), `--demo --out DIR` writes the demo lake's run (whole counterparties within 9,500 trades, 200 paths: 74 netting sets, 8,022 live trades on the data's date; what `lake demo --exposure demo` loads), `--reconcile I/N --stored DIR` compares stored profiles with the computed ones. Deterministic; `tools/packgen/ccr/test_ccr_data.py` checks the data. |
| `tools/testdata/sample_docs.py` | **The data samples** ([docs/contracts/samples/README.md](../contracts/samples/README.md)): one page per data type of `data/testdata` (and per kind a bank or a public source produces that has only pack samples), each with a real record pretty-printed as JSON, long arrays folded, one record per sub-type (a trade's product type, a P&L or PV form, a curve type), every field explained from the kind's data contract, the fields only other rows have, and a checklist for the production query; the index groups them by what the bank produces and lists the kinds Drishti computes. Reads every row of the test data (four worker processes, about 25 s). `--check` fails when a page is stale; `audit` compares every contract with all its committed rows (fields missing from the contract, required fields null, types and enums that differ). `tools/test_sample_docs.py` runs both. |
| `tools/samplegen/bulk_trades.py` | a large trade book for scale tests, N trades over D business days: one JSONL file a day under `data/build/bulk/<system>/`, then each through `lake build` into the system's trade table (`load-delta.sh --trades`; `--no-lake` writes the JSONL only, `--jsonl FILE.tar` loader rows for the other stores: a tar of `trade/<YYYY-MM-DD>/` folders; a plain file or `-` only with `--days 1`) |
| `tools/demonames.py` | the bank's own names in the demo data (`config/demo/names.yaml`): the mapping `lake demo --names` and `build_demo_lakes.py --names` apply as they load, and the pack generators as they write samples ([DATA_GUIDE.md section 11](DATA_GUIDE.md#1114-your-banks-names)) |
| `tools/convertcmd.py` (`drishti.py convert FILE`) | CSV (deprecated input), a JSON array or a folder of per-entity `.json` files into JSON Lines ([section 10](#convert-csv-a-json-array-or-a-folder-of-json-files-into-json-lines)) |
| `tools/samplegen/lake.py`, `tools/samplegen/load_rows.py` | one pack's sample history as dated JSONL loader rows (`<name>-history.tar`, `<name>-restated.tar`), and the loader that puts such rows into Delta ([DATA_GUIDE.md](DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)) |

The examples in this guide read the committed test data (`data/testdata`, written by `tools/testdata/make_testdata.py`). It writes the
same bytes on every Python version and machine: amounts that are sums (a netting set's `NET_MTM`, a counterparty's) are added with
`math.fsum` to the cent, never the built-in `sum()`, whose float result differs at the last bit between Python 3.13 and 3.14
(`tools/testdata/test_deterministic.py`; give `DRISHTI_OTHER_PYTHON=<another python>` to compare two versions), so
`make_testdata.py --check` passes wherever it runs. For a book
of another size, or a JSON array as a booking system exports it (`--array`; without it, JSON Lines), the generators write one:

```
$ drishti-console/.venv/bin/python tools/packgen/comswap_synth.py --deals 2000 --mix --array --out build/tools-walk/book-2026-10-16.json
wrote 2000 synthetic deals to build/tools-walk/book-2026-10-16.json
```

2,000 deals are 27 MB of pretty-printed JSON (about 14 KB a deal); the lake of section 7 holds the 5,169 rows of two days in 7.5 MB
of Parquet.

## 17. Developer tools

| Tool | What it is for |
|---|---|
| `tools/drill.sh` | "Drill it" ([PACK_DEVELOPER_GUIDE.md §2.6](PACK_DEVELOPER_GUIDE.md#26-the-drill-toolsdrillsh)): first a gate of a few minutes (licence headers, the generated documents and data that must be current, the docs and help console tests, the quick file checks (every test marked `static`), and one Java 21 build of the working tree with Javadoc, doclint and Spotless that runs only the rule tests), stopping at its first failure; then at once the console suite in a browser lane (handed out by test on as many workers as the memory available holds, its memory sampled, files over the 300 s per-file budget flagged; [TEST_HARNESS.md](../architecture/TEST_HARNESS.md)) and an other lane (by file), each longest first, the Python tool tests in two lanes (every `tools/**/test_*.py` and `tests/` folder, found rather than listed, one pytest process a file, a failed file listed after all have run), and the Java 21 verify and the Java 25 one, each in a copy of the committed tree; failed console tests run once more file by file (flakes logged, `tools/drill-known.txt` lists accepted failures); a check that the tests left no file under `config/` and wrote nothing to the data folder (a server running from the repository turns the data check into a listed warning); then push develop, merge into main, push main; nothing is pushed unless all pass. `--docs` for a docs-only change, `--no-push` to verify only, `--keep-going` to see every failure, `--gate` for the gate alone. Once per release wave. |
| `tools/drillkit/drill_plan.py` | the drill's console planner: `lanes` (the browser lane holds every file that drives a browser or uses the scratch ports, the other lane the rest; each longest first from `data/drill/durations.json`), `merge` (a run's per-file seconds into the durations), `rerun` (each failed test once more, file by file, under a time limit of twice the file's recorded time and at least ten minutes; every outcome reported; known failures from `tools/drill-known.txt` accepted), `workers` (the browser lane's workers from the memory available, the CPU threads and the scratch ports' limit of 12), `budget` (the browser files of a run over the 300 s per-file budget; `tools/drill-budgets.txt` lists the files allowed over it with their reasons), `static` (the quick file checks the gate runs: the console files with a test marked `static`, or `--kind tools` the tools files marked so) |
| `tools/drillkit/drill_monitor.py` | what a test run holds in memory: `watch` samples, every few seconds, the server JVMs, other JVMs, Chromium processes, consoles and pytest processes (count and resident memory) and the machine's available memory into a CSV (`--under PID` for one lane); `summary` prints the peaks. The drill samples its browser lane into `data/drill/monitor-browser.csv` |
| `tools/drillkit/drill_timings.py` | the drill's pytest plugin (`-p drill_timings`): the seconds each test file took, written to the file `DRILL_TIMINGS_OUT` names when the session ends |
| `tools/bicompute-unit.sh` | a light check of the pivot engine while a drill or a benchmark holds the machine: `javac` and the JUnit launcher at `-Xmx768m`, no Maven, against the jars of the last release installed in `~/.m2` (`DRISHTI_M2_VERSION`) and this checkout's `ErrorCode`; with test class names (`LivePivotTest ScaleSwitchesTest`) it runs them; `--server` also compiles the server's pivot package; its temporary folder is removed on exit. Never instead of the Maven build |
| `tools/drill-known.txt` | the console tests the drill accepts when they fail: one line each, its pytest id, a `|` and the reason; reported at the end of the lane, never hidden |
| `tools/sizing_planner.py` | reads the planner's one file, `drishti-server/src/main/resources/capacity/planner.yaml`, and writes the single-node sizing planner workbook `docs/architecture/Drishti-Sizing-Planner.xlsx` (inputs, results with the settings and JVM arguments, coefficients, explanation: [CLUSTER_GUIDE.md section 9.7](CLUSTER_GUIDE.md#155-the-sizing-planner)), the server's workbook template (`drishti-server/src/main/resources/capacity/template/`, for Admin → Capacity's download) and the shared test vectors (`drishti-server/src/test/resources/capacity/test-vectors.json`); `--check` exits 1 when any of them is not a fresh build; PyYAML |
| `tools/capacitycli.py` | `drishti.py admin capacity`: Admin → Capacity from a terminal, the server's planner evaluated with `sizing_planner.py`'s evaluator (`--live`, `--set Name=value`, `--all`, `--xlsx FILE --inputs plan\|live\|both`): [section 8](#admin-capacity-admin--capacity-from-a-terminal) |
| `tools/domain_strings.py` | the domain boundaries' rule 4 ([DOMAIN_MODULES.md](../architecture/DOMAIN_MODULES.md) section 4): counts the banking names (command words, engine ids, connectors, API and console paths, packages) in the core's code against `tools/domain_strings_allowed.yaml`, which only shrinks; `--write` lowers the list after names moved into the module (a quick file check in the drill: `tools/test_domain_strings.py`) |
| `tools/license_headers.py` | checks (exit `1` naming the files) or, with `--fix`, inserts the copyright header in every source file |
| `tools/elements_sheet.py` | builds the `<drishti-view>` and report stylesheets from the console's CSS (run it when `drishti_bi.css` or the report CSS changes) |
| `tools/render_sheet.py` | builds the client-side renderer's sheet (`static/render/<version>/drishti-render.css`) from the console's CSS and writes its `integrity.json`; `--check` says whether either is stale (CLIENT_RENDERER.md section 13). A released version's folder never changes: a change means a new version folder |
| `tools/make_icons.py` | the console's favicon and app icons from the logo mark |
| `tools/fetch-pyodide.sh` | installs the pinned, checksummed Pyodide runtime Calc serves from its own origin (no CDN) |
| `tools/docs/screenshots.py` | regenerates the guides' screenshots (Playwright, scratch ports); `drishti.py docs shots` runs it |
| `tools/rachana/md_to_yaml.py` | converts Markdown Sutras into YAML Sutras |
| `tools/maps/build_regions.py` | builds the vendored region maps from Natural Earth |
| `tools/packreg/packreg.py` | the signed pack registry behind `pack publish`, `pack keygen` and `pack install` |
| `tools/bench/trades.py` | synthetic trades of the real ENDUR, CALYPSO and MUREX shapes at scale (`--min`/`--max` a day per system, `--days`, `--counterparties`, with netting sets), trades repeat across days and change like a full daily dump (about 5 % amended a day, a few cancelled), loaded day by day through `lake build`; `--parallel` loads a day's tables side by side, `--order random` loads the days in a shuffled order, `--skip`/`--start` resume a run; writes only under `--lake` and `--tmp`; `--dry-run` says the days and counts ([BENCHMARK.md section 2.3](../admin/BENCHMARK.md#23-trades-at-scale)) |
| `tools/bench/users.py` | simultaneous users against a server's API (`--server`, `--token-file`) or a console's pages (`--console`, `--user`, `--password`) for `--seconds`: a mix of single trades, counterparty pages, netting sets and searches, and with `--history-share` that share of histories (30 and 60 days, 3M, DIFF, now and then *known at*; `--equities` adds equities); one JSON line with requests a second, p50/p95/p99 and failures per kind of request, and the failures per 10 s (`failedBuckets`); with `--var-share` that share of VaR questions of the standard mix (60% nodes, 20% the bank minus a desk, 10% 50,000-position baskets, 10% contributions and P&L paths) on a `vargen.py` lake; `--server` may name several servers (user n asks server n mod k) |
| `tools/bench/vargen.py` | synthetic VaR inputs at benchmark size (`--positions`, `--portfolios`, `--dates`): P&L vectors of 509 scenarios from a 40-factor model, the scenario set, the MGMT and LEGAL hierarchies, HIERARCHY, RULE and BASKET portfolios (`--big-baskets` of `--basket-size` positions) and limits, as JSON Lines outside the repository ([BENCHMARK.md section 4](../admin/BENCHMARK.md#4-the-200000-position-var-and-cluster-run)) |
| `tools/bench/var_bench.py` | the VaR benchmark with profiling: `load` times each kind into a lake with the Python path or the Java loader (peak memory of the loader's process tree); `run` starts one server or a three-node sharded cluster (`--mode`, `--java 21/25`, `--gc g1/zgc`, ports from 19480) with JFR, GC logs and NMT, takes heap histograms after a full GC at start, with one and two dates open, warm and at the end, times each date's first open under its own recording, measures log bytes at rest and under load, runs `users.py` at each `--users` level and records swap activity; one JSON line per measure ([BENCHMARK.md section 4](../admin/BENCHMARK.md#4-the-200000-position-var-and-cluster-run)) |
| `tools/bench/jfr_summary.py` | what a benchmark's JFR recordings say: `stages` splits a VaR date's first open into read, intake, sort, aggregate and write from its CPU samples (the lake read's own threads count as read); `views` writes the JDK's views (allocation by class and site, survivors, hot methods, GC pauses and CPU, pinned threads, contention) and counts pinned events and GC pauses |
| `tools/bench/pivot_bench.py` | the pivot engine's benchmark ([BENCHMARK.md section 16](../admin/BENCHMARK.md#16-the-pivot-engine-at-scale)): `plan` (the run matrix and the disk it needs), `generate --data-dir D --gb N` (synthetic 40-column trades as JSON Lines loaded into Delta day by day; the lake grows by appending days; refuses /tmp, a tmpfs, the repository and a disk under 150 GB free), `run` (V1 to V5 against one server or a cluster, cold or warm, with or without a rollup; time to the first ≈ and the exact answer, the estimate against the reading; one JSON line each), `report` (the measured tables and 100 GB, 1 TB and 3 TB EXTRAPOLATED, labelled so), `clean`, `all` (5, 25 and 75 GB, every configuration, the data deleted at the end unless `--keep`); `test_pivot_bench.py` runs it small with the tool tests |
| `tools/bench/charts.py` | the charts of [BENCHMARK.md](../admin/BENCHMARK.md#4-the-200000-position-var-and-cluster-run), drawn from the VaR run's results files (`var_bench.py`'s JSON lines) as plain SVG into `docs/admin/images/benchmark/` (`--to` another folder): requests a second by users, p99 by request type, a first open by stage, heap against the sizing formulas, GC pauses, the hottest CPU frames, disk and load time; transparent background, readable on light and dark pages; only measured cells are drawn; standard library only, rerun after every run |
| `tools/bench/net_soak.py` (with `tools/bench/soak_check.py`) | the Drishti\*net soak on real processes: three servers (each with only the packs and connectors its lake holds, the others off by connector file), a console, `users.py` load, a shard and the seed killed and restarted, every answer (views, searches, about 140 distinct histories with ten ranges as administrator and as viewer, whose `PRICE` or `NAME` must read masked on every row and in every diff) checked against one server holding all the data; samples memory, heap, open files and threads every minute (the console with all its worker processes, summed and one by one) and ends with a verdict (no wrong answers: each one kept with its time, request and the rule that failed, the first 200 with `partial`, `problems` and a compact diff; errors only in kill windows, p95/p99 per action, memory flat after warm-up: the slope in the steady spans outside the kill windows and `--settle` seconds after each, the whole-run slope reported beside it), exit `0` only when it passes ([CLUSTER_GUIDE.md › Drishti*net: many servers, one Drishti, section 17.1](../admin/BENCHMARK.md#215-drishtinet)) |
| `tools/bench/scale.sh`, `tools/bench/pinning.sh` | the scale benchmark per store and size, and the virtual-thread pinning comparison (Java 21 / 25); they start and stop their own servers by process id |
| `tools/windows/start-server.ps1`, `tools/windows/start-console.ps1`, `tools/windows/load-delta.ps1` | starting the server and console, and loading the demo lake, on Windows |
| `tools/rolling-upgrade.sh` (`tools/rolling.py`; Windows `tools/windows/rolling-upgrade.ps1`) | the rolling upgrade runner (CD3): a cluster upgraded one node at a time from an inventory (`deploy/rolling.example.yaml`), consoles, BI and data-serving, coordinators, then owners zone by zone; each drained, waited for until safe to stop, stopped, upgraded, started and waited for until ready, on VMs or ECS; `--dry-run`, `--from`, `--only` ([CLUSTER_GUIDE.md section 10.1](CLUSTER_GUIDE.md#82-rolling-upgrades)) |
| `deploy/bin/drishti-node.sh` (`deploy/bin/drishti-node.ps1`) | one server's drain before a stop and its readiness (CD1, CD2): `drain [--wait S]`, `undrain`, `status`, `wait-ready`, and `run COMMAND` (the server images' entry: drains on SIGTERM, then passes it on); the systemd unit's `ExecStop` ([OPERATIONS_GUIDE.md section 7.2](OPERATIONS_GUIDE.md#53-running-as-services-systemd)) |
| `tools/feeds/feed.sh`, `tools/windows/feed.ps1` | the one wrapper of every data feed on Linux and macOS, and on Windows: `drishti.py feed ...` with the console's Python (what cron, systemd and Task Scheduler run; [EXTERNAL_DATA.md](EXTERNAL_DATA.md)) |
| `tools/smoke.py` | the engine of `drishti.py server smoke` |
| `tools/javacmd.py` | `java_cmd()`: the one helper every Python launcher of a JVM uses, so each java it starts gets `-Djava.io.tmpdir=` (native libraries unpack in Drishti's own temp folder, not `/tmp`); a guard test fails on a tool that starts java without it ([CONFIGURATION.md](../admin/CONFIGURATION.md)) |
| `tools/settings_catalogue.py`, `tools/settings_sources.py` | the one configuration document: `settings_catalogue.py` writes every setting of every kind into [CONFIGURATION.md](../admin/CONFIGURATION.md) (and the console's Help → Configuration) from the files themselves, with `tools/settings_schemas.yaml` for the content files; `--check` (in the drill) fails when the document is not current or a key has no meaning; `settings_sources.py` holds its readers (a commented YAML's keys and comments, the connector settings catalogue, the shared `tls.*` and `proxy.*` specs) |
| `tools/under_construction.py` | the under-construction registry's document: writes [UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md) from `config/under-construction.yaml` (what depends on unbuilt work, where it shows, the release that builds it, hidden or *Under construction*, the screenshots to retake); `--check` fails when the document is stale or a place it names does not exist |
| `tools/docs/guide_data.py` (with `tools/docs/guide_data.yaml`) | the guides' data sections: per guide (VaR, exposure, FRTB-SA, SIMM, the market-risk cube, funds, groupings and analytics, pivot) every dataset the feature reads, what it is for (written in the spec), whether it is required, its sample files under `data/testdata` (a `.jsonl.gz`, or a tar of `<table>/<date>/*.jsonl.gz`) and one real record, formatted (a scalar array over 9 shows its first 4, `/* … N more … */` and its last 4; an array of objects its first 2), with the kind's key and field counts from its data contract. Written between `<!-- guide-data:begin <id> -->` and `<!-- guide-data:end -->`; text outside the markers is never touched. The record is deterministic: the last file of the glob in sort order (`pick: first` for the first), the first tar member, the first record or the first matching `where`. A dataset without a committed sample says *no sample in the demo data*. `--check` (a static test, so in the drill's gate checks) fails when a section is stale, a shown field is not in the kind's contract, the record lacks the contract's key, or a link names a heading the guide does not have. To change a section, edit the spec and run `python3 tools/docs/guide_data.py` |
| `tools/docs/code_volume.py` | the README's *By the numbers* section from the files git tracks: lines and files by language, rounded, and what the product holds (Maven and domain modules, lenses, functions, console routes, server REST handlers, Elements libraries and their ES modules, packs by domain, Sutras, connector plugins, feeds, lake files, test files by kind, documents), counted, dated; `--check` fails when a line count is more than 10% off (the drill's gate); `--compare <tag>` prints the console's screen code against the Elements libraries' at a tag and now; run in each release's preparation |
| `tools/api_docs.py` (with `tools/api_docs.yaml`) | the Java and Python API references **Help → Developer** serves, generated at build time and never committed (see [API references](#api-references) below): `check-java`, `check-python` and `check` (in the drill), `build [--maven] [--out DIR]`, `zip [--to FILE]` |

```
$ python3 tools/license_headers.py
$ echo $?
0
```

A two-hour soak, the shard killed at 20 minutes and the seed at 60 (on a workstation with no other heavy job running; nothing is written
outside `--work`):

```bash
python3 tools/bench/net_soak.py --work ~/drishti-scale-test/net-2h --users 20 --seconds 7200 --days 10 \
    --kill-shard-at 1200 --restart-shard-at 1500 --kill-seed-at 3600 --restart-seed-at 3900 \
    --history-share 0.15 --sample-every 60 --warmup 600 --max-growth 64
```

It prints the latency and error table (one line per action, `history` its own) and the verdict, and keeps them in
`<work>/soak-report.txt` beside the full `<work>/soak.json` (the per-minute samples are its `resources`).

### `docs shots`: screenshots

`docs shots` regenerates the screenshots of the guides (Playwright, against a scratch server and console on their own ports,
never the usual ones): `--list` shows the pictures, `--guide NAME` and `--only FRAGMENT` narrow it.

### API references

The Java and Python API references are built from the code and served by the console at **Help → Developer** (`/help/developer`),
beside the server's REST API (OpenAPI) and the renderer's TypeScript API. `tools/api_docs.yaml` lists what is documented: the Java modules
`drishti-api` (the plugin and connector SPI), `drishti-compute` (the shard machinery) and `drishti-server` (`ShardedMeasure`), each naming
its packages in its `pom.xml` (`drishti.apidocs.packages`), and the Python surfaces (the compute worker SDK, BI Python items, `drishti.py`
and its client library, the pack and test-data generator helpers).

```bash
./mvnw -o -P api-docs -pl drishti-api,drishti-compute,drishti-server -am process-classes   # Javadoc with doclint, into <module>/target/reports/apidocs
python3 tools/api_docs.py check            # every public Java member and Python function, class and method documented (exit 1 naming them)
python3 tools/api_docs.py build --maven    # both references into drishti-console/web/apidocs/<version>/ (the Maven step included)
python3 tools/api_docs.py zip              # drishti-console/web/apidocs/drishti-api-reference-<version>.zip, for the GitHub release
```

* **Doclint**: the `api-docs` profile runs Javadoc right after a documented module compiles, with `-Xdoclint:all,-missing` and warnings
  failing the build, so a broken `{@link}`, bad HTML or a malformed tag stops it there. It adds a few seconds to a build. The drill's
  Java 21 verify runs with the profile.
* **Undocumented members**: `check-java` reads the built Javadoc and names each public or protected type, member or package without a
  comment; `check-python` reads each Python module without importing it and names each public function, class or method without a
  docstring or a type hint on a parameter or the return (a module's `__all__` says what is public).
* **Output**: one folder per version under `help.api_docs_dir` (default `drishti-console/web/apidocs`, never committed), with `index.html`,
  `manifest.json`, `java/<module>/` and `python/`; self-contained (scripts, styles and the search index are files beside the pages). pdoc
  runs from the interpreter when it has it, else through `uv` at the version `tools/api_docs.yaml` pins.

### Where each command lives

`tools/drishti.py` is one program; its groups are in [section 1](#1-what-it-is). The command tree (every group, command and option) is built in `tools/drishti_parser.py`, the commands
themselves in `tools/drishti.py` and the group modules it registers (`datacli.py`, `pivotfncli.py`, ...), each file well under the
1,500-line cap.

The group modules live beside it and are not run on their own: `tools/authoring.py` (`data profile`, `pack regenerate|diff|catalogue|i18n`),
`tools/dataprofile.py`, `tools/packmake.py`, `tools/packregen.py`, `tools/packdiff.py`, `tools/packcatalogue.py`, `tools/packi18n.py`,
`tools/packform.py` (the `form:` section a pack check reads), `tools/schemapack.py` (`--schema`), `tools/sutragen.py` (`sutra gen`),
`tools/ingest_jsonl.py` and `tools/ingest_watch.py` (`data ingest`), `tools/packbundle.py` (`pack bundle|verify|deploy|rollback`),
`tools/serverpacks.py`, `tools/serverconnectors.py`, `tools/servernet.py` (`net`), `tools/hostaddr.py` (this machine's real address for Drishti*net members), `tools/build_demo_lakes.py` (every demo lake start to finish: `lake demo` over all sixty days, then your own ENDUR files one per date, `--store calypso=iceberg` for a source system in Iceberg, `--dry-run` to look first; the Windows QA guide's step 3.0), `tools/smoke.py`, `tools/doctor.py`, `tools/viewcmd.py`, `tools/completion.py`,
`tools/approvalscli.py` (`approvals`), `tools/bilang.py`, `tools/bimodel.py`, `tools/biitem.py`, `tools/bistatement.py` (`bi statement preview|file`), `tools/historycmd.py` (`history`), `tools/varcli.py` (`var`), `tools/datacli.py` (`data get`), `tools/expcli.py` (`exposure`), and `tools/lake/cli.py` with `tools/lake/build.py`, `tools/lake/scan.py`,
`tools/lake/jsonstream.py`, `tools/lake/stage.py`, `tools/lake/writers.py`, `tools/lake/icebergwrite.py` (`lake build --format iceberg`, section 7.5), `tools/lake/demo.py` (`lake demo`), `tools/lake/relocate.py` (`lake relocate`), `tools/lake/undo.py` (`lake undo`), `tools/lake/filedate.py` (a file name's table and business date, the Python half of drishti-common's `FileDates`), `tools/lake/feedroute.py` (a feed file paired with its `.ready` file and routed to a pack and kind), `tools/lake/feedfiles.py` (our own generators' dated feed files, by the load rules), `tools/lake/memory_cli.py` (`lake memory status|jobs|refill`), `tools/enginescli.py` (`server engines list|show|on|off|history`), `tools/dataloadcli.py` (`dataload status|run|retry`), `tools/lake/memory_caps.py` (the launchers' direct-memory limit from the stores' caps), `tools/lake/stores.py` (a source system's trades copied into Iceberg, its connector file switched; `build_demo_lakes.py --store`) and `tools/lake/laketables.py` (the tables `lake status` lists). `tools/connector_upgrade_fixture.py` is not a `drishti.py` group: it writes the connector files an older release's server generated, the fixture of the connector upgrade drill (`ConnectorUpgradeTest`, [DATA_GUIDE.md section 9](../guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors) "Upgrades").

### Running the tools from PyCharm (and the Java `sutra` from IntelliJ)

The tools are ordinary Python scripts, so PyCharm runs and debugs them like any other, and the Java `sutra` tool runs from IntelliJ
IDEA. (For the server and the console themselves see [OPERATIONS_GUIDE.md (IDE)](OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm).) This section is the whole path, from an
empty PyCharm to a run configuration for each common task.

#### Create the interpreter

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

#### A run configuration for each task

**Run → Edit Configurations → + → Python**. The fields are the same for every task; only **Name** and **Parameters** change:

| Field | Value |
|---|---|
| **Script path** (not *Module name*; switch the toggle on the left of the field) | `<repo>/tools/drishti.py` |
| **Python interpreter** | the `drishti-console/.venv` interpreter made above |
| **Working directory** | **the repository root**: relative paths in *Parameters* resolve from here |
| **Environment variables** | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64;DRISHTI_JAR=<repo>/drishti-server/target/drishti-server-<version>-exec.jar;PYTHONUNBUFFERED=1`, and for server commands `DRISHTI_SERVER=http://localhost:18480;DRISHTI_TOKEN=drk_...` |

Click the folder icon next to **Environment variables** to edit them as a table (one row per variable). `JAVA_HOME` and
`DRISHTI_JAR` may be left out: the tool then looks for JDK 21 and for the newest `drishti-server/target/*-exec.jar` itself.

| Name | **Parameters** |
|---|---|
| `drishti pack make` | `pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force` |
| `drishti data profile` | `data profile data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz` |
| `drishti pack regenerate` | `pack regenerate data/build/my-bank/pack/my-bank data/testdata/trades/endur/trade_endur.2026-09-23.jsonl.gz --dry-run` |
| `drishti sutra gen` | `sutra gen data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --out data/build/sutras` |
| `drishti data ingest` | `data ingest --from your-new-days/ --pack data/build/my-bank/pack/my-bank --lake data/build/my-bank/data/delta --dry-run` (remove `--dry-run` to write) |
| `drishti pack check` | `pack check data/build/my-bank/pack/my-bank --strict --junit data/build/reports` |
| `drishti server packs load` | `server packs load my-bank --server http://localhost:18480` with `DRISHTI_TOKEN` set |
| `drishti sutra lint` | `sutra lint config/packs/my-bank` |
| `drishti server health` | `server health` |

Press **Run** (Shift+F10): the output is in the Run window, and a non-zero exit code shows as a failed run (exit 1 = a check
failed, 2 = usage; see [Exit codes](#exit-codes)). If the Run window says `no drishti-server-*-exec.jar`, build it once
(`./mvnw -q -DskipTests package`) or set `DRISHTI_JAR`.

**Templates.** **Run → Edit Configurations → Edit configuration templates... → Python** sets the defaults for every *new* Python
configuration: put the interpreter, the working directory (`$PROJECT_DIR$/..`, the repository root), and the environment variables there once, and each new
configuration (**+ → Python**) then only needs a **Name**, the **Script path** and the **Parameters**. Duplicate an existing
configuration (Ctrl+D in the dialog) to make a variant.

**Secrets.** `DRISHTI_TOKEN` goes in the configuration's **Environment variables** (kept in `.idea/workspace.xml`, which is not committed),
or use `--token-file ~/.drishti-token` in **Parameters**. Never put a token in a shared configuration.

#### Debug a tool

The tools are plain Python, so debugging is PyCharm's normal debugging: open `tools/drishti.py` (or `tools/packmake.py`,
`tools/sutragen.py`, `tools/ingest_jsonl.py`, `tools/packgen/pack_from_jsonl.py`, `tools/packbundle.py`), click the gutter left of a
line to set a breakpoint, and press **Debug** (Shift+F9) on the run configuration instead of Run. Good first breakpoints:

- `pack make`: `detect_key` and `detect_date` in `tools/packmake.py` (why a field was chosen), and `make` (the whole flow);
- `sutra gen`: `generate` in `tools/sutragen.py` (the groups, one per `--match` value);
- `data ingest`: `ingest` in `tools/ingest_jsonl.py` (the dates and rows it will write).

The Java `sutra` command that `pack check` and `pack make` start is a separate process: breakpoints in Python do not stop it
(use the IntelliJ configuration below for that). **Variables** shows the parsed options (`a`), and **Evaluate expression** (Alt+F8) works on them.
To stop on an error, tick **Run → View Breakpoints → Python Exception Breakpoints → Any exception**.

#### Share the configurations with the team: `drishti-console/run-configs/`

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
    <option name="PARAMETERS" value="pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force" />
    <method v="2" />
  </configuration>
</component>
```

(the real files list every option PyCharm writes). `$PROJECT_DIR$` is `drishti-console/` (PyCharm's project), so `$PROJECT_DIR$/..` is the repository root on every machine; an empty `SDK_HOME` with `IS_MODULE_SDK`
false is PyCharm's *Project Default* interpreter, so nobody's local path is in the file and the same file works on Windows
(`tools/test_run_configs.py` guards it); the `module` name does not decide the interpreter, so a clone in a folder of another name works too. Put only what is shared in them: no tokens, no home-directory
paths. Edit **Parameters** for your own input folder, or duplicate the configuration for each data set.

#### The Java `sutra` tool from IntelliJ IDEA

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

#### Run the tools' own tests

**Run → Edit Configurations → + → Python tests → Unittest**: *Target* `Script path`, the file `tools/test_packmake.py` (or
`test_drishti_cli.py`, `test_sutragen.py`, `test_ingest_jsonl.py`, `test_pack_bundle.py`), working directory the repository root,
environment `DRISHTI_JAR=...` (the tests that run `sutra lint` are skipped without a jar).

#### Where next

[OPERATIONS_GUIDE.md (IDE)](OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm) runs the server and the console; [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md) and
[PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md) are the references for what these commands produce and check.

## 18. Recipes

### 18.1 JSONL folder or file to a pack, a lake or files, a running server and a live view

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

With a **Delta lake** instead: `--lake data/delta/demo` in step 1 (the server's `DRISHTI_DELTA_ROOT`, default `<data>/delta`).

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

**Three ways a pack becomes known** to a server (the third is [18.4](#184-registry-publish-and-install)): start with `DRISHTI_PACKS=...,curves-demo` and a
restart, or put it in the watched packs folder as above; `server packs load curves-demo` (or **Admin → Packs → Load**; it takes effect at
once, with no restart, and is remembered in `data/packs/added.yaml`); install from a registry. See
[PACK_DEVELOPER_GUIDE.md#loading-a-pack-while-the-server-runs-1](PACK_DEVELOPER_GUIDE.md#loading-a-pack-while-the-server-runs-1).

### 18.2 A CI pipeline: lint, test, JUnit

```bash
set -e
python3 tools/drishti.py pack check config/packs/my-bank --strict --junit data/build/reports  # exit 1 fails the job
python3 tools/drishti.py pack check config/packs/my-bank --json > data/build/pack-check.json  # for a dashboard
```

A GitHub Actions job (the jar is built once and reused):

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: 21 }
- run: ./mvnw -q -DskipTests -pl drishti-app -am package
- uses: astral-sh/setup-uv@v5
- run: uv run --with pyyaml python tools/drishti.py pack check config/packs/my-bank --strict --junit build/reports
- uses: actions/upload-artifact@v4
  if: always()
  with: { name: pack-reports, path: build/reports }
```

`--strict` also makes a field that is shown but unexplained a failure, so help text cannot rot. `build/reports/<pack>-test.xml`
is a standard JUnit file: one test case per Sutra and sample.

### 18.3 GitOps: create, propose, approve

A design is made and checked by anyone, reviewed by an approver, and only then live. From a pipeline or a terminal:

```bash
# the author (a role with `author`, on a server with DRISHTI_STUDIO_SAVE=true)
python3 tools/drishti.py design create --name trade-swap --from-sutra sutras/trade/trade-swap.v1.sutra.yaml \
    --samples tests/trade-swap --json | jq -r .id > /tmp/design-id
python3 tools/drishti.py design check "$(cat /tmp/design-id)"                       # exit 1 stops the pipeline
python3 tools/drishti.py design propose "$(cat /tmp/design-id)" --note "swap strip: add MTM"

# the approver (a role with `approve`; with four-eyes on, not the author)
python3 tools/drishti.py design proposals --status pending
python3 tools/drishti.py approvals approve sutra P-000042 --comment "checked against 5 samples"
```

With review off (`drishti.governance.enabled: false`) `design propose` saves the Sutra live straight away.

### 18.4 Registry: publish and install

```bash
python3 tools/drishti.py pack keygen --out keys/me.pem                      # once; keep the private key secret
python3 tools/drishti.py pack publish config/packs/my-bank --registry /srv/registry --key keys/me.pem --publisher me
# the server trusts the publisher (drishti.packs.registry.trusted-keys.me = the public key keygen printed), then:
python3 tools/drishti.py pack install --list
python3 tools/drishti.py pack install my-bank 1.0.0
```

### 18.5 Demo data at sizes

```bash
python3 tools/drishti.py data load --store files                                   # the samples, 10 days: seconds
python3 tools/drishti.py data load --store delta --trades 10000 --days 3           # a realistic local demo
```

Larger books (hundreds of thousands to a million trades a day) are for a machine sized for them: see
[DATA_GUIDE.md section 11](DATA_GUIDE.md#11-the-demo-data) for the sizes and what each store needs.

## 19. JSON output, exit codes and troubleshooting

### JSON output formats

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

### Exit codes

| Code | Meaning | Examples |
|---|---|---|
| **0** | ok | a clean lint; a design that checks; a server whose health is `OK`; a `connector apply` that was saved |
| **1** | problems found | a failing lint or test; `--strict` warnings; an unexplained field (`about-check`); a design with a failing sample; the server refused (401, 403, 404, 400...); the server cannot be reached; `server health` not `OK`; `server smoke` with a failing view or pack; `doctor` with a red line; `connector test` that cannot reach the source; `server packs datasource get` while a connector is not configured; a `connector` change refused (`DRS-5031`, `5032`, `5033`); `data ingest --watch --once` with a failed file |
| **2** | usage | a missing or unknown option or file (a `view` reference that is not KIND/ID); the exec jar not found; PyYAML or `deltalake` not installed; `--load` without `--server`; a pack folder without `pack.yaml` |

The Java `sutra` commands keep their own 0/1/2 and pass them through.

Some commands add codes of their own, each named in its section:

| Command | Adds |
|---|---|
| `lake build`, `lake publish`, the parallel loader | `2` written but with rejects (or the data-load signal failed); `3` another run holds the table's or the lake's lock (`lake publish`: nothing to publish for the dates asked); [7.7](#77-exit-codes-the-lock-the-log-and-the-summary) |
| `dataload` | `3` the inbox is off on every server |
| `feed run` | `2` refused, `3` locked, `4` partial |
| `bi runs wait` | `3` failed, `4` cancelled, `5` the `--timeout` passed; `bi runs submit` `2` on a large question without `--yes` |
| `pack make` | `1` made but a check failed (the folder is still written) |
| `play frtb --recipe`, `play simm --recipe` | `1` when the recipe does not reproduce to the cent |

### Troubleshooting

| You see | Why | Do |
|---|---|---|
| `drishti: no drishti-server-*-exec.jar under drishti-server/target: build it ... or pass --jar / DRISHTI_JAR` | the Java commands need the exec jar | `./mvnw -q -DskipTests package`, or `export DRISHTI_JAR=/path/to/drishti-server-<version>-exec.jar` |
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
