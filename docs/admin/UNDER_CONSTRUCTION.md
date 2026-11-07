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
| **credit-limit-usage**: A credit limit's used amount and utilisation as stored, not measured against the exposure engine's figure | field | under-construction | 2.9.11.k | the `credit-limit` page: `$.USED`, `$.UTILISATION`, `@.USED`, `@.UTILISATION`, `$.LIMIT - $.USED`, `$.LIMIT > 0 ? $.USED / $.LIMIT : null`, `$.LIMIT > 0 ? $.USED / $.LIMIT - $.UTILISATION : null`, panel `util` |
| **counterparty-stored-net-mtm**: The counterparty master's stored net MTM: the computed one, from the trade valuations, is shown beside it and stays | field | under-construction | decided by the product owner (computed, or lifted from stored data) | the `counterparty` page: `$.NET_MTM`, `@.NET_MTM`, `$src.NET_MTM - $.NET_MTM` |
| **pre-deal-limit-decision**: The pre-deal limit decision: approve, refer or decline a candidate trade against every limit it touches (the exposure what-if itself is built) | decision | under-construction | 2.9.11.k | [docs/contracts/LIM.md#64-pre-deal-check](../../docs/contracts/LIM.md#64-pre-deal-check) |
| **pnl-explain-waterfall**: The P&L explain's waterfall: a generator's stored steps that disagree with the Attribution bars on new trades (F19); the computed explain (by trade events, then by risk factor) is PNL.md's | panel | under-construction | the P&L explain (TRADING_LENS.md TL4, PNL.md) | the `pnl-explain` page: panel `explain`; [docs/contracts/PNL.md#1-summary](../../docs/contracts/PNL.md#1-summary) |
| **wwr-contract**: Wrong-way risk: the contract of its inputs and results, planned | contract | under-construction | the wrong-way risk engine (planned) | help page `/help/docs-contracts-wwr`; [docs/contracts/WWR.md](../../docs/contracts/WWR.md) |
| **collateral-contract**: Collateral: the planned contract of the computed collateral figures | contract | under-construction | the collateral engine (planned) | help page `/help/docs-contracts-coll`; [docs/contracts/COLL.md](../../docs/contracts/COLL.md) |
| **margin-call-contract**: Margin calls: the planned contract of the computed margin call | contract | under-construction | the collateral engine (planned) | help page `/help/docs-contracts-mc`; [docs/contracts/MC.md](../../docs/contracts/MC.md) |
| **flight-cluster-end-to-end**: Arrow Flight on a three-node cluster, end to end (endpoints on every node, a node stopped mid-read, a VaR stream through an intraday batch): not measured yet; the single-node reads, the stream, Stop and resume are run (tutorial 35) | test | under-construction | a later heavy run (needs a three-node scratch cluster) | [docs/guides/FLIGHT_GUIDE.md#13-testing](../../docs/guides/FLIGHT_GUIDE.md#13-testing) |
| **ccr-cube-preset**: The CCR cube, the Pivot's preset ccr (Counterparties → CCR cube, /pivot?preset=ccr): the preset, its door and its landing are defined; its source, the CCR provider (each netting set's exposure paths netted and collateralised first, then summed per cell), comes with the counterparty engines | menu item | under-construction | 2.9.14 | menu item `ccr-cube`; [docs/guides/PIVOT_GUIDE.md#28-presets-the-risk-cubes](../../docs/guides/PIVOT_GUIDE.md#28-presets-the-risk-cubes); [docs/architecture/PIVOT_ENGINE.md#65-the-ccr-provider-later](../../docs/architecture/PIVOT_ENGINE.md#65-the-ccr-provider-later) |
| **approvals-access-kinds**: The Approvals queue's access kinds: users and roles, API keys, captures, pack changes and admin configuration as four-eyes items (today each takes effect at once, by an administrator, audited); the queue and its API take a new kind as one more ApprovalSource | decision | under-construction | 2.9.11.h | [docs/guides/USER_GUIDE.md#approvals](../../docs/guides/USER_GUIDE.md#approvals); [docs/architecture/SHARED_SCREENS.md#2-one-approvals-queue](../../docs/architecture/SHARED_SCREENS.md#2-one-approvals-queue) |
| **approvals-effect-on-risk**: A correction's effect if approved, in risk numbers: a scope's VaR and limit use before and after (the mock-up's example); today the effect is the impact service's list of what reads the record downstream | decision | under-construction | 2.9.11.j | [docs/guides/USER_GUIDE.md#approvals](../../docs/guides/USER_GUIDE.md#approvals) |
| **platform-alert-lifecycle-shared**: A platform alert's acknowledgement, owner and snooze are kept in the memory of the member it opened on: a restart forgets them (the alert itself opens again if it still holds), and another member of a cluster does not see them | decision | under-construction | 2.9.11.h | [docs/guides/USER_GUIDE.md#alerts](../../docs/guides/USER_GUIDE.md#alerts) |
