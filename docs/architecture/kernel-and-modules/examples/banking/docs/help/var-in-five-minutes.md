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

  Status: design, not built. An EXAMPLE of a Help page a module ships (docs/help/ inside banking-3.0.0.dmod), listed by the module's
  console/help.yaml and merged into the Help centre while the module is loaded and the reader's lens uses it
  (docs/architecture/KERNEL_AND_MODULES.md section 4.7). {product} is replaced by the distribution's brand.
-->

# VaR in five minutes

*Risk measures · for everyone who opens a VaR figure in {product}*

**Value at risk (VaR)** answers one question: *how much could this portfolio lose in a day, in all but the worst 1% of the days we have
seen?* {product} computes it from the trades' P&L under each historical scenario day, for any scope you pick: the bank, a desk, a book, a
counterparty, a basket you typed.

## Open it

| Where | What to do |
|---|---|
| Command line | type `VAR BANK` and press GO, or `VAR DESK RATES` |
| Home (Risk lens) | the card *VaR of the bank* |
| Any desk, book or position page | the VaR strip at the top; press **E** on a figure to see how it was calculated |

## Read it

- **VaR 99% 1D**: the loss exceeded on only 1% of the scenario days.
- **ES 97.5%**: the average loss on the worst 2.5% of days (the regulators' measure).
- **Component VaR**: each part's share; the components add up exactly to the whole. The VaRs of the parts do not: diversification.
- **The window**: 509 or 250 scenario days; the screen says which.

## When a number looks wrong

Press **E** on it: the Explain drawer shows the formula, the inputs with their lineage and *why it moved* since yesterday, step by step.
If the strip says **DEMO DATA**, your administrator has not yet bound the bank's own scenario PVs or P&L vectors: they will see it in
Admin → Data bindings.

## Learn more

The VaR guide and the VaR tutorial, from the bank to a trade, in the Risk measures section of Help.
