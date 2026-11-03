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
# Tutorial 34 · FVA: funding cost and benefit

FVA, the funding valuation adjustment, is what it costs the bank to fund a netting set's trades over their life, net of the funding they
provide: the funding cost (FCA) on the expected exposure and the funding benefit (FBA) on the expected negative exposure, discounted, by
the bank's funding spread. Drishti computes it on the server from the profile the exposure engine already holds for every netting set. In
this tutorial you check the hand-built example by hand, open a netting set's FVA and read its term bars, rank the netting sets by FVA, put
a counterparty's sets side by side, change the choices and watch the figures move as the formulas say, recalculate the playground's
workbook, and name your own funding curve. Each step shows the UI, the command line and the YAML behind it; the reference is the
[FVA guide](../guides/FVA_GUIDE.md).

**About the numbers.** The demo's exposure run is calibrated to history (`REAL_WORLD`), not to market prices, so every demo FVA is flagged
`REAL_WORLD_RUN`: it proves the arithmetic, not a price. The demo's funding curve `CRV-USD-FUNDING` is synthetic and labelled so. The
hand-built example of step 1 is exact.

## Time to complete

About 20 minutes.

## Before you start

- The demo lake with the counterparty-risk pack's exposure paths and the market-data pack's curves (`drishti.py lake demo`).
- The exposure and FVA engines on (Admin → Engines; both are on by default).
- A role that sees the counterparty-risk kinds at trade level (the playground shows each netting set's profile).

## What you will learn

- The formulas FCA = −Σ EE·DF·s_B·Δt, FBA = Σ |ENE|·DF·s_L·Δt, FVA = FCA + FBA, and the one-line EPE reading.
- The three views of the FVA screen and the FVA section of the netting-set page.
- How each choice (the period's point, FBA, survival weighting, the spread) changes FVA.
- How the workbook of live formulas matches the engine to the cent.

## Step 1 · The hand-built example, by hand

**In the UI.** Type `PLAY FVA <GO>` and click **Example workbook**. It is case F1 (b) of the design: EE 0.8, 1.6, 1.1 and 1.3 million and
ENE 0, 0, −0.4 and −0.3 million at 0.25, 0.5, 0.75 and 1 year, a flat 50 bp spread, DF = e^(−0.04 t). Open it: Main reads **ALL CHECKS
PASS**, FCA **−5,846.09**, FBA **845.52**, FVA **−5,000.57**. Check the first term by hand on Terms: DF(0.25) = e^(−0.01) = 0.990050, EE* =
0.8m × 0.990050 = 792,040, the FCA term = −792,040 × 0.005 × 0.25 = **−990.05**.

**From the command line.**

```bash
python3 tools/drishti.py play fva --example --xlsx fva-example.xlsx
```

```text
wrote fva-example.xlsx (… bytes): the FVA Playground workbook, every step a formula, the choices cells of Config, each figure checked against the engine's on Main
```

**The YAML.** Nothing to configure: the example is built into the server (`GET /api/v1/fva/playground/template`).

**You should see** FCA −5,846.09, FBA 845.52 and FVA −5,000.57, every check ✓. Without the discounting (case F1 (a)) the same profile gives
FCA = −0.005 × (0.8 + 1.6 + 1.1 + 1.3) × 0.25 million = −6,000: the spread times the life times the average exposure, the EPE reading.

## Step 2 · A netting set's FVA (view A)

**In the UI.** Type `FVA NSET:NS-ALDERSHOT-FRA <GO>`. The FVA screen opens on view **A, Netting set**: the tiles FVA, FCA (funding cost),
FBA (funding benefit), CVA (planned) and EPE over the life; the term bars by future date, the funding benefit above the line and the cost
below; the formula with the funding curve `CRV-USD-FUNDING` and the discount curve `CRV-USD-OIS`; how it was computed (the run, its
paths, its primary dates). The notice says the run is `REAL_WORLD`. The netting-set page (`NSET NS-ALDERSHOT-FRA <GO>`) shows the same
FVA in its strip and its Funding panel.

**From the command line.**

```bash
python3 tools/drishti.py fva show NSET:NS-ALDERSHOT-FRA
python3 tools/drishti.py fva show NSET:NS-ALDERSHOT-FRA --json     # the one answer: figures, terms, settings, provenance
```

**The YAML.** The engine's switch and the funding spread, in `config/engines/fva.yaml`:

```yaml
drishti:
  engines:
    fva:
      enabled: ${DRISHTI_FVA_ENABLED:true}
  ccr:
    fva:
      funding-spread: CRV-USD-FUNDING
      discount-curve: CRV-USD-OIS
```

**You should see** a negative FVA (a cost: the bank funds the set's positive exposure), FCA ≤ 0 and FBA ≥ 0 with FVA = FCA + FBA, and the
flag `REAL_WORLD_RUN`.

## Step 3 · The netting sets by FVA (view B)

**In the UI.** Click the **Netting sets by FVA** tab, or type `FVA BANK <GO>`. Every netting set with its counterparty, CSA, EPE over the
life, FCA, FBA and FVA, the costliest first, and the total. Click a netting set: its bars appear below. The sets with a CSA sit at the
bottom: collateral received funds the exposure, so their FVA is small.

**From the command line.**

```bash
python3 tools/drishti.py fva sets BANK
```

**You should see** the total equal to the sum of the rows: FVA adds over netting sets (EPE does not, so no EPE total is shown).

## Step 4 · A counterparty's sets side by side (view C)

**In the UI.** Type `FVA CPTY:CP-ALDERSHOT <GO>`: view **C, Counterparty**, its FVA, the largest set and its share, and each netting set's
term bars side by side. Click a set's name to open it in view A.

**From the command line.**

```bash
python3 tools/drishti.py fva show CPTY:CP-ALDERSHOT
```

**You should see** the counterparty's FVA equal to the sum of its sets' (the `counterparties` row of the answer says the same).

## Step 5 · The choices

**In the UI.** Type `PLAY FVA NSET:NS-ALDERSHOT-FRA <GO>`. In the Choices set **FBA** to off: FBA becomes 0 and FVA = FCA. Set the
**Funding spread** to `0.006`: a flat 60 bp, and the EPE reading tile appears (spread × life × EPE over the life). Set **EE point** to
mid, then **Survival weighting** on. Select a cell of the terms: the formula bar says what it is and its value.

**From the command line.**

```bash
python3 tools/drishti.py fva show NSET:NS-ALDERSHOT-FRA --fba off
python3 tools/drishti.py fva show NSET:NS-ALDERSHOT-FRA --spread 0.006
python3 tools/drishti.py play fva NSET:NS-ALDERSHOT-FRA --ee-point mid --survival on
```

**The YAML.** The defaults of the choices:

```yaml
drishti:
  ccr:
    fva:
      ee-point: end
      fba: true
      funding-survival: false
      counterparty-hazard: 0.0
      own-hazard: 0.0
```

**You should see** with a flat spread and no discounting, FCA equal to −spread × Σ EE·Δt; doubling the spread doubles FCA.

## Step 6 · The workbook, recalculated

**In the UI.** In the playground click **Export to Excel**. Open the workbook in Excel or LibreOffice: Main reads ALL CHECKS PASS; each
netting set's FCA, FBA and FVA stand beside the engine's. In Config change **IncludeFba** to off: FVA recomputes, the FBA and FVA checks turn
red (the engine answered the original question) and the add-up check stays green.

**From the command line.**

```bash
python3 tools/drishti.py play fva CPTY:CP-ALDERSHOT --xlsx aldershot-fva.xlsx
```

**You should see** every check ✓ before the change, and the workbook equal to the engine to the cent.

## Step 7 · Your own funding curve

A bank names its own funding curve. Deliver it in the curve kind (`ir-curve` by default) as a document whose points carry `TENOR` and
`SPREAD` (in decimals), and name it:

```yaml
drishti:
  ccr:
    fva:
      funding-spread: CRV-USD-FUNDING-BANK   # read from the ir-curve kind of each date
      lending-spread: ""                     # symmetric funding; or a curve id for FBA alone
```

```bash
python3 tools/drishti.py fva status        # the curve of the newest date and where it was read from
```

**You should see** the source of the curve in `fva status` and in every answer's provenance; a curve found nowhere is priced at the flat
`fallback-spread` and flagged `FUNDING_FALLBACK`.

## Next steps

- The [FVA guide](../guides/FVA_GUIDE.md): every input, choice, flag and error code, the API and the workbook tab by tab.
- The [exposure tutorial](26-exposure-from-the-bank-to-a-trade.md): where the EE and ENE profile comes from.
- [COUNTERPARTY_ENGINES.md](../architecture/COUNTERPARTY_ENGINES.md): FVA beside SA-CCR, the Exposure playground and the CCR cube.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
