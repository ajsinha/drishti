<!-- Project Drishti · Any data. Any domain. One grammar. Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. PROPRIETARY AND CONFIDENTIAL. See LICENSE. -->
# QA 2026-10-03 — data and engine: activity log

## Setup
- Scratch server: `scripts/server.sh a 18962` (JDK 25, security on with a QA HS256 secret, studio-save on, packs = the 9 named, cwd scratchpad/run/a so data/ is scratch). Tokens minted by `scripts/qa.py`. Jar `drishti-server-1.13.0-exec.jar` built 12:26 (HEAD fea9398a).

## Shape engine
- `scripts/shape_hostile.py` (45 hostile cases: depth 60..5000, 100k-element arrays, dotted/empty/unicode/`__proto__` keys, huge ints, 1e999999, NaN, mixed types, maps vs records, trees, dup keys): every 200 answer validates against its own schema (Draft 2020-12, python-jsonschema), except big integers (see S-1). Depth 64 ok, 65 -> 413 DRS-5005 (also for a 5000-deep body: clean). NaN literal -> 400 DRS-5001 (not JSON).
- `scripts/shape_fuzz.py <seed> <n>` random documents + mutations, brute-force checks: every sample validates, `required` equals the keys present in every instance of the record node, `presence` equals the observed share, optional keys carry presence: seeds 1..5, 1,800 sets -> 0 invalid / 0 issues without >2^53 integers; with them 148 of 300 sets invalid (S-1).
- `scripts/shape_map.py` the map/record boundary: sparse optional sections become a map (S-2).
- `scripts/design_fuzz.py`: auto-design + check on the same hostile sets: every draft valid, no error cells; but see D-1 (single-root tree gets no tree panel).
- `scripts/schema_synth.py`: synthetic samples from 25 plain JSON Schemas validated with python-jsonschema: failures in pattern, oneOf, patternProperties+minProperties, recursive $ref, prefixItems, boolean-false property schema (S-3).

## Designs, ops, checker
- `scripts/edit_fuzz.py <seed> <n>`: random single operations through `/builder/edit` vs a python model of the parsed YAML (seeds 1-6, ~2,800 ops, ~2,200 applied): the model equals the result (area `main` and span 12 are normalised away; a `before`/`after` target in another column adopts its area); text unchanged and a located problem for refused ops; all comments survive except those inside a replaced `strip` (L-9). `edit_garbage.py`: wrong-typed values (L-8).
- `design_life.py`: stale/future/missing `baseRev` (409 DRS-5007 / 400), 16 concurrent writers on one baseRev -> exactly 1 x 200 and 15 x 409; `text` op with broken YAML/tabs/empty/binary/duplicate key/3 MB/alias bomb -> located problem, text and rev unchanged; 130 ops -> 101 versions, 100 undos then 409 "nothing to undo", 100 redos; new op after undo drops the redo tail.
- `rebase.py`: base moves (M-2) and approval overwrite (M-1).
- `matrix_brute.py <seed> <example> <n>`: matrix (stateless and design) equals the preview-derived truth, counts consistent. noAccess check with `qa-nobody` (no roles): every cell noAccess; viewers see ok/error per cell.
- `parity.py` on file (:18962) vs JPA (:18972): identical normalised transcripts for 35 calls except per-process fingerprints and share tokens; exported zips identical except the design id. `survive.py make|check` across restarts: identical. `expiry.py` against a server with scratch 20 s / named 60 s / warn 30 s / sweep 5 s. `quotas.py`, `roundtrip.py` (M-5).

## CLI
- `cli_parity.py` (lint vs API codes and lines, exit codes, examples through `test --junit`), `cli_test2.py` (21 pack variants), `cli_shape_design.py` (shape/design/preview vs API), M-6, M-7, L-6, L-7, L-10.

## Row groups, source, masks
- `pivot_brute.py` (120 cases, sum/count/avg/min/max, null/missing/odd keys, `amount0` totals parsed back), `tree_brute.py` (clean trees equal; hostile children L-12; totals are leaf sums as documented), `pivot_masked.py` (viewer: `•••` group, combined totals, no leak), `source_all.py` and `source_more.py` (L-13).

## Design quality
- `design_examples.py` (10 examples), `design_store.py` (23 stored kinds x 8 entities as references: no error cells, strips of 2-6 figures, pruning reasons present; L-2, L-14), `mixed50.py` (47 kinds in one set: valid, 92 ok / 2 empty cells, 70 pruned, only provenance+links drafted), prune boundary (>50 % empty drops the panel, exactly 50 % keeps it).
- Servers started and stopped by PID: :18962 file store, :18972 JPA store (:18963 was taken by another process, left alone), :18964 short-TTL.
