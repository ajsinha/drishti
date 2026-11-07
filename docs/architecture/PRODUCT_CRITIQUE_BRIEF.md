<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The independent product critique: the brief

An internal brief for reviewing Drishti with a stronger, independent model (Claude Fable 5.1). Opus 5.5 builds and fixes; Fable 5.1
critiques. The review is used twice: a **mid-way pass** on the riskiest change (after the BI foundation's parity work), and a **final
pass** on the whole product before it is called finished. The product owner approves this brief, the areas and the budget before
anything runs.

## Contents

1. [Purpose](#1-purpose)
2. [Principles](#2-principles)
3. [The areas](#3-the-areas)
4. [What each reviewer receives](#4-what-each-reviewer-receives)
5. [What to look for](#5-what-to-look-for)
6. [The finding format](#6-the-finding-format)
7. [How a pass runs](#7-how-a-pass-runs)
8. [After the review](#8-after-the-review)
9. [Budget and machine](#9-budget-and-machine)
10. [The prompt for one area](#10-the-prompt-for-one-area)

## 1. Purpose

Find what the builders and their tests missed: wrong numbers, security gaps, races, data loss, broken promises in the docs, and
experience problems a bank's user would hit. A critique that only confirms the product is a wasted pass; the goal is evidenced
problems, ranked, each one fixable.

## 2. Principles

- **Independent.** The reviewer gets the code, the tests, the data contracts and the customer documents, **never our conclusions**:
  no changelog claims, no release notes' "fixed", no builders' reports, no "this was checked". A reviewer handed a story tends to
  confirm it.
- **Evidence, not opinion.** A finding is a failing test, exact steps, or a cited line with the input that breaks it. Anything that
  cannot be shown goes to a separate *observations* list and is never mixed with findings.
- **Scoped.** One area per pass, never "the whole product" at once: depth over breadth.
- **Read-only.** The reviewer changes nothing in the repository; it may run tests and a scratch server, never the owner's stack, the
  main checkout's `data/` or `config/connectors/`.
- **Separation of duties.** Fable finds and re-checks; Opus fixes. Fable re-checks only the high-severity fixes.
- **No new features.** Suggestions are welcome but listed apart, ranked by value, and judged against the motto *lean & streamlined*
  (fold, simplify, delete before adding).

## 3. The areas

Each area is one pass. The order runs from the money a bank relies on to the screens around it.

| # | Area | What it covers | Ground truth to check against |
|---|---|---|---|
| 1 | **VaR and the risk engines** | historical VaR/ES, any scope, hierarchies, horizons and confidences, parametric VaR, backtesting, the official record, FRTB-SA, SIMM, the MR cube | the VAR, FRTB-SA, SIMM and regulatory guides; the playground workbooks (equal to the engine to the cent); the VaR check between releases |
| 2 | **Exposure and counterparty risk** | exposure paths, netting, collateral, EE/PFE/EPE/EEPE, Euler allocation, the stored figures marked under construction | the exposure guide and data contracts; the under-construction registry |
| 3 | **BI and the pivot engine** | Rachana formulas, virtual tables, semantic models, row rules and masks, non-additive totals, reports and boards, Explain, What-if | the BI and pivot guides, the function catalogue, the parity recorder's queries |
| 4 | **Data in and out** | the load rules (names, `.ready`, tars, append, undo), lake files, the inbox, Delta/Iceberg/in-memory lakes, connectors, Arrow Flight and Flight SQL | DATA_LAKE_GUIDE.md, the lake and Flight guides, the client examples |
| 5 | **Security and access** | sign-in, tokens and API keys, roles, entitlements, masks across every door (screens, API, Flight, exports), audit, four eyes, secrets | the security and user-management documents, the session-isolation rule (a cross-user leak is a release blocker) |
| 6 | **Cluster and operations** | Drishti*net, sharding, a node down, identity sync, engine switches, capacity, busy protection, upgrades, the native libraries and their fallbacks | OPERATIONS.md, CLUSTER_DEPLOYMENT.md, CONFIGURATION.md, BENCHMARK.md |
| 7 | **The console and experience** | the command line, Sutras and the inference engine, the editors, Help and tutorials, phone layout, accessibility, the trader's first ten minutes | the user guide, the tutorials (run them as written), EDITORS.md, the UX rules |
| 8 | **Engineering health** | architecture rules, duplicated machinery, dead code, test signal (flakes, skips, tests that cannot fail), the drill, build and dependencies | ARCHITECTURE.md, TEST_HARNESS.md, the coherence motto |

The **mid-way pass** covers area 3 only (the formula grammar fold and virtual tables), right after their parity proof and before the
old code is deleted.

## 4. What each reviewer receives

- The area's source folders and their tests (paths listed in the pass's prompt).
- The area's customer documents and data contracts, and the Help pages they generate.
- How to build and start a **scratch** server and console on the demo data (GETTING_STARTED), with free ports and its own data folder.
- The machine rules (section 9).
- **Not**: CHANGELOG.md, RELEASE_NOTES.md, the implementation plan's claims, builders' reports, or previous critiques of the area
  (a previous critique is shared only after the reviewer has written its own findings, to check for regressions).

## 5. What to look for

Per area, at least:

- **Correctness**: wrong figures, wrong totals (a sum where the measure does not add up), wrong dates, rounding, currency, edge cases
  (empty scope, one position, a missing scenario day, a leap day, a holiday).
- **Promises**: every claim in the customer documents, tested against the product; a tutorial that does not run as written is a
  finding.
- **Security**: a door that skips a row rule or a mask; a token or key that outlives its owner's rights; a secret in a log; a
  cross-user leak in a shared cache.
- **Concurrency and failure**: races, a cache served stale after a write, a node down mid-request, a load interrupted, a disk full,
  a native library missing.
- **Data safety**: a load that half-writes, an undo that misses rows, a retention that deletes the wrong dates.
- **Performance cliffs**: an input size where time or memory jumps (measured, not guessed).
- **Experience**: a dead button, an error a person cannot act on, a number without its explanation, an action without a way back.
- **Coherence**: two ways to do one job, a duplicated engine or parser, a setting nobody needs.

## 6. The finding format

One entry per finding, ranked most severe first:

| Field | What it holds |
|---|---|
| **ID** | area number and a sequence, e.g. `R1-07` |
| **Severity** | **S1** wrong risk number, data loss or security leak · **S2** a broken feature or a promise the docs make · **S3** an inconvenience with a workaround · **S4** polish |
| **Where** | file and line, screen or command |
| **What** | one sentence, the defect |
| **Evidence** | the failing test (code given), or exact steps with input and output, or the cited line with the input that breaks it |
| **Why it matters** | who is affected and how (a trader, a risk manager, an auditor, an operator) |
| **Suggested fix** | the smallest change at the cause |
| **Confidence** | proven (reproduced) or likely (reasoned, not reproduced) |

Kept apart, after the findings: **observations** (concerns without evidence) and **suggestions** (improvements, each with its value
and what it would let us delete).

## 7. How a pass runs

1. The product owner approves the area list, the order and the budget.
2. One Fable 5.1 agent per area, **one or two at a time**, in its own worktree, read-only, with the prompt in section 10.
3. Each agent writes its findings to `review/<date>/<area>.md` in the scratch folder (never committed raw: findings can name
   weaknesses), with any failing tests as separate files.
4. A short synthesis across areas: duplicates merged, a single ranked list, the S1s on top.
5. The owner reviews the synthesis and decides what is fixed now, later, or not at all.

## 8. After the review

- **Opus fixes**, S1 first, each fix with the reviewer's failing test turned into a permanent test.
- **Fable re-checks the S1 and S2 fixes only**: given the finding, the fix and the test, it confirms the defect is gone and nothing
  near it broke.
- The fixes go through the usual pre-merge gate and a full drill; the VaR check between releases must stay exactly equal unless a
  finding proved the old figure wrong (then the change is documented and approved by the owner).
- Customer documents are corrected where they promised something untrue. No bug history goes into them.

## 9. Budget and machine

- **Model**: Fable 5.1 for review and re-check; Opus 5.5 for fixes, synthesis and the drill.
- **Budget**: start each pass on a fresh weekly allowance. A scoped pass per area plus the S1/S2 re-check is expected to use a few
  days' worth of a week, not a whole week; the owner sets a ceiling and the passes stop at it, reporting what remains.
- **Machine**: at most two reviewers at once; one heavy job (Maven with tests, or browser tests) at a time; scratch servers on free
  ports with their own data folders, stopped by PID when done; temp folders removed; the owner's stack never touched; no Docker tests
  beside browser tests.

## 10. The prompt for one area

The prompt each reviewer gets, with the area's parts filled in:

> You are reviewing one area of Drishti, a risk and data platform used by banks, as an independent critic. **Area:** *{name}*.
> **Covers:** *{what it covers}*. **Source:** *{folders}*. **Tests:** *{test folders}*. **Documents and contracts:** *{list}*.
>
> Find defects a bank would care about: wrong numbers, security gaps, races, data loss, promises the documents make that the product
> does not keep, dead or confusing screens, and duplicated machinery. Read the code and the documents yourself; you are not given
> anyone's claims about what works, and you should not look for them (do not read CHANGELOG.md, RELEASE_NOTES.md or the
> implementation plan).
>
> Prove each finding: a failing test, exact steps with input and output, or a cited line with the input that breaks it. Rank by
> severity (S1 wrong risk number, data loss or security leak; S2 broken feature or broken promise; S3 inconvenience; S4 polish). Use
> the finding format: ID, severity, where, what, evidence, why it matters, suggested fix, confidence. List unproven concerns
> separately as observations, and improvements separately as suggestions (with what each would let us delete).
>
> Rules: change nothing in the repository; build and run only a scratch server on free ports with its own data folder (GETTING_STARTED);
> never touch the owner's running stack, `data/` or `config/connectors/` of the main checkout; one heavy job at a time; stop what you
> start by PID; remove your temp folders. Write your findings to *{output path}*. Report: the count per severity, the S1s in one line
> each, and anything you could not check and why.
