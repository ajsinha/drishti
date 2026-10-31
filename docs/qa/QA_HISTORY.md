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
# Quality assurance history

Every adversarial QA round, soak and documentation verification Drishti has had, in one page: when it ran, on what, what it
found and what was fixed, with the commits. The round reports themselves (activity logs, findings files with steps to reproduce,
access matrices, the scripts and the raw soak results) were 35 files under `docs/qa/`; they were folded into this summary in
2.9.7 and deleted. **Git keeps every one of them:** each section below names the commit that added the report, so
`git show <commit>:<path>` (or `git log -- docs/qa/<round>/`) brings the full text back, scripts included. The last tree that
still had them all is tag `v2.9.6`.

Severity words follow the round: rounds of 1.13 and 1.14 used *High, Medium, Low, Info*; the 2.6.5 and 2.6.6 rounds used
*P1* (wrong data, a leaking mask, data loss, a crash, a security hole), *P2* (does not work as documented, a dead end, a misleading
message) and *P3* (polish). Commit links open on GitHub.

The one QA document still kept here is a live procedure, not a round: [windows-custom-data.md](windows-custom-data.md), the
step-by-step script for a demo or QA on an offline Windows machine with your own ENDUR files.

## Contents

1. [The rounds at a glance](#1-the-rounds-at-a-glance)
2. [2026-10-01: adversarial QA of 1.13.0](#2-2026-10-01-adversarial-qa-of-1130)
3. [2026-10-03: round 2, the Build workbench](#3-2026-10-03-round-2-the-build-workbench)
4. [2026-10-05: collaboration behaviour and data safety](#4-2026-10-05-collaboration-behaviour-and-data-safety)
5. [2026-10-17: the 2.3.0 documentation verification](#5-2026-10-17-the-230-documentation-verification)
6. [2026-10-20: the six-hour adversarial QA of 2.6.5 and 2.6.6](#6-2026-10-20-the-six-hour-adversarial-qa-of-265-and-266)
7. [2026-10-20: the Drishti\*net soaks](#7-2026-10-20-the-drishtinet-soaks)
8. [2026-10-21: BI runs, the console and the real server together](#8-2026-10-21-bi-runs-the-console-and-the-real-server-together)
9. [What every round taught the next](#9-what-every-round-taught-the-next)

## 1. The rounds at a glance

| Date | Round | Release under test | Found | Outcome | Report added in |
|---|---|---|---|---|---|
| 2026-10-01 | Adversarial QA: security, data, UX/grammar/docs | 1.13.0 (`develop` at `778bac1`) | 10 High, 33 Medium, 36 Low, 15 Info | every High and Medium fixed with a regression test (1.13.x and 1.14.0) | [d5bb1e5d0](https://github.com/ajsinha/drishti/commit/d5bb1e5d0) |
| 2026-10-03 | Round 2: the Build workbench (security, data, usability) | 1.13.0 jar, `develop` at `fea9398a` | 0 High, 18 Medium, 39 Low, 15 Info | every Medium fixed; wave 1 in [89121ae7](https://github.com/ajsinha/drishti/commit/89121ae7), released in 1.14.1 | [0ee459766](https://github.com/ajsinha/drishti/commit/0ee459766) |
| 2026-10-05 | Behaviour pass (B3) and data-safety pass (S3): collaboration, About/Ask, bridges, email, snapshots | `develop` at `fcb22ef6` | B3: 1 High, 3 Medium, 9 Low, 4 Info; S3: 1 High and the rest Medium/Low | fixed in [bd5e0383b](https://github.com/ajsinha/drishti/commit/bd5e0383b) and [ec7ad92d1](https://github.com/ajsinha/drishti/commit/ec7ad92d1) | [f515f4131](https://github.com/ajsinha/drishti/commit/f515f4131), [d13a768c0](https://github.com/ajsinha/drishti/commit/d13a768c0) |
| 2026-10-17 | 2.3.0 documentation verification | 2.3.0 | one row per guide, admin page, connector page and tutorial | released with 2.3.0 | [33d9e0535](https://github.com/ajsinha/drishti/commit/33d9e0535) |
| 2026-10-20 | Six-hour adversarial QA, rounds 1 to 4, two documentation verifications | 2.6.5, then 2.6.6 | QA-1: 2 P1, 8 P2, 9 P3; QA-2: statements, tokens, themes, Iceberg, Cassandra, Admin; round 3: 1 P1, 11 P2, 8 P3 | every finding fixed before 2.7.0 (product owner: "fix them all"); round 4 left QA3B-03 (P2) open, then fixed | [20c236eb0](https://github.com/ajsinha/drishti/commit/20c236eb0) (plan) |
| 2026-10-20 | Drishti\*net soaks 1 to 4 (two hours, one hour, two hours) | 2.6.5, 2.6.6 | soak 1: 32 complete histories marked partial, memory steps; soak 2: SOAK2-01 (P1, mask class); soak 3: SOAK3-01/02; soak 4: memory only | each failure fixed and re-soaked; soak 4 failed on memory alone | [ee20d7585](https://github.com/ajsinha/drishti/commit/ee20d7585) to [6a94cf8de](https://github.com/ajsinha/drishti/commit/6a94cf8de) |
| 2026-10-21 | BI runs integration: the console lane against the real server | 2.6.7 (`develop` at `542543925`) | BIR-01 to BIR-14 | all fixed in 2.6.7 | [63fe96f90](https://github.com/ajsinha/drishti/commit/63fe96f90) |

## 2. 2026-10-01: adversarial QA of 1.13.0

**What ran.** Three independent passes in parallel on scratch servers and consoles (ports 1898x and 1798x), on the banking packs'
samples and small generated books (at most 10,000 trades a day), containers one at a time capped at 2 GB. They reported and changed
no product code. Reports: `docs/qa/2026-10-01/README.md` (the summary), and per area a `LOG.md`, a `FINDINGS.md` and `scripts/`
(security also `matrix_server.md`, 101 endpoint/method rows over 35 controllers × 9 identities, and `matrix_console.md`, 42 console
routes × 8), added in [d5bb1e5d0](https://github.com/ajsinha/drishti/commit/d5bb1e5d0) and
[32c9cecc5](https://github.com/ajsinha/drishti/commit/32c9cecc5) (the data pass, once the ignore rule stopped hiding it).

**Result.** 10 High, 33 Medium, 36 Low, 15 Info. *In one sentence:* Drishti answered correctly when everything worked (4,640
randomised searches over seven stores matched an independent brute-force evaluation exactly; about 250 documented settings matched
the code; about 190 grammar fuzz cases got clean problem codes; no XSS; the access matrix held on admin, ownership and four-eyes),
but it was too quiet and too forgiving when something failed.

**The High findings and their fixes**

| Id | Finding | Fixed in |
|---|---|---|
| SEC-01 | Disabling or demoting a user did not end their console session | [6f886ab](https://github.com/ajsinha/drishti/commit/6f886ab) |
| DATA-01 | A source failing while listing made a search look exact and empty; a failed reindex dropped the kind from type-ahead | [c00488a](https://github.com/ajsinha/drishti/commit/c00488a), [724328b](https://github.com/ajsinha/drishti/commit/724328b) |
| DATA-02 | One unreadable JSON-lines line silently erased the whole day | [5ecce29](https://github.com/ajsinha/drishti/commit/5ecce29) |
| DATA-03 | A failing store counted as "not held", so the next store answered with different data | [8514970](https://github.com/ajsinha/drishti/commit/8514970), [c00488a](https://github.com/ajsinha/drishti/commit/c00488a) |
| DATA-04 | PostgresLoader was not atomic | [1420ab6](https://github.com/ajsinha/drishti/commit/1420ab6) (also [2cd7aa7](https://github.com/ajsinha/drishti/commit/2cd7aa7) Redis, [6de321d](https://github.com/ajsinha/drishti/commit/6de321d) MongoDB) |
| DATA-05 | One future-dated row with retention dropped all history | [1420ab6](https://github.com/ajsinha/drishti/commit/1420ab6), [9b2099d](https://github.com/ajsinha/drishti/commit/9b2099d), [2cd7aa7](https://github.com/ajsinha/drishti/commit/2cd7aa7) |
| DATA-06 | JSON-lines rows with `doc` as an object and no `columns` had every promoted column null | [5ecce29](https://github.com/ajsinha/drishti/commit/5ecce29) |
| GRAM-01, GRAM-02 | A deeply nested Sutra expression stopped the server starting, and silently killed hot reload | [c1ace31](https://github.com/ajsinha/drishti/commit/c1ace31) |
| UX-01 | Six live tabs froze a seventh page (one live connection per tab) | [1400225](https://github.com/ajsinha/drishti/commit/1400225) |

**Medium findings, fixed:** sessions and masks (SEC-02 to SEC-06: the token filter's raw URI, masks on every path, impact masks,
server-side sign-out, enforced first password change; [fc82831](https://github.com/ajsinha/drishti/commit/fc82831),
[43075d9](https://github.com/ajsinha/drishti/commit/43075d9), [d802261](https://github.com/ajsinha/drishti/commit/d802261),
[6f886ab](https://github.com/ajsinha/drishti/commit/6f886ab)); data correctness (DATA-07 to DATA-15: mixed-type columns, effective
kinds in reverse lookups, duplicate ids, reads racing a replace, Redis merging days, multi-store fall-through, LZ4 Parquet,
`quant.year_fraction` conventions, `knownAt` on stores without time travel;
[494741c](https://github.com/ajsinha/drishti/commit/494741c), [5ecce29](https://github.com/ajsinha/drishti/commit/5ecce29),
[f047e6a](https://github.com/ajsinha/drishti/commit/f047e6a), [8dbec96](https://github.com/ajsinha/drishti/commit/8dbec96),
[724328b](https://github.com/ajsinha/drishti/commit/724328b), [891bc73](https://github.com/ajsinha/drishti/commit/891bc73),
[a9dc80a](https://github.com/ajsinha/drishti/commit/a9dc80a)); grammar (GRAM-03 to GRAM-05: deep expressions as a problem code,
field names ignoring case, strict Sutra input; [c1ace31](https://github.com/ajsinha/drishti/commit/c1ace31),
[4ffeef1](https://github.com/ajsinha/drishti/commit/4ffeef1), [9db82fa](https://github.com/ajsinha/drishti/commit/9db82fa));
pages (UX-02 to UX-09: phone width, screenshot fit, theme contrast, Studio and workspaces without the right packs, Alt keys in
workspaces, a past date showing live data, pivot keys in scientific notation;
[61ba1d9](https://github.com/ajsinha/drishti/commit/61ba1d9), [8ad4307](https://github.com/ajsinha/drishti/commit/8ad4307),
[0cea59a](https://github.com/ajsinha/drishti/commit/0cea59a), [6966b08](https://github.com/ajsinha/drishti/commit/6966b08),
[ac21b62](https://github.com/ajsinha/drishti/commit/ac21b62), [1598327](https://github.com/ajsinha/drishti/commit/1598327),
[9a33cce](https://github.com/ajsinha/drishti/commit/9a33cce), [ef0b0be](https://github.com/ajsinha/drishti/commit/ef0b0be));
documentation (DOC-01 to DOC-22, closed with the drift tests `test_docs_error_codes.py`, `test_docs_plugins.py`,
`test_docs_settings.py` and `test_help_links.py` in [0537fee0b](https://github.com/ajsinha/drishti/commit/0537fee0b)). The lows and
infos were fixed or addressed in [ecad201f6](https://github.com/ajsinha/drishti/commit/ecad201f6),
[4dd91f15a](https://github.com/ajsinha/drishti/commit/4dd91f15a), [1663a2192](https://github.com/ajsinha/drishti/commit/1663a2192)
and [4d858d8c8](https://github.com/ajsinha/drishti/commit/4d858d8c8).

**What held up:** search correctness on files, Delta (both engines), DuckDB, PostgreSQL and Redis; dates and history; derived kinds;
reloads under 12 to 16 reader threads; the message state store with `sync` durability through `kill -9`; tokens (tampered,
`alg: none`, expired) refused; malformed input giving clean 4xx; 85 pages at three widths in all seven themes; 109 `drishti.quant`
checks against scipy. **Not covered:** MongoDB, Iceberg, Aerospike, Kafka, ActiveMQ, S3; single sign-on with a real provider;
Firefox, Safari and screen readers; Windows (read only).

## 3. 2026-10-03: round 2, the Build workbench

**What ran.** Everything added since round 1: the Screen Builder (shape, auto-design, suggest), the workbench (Designs, canvas,
revisions, checker, versions), shipping (propose with evidence, pack export and import, share links, file binding), the `sutra`
CLI, `source:` on every kind and expandable row groups. Three passes (security, data and engine, usability and docs), each on its
own scratch server; about 950 security requests, an isolation matrix of 25 endpoints × 6 callers, brute-force checkers for shape,
pivot, tree and matrix, and the guide followed end to end. Reports added in
[0ee459766](https://github.com/ajsinha/drishti/commit/0ee459766) (`docs/qa/2026-10-03/`).

**Result.** 0 High, 18 Medium, 39 Low, 15 Info.

| Area | Medium findings | Fixed in |
|---|---|---|
| Governance and safety | S2-01 (file binding made a Sutra live without review), S2-02 (a schema with huge `minItems` ran the server out of memory), S2-03 (unbounded design parts), M-1 (approval rewrote version v instead of making v+1), M-2 (no rebase when the base moved), UX-05 (scratch designs filled the quota) | [89121ae7](https://github.com/ajsinha/drishti/commit/89121ae7) |
| Correctness | M-3 (integers past 2^53 and infinities in the shape schema), M-4 (sparse sections turned a record into a map) | [69343448](https://github.com/ajsinha/drishti/commit/69343448) |
| | M-5 (export then import grew the notes) | [1615c22f](https://github.com/ajsinha/drishti/commit/1615c22f) |
| | M-6, M-7 (`sutra test` and bad or empty samples) | [2a9e9ea2](https://github.com/ajsinha/drishti/commit/2a9e9ea2) |
| Usability | UX-01, UX-02, UX-04 (false kind problem, a mistyped expression with no problem shown, codes written twice) | [b05bca8e](https://github.com/ajsinha/drishti/commit/b05bca8e) |
| | UX-03 (options the inspector could not set) | [272d0572](https://github.com/ajsinha/drishti/commit/272d0572) |
| | UX-06, UX-10 (invisible notes, 71 Tab presses to the canvas) | [1ee0729f](https://github.com/ajsinha/drishti/commit/1ee0729f) |
| | UX-08 (sideways scrolling at 390 px) | [4ff7a824](https://github.com/ajsinha/drishti/commit/4ff7a824) |

S2-08 (a clean 413 for oversized requests) and S2-11 (opt-in `mask-copies` and path masks) shipped in 1.14.1
([418d4e64b](https://github.com/ajsinha/drishti/commit/418d4e64b)); every finding of the round was then marked fixed or addressed with
its commit and test ([57fce8234](https://github.com/ajsinha/drishti/commit/57fce8234),
[cc0cb597a](https://github.com/ajsinha/drishti/commit/cc0cb597a), [6afeec9d1](https://github.com/ajsinha/drishti/commit/6afeec9d1)).
**What held up:** design isolation, share links, the open-design and gated-deploy policy with four-eyes, kind rights and masks on
every new surface, pack import against zip-slip, zip bombs and `!!java` tags, console CSRF, the shape engine (1,800 random sets and
45 hostile cases), nested pivots against brute force, 16 concurrent writers producing exactly one winner.

## 4. 2026-10-05: collaboration behaviour and data safety

Two adversarial passes on collaboration (share with a note, comment threads, the inbox, email, bridges, snapshots) and *About this
page* / Ask, with fake Ask and bridge endpoints and an SMTP sink. Reports: `docs/qa/2026-10-05/BEHAVIOUR.md`
([f515f4131](https://github.com/ajsinha/drishti/commit/f515f4131)) and `docs/qa/2026-10-05/DATA_SAFETY.md`
([d13a768c0](https://github.com/ajsinha/drishti/commit/d13a768c0)).

- **Behaviour (B3-01 to B3-17):** 1 High (B3-01: a viewer's shared picture carried what his masks hid, owned by the data-safety
  fixes), 3 Medium, 9 Low, 4 Info. Fixed in [bd5e0383b](https://github.com/ajsinha/drishti/commit/bd5e0383b): the mail outbox and
  bridge trouble in Admin → Collaboration, one zone rule for timestamps, `Retry-After`, the bridge test timeout (`DRS-7013`), the edit
  window, the phone layout; B3-03 addressed (a picture is drawn in the business-date zone, documented).
- **Data safety (S3-01 onwards):** 1 High (S3-01: a viewer with no raw rights could post a comment or share that echoed masked
  values), and Medium findings on mention email, share email with a picture and the tamper-evidence check. Fixed in
  [ec7ad92d1](https://github.com/ajsinha/drishti/commit/ec7ad92d1): no masked-value oracle, normalised copy matching, values in email
  and bridges per recipient, a frozen picture per recipient's rights. The decisions made after this pass are in
  [COLLABORATION.md](../architecture/COLLABORATION.md).

## 5. 2026-10-17: the 2.3.0 documentation verification

One row per document of `docs/guides`, `docs/admin`, `docs/connectors` and `docs/tutorials`, with the outcome derived from the git
history since `v2.2.0` (rewritten, updated, names-only), what was checked, the commit and a check a reader can repeat. Report:
`docs/qa/2.3.0-docs-verification.md` ([33d9e0535](https://github.com/ajsinha/drishti/commit/33d9e0535)), cited by the 2.3.0 release
notes.

## 6. 2026-10-20: the six-hour adversarial QA of 2.6.5 and 2.6.6

**The plan** (`docs/qa/2026-10-20/QA_PLAN.md`, [20c236eb0](https://github.com/ajsinha/drishti/commit/20c236eb0),
[a66971211](https://github.com/ajsinha/drishti/commit/a66971211)): a time-boxed run to break what 2.5.0, 2.6.0 and 2.6.5 added, as the
people who use it daily would. Round 1, triage into fix lanes, a fix run with regression, a documentation verification, round 2
re-tests. Exit criteria for 2.7.0: no P1 open, every finding fixed and re-tested (the product owner's word: *fix them all*), the
regression and one drill green (released as 2.6.6), no documentation row left *wrong*, and a soak on real processes.

| Round | Tester and scope | Found | Report added in |
|---|---|---|---|
| 1 | QA-1, trader and risk analyst: history (H-1 to H-15), the command line, Drishti\*net | 2 P1, 8 P2, 9 P3 (QA1-01 to QA1-19) | [69202aec1](https://github.com/ajsinha/drishti/commit/69202aec1) |
| 1 | QA-2, BI author and administrator: statements and bursting, API tokens, field parameters, themes, Iceberg, Cassandra, Admin (B-1 to B-10) | QA2-01 to QA2-25 | [245658fce](https://github.com/ajsinha/drishti/commit/245658fce) |
| 2 | QA-1 re-test of Fix-1 | QA1-01 to 06, 08, 09, 11 to 13 fixed, QA1-18 partly; two new P2 | [7779ce797](https://github.com/ajsinha/drishti/commit/7779ce797) |
| 2 | QA-2 re-test of Fix-1 to Fix-3 | QA2-01 to 09, 11, 13, 24, 25 fixed; QA1-10 and the `lake maintain` layout fixed; new QA2-201 | [fec8171d4](https://github.com/ajsinha/drishti/commit/fec8171d4) |
| 3A | Multi-worker console, Fix-4 and Fix-5 re-tests (`develop` at `bf0141e78`) | 1 P1, 4 P2, 3 P3 | [aebd18b20](https://github.com/ajsinha/drishti/commit/aebd18b20) |
| 3B | BI author and administrator (`develop` at `5d20bd55f`) | QA3B-01 to 12: no P1, 7 P2, 5 P3; no mask leak in bursts to masked recipients | [e4a339069](https://github.com/ajsinha/drishti/commit/e4a339069) |
| 4 | Short re-check of Fix-8, Fix-9a/9b and Fix-10 (`develop` at `e0e0f4644`) | not done: QA3B-03 (P2) still open, a moved Iceberg table loaded by `load-iceberg.sh` still read `UP`; QA3A-07 fixed | [d1fe7f6ee](https://github.com/ajsinha/drishti/commit/d1fe7f6ee) |

**Documentation verifications.** `DOCS_VERIFICATION.md` ([22cde0edb](https://github.com/ajsinha/drishti/commit/22cde0edb)): 93 rows,
every document touched since `v2.5.0`, the cross-checks (ten themes, counts, captured figures), the wrong rows fixed in
[d8ce06790](https://github.com/ajsinha/drishti/commit/d8ce06790). `DOCS_VERIFICATION_2.md`
([485217aad](https://github.com/ajsinha/drishti/commit/485217aad)): the second pass for 2.6.6, 51 rows (33 verified, 14 wrong and
fixed).

## 7. 2026-10-20: the Drishti\*net soaks

Soaks on real processes (three servers, a console, user load with history queries, kills of a shard and of the seed), run with
`tools/bench/net_soak.py`; each report kept the raw result (`soak.json` to `soak4.json`).

| Soak | Length | Verdict | What it found | Added in |
|---|---|---|---|---|
| 1 (2.6.5) | two hours | FAIL | 32 complete history answers marked partial; memory stepping up on failover (SOAK-01 to SOAK-10) | [ee20d7585](https://github.com/ajsinha/drishti/commit/ee20d7585) |
| 2 (2.6.6, production JVM settings) | two hours | FAIL | SOAK2-01 (P1): an administrator's history of a peer-held trade was served to a viewer from the cache; the tool gained `--jvm-arg` and `--server-env` ([ff15b3893](https://github.com/ajsinha/drishti/commit/ff15b3893)) | [84907346c](https://github.com/ajsinha/drishti/commit/84907346c) |
| 3 | one hour | FAIL | 15,940 admin/viewer history pairs in both orders, 0 mask errors (SOAK2-01 fixed by Fix-10, the cache keyed by the caller's security signature); SOAK3-01 (a rejoined node answered `404 DRS-1001` for what only its peers held), SOAK3-02 (the history cache's `cache-mb` not sized as heap) | [cafe5bacd](https://github.com/ajsinha/drishti/commit/cafe5bacd) |
| 4 | two hours, kills at 900 s and 1,800 s | FAIL on memory only | SOAK3-01 fixed (0 wrong answers, 0 `DRS-1001`); one process's resident memory still sloped 165 MB an hour over 74 steady samples | [6a94cf8de](https://github.com/ajsinha/drishti/commit/6a94cf8de) |

The memory settings that came out of them (`MALLOC_ARENA_MAX=2`, `-XX:MaxDirectMemorySize`, `-XX:TrimNativeHeapInterval`) are in
[PERFORMANCE.md](../admin/PERFORMANCE.md).

## 8. 2026-10-21: BI runs, the console and the real server together

The console lane of BI runs had been built against a stand-in server (`drishti-console/tests/bi_runs_backend.py`). This round put it
in front of the real server and checked every flow in Chromium, over the API and from the CLI: 12 checks with evidence (the Runs tab
live, reuse, a masked role never getting another person's result, confirm cards, a selection that needs a run, schedules, exports,
promote). Findings BIR-01 to BIR-14, all fixed in 2.6.7: the console's in
[807153f51](https://github.com/ajsinha/drishti/commit/807153f51) (BIR-09, 10, 12, 13, 14 and Promote in the run drawer) and the
server's in [b975f4876](https://github.com/ajsinha/drishti/commit/b975f4876) (BIR-06 to BIR-11 and BIR-13, with the BIR-07 root
cause). Report: `docs/qa/2026-10-21/BI_RUNS_INTEGRATION.md`, added in [63fe96f90](https://github.com/ajsinha/drishti/commit/63fe96f90).

## 9. What every round taught the next

- **Report, then fix, then re-test.** Testers never change product code; every fix carries a regression test built from the
  reproduction, and a later round re-runs the finding and the area next to it.
- **Silence is the bug.** The first round's lesson (a failing store or a bad line becoming "nothing found") became the rule that a
  failure is a problem code, a health state and a log line, never an empty answer.
- **Drift tests over reviews.** The documentation findings became tests (`test_docs_rot.py`, `test_help_links.py`,
  `test_docs_settings.py`, `test_docs_error_codes.py`, `test_docs_plugins.py`), so the next drift fails the build instead of waiting
  for a QA round.
- **Soak before the drill** for anything clustered, with the production JVM settings, and a verdict per check.
