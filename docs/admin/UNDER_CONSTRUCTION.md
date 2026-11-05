<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Under construction: what depends on work not built yet

Generated from [`config/under-construction.yaml`](../../config/under-construction.yaml) by `tools/under_construction.py` (do not edit by
hand; `--check` fails when this page is stale or the registry names a place that does not exist).

Some figures, panels, menu items and pages depend on engines or decisions that are not built yet: a figure that is stored rather than
computed, or a contract still to come. The registry lists each one with the release that builds it and how a person meets it until then:

- **under-construction**: shown, with the words *Under construction* (a chip) in place of its value;
- **hidden**: left out.

The data stays where it is; only what is displayed changes. **One mechanism:** the server rewrites a kind's Sutra when it is matched to a
document (strip items and kv or table columns by their bind, panels by their id), so the console, the phone display, pivots and the client
renderer read the same answer (the figure's tone is `uc`, drawn as the chip); the console reads the same file for its menu items and help
pages. A new item that depends on unbuilt work is registered in the same change
([PACK_DEVELOPER_GUIDE.md, Unbuilt work](../guides/PACK_DEVELOPER_GUIDE.md#unbuilt-work-the-under-construction-registry)).

Settings: `drishti.under-construction.file` (server, `DRISHTI_UNDER_CONSTRUCTION_FILE`, default `./config/under-construction.yaml`, else the
file beside the packs folder) and `under_construction.file` (console, default `../config/under-construction.yaml`).


| Item | What | Treatment | Built in | Where it shows |
|---|---|---|---|---|
| **sa-ccr-figures**: SA-CCR exposure at default, replacement cost, add-ons, multiplier and RWA: stored figures of the sa-ccr kind, not computed by an SA-CCR engine | field | under-construction | 2.9.11.k | the `sa-ccr` page: `$.REPLACEMENT_COST`, `$.ADD_ON`, `$.MULTIPLIER`, `$.EAD`, `$.RWA`, panel `addons`, panel `hedging`, panel `checks`; help page `/help/docs-contracts-saccr`; [docs/contracts/SACCR.md](../../docs/contracts/SACCR.md) |
| **cva-figures**: CVA, DVA, FVA, KVA and CS01 with their components and sensitivities: stored figures of the cva kind, not computed by an xVA engine | field | under-construction | the xVA engine (planned) | the `cva` page: `$.CVA`, `$.DVA`, `$.FVA`, `$.KVA`, `$.CS01`, panel `components`, panel `sens`; the `netting-set` page: `$.CVA`; help page `/help/docs-contracts-cva`; [docs/contracts/CVA.md](../../docs/contracts/CVA.md) |
| **credit-limit-usage**: A credit limit's used amount and utilisation as stored, not measured against the exposure engine's figure | field | under-construction | 2.9.11.h | the `credit-limit` page: `$.USED`, `$.UTILISATION`, `@.USED`, `@.UTILISATION`, `$.LIMIT - $.USED`, `$.LIMIT > 0 ? $.USED / $.LIMIT : null`, `$.LIMIT > 0 ? $.USED / $.LIMIT - $.UTILISATION : null`, panel `util` |
| **counterparty-stored-net-mtm**: The counterparty master's stored net MTM: the computed one, from the trade valuations, is shown beside it and stays | field | under-construction | decided by the product owner (computed, or lifted from stored data) | the `counterparty` page: `$.NET_MTM`, `@.NET_MTM`, `$src.NET_MTM - $.NET_MTM` |
| **pre-deal-limit-decision**: The pre-deal limit decision: approve, refer or decline a candidate trade against every limit it touches (the exposure what-if itself is built) | decision | under-construction | 2.9.11.h | [docs/contracts/LIM.md#64-pre-deal-check](../../docs/contracts/LIM.md#64-pre-deal-check) |
| **wwr-contract**: Wrong-way risk: the contract of its inputs and results, planned | contract | under-construction | the wrong-way risk engine (planned) | help page `/help/docs-contracts-wwr`; [docs/contracts/WWR.md](../../docs/contracts/WWR.md) |
| **collateral-contract**: Collateral: the planned contract of the computed collateral figures | contract | under-construction | the collateral engine (planned) | help page `/help/docs-contracts-coll`; [docs/contracts/COLL.md](../../docs/contracts/COLL.md) |
| **margin-call-contract**: Margin calls: the planned contract of the computed margin call | contract | under-construction | the collateral engine (planned) | help page `/help/docs-contracts-mc`; [docs/contracts/MC.md](../../docs/contracts/MC.md) |
