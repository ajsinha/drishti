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
# Samples

**Synthetic.** Made up for trying the scripts; not market data and not anyone's extract (the owner's real files live in the git-ignored
`data/pvf/`). Business date 2026-11-18, 251 scenario days.

| File | What |
|---|---|
| `scenario_dates_2026-11-18.txt` | the 251 weekdays before 2026-11-18, oldest first, one per line (`pvf_scenario_set.py --dates`) |
| `fx_2026-11-18.csv` | scenario FX, **wide** form: EUR, GBP, JPY, CAD, AUD, CHF; `RATE_TODAY` and one column per scenario day |
| `fx_long_2026-11-18.csv` | the same rates in **long** form: `CURRENCY,SCENARIO_DATE,RATE`, today's rate on the row dated 2026-11-18 |

```bash
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates tools/mrloader/samples/scenario_dates_2026-11-18.txt
python3 tools/mrloader/pvf_fx_scenario.py  --date 2026-11-18 --fx tools/mrloader/samples/fx_2026-11-18.csv --dates tools/mrloader/samples/scenario_dates_2026-11-18.txt --scenario-set-id HS-PVF-251
```
