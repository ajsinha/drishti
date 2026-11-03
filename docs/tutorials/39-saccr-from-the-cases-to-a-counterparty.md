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
# Tutorial 39 · SA-CCR from the hand-built cases to a counterparty

SA-CCR, the regulator's standard measure of a derivative netting set's exposure at default, computed by Drishti from one classification
row per trade. In this tutorial you read the parameter file, run the hand-built cases (small enough to check with a calculator), open a
demo netting set's EAD waterfall, list the bank's netting sets, put a counterparty's sets side by side, download the workbook of live
formulas and flip a switch in it, and price a new trade's incremental EAD. Each step shows the UI, the command line and the YAML behind
it; the reference is the [SA-CCR guide](../guides/SACCR_GUIDE.md).

## Time to complete

About 20 minutes.

## Before you start

- The demo lake loaded (`python3 tools/drishti.py lake demo`): it holds `counterparty-risk/saccr-trade` (6,527 classification rows of
  2026-09-30), the netting sets, CSAs, collateral balances and the trade valuations.
- An edition with the SACCR word (the Risk edition has it) and a role that sees the counterparty-risk kinds (`credit-risk`).
- The SA-CCR engine on (`drishti.engines.sa-ccr.enabled: true`, as shipped; Admin → Engines shows it running).

## What you will learn

- where the supervisory parameters come from and how each names its Basel paragraph;
- how EAD is built: RC, the add-ons by asset class and hedging set, the multiplier, PFE, alpha and the cap;
- the SA-CCR page (`SACCR`) and its three views over one answer: the waterfall, the netting-set list, a counterparty's sets;
- the playground (`PLAY SACCR`) and its workbook of live formulas;
- the what-if: a new trade's incremental EAD before it is dealt.

## Step 1 · The parameter file

**In the UI.** Type `PLAY SACCR EXAMPLE <GO>`, choose **Export to Excel** and open the **Config** tab: every parameter with its value and
its paragraph (`alpha` 1.4, CRE52.1; `IR.sf` 0.005, CRE52.72; `mpor-floors-days.bilateral` 10, CRE52.50 …).

**From the command line.**

```bash
drishti-console/.venv/bin/python tools/drishti.py saccr parameters
```

**In YAML.** The Basel file ships in the jar (`drishti-compute/src/main/resources/saccr/saccr-bcbs.yaml`); a regulation's own file is named
in `config/engines/sa-ccr.yaml`:

```yaml
drishti:
  ccr:
    saccr:
      parameters-file: ""      # blank: the Basel file; e.g. ./config/saccr/saccr-osfi.yaml once OSFI's is written
```

## Step 2 · The hand-built cases, checked by hand

**In the UI.** Type `SACCR EXAMPLE <GO>`. View **B** lists the cases as netting sets. Select `EX-S1` (one USD payer swap of 100m for five
years, V +1.2m). Check it with a calculator:

- SD = (1 − e^−0.25) / 0.05 = 4.423984, d = 442,398,434, δ = +1, MF = 1, D = 442,398,434;
- AddOn_IR = 0.5 % × 442,398,434 = 2,211,992; RC = 1,200,000; multiplier 1;
- EAD = 1.4 × (1,200,000 + 2,211,992) = **4,776,789**: the figure on the page.

Select `EX-S5`: a margined set where the threshold makes the margined RC 4,500,000, but its unmargined EAD (4,366,244) is lower; the cap
binds and `CAPPED` is shown.

**From the command line.**

```bash
drishti-console/.venv/bin/python tools/drishti.py saccr show EXAMPLE:S5
drishti-console/.venv/bin/python tools/drishti.py saccr show EXAMPLE:S5 --no-cap          # the margined EAD, not the regulatory figure
```

**In YAML.** Nothing to configure: the cases are part of the engine (`SaccrExamples`, COUNTERPARTY_ENGINES.md section 9).

## Step 3 · A demo netting set's waterfall (view A)

**In the UI.** Type `SACCR NS-ALDERSHOT-NY <GO>`. View **A** opens: the tiles (RC 165,687,766; the add-on; the multiplier 1; PFE; EAD
**948,761,509**), the waterfall from RC through each asset class's add-on to RC + PFE and × 1.4 to EAD, the hedging sets with their
sub-keys, and the formula with the set's own figures. The set is margined (a VM CSA, threshold 10m, MTA 0.5m, a 10-day MPOR): its
margined EAD is far below the unmargined 2,469,382,222, so the cap does not bind. The provenance at the bottom names the 204 rows read, the
61 members excluded (securities financing and cash products) and the parameter file's SHA-256.

**From the command line.**

```bash
drishti-console/.venv/bin/python tools/drishti.py saccr show NSET:NS-ALDERSHOT-NY
```

**In YAML.** The set's CSA terms come from the `csa` kind; C from the collateral balance:

```yaml
drishti:
  ccr:
    saccr:
      csa-kind: csa
      collateral-kind: collateral-balance
```

## Step 4 · The bank's netting sets (view B)

**In the UI.** Type `SACCR <GO>` (the bank). View **B** lists the 76 netting sets by EAD: 38 with derivatives, the others marked *no
derivatives* (securities financing only). Choose the **incomplete** filter: `NS-AURORA-LDN` is marked incomplete, one live derivative
(`CLY-3000023`) has no classification row; its EAD leaves it out and says so.

**From the command line.**

```bash
drishti-console/.venv/bin/python tools/drishti.py saccr show BANK
drishti-console/.venv/bin/python tools/drishti.py saccr show NS-AURORA-LDN --strict          # refused: DRS-5411, one live derivative has no classification row
```

**In YAML.** What counts as excluded rather than unclassified:

```yaml
drishti:
  ccr:
    saccr:
      excluded-groups: [Securities financing, Money market, Fixed income]
```

## Step 5 · A counterparty's sets side by side (view C)

**In the UI.** Type `SACCR CPTY CP-ALDERSHOT <GO>`. View **C** shows each of its four sets with its own small waterfall and the
counterparty's EAD on top: **1,271,935,765**, the sum of NS-ALDERSHOT-NY and NS-ALDERSHOT-LDN (its FRA and TKY sets hold only securities
financing). Switch to **A** or **B** with the tabs: the same answer, drawn another way.

**From the command line.**

```bash
drishti-console/.venv/bin/python tools/drishti.py saccr show CPTY:CP-ALDERSHOT
drishti-console/.venv/bin/python tools/drishti.py saccr contributions CPTY:CP-ALDERSHOT --by netting-set
```

## Step 6 · The workbook of live formulas, a switch flipped

**In the UI.** Type `PLAY SACCR NS-ALDERSHOT-LDN <GO>`, click a cell of the grid to read its formula in the formula bar, then **Export to
Excel**. In the workbook, Main's band reads **ALL CHECKS PASS**: every result equals the engine's to the cent. In **Config** set
`ir-bucket-offset` to `off`: the IR add-ons grow (no offset across maturity buckets) and the engine checks turn red, as expected (the
engine answered the original question); the add-up checks stay green.

**From the command line.**

```bash
drishti-console/.venv/bin/python tools/drishti.py play saccr NSET:NS-ALDERSHOT-LDN --xlsx saccr-ldn.xlsx
drishti-console/.venv/bin/python tools/drishti.py saccr show NSET:NS-ALDERSHOT-LDN --no-ir-offset   # the server's figure with the offsets off
```

## Step 7 · A new trade's incremental EAD

**In the UI.** The pre-deal what-if is an API answer today (the CCR cube's pre-deal tab will call it).

**From the command line.** Add a 100m five-year USD receiver swap at market to NS-ALDERSHOT-NY:

```bash
cat > new-swap.json <<'JSON'
{"ASSET_CLASS": "IR", "HEDGING_SET": "USD", "TRANSACTION_TYPE": "LINEAR", "DIRECTION": "SHORT", "NOTIONAL_USD": 100000000,
 "START_DATE": "2026-09-30", "END_DATE": "2031-09-30", "MATURITY_DATE": "2031-09-30", "MTM": 0}
JSON
drishti-console/.venv/bin/python tools/drishti.py saccr what-if NS-ALDERSHOT-NY --candidates new-swap.json
```

The same through the API: `POST /api/v1/saccr/what-if` with `{"nettingSet": "NS-ALDERSHOT-NY", "candidates": [<the row>]}`.

The answer gives EAD, RC, PFE and the multiplier before and after, and each hedging set's add-on before and after: where the set is long USD
rates the receiver swap offsets and the USD add-on falls, and the answer shows by how much. Nothing is stored.

**In YAML.** The token scope: the what-if is a read (`token-read-posts` in application.yaml); a `read` token may ask it.

## What you did

You read the regulator's parameters with their paragraphs, checked two hand-built cases by hand, followed a demo netting set from RC to
EAD, found the incomplete set, summed a counterparty's sets, watched the workbook recalculate with a switch flipped and priced a new trade.
Next: the [SA-CCR guide](../guides/SACCR_GUIDE.md) for every formula, setting and refusal code.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
