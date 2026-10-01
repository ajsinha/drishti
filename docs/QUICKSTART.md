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
# Quickstart

Ten minutes from a fresh clone to your first live view, with only the commands you need. It skips the
explanations. For a slower walk through the same steps, read [GETTING_STARTED.md](GETTING_STARTED.md).

In these docs, `<GO>` means "press Enter".

## 1. Check the prerequisites

| Tool | Version | Check with | You should see |
|---|---|---|---|
| OpenJDK | **21** (the build refuses any other) | `java -version` | `openjdk version "21.…` |
| Python | 3.11 or newer | `python3 --version` | `Python 3.11` or later |
| uv | any recent | `uv --version` | `uv 0.…` |
| git | any | `git --version` | `git version …` |

You do not need Maven: the repository has its own (`./mvnw`). Point `JAVA_HOME` at Java 21 in **every**
terminal you use (this is the Ubuntu path; adjust it for your system):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## 2. Build

Run everything from the repository root.

```bash
git clone https://github.com/ajsinha/drishti.git
cd drishti
./mvnw -q package -DskipTests
ls drishti-server/target/*-exec.jar
```

You should see `drishti-server/target/drishti-server-1.12.0-exec.jar`. The first build downloads its
libraries and takes a few minutes.

If the build stops with `Drishti builds and runs on OpenJDK 21.`, `JAVA_HOME` is not Java 21.

## 3. Set up the console

```bash
uv venv console/.venv
uv pip install --python console/.venv/bin/python -r console/requirements.txt
```

You should see uv list what it installed (FastAPI, Uvicorn, Jinja2, httpx, PyYAML, Markdown, pytest).

## 4. (Optional) Build the sample history

Live views work without this. Past business dates and **Compare** need a small Delta Lake under
`data/delta/` (ten business days ending 30 September 2026); the same command writes the sample feed file
`data/feeds/fixing/SOFR-HISTORY.csv`. Nothing under `data/` is in git: it is always generated.

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta
```

## 5. Start the server

Terminal 1, from the repository root:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics
java -jar drishti-server/target/drishti-server-1.12.0-exec.jar
```

| Variable | What it does | Default |
|---|---|---|
| `DRISHTI_PACKS` | The domain packs to load. A pack's parents load with it: `market-risk` brings `banking-core`, `market-data` and `trading`. | `finance` |
| `DRISHTI_PORT` | The server's port | `18480` |
| `DRISHTI_STUDIO_SAVE` | `true` lets Sutra Studio save layouts | `false` |

When the log says `Started DrishtiApplication`, check it from another terminal:

```bash
curl -s localhost:18480/actuator/health
curl -s localhost:18480/api/v1/packs | python3 -c 'import json,sys; print([p["name"] for p in json.load(sys.stdin)])'
```

You should see `{"status":"UP","groups":["liveness","readiness"]}`, then twelve pack names starting with
`'banking-core', 'market-data', 'trading'`.

## 6. Start the console

Terminal 2, from the repository root:

```bash
console/.venv/bin/python console/run_drishti_web.py
```

You should see `Uvicorn running on http://127.0.0.1:17480`. The console finds the server at
`http://127.0.0.1:18480` (`DRISHTI_BACKEND_URL` changes that; `DRISHTI_CONSOLE_PORT` changes its own port).

Open **http://localhost:17480/t** in your browser. Sign-in is off out of the box, so you are the development
user *Ash* with every role.

## 7. Your first eight commands

Click the command line at the top (or press `/`), type each command and press Enter.

| # | Type | You should see |
|---|---|---|
| 1 | `TRD MX-20000001` | An interest rate swap: the title `Rates · Interest rate swap (fixed/float)`, `MX-20000001`, `with Meridian Reinsurance Ltd`; a strip with Notional `AUD 242,000,000` and a highlighted **MTM (USD)** that ticks and flashes; panels *Terms*, *Legs*, *Cashflows*, a curve chart, *DV01 by bucket*, *Daily P&L*, *Linked entities*. |
| 2 | `F9`, then `F7`, then `Alt+←` | `F9` opens the raw JSON (source `murex-rates`). `F7` jumps to the netting set `NS-MERIDIAN-RE-NY`. `Alt+←` comes back. The bottom bar lists every key the view has: `F2 Terms`, `F3 Cashflows`, `F4 Interest rate`, `F7 Netting set`, `F8 Impact`, `F9 Raw JSON`. |
| 3 | `F8` (on the trade) | *Impact of MX-20000001*: level 1 the netting set `NS-MERIDIAN-RE-NY`, level 2 the credit limit `LIM-MERIDIAN-RE`, each with the amount at stake. |
| 4 | `TRD MX-200000` | No trade has that exact id, so you get a **pick list**: `99 of 99 trades match`, with each trade's product type, direction, currency, notional, MTM, maturity and book. Use `↑` `↓` and Enter, or click an id. |
| 5 | `TRD productType=Revolver` | A pick list by field value: `6 of 750 trades match`. Case never matters (`trd producttype=revolver` works too). |
| 6 | `CPTY north` | Exactly one counterparty's name contains "north", so it opens at once: `CP-NORTHBRIDGE`, Northbridge Capital LLP, with Rating `BB+` and its PFE peak. |
| 7 | `TRD where mtm > 1m order by mtm desc limit 20` | A search: `163 of 750 trades match; the first 20 are shown`, largest MTM first. |
| 8 | `NSET NS-SUMMIT-NY` | A netting set: Trades `108`, Utilisation `58%`, an exposure profile (`F2`) and the member trades (`F3`). |

While you type, the dropdown suggests mnemonics and ids: type `TRD MX-2000010` and you should see `MX-20000100`,
`MX-20000101`, … Press `↓` to pick one, `Tab` to complete it, Enter to open it.

## 8. Look at a past business date

This needs step 4.

1. Open `TRD MX-20000001`.
2. In the top bar, click the **Business date** box (it shows today's date and a green **Live**) and pick
   **29 September 2026**.
3. The box turns amber and **Live** goes out. The MTM is that day's value and no longer ticks.
4. Click **Compare** in the title line. You should see *What changed in MX-20000001* against the business day
   before, field by field.
5. Click **Live** to return to today.

Without the sample history, the view says the source is not a dated source and there is no **Compare**.

## 9. Try a workspace (optional)

**Workspaces** in the top bar (`/w`) puts up to four live views on one screen, and a pane can follow
another. The starter workspaces come from the `finance` and `logistics` packs: restart the server with
`DRISHTI_PACKS=finance`, open `/w`, and click **Credit desk** under *Starters*. The
[workspaces guide](../console/web/guides/workspaces.md) walks through it.

## If something goes wrong

| You see | Do this |
|---|---|
| `Port 18480 was already in use` | Another server is running. Stop it, or start this one with `DRISHTI_PORT=18481` and the console with `DRISHTI_BACKEND_URL=http://127.0.0.1:18481`. |
| `DRS-5003 backend unreachable` in the console | Start the server first; check `curl -s localhost:18480/actuator/health`. |
| `DRS-4001 cannot read command …` | The mnemonic is unknown. Is its pack loaded (`/api/v1/packs`)? |
| `DRS-5002 ash may not open gene entities` | The kind's pack is not among the packs you chose. Click the box icon in the top bar, tick the pack, and click **Apply**. |
| `DRS-1001 no source holds trade/…` | The id does not exist. Type part of it and pick from the suggestions. |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Where next

| If you want to… | Read |
|---|---|
| The same setup, step by step, with sign-in, alerts and monitors | [GETTING_STARTED.md](GETTING_STARTED.md) |
| Every console feature, with examples | [USER_GUIDE.md](USER_GUIDE.md) |
| The packs and the commands each one adds | [PACKS.md](PACKS.md) |
| Change how a screen looks (Sutras) | [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) and the in-app *Sutra guide* |
| Build Drishti from source, run the tests, add an endpoint, plugin, pack or page | [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) |
| Connect your own data | [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) and [CONFIGURATION.md](CONFIGURATION.md) |
| Turn on sign-in and run it for others | [USER_MANAGEMENT.md](USER_MANAGEMENT.md) and [OPERATIONS.md](OPERATIONS.md) |
| See every document | [the documentation map](README.md) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
