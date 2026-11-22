<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i7b gate 3 review: the products complete (Rachana, Elements, Lake, Tools)

Code and design gate of milestone G3 of [I7B_PRODUCTS_PLAN](I7B_PRODUCTS_PLAN.md), in the format of
[I7B_GATE_2_REVIEW](I7B_GATE_2_REVIEW.md). A point-in-time inspection, not a drill. Run on 2026-11-21, 17:29 to 18:00, on JDK 21
(`/usr/lib/jvm/java-21-openjdk-amd64`), Node 22.22 and Python 3 (`uv`), in **fresh clones from `origin`** under
`~/.cache/drishti-lanes/fable-g3/` (deleted afterwards). Judged from the code and from my own runs; no builder summary was used as
evidence. Two read-only sweep agents (Sonnet) listed candidates; every claim used below was opened and confirmed by me, or is marked
"sweep" where it was not. Documents are judged separately later. No product code was changed. Nothing was installed into `~/.m2`
(Tools went into a scratch Maven repository, Lake's build read it through `MAVEN_ARGS`).

| Repository | Branch | Commit reviewed | Was the head when I finished |
|---|---|---|---|
| `drishti-rachana` | `develop` | `4d7483fa` (after `c4b2c03d`: the G4 binder fixes P14, P15, R-1, R-2 and the library doc-text fix) | see section 7 |
| `drishti-elements` | `develop` | `67344c2` (tag `v3.2.0` = `787f97d`, ten commits earlier) | see section 7 |
| `drishti-lake` | `main` | `32819cd` | see section 7 |
| `drishti-tools` | `main` | `3ef8e9d` | see section 7 |

The machine was shared with the G4 lanes the whole time (load 10 to 50). I started each heavy job below load 17 except where said.

## Verdict

**PASS WITH CONDITIONS. Two blockers are open: F1 (Rachana) and F5 (Lake). Tools and Elements pass. Neither Rachana nor Lake is
tagged until its blocker is fixed and re-checked.** Both blockers are defects no existing test looks at; I found each with a
probe of my own, and each reproduces every time.

- **Drishti Tools `3ef8e9d`: PASS.** Ready for `v1.0.0` after the version commit (F7).
- **Drishti Lake `32819cd`: NOT YET.** Its own check is green and stable, but four loaders replacing the same business date at
  once leave **all four loads' rows** in an Iceberg table (F5, 4 runs of 4, local disk and memory); Delta and the files store keep
  one. After F5: F4 (say what the files store cannot do), F6 (the leftovers the plan put in G3) and F7.
- **Drishti Elements `67344c2`: PASS, but it is not a 3.2.x.** The work after `v3.2.0` removes public names and adds API: it is
  **3.3.0** (F3). `v3.2.0` at `787f97d` stands and is exactly what Rachana vendors.
- **Drishti Rachana `4d7483fa`: NOT YET.** Every lane is green and the live-table cases are right, but a ViewModel with a layout
  split or sections has different bytes on every JVM run and on Node (F1, proven), and a fragment's declared level is ignored by
  the compiler (F2, proven). Both are small fixes; both must be in `v0.12.0`, the upgrade release.

## Owner summary

1. **All four builds are green on fresh clones.** Rachana `./mvnw clean verify`: 1,147 Java tests, 44 Node tests, 0 skipped, four
   lanes green, no temporary folder left. Lake `tools/ci/check.py`: all green, the parity kit stable over two runs. Tools: 123
   tests. Elements: 392 + 36 Node tests and 196 browser/static tests green; one rate budget test failed at machine load 50 (F8).
2. **Blocker (Rachana):** `layout.split` and `layout.sections` are written from `Map.of`. Six JVM runs of the same Sutra gave four
   different byte sets; Node gave a fifth. No case and no corpus Sutra has a split, so nothing noticed. The guard added after gate 2
   covers three packages of one module.
3. **The 93 newer cases** (`live/*` 51, `protocol/window` 12, `write` 20, `approve` 10): I read every one against PROTOCOL 8, 10,
   15, 16 and LIVE_TABLES 1 to 5. **93 of 93 expected answers are right.** Three are weaker than their names, three pin behaviour
   the specification only permits (F12).
4. **The upgrade tool is fixed:** the three runs of gate 2 section 10.5 now behave (idempotent, a fragment's key read and written,
   1.2 text left alone). The corpus proof is real: 520 files, 0 refused, 478 of 478 compiles and binds identical.
5. **But the compiler does not read a fragment's level.** A `rachana: 1` fragment inside a `rachana: 1.2` Sutra is read at 1.2
   (`0.1 + 0.2` gives `0.3`, not the 1.0 double). SPEC 2.1 rule 4 says the key is its level (F2).
6. **Security:** the server's gate (Host, Origin, CSRF on writes, sessions, CSP), the write guard's order, fail-closed without an
   authoriser, the exactly-once outbox and the one HTML escape in Elements are sound. Small gaps only (F11, F14).
7. **Blocker (Lake):** concurrency is proven by one case with two different dates. On the **same** date, with `mode:
   replace-date` and `--no-lock` (two machines on one store), Iceberg keeps every writer's rows (140 where 30 or 40 is right);
   Delta keeps one and fails the other three loaders; the files store keeps the last. Three stores, three answers (F5). Storage
   parity is also claimed closed and is not: the files store cannot live on S3 or in memory (F4).
8. **API:** Rachana lost 32 lines since `v0.11.0`, all 32 logged; 4,982 lines, 287 stable. Lake has no removals log yet (fine
   before its first tag).
9. **Versions:** every product still says a snapshot version; Lake builds against Tools `1.0.0-SNAPSHOT`. The tags go on version
   commits, in the order Tools, Lake; Elements; Rachana (F7, section 5).
10. `main` and the tags are as the owner left them (section 7). Eight owner decisions are in section 6.

## 1. What I ran

| Check | Result |
|---|---|
| Tools `./mvnw clean install` into a scratch repository (`-Dmaven.repo.local`, tail `~/.m2`) | green in 19 s: **123 tests, 0 failed, 1 skipped** (`LibreOfficeOpensTest`: opt-in, needs `-Dtools.test.soffice` and LibreOffice); guards green |
| Lake `python3 tools/ci/check.py` with `MAVEN_ARGS` naming the scratch repository | **all green**, 4 min: 441 Java tests, 0 failed, **1 skipped** (`CodecsTest`, `@Disabled`, a listed split exemption); delta-rs interop both directions (deltalake 1.6.6, pyarrow 26.0.1): 3 + 3 + 2 run, 0 skipped; Python path 116 passed; parity kit 385 results twice, **stable: yes**; coverage 69 of 106 items (37 in the baseline), 50 cases, none removed; against the reference `9ce7c3170`: 93 identical, 3 known differences (the Iceberg undo, listed), 289 beyond the reference; the Python row 50 of 50 equal to its Delta case |
| Rachana `./mvnw clean verify` (in `java/`) | green in 4 min 26 s: **1,147 Java tests, 0 skipped**; 44 Node tests; `tmp_leaks: no temporary folder left behind`; clone clean afterwards |
| Rachana conformance lane | 2,696 cases in 134 files, **0 unverified** (counted from the files). Pass at 1.0: 2,028; at 1.1: 2,319; at 1.2: 2,533; JVM = Node at each level; 0 failing |
| Rachana protocol lane | reference 57 pass, Python host 57 pass (the 90 others are live-table cases neither serves), **live server 147 of 147**; 0 fail |
| Rachana Python lane, browser lane | 98 passed; browser lane green |
| `tools/js/build_js.py` | green: runtime Brotli 516,899, tools 542,120 (inside the size guard); runtime and tools bundles: 0 verified failing at 1.0, 1.1, 1.2 |
| `tools/libraries/build.py --gallery` | green: LG1 to LG5 and LG8 pass, 147 examples, both libraries packed and verified, gallery 33 + 57 pages |
| Elements `tools/run_tests.py` (Playwright 1.64.0, `DRISHTI_RACHANA` = my clone) | Node 392 pass, 0 skipped; Node budget 36 pass; pytest `-n 8`: **196 passed**, 0 skipped (the live-edit 409 test among them, at load about 30); pytest budget: **3 passed, 1 failed** at load 50 (F8); re-run: section 1.1 |
| Elements pack | rebuilt from tag `v3.2.0` with the documented command: **byte for byte** the pack Rachana vendors (`js/vendor/drishti-elements/3.2.0`, 12 files) |
| Layout determinism probe (mine) | F1: 6 JVM runs, 4 distinct outputs; Node a fifth |
| Upgrade probe (mine) | the three runs of gate 2 section 10.5 and a fragment without the key: all correct |
| Mixed-level probe (mine) | F2 |
| Lake same-date race probe (mine) | F5, section 1.1 |

### 1.1 Re-runs on a quieter machine

**Elements budget pass**, run again twice when the load had fallen to about 15 (it rose to 30 during the second run):
`pytest -q -rs tests -n 0 -m budget`: **4 passed**, twice. So the failure of section 1 is load, not code (F8).

**Lake, writers on one date.** A case of my own in my clone (not pushed): one load of 40 rows for 2026-10-27, then **four
loaders started together** with `--no-lock`, two with the same 40 rows and two with 30 other rows of the same date; run with
`tools/tck/tck.py --only concurrent/zz --storages local,mem --loaders java`.

| Mode | Runs | Delta (local, memory) | Iceberg (local, memory) | Files (local) |
|---|---|---|---|---|
| `append` (the default) | 3 | 180 rows: every load kept once | 180 | 180 |
| `replace-date` | 4 | **30 or 40 rows** (one writer's); one loader exits 0, **three exit 1** | **140 rows, every run**: all four writers' rows (40 + 40 + 30 + 30); all four exit 0 | **30 or 40 rows** (the last writer's); all four exit 0 |

Appends are right everywhere (15 of 15). A concurrent replace is right on Delta and the files store, with different manners
(the losers fail, or the last wins), and **wrong on Iceberg**: the date holds four loads. One after another the same replace is
right on Iceberg (`load/rerun-replaces-the-date` passes); only writers in flight together show it.

## 2. The questions asked of this gate

### 2.1 Are the newer cases right?

All 2,696 cases are flagged `verified: true`. Gate 2 sampled 70. Here I read **every** case of the suites built since:
`live/declaration` 9, `live/merge` 9, `live/overlay` 12, `live/readers` 11, `live/resync` 5, `live/retention` 5,
`protocol/window` 12, `protocol/write` 20, `protocol/approve` 10: **93 cases**, each expected answer against PROTOCOL 8.1 to 8.4,
10.1, 15 and 16, LIVE_TABLES 1.1 to 1.6, 2, 3, 5 and SPEC 6.2, with the arithmetic done by hand (orders, totals, clocks, versions).

**93 of 93 are right.** Checked by hand among them: the merged orders of `live/overlay` (ties broken by key, a column the source
does not keep sorted as null after the live rows, `nulls: first`, keyset and offset pages after a removal); the retention clocks
(12:00 start, `ttl: 1h` by column, `30m` by arrival across two 20-minute steps, `max-rows` oldest first); each refusal's status in
the guard's order (422 shape, 405, 403 switch, 403 column, 404 row, 403 host, 422 rules, 409 version); the four-eyes cases (an
approver sees others' held changes in scope, masked as the table is; never their own; a non-approver gets 403 and learns nothing
of the change; out of scope and unknown both 404); the `can` lists in their specified order; the audit events and who is `caller`.

Not verified by me: the other 2,603 cases (gate 2 sampled 70 of them, 70 right; the lanes prove every runtime agrees).

The exceptions are in F12: three cases cannot assert what their names say, and three pin behaviour the text only permits.

### 2.2 Determinism

- **Rachana: F1 (blocker).** The lanes compare JVM with Node on every case, so any leak a case covers is caught at once. The leak
  is where no case looks: layouts. The class is wider than the one line (list in F1).
- **File and listing order:** every `Files.walk` and `Files.list` in Rachana's main code is sorted (sweep, eight sites); Lake sorts
  its inputs by path (`Inputs.java:90`, `RowFeed.java:87`, `LocalStorage.java:91`) and collects parallel writers in task order
  (`DateWriter.java:68`). No parallel stream, no unordered `toMap` in either.
- **Lake ordering:** the parity kit is byte-stable over two runs (385 results). "Newest load wins" is one rule
  (`com.ash.lake.rows.LoadOrder`) on all three stores, with `read/newest-wins-same-millisecond`. Its first key is the loader's
  wall clock, not the commit order: F10.
- **Lake, by design, not byte-stable:** data file names carry a random UUID (`DateWriter.java:125`); the memory lake stamps a row's
  arrival with `Instant.now()` (`MemoryLake.java:438`). Neither changes the rows a reader gets.

### 2.3 Security

Read: `RachanaServer.java` (the request gate), `Access.java`, `WriteGuard.java`, `Outbox.java`, `FileOutboxStore.java`,
`FileTableWriter.java` (head), and the Elements escape and markup sites.

- **Server.** `hostAllowed` runs first on every request in every auth mode (`RachanaServer.java:155`); every `POST` passes
  `sameOrigin` (`:169`); a protocol write needs the session's CSRF token as `X-Rachana-CSRF`, or in header mode the header itself
  (`:248`, `:497`); bodies are capped; sessions are `HttpOnly`, `SameSite=Lax`; CSP is `script-src 'self'`, `object-src`/frames
  closed (`style-src` still allows `'unsafe-inline'`, as at gate 2). Sound.
- **Authoriser, fail closed.** `Access.editable` gives nothing without a listed role (`Access.java:54`); `mayEdit` re-checks every
  write; an approver never decides their own change. In the engine a write without an authoriser is refused before the host is
  asked (`WriteGuard.java:160`: no name to write as), and the case `live/readers#without-an-authoriser-reads-are-refused` holds.
- **Live writes.** The guard's order leaks nothing: a row outside the reader's scope and an absent row give the same 404 and
  words (`WriteGuard.java:200` to `207`); masked columns are never editable, never in an audit `from`/`to`, never in a conflict's
  `by`/`at`. The outbox journals before it acknowledges (`Outbox.java:129`, `force` on every append), dedupes a change id under
  one lock, never lets a later change overtake a failed one, compacts through an atomic move. `FileTableWriter` records `writing`
  and `applied` around an atomic replace and runs the version guard again at the file.
- **Elements.** One `escapeHtml` (`drishti-core/src/drishti_dom.js:27`, five characters), aliases only elsewhere; no `eval`,
  `new Function`, `insertAdjacentHTML`, `outerHTML` in the libraries; `h()` refuses `on*`, `style`, `src`; no external URL at run
  time in either product (sweep, confirmed for the sites I opened).
- Gaps: F11 (four small ones in the write path), F14 (the toast's host API).

### 2.4 API surfaces

- **Rachana.** `java/api-surface.txt`: 4,982 lines over seven modules, 287 `[stable]`. Against `v0.11.0` (marks stripped): 32
  lines gone, **all 32 in `java/api-removals.txt`** with date and reason (the fragment-level `upgrade` overload; 31 members of the
  experimental `agg` classes folded onto a shared base, inherited now). One logged line was never in `v0.11.0` (harmless).
  `Final.encode/decode/isEncoded` and `UpgradeRule.CATALOGUE_ARGUMENTS` are additions.
- **Lake.** `java/api-surface.txt` 1,556 lines; a spot check of every public top-level class found none missing (sweep). No
  removals log: a removal is "rewrite the file". Acceptable before the first tag; needed from `v0.9.0` on (F15).
- **Tools.** A surface file per published module; "only grows" is the rule from `v1.0.0`.
- **Elements.** Public names were removed after `v3.2.0` (F3).

### 2.5 Duplicates: the audit against the code

Done as [I7B_DUPLICATES](I7B_DUPLICATES.md) planned for G3, confirmed in the code: E1 (one escape), E3 (one search reader), E4
(one aggregation engine), S11 (aliases gone), Q3 (`agg` shared base), Q4 (one program named `rachana`), T1, T3, T6 (mostly), T10
(Lake on the Tools guard kit), the gallery's hand-made buttons (0 left). **Not done:** F6 (Lake: S4, S8, two temp-folder
by-passes) and F13 (Rachana Q1, part of the default-level fold; Elements E5, E7's library half).

### 2.6 Load-sensitive tests

- The known one, `test_a_409_says_who_changed_the_row_and_offers_to_edit_again`, **passed** in the `-n 8` pass at load about 30.
- `test_ten_thousand_a_second_into_a_grid_once_a_frame` **failed** in the budget pass, run alone, at load 50 from other lanes
  (F8). It measures a rate on a shared machine; running it alone does not help when the machine is not alone.
- Rachana and Lake: no failure and no retry seen under the same load.

### 2.7 The upgrade tool and the corpus proof

- `Upgrade.java` reads each file's level from its own key, a fragment's as a Sutra's; `--from` is gone (logged in
  `api-removals.txt`). My runs on a Sutra and a fragment that call `range(list, field)`:
  run 1 rewrites both to `span` and declares `rachana: 1.2` in both; run 2 changes nothing (exit 0); a 1.2 sequence
  `sum(range(1, 4))` written into the upgraded fragment is **left alone**; a fragment without the key is read as 1.0, rewritten and
  gains the key. All four are right.
- `compat/upgrade/drishti-compat-corpus-2.9.11.i7a.report.json` (compared by `UpgradeCorpusTest` in the build): 520 files (478
  Sutras, 42 fragments), 520 changed, 0 refused, 0 blocked; 478 compiles compared, 0 differences; 478 binds replayed, **478
  identical**; upgrading the upgraded text changes nothing. By rule: `declare` 520, `exact (review)` 18 flags, `text-code-points
  (info)` 6 flags.
- What the report does not prove: **no rewrite rule fires on the corpus** (only `declare`). `span`, the named-argument and the
  catalogue rules are proven by `compat/upgrade/cases.yaml` and the unit tests alone. That is acceptable, and worth one line in
  the G4 run book: the 18 `exact` flags are to be read by a person before Drishti's Sutras are declared 1.2.
- Open: F2 (what the key means to the compiler).

### 2.8 Versions across the stack

| Link | State |
|---|---|
| Rachana vendors Elements | `drishti.elements.version` 3.2.0 (`java/pom.xml:38`); the folder, its manifest and `v3.2.0` agree byte for byte |
| Elements tests pin Rachana | `3cf6fc49` (the G2 base) for the live host and the fire fixture; the ViewModel schema synced at `19b16da`. Elements develop is therefore tested against an older Rachana than the one reviewed here: move the pins at the 3.3.0 release |
| Lake uses Tools | through `tools-bom`, `drishti.tools.version` = `1.0.0-SNAPSHOT` (`lake-bom/pom.xml:42`); seven modules and the guards test jar |
| Rachana uses Tools | none, by design: confirmed (no `com.ash.tools` in any Rachana pom). The cost is that Rachana keeps its own CSV and JSON text code |
| Versions written | Rachana `0.11.0-SNAPSHOT` (pom), `0.11.0` (`Facade.VERSION`), `0.11.0.dev0` (Python), `0.11.0-snapshot` (five npm packages); Lake `0.9.0-SNAPSHOT`, Python `0.9.0.dev0`; Tools `1.0.0-SNAPSHOT`; Elements `3.2.0` with unreleased breaking work on top |

## 3. Findings

Severity: **blocker** (no tag, and G4 must not build on it), **must-fix-before-tag**, **later** (before the product's `v1.0.0`
unless said).

| # | Severity | Finding | Evidence |
|---|---|---|---|
| F1 | **blocker** (Rachana) | **A ViewModel's layout has no fixed key order.** `ScreenLayout.toView()` writes `split` and each section from `Map.of` with three keys; `ViewBuilder` puts the map into the ViewModel as it is. The bytes change from one JVM run to the next and differ on Node. The same class, not in the ViewModel: the server's home page lists Sutras from `Map.copyOf` of a sorted map (a different order at every start), its pending tables too; a table source's column types; the JavaScript facade's `levels()` answer; the live reference server's error and outbox answers; the audit event's extra fields. The guard `ScreenWireOrderTest` reads three packages (`wire`, `view`, `bind`) of `rachana-core`; gate 2 left "guard not widened" open. No conformance case and no corpus Sutra has a split or sections, which is why every lane is green. | `ScreenLayout.java:118, 126`; `ScreenGrid.java:30`; `ViewBuilder.java:207`. Probe: one Sutra with `split`, one with `sections`, run through `rachana_conformance.py --jvm` six times: `split` keys came out `between, remember, resizable` (2 runs), `remember, resizable, between` (3), `resizable, between, remember` (1); sections in three orders; `--node`: `remember, between, resizable`. Also `Project.java:193` with `RachanaServer.java:262`; `ServerTables.java:217`; `TableSource.java:47`; `Facade.java:103`; `WriteGuard.java:373, 481`. **Fix:** ordered maps at these sites; the guard over every main source of the seven modules (`Map.of` with two or more keys, `Map.copyOf`, `Set.of` iterated); binder cases for `split`, `sections`, `sticky`, `phone` and `tablet`; then two JVM runs of the conformance lane compared in the build. |
| F2 | must-fix-before-tag (Rachana) | **The compiler ignores a fragment's `rachana:` key.** SPEC 2.1 rule 4 says it "is its level in exactly the same way"; the upgrade tool reads and writes it; the compiler accepts the key and expands the fragment at the level of the Sutra that uses it. In a tree upgraded half way the arithmetic of a 1.0 fragment silently becomes exact. The frozen-to-be cases themselves hold typed fragments (a 1.2 feature) that declare `rachana: 1`. | Probe: a fragment `rachana: 1` with `value: "0.1 + 0.2"` used by a Sutra `rachana: 1.2` binds `"text": "0.3"` (level 1.0 gives the double); its `range(rows, 'p')` is refused with the 1.2 words. `TypedFragment.java:70` lists the key; nothing reads its value. `sutra/fragments-calls` (first case): `rachana: 1` in a typed fragment. **Fix, one of two (decision O1):** (a) a use of a fragment whose level is not the Sutra's is a problem (`RCH-2008`, naming both levels and `rachana upgrade`); or (b) each fragment is read at its own level. (a) is small and matches gate 2's recommendation; the cases' fragments then say 1.2. |
| F3 | must-fix-before-tag (Elements) | **The work after `v3.2.0` is a minor release with breaking changes, not 3.2.1.** It removes public names (thirteen chart-kind aliases and `KIND_ALIASES`; the aggregation aliases `average`, `mean`, `distinct`, `countDistinct`; the changelog says "breaking") and adds API (`Button`, `ButtonGroup`, the helpers moved into `drishti-core`). `elements/drishti_elements.json` still says 3.2.0, so a pack built from develop would call itself 3.2.0 with other contents. | `git diff v3.2.0 67344c2`: 95 files, +2,005 −1,004; `CHANGELOG.md` "Unreleased", "One name per thing (breaking)". **Fix:** version 3.3.0 now on develop; `v3.2.0` is not moved. |
| F4 | must-fix-before-tag (Lake) | **Storage parity is declared closed and is not.** `tck/capabilities.yaml` says "every gap is closed … the three stores are feature-identical" and `files: {}`. The files store reads and writes local folders only, so the kit's memory and S3 rows run Delta and Iceberg and never the files store; `read/deleted-rows-not-read` runs on Delta and files only (Iceberg by a unit test); a slice of a shared table cannot be replaced in the files store. The capability list has no entry for the storage a store can live on, so the gap cannot even be written down. | `FilesStore.java:97` ("local folders only"); `tools/tck/tck.py:150`; `tck/cases/read/deleted-rows-not-read.yaml:5`; `FilesStore.java:349` (sweep). **Fix:** either the files store on the `Storage` interface (S3, memory), or the gap declared in `capabilities.yaml` with its reason and a case, the sentence corrected, and an Iceberg delete-file writer in the kit. Decision O3. |
| F5 | **blocker** (Lake) | **A concurrent replace of one date keeps every writer's rows in an Iceberg table.** Four loaders, `mode: replace-date`, the same business date, `--no-lock` (the documented way two machines write one object store): the table ends with 140 rows where one writer's 30 or 40 is right, in 4 runs of 4, on the local disk and in the memory store; every loader reports `ok`. The replace is an overwrite that removes the date's files as the writer saw them and adds its own, with no validation against the snapshot it planned on (`Operations.java:131`; the other overwrite, at `:212`, has `validateFromSnapshot`). I did not trace which path lets all four through; the outcome is what is proven. Delta detects the conflict (one writer wins, three fail with exit 1); the files store serialises (the last wins). **Three stores give three answers to the same four commands**, which is also a parity defect the kit cannot see: its one concurrency case uses two different dates, is not run on S3, and no test races one date. S3 itself is exercised against a simulator only. The defect is inherited (the reference's code), not new; it is still a wrong number waiting for the first overlapping re-run. | Section 1.1 (my case, 7 runs); `lake-iceberg/.../write/Operations.java:120` to `136`. `tck/cases/concurrent/two-writers-two-dates.yaml` is the only concurrency case; Iceberg `NativeCommitTest` (4 writers × 5 commits, appends) the only unit race. **Fix:** the Iceberg replace validates against the snapshot it planned on (iceberg-core's overwrite with `validateFromSnapshot` and a conflict filter on the date, or its replace-partitions validation), so a loser fails or re-plans; **one** stated outcome for a lost replace on all three stores (decision O5a); the kit gains "N writers, one date" for `append` and `replace-date` on every store and row; the S3 row once against a real S3 or MinIO before `v1.0.0`. |
| F6 | must-fix-before-tag (Lake) | **Leftovers the duplicates plan put in G3.** S8: `PersistedNames` and the read-both of `drishti.*` names are still there, and the default lake root is still spelled `${drishti.data.dir:./data}/delta` (a product's name in Lake; DD-15 allows it until `v1.0.0`, the G3 row of the plan does not). S4: `LakeRelocate` (483 lines, "retired in the next release") is still shipped. T6: two spill folders by-pass `TempFiles` and take `java.io.tmpdir` directly. | `lake-api/.../PersistedNames.java`; `Lakes.java:63, 94, 540`; `lake-load/.../LakeRelocate.java`; `IcebergMaintenance.java:173`; `Loader.java:476`. Note: the relative `./data` is anchored under the install folder, never the working folder (`LakeRoots.java:24`), so this is not an explicit-paths defect. Decision O4: remove now, or record in `SPLIT_EXEMPTIONS.md` with the release that removes each. |
| F7 | must-fix-before-tag (all) | **Release mechanics.** Every product says a snapshot or old version; Lake depends on Tools `1.0.0-SNAPSHOT`, so a tagged Lake would not build against a tagged Tools. Rachana writes its version in eight places (the pom, `Facade.VERSION`, the Python package, five npm packages). | Section 2.8. **Fix:** a version commit per product (no other change), in the order of section 5. Lake's commit also sets `drishti.tools.version` to `1.0.0`. Rachana `v0.12.0` keeps `FROZEN.sha384` at `pre-release` and records no level: both belong to `v1.0.0`. |
| F8 | later, before the G4 drill (Elements) | **A rate budget test fails on a busy machine even when run alone.** `test_ten_thousand_a_second_into_a_grid_once_a_frame` needs 15,000 messages sent in three seconds of wall time; at load 50 the page sent 11,300 (p95 exactly 50 ms, the other limit). A drill on a shared machine will hit this. | `tests/test_elements_events_browser.py:285`; my run; green twice at load 15 to 30 (section 1.1). **Fix:** measure what the page did with what arrived (applied per frame, p95 of the frame work in CPU time, as the Node budgets do since gate 2), not how much the sender managed to send; or make the run refuse to start the budget pass above a stated load. |
| F9 | later | **"Nothing skipped" is not quite true in two builds,** and neither build fails on a skip as Rachana's and Elements' do. Tools: `LibreOfficeOpensTest` (opt-in). Lake: `CodecsTest` one `@Disabled` (a listed split exemption, "L9 gives it a pack of the kit's own"). | `tools.log`: `Tests run: 17 … Skipped: 1`; `CodecsTest.java:116`. Give the Lake test its pack or delete it; let `check.py` and the Tools build print the skips they allow and fail on any other. |
| F10 | later, decision O5 (Lake) | **"Newest load wins" orders by the loader's clock first.** Commit order (the table version, the sequence number) is used only between runs of the same millisecond. Two machines whose clocks differ, or a long load that starts first and commits last, give a "newest" that is not the last committed. `--run-id` text is compared as a time. | `LoadOrder.java:compare`; CHANGELOG "The newest load, one rule". It equals the reference (Drishti at the freeze), which is why it is so. Accuracy first says commit order should lead; that is a behaviour change against the reference, so it is the owner's. |
| F11 | later (Rachana live) | Four small gaps in the write path. (a) An `add` is not tested against the reader's row scope: the engine checks scope only on an existing row, so a reader can add a row they cannot then see unless the host's `mayEdit` refuses. (b) Two approvers deciding one change at the same moment: the loser gets the outbox's `IllegalStateException` (a 500), not the `409` the protocol promises. (c) `decide` calls `a.who(caller)` with no authoriser installed (a bridge host with a holding sink): a null pointer, refused but as a 500. (d) A paged table with `retention.max-rows` shows the source's older row again after a trim (LIVE_TABLES 1.4, case `live/resync#a-trimmed-paged-table-resyncs`): by design, but the engine's version guard then passes a write based on the old version and only the sink's second guard stops it. | `WriteGuard.java:200` to `219`; `Outbox.java:189` (`decide`) with `WriteGuard.java:491`; `WriteGuard.java:455`; LIVE_TABLES 1.4. Fix (a) to (c) in 0.12.x; say (d) in LIVE_TABLES 1.4 and require the sink's guard there. Also: a CSV file written by `FileTableWriter` takes a user's cell as it is (a cell starting with `=` is a formula when the operator opens the file in a spreadsheet). |
| F12 | later (Rachana cases and text) | **Cases weaker than their names:** `protocol/window#the-schema-offers-windows` does not look at `can`; `protocol/write#the-schema-offers-write-to-an-editor-only` cannot assert that the reader without the right has no `write` (subset comparison; `ServerWriteTest:180` does assert it); `protocol/window#sse-windowed-stream-starts-with-subd` does not check the `sub` id. **Cases that pin what the text only permits:** `protocol/window#a-windowed-resume-with-changes-missed-reads-again` (`recovered: false` for a windowed resume: PROTOCOL 16 does not say it, 8.3 allows it); `protocol/write#a-failed-send-stays-queued-and-is-tried-again` (nothing overtakes across **keys**; LIVE_TABLES 1.6 says "once per key in order"); the audit of a decision (no `write-submitted` for the decision itself: PROTOCOL 15 implies it, does not say it). **Stale text:** `LIVE_TABLES.md:202` still says `RCH-2058` where SPEC and the case say `RCH-2060` (listed at gate 2). | The files named. Write the three rules into PROTOCOL 15 and 16 and LIVE_TABLES 1.6; give the format an "absent" assertion or keep such checks in unit tests and name the case for what it checks. |
| F13 | later (Rachana, Elements) | **Duplicates left.** Rachana Q1: the JSON string escaper is still written again in `Cases.java:753` (lower-case hex, also 0x7f) and `LangJson.java:96` (upper-case hex), and in three tools; the default level is still a literal `"1.2"` in `LibraryGates.java:77, 466` and `LibraryExports.java:80`, outside `DefaultLevelGuardTest`'s reach. Elements: 147 hand-made buttons in the libraries while the guard allows 167 (lower the number to what is there, so it can only fall); a second number-to-text in `drishti_pivot-answer.js:175` (E5); a second condition evaluator in `drishti_chart-data.js:183` beside core's filter; three YAML implementations across the two products (Core's, js-yaml in Studio, the designer's own). | Sweep, with `LangJson.java:96`, `Cases.java:753` and `test_elements_guards.py:182` confirmed by me. DD-18 puts these before each `v1.0.0`. |
| F14 | later (Elements) | The toast's host API takes markup (`html`), a link (`href`) and any attribute (`attrs`) as given: no `safeUrl`, `on*` attributes not refused, unlike `h()`. The chart tooltip's template strips `style` only. No library caller passes data there today. | `drishti_overlay.js:144` to `151`; `drishti_chart-panels.js:311`. Run `href` through `safeUrl`, refuse `on*` in `attrs`. |
| F15 | later (Lake) | No API removals log (needed from the first tag); 37 items no kit case covers (19 configuration keys, 12 `.ready` keys, 4 codes, 2 flags); `lake-files` has no test of its own (the kit is its only proof); a bare `source.files` glob is taken from the working folder (`Inputs.java:151`); the memory lake holds a table's lock across the source read (`MemoryLake.java:282` to `290`); Hadoop still writes `.crc` side files into Iceberg tables on the local disk (hidden files the product causes, though not its own). | The files named; `tck/coverage-baseline.txt`. |
| F16 | later (Rachana) | Rachana has no Tools dependency by design, so it keeps its own CSV, JSON and temp-folder code beside Tools' (`tools-csv` has the formula guard `FileTableWriter` lacks). State the rule and its cost in DECISIONS, or let `rachana-server` alone (not Core) use Tools. Decision O6. | Section 2.8; F11. |

## 4. Owner rules, one line each

| Rule | Held? |
|---|---|
| No duplicates, no punting | Mostly: F6, F13 are the leftovers; none is hidden |
| One way per job | Yes in what was built (one `rachana`, one upgrade implementation, one escape, one aggregation engine, one load-order rule) |
| Accuracy first | F5 (a date loaded four times), F1 (bytes), F2 (level), F10 (what "newest" means) |
| Storage parity | F4, F5 |
| Standard libraries | Yes: Delta Kernel, iceberg-core, parquet-hadoop, AWS SDK v2; no own signing or file formats; the stated xlsx exception; own gzip member reader only (sweep) |
| Explicit paths | Yes: Rachana's server refuses bare relative paths (`ServerConfig.java:546`), Lake anchors under the install folder, Tools `PathAnchors` refuses the working folder. One exception: F15's bare glob |
| No hidden files | Yes for what the products write (`commit.lock`, `*.writing`, `*.compact`, `*.part`: all visible). Hadoop's `.crc` in F15 |
| Windows, no `.exe` | Yes: nothing native is launched; launchers run Maven, Node, Python or `java` |
| Session isolation | Yes: no mutable static in `rachana-server`, `-live`, `-data`; per-reader state is the view and the window. Elements keeps page-wide registries by design (kinds, calendars, the toast tray) |
| No AI attribution | Yes: every commit of the four repositories is `ajsinha`; no attribution line |

## 5. The commits to tag

No commit reviewed here is taggable as it is: each needs its version commit (F7), Rachana needs F1 and F2, Lake needs F5. In this order:

| # | Repository | Tag | On | Before it |
|---|---|---|---|---|
| 1 | `drishti-tools` | `v1.0.0` | the version commit on top of **`3ef8e9d`** (version `1.0.0`, changelog heading; nothing else) | nothing else |
| 2 | `drishti-lake` | `v0.9.0` | a commit after **`32819cd`** that holds F5, F4 and F6 and the version commit (version `0.9.0`, `drishti.tools.version` `1.0.0`, Python `0.9.0`) | **F5 with its kit cases**, F4 (at least the declared gap and the corrected sentence), F6 (or the exemptions); `check.py` again against the installed Tools tag; a short re-check by this gate |
| 3 | `drishti-elements` | `v3.2.0` stays at **`787f97d`** (already tagged; do not move). Next: `v3.3.0` on develop after F3 and F8, pins moved to the Rachana tag | no 3.2.1 |
| 4 | `drishti-rachana` | `v0.12.0` | a commit after **`4d7483fa`** that holds F1 and F2 and the version commit; it keeps vendoring Elements **3.2.0** | F1 with its guard and cases, F2, the four lanes green twice with the same bytes. A short re-check of that commit by this gate (an hour) before the tag |

## 6. Owner decisions needed

| # | Decision | Recommendation |
|---|---|---|
| O1 | F2: what a fragment's `rachana:` key means to the compiler | **Refuse a use across levels** (`RCH-2008` naming both levels and `rachana upgrade`). One meaning per tree, no second arithmetic inside one screen; the cases' and libraries' fragments say 1.2 |
| O2 | F3: the next Elements version | **3.3.0**, and keep the rule: a removed public name is never a patch release. Rachana `v0.12.0` stays on 3.2.0; it moves to 3.3.0 in G4 with the pins |
| O3 | F4: the files store on S3 and in memory | **Declare the gap now** (capability `storage`, reason, case) and tag `v0.9.0`; decide before Lake `v1.0.0` whether the files store goes onto the `Storage` interface or is named a local store for good. The owner rule says identical, so the default answer is: build it |
| O4 | F6: Lake's read-both of `drishti.*`, `LakeRelocate`, the spelled default root | **Remove in `v0.9.0`** (the plan's G3 row); the owner's lakes are rebuilt in G4 (D8) anyway |
| O5a | F5: what a loader that loses a concurrent replace of a date does, on every store | **It fails with a clear message and exit 1, as Delta does today** ("the date was replaced by another load meanwhile; run again"). A silent "last wins" hides an operator's mistake; the files store then checks its base commit the same way |
| O5 | F10: newest load by clock or by commit | **Commit order first**, the run's time only as the name. It differs from the reference only when clocks disagree or loads overlap; record it as a known difference with the reason |
| O6 | F16: Rachana without Tools | **Keep Core free of it; allow `rachana-server` to use `tools-csv` and `tools-files`** when Tools is tagged, so the server's file writer gets the formula guard |
| O7 | The re-check | **Yes, a short one** on the Rachana commit that carries F1 and F2 and on the Lake commit that carries F5, F4 and F6, before the tags of section 5. Tools can be tagged now |

## 7. `main`, tags and heads

`git ls-remote` at 17:58, after the last run: every branch reviewed was still at the commit reviewed.

| Repository | `main` | `develop` | Tags |
|---|---|---|---|
| `drishti-rachana` | `3cf6fc49` (the G2 base) | `4d7483fa` | `v0.9.0`, `v0.10.0`, `v0.11.0` |
| `drishti-elements` | `787f97d` (= `v3.2.0`) | `67344c2` | `v2.4.0`, `v3.0.0-rc.1`, `v3.0.0`, `v3.1.0`, `v3.2.0` |
| `drishti-lake` | `32819cd` | none | none |
| `drishti-tools` | `3ef8e9d` | none | none |
| `drishti` | `9ce7c3170` (untouched) | | |

Lake and Tools are developed on `main` with no tag yet: until `v0.9.0` and `v1.0.0` exist, nothing marks a commit of theirs as
released, and a consumer must never build against the branch (Tools' README says so). After the tags, give both a `develop`
as the other two have.

## 8. What was not judged

- Documents (guides, tutorials, the docs wave): separately, later.
- The 2,603 conformance cases outside section 2.1; the Studio's JavaScript beyond its YAML use; Lake's Glue, REST catalog and S3
  Tables paths (no test reaches a real service); the benchmark numbers of `docs/BENCHMARK.md` (the machine was never quiet).
- Drishti itself: untouched and not built here (G4).

Scratch: `~/.cache/drishti-lanes/fable-g3/` held the four clones, the scratch Maven repository, the probes and the logs; it is
removed. Nothing was written to `~/.m2`, to `/tmp` by me, or to any product repository.

## Decisions taken (lead, under the owner's delegation, 2026-11-21)

All eight section-6 recommendations are accepted: a fragment used from a Sutra of another level is refused; Elements next is 3.3.0 (v3.2.0 stays at 787f97d); the files store is BUILT on S3 and memory (owner parity rule: build, not declare); Lake's drishti.* read-both is removed now; a lost concurrent replace fails on every store; newest load is by commit order, not clock; rachana-server may use tools-csv (Core stays dependency-free); a short re-check of the Rachana and Lake fix commits before tagging. Tools v1.0.0 is tagged now on a version commit over 3ef8e9d.

## Re-check (final, 2026-11-21 21:15)

**Verdict: GOLDEN.** Nothing blocks the three tags. Findings F1, F2, F4, F5, F6 and F7 are closed; F3 and F8 are in Elements 3.3.0.

### The commits to tag

| # | Repository | Tag | Commit | Evidence |
|---|---|---|---|---|
| 0 | `drishti-tools` | `v1.1.0` (already tagged) | `4d3567a2d31ca270e2d1e1863d5c8dbdb38f9830` | built and installed by this gate, green |
| 1 | `drishti-lake` | `v0.9.0` | **`eed492ad859d800b06dd3519ba588eaa90ae5f46`** (`main`) | this gate's own `tools/ci/check.py` on this commit: all green (delta-rs interop 3 + 3 + 2, none skipped; Python 142 passed; kit 562 results twice, stable; coverage 79 of 115, 59 cases, none removed; reference 93 identical + the 3 known Iceberg undo differences) |
| 2 | `drishti-rachana` | `v0.12.0` | **`038ea4bb040bad6b2b7890a4ab5e2e88eaa80afa`** (`develop`, "Release 0.12.0") | this gate's full verify of `73c76065` (1,150 Java tests, 44 Node, four lanes, F1 and F2 probes); on top of it only: `ProtocolHost.keys` public, launchers, the two-line Tools 1.1.0 change (`92cf1ff9`) and the version commit, each read as a diff; the builder's full verify green on `92cf1ff9` and `038ea4bb` |
| 3 | `drishti-elements` | `v3.3.0` | **`3363c9f16ec5441aa9570acd8cdcfdcbd8182bdb`** (`develop`, "Release 3.3.0") | the version commit read as a diff (version file, bundles, changelog heading, both Rachana pins); the builder's `run_tests` green with nothing skipped; the ViewModel schema copy is byte-equal to Rachana `038ea4bb` (sha256 `10894f18…ba20`, compared by this gate) |

Order: Lake and Rachana first (both need only the Tools tag, which exists), Elements last, since its pins name the Rachana commit that
becomes `v0.12.0`. No tag of these names exists on any of the three remotes today.

### What changed since the first re-check, and how it was judged

- **Lake `main` moved after `eafbd4e`** (the commit the first re-check and the builder's probes ran on): `bcff31d` (loader rows keep
  their vectors in the stored form `{"$vector": "<column>"}`, Java `RowParser` and Python `rowrule`, kit case
  `load/loader-rows-stored-vectors`) and `eed492a` (launchers only). `bcff31d` changes the loader's row path and is listed under 0.9.0 in
  the changelog, so the tag belongs on `eed492a`, not `eafbd4e`; that is why the full check was run again on it. The diff was read: the
  stored form is taken only for loader rows, only when the object is exactly the one field naming the vector's own column; a null column
  is no vector; a missing column, a non-list value or another column's name is rejected; the kit case holds all seven rows.
  The builder's probes (F5 four-loader same-date replace on Delta, Iceberg and files, local and memory; guards; `lake maintain` on every
  store including S3) ran on `eafbd4e`; nothing in the two later commits touches commit, replace, guard or maintain code, so they stand.
- **Rachana**: finding 1 of the first re-check (Rachana did not build against Tools 1.1.0) is fixed exactly as asked: the pin is 1.1.0
  and `rachana-server` requires `com.ash.tools.csv`. The release commit changes versions only (25 files, one line each); no
  `0.11.0` or `-SNAPSHOT` is left outside the changelog and the compat corpus.
- **Elements**: the 3.3.0 version commit exists; the live-host pin and the ViewModel pin both name `038ea4bb` in full.

### Cross-stack versions at the tags

| Link | State |
|---|---|
| Lake 0.9.0 uses Tools | 1.1.0 |
| Rachana 0.12.0 uses Tools | 1.1.0 (`tools-csv` only, module `com.ash.tools.csv`) — one Tools version for a host that takes both |
| Elements 3.3.0 tests pin Rachana | `038ea4bb` = `v0.12.0` |
| Rachana 0.12.0 vendors Elements | **3.2.0**, not 3.3.0 (`ELEMENTS_VERSION`, `drishti.elements.version`). A host that wants Elements 3.3.0 takes it from the Elements tag itself; Rachana 1.0.0 vendors 3.3.0 |

### Known and open, not blockers (G4 / Lake follow-up)

- Iceberg `sorted-and-filed` is false in read-ids.
- Lake's retention keeps one date fewer than Drishti's old rule: Drishti's G4 move must state the count it wants.
- Rachana 0.12.0 vendors Elements 3.2.0 (above).
- `S3LoadTest` failed once in this gate's first run on `eed492a` with "Port 18751 was already in use" and passed on the rerun and on
  `eafbd4e`: `S3MockServer` picks a free port and binds it later, so a second lane on the machine can take it in between. A test-harness
  race, not product code; fix in the Lake follow-up (bind port 0, or retry on a new port), because a drill with lanes in parallel can meet it.
