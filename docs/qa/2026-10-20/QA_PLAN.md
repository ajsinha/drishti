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
# Adversarial QA of 2.6.5 (2026-10-20)

A six-hour, time-boxed run (product owner, 2026-10-20): break what 2.5.0, 2.6.0 and 2.6.5 added, as the people who will use it every
day would, fix what is found, verify and fix the documentation, and prove the fixes with a regression and a re-test. Testers only
report; fixes are made by fix builders after triage. Only after the exit criteria of section 8 hold does 2.7.0 start.

## 1. Schedule (about 09:30 to 15:30)

| When | What | Who |
|---|---|---|
| 09:30 → 11:30 | **Round 1**: adversarial QA, two testers in parallel, each on their own stack | QA-1 (trader and risk), QA-2 (BI author and administrator) |
| 11:30 → 11:45 | **Triage**: every finding classed P1 / P2 / P3, grouped into fix lanes; duplicates merged; the product owner sees the summary | lead |
| 11:45 → 14:00 | **Fix run**: two fix builders, one per lane (server and connectors / console, CLI and docs), each fix with a regression test and its docs | Fix-1, Fix-2 |
| 11:45 → 12:15 | **Documentation verification** (30 minutes, section 6), in parallel with the fixes; wrong pages become doc fixes for Fix-2 | Docs reviewer |
| 12:15 → 13:30 | **Quiet window for the product owner's demo** on the user's cluster: builders keep coding, but no browser, server or full test run starts | all |
| 13:30 → 14:30 | **Regression**: every fix's own test, the touched suites, the browser files of the touched pages one at a time | Fix-1, Fix-2 |
| 14:30 → 15:15 | **Round 2**: the testers re-run every P1 and P2 of round 1 and the areas next to each fix | QA-1, QA-2 |
| 15:15 → 15:30 | **Close**: one drill, then 2.6.6 if anything was fixed, and the report to the product owner | lead |

Anything found in round 2 that is P1 is fixed before the drill; P2 and P3 from round 2 are listed for 2.7.0's first item.

## 2. Rules for the testers

- **Own stack, never the user's.** Each tester starts its own server(s) and console on its own ports (QA-1: 18610/17610 and a peer on
  18611; QA-2: 18620/17620), its own data folder under its scratchpad, built from the release jar of 2.6.5 and `drishti.py lake demo`
  (seven business days) plus a 60-day synthetic lake from `tools/bench/trades.py` (at most 1,500 trades a day; never more than 10K
  trades). Never 18480/17480/18791/18792, `drishti-stack/run` or `data/stack`. Members advertise the machine's real address.
- **One heavy process each:** one browser at a time, one server build at a time. Heap at most 8 GB. Testcontainers' own containers
  only; no other Docker. Stop processes by PID only.
- **Synthetic data only.** Never the real ENDUR files of `~/Downloads`.
- **No code changes.** A tester writes findings, screenshots and the exact steps; it does not fix or commit product code.
- **Adversarial means:** wrong input, edge dates, empty data, huge ranges, permissions and masks, two tabs, the keyboard only, phone
  width, every theme, a node stopped mid-way, a connector restarting, slow answers, copy-paste of what the docs say.

## 3. Finding format

Each finding: **id** (QA1-nn / QA2-nn), **severity**, **area**, **steps** (numbered, from a fresh sign-in), **expected**, **actual**,
**evidence** (screenshot path, response body, log line), **doc** (the page that says otherwise, if any).

| Severity | Meaning |
|---|---|
| **P1** | wrong data, a mask that leaks, data loss, a crash or a page that cannot be used, a security hole |
| **P2** | a feature does not work as documented, a dead end in a workflow, a misleading message, a broken key or theme |
| **P3** | polish: wording, alignment, a slow but correct answer, a missing hint |

## 4. QA-1: trader and risk analyst (history, command line, cluster)

### 4.1 History queries (2.6.5), the main target

| # | Try to break | Expect |
|---|---|---|
| H-1 | Every range form: `TRD <id> 1`, `30`, `30D`, `2W`, `3M`, `1Y`, `YTD`, `MTD`, `QTD`, `FROM a TO b`, `FROM a` alone, `DIFF` after each, lower case, extra spaces | each opens the right window; the header states the dates |
| H-2 | Bad ranges: `0`, `-5`, `99999`, `FROM` after `TO`, `FROM 2026-02-30`, `FROM yesterday`, `3X`, `30 30`, `DIFF DIFF` | a clear DRS message (DRS-4021 / 4022 / 4001), never a stack trace or a blank page |
| H-3 | Ids that look like ranges: a numeric id (`TRD 1000015 30`), an id containing a space, an alias with a trailing word (`MYBOOK 30`), a bare id with a range, a pick list (`TRD MX-2 30`) | the range is never eaten as the id and vice versa; the pick list carries the range |
| H-4 | Kinds: trade, counterparty, netting set, curve, equity, FX, index, bond, economy, a derived kind (counterparty-trades), a kind with no daily history | document or market shape as the pack says; derived kinds and undated kinds answer with the documented message |
| H-5 | Data edges: a trade that does not exist, one cancelled mid-window, one with no change at all, an entity only on a peer, a window entirely before the lake, a window after the newest day, a day missing in the middle | rows, *missing*, *not loaded yet*, *changes only* all truthful |
| H-6 | Market shape: `EQ EQ-CSCA 3M`, `1M`, `1Y`; the series fill before the first stored day; summary numbers against the table (high, low, change %, average by hand for one case) | numbers match the rows; the chart matches the table; no duplicated day |
| H-7 | Masks and rights: a user without the trading pack, a user who cannot see TRADER_ID or MTM; the same through export, DIFF, the link, the CLI and `GET /api/v1/history` | masked on every row, never "changed" because of a mask, never in a file or a diff |
| H-8 | Keys only: ↑↓ j k Home End PgUp PgDn ←→ Enter C D E R ? Esc Alt+S, `1 3 6 Y T` on market pages | every key does what the keys card says; focus never lost |
| H-9 | Export: CSV, Excel, JSON of a 60-day trade and a 3M equity; open the Excel; compare with the page | same rows, same order, masks kept, numbers not as text |
| H-10 | Links: copy the link, open in another browser signed in as another user; the short form `/v/trade/<id>?days=30` | same view within that user's rights; the redirect works |
| H-11 | Plain words: "trade 1000015 last month", "what changed on trade X this week", "equity EQ-CSCA last 3 months", "BOOK-RATES-3 last week" | becomes the documented command, or stays a search |
| H-12 | CLI: `drishti.py history trade <id> --days 30`, `--diff`, `--changes-only`, `--csv`, `--json`, a bad token, a bad kind | same answer as the page; clear errors |
| H-13 | Themes and widths: all ten themes, 375 px phone width, 1280 px laptop, zoom 200 % | readable, no overlap, the chart redraws |
| H-14 | Concurrency and cache: two tabs on the same history while a load adds a day (`lake build` of one more day on the QA lake) | the newer day appears after the load; never a mixed answer |
| H-15 | Speed: 60-day trade cold and warm, 1Y equity, `max-days` exceeded | within the documented targets; DRS-4021 past the limit |

### 4.2 Command line and pages around history

- The type-ahead after an id offers the ranges; Tab and Enter; command history (↑) brings back a range command; an alias saved with a
  range.
- *Compare* from the DIFF view; *Open the day* pins the date; *Go live* returns to today.

### 4.3 Drishti*net (2.5.0 and 2.6.0 rules)

- A two-node QA cluster: `advertise-url` with 127.0.0.1, localhost or 0.0.0.0 is refused by the server, Admin → Net → Files and
  `drishti.py net`; without it the node advertises the real address.
- History of an entity held only on the peer; the peer stopped mid-request; the peer back.
- Last administrator: disable the last admin on one node while demoting another on the second node at the same moment.

## 5. QA-2: BI author and administrator (statements, field parameters, themes, connectors, admin)

| # | Try to break | Expect |
|---|---|---|
| B-1 | Statements: a statement page with 0 rows, 1 row, 5,000 rows; a group bigger than a page; header/footer tokens (`{page}`, `{pages}`, `{param.x}`); every paper size and landscape; margins at the limits | page-exact PDF, titles repeated, groups kept together, a clear message past the page limit |
| B-2 | Statement bursting: a subscription to three users with different masks and row rules; one user without access | each PDF masked as its recipient; none sent to the one without access |
| B-3 | Statement endpoints with an API token (the 2.6.5 fix): `POST …/statement/preview` and `/file` | allowed as read-only; nothing stored |
| B-4 | Field parameters: a slicer switching fields and measures; a parameter in the wrong well; a what-if parameter in a well; a report over a dataset | switches, or DRS-9037 with a clear message |
| B-5 | Themes: the three Terminal themes on BI Studio, the designer, the statement Paper tab, Admin pages, dialogs, the phone; theme persists after reload and on another device | readable everywhere; buttons fill with `--d-fill`; Crimson never blood-red text |
| B-6 | Iceberg without Hadoop: `tools/load-iceberg.sh` with `--engine native` into a QA folder; serve with `engine: native`; `engine: library`; `iceberg-external` on a table written by pyiceberg; history of an Iceberg-held trade | loads and reads with no Hadoop class; history works on Iceberg |
| B-7 | Cassandra: the connector against Cassandra 5 in Testcontainers with a small QA load; a reload while reading; history on Cassandra | a day appears whole; history days from `entity_days` |
| B-8 | Admin: Connectors, Health, Lakes, Packs (history block lint: a bad `history:` block in a copied pack gives DRS-2061 at deploy), Tokens, Net | each page truthful; the bad block refused with a clear message |
| B-9 | Server start with no Cassandra or MongoDB anywhere (the 2.6.0 start-up fix) | starts; no attempt to reach 127.0.0.1:9042 or :27017 |
| B-10 | Upgrades: a data folder from 2.5.0 (connector files written by an older server) started on 2.6.5 | starts; any stale file is named in Health with what to do |

## 6. Documentation verification (30 minutes, during the fix run)

One reviewer, one table in `docs/qa/2026-10-20/DOCS_VERIFICATION.md`, in the format of `docs/qa/2.3.0-docs-verification.md`: every
document touched since `v2.5.0` (`git log --name-only v2.5.0..v2.6.5 -- docs README.md`), with outcome, what was checked and how to
re-check. Priorities, in order:

1. The new and rewritten pages: HISTORY_QUERIES, USER_GUIDE (history, command line, plain words, themes), CLI_GUIDE (history, net),
   API_GUIDE (history, statement, net/history), STATEMENTS, REPORTS 21–22, tutorials 19, 20, 21, NET, CASSANDRA_CONNECTOR,
   ICEBERG_CONNECTOR §17, ICEBERG_EXTERNAL_CONNECTOR, the Windows QA guide, README, RELEASE_NOTES, CHANGELOG 2.6.0 / 2.6.5.
2. Every command and URL in those pages run once against a QA stack; every picture compared with the screen.
3. Cross-checks: version numbers (2.6.5 everywhere it is the current release), the release order (2.7.0 write-back, 2.8.0 AI and
   Sajha, send/push and integration after 3.0.0), real addresses (no 127.0.0.1 advertised), plugin count (nineteen), theme count (ten).
4. Help centre: every new help address opens; F1 on the history page lands on the right section.

## 6a. Fix run and regression

- **Lanes.** Fix-1: server, engine, connectors, history service, security. Fix-2: console pages and keys, themes, CLI, help, docs (the
  documentation verification's *wrong* rows included). A finding touching both goes to the lane of its root cause; the other lane is told.
- **Order.** P1 first, then P2 by how many users meet it; P3 only when a lane has nothing above it.
- **Each fix** carries a test that failed before it (unit, contract or browser), its docs and a CHANGELOG line, and names the finding id
  in the commit message.
- **Regression.** After the lane's fixes: the fix tests, the touched module's tests, the touched browser files one at a time, the docs
  tests. No full drill until the close.
- **Rules** as in section 2: own stacks, synthetic data, PIDs only, never the user's cluster, git author ajsinha only.

## 7. Outputs

- `docs/qa/2026-10-20/QA1_FINDINGS.md`, `QA2_FINDINGS.md`: findings in the format of section 3, screenshots in `docs/qa/2026-10-20/img/`
  (synthetic data only).
- `docs/qa/2026-10-20/DOCS_VERIFICATION.md`.
- A triage summary for the product owner: counts by severity and area, the proposed fix run (which builder, what), and the regression
  list (the tests to add or run).

## 8. Exit criteria for moving to 2.7.0

- No P1 open; every P2 fixed or explicitly deferred by the product owner.
- Round 2 confirms every fixed finding and finds no new P1.
- The regression for every fix passes (targeted tests, then one drill), released as 2.6.6.
- The documentation verification has no row left marked *wrong*.
