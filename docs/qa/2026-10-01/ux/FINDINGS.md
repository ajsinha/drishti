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
# UX, grammar and documentation QA findings — Drishti 1.13.0 (develop @ 778bac1, 2026-10-01)

Scratch server :18985, console :17985, and a fresh clone followed literally (QUICKSTART, GETTING_STARTED, DEMO_DATA's
Delta path). Activity: LOG.md; screenshots: shots/; scripts and raw outputs beside them (gram.py/gram.out, sweep.py,
contrast.py, calc.py, layout*.py, pivot.py, alerts.py, studio.py, streams*.py, kbd.py, asof.py, ws*.py, signin.py,
docaudit/). Totals: 3 high, 19 medium, 23 low, 5 info.

## High
| Id | Finding | Where | Repro |
|---|---|---|---|
| GRAM-01 | One Sutra with ~800+ nested parentheses in a bind stops the server starting (`Application run failed … sutraRegistry … StackOverflowError`). The recursive EL parser has no depth limit and the registry catches only SutraException/IOException. Docs say a bad file "is not loaded". Fix: depth limit → DRS-2101; per-file guard for StackOverflowError. | el/Parser.java; SutraRegistry.java:217-220 | hold/qa-deep.v1.sutra.yaml in a Sutra directory, start the server **Fixed** in c1ace31 (SutraRegistryTest.aDeeplyNestedSutraIsAProblemNotAFailedStart, PathologicalExpressionsTest.DefaultLimits.theServerStartsAndListsBothFilesAsProblems) |
| GRAM-02 | The same file kills the hot-reload watcher silently (`Exception in thread "drishti-rachana-watch" StackOverflowError`): later valid Sutras are never loaded, /api/v1/sutras/problems stays `{}`, the next restart fails (GRAM-01). | SutraRegistry.java:343 (watchLoop catches only RuntimeException) | drop the file while the server runs **Fixed** in c1ace31 (SutraRegistryTest.hotReloadSurvivesADeeplyNestedSutraAndKeepsLoadingLaterOnes, SutraRegistryTest.anyFailureLoadingOneFileIsThatFilesProblem) |
| UX-01 | Six visible tabs on live views freeze a 7th page completely (`/t` never loads in 60 s, no message; closing one live tab → loads in 0.4 s): one SSE connection per visible tab over HTTP/1.1, browser limit 6 per host. LIVE.md presents this as solved; only hidden tabs release their connection. | live channel; LIVE.md | streams2.py **Fixed** in 1400225 (test_live_tabs_browser.py: test_many_live_tabs_and_a_live_workspace_leave_the_browser_free_and_all_keep_ticking[shared-worker], [leader]; test_live_hub.py) |

## Medium
| Id | Finding |
|---|---|
| GRAM-03 | Very deep or very long expressions: a 5000-term `+` chain loads without a problem, then the view returns HTTP 500 with no DRS code (console shows raw Spring JSON, Studio "HTTP-500 {…}"); contradicts "evaluation is total". **Fixed** in c1ace31 (ElLimitsTest.longChainsThatWouldOverflowEvaluationAreRefusedAtCompileTime, PathologicalExpressionsTest.LiftedLimits.anOverflowIsAPanelProblemOrAFileProblemNeverA500) |
| GRAM-04 | "Case never matters" (QUICKSTART:127, search.html:50) is false for field names: `trd producttype=revolver` → 0, `TRD productType=Revolver` → 84. Unknown fields (`TRD where nosuchfield > 1`) silently match nothing. **Fixed** in 4ffeef1 (SearchFieldNamesTest.fieldNamesIgnoreCaseAsTheGuidesPromise, SearchFieldNamesTest.anUnknownFieldIsAProblemWithTheClosestNames) |
| GRAM-05 | Invalid Sutra inputs silently accepted: duplicate YAML keys (last wins), a second YAML document (ignored), unknown custom tags (ignored), `match.kind: 42`, `priority: high`, a title without `id`, `rows: 5`, `limit: many`, `search: maybe`, `fields: 5`, `series: 5`. **Fixed** in 9db82fa (StrictSutraTest: ambiguousYamlIsAProblemWithItsLine, headerValuesOfTheWrongShape, panelOptionsOfTheWrongType) |
| UX-02 | At 390 px, 77 of 85 pages scroll sideways (419 px); the Live pill and avatar are clipped; admin pages 603 px (nav.css:21,113). **Fixed** in 61ba1d9 (test_page_widths_browser.py: test_no_page_scrolls_sideways_and_images_fit[390], [1600], [2560]) |
| UX-03 | Help-centre screenshots have no max-width: guides up to 1922 px wide at 1600 px, 1460 px at 390 px. **Fixed** in 8ad4307 (test_help.py: test_guide_screenshots_fit_their_column_and_open_full_size; test_page_widths_browser.py) |
| UX-04 | WCAG contrast fails for `--d-faint` in six of seven themes (light 2.83:1, crimson 2.82, terminal 3.32/2.94), used for "No data available", the footer, placeholders, key notes; the light theme's accent as text 3.35–3.98:1. test_contrast.py does not check these. **Fixed** in 0cea59a (test_contrast.py: test_every_text_colour_reads_on_every_ground_in_every_theme, test_labels_on_accent_fills_read_in_every_theme, test_the_light_fallback_is_the_light_theme) |
| UX-05 | With the packs QUICKSTART recommends, Studio opens on a red `DRS-1001 … IRS-48213` (studio_routes.py:49); the landing animation and empty-command example use finance-pack ids that don't exist. **Fixed** in 6966b08 (test_samples.py: test_studio_opens_on_an_entity_of_an_installed_pack, test_studio_without_any_example_opens_empty_with_a_hint, test_landing_commands_come_from_the_installed_packs, test_landing_without_packs_names_no_entity; CommandParserTest.anUnreadableCommandIsToldTheConfiguredMnemonicsNotAPackSample) |
| UX-06 | Workspaces cannot be created without the finance or logistics packs: Starters empty with no message, no "new" control, `/w/<name>` → 404. **Fixed** in ac21b62 (test_workspaces.py: test_without_starters_the_index_says_so_and_offers_a_blank_workspace, test_an_unknown_name_offers_to_create_it) |
| UX-07 | In a workspace, Alt+1..4 stops working once a pane has focus (workspace.js:151 listens on the parent page only); keyboard users are stuck in the pane. **Fixed** in 1598327 (test_workspace_keys_browser.py: test_alt_keys_move_between_panes_even_from_inside_a_pane, test_a_blank_pane_takes_the_focus_on_its_command_input, test_a_message_from_elsewhere_moves_nothing) |
| UX-08 | A past date missing from the dated store silently shows live, ticking data with a wrong reason ("murex-rates is not a dated source"). **Fixed** in 9a33cce (test_terminal.py: test_a_past_date_no_store_holds_says_so_and_does_not_pass_current_data_off_as_live) |
| UX-09 | Pivot row keys in Java scientific notation, e.g. `4.240872601E7` (PivotCube.java:97). **Fixed** in ef0b0be (PivotCubeTest.numericKeysArePlainWithoutExponentOrFloatNoise; test_pivot.py: test_client_engine_writes_numeric_keys_as_the_server_does) |
| DOC-01 | GETTING_STARTED:87 expects `openjdk version "21.`; should be 25. |
| DOC-02 | Calc is dead after the QUICKSTART/GETTING_STARTED walkthrough: neither mentions tools/fetch-pyodide.sh; no Windows way to install the Python runtime. |
| DOC-03 | DEMO_DATA:29 says `--trades N` "replaces the trade days it covers"; it replaces the whole trade table, losing seven sample days and breaking GETTING_STARTED step 11 on earlier dates. |
| DOC-08 | API_GUIDE:1187 gives Notes' forbidden code as DRS-5003; the code throws DRS-5002. |
| DOC-09 | API_GUIDE's "complete list" of error codes misses DRS-6008 and DRS-6009. |
| DOC-13 | DEVELOPER_GUIDE and README list 11 plugins; pom.xml has 15 (redis, mongodb, iceberg, duckdb missing). |
| DOC-16 | Settings read by the code but documented nowhere: drishti.branding.*, DRISHTI_PRODUCT, console ui.*, backend.name, identity seed-display-name / preferences-dir. |
| DOC-18 | About 88 in-app help links go nowhere (/help/connectors 17 dead, /help/plugins 11, /help/configuration 17). |

## Low
| Id | Finding |
|---|---|
| GRAM-06 | `limit 999999999999` leaks a raw Java error "For input string … (DRS-5001)" (SearchQuery.java:160); `limit 0` silently becomes "the first 1 are shown". **Fixed** in 4ffeef1, ee8b39e (SearchFieldNamesTest.aLimitOutsideItsRangeSaysTheRange) |
| GRAM-07 | Unhelpful or misclassified messages: `unexpected '' at 7` for a condition ending early; out-of-range `version` reported as YAML syntax (DRS-2001) instead of DRS-2020; unsorted unknown-function list; tab-indented line hint says "needs a colon"; phrase search shows a Python list with a bad plural. **Fixed** in 9db82fa, 4ffeef1, ee8b39e (StrictSutraTest.expressionMessagesSayWhatIsWrong, StrictSutraTest.anOutOfRangeVersionIsAVersionProblemNotYamlSyntax, SearchFieldNamesTest.aConditionThatStopsShortSaysSo, PhraseParserTest.explainsWhatItReadAndSaysWhatItDidNot, test_studio_check_js.py::test_a_tab_indented_line_is_said_to_be_indented_with_a_tab) |
| GRAM-08 | Preview API 500 on `{"yaml": null}`; malformed JSON → bare 400 with no DRS code. **Fixed** in c007e5b, cc386d6 (StudioTest.aPreviewRequestThatCannotBeReadIsACleanProblem; test_gram_inputs.py::test_a_preview_body_of_the_wrong_shape_is_a_400_problem, test_a_preview_body_that_is_not_json_is_a_400_problem) |
| GRAM-09 | Invalid or future dates are stored in the as-of cookie and break every view for 12 hours. **Fixed** in cc386d6 (test_gram_inputs.py: test_a_date_that_cannot_be_picked_is_refused_and_not_stored, test_a_stored_date_the_server_refuses_is_ignored_and_cleared) |
| UX-10 | DRS codes repeated two or three times in messages ("DRS-2101: DRS-2101 alert expression: DRS-2101 …"; missing.html:23, alerts-page.js:31 and others). |
| UX-11 | Error pages always say "Check the identifier" (also for timeouts and bad dates); unknown kind reported as a missing id; unknown URLs return raw `{"detail":"Not Found"}`. |
| UX-12 | First past-date read after start timed out (DRS-1004) → console 502; not reproduced again (2 s timeout). |
| UX-13 | Six console endpoints return a plain 500 on a non-JSON body. |
| UX-14 | Layout mode lets every panel be hidden, then Save is refused; panels shrink to an unreadable 1 column; the height announcement is off by one. |
| UX-15 | Studio keeps a stale preview after a failed preview; after approval the review page says "No differences"; Studio has no `<h1>`; its editor has no accessible name. |
| UX-16 | A fresh start logs 124 WARN lines. |
| UX-17 | The function-key bar truncates panel titles. |
| UX-18 | The release-notes guide has 16 `<h1>` elements. |
| DOC-04 | QUICKSTART's "every key" list misses F5, F6, Alt+C and Alt+L. |
| DOC-05 | Docs say expression evaluation is total (GRAM-03 shows it is not). **Fixed** in c1ace31 (RACHANA_REFERENCE: size limits; evaluation is total and explained). |
| DOC-10 | DRS-3001 documented but never thrown. |
| DOC-11 | TROUBLESHOOTING's code table has gaps. |
| DOC-12 | RACHANA_REFERENCE gives the problem-code range as 2010–2027 instead of –2031. |
| DOC-14 | CONFIGURATION's plugin-name list misses four. |
| DOC-15 | CONFIGURATION says `require:`; the code reads `extends:`/`requires:`. |
| DOC-17 | DELTA_CONNECTOR gives `row-group-rows` as both 1,000 and 10,000. |
| DOC-19 | GitHub-style double-hyphen anchors break in the app. |
| DOC-20 | ADR-016 gives two different About endpoint paths. |

## Info
| Id | Finding |
|---|---|
| UX-19 | Calc's SyntaxError traceback shows Pyodide internals. |
| DOC-06 | Charts round to six significant digits by default; documented only in PANELS.md. |
| DOC-07 | The `markdown` panel does not render Markdown (documented). |
| DOC-21 | `suggest-limit` "at most 50" applies only to per-request limits. |
| DOC-22 | DRISHTI_STUDIO_SAVE described as "save layouts"; it saves Sutras. |

## What held up
Walkthrough commands and outputs match (build 49 s, generators, 12 packs, health JSON; the eight QUICKSTART commands,
F7/F8/F9, suggestions, Compare, alert/monitor, sign-in/password/lockout, the finance workspace). Grammar fuzz of ~190
cases: most bad input gets located DRS codes, all problems at once; billion-laughs, recursive aliases, `!!java` tags,
files over 3 MiB and nesting over 1000 rejected cleanly. No XSS (titles, markdown, notes, user names, proposals
escaped). No JavaScript errors, CSP violations or broken images across 85 pages at 3 widths; all 39 help guides render.
Keyboard and ARIA: skip link first; the command line a correct ARIA combobox; live regions in layout mode and pivot; F1
opens the right help. Calc: exceptions, missing packages, network/DOM access, huge output, memory bomb, infinite loop
with Stop, recursion all handled; PYTHON_CALC's VaR example reproduces exactly. Pivot limits enforced and announced;
export works. Admin validation and last-admin protection hold; no open redirect. About 250 settings match their code
defaults; counts and version consistent; help.yaml entries and script flags check out.

## Coverage
Docs: QUICKSTART and GETTING_STARTED followed literally (Docker skipped by rule); DEMO_DATA's Delta path; USER_GUIDE,
PACKS, PYTHON_CALC and WINDOWS (read-only) spot-checked. All 20 panel kinds on one view (fresh/sutras/qa/
qa-allkinds.v1.sutra.yaml). 85 URLs at 390, 1600 and 2560 px. All 7 themes, contrast on 15 pages each. Keyboard-only,
Calc, layout mode, Pivot, many live streams, sign-in. Not covered: Firefox, real screen readers, report scheduling,
Docker, other stores; a synthetic mouse drag in layout mode did not trigger (inconclusive).
