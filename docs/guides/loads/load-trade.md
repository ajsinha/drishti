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
# Load trades every day

Trades from the trading systems (Endur, Calypso, Murex, WSS, SwapOne, ICI) as JSON Lines, one file per system and business date, into the one canonical `trade` kind. Each system has **its own table**
(`trading/trade_endur`, `trade_calypso`, `trade_murex`, `trade_wss`, `trade_swapone`, `trade_ici`); a system's file replaces that system's date in its own table and nothing else.
Your extract or ETL writes the file; Drishti never connects to the trading system for this load.

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. Loading needs Java 21 and the server jar in `lib` (`drishti.py build jar`); converting needs neither. A date in an example is an example: use the business date of your file.

## Every day

Two ways, the same result. **A. The data inbox** (the production way: the server loads the file at its next cycle). Drop the file, then its marker, then ask for a cycle:

```text
Linux     cp /landing/endur-2026-11-27.jsonl.gz /opt/drishti/data/dataload/inbox/
          touch /opt/drishti/data/dataload/inbox/endur-2026-11-27.jsonl.gz.ready
          cd /opt/drishti && python3.13 tools/drishti.py dataload run --wait --token-file ~/.drishti-token
Windows   copy C:\landing\endur-2026-11-27.jsonl.gz C:\drishti\data\dataload\inbox\
          type nul > C:\drishti\data\dataload\inbox\endur-2026-11-27.jsonl.gz.ready
          cd C:\drishti && py -3.13 tools\drishti.py dataload run --wait --token-file %USERPROFILE%\.drishti-token
```

The file goes first and its `.ready` marker last: the marker says the file is complete. An empty marker is enough; the module's rule names the table (`endur-*.jsonl.gz` goes to `trading/trade_endur`) and reads the date from the name.
`dataload run --wait` exits **0** all loaded, **2** loaded with rejected rows (counts and reasons printed), **1** a file failed. The server need not be asked: it also takes a ready file at its own next cycle.

**B. Straight into the lake** (no server needed; one run over the system's file, one commit per date):

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py lake build --engine java --from /landing/endur-2026-11-27.jsonl.gz --as-of 2026-11-27 \
              --lake demo --pack modules/banking/packs/trading --kind trade --connector trade-endur --key TRADE_ID
Windows   cd C:\drishti && py -3.13 tools\drishti.py lake build --engine java --from C:\landing\endur-2026-11-27.jsonl.gz --as-of 2026-11-27 ^
              --lake demo --pack modules\banking\packs\trading --kind trade --connector trade-endur --key TRADE_ID
```

`--lake` is the lake your trade connectors read (`lake:` in `config/connectors/trade-endur.yaml`); `demo` is the shipped one. Add `--signal http://localhost:18480 --token-file FILE` to tell a running server at once.
**What you see when it worked** (a real run of this page's author, the 4,407 synthetic Endur trades of `data/testdata`, into a scratch data folder):

```text
engine: java-loader (--engine java: the parallel lake loader)
business date  PRODUCT_TYPE           rows
2026-07-08     ComSwap                1994
2026-07-08     ComFut                  883
2026-07-08     Cash                    723
2026-07-08     CallNot                  50
2026-07-08     Commodity               757
documents read: 4407, rows written: 4407, rejected: 0
  business date 2026-07-08 from the command line: endur-2026-07-08.jsonl.gz
summary: <scratch>/data/delta/demo/_drishti-lake/runs/20261127T194416.548-3595641.json
2026-11-27T19:44:22-05:00 lake-build ok exit=0 run=20261127T194416.548-3595641 domain=trading kind=trade engine=java-loader mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=5.986
```

For another system change the file's prefix and `--connector` (`trade-calypso`, `trade-murex`, `trade-wss`, `trade-swapone`, `trade-ici`); the key is `TRADE_ID` for Endur, Calypso and Murex, `DEAL_REF` for WSS, `SWAP_ID` for SwapOne and `TICKET_NO` for ICI.
A system's files that are ready in one inbox cycle (`endur-part1-2026-11-27.jsonl.gz`, `endur-part2-2026-11-27.jsonl.gz`) are **one load and one commit**.

## Schedule it

Schedule the job **after your extract lands** (the load does not wait for a file that has not come). This one takes today's Endur file from the landing folder and loads it straight into the lake:

**Linux cron** (`crontab -e`; mind that `%` must be written `\%` in a crontab):

```text
SHELL=/bin/bash
30 19 * * 1-5 cd /opt/drishti && d=$(date +\%F) && python3.13 tools/drishti.py lake build --engine java --from /landing/endur-$d.jsonl.gz --as-of $d --lake demo --pack modules/banking/packs/trading --kind trade --connector trade-endur --key TRADE_ID --signal http://localhost:18480 --token-file $HOME/.drishti-token >> $HOME/drishti-trades.log 2>&1
```

Cron starts with an empty environment: put the working folder (`cd /opt/drishti`) and any variable in the line or the crontab, as above. For the inbox way (A) schedule the copy, the `touch` and `dataload run --wait` in one script the same way.

**Windows Task Scheduler.** Save the job as a script, then register it:

```powershell
# C:\drishti\jobs\load-trades.ps1
$d = Get-Date -Format yyyy-MM-dd
Set-Location C:\drishti
py -3.13 tools\drishti.py lake build --engine java --from "C:\landing\endur-$d.jsonl.gz" --as-of $d `
  --lake demo --pack modules\banking\packs\trading --kind trade --connector trade-endur --key TRADE_ID `
  --signal http://localhost:18480 --token-file "$env:USERPROFILE\.drishti-token" *>> C:\drishti-logs\trades.log
exit $LASTEXITCODE
```

```text
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load trades" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 19:30 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-trades.ps1"
schtasks /Run /TN "Drishti load trades"
schtasks /Query /TN "Drishti load trades" /V /FO LIST
```

The task's *Last Run Result* is the script's exit code. By hand: Task Scheduler → Create Task → *Triggers* New (weekly, Monday to Friday, at the time your extract lands plus a margin) → *Actions* New → Start a program `powershell`, arguments `-NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-trades.ps1`, **Start in** `C:\drishti` → *Run whether user is logged on or not*.

## When a day fails or is late

- **Late file:** load it when it arrives, with the date in its name or `--as-of`. Nothing else has to move: other dates and other systems are untouched.
- **Several days, oldest first:**

  ```bash
  for d in 2026-11-24 2026-11-25 2026-11-26; do
    python3.13 tools/drishti.py lake build --engine java --from /landing/endur-$d.jsonl.gz --as-of $d --lake demo \
      --pack modules/banking/packs/trading --kind trade --connector trade-endur --key TRADE_ID || break
  done
  ```

  ```powershell
  foreach ($d in "2026-11-24","2026-11-25","2026-11-26") {
    py -3.13 tools\drishti.py lake build --engine java --from "C:\landing\endur-$d.jsonl.gz" --as-of $d --lake demo `
      --pack modules\banking\packs\trading --kind trade --connector trade-endur --key TRADE_ID
    if ($LASTEXITCODE -ne 0) { break }
  }
  ```

- **Replace a day:** send the corrected file again for that date and run the same command. **The date is replaced whole**: the new file must hold the day's *full* set of live trades (or, knowingly, only the day's changes). A trade is seen on a date only when that date's file carries it.
- **Run it again** (a failed or interrupted load): the same command. Each load replaces its business date **whole** in the table and leaves the other dates alone, so a repeat never doubles rows.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake demo --table trading/trade_endur --date 2026-11-24 --dry-run`, then without `--dry-run` (asks to confirm; `--yes` for a script). **Undo one load** by its id (the `run=` in its closing line): `python3.13 tools/drishti.py lake undo --lake demo --dry-run RUN_ID`, then `--yes`.
- **A file that was refused** in the inbox goes to `failed/` with the reason in words: fix it, then `python3.13 tools/drishti.py dataload retry endur-2026-11-27.jsonl.gz`.
- **A system's module is off:** its trades leave the pages and its inbox rule goes with it. Switch it on in Admin → Modules (`config/modules/source-<system>.yaml`, `enabled: true`).
- A scheduler reads the exit code: **0** done, **1** refused or failed (one line says why), **2** loaded with rejected rows, **3** another load holds the lake's lock (wait and repeat).

## Check it loaded

```text
Linux     python3.13 tools/drishti.py lake status --lake demo
Windows   py -3.13 tools\drishti.py lake status --lake demo
```

```text
lake <scratch>/data/delta/demo
tables (1):
  table                rows   files  size    partitions        dates  latest date  last build
  trading/trade_endur  4,407  5      6.7 MB  5 (PRODUCT_TYPE)  1      2026-07-08   2026-11-28 00:44:22Z
lock: free
```

The count is the file's: `zcat endur-2026-11-27.jsonl.gz | wc -l` (Windows: load the same file and compare the `rows written`). With a server running:

```text
Linux     python3.13 tools/drishti.py dataload status --token-file ~/.drishti-token
          python3.13 tools/drishti.py pivot run --source trade --date 2026-11-27 --rows SOURCE_SYSTEM --values "count()" --token-file ~/.drishti-token
Windows   py -3.13 tools\drishti.py dataload status --token-file %USERPROFILE%\.drishti-token
          py -3.13 tools\drishti.py pivot run --source trade --date 2026-11-27 --rows SOURCE_SYSTEM --values "count()" --token-file %USERPROFILE%\.drishti-token
```

The pivot prints one row per system and a grand total (the runbook's tested run showed `ENDUR 4,407 ... Grand total 12,753` for six systems on 2026-07-08). In the console: Admin → Data loads (Inbox, outbox, failed, lakes, feeds) and the Trades menu's blotter, filtered on `SOURCE_SYSTEM`; `TRD <id>` shows one trade. `python3.13 tools/drishti.py doctor` checks Java, the jar, the libraries and the folders.

## Reference

**What it writes.** Lake `demo` (or your trade lake), tables `trading/trade_<system>`, kind `trade` (mnemonic `TRD`). The contract of every field: [TRD, the trade contract](../../contracts/packs/trading.md#trd-trade-trade). The files are stored **as the system sent them**; the field map (`modules/source-<system>/mappings/<system>-trade.yaml`) is applied when trades are read, so a new attribute costs nothing at load. The load checks no field of a trade: get the key, `SOURCE_SYSTEM`, status, product, counterparty, legal entity and book right.

**Input and file naming.** JSON Lines, gzip recommended (`.jsonl`, `.jsonl.gz`, `.zip`, `.tar.gz` are read), `UPPER_SNAKE` field names.

| System | File name | Native key | Table |
|---|---|---|---|
| Endur | `endur-<date>.jsonl.gz` | `TRADE_ID` | `trading/trade_endur` |
| Calypso | `calypso-<date>.jsonl.gz` | `TRADE_ID` | `trading/trade_calypso` |
| Murex | `murex-<date>.jsonl.gz` | `TRADE_ID` | `trading/trade_murex` |
| WSS | `wss-<date>.jsonl.gz` | `DEAL_REF` | `trading/trade_wss` |
| SwapOne | `swapone-<date>.jsonl.gz` | `SWAP_ID` | `trading/trade_swapone` |
| ICI | `ici-<date>.jsonl.gz` | `TICKET_NO` | `trading/trade_ici` |

The date is in the name as `YYYY-MM-DD` (`-(\d{4}-\d{2}-\d{2})\.`; a name that does not match is refused), or `--as-of` on the command line, or `business-date:` in a `.ready` file (which wins). A `.ready` may also say `lake:`, `mode:`, `sha256:` and `rows:` (a file cut short fails before anything is written); every key is in [DATA_LAKE_GUIDE.md section 4](../DATA_LAKE_GUIDE.md#4-the-ready-file-every-key).
**The lake must describe the table.** A writer never creates a table the lake file does not describe: a load into a table with no entry stops with *lake X has no table for DOMAIN.TABLE: add its entry to config/lakes/X.yaml*. The shipped `demo` lake has the entries of the standard kinds; `risklake` ships empty (`tables: {}`), so copy the entries you need from the worked example in [DATA_LAKE_GUIDE.md section 2.1](../DATA_LAKE_GUIDE.md#21-a-worked-example-configlakesrisklakeyaml) once, keep the file with your site's settings, and point the connectors at the lake (`config/connectors/*.yaml`, `lake:`). The commands below say `--lake demo`; use your lake's name.

**A real example** (two rows of `data/testdata/trades/endur`, synthetic and fictitious; fields cut to 14 of about 80):

```json
{"SOURCE_SYSTEM": "ENDUR", "TRADE_ID": "1000001", "TRADE_DATE": "2025-06-20", "TRADE_STATUS": "VALIDATED", "PRODUCT_TYPE": "ComSwap", "BUY_SELL_CODE": "S", "QUANTITY": 72000.0, "PRICE": 2332.9131, "CURRENCY": "USD", "COUNTERPARTY": "CP-HARBORPT", "LEGAL_ENTITY": "LE-NY", "PORTFOLIO": "BOOK-COMM-2", "TRADER_ID": "TRDR-FROSSI", "MATURITY_DATE": "2028-07-31"}
{"SOURCE_SYSTEM": "ENDUR", "TRADE_ID": "1000002", "TRADE_DATE": "2025-01-21", "TRADE_STATUS": "MATURED", "PRODUCT_TYPE": "ComSwap", "BUY_SELL_CODE": "B", "QUANTITY": 300000.0, "PRICE": null, "CURRENCY": "USD", "COUNTERPARTY": "CP-LUMEN", "LEGAL_ENTITY": "LE-NY", "PORTFOLIO": "BOOK-COMM-1", "TRADER_ID": "TRDR-TBRENNAN", "MATURITY_DATE": "2025-06-30"}
```

**Source and licence.** Yours. The files in `data/testdata` are generated, fictitious and committed; real extracts are never committed.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| `failed/`: *no rule matches this name* | the name does not fit `<system>-*.jsonl.gz` | rename it, or add a rule (`config/dataload.yaml`) |
| `failed/`: *... is neither the date ... nor an extension* | a word sits between the table and the date | use `endur-2026-11-27.jsonl.gz` or `endur-part1-2026-11-27.jsonl.gz` |
| `failed/`: *its .ready file says N rows, the file holds M* | the file was cut short, or `rows:` is wrong | resend, then `dataload retry` |
| a file waits in *Waiting*, *no marker* | no `.ready` beside it | add `<file>.ready` |
| the trades of one system are missing, the others are there | its module is off, or its connector reads a table that holds nothing | `enabled: true` in its module file; Admin → Connectors |
| *lake demo has no table for trading.trade_xyz* | the lake file has no entry for the table | add it (above) |
| 404 *no source holds trade/ID ... for DATE* | the id is not in that date's file | ask the date that has it; send full dates |

More: [DATA_LAKE_GUIDE.md section 16.2](../DATA_LAKE_GUIDE.md#162-production-trades-from-the-source-systems) (the runbook, sizing and the field maps) and [BANK_DATA_GUIDE.md section 8](../BANK_DATA_GUIDE.md#8-the-trading-module) (the trading module).
