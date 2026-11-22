<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti. Proprietary: see LICENSE. -->
# Carried into combined i8 (named, by owner decision)

Items decided in i7b to move to combined i8, each with where it was found. Combined i8 starts only on the owner's OK.

| Item | From | What | Why moved |
|---|---|---|---|
| Cluster-spanning live tables read as the first opener | G4 gate F4 (I7B_GATE_4_REVIEW.md) | Ask other members with a node identity carrying the table's audience; audit every reader by name where they read (option A) | Owner 2026-11-21: fix if within ~2 h, else park; it needs a trusted node identity and a cluster test |
| Zip writing through drishti-tools (T11) | I7B_DUPLICATES T11, G4 gate F9 | `tools-codec` gains `Zips`; Drishti's four JDK zip writers move to it (pinned shrink-only until then) | drishti-tools 1.1.0 has no zip helper |
| drishti-tools 1.2.0 helpers | G4 tools adoption | `Zips`, an HTTP client factory in tools-net, a JVM tmpdir/native-copy helper in tools-files, a configurable header mark in the guards kit | Found while adopting tools in Drishti |

## Owner requirement for engines (2026-11-22)

An engine is built and unit-tested without the Drishti application: it needs only the engine API and, if it wants them, drishti-tools,
Drishti Lake and Rachana. Its own test kit runs with no server. To use it, its JAR is copied into the Drishti server's lib folder: the
server finds it at start and binds it by configuration. No engine is compiled into Drishti, and adding or upgrading one needs no Drishti
build. This is the acceptance test of the engine design (ENGINE_INTEGRATION_DESIGN.md, refreshed at the start of combined i8), and Drishti
Risk is the first engine delivered this way.

Designed 2026-11-22: ENGINE_INTEGRATION_DESIGN.md section 0 (the test as A1 to A8, the JAR, the lib folder, class loading,
compatibility, trust) and [ENGINE_SPEC](https://github.com/drishti-platform/drishti-engine/blob/develop/spec/1/SPEC.md) section 17; DRISHTI_RISK_DESIGN.md section 0a (Risk as one JAR). EN-29 to EN-44 are
accepted (owner, 2026-11-22).

## Owner direction for engines (2026-11-22): everything that computes

"We will expand the engine concept to literally everything where computations are done: design it well." Designed in
ENGINE_INTEGRATION_DESIGN.md section 2 (inventory, five ways of interacting on the same two interfaces, the rules every engine keeps,
the handover and the benchmark gate, the order M1 to M5) and section 11 (the minimal host); [ENGINE_SPEC](https://github.com/drishti-platform/drishti-engine/blob/develop/spec/1/SPEC.md) draft 3. Combined i8 is step
M1 and its scope does not grow. Open for the owner: EN-45 to EN-55, each with a recommendation.

## Also carried (G4 gate part 2b)

| Item | From | What |
|---|---|---|
| Lake 0.9.2: newest-wins by commit order in every case | Part 2b item 5 | On a Delta date whose files share one load run id, or whose commits are older than the Delta log keeps, the order falls back to run id and file time; compaction never settles a two-file date. MUST-FIX in Lake 0.9.2 (named in the v0.9.1 release text) |
| Sutra test-folder `.json` per case (F28 o) | Part 2 F28 | Still read by seven SutraCliTest cases; rewrite them and the CLI reference, then remove |
| PackStateStore / LoadStore / FileUserStore / FileAuditLog leftovers (F28 l) | Part 2 F28 | Remove once no install holds old state |
| Iceberg maintenance on REST-catalog lakes (AWS Glue) | Pre-drill (owner OK 2026-11-22) | `LakeMaintain` has no `--set` for catalog settings, so Drishti refuses to maintain Glue/REST-catalog Iceberg lakes with a clear message; Lake 0.9.2 adds it and Drishti routes through it |
| Elements 3.3.3: `selects` and `remember` in drishti-views | Patch gate 1.0.1 / 3.3.2 | A row click setting a control (ViewModel.selects, e.g. netting-set.v1 on-select) and remembered control values (ControlView.remember via the options.remember {get,set} hook) — embed v1 behaviour not yet restored by 3.3.2 |
| BI wizard: a model saved by the wizard is not offered in the designer's dataset picker | i7b final drill (parked by the owner's rule: not core) | test_bi_wizard_and_controls_browser: `mq:wiz-trades@1` missing from `[data-new-dataset]` after save (the dependent duplicate/delete test fails with it); BI reports draw — the start-from-a-new-model shortcut is the gap |
| Workbench: numeric option editor for table `limit` | i7b final drill | Rachana 1.0.1 makes `limit` an integer ≥ 1 or a ctl. reference, so the workbench draws a text box; test_workbench_options expects inputmode=decimal — decide the editor (number box with a ctl. toggle) and update the test |
| Workbench: a refused YAML-tab edit shows no problem line | i7b final drill | test_workbench_forms_browser: `.wb-problem-b` with "nonsense" never appears (15 s) |
| Admin snapshots: connectors (2–3%) and bindings (0.2–0.3%) differ from golden | i7b final drill | review the diffs (data/drill/snapshot-diffs/rerun/admin-*) then re-approve or fix |
| Iceberg: Python lake build under load (tools/lake/test_maintain_iceberg) | i7b final drill (owner: Iceberg parked to i8; Delta only in use) | `build.main --format iceberg` exited 1 in the loaded tools lane, passes alone (4.8 s); find the load-dependent cause with the Iceberg parity work |
