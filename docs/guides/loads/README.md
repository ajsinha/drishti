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
# Daily data loads: one page per type

For the person who loads Drishti's data every day. **One page per data load type**; each has the same five parts, in this order: **Every day** (the one command, Linux and Windows, and what you see when it worked), **Schedule it** (a cron line and a Windows Task Scheduler recipe), **When a day fails or is late** (re-run, a missed day, several days, replace a day), **Check it loaded** (the commands, with sample output) and **Reference** (what it writes, the input format and file naming, the source and licence, errors and fixes).

Commands are shown for Linux (`python3.13 tools/drishti.py ...`) and Windows (`py -3.13 tools\drishti.py ...`); only the interpreter and the path separator differ. Run them from the Drishti folder.

## The pages

| Data | Page | The daily command | When |
|---|---|---|---|
| **Your own bank files** | | | |
| Trades | [load-trade.md](load-trade.md) | drop `endur-<date>.jsonl.gz` and its `.ready` in the inbox, or `lake build` | after your trade extract |
| Scenario PVs (PVF) | [load-scenario-pv.md](load-scenario-pv.md) | `pvf_run.py ... --date D --load` | after the PVF extract |
| Positions | [load-positions.md](load-positions.md) | the same PVF command (positions are joined into the PVs) | with the PVs |
| Portfolios (books) | [load-portfolio.md](load-portfolio.md) | `csv_load.py --kind book --source pvf ...` | after the PVF extract |
| Legal entities, desks, books | [load-csv-organisation.md](load-csv-organisation.md) | `mr_run.py --day organisation.yaml` | on change, or daily |
| Counterparties, agreements, netting sets, limits | [load-csv-counterparty.md](load-csv-counterparty.md) | `mr_run.py --day counterparty.yaml` | daily |
| VaR limits, risk factors | [load-csv-limits-and-factors.md](load-csv-limits-and-factors.md) | `mr_run.py --day limits.yaml` | daily |
| Sensitivities, valuations, desk P&L | [load-csv-sensitivities-and-pnl.md](load-csv-sensitivities-and-pnl.md) | `mr_run.py --day sensitivities.yaml` | daily |
| **Public market and reference data** | | | |
| Market data (ECB FX, Treasury curve, SOFR, EUR STR) | [load-market-data.md](load-market-data.md) | `drishti.py feed run market-data` | weekdays 18:30 |
| GLEIF legal entities | [load-gleif.md](load-gleif.md) | `drishti.py feed run gleif` | daily 05:30 |
| US funds and ETFs | [load-funds.md](load-funds.md) | `drishti.py feed run funds` | daily 06:15 |
| US equities | [load-us-equities.md](load-us-equities.md) | `drishti.py feed run us-equities` | daily 07:00 |
| **The economy** | | | |
| Central bank rates | [load-central-banks.md](load-central-banks.md) | `drishti.py feed run central-banks` | weekdays 18:15 |
| National statistics | [load-national-statistics.md](load-national-statistics.md) | `drishti.py feed run national-statistics` | weekdays 09:00 |
| OECD indicators | [load-oecd.md](load-oecd.md) | `drishti.py feed run oecd` | daily 07:00 |
| The world economy | [load-world-economy.md](load-world-economy.md) | `drishti.py feed run world-economy` | daily 06:45 |
| World development indicators | [load-world-development.md](load-world-development.md) | `drishti.py feed run world-development` | daily 06:30 |
| **News and events** | | | |
| Official press releases | [load-official-news.md](load-official-news.md) | `drishti.py feed run official-news` | daily 19:00 |
| News and quotes (internal only) | [load-news-and-markets.md](load-news-and-markets.md) | `drishti.py feed run news-and-markets` | daily 07:30 |
| GDELT world events | [load-gdelt-events.md](load-gdelt-events.md) | `drishti.py feed run gdelt-events` | daily 04:30 |
| **Genomics** | | | |
| ClinVar variants | [load-clinvar.md](load-clinvar.md) | `drishti.py feed run clinvar` | daily 06:00 |
| Ensembl genes | [load-ensembl-genes.md](load-ensembl-genes.md) | `drishti.py feed run ensembl` | daily 06:10 |
| Your own VCF, gene and sample files | [load-genomics-files.md](load-genomics-files.md) | `genomics_run.py` | after your files land |

## Set up once, for every page

- **Python and the server jar.** `drishti.py setup` checks the libraries and prints the `pip` command for anything missing (you run it; nothing is installed for you, and there is no virtual environment). Loading needs Java 21 and `lib/drishti-server-<version>-exec.jar` (`drishti.py build jar`). `drishti.py doctor` checks all of it (green, yellow and red lines with a fix; exit 1 on a red).
- **A contact in the User-Agent** for the public feeds: `DRISHTI_FEEDS_USER_AGENT` (Linux `export`, in a crontab as a `NAME=value` line; Windows `setx`, then a new window). Use your own organisation's contact; the SEC and others require a real one.
- **The data folder** is `data/` (or `DRISHTI_DATA_DIR`). Everything a load creates is under it: `data/datafeeds/<feed>/<date>/` (the feeds), `data/dataload/inbox` (the inbox), `data/genomics/inbox` (your genomics files).
- **Exit codes** tell a scheduler how it went: 0 done, 1 failed or refused, 2 loaded with rejected rows (or refused: a setting), 3 another load holds the lock, 4 partial. A feed prints one closing line (`feed ok exit=0 feed=... rows=... loaded=...`) and appends it to `data/datafeeds/ledger.jsonl`.
- **Re-running is safe.** Every load replaces its business date **whole** in its table and leaves the other dates alone.

## Check any day

| Question | Command |
|---|---|
| Did the feeds run, and when did each last succeed? | `drishti.py feed status` |
| Rows and dates per table of a lake | `drishti.py lake status --lake <lake>` |
| What is in the inbox, what failed and why? (needs the server) | `drishti.py dataload status` |
| Is the machine ready? | `drishti.py doctor` |
| Take a date out of a table | `drishti.py lake delete --lake <lake> --table <domain/table> --date D --dry-run` |

In the console: **Admin → Data loads** (inbox, outbox, failed, lakes, feeds, **Run now**).

## Depth

[The loaders' READMEs](../../../tools/loaders/README.md) (the families and how the scripts work), [BANK_DATA_GUIDE.md](../BANK_DATA_GUIDE.md) (the bank's files, column by column), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the public data), [DATA_GUIDE.md](../DATA_GUIDE.md) and [DATA_LAKE_GUIDE.md](../DATA_LAKE_GUIDE.md) (the inbox, lakes and `.ready` files), [CLI_REFERENCE.md](../CLI_REFERENCE.md) (every command).
