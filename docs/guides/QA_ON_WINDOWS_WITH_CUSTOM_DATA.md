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
# How to QA in Windows Environment using Custom Data

A step-by-step recipe for a demo on a **Windows** machine **without internet access** (pip and Maven work through your
company's internal mirror). You will show:

1. **ENDUR trades from your real JSONL files** (one or more files, one per business date).
2. **CALYPSO and MUREX trades**, synthetic, from this repository.
3. **Supporting data:** curves, counterparties, netting sets, agreements and CSAs, collateral, FX, fixings, risk.
4. **Equity prices** and **news**: a snapshot captured from Yahoo Finance and Google News, committed here and served
   offline (no website is contacted).
5. **World economy** data, committed here.

Every step says what to click and what you should see. Do the steps **in order**, the day before the demo.

> **⚠ Your real ENDUR files are confidential and this repository is public.**
> Keep them **outside** the project folder, in `C:\Users\asinh09\drishti-private\endur\`. Never copy them into
> `drishti-orig`, never commit them. Drishti reads them from where they are.

Names used below:

| Name | Value |
|---|---|
| Project folder | `C:\Users\asinh09\IdeaProjects\drishti-orig` |
| Your ENDUR files | `C:\Users\asinh09\drishti-private\endur\` (for example `2026-10-15.jsonl`, `2026-10-16.jsonl`) |
| The data folder | `C:\Users\asinh09\IdeaProjects\drishti-orig\data` (the project's own `data` folder) |
| Server | `http://localhost:18480` |
| Console (the browser address) | `http://localhost:17480` |
| Sign-in | user `drishti-dev-admin`, password `drishti-dev-admin123` |

---

## 0. What you need on the machine

| Item | Check |
|---|---|
| **JDK 21** (or newer) | PowerShell: `java -version` shows `21` or higher |
| **Python 3.12 or 3.13** | PowerShell: `py -3.13 --version` (or `py -3.12 --version`) |
| **IntelliJ IDEA** (for the server) and **PyCharm** (for the console and the tools) | both start |
| pip and Maven reach your **internal mirror** | if a download fails, see section 7 |
| 8 GB free memory, 2 GB free disk | Task Manager |

---

## 1. Copy the project — nothing to unzip

1. Copy everything from GitHub into `C:\Users\asinh09\IdeaProjects\drishti-orig` (a `git clone`, or the ZIP from
   GitHub extracted there). Take the branch **main**.
2. **Nothing inside needs unzipping.** The test data in `data\testdata` is `.jsonl.gz`; Drishti reads the `.gz` files
   directly.
3. Create the folder `C:\Users\asinh09\drishti-private\endur\` and put your real ENDUR `.jsonl` files in it, **one file per
   business date**, named by the date, for example `2026-10-16.jsonl`.

You should see: `drishti-orig` contains `drishti-server`, `drishti-console`, `tools`, `config`, `data`, `run-configs`.

---

## 2. One-time setup

### 2.1 The Python environment (PowerShell, once)

Open **PowerShell** in the project folder and run:

```powershell
cd C:\Users\asinh09\IdeaProjects\drishti-orig
py -3.13 -m venv drishti-console\.venv
drishti-console\.venv\Scripts\python.exe -m pip install -r drishti-console\requirements.txt
```

You should see: the last line says `Successfully installed …` (fastapi, uvicorn, pyyaml, pyarrow, deltalake and others).

### 2.2 PyCharm (once)

1. **File → Open** → choose `C:\Users\asinh09\IdeaProjects\drishti-orig` (the whole folder, **not** `drishti-console`) →
   **Trust Project**.
2. **File → Settings → Project: drishti-orig → Python Interpreter → Add Interpreter → Add Local Interpreter →
   Virtualenv Environment → Existing** → `C:\Users\asinh09\IdeaProjects\drishti-orig\drishti-console\.venv\Scripts\python.exe`
   → **OK**.
3. In the **Project** panel, right-click each folder → **Mark Directory as**:
   - `drishti-console` → **Sources Root**
   - `tools` → **Sources Root**
   - `drishti-console\tests` → **Test Sources Root**
   - `data` → **Excluded** (PyCharm must not index the lakes)
4. **File → Settings → Tools → Python Integrated Tools → Default test runner** → **pytest** → **OK**.

You should see: the drop-down at the top right lists the configurations of `run-configs\`, among them **Build demo lakes**,
**Load ENDUR file into the lake** and **Drishti console**. They all use the interpreter of step 2; nothing to edit.

> **What these configurations run.** Every command in this guide that starts with `lake …` (`lake demo`, `lake build`)
> is not a program of its own: it is a sub-command of the project's command-line tool **`tools\drishti.py`**. A
> PyCharm configuration is just "run `tools\drishti.py` with these *Parameters*, from the project folder":
>
> | Configuration | Script | Parameters (what you edit) |
> |---|---|---|
> | **Build demo lakes** | `tools\drishti.py` | `lake demo …` |
> | **Load ENDUR file into the lake** | `tools\drishti.py` | `lake build …` |
> | **Drishti console** | `drishti-console\run_drishti_web.py` | `--server.port=17480 --backend.url=http://127.0.0.1:18480` |
>
> So a line like `lake build --from …` below goes into the **Parameters** box, not into a terminal. To run the same
> thing in PowerShell instead, put `drishti-console\.venv\Scripts\python.exe tools\drishti.py` in front of it, from
> the project folder. `python tools\drishti.py lake build --help` lists every option.

### 2.3 IntelliJ IDEA (once)

1. **File → Open** → `C:\Users\asinh09\IdeaProjects\drishti-orig` → **Trust Project**. IntelliJ imports the Maven project
   (bottom-right progress bar; it downloads through your mirror the first time).
2. **File → Project Structure → Project → SDK** → your **JDK 21** → **OK**.
3. Top-right drop-down → **Drishti build server jar** → ▶ (Run). (This is Maven `package -DskipTests -pl drishti-server -am`
   on the project's `pom.xml`.)

You should see: in the Run window `BUILD SUCCESS`, and the file `drishti-server\target\drishti-server-2.3.0-exec.jar`
exists.

---

## 3. Put the data in place (PyCharm)

### 3.1 All synthetic data, aligned to your ENDUR dates

The synthetic data has seven business days. Make them **end on the date of your newest ENDUR file**, so the date picker
shows CALYPSO, MUREX and ENDUR together.

1. PyCharm, top-right drop-down → **Build demo lakes** → **Edit Configurations…**. The configuration runs the script
   `tools\drishti.py`; in its *Parameters* box change `lake demo` to `lake demo --as-of 2026-10-16` (your newest ENDUR
   date) → **OK**.
2. Press ▶ (Run).

You should see, after about a minute: the Run window ends with what to start next; `data\delta` now holds the folders
`trading`, `reference`, `legal`, `collateral`, `credit`, `risk`, `market`, `macro`, `news` and `civic` (64 tables). This includes the quotes and news snapshot and
the world economy data.

(PowerShell instead: `drishti-console\.venv\Scripts\python.exe tools\drishti.py lake demo --as-of 2026-10-16`.)

### 3.2 Replace the synthetic ENDUR trades with your real files

1. **Delete the synthetic ENDUR table:** in Windows Explorer delete the folder
   `C:\Users\asinh09\IdeaProjects\drishti-orig\data\delta\trading\trade_endur`. (Only that folder. `trade_calypso` and
   `trade_murex` stay.)
2. PyCharm, top-right drop-down → **Load ENDUR file into the lake** → **Edit Configurations…**. The configuration runs
   the script `tools\drishti.py` (field *Script path*); the line below is what its *Parameters* box holds. Change only
   the two things that name your file — the path after `--from` and the date after `--as-of` — for example:

   ```
   lake build --from C:\Users\asinh09\drishti-private\endur\2026-10-15.jsonl --as-of 2026-10-15 --pack config/packs/trading --connector trade-endur --key TRADE_ID --partition-by PRODUCT_TYPE --mode overwrite-dates --lake data\delta
   ```

   → **OK** → ▶ (Run).

   PowerShell instead (from the project folder, same line with the script in front):

   ```powershell
   drishti-console\.venv\Scripts\python.exe tools\drishti.py lake build --from C:\Users\asinh09\drishti-private\endur\2026-10-15.jsonl --as-of 2026-10-15 --pack config/packs/trading --connector trade-endur --key TRADE_ID --partition-by PRODUCT_TYPE --mode overwrite-dates --lake data\delta
   ```

   What the options mean: `--from` your file; `--as-of` its business date; `--pack` the trading pack, whose schema
   checks each trade; `--connector trade-endur` the table it feeds (`data\delta\trading\trade_endur`); `--key TRADE_ID`
   the id field; `--partition-by PRODUCT_TYPE` one folder per product type; `--mode overwrite-dates` replaces that
   date if it was loaded before; `--lake data\delta` the lake folder the server reads (the project's `data\delta`; the
   table lands in `data\delta\trading\trade_endur`). Run it from the project folder: `data\delta` is relative to it.
3. Repeat step 2 for **each** of your files (each with its own path and date).

You should see, for each file, a last line like:

```
lake-build ok exit=0 ... domain=trading kind=trade engine=drishti mode=overwrite-dates rows=500 rejected=0 dates=1 partitions=5
```

`rows` is the number of trades read. `rejected` should be `0`; if not, the lines above it say which line and why.

Good to know:

- **Trade ids are text.** A `TRADE_ID` written as a number in your file (`1000008`) is read as the text `"1000008"`; ids
  may have letters, dashes and underscores; nothing expects an `END` prefix. `SOURCE_SYSTEM` says the trade is ENDUR.
- Loading the same file again is safe: `--mode overwrite-dates` replaces that date.
- A file of several hundred thousand trades takes a few minutes.

---

## 4. Start the server (IntelliJ) and the console (PyCharm)

1. **IntelliJ**, top-right drop-down → **Drishti server (demo lakes)** → ▶ (Run).
   You should see, within about 20 seconds: `Started DrishtiApplication in … seconds` in the Run window.
   This configuration runs with the seven packs, the lakes as the only data (no built-in samples), a 4 GB heap, and
   **offline mode** (`DRISHTI_OFFLINE=true`): quotes and news come from the committed snapshot and no website is
   contacted.
2. **PyCharm**, top-right drop-down → **Drishti console** → ▶ (Run).
   You should see: `Uvicorn running on …:17480`.
3. Browser → `http://localhost:17480` → sign in as `drishti-dev-admin` / `drishti-dev-admin123`.

The console reconnects by itself if you start or restart the server after it.

---

## 5. Check in the admin console (5 minutes)

1. **Admin ▾ → Lakes.** You should see every table with its rows and latest date. `trading / trade_endur` shows **your**
   row count and **your** dates. (On Windows the folder under each table name reads `C:\Users\asinh09\IdeaProjects\drishti-orig\data\delta`.)

   ![Admin, Lakes: every table with rows, files, size, dates and the Maintain controls](img/qa-windows/01-admin-lakes.png)
2. **Admin ▾ → Connectors.** You should see `trade-endur`, `trade-calypso`, `trade-murex` and the stores **RUNNING**.
   Yahoo Finance and news are shown as **off (offline mode)**; their stores serve the snapshot.

   ![Admin, Connectors: the trade connectors of the three systems RUNNING](img/qa-windows/02-admin-connectors.png)
3. **Admin ▾ → Health.** You should see everything green (a warning about a field mask of an example pack that is not
   loaded can be ignored).

   ![Admin, Health](img/qa-windows/03-admin-health.png)
4. **Admin ▾ → Packs → trading → Sutra coverage.** Every SOURCE_SYSTEM × PRODUCT_TYPE in your data has exactly one Sutra.
   A row without a Sutra means a product type of your real data the pack does not know yet: its trades still open, laid
   out by inference.

   ![Admin, Packs, trading: the Sutra coverage table](img/qa-windows/04-pack-coverage.png)

---

## 6. The demo, page by page

| # | Show | How |
|---|---|---|
| 1 | An **ENDUR deal** from your file | Top bar: type one of your real trade ids → Enter. Ticket, legs side by side, cash flows → resets, events, charts. |
| 2 | Trades by system and product | **Forms → Trading**: Source system `ENDUR`, Product type e.g. `ComSwap` → Search. Then `CALYPSO`, then `MUREX`. |
| 3 | A **CALYPSO** and a **MUREX** deal | `/v/trade/CLY-3000157` and `/v/trade/MX-20000001` |
| 4 | **Counterparty → netting set → CSA → collateral** | `/v/counterparty-trades/CP-NORTHPLAINS`, then click a netting set, its CSA, its collateral |
| 5 | A **curve** | `/v/commodity-curve/CMDC-BRENT` |
| 6 | An **equity price** | `/v/listed-company/CO-AAPL` (quote and chart from the snapshot) |
| 7 | **News** | the news panel of the company page, or `/v/news-topic/TOPIC-FED` |
| 8 | **Compare** across two dates | on a trade page, set the date picker to one of your dates → **Compare** → the other date |
| 9 | **BI** by source system | **BI → Reports → Trades overview**; change a slicer; open *How this was answered* |
| 10 | How a screen is designed | on a deal page, *How this view was built* → click the Sutra name: the designer opens with the trade as its sample |

### The pages, as they look

| | |
|---|---|
| ![An ENDUR deal: ticket, payments, linked entities](img/qa-windows/05-endur-deal.png) **1. An ENDUR deal** | ![Forms: the trading form](img/qa-windows/06-trading-form.png) **2. Forms → Trading** |
| ![A CALYPSO deal](img/qa-windows/07-calypso-deal.png) **3. A CALYPSO deal** | ![A MUREX deal](img/qa-windows/08-murex-deal.png) **3. A MUREX deal** |
| ![A counterparty, its trades and netting sets](img/qa-windows/09-counterparty.png) **4. A counterparty** | ![A commodity curve](img/qa-windows/10-curve.png) **5. A curve** |
| ![Compare a trade across two dates](img/qa-windows/13-compare.png) **8. Compare** | ![BI: Trades overview](img/qa-windows/14-bi-trades-overview.png) **9. BI, Trades overview** |
| ![How this view was built: the Sutra link](img/qa-windows/15-view-built.png) **10. How this view was built** | ![The designer opened on the Sutra with the trade as its sample](img/qa-windows/16-designer.png) **10. The designer** |

The pictures were taken on synthetic data (a stand-in ENDUR file loaded exactly as in step 3.2); your pages show your
trades.

---

## 7. If something goes wrong

| What you see | Do this |
|---|---|
| `pip` or Maven cannot download | Ask IT for the mirror settings: pip reads `%APPDATA%\pip\pip.ini`; Maven reads `%USERPROFILE%\.m2\settings.xml`. |
| PyCharm says the interpreter is invalid | Redo step 2.2 point 2. |
| `Port 18480 (or 17480) is already in use` | Another copy is running: stop it in IntelliJ / PyCharm (red square), or reboot. |
| `lake build` says `no id (field 'id' is missing…)` | The parameters lack `--key TRADE_ID`: copy the line of step 3.2 again. |
| `lake build` says `give --lake DIR (the Delta root the connector reads)` | The parameters lack `--lake data\delta`: copy the line of step 3.2 again (it ends with `--lake data\delta`), and run it from the project folder. |
| PowerShell says `lake` is not recognized | `lake …` is not a command of its own: run it as `drishti-console\.venv\Scripts\python.exe tools\drishti.py lake …` from the project folder, or use the PyCharm configuration (step 2.2, *What these configurations run*). |
| The run configurations are not in the drop-down | The project was opened at `drishti-console` instead of the project folder: **File → Open** `drishti-orig` itself (step 2.2 point 1). |
| ENDUR pages still show synthetic trades | Step 3.2 point 1 was skipped: stop the server, delete `data\delta\trading\trade_endur`, load your files again, start the server. |
| ENDUR and CALYPSO/MUREX never show on the same date | Step 3.1 used another date: run **Build demo lakes** again with `--as-of` = your newest ENDUR date, then step 3.2 again. |
| A page says *unavailable* for quotes or news | Check **Admin → Connectors**: the snapshot stores must be RUNNING; run **Build demo lakes** again. |
| The server stops with `OutOfMemoryError` | Close other programs; the configuration already gives it 4 GB (`-Xmx4g`). |

---

## 8. Checklist

**The day before:**

- [ ] Step 2 done once (Python environment, PyCharm, IntelliJ, jar built).
- [ ] Step 3.1 with `--as-of` = your newest ENDUR date.
- [ ] Step 3.2 for every ENDUR file, each `rejected=0`.
- [ ] Step 4: server and console start; sign-in works.
- [ ] Step 5: Lakes, Connectors, Health as described.
- [ ] Step 6: walk through the ten pages once.

**After the demo:**

- [ ] Stop the server and the console.
- [ ] In PowerShell in the project folder, `git status` shows nothing new outside `data\` (the lakes there are ignored by
      git). Your ENDUR files never left `C:\Users\asinh09\drishti-private\`.
