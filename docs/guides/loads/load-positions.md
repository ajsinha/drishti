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
# Load positions every day

**Drishti has no position table of its own to fill.** A position reaches Drishti one of three ways, and the daily job for all three is the same command: the [scenario PV load](load-scenario-pv.md).

| Your positions are | They arrive as | You load |
|---|---|---|
| the PVF extract's `pos_YYYYMMDD.csv` (one row per position: portfolio, currency, trade, amount) | joined into the position's scenario-PV record (`risk/scenario-pv`, grain `POSITION`) | the PVF day, below |
| position-level PVs from another system, already `risk/scenario-pv` rows | the rows themselves (`GRAIN: POSITION`, a `POSITION_ID`, placed by its book) | the inbox, as [load-scenario-pv](load-scenario-pv.md) |
| positions you want derived from trades | derived by the VaR engine when a date loads (the `position` kind, `POS`), never stored | [trades](load-trade.md) |

Position **sensitivities** (delta, vega per risk factor) are a separate CSV kind: [sensitivities, valuations and desk P&L](load-csv-sensitivities-and-pnl.md).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. Loading needs Java 21 and the server jar in `lib` (`drishti.py build jar`); converting needs neither. A date in an example is an example: use the business date of your file.

## Every day

The positions file goes in with the PVs, the portfolio file and the date. The one command (check, then load; see [scenario PVs](load-scenario-pv.md) for the full run and its output):

```text
Linux     cd /opt/drishti
          python3.13 tools/loaders/mrloader/pvf_run.py --pv /data/pvf/pvf_path_20261127.csv --portfolio /data/pvf/portfolio_d2.csv --positions /data/pvf/pos_20261127.csv --date 2026-11-27 --dry-run
          python3.13 tools/loaders/mrloader/pvf_run.py --pv /data/pvf/pvf_path_20261127.csv --portfolio /data/pvf/portfolio_d2.csv --positions /data/pvf/pos_20261127.csv --date 2026-11-27 --load --lake demo
Windows   cd C:\drishti
          py -3.13 tools\loaders\mrloader\pvf_run.py --pv D:\pvf\pvf_path_20261127.csv --portfolio D:\pvf\portfolio_d2.csv --positions D:\pvf\pos_20261127.csv --date 2026-11-27 --dry-run
          py -3.13 tools\loaders\mrloader\pvf_run.py --pv D:\pvf\pvf_path_20261127.csv --portfolio D:\pvf\portfolio_d2.csv --positions D:\pvf\pos_20261127.csv --date 2026-11-27 --load --lake demo
```

**What you see when it worked** (a real run on a synthetic extract of 300 positions; every position of the file is read, the ones with no base PV are left out and named):

```text
INFO read 300 positions and 20 portfolio rows valid on 2026-11-27 (0.0s)
INFO summary: 300 positions in, 284 written, 16 excluded {'MISSING_BASE_PV': 16}; fills (forward): 77 positions, path 0 from BASE_PV in 46, 9,999 paths (14.03% of all); 0.1s, peak memory 154.1 MB
2026-11-27T19:44:59-05:00 lake-build ok exit=0 run=20261127T194455.508-3597168 domain=risk kind=scenario-pv engine=java-loader mode=overwrite-dates rows=284 rejected=0 dates=1 partitions=1 seconds=3.965
INFO pvf run of 2026-11-27: 300 positions in, 16 excluded by the join, 0 by the mapping, 284 records written
```

Positions the join leaves out are listed one per line in `mr-2026-11-27/pvf_joined.2026-11-27.audit.csv`:

```text
POSITION_ID,REASON,DETAIL
200000020,MISSING_BASE_PV,no row with path_num = -1 on 2026-11-27
200000030,MISSING_BASE_PV,no row with path_num = -1 on 2026-11-27
```

## Schedule it

The same job as the scenario PVs: one scheduled `pvf_run.py` after the extract lands. The cron line and the Windows Task Scheduler recipe are on [the scenario PV page](load-scenario-pv.md#schedule-it); nothing about positions changes them (the positions file is one of the three `--pv`, `--portfolio`, `--positions` arguments, named with the date).

## When a day fails or is late

- A day that failed, was late or has to be restated: run the same command again for that `--date`; the date's records are replaced **whole**. Several days: oldest first, with the loop on [the scenario PV page](load-scenario-pv.md#when-a-day-fails-or-is-late).
- **A position is missing from the lake:** open the audit file above. `MISSING_BASE_PV` (no path -1 for it) and `MISSING_PORTFOLIO` (its portfolio is not in PORTFOLIO_D2 on that date) are the reasons; the source must send it again, then run the day again.
- **A position that should have been removed** is not removed by omission: send the day without it and the date is replaced whole, or delete the date (`lake delete --lake demo --kind scenario-pv --date D`).
- A scheduler reads the exit code: **0** done, **1** refused or failed (one line says why), **2** loaded with rejected rows, **3** another load holds the lake's lock (wait and repeat).

## Check it loaded

```text
Linux     python3.13 tools/drishti.py lake status --lake demo
Windows   py -3.13 tools\drishti.py lake status --lake demo
```

The `risk/scenario-pv` row count is the number of positions written (284 above, from 300 read). With a server: `python3.13 tools/drishti.py dataload status` and the position's page, `POS` in the console (derived positions) or `SPV` for the record with its vector.

## Reference

**What it writes.** The positions are part of `risk/scenario-pv` records (grain `POSITION`; fields `POSITION_ID`, `TRADE_IDS`, `QUANTITY`, `BASE_PV`, `SCENARIO_PV`, the book and the desk) and are never a table of their own. The derived `position` kind is described in its contract ([position](../../../modules/banking/packs/market-risk/contracts/position.contract.yaml): `class: drishti-owned`, derived when a date loads, id `POS-<TT_BOOK>~<security>` or `POS-<TT_BOOK>~<trade>`).
**Input and date rules.** `pos_YYYYMMDD.csv`: `position_id, src_position_id, portfolio_id, currency_cd, portfolio_nm, transit_cd, position_amt, trade_id, tv_product_type, mf1_pnl_type`. The date is the `--date` you type; the file name's digits are a convention. `position_amt` has no sign in the extract (the engine reads the PVs, not the sign).

**A real example** (synthetic, from `pvf_gen.py`):

```text
position_id,src_position_id,portfolio_id,currency_cd,portfolio_nm,transit_cd,position_amt,trade_id,tv_product_type,mf1_pnl_type
200000281,CAL:44373897.CAL:44373897,100010,USD,GLOBAL COMMODITIES-SGP,T3301,31820140.33798409,44373897,Option,StandardBackwardFunding
200000262,CAL:45670558.CAL:45670558,100006,USD,CREDIT FLOW-HKG,T8734,-13500044.957104126,45670558,Future,StandardBackwardFunding
```

**Source and licence.** Your own extract; synthetic samples only in the repository.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| `MISSING_BASE_PV` / `MISSING_PORTFOLIO` in the audit | see above | the source sends the missing row; run the day again |
| *has no usable path rows of DATE* | `--date` differs from the `valid_dt` in the file | use the file's date |
| a position appears twice | the same `position_id` in two extracts of one date | send one file per date |

More: [scenario PVs](load-scenario-pv.md), [BANK_DATA_GUIDE.md](../BANK_DATA_GUIDE.md) (section 1, what each file is) and [VAR_GUIDE.md](../VAR_GUIDE.md) (positions in the VaR).
