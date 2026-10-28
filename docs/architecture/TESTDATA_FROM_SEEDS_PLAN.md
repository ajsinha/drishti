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
# Plan: test data from seeds

**Status: proposed, design not built; approved for 2.9.6 by the product owner (plan of 2026-10-27, tracked in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)).** Goal: stop committing the synthetic part of `data/testdata` and regenerate
it, identically, on any machine (the offline Windows laptop included: Python only, no `.exe`, no internet), without git LFS. Public
snapshots (funds, us-equities, gleif, news-and-markets) stay committed.

## 1. Today

- `tools/testdata/make_testdata.py` writes everything in one process: the universe (3 × 5,000 trades) → 60 days of history →
  `make_var_data` (reads the trades it just wrote) → `make_ccr_data` (reads the netting sets) → manifest `testdata.yaml`.
- Committed: **137.6 MB** in 2,860 files. Synthetic **127.7 MB** (trades 41.6, market-risk 50.3, counterparty-risk 23.8, core 8.8,
  market-data 3.0); public **9.55 MB** (stays). Git history holds about 353 MB of test-data blobs.
- Generation time (measured in memory): about **3–4 min** here, **5–12 min** on a Windows laptop with Defender; `lake demo` itself
  takes 15–45 min.
- Determinism: seeds are text-hashed and the business date is fixed in code (safe). **Not** safe today: gzip bytes (zlib vs zlib-ng),
  `GzParts` splits on *compressed* size (file names can differ by platform), a few float `sum()` calls, last-bit differences of the
  platform math library (`exp`, `log`, `gauss`), and side files (pack samples, `reference.json`) rewritten when generating in place.

## 2. What stays and what is generated

| Path | |
|---|---|
| `README.md`, `testdata.yaml`, **new** `testdata.lock` (~250 KB), **new** `seeds.yaml` (~2 KB) | committed |
| `news-and-markets/`, `us-equities/`, `gleif/`, `funds/` | committed as now |
| `trades/`, `core/`, `market-data/`, `counterparty-risk/`, `market-risk/` | **generated in place**, git-ignored, same names and layout |
| `data/testdata-scale/` | generated, never committed |

Same paths, so `lake demo`, `build_demo_lakes`, the benches, `contracts.py`, the Java tests and the CLI examples need no change.

## 3. Seeds and lock

- `seeds.yaml`: profiles `demo` (5,000 per system, 60 days, 2026-09-30, locked), `small` (tests), `scale`; split files by
  **uncompressed** bytes; the compression level is not part of the hash. The seeds stay in the code; the file names the parameters.
- `testdata.lock`: one tab-separated line per generated file: sha256 of the **decompressed** content, a canonical hash with floats
  rounded to 9 significant digits (reported as *equivalent*, not a failure, when only the last bits differ), line count, bytes,
  path; plus a fingerprint of the generator sources, so CI catches "code changed, lock not regenerated" in a second.

## 4. One command

```
Linux:    drishti-console/.venv/bin/python tools/drishti.py testdata generate
Windows:  drishti-console\.venv\Scripts\python.exe tools\drishti.py testdata generate
```
Standard library + PyYAML only. Quick check when up to date (< 1 s); otherwise generate into `data/build/testdata-gen/`, verify
against the lock, then move each folder in (a half-written folder never replaces a good one). `--check`, `--check-lock`, `--force`,
`--profile demo|small|scale`, `--out`, `--explain`, `--accept-unverified`. Refuses Python < 3.12; checks free disk (~1.3 GB peak).

- `build_demo_lakes.py` gains step **1/4 the test data** (skipped with `--files-only`; `--no-generate` to forbid it).
- `lake demo` generates by itself when the folders are missing ("about 5 minutes, once").
- **Your own data is unchanged**: `--endur-dir`, `--source-dir`, `--with-real` work as now; generation never reads real files.

## 5. Scale (100k positions, 1,000 portfolios)

About 190k trades give 100k positions (0.53 positions per trade today), so `--positions 100000` sizes the run. 1,000 portfolios:
the 28 named ones plus generated rule, basket and per-book portfolios. 5 days of history by default (~1 min of trade states; 60 days
would be ~15 min). VaR vectors ~160 MB gz. Exposure stays capped at 9,500 trades. Written to `data/testdata-scale/`, not locked.

## 6. Tests, drill, CI

- New `test_lock.py` (lock complete, public folders tracked and synthetic ones not, sources fingerprint current); determinism test
  extended to every kind, two hash seeds and a second Python; `git status` clean after a user's generation.
- Java `ExposureParityTest` and `VarTestDataPrecisionTest` skip with a message naming the command, and fail in CI/drill
  (`-Ddrishti.requireTestdata=true`).
- Drill generates first (cached), then runs the generators' `--check` as now.
- CI: `fast.yml` generates (cached on the lock's hash); **a Windows job on Python 3.13 and 3.14, no cache, must be green before the
  folders stop being tracked**: the proof for the offline Windows machine.

## 7. Migration (one commit each)

1. Platform-independent output: split by raw bytes, `fsum` audit, side files off by default; prove the content unchanged.
2. Lock code. 3. Regenerate once, commit lock + manifest (data still tracked). 4. The command, `lake demo` and `build_demo_lakes`
   wiring, run configuration. 5. Windows CI green. 6. Stop tracking the five folders (`git rm --cached` + `.gitignore`); the first
   `lake demo` after a pull regenerates them; release note and QA guide say so. 7. Docs (README, QA on Windows, getting started,
   developer guide).
8. *Separate decision, not recommended now:* rewriting history to reclaim ~350 MB (changes every commit id; needs force pushes).

## 8. Risks

Last-bit floats on Windows (canonical hash, Windows CI, round `gauss` draws at the source if needed) · zlib differences (hash
decompressed content) · Python drift (≥ 3.12, CI on two versions) · a user's run rewriting committed files (side files off, test) ·
forgotten lock (fingerprint check) · first run 5–12 min offline (once, with progress) · history stays large (separate decision) ·
`git pull` removes the folders (auto-generate, release note) · scale memory (5 days, measure, stream if needed).

## 9. Estimate

About **6 days** for the core (sections 2–4, 6, 7), **8 days** with scale, +1 if Windows floats need fixing at the source.
