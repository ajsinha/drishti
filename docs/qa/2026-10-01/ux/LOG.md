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
# QA-UX activity log (adversarial UX / Rachana grammar / docs)

Tester: QA agent. Repo HEAD 778bac1 (develop). Scratch server :18985, console :17985.

## 2026-10-01T20:38:35-04:00 setup
- Created scratch dirs. Ports 17480/18480 belong to the user's processes (pids 1852550/1852549) and are not touched.

## 20:38 fresh-clone walkthrough (QUICKSTART / GETTING_STARTED)
- `git ls-remote https://github.com/ajsinha/drishti.git` -> HEAD 778bac1 (same as local). Cloned (from the local repo, same commit) into qa-ux/fresh.
- Prereqs: `java -version` -> openjdk 25.0.4.1; uv 0.12.17; python3 3.14.4. GETTING_STARTED Step 1 says the java line "starts with `openjdk version "21.`" (DOC: wrong, and contradicts "25 exactly").
- `./mvnw -q package -DskipTests` (JAVA_HOME=25): rc 0, 49 s (warm ~/.m2), produced drishti-server-1.13.0-exec.jar (matches doc).
- `uv venv console/.venv` + `uv pip install ... -r console/requirements.txt`: 26 packages incl. FastAPI, Uvicorn, Jinja2, httpx, PyYAML, Markdown, pytest (matches QUICKSTART list). uv chose CPython 3.13.
- Step 4 `make_data.py --lake data/delta`: rc 0, "lake: 17910 rows under data/delta (reference, market, trading, risk, credit, collateral)" + data/feeds/fixing/SOFR-HISTORY.csv: matches.
- GETTING_STARTED Step 5 other generators (liquidity..economics) all rc 0; `ls data/delta` lists the 13 domains the doc names: matches.
- `tools/load-delta.sh data/delta --trades 10000`: 7.3 s, 69 MB; 10,000 trades x 3 days (2026-09-28..30). (Progress lines are \r-joined in a log; cosmetic.)
- Server: started with QUICKSTART env + DRISHTI_PORT=18985, -Xmx2g (qa-ux/start-server.sh). "Started DrishtiApplication in 5.797 seconds". health UP JSON matches. /api/v1/packs -> 12 names starting banking-core, market-data, trading: matches.
- Startup log: 124 WARN lines on a clean first start (99 x Delta "Cannot find a complete checkpoint", 20 x Hibernate HHH90006001 incubating setting, 1 "sutra directory .../sutras does not exist", JPA open-in-view, springdoc). Noise for a new user (UX/ops low).
- Console: started with DRISHTI_BACKEND_URL=http://127.0.0.1:18985 DRISHTI_CONSOLE_PORT=17985 -> "Uvicorn running on http://127.0.0.1:17985": matches.

## 20:42 QUICKSTART step 7/8 in Chromium (qs.py, output qs.out, errors errs_qs.json)
- 1 TRD MX-20000001: title, notional AUD 242,000,000, MTM ticking, all named panels present (shots/qs1_trade.png). Bottom bar also has F5 P&L explain, F6 Lifecycle, Alt+C, Alt+L: QUICKSTART says it "lists every key the view has: F2,F3,F4,F7,F8,F9" (DOC low).
- 2 F9 raw (murex-rates) ok; F7 -> NS-MERIDIAN-RE-NY ok; Alt+Left back ok.
- 3 F8 impact: NS-MERIDIAN-RE-NY and LIM-MERIDIAN-RE shown. ok.
- 4 `TRD MX-200000` -> "99 of 10000 trades match" (doc: 99 of 99; my lake has 10k trades: expected difference).
- 5 `TRD productType=Revolver` -> 84 match; `trd producttype=revolver` -> **0 of 3675** (later 0 of 10000). Doc and the in-page hint say "case never matters". Isolated: FIELD NAME is case-sensitive (`producttype` -> 0, `productType=revolver` -> 84). Unknown field (`nosuchfield > 1`) silently gives 0 matches, no warning.
- 6 CPTY north -> CP-NORTHBRIDGE, BB+. ok. 7 search -> 2152 of 10000 (10k lake). 8 NSET NS-SUMMIT-NY Trades 108, Utilisation 58%: ok.
- Suggest dropdown for `TRD MX-2000010` lists MX-20000100.. ; Down+Tab -> "TRD MX-20000100 <GO>", Enter opens it: ok.
- Past date: /asof?d=2026-09-29 then /v/trade/MX-20000001 FIRST time -> console 502, page "DRS-1004 · DRS-1004 timed out reading trade/MX-20000001 / Check the identifier, or type part of it..." (shots/qs_past.png). Code duplicated, advice wrong for a timeout. Second load 200 and fast (15 ms). Server fetch-timeout 2s; cold Delta snapshot read on first dated access exceeded it.

## 20:50 search grammar fuzz via /go (cmds.py; shots/cmd_*.png)
- `TRD where mtm >` -> "DRS-4004 cannot read the condition: DRS-2101 unexpected '' at 7" (empty token quoted; not helpful).
- `TRD where mtm > 1m limit 999999999999` -> "For input string: "999999999999" (DRS-5001)": raw Java NumberFormatException leaked as an internal error (SearchQuery.java:160 Integer.parseInt).
- `limit 0` -> silently 1, text "the first 1 are shown" (grammar).
- `ZZZ FOO` -> "say what to look for: a kind such as [deposit accounts, master agreements, bills, bond (security master)s]" (python list brackets, bad plural).
- empty command -> DRS-4001 "...for example TRD IRS-48213" — IRS-48213 only exists in the finance pack, not loaded with the banking packs QUICKSTART recommends.
- `1e309` -> "unknown amount suffix 'e' in 1e" (acceptable, clear).
- Doc examples from GETTING_STARTED step 10 all run (counts differ due to 10k lake).

## 20:45 Rachana grammar fuzz through POST /api/v1/studio/preview (gram.py, output gram.out)
- 190+ cases: every top-level key missing/wrong type, every panel kind's required options missing and invalid values, keys/span/height/area/strip limits, expressions, YAML edge cases (anchors, merge keys, billion laughs, recursive alias, java tags, custom tags, multi-docs, tabs, BOM, CRLF, NUL, duplicate keys, deep nesting, 5 MB file, 2000 panels), request-shape attacks.
- HELD: most invalid input -> 422 DRS-2002 envelope with located DRS-2001/2009/2010/2011/2012/2020/2021/2022/2023/2024/2025/2026/2027/2029/2030/2031/2101 problems; billion laughs and recursive aliases rejected cleanly; !!java tag rejected (DRS-2012); 5 MB file rejected by the 3 MiB code-point limit (DRS-2001); nesting depth >1000 rejected (DRS-2001).
- FAIL: expression with ~800 nested parentheses -> HTTP 500 (StackOverflowError in el.Parser), no DRS code. A 5000-term `$.mtm + ...` chain compiles but overflows at evaluation -> 500 (CompletionException: StackOverflowError). Thresholds: parens 400 ok, 800 fails; chain 3000 ok, 5000 fails.
- FAIL: body `{"yaml": null}` -> 500 NullPointerException; malformed JSON / empty body -> bare Spring 400 without DRS code.
- Silently accepted (no problem reported): duplicate top-level key (`version` twice: last wins), duplicate key inside a panel mapping (`id: a, id: b` -> b), a second YAML document (`---`) ignored, unknown custom tag `!panel` ignored, `match.kind: 42`, `match.priority: high` and `1e99`, title without `id`, `markdown.text: [1,2]` rendered as "[1, 2]", `table.rows: 5` (empty table), `table.limit: many` / `-5`, `table.search: maybe`, `waterfall.sum: maybe`, `status.fields: 5`, `area.series: 5`, `pivot heat: yes` unquoted (documented YAML gotcha), `keys: {F5: nosuchpanel}` (documented as unchecked).
- `version: 99999999999999999999` -> DRS-2001 "YAML syntax: Numeric value ... out of range of long" (misclassified: should be DRS-2020 "version must be a positive integer").
- DRS-2101 unknown function message prints the known list in HashSet order: "[min, startsWith, size, upper, sum, coalesce, first, link, ...]" (not alphabetical; doc shows "[size, upper, sum, …]").

## 20:47 pathological Sutra ON DISK (fresh/sutras/qa/)
- ./sutras did not exist at start, so the new folder was not watched (documented: directories registered at start-up).
- Restarted with qa-deep.v1.sutra.yaml (3000 nested parens) present -> **server fails to start**: "Application run failed ... sutraRegistry ... Caused by: java.lang.StackOverflowError" in el.Parser (server2? see server1.log/server.log at 20:47:41). One bad file takes the whole server down; contradicts "A Sutra file that fails any check is not loaded".
- Restarted with only qa-chain (5000-term chain, matches MX-20000003): server starts; GET /api/v1/views/trade/MX-20000003 -> 500 (no DRS code); console /v/trade/MX-20000003 -> 502 page showing raw Spring JSON "HTTP-500 · {"timestamp":...,"status":500,"error":"Internal Server Error",...}". Other trades fine.
- Hot-dropped qa-deep into the watched folder while running -> log "Exception in thread "drishti-rachana-watch" java.lang.StackOverflowError": the watcher thread DIES. A valid qa-probe Sutra added afterwards is never picked up (MX-20000009 still uses the pack Sutra); /api/v1/sutras/problems stays {}. Hot reload is silently dead until restart, and the restart then fails (above).
- Cold past-date read after this restart: 0.64 s (200). The earlier DRS-1004 timeout (first dated read right after start + fresh 10k-trade lake) did not reproduce: intermittent.
- Removed qa files, restarted clean.

## 20:52 all-kinds Sutra on disk (fresh/sutras/qa/qa-allkinds.v1.sutra.yaml, all 20 kinds on MX-20000001)
- Loaded at restart (problems {}); view "Sutra qa-allkinds v1 + inference" renders all 20 kinds (shots/sw_terminal_2560__v_trade_MX_20000001.png).
- markdown kind shows raw `**Bold**`, html escaped (<script> inert): documented ("not rendered") — XSS held; naming oddity only.
- Charts without fmt round to 6 significant digits (waterfall 1748877 -> "1748880", scatter -1403091.13 -> "-1403090"): documented in PANELS.md, not in RACHANA_REFERENCE's kind tables.
- Function-key bar truncates panel titles ("F3 Table with", "F4 Line from").
- gauge with fmt amount0 shows "1" (fmt applies to ratio: documented gotcha).

## 20:55 page sweep (sweep.py) — 85 URLs x {1600, 390, 2560} px, terminal theme; sweep_terminal_*.json, errs_sweep_*.json, shots/sw_*.png
- All 39 help-centre guides render 200 (/help/<slug>); about, admin (8 pages), studio, reviews, reports, servers, account, m, w, alerts, compare, impact, search pages 200.
- No JS pageerrors, no CSP violations, no broken images on any page at any width.
- 404s: /v/trade/NOPE-404 and /v/nosuchkind/X (styled page "DRS-1001 · DRS-1001 no source holds nosuchkind/X" — code printed twice; unknown KIND reported as missing id); /nonexistent-page -> raw JSON {"detail":"Not Found"} (unstyled).
- /studio: on load the console POSTs /studio/preview for trade/IRS-48213 (finance pack default, studio_routes.py:49) -> 404, red "DRS-1001: DRS-1001 no source holds trade/IRS-48213" is the first thing a QUICKSTART user sees in Studio (shots/sw_terminal_1600__studio.png). The template's match `$.productType == 'IRS'` also matches nothing in banking packs (IRS_FIXFLOAT).
- 390 px: EVERY page with the top bar scrolls horizontally (scrollWidth 419 > 390): .tbar-row1 overflows; "Live" pill and avatar clipped (shots/sw_terminal_390__v_trade_MX_20000002.png). Admin pages 603 px (users table not in a scroll container, shots/sw_terminal_390__admin_users.png). Help guides with screenshots: up to 1460 px (layout-mode, pivot-tab, using-the-terminal, sutra-guide, panel-kinds) because guide <img> has max-width:none (naturalWidth 1265-1440): overflow even at 1600 px (sutra-guide 1922).
- 2560 px: no overflow; layout fine.
- Long tasks: max 339 ms (all-kinds view), 224 ms landing, 206 ms vol surface; acceptable.
- Landmarks: every page has exactly one main; help-centre guides have 2 nav/2 header/2 footer; /help/release-notes has 16 <h1> (each release heading is h1), /help/plugins 2 h1; /studio, /help/search have no h1. Studio editor textarea has no accessible name.

## 21:05 runtime contrast (contrast.py; contrast.json; shots/theme_*.png) — 7 themes x 15 pages
- (ratio-1 hits are gradient text with transparent fill: false positives, ignored; badge "12" false positive verified by crop shots/crop_light_t.png.)
- --d-faint used for real text fails WCAG 4.5:1 in every theme except wallstreet: light 2.83 (#8a93a6 on #f7f5f0), crimson 2.82, terminal 3.32 (2.94 on surfaces: "No data available"), blue 3.94, green 4.36, crimson-dark 4.17. Used by footer copyright, .fk-note, .pnl-empty, .help-foot, separators, input placeholders (2.6 in light/crimson).
- light theme accent #b86e0c used as TEXT (example commands on /t, inline code in help, kickers, CodeMirror keywords): 3.35-3.98:1.
- bootstrap btn-outline-secondary (#6c757d) 3.8-3.9:1 in dark themes.
- The repository test (console/tests/test_contrast.py, passes) only checks ink/muted/link/neg/pos and accent-vs-bg at 3:1 — it never checks --d-faint or accent as body text.

## 21:00 Calc (Alt+C) — calc0.py, calc.py, calc.out, shots/calc_*.png
- Fresh clone without Pyodide: drawer says "Python runtime not installed: run tools/fetch-pyodide.sh", Run disabled (graceful). QUICKSTART/GETTING_STARTED never mention fetch-pyodide.sh, so Alt+C (advertised in the bottom bar) is dead after the walkthrough.
- `tools/fetch-pyodide.sh` (cached tarball): 33 s, 51 MB, as documented. Console restarted (pid file console.pid).
- HELD: print, exceptions (clean traceback), import requests/torch/micropip -> ModuleNotFoundError with list of what Calc has; pyfetch to the internet -> AbortError (CSP); js.document -> AttributeError (worker); drishti.get missing id -> DrishtiError DRS-1001; bad search -> DRS-4004; 300k-line print capped at 200,000 chars; 50 MB string ok; 200k x 30 DataFrame shows 2,000 rows; RecursionError clean; memory bomb -> MemoryError at 4295 MB, tab survives, next run works; infinite loop -> Stop enabled, Stop restarts a fresh worker in ~2.5 s, rerun works. Page never unresponsive.
- Minor: SyntaxError traceback includes Pyodide internals (/lib/python314.zip/_pyodide/_base.py frames). Memory shown stays 4295 MB after MemoryError until Stop.

## 21:08 Layout mode (Alt+L) — layout.py, layout2.py, shots/layout_*.png
- Bar, keys (arrows move, Shift+arrows size, -/+ width, H hide, A natural), live-region announcements work; Save persists; Reset (DELETE) works; Esc cancels; no layout bar on 404 and search pages; works on inferred view (agreement).
- Width can be reduced to 1 of 12 columns: KV squeezed to an unreadable sliver ("en") in the side column (shots/layout_all_hidden.png top right) — no minimum.
- Announcement says "23 rows tall" while the saved height is 24 (off by one, live text vs saved).
- Hiding every panel is allowed in the UI; Save then fails: "DRS-5001: DRS-5001 a layout must leave at least one panel shown" (server 400; code doubled; the UI should prevent it or say it before Save).
- Mouse drag of panel heading: my synthetic drag did not move the panel (selector .pnl-h/header may not be the drag handle) — inconclusive, not reported.

## 21:12 Pivot tab — pivot.py, pivot.out, shots/pivot_*.png
- Panel pivot (35 rows) and search pivot (10,000 trades, server-side) open in <3 s; R/C/V/F keys move fields with announcements; limits enforced: "Not moved: Rows holds at most 4 fields" / Columns 4; CSV export downloads (MX-20000001-t1-pivot.csv, trade-search-pivot.csv). Empty table panel has no Pivot tab.
- Search pivot with Notional as a ROW field shows keys in Java scientific notation "4.240872601E7", "6.058599711E7" (shots/pivot_search_10k_maxed.png); numeric column keys unformatted ("-2838229.57").
- Text fields accepted into Values silently become count.
- Status line jargon "source columns"/"source records".

## 21:15 Alerts / monitors / notes — alerts.py, alerts.out
- GETTING_STARTED step 12 literally: Alert button pre-fills kind/id; rule saved; "NS-SUMMIT-NY: 58% of limit" fires. Monitor from search: 9 rows, add NS-SUMMIT-NY -> 10, bad id -> DRS-5001 message.
- Errors shown as "DRS-2101: DRS-2101 alert expression: DRS-2101 unexpected '' at 15" (code three times) — alerts-page.js:31 prepends code to a detail that already has it. Same doubling in layout, Studio, missing.html ("DRS-1001 · DRS-1001").
- Names: 300 chars / html -> DRS-5001 "names are 1-64 of letters, digits, space . _ -"; "../../etc/passwd" monitor name refused (DRS-5001). Notes: html stored verbatim and rendered via textContent (safe); >2000 chars -> DRS-5001; empty -> DRS-5001.
- Console endpoints crash with plain "Internal Server Error" 500 on a non-JSON body: POST /studio/preview, /alerts/api/x, /m/api/x, /w/api/x, /studio/test, /api/notes/... (json.loads unguarded).

## 21:17 Studio in the browser — studio.py, studio.out, shots/studio_*.png
- Problems by line with DRS codes and hints; multi-problem files list all; 2000 panels preview in 142 ms.
- 1000-deep parentheses -> Studio shows "HTTP-500: {"timestamp":...,"status":500,...}" raw Spring JSON.
- After a failed preview the previous (valid) preview stays on screen, unmarked as stale.
- Tab-indent YAML: hint "check line 5: "title: {...}" needs a colon" — wrong (line has a colon; cause is the TAB); also message mixes "line 4" (ours) with "line 5, column 1" (parser).
- Submit for review -> P-000001; /studio/reviews lists it; review page diff; Approve (security off: four-eyes not applied, documented) -> written to fresh/sutras/studio/qa-studio.v1.sutra.yaml, live on MX-20000005. After approval the review page says "No differences." (diff now against itself) — confusing record.

## 21:20 Many live streams — streams.py, streams2.py
- 4 live tabs + 1: suggestions 14-19 ms, ticking continues: ok.
- 6 VISIBLE live view tabs in one profile (as with several windows on several monitors) -> a 7th tab cannot load ANY page: /t did not load in 60 s; closing one tab -> loads in 0.4 s. Browser 6-connections-per-host over HTTP/1.1, one SSE per tab; only hidden tabs give the connection back (10 s). Uvicorn serves HTTP/1.1 only; the QUICKSTART setup has no HTTP/2 proxy.
- Workspaces with the QUICKSTART packs: /w says "Open a starter below" but the Starters list is EMPTY (no message), there is no "new workspace" control, and /w/QA -> 404: a workspace cannot be created at all without the finance/logistics packs.

## 21:20 keyboard / ARIA — kbd.py, kbd.out
- Tab order: Skip to content first, then logo, command line, date (4 stops, native subfields; last stop has no focus ring), Live, Views/Build/Admin/Help, Alerts, Packs, Theme, user, title links, actions, panel controls. Only 1 of 45 stops without a visible focus ring.
- Command line: role=combobox, aria-autocomplete=list, aria-controls, aria-expanded toggles, aria-activedescendant points at role=option; Esc collapses. "XYZ Q" -> "No matches for “XYZ Q”" (USER_GUIDE correct). "/" focuses; F1 -> /help/using-the-terminal; F9 works from the command line.
- Help centre: /help/connectors has 17 links rendered as href="#" data-unavailable ("Not part of the in-app help: FILE_CONNECTOR.md"); /help/plugins 11, /help/configuration 17, /help/operations 1 (confirms docaudit DOC-11).

## 21:22 as-of handling — asof.py
- /asof?d=garbage ignored; d=9999-99-99, 2030-01-01, 1900-01-01 are STORED in the drishti_asof cookie (12 h) and then every view is a 400 page "DRS-4003 · DRS-4003 business date 2030-01-01 is in the future / Check the identifier..." until the user resets. next= open redirects to other hosts refused (->/t).
- d=2026-09-25/26/27 after `load-delta.sh --trades 10000`: the trade table only has 2026-09-28..30 (the whole table was replaced, not "the trade days it covers"), and the view silently shows LIVE murex-rates data with the notice "murex-rates is not a dated source: this shows its current data, not 2026-09-25" — the source IS dated (trading-store); the date is just missing (v25.json vs v28.json provenance).

## 21:23 admin users fuzz (API through console)
- short password DRS-6003; bad name DRS-6007; unknown role/pack DRS-6007 with lists; duplicate DRS-6002; deleting last admin DRS-6006; display name "<script>" stored and rendered escaped. Held. Test users deleted.

## 21:24 docs spot checks
- USER_GUIDE search/suggest examples (16 commands) all run; counts differ only because of the 10k lake. PACKS.md 20 example commands: 5 sampled open the documented entities. PYTHON_CALC worked example (VaR snippet on VAR-RATES): 9,611,219 / 9,802,031 / 10,860,677 / 57% / 16,740,000 all reproduced exactly (snippet load needs confirming the "Replace the code" dialog).
- WINDOWS.md (read-only): scripts tools/windows/{start-server,start-console,load-delta}.ps1 exist; no Windows way to install the Calc runtime (fetch-pyodide.sh is bash only, WINDOWS.md never mentions Calc/Pyodide).

## 21:25 QUICKSTART step 9 (finance pack) — ws.py, ws2.py
- Restarted with DRISHTI_PACKS=finance: /w lists "Credit desk"; it opens 3 panes; dividers have role=separator with labels/valuenow; drag clamps at ~10%/90%; arrows move; follow works (click IRS-48213 in pane 1 -> pane 2 opens it); Save as… prompt saves "QA desk".
- Alt+1..4: works only while focus is in the parent page; once a pane (iframe) has focus, Alt+2/Alt+3 do nothing (workspace.js:151 listens on the parent document only).

## 21:27 GETTING_STARTED step 13 sign-in — signin.py, signin.out
- Server DRISHTI_SECURITY_ENABLED + token secret, console DRISHTI_AUTH_ENABLED etc. /t -> /login?next=/t; dev admin signs in; Users page warns about the default password; letters-only password -> DRS-6003 (code doubled "DRS-6003: DRS-6003"); good password changes; old password refused; 5 wrong then right -> "This account is locked..." Matches the doc.

## 21:28 cleanup
- Stopped my server (2110916) and console (2110939) by PID. User's 18480/17480 still listening. Nothing written outside qa-ux/ (fresh clone's own data/ and sutras/ only).
