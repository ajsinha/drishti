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
# Drishti World: what macro, research and context users need, what the lens gives them today, and what comes next

**Status: analysis and product description: built parts as built, the rest proposals.** Every "built" or "partly" statement cites the
document, section or file it was read from on 2026-10-31 (develop at b22ff3850). Everything in sections 5 and 8 is a proposal; the rows of
section 8 are candidates in the [implementation plan](../architecture/IMPLEMENTATION_PLAN.md#lens-product-candidates-not-started), not
started.

**The request (product owner, 2026-10-31):** "We should have similar and fuller standalone document for other lenses too", after the
market risk oversight analysis (RISK_OVERSIGHT.md, on branch `wip/risk-oversight`, merging in 2.9.7). This is the World lens's.

**Licensing first.** The IMF figures and the Yahoo prices (and the Google News headlines) in these packs are **internal only**: shown
inside the bank by the product owner's decision of 2026-10-29; any showing outside it needs a decision first
([EDITIONS.md section 12](../architecture/EDITIONS.md#12-drishti-world-built-2026-10-29-for-297); [PUBLIC_DATA.md, The internal-only
rule](../guides/PUBLIC_DATA.md#the-internal-only-rule)). Section 6 lists every source and its terms.

**Read with:** [EDITIONS.md section 12](../architecture/EDITIONS.md#12-drishti-world-built-2026-10-29-for-297) (the lens as built),
[EDITIONS_GUIDE.md section 10](../admin/EDITIONS_GUIDE.md#10-drishti-world) (how to run it), the edition file
[`config/editions/world.yaml`](../../config/editions/world.yaml), [PUBLIC_DATA.md](../guides/PUBLIC_DATA.md) (every external source, its
licence and its refresh script) and the packs' READMEs: [world-economy](../../config/packs/world-economy/README.md),
[news-and-markets](../../config/packs/news-and-markets/README.md), [us-equities](../../config/packs/us-equities/README.md),
[gleif](../../config/packs/gleif/README.md), [funds](../../config/packs/funds/README.md). The sibling lens documents:
[RISK_LENS.md](RISK_LENS.md), [TRADING_LENS.md](TRADING_LENS.md), [MARKET_LENS.md](MARKET_LENS.md).

## Contents

1. [Who uses it](#1-who-uses-it)
2. [Their questions: the morning, intraday, the close, month-end](#2-their-questions-the-morning-intraday-the-close-month-end)
3. [Needs by area, with status](#3-needs-by-area-with-status)
4. [The landings as built](#4-the-landings-as-built)
5. [Gaps, with design sketches](#5-gaps-with-design-sketches)
6. [Data needed, and licensing](#6-data-needed-and-licensing)
7. [Relationship to the other lenses](#7-relationship-to-the-other-lenses)
8. [A roadmap](#8-a-roadmap)
9. [Decisions for the product owner](#9-decisions-for-the-product-owner)
10. [Glossary](#10-glossary)

**Status words.** **Built**: on develop, documented as built. **Partly**: some of it is built; the cell says what is missing.
**Planned (row)**: a row of the implementation plan names it. **Not built**: neither built nor planned; section 5 sketches it and section 8
proposes a row.

---

## 1. Who uses it

Drishti World is "the public-data packs in one product: economies and regions, news and quotes, US companies and filings, legal entities
(GLEIF), funds and ETFs" ([`config/editions/world.yaml`](../../config/editions/world.yaml), its header). None of its packs links into the
bank's own data (core, market-data): it is **context**, not the bank's book.

| Persona | Accountable for | What they need from the lens |
|---|---|---|
| **Economist, macro strategist** (research) | The house view on growth, inflation, rates and fiscal positions; notes for clients and the bank's committees | Economies and groups side by side; forecasts and their vintages; what changed since the last release |
| **Country and sovereign risk analyst** (credit risk) | Country ratings, country limits, the bank's country-risk view | Debt, deficits, growth, governance and elections per country; groups (G7, G20, regions, income groups) |
| **Credit analyst, relationship manager, coverage banker** | A client's or counterparty's profile: financials, filings, ownership, who holds its shares | Company financials and filings (SEC), LEI group and parents (GLEIF), funds that hold it |
| **KYC, onboarding, reference data** | Legal entity identity, parents and ultimate parent, renewals | GLEIF records, relationships, mappings (BIC, ISIN), late renewals |
| **Sales, traders, risk managers wanting context** | Their own markets; World is a side window | Today's news by topic, the market snapshot, an economy's page |

**What they do not get here:** the bank's trades, positions, VaR or exposure (the Risk and Trading lenses); the bank's market data (the
Market lens). World's quotes are a **public snapshot** (internal only), not the bank's marks.

### 1.1 A day, a month and a year

| When | Economist, country analyst | Credit analyst, RM, KYC |
|---|---|---|
| **The morning** | News by topic; overnight market moves; data released since yesterday | Filings since yesterday for covered companies; news on clients |
| **Intraday** | A country page for a call or a note | A company or LEI group for a meeting or an onboarding |
| **Month-end** | Monthly data releases; the country dashboard for the risk committee | Periodic reviews of clients: financials, ownership changes, renewals |
| **Twice a year** | The IMF World Economic Outlook vintages (April, October): what was revised | — |
| **Year** | Country rating reviews; the elections calendar | Annual reviews; 10-K and 20-F season |

---

## 2. Their questions: the morning, intraday, the close, month-end

### 2.1 The morning

| Question | Drishti today | Gap |
|---|---|---|
| *What is the news on my topics?* | **Built**: **Today's news** by topic, each of `home.topics`' latest stories, newest first ([EDITIONS.md section 12](../architecture/EDITIONS.md#12-drishti-world-built-2026-10-29-for-297)); Google News headlines, internal only | Relevance to the bank's clients and holdings (section 5.9) |
| *How did markets do?* | **Partly**: quotes up and down in the **market snapshot** (twenty equity quotes) and the **biggest mover** | **Index levels and FX are not in the snapshot**: the S&P 500 level and USD/CAD were replaced by breadth and the biggest mover (section 5.1) |
| *What changed since yesterday?* | **Partly**: **What changed** shows the latest of each source (the IMF vintage, the filings on the feed's latest day, the latest annual report, the market snapshot, GLEIF on file) | **No change feed**, so no "since yesterday" deltas (sections 5.4 and 5.5) |
| *Any new filings from my companies?* | **Built**: new SEC filings on the feed's latest day (a tile); `EDGAR`, `FILING` | Filtered to *my* coverage list (section 5.10) |

### 2.2 Intraday

| Question | Drishti today | Gap |
|---|---|---|
| *Show me this economy* | **Built**: `ECON`, `MACRO`, `FCST`, `LABR`, `FISC`, `SOCI`, `JUR`, `PARTY`, `OFFC`, `ELEC`; forms on | — |
| *Compare the G7 (or G20, or emerging markets)* | **Partly**: the World map shows G7 and G20 as **member lists** (hard-coded in `drishti-console/web/static/js/world-landing.js`); the pack's groups are World Bank regions, income groups and the World; the income groups stand for emerging markets | G7 and G20 as country groups with GDP-weighted figures like the others (section 5.2) |
| *Which elections are coming up?* | **Not served**: an election's next date is free text (`NEXT_ELECTION`, "by December 2028", [world-economy README](../../config/packs/world-economy/README.md)); the tile was replaced by funds and ETFs | A parsed date window (section 5.3) |
| *Who owns this company? Who is its ultimate parent?* | **Built**: `LEI`, `LEIG`, `LEIR`, `LEIC`, `LEIM`; a company and its LEI record link both ways when us-equities is on ([gleif README](../../config/packs/gleif/README.md)) | Parent changes over time (section 5.6) |
| *Which funds hold this company? Which ETFs track this index?* | **Built**: `HELD`, `TRACK`, `FUND` (the 200 largest US funds and ETFs, newest N-PORT) | Holdings changes between quarters (section 5.7) |
| *What are its financials?* | **Built**: `USEQ`, `FACT`, `FILING`: ten fiscal years from XBRL | — |
| *What is its share price?* | **Partly**: `PXH` read live by an off-by-default connector, internal only; the snapshot's twenty quotes | No prices shipped ([us-equities README](../../config/packs/us-equities/README.md)); licensing (section 6) |

### 2.3 The close and month-end

| Task | Drishti today | Gap |
|---|---|---|
| A country dashboard for a committee | **Partly**: BI datasets `world-economics-economies`, `region-economics-regions` and their reports (`world.yaml` `bi`) | Companies, funds and LEI datasets in BI (section 5.8) |
| Periodic client review: what changed in the profile | **Partly**: the latest of everything | A diff since the last review (section 5.4) |
| The IMF vintage: what was revised | **Partly**: the vintage named (`VINTAGE`); the count of revised economies was replaced by the vintage | Compare two vintages (section 5.6) |
| Refresh the public data | **Built**: one standalone script per pack (`tools/publicdata/*.py` with `.sh` and `.ps1`), `status`, `refresh --dry-run`, `refresh` ([PUBLIC_DATA.md](../guides/PUBLIC_DATA.md#the-scripts)) | A schedule and a freshness card (section 5.11) |

---

## 3. Needs by area, with status

### 3.1 Economies and governments (world-economy)

| Need | Status | Evidence |
|---|---|---|
| About 200 economies: output, growth, prices, jobs, debt, external balance | **Built** (World Bank, IMF, CIA World Factbook) | [world-economy README](../../config/packs/world-economy/README.md) |
| IMF forecasts with their vintage | **Built**, **internal only** (`FCST`, `VINTAGE`) | README `economic-forecast`; sources block `internal-only: true` |
| Country groups (World Bank regions, income groups, the World), GDP-weighted | **Built** (`ECG`, read live by `macro-groupings`) | README `country-group` |
| G7, G20 as groups | **Partly** (member lists in the landing's script) | section 5.2 |
| Regional economies | **Built** but **illustrative** ("Illustrative, not for reporting", `REGE`) | README `region-economy` |
| Governments, parties, office holders, elections | **Built** (Factbook; elections' next date as text) | README `election` |
| Social and governance indicators | **Built** (UNDP HDI, World Bank WGI) | |
| A change feed, revisions between vintages | **Not built** | sections 5.4, 5.6 |

### 3.2 News and markets (news-and-markets)

| Need | Status | Evidence |
|---|---|---|
| Fifteen news topics and twenty listed companies with headlines | **Built**, Google News, **internal only** | [news-and-markets README](../../config/packs/news-and-markets/README.md) |
| Live quotes | **Built**, Yahoo (yfinance connector), **internal only**, off until enabled; offline, a captured snapshot (the owner's choice for the offline demo machine) | same |
| Index levels, FX | **Not in the snapshot** | section 5.1 |

### 3.3 US companies (us-equities)

| Need | Status | Evidence |
|---|---|---|
| S&P 500, Nasdaq-100, Dow 30 and the US, Canadian and UK banks that file with the SEC: filings, ten years of XBRL financials, LEI, SIC sector, index membership | **Built** | [us-equities README](../../config/packs/us-equities/README.md) |
| Index weights or levels, GICS | **Not shipped** | same |
| Prices | **Live only**, off by default, **internal only** (`PXH`) | same |
| Sector changes over time | **Not served** (replaced on the landing) | section 5.6 |

### 3.4 Legal entities (gleif)

| Need | Status | Evidence |
|---|---|---|
| LEI records, groups (ultimate parent and up to 50 consolidated entities), relationships, countries, mappings (BIC, ISIN) | **Built**, CC0 | [gleif README](../../config/packs/gleif/README.md) |
| Late renewals flagged | **Built** | same |
| New entities and parent changes since yesterday | **Not served** (the counts on file instead) | section 5.6 |

### 3.5 Funds and ETFs (funds)

| Need | Status | Evidence |
|---|---|---|
| The 200 largest US funds and ETFs: holdings (CUSIP, ISIN, LEI), derivatives and counterparties, flows, monthly returns, N-CEN facts, prospectus figures | **Built**, public domain | [funds README](../../config/packs/funds/README.md) |
| Who holds a company; which funds track an index | **Built** (`HELD`, `TRACK`) | same |
| Prices or NAV history | **Not shipped** | same |
| Holdings changes between quarters | **Not built** | section 5.7 |

### 3.6 The lens itself

| Need | Status | Evidence |
|---|---|---|
| One product for the five public-data packs, its own landings, BI and help | **Built** | [EDITIONS.md section 12](../architecture/EDITIONS.md#12-drishti-world-built-2026-10-29-for-297) |
| A lens beside the others on a full instance | **Built** (`drishti.lenses: risk,world,market,trading`) | [EDITIONS.md section 8](../architecture/EDITIONS.md#8-lenses) |
| Hiding internal-only sources for an external showing | **Not built** | section 5.12 |

---

## 4. The landings as built

One template, `home/world_landing.html`, with `world-landing.js` and `world-landing.css`, a **teal accent** (deeper on light themes). Every
figure is the packs' own answer through one thin read-only route, `/api/home/search` (`drishti-console/routes/edition_routes.py`: the
server's own search grammar, refused with `404 DRS-4023` for a command the person's edition or lens does not show), and `/api/raw` for
entities; each panel paints when its answer arrives ([EDITIONS.md section 12](../architecture/EDITIONS.md#12-drishti-world-built-2026-10-29-for-297)).

### 4.1 A: World desk (`desk`, the default)

| Tile or card | What is real | Replaced or left out, and why |
|---|---|---|
| **World GDP growth** | the World country group's growth (`ECG-WLD`), GDP-weighted by the pack | — |
| **Quotes up and down**, **biggest mover** | the market snapshot (`QUOTE where PRICE > 0 …`) | In place of the **S&P 500 level** and **USD/CAD**: the snapshot holds twenty equity quotes, no index and no FX |
| **New SEC filings** | the filings on the feed's latest day | "Since yesterday" for my companies: no change feed, no coverage list |
| **Funds and ETFs** (with the largest) | `FUND where NET_ASSETS > 0 …` | In place of **elections in the next 90 days**: the next date is free text |
| **Economies that moved** | the twelve largest economies, this year's growth against the year before, from each one's growth path, largest move first | — |
| **Today's news** | each of `home.topics`' latest stories (`TOPIC-RATES`, `TOPIC-INFLATION`, `TOPIC-OIL`, `TOPIC-FED`, `TOPIC-ECB`, `TOPIC-AI`) | — |
| **Quick cards** | `ECON`, `USEQ`, `LEIG`, `FUND` | — |

### 4.2 B: Explorer (`explorer`)

A big command line; the chips of `home.chips` (`ECON USA`, `ECG ECG-WLD`, `REGE REGE-US-NY`, `USEQ MSFT`, `SIC 3674`, `IDX SP500`,
`LEIC US`, `FUND QQQ`, `TOPIC RATES`, `NEWS rates`, `DATAMAP`); a counts strip (economies, US companies, legal entities, funds and ETFs,
filings, news topics); Recently opened (World's commands only), Knowledge, Data map, Learn.

### 4.3 C: World map (`map`)

| Card | What is real | Replaced or left out, and why |
|---|---|---|
| **The world by group** | by **region**, **income group**, **G7** or **G20**; by **growth**, **inflation** or **debt to GDP**; each group's figures GDP-weighted from its economies' own (`ECON`); the leader marked; each tile opens its group or economy | G7 and G20 are **member lists** in the script (the EU and the African Union, G20 members, are not economies of the pack); "emerging markets" is stood in for by the income groups; a debt aggregate per group is computed, GDP-weighted, from the members' `DEBT_TO_GDP` |
| **US sectors** | the companies' revenue by SIC division | "3 companies changed sector": not served |
| **Largest funds** | net assets, the index each tracks (`TRACK`), holdings | — |
| **What changed** | the IMF outlook's vintage, the filings on the feed's latest day, the latest annual report, the market snapshot, GLEIF on file | New GLEIF entities and parent changes, the IMF's count of revised economies: not served (no change feed) |

### 4.4 What is missing (this document's gaps)

1. Index levels and FX (5.1). 2. G7 and G20 as groups (5.2). 3. Elections as dates (5.3). 4. A change feed (5.4). 5. "Since yesterday" on
the tiles (5.5). 6. GLEIF, sector and IMF revisions (5.6). 7. Fund holdings over quarters (5.7). 8. BI beyond economies (5.8). 9. News
relevance (5.9). 10. A coverage list (5.10). 11. Freshness and a refresh schedule (5.11). 12. An external-showing switch (5.12).

---

## 5. Gaps, with design sketches

Drishti's rules ([COMPUTED_ATTRIBUTES.md section 1.2](../architecture/COMPUTED_ATTRIBUTES.md#12-the-rule-for-deciding-which)) apply to
public data as to the bank's: each source arrives through **one standalone refresh script per pack** (with `.sh` and `.ps1`), as
**JSON Lines** under the test-data folder, built into the lake (house rules: one script per public-data pack, JSONL everywhere); every
document carries `PROVENANCE` (source, licence, retrieval date); **group figures, changes and deltas are computed on demand**, never
stored as copies; BI is the report layer. Sizes: **S** days, **M** one to three weeks, **L** more than three weeks. Ids WL1 to WL12 are
this document's.

| Id | Gap | Size | Depends on |
|---|---|---|---|
| WL1 | FX (official reference rates) and index levels | S | licensing (decision) |
| WL2 | G7 and G20 (and EMDE) as country groups | S | — |
| WL3 | Elections as date windows | S | — |
| WL4 | A change feed from the lake's table versions | M | Delta or Iceberg versions (built) |
| WL5 | "Since yesterday" on the tiles | S | WL4 |
| WL6 | GLEIF new entities and parent changes, sector changes, IMF revisions | S | WL4 |
| WL7 | Fund holdings over quarters | M | WL4; the funds refresh keeping quarters |
| WL8 | BI datasets for companies, funds and entities | S | — |
| WL9 | News relevance to companies, economies and LEIs | S to M | — |
| WL10 | A coverage list ("my companies, my countries") | S | — |
| WL11 | Freshness card and a refresh schedule | S | — |
| WL12 | An external-showing switch | S | decision |

### 5.1 WL1: FX and index levels

**FX.** Openly licensed official rates exist and Drishti already captures them for the market-data pack:
`tools/publicdata/official_market_data.py` writes the **ECB euro reference rates** (twelve pairs including `USDCAD` as a cross through EUR,
with daily history), the US Treasury par curve, SOFR and €STR, "free reuse with the source acknowledged"
([PUBLIC_DATA.md, market-data](../guides/PUBLIC_DATA.md#market-data)). World's packs are allow-only and do not include market-data. The
design: a small World kind (proposed `reference-rate`, in news-and-markets or world-economy, mnemonic to be chosen) filled by the same
parser into World's own lake folder, so the World desk tile reads **USD/CAD 1.3712 (ECB reference, 2026-09-30)**, with the source named,
and the market-data pack stays the bank's. **Size** S.

**Index levels.** No openly licensed daily level exists for the S&P 500 or the S&P/TSX: the index owners license them (vendors and
FRED's S&P series carry the owner's terms). Options: (a) add the index tickers (`^GSPC`, `^GSPTSE`) to the Yahoo snapshot, **internal
only** like its quotes (the snapshot is the owner's choice for the offline demo, [news-and-markets README](../../config/packs/news-and-markets/README.md));
(b) show the tracking ETF's quote (SPY) as "S&P 500 via SPY", also Yahoo, internal only; (c) leave levels out (today). Recommended: (a),
labelled internal only, and nothing outside the bank. **Size** S. **Tests:** the tile names its source and date; with the snapshot lacking
the ticker, the tile falls back to breadth, as today.

### 5.2 WL2: G7 and G20 as country groups

**Today** the World map holds two lists in its script. **Design:** membership as reference data of the world-economy pack (a small JSONL
under its test data: `GROUP_ID`, `NAME`, `MEMBERS[]` ISO3, `SOURCE`, `AS_OF`; G7 and G20 are public facts), served by the
`macro-groupings` connector as `ECG-G7` and `ECG-G20` with the same GDP-weighted figures as the World Bank groups; the G20's **EU and
African Union** members recorded as non-economy members (named, not summed). **Emerging markets**: the IMF's "emerging market and
developing economies" list is IMF data (internal only); the World Bank income groups (CC BY) stay the public stand-in. The landing's
lists are removed and the map reads the groups. **Size** S. **Tests:** `ECG ECG-G7` lists seven economies and its GDP-weighted growth
equals the landing's figure today; the EU is named, not counted.

### 5.3 WL3: elections as date windows

`NEXT_ELECTION` is text as the Factbook publishes it ("by December 2028", "2027", "November 2026"). **Design:** the world-economy refresh
script parses it into `NEXT_FROM`, `NEXT_TO` and `NEXT_PRECISION` (`day`, `month`, `year`, `by-month`, `unknown`), keeps the text, and
records the parse in `PROVENANCE`; the tile **Elections in the next 90 days** lists elections whose window overlaps the next 90 days,
sorted by `NEXT_FROM`, each showing the published text, and counts the `unknown` ones separately. A derived field, not a guess: an
unparseable text is never placed in a window. **Size** S. **Tests:** a table of Factbook phrasings and their windows; "unknown" never
appears in the 90-day list.

### 5.4 WL4: a change feed from the lake's table versions

**The insight.** Every refresh writes a new **table version** of each kind (Delta and Iceberg both keep versions and time travel). A change
feed needs no new storage: compare the latest version with an earlier one.

**Design.** A computed answer (proposed `GET /api/v1/changes?kind=&since=`, and a command form chosen at build) that, per kind, diffs two
versions by key: **added**, **removed**, **changed** (field, old, new), with each version's commit time and the refresh's `RETRIEVED_AT`;
`since` is a date (the newest version at or before it) or `last-refresh`. Field-level rules per kind decide what counts as a change worth
showing (a GLEIF `PARENT` change, a us-equities `SIC`, a forecast figure; not a re-fetched timestamp). Retention follows the lake's
version retention (vacuum and snapshot expiry settings): the feed says how far back it can see. **Size** M. **Tests:** two crafted versions
give exact adds, removes and changes; a field excluded by the rules never appears; asking before the oldest kept version says so.

### 5.5 WL5: "since yesterday" on the tiles

From WL4, each tile gains a change line **only where the source refreshes at that cadence**: filings (daily), GLEIF (daily), news
(continuous), the snapshot (each capture); macro sources (World Bank monthly at best, IMF twice a year, Factbook irregular) show "since the
last release" with its date, never a fake daily change. **Size** S after WL4.

### 5.6 WL6: GLEIF, sectors and IMF revisions

All from WL4's diffs, as cards on **What changed**: GLEIF **new entities** and **parent changes** in covered groups; us-equities **sector
changes** (`SIC`) and index joins and leaves; the IMF outlook: **economies revised** between the two latest vintages (growth, inflation,
debt), with the size of each revision, internal only. **Size** S after WL4.

### 5.7 WL7: fund holdings over quarters

The funds pack keeps the **newest** N-PORT per fund. **Design:** keep each quarter's holdings as a table version (WL4) or a dated partition
(`REPORT_DATE`); a computed answer per company: the funds that **bought**, **sold**, **entered** or **exited** it between two quarters,
with shares and value; per fund, its largest changes. Public domain data; the downloads are large (about 450 MB a quarter, PUBLIC_DATA.md
funds section): kept in the data folder. **Size** M.

### 5.8 WL8: BI beyond economies

World's BI offers two datasets today (`world.yaml` `bi`). **Design:** datasets for US companies (financial facts by year), funds (holdings
by fund and issuer), GLEIF (entities by country and parent) and their models, added to the edition's lists. **Size** S.

### 5.9 WL9: news relevance

Topics exist for fifteen themes and twenty companies. **Design:** a topic per covered company or economy generated from the coverage list
(WL10) and the us-equities and GLEIF names (search terms from the legal name and ticker); a **News on my companies** card. Google News stays
internal only. **Size** S to M.

### 5.10 WL10: a coverage list

A person's list of companies, economies and LEI groups (a workspace-scoped setting, like `tradingView` in the Trading lens), used to filter
the desk's filings, news and changes. It never widens access. **Size** S.

### 5.11 WL11: freshness and a refresh schedule

A **Freshness** card listing each source, its last refresh (the scripts' `status`), its cadence and its licence; the refresh scripts run on
a schedule on the server (operations configuration, one job per pack, never one combined script), each run recorded. **Size** S.

### 5.12 WL12: an external-showing switch

**Today** an external showing needs a decision, and the documented way is to rebuild without the internal-only sources
(`world_economy.py refresh --no-imf`, [PUBLIC_DATA.md](../guides/PUBLIC_DATA.md#the-internal-only-rule)). **Design:** a site setting
(proposed `world.external: true`) that hides every document and field whose source is `internal-only: true` in the pack's README front
matter (the IMF forecasts and fiscal paths, Yahoo quotes and prices, Google News), shows a banner, and refuses the hidden commands with
their reason; the landing's tiles fall back to the public figures. A switch never replaces the legal decision. **Size** S. **Tests:** with
the switch, no IMF, Yahoo or Google field reaches any answer; the tiles still paint.

---

## 6. Data needed, and licensing

| Source | Pack | Licence | Internal only | Used for | Cadence |
|---|---|---|---|---|---|
| World Bank WDI and WGI | world-economy | CC BY 4.0 | no | economies, indicators, labour, regions, governance | monthly to yearly |
| **IMF World Economic Outlook** | world-economy | reuse with attribution; **commercial use needs the IMF's permission** | **yes** | forecasts; debt and net lending of fiscal positions | twice a year (April, October) |
| CIA World Factbook | world-economy | public domain | no | governments, office holders, elections, parties | irregular |
| UNDP HDI | world-economy | CC BY 3.0 IGO | no | social indicators | yearly |
| **Google News RSS** | news-and-markets | headlines belong to their publishers; no redistribution | **yes** | news | continuous |
| **Yahoo Finance** | news-and-markets, us-equities | unofficial endpoint; terms forbid redistribution | **yes** | quotes, the snapshot, price history | live; the snapshot by capture |
| SEC EDGAR, N-PORT, N-CEN, prospectus data | us-equities, funds | public domain; EDGAR fair access | no | filings, XBRL facts, funds | daily (filings); quarterly (N-PORT) |
| Wikidata, Nasdaq Trader | us-equities, funds | CC0; public directory | no | company facts, tickers | on refresh |
| GLEIF | gleif, us-equities, funds | CC0 | no | LEIs, relationships, mappings | daily |
| ECB reference FX rates (WL1) | proposed for World | free reuse with the source acknowledged | no | FX | daily |
| Index levels (WL1) | Yahoo snapshot, if chosen | the index owner's terms | **yes** | index levels | by capture |
| G7, G20 membership (WL2) | world-economy | public fact | no | groups | on change |

From [PUBLIC_DATA.md, every external source at a glance](../guides/PUBLIC_DATA.md#every-external-source-at-a-glance) and the packs'
README front matter (`sources:`). **No position sensitivities or bank data** are needed by World: it is public context. Its link to the
bank's book (a counterparty's economy, a counterparty's LEI group) lives in the bank's packs and lenses (section 7).

**Licensing notes for the product owner.** (1) The committed IMF forecasts and the committed Yahoo and Google snapshot were the owner's
choice for internal demos; before any external or client use, rebuild without them or get permission (PUBLIC_DATA.md). (2) Nothing World
shows from Yahoo may be redistributed, including screenshots in external decks. (3) SEC data is public, but EDGAR's fair access policy
limits request rates: the refresh scripts honour it.

---

## 7. Relationship to the other lenses

| Lens | What World shares with it | What World deliberately hides | When a World user should switch |
|---|---|---|---|
| **Drishti Risk** ([RISK_LENS.md](RISK_LENS.md)) | Nothing loaded in common; a core counterparty links to its country's economy in the full product ([world-economy README](../../config/packs/world-economy/README.md)) | `VAR`, `EXP` and every risk command (`world.yaml`) | For the bank's exposure to a country or a group, once country concentration exists (RO8 of the oversight analysis) |
| **Drishti Trading** ([TRADING_LENS.md](TRADING_LENS.md)) | Nothing | Trades and books | For a trader, World is a side window; Trading leaves macro out by design |
| **Drishti Market** ([MARKET_LENS.md](MARKET_LENS.md)) | Nothing loaded in common; the official public FX and rates captured for market-data (WL1 reuses the parser) | The bank's market data | When a public FX or rate differs from the bank's mark: the bank's is in Market |
| **Drishti Oversight** (proposed) | — | — | — |

**Context into the bank's lenses (a proposal, not a World row).** The highest value of World's data inside the bank is **next to the
book**: a counterparty's page in Risk showing its economy's growth and debt, its LEI group's parent and its latest filing. That is a
read-only context panel on the counterparty page of the full product, not a World landing, and it respects World's licensing (internal
only). It belongs to the Risk lens's roadmap (RISK_LENS.md, RL10).

**Embedding.** An entity page (`ECON CAN`, `LEIG …`) can be shown on an intranet page by the client renderer, with the optional sign-in
window for a page without a backend (branch `wip/renderer-2a`, merging in 2.9.7; [CLIENT_RENDERER.md section 10.1](../architecture/CLIENT_RENDERER.md#101-signing-in-on-another-site-the-token-exchange-and-an-optional-sign-in-window-design-product-owner-2026-10-30));
the World landings themselves are not Sutras and cannot be embedded until a "home" Sutra kind exists
([EDITIONS.md section 7](../architecture/EDITIONS.md#7-drishti-risks-home-three-landings)). Internal-only data stays internal on any host.

---

## 8. A roadmap

The rows below are added to the plan's [Lens product candidates (not started)](../architecture/IMPLEMENTATION_PLAN.md#lens-product-candidates-not-started)
section.

| Order | Row | Why here | Size | Depends on |
|---|---|---|---|---|
| 1 | **WL2** G7 and G20 as groups | Removes lists from a script; a clean data fix | S | — |
| 2 | **WL3** elections as date windows | Restores the mock-up's tile honestly | S | — |
| 3 | **WL1** FX (ECB) and index levels | The two tiles the mock-up wanted | S | decision 1 |
| 4 | **WL4** the change feed | Unlocks WL5, WL6, WL7 | M | — |
| 5 | **WL5**, **WL6** since yesterday; GLEIF, sectors, IMF revisions | The "what changed" questions | S each | WL4 |
| 6 | **WL12** external-showing switch | Before any external demo | S | decision 2 |
| 7 | **WL10** coverage list; **WL9** news relevance | Personalises the desk | S; S to M | — |
| 8 | **WL8** BI datasets; **WL11** freshness and schedule | Reports and operations | S each | — |
| 9 | **WL7** fund holdings over quarters | Large downloads | M | WL4 |

---

## 9. Decisions for the product owner

1. **Index levels (WL1).** Add index tickers to the Yahoo snapshot, internal only (recommended), show SPY as a stand-in, or leave levels out.
2. **External showing (WL12).** A switch that hides internal-only sources, or a separate build without them (`--no-imf`), before any client
   demo; either way the legal decision comes first.
3. **Where official FX lives (WL1).** A small World kind fed by the same parser (recommended: World stays independent of the bank's
   market data), or let World read the market-data pack's public documents.
4. **Emerging markets (WL2).** The World Bank income groups as the public stand-in (recommended), or the IMF's list, internal only.
5. **Change-feed retention (WL4).** How many table versions the public lakes keep (it decides how far back "what changed" can see).
6. **Context in the bank's lenses.** A World context panel on the counterparty page in Risk (recommended, internal only), or keep World
   fully separate.
7. **Fund quarters (WL7).** Keep each quarter's N-PORT (about 450 MB a quarter in the data folder), or newest only.

---

## 10. Glossary

| Term | In plain words |
|---|---|
| **Public data** | Data published by governments, international bodies or open registries, under their own terms |
| **Internal only** | May be shown inside the bank, not to clients or the public, because its terms forbid redistribution or commercial use |
| **Vintage** | One edition of a forecast (the IMF's April or October World Economic Outlook) |
| **Country group** | A set of economies with figures computed for the whole (a region, an income group, the G7) |
| **GDP-weighted** | An average in which bigger economies count more, in proportion to their output |
| **Debt to GDP** | Government debt as a share of a year's output |
| **G7, G20** | Forums of the largest advanced economies (7) and of major economies (19 countries, the EU and the African Union) |
| **Emerging markets** | Economies between low and high income; here stood in for by the World Bank income groups |
| **LEI** | A Legal Entity Identifier: a 20-character code for a legal entity, from GLEIF |
| **Ultimate parent** | The top company in an ownership chain |
| **SIC** | Standard Industrial Classification: the SEC's sector codes |
| **XBRL** | The machine-readable format of financial statements in SEC filings |
| **N-PORT** | A US fund's monthly portfolio report to the SEC, published quarterly |
| **Snapshot** | A captured copy of live data (quotes, headlines) kept for an offline demo |
| **Table version** | Each change to a lake table is a new version; older versions can be read (time travel) |
| **Change feed** | The list of what was added, removed or changed between two versions |
| **Lens** | One person's focused view of Drishti, opened by a link; it hides, it does not secure |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
