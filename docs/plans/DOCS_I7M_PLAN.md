<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti platform. Proprietary: see LICENSE. -->
# Documentation, the content pass (release i7m, documentation only)

**Design only, 2026-11-25. No file is moved by this document.** It follows `DOCS_CONSOLIDATION.md` (i7k, the file-level pass:
400 files to 214). i7m is the content-level pass the owner asked for: fewer guides, one home per fact, only what is built, no history,
tutorials of 15 to 30 minutes.

Every number is counted from the Drishti repository at tag `v2.9.11.i_7l` (`git ls-tree -r`, `git show`, and
`tools/docs/dup_check.py` run on an export of that tag).

## 0. The answer in one table

| | Today (i7l) | Target (i7m) |
|---|---|---|
| Tracked `*.md` | 214 files, 183,849 lines (10,585 of them `CHANGELOG.md`) | about 200 files, about 169,000 lines |
| Guides (`docs/guides`) | 39 files: 26 + 11 BI pages + 2 example files | **17 guides**: 12 product guides (BI is one guide of 11 pages) + 5 measure guides; 4 hand-written references beside them |
| Lines in `docs/guides` | 80,160 | about 68,000 |
| Tutorials (`docs/tutorials`) | 11 + index, 6,606 lines; five say 40 to 60 minutes or run past 1,100 lines | **10** + index, about 4,300 lines, each 15 to 30 minutes |
| Architecture | 9 files, 19,758 lines | 9 files, about 15,000 lines (proposals out, numbering repaired) |
| Computed-measure contracts | 14 + `CSV_FORMATS` + `README`; **12 of the 14 are not built** | 2 built (`FRTB`, `SIMM`) stay; 12 move to `docs/plans/contracts/` (5,991 lines) |
| `docs/plans` | 5 documents, 3,151 lines | 18 documents, about 13,300 lines |
| Real repeated text | 149 pairs, 2,676 windows (see section 4: the 4,464 is mostly the legal header) | 0 outside an allow-list; `dup_check --fail` on |
| Settings rows outside `CONFIGURATION.md` | 205 | 0 hand-written (generated blocks or links) |

Two findings change the brief:

1. **The "4,460 repeated windows" is 4,464 document pairs, and 94% of them are the copyright header.** The header sits in a
   multi-line HTML comment that `dup_check.words()` does not strip. Without it: 253 pairs. Without tables of contents and
   navigation strips as well: **149 pairs, 2,676 windows** of real prose. Section 4 works from that number.
2. **Twelve contracts are not built, not eight.** `CVA, LIM, COLL, NSET, GRP, SACCR, WWR, MC` say "PLANNED, not built" (3,783 lines);
   `PNL, STR, TVAL, TRADING_GROUPINGS` say "proposed, not built (planned, parked)" (2,208 lines). All twelve move.

## 1. The target guide set

Merges are chosen by citation cost. Code, YAML and tests name `DATA_LAKE_GUIDE.md` 263 times, `BANK_DATA_GUIDE.md` 159,
`WORLD_DATA_GUIDE.md` 134, mostly with section numbers; those three stay separate documents. The guides that merge are cited 13 to 84
times and `moved.py --apply` rewrites those.

### 1.1 Product guides (12)

| # | Guide | Purpose · reader | Sources (lines today) | Target |
|---|---|---|---|---|
| 1 | `USER_GUIDE` | every screen a user works in · users | USER_GUIDE 3,733 + LIVE_RESULTS_GUIDE 430 + the "use it" half of AI_GUIDE 1,023; loses Calc (792, to BI Notebook), "The command line" (391, to CLI_REFERENCE), "Administration" (204, to ADMIN_GUIDE) | 3,200 |
| 2 | BI guide (`guides/bi/`, 11 pages) | pivot, models, reports, boards, distribution · analysts | the 11 pages, 12,835; files and section numbers unchanged; navigation strip becomes a generated block; gains Calc | 12,800 |
| 3 | `DATA_GUIDE` | connect data, formats, TLS, export · data engineers | DATA_GUIDE 3,530; section 9 "Connector files" (1,081) keeps the explanation, key tables go to the connector READMEs; "demo data" (413) to BANK_DATA_GUIDE | 2,700 |
| 4 | `DATA_LAKE_GUIDE` | load and run the lake · data engineers, operators | 2,854; lake-file key table (230) becomes a generated block | 2,700 |
| 5 | `BANK_DATA_GUIDE` | the shipped banking data · risk and data people | 1,647 + demo data from DATA_GUIDE | 1,900 |
| 6 | `WORLD_DATA_GUIDE` | the shipped public data and its feeds · analysts | 3,667; "External data feeds" (1,203) shrinks to the list with links to the 13 generated `config/feeds/*.md`; its two tutorials (1,041) become one worked example of at most 300 lines | 2,000 |
| 7 | `BUILD_GUIDE` | packs and screens · pack authors | 5,289; "`pack.yaml`, key by key" (364) and the README standard (248) link the generated references | 4,400 |
| 8 | `ADMIN_GUIDE` | users, roles, lenses, modules, approvals set-up, AI set-up · administrators | ADMIN_GUIDE 1,669 + APPROVALS_GUIDE 518 + AI set-up + USER_GUIDE "Administration" | 2,600 |
| 9 | `OPERATIONS_GUIDE` | run, watch, maintain, recover · operators | 4,389; "Production deployment (Linux)" (812) merges into DEPLOYMENT_AND_QA_GUIDE, its home (12 of the 22 owned-command findings) | 3,500 |
| 10 | `CLUSTER_GUIDE` | Drishti\*net · operators | 2,634; 14 settings rows become a generated block | 2,500 |
| 11 | `DEVELOPER_GUIDE` | the repository, rules, recipes, connectors, IDEs · developers | DEVELOPER_GUIDE 4,325 + IDE_GUIDE 884; section 11 "The API: calling the server" (544) to INTEGRATION_GUIDE; owns the naming rules API_REFERENCE repeats | 4,500 |
| 12 | `INTEGRATION_GUIDE` (new name) | embed a view, data out with Flight, call the API · integrators | EMBED_GUIDE 899 + FLIGHT_GUIDE 710 + DEVELOPER_GUIDE section 11 | 2,000 |

Beside them, unchanged in role: `docs/DEPLOYMENT_AND_QA_GUIDE.md` (1,982, grows by the deployment merge to about 2,400) and
`docs/QUICKSTART_DEVELOPERS.md` (368). Both have a kept PDF.

### 1.2 Measure guides (5): one document per measure, guide and worked tutorial together (owner rule)

`VAR_GUIDE` (7,234), `REGULATORY_VAR_REPORTING` (856), `EXPOSURE_GUIDE` (2,417), `FRTB_SA_GUIDE` (742), `SIMM_GUIDE` (700).
**No split, no merge, no rewording.** Two mechanical changes only: the 67 settings rows of VAR_GUIDE become a generated
`settings` block, and the shared "data files" paragraph moves inside the existing `guide-data` generated block (section 4).

### 1.3 References (not counted as guides)

| Reference | State | Change |
|---|---|---|
| `API_REFERENCE` 3,535 | hand-written | drops the 311 windows it shares with DEVELOPER_GUIDE (naming rules) for a link: 3,200 |
| `CLI_REFERENCE` 4,425 | hand-written | gains USER_GUIDE "The command line"; `setup` text links DEPLOYMENT: 4,600 |
| `RACHANA_REFERENCE` 8,202 | hand-written | **split**: Drishti's own (the 22 panel kinds 1,441 + 888, Screen designer and Build workbench 1,119, Inference 669) stays, about 4,500; the general language becomes one paragraph and a link to the Rachana product's specification (v1.0.1-ide4); the three sections titled "the design" (Chart any table 766, Sutra Next 691, context help 647) are rewritten as built, and any part not built goes to `docs/plans/RACHANA_NEXT.md` |
| `FUNCTIONS` 381 + `architecture/FUNCTION_CATALOGUE` 409 | two lists | one generated page (`docs generate`), the other a `moved:` row |
| `COMMAND_CATALOG` 371 | hand-written | stays if `docs generate` can write it from the mnemonic registry; otherwise folded into USER_GUIDE |
| `admin/CONFIGURATION` 6,785, `contracts/packs/*` 16,324, `config/feeds/*`, pack and connector settings blocks | generated | regenerated, never edited |
| `admin/BENCHMARK` 2,123, `architecture/VIEWMODEL` 919, `contracts/CSV_FORMATS` 1,582, `contracts/FRTB`, `contracts/SIMM`, `contracts/README` | hand-written | untouched, except the README index loses the 12 rows that move |

**Files that go** from `docs/guides`: AI_GUIDE, APPROVALS_GUIDE, LIVE_RESULTS_GUIDE, IDE_GUIDE, EMBED_GUIDE, FLIGHT_GUIDE
(6 merged; INTEGRATION_GUIDE is new).

## 2. Tutorials: 10, each 15 to 30 minutes

File names and numbers stay (Help slugs and links). A tutorial holds steps and expected results; an explanation longer than three
lines becomes a link to the guide.

| File | Task | Time | Change |
|---|---|---|---|
| `01-first-15-minutes` | sign in, open a view, search, drill, export | 15 | 1,297 lines to about 450 |
| `02-pivot-and-report` (new) | build a pivot, save it as a report | 30 | assembled only from worked examples already in the BI pages; if none is step-complete it is dropped and the target is 9 |
| `10-ai-assistant` | ask, refine, save an answer | 30 | 425, light trim |
| `11-correct-and-approve` | correct a trade, approve it | 20 | unchanged |
| `15-connect-your-data` | bind a connector to a database | 30 | unchanged |
| `16-load-your-own-data-into-a-new-lake` | create a lake, load a file, load the next day | 30 | 1,551 to about 700; absorbs the daily part of `12-a-day-of-bank-data` (294), whose runbook already lives in DATA_LAKE_GUIDE sections 16 and 17 |
| `17-data-out-with-arrow-flight` | token to a pandas DataFrame | 20 | unchanged |
| `19-build-a-pack-and-a-screen` | a pack, a kind, a screen | 30 | 1,132 to about 650 |
| `20-embed-drishti` | a view inside another page | 30 | says 40 today; the host-page variants move to INTEGRATION_GUIDE |
| `22-a-cluster-of-three` | three nodes, one console, a node lost and back | 30 | says 45; absorbs the cluster-relevant steps of `21-run-it-in-production` (369, says 60); the rest of 21 is DEPLOYMENT_AND_QA_GUIDE and merges there |

`12` and `21` get `moved:` rows. The worked tutorials inside the measure guides (VaR, 1,494 lines) stay where they are.

## 3. What moves to `docs/plans`

| What | Lines | To | References that change |
|---|---|---|---|
| The 12 contracts not built | 5,991 | `docs/plans/contracts/<NAME>.md`, names unchanged | `config/under-construction.yaml`: every `contracts:` path, and its five `help:` slugs (`docs-contracts-coll, -cva, -mc, -saccr, -wwr`); `tools/docs/guide_data.yaml`; `contracts/README.md` index; `config/docs-corpus.yaml`. Java and pack YAML name only `SIMM.md` and `FRTB.md`, which stay |
| LENSES proposals | about 4,100 of 4,751 | `docs/plans/LENS_PROPOSALS.md`: per lens "Who uses it", "Gaps and proposals", "Mock-ups and build checklist", the data lists, the mock-up index | `LENSES.md` keeps its ten `## N.` headings with "What it answers today" (27 to 58 lines each) and section 10 (the mechanism), about 650 lines, so the 33 uses of `LENSES.md#N-...-lens` anchors stay valid; the two uses of `#74-mock-ups-and-build-checklist` and `#mock-ups` get anchor rows. The 116 non-Markdown lines naming `LENSES.md` (98 files, mostly mock-up pages) are swept by `moved.py` only where they cite a moved anchor. `docs/lenses/mockups/` does not move |
| KERNEL_AND_MODULES plan and history | about 500 | sections 14 "Migration", 16 "What moves from DOMAIN_MODULES.md", and the appended "What is built when", "Build plan inside 2.9.11.i2 + i3", "The move, step by step", "Open questions": deleted where done (git keeps them), otherwise `docs/plans/KERNEL_OPEN_ITEMS.md` | the section number stays as a one-line pointer; numbers are never reused |
| RACHANA_REFERENCE parts not built | found by the lane | `docs/plans/RACHANA_NEXT.md` | Help page keeps its slug |

**Help and under-construction.** The under-construction notes are the one place where the product points a user at something planned,
and that is honest. Default: Help serves the five moved contracts from their new path, with `moved:` rows in `help.yaml`
(`docs-contracts-cva: docs-plans-contracts-cva`, and so on) and the page opening with its existing "PLANNED, not built" line. If the
Help route cannot serve `docs/plans`, the five `help:` entries are removed and the note keeps the `contracts:` path only. No other Help
page names a planned contract.

## 4. Dedup: the real 2,676 windows

**Step 0, the tool (one small change, first commit of lane C).** `dup_check.words()` drops HTML comments (the header), link-only list
lines (tables of contents) and anything inside a generated block. The baseline is then 149 pairs, 2,676 windows. The check also
skips `docs/plans` for settings rows (35 of the 205 sit in contracts that move, 14 in `plans/HIGH_PERFORMANCE_NATIVE.md`).

| Pairs | Windows | What repeats | Rule |
|---|---|---|---|
| `API_REFERENCE` and `DEVELOPER_GUIDE` | 311 | the naming rules for static files, classes and globals | home: DEVELOPER_GUIDE section 3 "Project rules"; API_REFERENCE links |
| Measure guides, `WORLD_DATA_GUIDE`, `bi/GROUPINGS_AND_ANALYTICS`, `bi/PIVOT_SCALE_AND_CUBES` (21 pairs; `EXPOSURE` and `VAR` alone 132) | about 1,000 | the "data files: where the sample lives, its contract, how to point a domain at a database" paragraph | `guide_data.py` writes it inside the `guide-data` block; no hand-written copy |
| Connector READMEs (80 pairs: mongodb/redis 74, activemq/rabbitmq 61, aerospike/cassandra/mongodb/redis 48 to 50 each, delta/iceberg 33) | 917 | loading, business-date and TLS paragraphs | `tools/connector_readmes.py` writes one "Common rules" generated block that links DATA_GUIDE and DATA_LAKE_GUIDE; the fixed sections and front matter of the README standard stay |
| Contracts with each other (31 pairs) | 190 | shared rules | home: `contracts/README.md`; mostly leaves with the 12 moved files |
| `knowledge/README` and `knowledge/risk` | 84 | glossary entries | home: `risk.md`; the README is an index |
| `bi/*` with each other | 50 (1,503 before the navigation strip is excluded) | the navigation strip | generated `nav` block |
| `APPROVALS` and `BUILD` 24, `ADMIN` and `API_REFERENCE` 23, `WORLD_DATA` and `knowledge/world` 15, `HOW_IT_FITS` and `README` 9, the rest | about 100 | single paragraphs | the owner in `config/docs-owners.yaml` keeps it, the other links |

**Settings rows (205).** VAR_GUIDE 67, DEPLOYMENT_AND_QA_GUIDE 19, CLUSTER_GUIDE 14, KERNEL_AND_MODULES 13, OPERATIONS_GUIDE 12,
DATA_GUIDE 7, bi/PIVOT_SCALE_AND_CUBES 7, LIVE_RESULTS 6, IDE 4, CLI 3, bi/GROUPINGS 3, BENCHMARK 1, the rest in documents that move
to plans. Rule: a guide names a key in prose and links `CONFIGURATION.md`; where a table of keys helps the reader (VAR_GUIDE,
CLUSTER_GUIDE, the DEPLOYMENT environment table) it is a generated `settings` block filtered by prefix, the same generator the
connector READMEs use. Defaults and descriptions then have one source.

**Owned commands (22).** OPERATIONS 12 (gone with the deployment merge), CLI_REFERENCE 6 (link), QUICKSTART_DEVELOPERS 4 (allow-listed:
the quickstart must stand alone as a PDF), DEVELOPER 1, IDE 1.

End state: `dup_check.py --fail` passes and joins `docs generate --check` in the release checks.

## 5. Numbering repair

The first numbered series of each document is **not** renumbered: code, YAML and tests cite it in plain text
(`KERNEL_AND_MODULES.md section 3.3` 28 times, `18.5` 27, `2.3` 26, `4.2.1` 16; `THIN_CONSOLE_DESIGN.md section 3` 23, `section 2` 12).
The appended parts take the next numbers.

| Document | Appended part today | Becomes | Anchor rows |
|---|---|---|---|
| KERNEL_AND_MODULES | "The access model" (`### 1.` to `10.`) | `## 19.`, `### 19.1` to `19.10` | 11 |
| | "Drishti distributions" (`### 1.` to `10.`) | `## 20.`, `### 20.1` to `20.10` | 11 |
| | "Domain modules" (`### 1.` to `8.`) | `## 21.`, `### 21.1` to `21.8` | 9 |
| | "Kernel and modules: mock-ups" | `## 22.` | 1 |
| THIN_CONSOLE_DESIGN | "Shared screens" (`### 1.` to `7.`) | `## 8.`, `### 8.1` to `8.7` | 8 |
| | "Drishti Elements in Drishti" (`### 1.` to `10.`) | `## 9.`, `### 9.1` to `9.10` | 11 |

- The 51 rows go below the marker line of `tools/docs/moved.yaml` as anchor-level moves (old slug to new slug, same file), written by
  a small generator from the old and new heading lists, not by hand. `moved.py --apply --write` rewrites the uses: 46 today
  (`THIN_CONSOLE_DESIGN.md#3-one-explain-for-every-number` 18, `#10-drishtis-screens-on-the-libraries` 10, `#2-one-approvals-queue` 3,
  five more in THIN; ten in KERNEL such as `#10-row-rules` and `#8-as-built-the-backend-phase-1-of-the-split`).
- Plain-text citations of an appended part cannot be told from the first series by a tool (`THIN_CONSOLE_DESIGN.md section 3` may mean
  "The target" or "One Explain"). Lane C lists every plain-text citation of sections 1 to 10 of both files, reads the line, and
  rewrites those that mean an appended part (known cases: "section 4: option A as the editor", "section 3: option A as the editor",
  "section 7").
- New guard in `tools/test_docs_consolidation.py`: no document has two headings at the same level with the same leading number.
- Any context-help address into a renumbered heading gets a `moved:` row in `help.yaml` in the same commit.

The same rule governs every merge in section 1: the absorbed guide's sections take the next numbers of the host, with generated anchor
rows; a host section that Help points at is never renumbered.

## 6. Help, About, tests, PDFs and the checks

| Area | Impact | Check |
|---|---|---|
| `drishti-console/config/help.yaml` (58 pages) | 6 guide pages merge (AI, approvals, live results, IDE, embed, Flight), 2 tutorials merge, 5 contract pages move, FUNCTION_CATALOGUE; one `moved:` row each, about 15 rows; one new page (INTEGRATION_GUIDE); the BI pages and every `section:` address stay | `test_help.py`, `test_help_hub_browser.py`, `test_sitemap.py` |
| Module Help (`modules/*/docs/help.yaml`, 12 files) | entries that name a merged guide follow `moved.yaml` | `test_pack_guides.py`, `test_guide_contract_links.py` |
| Context help (52+ section addresses) | no host section renumbered; addresses into absorbed guides and appended parts get rows | `test_help_links.py` |
| `docs/README.md` help map | regenerated by `help_map.py` | `test_docs_help_map.py` |
| About, landing counts | counts of guides and tutorials come from the file system | the README and About count tests |
| `config/docs-corpus.yaml`, `moved.yaml` | regenerated by `corpus.py --write` after the rules change; anchor rows appended | `test_docs_consolidation.py` (totals updated in the same commit) |
| `config/under-construction.yaml`, `guide_data.yaml` | paths and slugs of the moved contracts | `test_contracts.py`, `test_contract_examples.py` |
| Code comments, YAML, tests naming a document (362 test files name a document path) | swept by `moved.py --apply --write` | `moved.py --verify --base v2.9.11.i_7l`: every changed non-Markdown line is a comment, a test, or a document name |
| Merges | nothing lost | `merge_audit.py` with a new `merge_log_i7m_<lane>.txt` per lane: every dropped block names the place that already says it |
| PDFs (3 kept) | DEPLOYMENT_AND_QA_GUIDE regenerated after the deployment merge; QUICKSTART_DEVELOPERS if touched | `md_to_pdf.py`, page count read back |
| History and "not built" in customer documents | a gate, extended to `docs/architecture` for the words "PLANNED", "proposal", "open question", "phase N" | the existing history gate |
| Final | `docs generate --check`, `dup_check.py --fail`, the link check, the docs and Help test files above, then the Fable gate reads the three merge logs | no drill: no product code changes; `moved.py --verify` is the proof |

## 7. Execution: three Sonnet lanes, no file in two lanes

Worktrees in `~/IdeaProjects/drishti-lanes/`, branch `i7m/develop` from the tag, lane branches off it, backed up as `wip/*`.
Lane C's step 0 merges first (45 minutes); then A, B and C run together.

| Lane | Files (disjoint) | Work | Time |
|---|---|---|---|
| **C · structure** (starts first) | `tools/docs/*`, `config/docs-*.yaml`, `config/under-construction.yaml`, every `help.yaml`, the tests, `docs/architecture/*`, `docs/contracts/*`, `docs/plans/*`, `docs/README.md`, the five measure guides (generated blocks only), `docs/admin/CONFIGURATION.md` (regenerated), all non-Markdown sweeps | step 0: `dup_check` fix, the anchor-row generator, the duplicate-number guard, the `settings` and `nav` block generators; then contracts to plans, LENSES split, numbering repair, FUNCTIONS page; at the end: apply lanes A and B's rows to Help, sweep, regenerate, PDFs, all checks | 0:45 + 5 h + 1:30 integration |
| **A · use it** | `USER_GUIDE`, `AI_GUIDE`, `LIVE_RESULTS_GUIDE`, `APPROVALS_GUIDE`, `ADMIN_GUIDE`, `guides/bi/*`, `BANK_DATA_GUIDE`, `WORLD_DATA_GUIDE`, `docs/tutorials/*`, `docs/knowledge/*` | guides 1, 2, 5, 6, 8; the ten tutorials; the knowledge dedup | 5 h |
| **B · run and build it** | `OPERATIONS_GUIDE`, `CLUSTER_GUIDE`, `DEPLOYMENT_AND_QA_GUIDE`, `QUICKSTART_DEVELOPERS`, `DEVELOPER_GUIDE`, `IDE_GUIDE`, `EMBED_GUIDE`, `FLIGHT_GUIDE`, `INTEGRATION_GUIDE`, `DATA_GUIDE`, `DATA_LAKE_GUIDE`, `BUILD_GUIDE`, `API_REFERENCE`, `CLI_REFERENCE`, `COMMAND_CATALOG`, `RACHANA_REFERENCE`, connector READMEs (`plugins/*/README.md`, `docs/connectors/**`) and `tools/connector_readmes.py` | guides 3, 4, 7, 9 to 12; the reference changes; RACHANA split last and only if time remains (it is the one item that can slip to the next release without breaking anything) | 6 h |

Rules for every lane: a lane writes its `moved:` rows to its own file `tools/docs/moved_i7m_<lane>.yaml` and its merge log; only lane C
touches `moved.yaml`, `help.yaml` and tests. A sentence that crosses lanes (USER_GUIDE "The command line" into CLI_REFERENCE,
DEVELOPER_GUIDE section 11 into INTEGRATION_GUIDE inside lane B, Calc into BI inside lane A) is cut by the owner of the source and
pasted by the owner of the target from a hand-over file, never edited across. Commits as `ajsinha`, no attribution lines, no push to
`main`, no tag without the Fable gate and the owner's word.

**Do not touch**

- VaR methodology and every formula: VAR_GUIDE sections 15 "Inputs", 18 "The computation", 26 "Tiered memory", its tutorial; all of
  `REGULATORY_VAR_REPORTING`; the method sections of EXPOSURE, FRTB_SA and SIMM guides; `contracts/FRTB.md`, `contracts/SIMM.md`,
  `contracts/CSV_FORMATS.md`. Only the generated-block swaps of section 4 enter those files.
- The text of the 12 moved contracts (moved whole, status line kept).
- The first numbered series of KERNEL_AND_MODULES (1 to 18) and THIN_CONSOLE_DESIGN (1 to 7); any section a Help address points at.
- `CHANGELOG.md`, `RELEASE_NOTES.md`, `THIRD-PARTY-NOTICES.md`, `architecture/DECISIONS.md`, the numbers in `BENCHMARK.md`.
- Generated pages by hand; the README standard's front matter and fixed sections; the vendored Elements files; the copyright headers.
- `docs/lenses/mockups/`, `docs/publications/`, screenshots (no new captures: nothing on screen changes).
- The owner's running checkout `~/IdeaProjects/drishti-platform/drishti`.

## 8. Risks

| Risk | Guard |
|---|---|
| A fact is lost in a content merge (about 12,000 lines leave the guides) | `merge_audit.py` and the per-lane log; the Fable gate samples each log |
| A Help or context-help address breaks (about 15 page moves, 51 anchor moves) | rows written by a generator; `test_help*.py`, `test_help_links.py`; no host renumbering |
| A plain-text "section N" citation now points at the wrong part | the hand-read list of section 5; the duplicate-number guard stops it recurring |
| A Sonnet lane rewords methodology while "deduplicating" a measure guide | measure guides belong to lane C only, generated-block swaps only; diff of those five files reviewed line by line at the gate |
| A planned contract is still served as if built | status line kept; the "not built" gate; `under-construction.yaml` is the only product reference |
| RACHANA split cuts something only Drishti documents | last item of lane B, may slip; every cut paragraph is logged with the Rachana specification section that says it |
| Tutorial trimmed past the point it works | steps and expected results are kept verbatim; only explanation leaves; no step is renumbered inside a tutorial that screenshots name |
| The 362 test files and code comments change in a documentation release | `moved.py --verify` proves comment-or-name only; anything else fails the lane |

## 9. Decisions for the owner (the lanes proceed with these defaults)

1. 17 guides (12 product + 5 measure) rather than 12: DATA_LAKE, BANK_DATA and WORLD_DATA stay separate because 556 citations in code
   name them with section numbers. Overrule to merge them and the count is 15, at the price of that sweep.
2. The BI guide keeps its 11 pages (one guide, one index).
3. World-data tutorials become a worked example inside WORLD_DATA_GUIDE, not an eleventh tutorial.
4. The 12 planned contracts stay reachable from the under-construction notes, served from `docs/plans`.
5. RACHANA_REFERENCE split is in scope but last, and may slip.
