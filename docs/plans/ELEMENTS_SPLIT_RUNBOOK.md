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
# Runbook: Drishti Elements becomes its own project

A plan to be carried out later, step by step. Nothing in it is built. It is written so that a builder follows it without making
choices: every choice is made here, and where the facts on the day differ from what is written, the step says **STOP** and whom to ask.

**This runbook is the separation only.** Drishti Elements moves into its own repository with its history, its own build, tests and
CI, at version 2.4.0, and Drishti takes a pinned, vendored copy. Elements **stays proprietary**: the same header and the same
`LICENSE` as Drishti. The change to Apache-2.0, the `NOTICE` file, contributor sign-off, the npm package and the CDN are a later,
separate phase, described at the end and marked **not now**. The project does get its own identity now: a logo, a README, docs and
the gallery as its docs site (step 6A).

## Contents

- [0. The decisions this runbook rests on](#0-the-decisions-this-runbook-rests-on)
- [Before you start](#before-you-start)
- [Step 1. Inventory](#step-1-inventory)
- [Step 2. Extraction with history](#step-2-extraction-with-history)
- [Step 3. Decoupling](#step-3-decoupling)
- [Step 4. Confidentiality scan](#step-4-confidentiality-scan)
- [Step 5. Header, licence file, notices (proprietary, unchanged)](#step-5-header-licence-file-notices-proprietary-unchanged)
- [Step 6. Standalone build, tests, CI, the release tree](#step-6-standalone-build-tests-ci-the-release-tree)
- [Step 6A. The project's own identity](#step-6a-the-projects-own-identity)
- [Step 7. Local preview for the owner](#step-7-local-preview-for-the-owner)
- [Step 8. Push and tag](#step-8-push-and-tag)
- [Step 9. The Drishti side](#step-9-the-drishti-side)
- [Step 10. Rollback, risks, effort, who does what](#step-10-rollback-risks-effort-who-does-what)
- [Later phase, NOT NOW: open licence, npm, CDN](#later-phase-not-now-open-licence-npm-cdn)
- [Open questions for the owner](#open-questions-for-the-owner)

## 0. The decisions this runbook rests on

**Decided by the owner**

| # | Decision |
|---|---|
| O1 | The split happens right after i6 ships and before i7 starts. |
| O2 | The target is the GitHub repository `ajsinha/drishti-elements` (today: one README, no licence), cloned to `/home/ashutosh/IdeaProjects/drishti-elements`. |
| O3 | **Separation only (2026-11-16).** Elements stays proprietary: the same proprietary header and `LICENSE` as Drishti. No Apache-2.0, no `NOTICE`, no DCO, no npm publish, no CDN in this step. |
| O4 | **Repository visibility: confirmed public by the owner on 2026-11-16**, with proprietary code in it. The confidentiality scan with zero hits before any push stays mandatory. |
| O5 | The owner reviews a local preview before anything is pushed. |
| O6 | The first version of the separate project is 2.4.0, from the one version source. |
| O7 | Afterwards Drishti vendors a pinned copy under `static/vendor/` (a `VENDOR.md` row, the licence file beside it) and no longer holds Elements' sources, docs or gallery. One home per fact: Drishti's docs link to the Elements docs. |
| O9 | **The separate project has its own identity (2026-11-16):** its own logo, its own README, changelog and docs, the gallery as its docs site, its own GitHub description, topics and social preview image; Drishti's README and `VENDOR.md` describe it as an external, vendored component and link there. |
| O10 | **One author and owner, everywhere (2026-11-16, a hard requirement).** The only author and owner anywhere in Drishti Elements is Ashutosh Sinha: git author and committer (`ajsinha` with the e-mail of this file's header), copyright lines, README, `LICENSE`, the repository's GitHub metadata, and later `package.json` and `NOTICE`. No mention of Claude, Anthropic, AI, an assistant, a co-author or "generated with" anywhere: no trailer, no line in a commit message, file header, doc, comment or metadata. Checked over every commit of the extracted history, with zero hits, before any push (step 4, 4e). |
| O8 | Later, as a separate phase: Apache-2.0 with `NOTICE` and the patent grant, "Drishti" and "Drishti Elements" kept as trademarks, DCO for contributors, a lawyer's look before the first open release, npm and then the CDNs. |

**Made in this runbook** (the owner confirms them at the stop of step 1; each is the simplest choice that keeps one home per fact)

| # | Choice | Why |
|---|---|---|
| R1 | **Eight libraries move: core, inputs, grid, chart, data, tree, graph, designer. Three stay in Drishti: drishti-insight, drishti-renderer, drishti-risk.** | The eight work without a Drishti server. Insight (Explain, search, approvals) and the renderer (the ViewModel client: `/api/view`, sign-in, DRS codes; its sheet is generated from about ten console sheets and Jinja macros) are clients of the Drishti server. drishti-risk is the banking module's, licensed with it. This differs from the README's roadmap, which moved the renderer's hosting too. |
| R2 | The Elements repository keeps the static tree's shape at its root: `elements/`, `css/`, `vendor/`, `gallery/`, plus `tools/`, `tests/`, `docs/`, `data/`, `config/`. | The loader, the gallery and the harness pages name each other by relative path (`../css/…`, `../vendor/…`). Keeping the shape means no path is rewritten in any source. |
| R3 | **A table that both projects read has its home in Elements**: `data/world-regions.yaml` and `data/formats.yaml`. Drishti's Java reads them from the vendored copy at build time (a Maven resource folder), with no second copy in git. | Elements must build without Drishti, so the shared table lives upstream and Drishti, which already takes a pinned copy, reads it there. `WorldRegions.java` and `Formats.java` stay in Drishti unchanged: they read the same file name from the classpath. |
| R4 | The engines the eight libraries load (ECharts with its maps, CodeMirror, and any other `vendor/<name>/` the inventory finds) move with them. Drishti then loads them from inside the vendored Elements copy and deletes its own. | One copy of each engine. |
| R5 | One distribution: **the release tree** (`drishti-elements-<version>/`, also as a zip), built by `tools/elements/dist.py` from a tag. The per-library npm tarballs for an internal registry and the Maven WebJar module are deleted. | One way per job. The loader needs the tree's shape anyway; the later npm package is this same tree. |
| R6 | History is kept with **git filter-repo**, run on a fresh scratch clone. | See step 2. |
| R7 | The design doc and the guides are added to Elements as new text, without their history. Code keeps its history. | The design doc's history holds internal decisions, release planning and mock-up notes that do not belong in a public repository. |
| R8 | The gallery ships inside the release tree, so Drishti serves it offline from the vendored copy. | Bank machines are offline; Drishti holds no gallery source. |
| R9 | Python stays the build language of Elements (the build has no npm dependency); tests are Node's own runner and pytest with Playwright, as today. | Nothing is rewritten for the split. |

**What was measured** (branch `wip/i6-elements`, commit `9f4676771`; measure again on the day)

- The candidate move set without the engines and the Python tests: 285 files, about 104,000 lines (of which the generated `ELEMENTS_API.md` is 19,392 and `ELEMENTS.md` 1,916).
- Outside the libraries and the gallery, 101 files name `static/elements/`, 30 name `vendor/echarts/` or `vendor/codemirror/`, 19 name one of core's six sheets, 42 name the Elements tools.
- A first scan of the candidate set: no bank name, no secret, no host, no `/home` path. 32 mentions of "product owner"; 72 Drishti release labels in 12 files; 14 DRS codes in 7 files. The owner's e-mail appears once per file, in the header, and nowhere else.
- Sole author (O10), a first look at Drishti's whole history: 5,266 commits, all by the owner as author; one commit has the committer "GitHub" (made on the website). Over the Elements paths, one commit message matches the strict pattern of step 4e (the gallery commit `a08b6fed4`): expect step 4e to find it and one `--replace-message` pass to clear it in the scratch extraction.
- `git filter-repo` is not installed on the build machine. Node is 22, npm 9.

## Before you start

Every command below is run in a shell with these variables set. Nothing in this runbook runs inside
`/home/ashutosh/IdeaProjects/drishti` except `git fetch`, and nothing ever rewrites that repository's history.

```bash
export DRISHTI=/home/ashutosh/IdeaProjects/drishti
export WORK=/home/ashutosh/drishti-elements-split          # scratch, outside every repository; removed in step 10
export SRC=$WORK/src                                       # a read-only clone of Drishti at the i6 release
export EXTRACT=$WORK/extract                               # the filtered clone that becomes Elements
export TARGET=/home/ashutosh/IdeaProjects/drishti-elements # created in step 8 only
export S=drishti-console/web/static
export V=2.4.0
export LIBS="core inputs grid chart data tree graph designer"
export I6_TAG=<the i6 release tag on main>                 # the owner or the lead names it
```

Preconditions (each must hold, else **STOP and ask the lead**):

```bash
git -C $DRISHTI fetch -q origin --tags
git -C $DRISHTI rev-parse -q --verify "refs/tags/$I6_TAG"                 # prints one commit id
git -C $DRISHTI show "$I6_TAG:$S/elements/drishti_elements.json" | python3 -c "import json,sys; print(json.load(sys.stdin)['elements'])"   # prints 2.4.0
test ! -e $WORK && test ! -e $TARGET && echo clean                        # prints clean
python3 -m pip install --user git-filter-repo && git filter-repo --version   # prints a version
```

If the manifest's version is not 2.4.0, the first version of the separate project is whatever it says: set `V` to it and tell the owner.

Every commit made in this runbook uses the owner's identity, exactly as Drishti's commits do
(`git -c user.name=… -c user.email=… commit`, the same two values as in Drishti's history), with no other author and no
attribution line of any kind.

---

## Step 1. Inventory

**Who:** Sonnet. **Effort:** 0.5 day.

```bash
mkdir -p $WORK/reports $WORK/patterns
git clone -q --no-local --branch "$I6_TAG" $DRISHTI $SRC && cd $SRC && git switch -q -c split-source

# 1a. The list that moves: one path per line, files only, as they are at the i6 release.
{
  for l in $LIBS; do git ls-files "$S/elements/drishti-$l/"; done
  git ls-files "$S/elements/README.md" "$S/elements/drishti_elements.json" "$S/gallery/"
  # the shared sheets and the engines the eight libraries name
  python3 - <<'PY'
import json, os, re, subprocess
S = os.environ["S"]; libs = ["drishti-" + l for l in os.environ["LIBS"].split()]
m = json.load(open(f"{S}/elements/drishti_elements.json"))["libraries"]
named = set()
for l in libs:
    for key in ("css", "scripts", "assets"):
        named.update(m[l].get(key, []))
text = subprocess.run(["git", "grep", "-hoE", r"vendor/[a-z0-9-]+/", "--", *[f"{S}/elements/{l}" for l in libs], f"{S}/gallery"],
                      capture_output=True, text=True).stdout
folders = set(text.split()) | {re.match(r"vendor/[^/]+/", n).group(0) for n in named if n.startswith("vendor/")}
for n in sorted(n for n in named if n.startswith("css/")):
    print(f"{S}/{n}")
for f in sorted(folders):
    print(subprocess.run(["git", "ls-files", f"{S}/{f}"], capture_output=True, text=True).stdout, end="")
PY
  git ls-files tools/elements/ tools/maps/ tools/docs/elements_api.py tools/docs/elements_catalogue.py
  git ls-files "drishti-console/tests/elements/" | while read -r f; do grep -qE "drishti-(insight|renderer|risk)/" "$f" || echo "$f"; done
  git ls-files "drishti-console/tests/test_elements*.py" drishti-console/tests/test_vendor_licences.py | grep -v test_elements_renderer_browser.py
  echo $S/vendor/VENDOR.md
  echo compute/drishti-rachana/src/main/resources/world-regions.yaml
  echo compute/drishti-rachana/src/main/resources/formats.yaml
  echo config/license-header.txt; echo LICENSE; echo tools/license_headers.py
} | sort -u > $WORK/move-paths.txt

# 1b. What stays although it sits beside what moves.
{ for l in insight renderer risk; do git ls-files "$S/elements/drishti-$l/"; done
  git ls-files "$S/render/" "$S/css/drishti_explain.css" "$S/css/drishti_approvals.css"
  git ls-files "drishti-console/tests/elements/" | while read -r f; do grep -qE "drishti-(insight|renderer|risk)/" "$f" && echo "$f"; done
  echo drishti-console/tests/test_elements_renderer_browser.py; } | sort -u > $WORK/stay-paths.txt

# 1c. The report.
wc -l < $WORK/move-paths.txt; xargs -a $WORK/move-paths.txt cat | wc -l
grep "/vendor/" $WORK/move-paths.txt | cut -d/ -f5 | sort | uniq -c
comm -12 $WORK/move-paths.txt $WORK/stay-paths.txt | wc -l
```

**Expected.** `move-paths.txt` holds roughly 600 to 700 paths (about 285 sources, docs and tools, about 280 vendor files, about 25
Node tests, about 20 Python tests); the vendor folders listed are `echarts`, `codemirror` and `apache-arrow` (drishti-data's Arrow client; owner 2026-11-16) and `echarts-gl` (once the 3D trace is in the chart: it is, after the showcase merge) (and `echarts-gl` if a chart kind now
names it), plus the one file `VENDOR.md`; the last command prints `0`.

**What moves, what stays, what is in both for a while**

| | Where it is in Drishti | After the split |
|---|---|---|
| Moves, with history | the eight libraries (`src/`, bundles, sheets, harness pages), the manifest, the Elements README, the gallery, core's six sheets in `static/css/` (tokens, resize, chips, states, command-palette, dock), the engines, `tools/elements/`, `tools/maps/`, the two docs generators, the Node tests of the eight, the Python Elements tests, the two shared tables | the Elements repository; Drishti reads all of it from the vendored copy |
| Copied, with history, and kept in Drishti too | `LICENSE`, `config/license-header.txt`, `tools/license_headers.py`, `vendor/VENDOR.md` (cut down to the engines' rows in Elements) | each project has its own; they are the same text because the licence is the same |
| Moves as new text, without history | `docs/architecture/ELEMENTS.md` sections 1 to 11, `docs/guides/EMBED_GUIDE.md` sections 13 and 14, `ELEMENTS_API.md` (generated again) | `docs/` in Elements (step 6) |
| Stays | drishti-insight, drishti-renderer, drishti-risk and their tests; `static/render/<version>/` and its redirect policy; the embed and render routes; every template and page script; `modules/*/elements/` (drishti-genomics); `WorldRegions.java`, `Formats.java` and their tests; `ELEMENTS.md` section 12 (moving Drishti's screens) as a short Drishti doc; `EMBED_GUIDE.md` sections 1 to 12; the mock-ups under `docs/architecture/mockups/elements/` | Drishti |
| In both for a while | everything in `move-paths.txt` stays in Drishti, untouched, from the extraction until step 9 lands. **During that window nobody changes those files in Drishti** (a freeze the lead announces). A fix that cannot wait is made in the Elements scratch clone and copied back by hand, and the lead is told. | one home again after step 9 |

**STOP and ask the owner** (Stop A): send the numbers of 1c, the vendor folder list and choices R1 to R9. Go on only when the
owner confirms R1 (which libraries move) and R3 (the home of the two tables). **STOP and ask the lead** if a vendor folder other than
`echarts`, `echarts-gl`, `codemirror` or `apache-arrow` is listed, or if the last command does not print `0`.

---

## Step 2. Extraction with history

**Who:** Sonnet. **Effort:** 0.5 day.

**The tool: git filter-repo, not `git subtree split`.** A subtree split takes one folder; the move set is about twenty paths in six
places, each of which also needs a new place in the tree. filter-repo takes a list of paths, renames them, can replace text in old
revisions and in commit messages when the scan of step 4 finds something, and refuses to run on anything but a fresh clone, so the
Drishti repository cannot be rewritten by mistake. The history kept is the history of the listed files at their listed paths; a
file's life before it was moved to its present path is not followed.

**Never into the target repository.** The filter runs in `$EXTRACT`, a scratch clone with no remote. The target is first cloned in
step 8, after the scan, the preview and the owner's approval.

```bash
git clone -q --no-local $SRC $EXTRACT && cd $EXTRACT
git remote remove origin
git tag -l | xargs -r git tag -d > /dev/null            # no Drishti tag is carried over
git branch -m main 2>/dev/null || git switch -q -c main

# 2a. Who ever touched what moves, and no attribution line of a tool.
git log --format='%an' -- $(cat $WORK/move-paths.txt) | sort | uniq -c
git log --format='%B' -- $(cat $WORK/move-paths.txt) | grep -ciE "co-authored-by|generated with|signed-off-by" 
```

**Expected:** one author, the owner; the second command prints `0`. This is a first look only; the binding check is step 4, 4e.
If another author appears, or the count is not `0`, go on: 4e says how it is cleared in the scratch extraction.

```bash
# 2b. The filter: the listed paths only, at their new places. $WORK/replace-text.txt and $WORK/replace-message.txt do not
#     exist on the first pass; step 4 creates them only if it finds something, and then this command gains
#     --replace-text $WORK/replace-text.txt --replace-message $WORK/replace-message.txt.
git filter-repo --force \
  --paths-from-file $WORK/move-paths.txt \
  --path-rename "$S/elements/:elements/" \
  --path-rename "$S/gallery/:gallery/" \
  --path-rename "$S/css/:css/" \
  --path-rename "$S/vendor/:vendor/" \
  --path-rename "drishti-console/tests/elements/:tests/elements/" \
  --path-rename "drishti-console/tests/:tests/" \
  --path-rename "compute/drishti-rachana/src/main/resources/:data/"

# 2c. Checks.
git ls-files | cut -d/ -f1 | sort | uniq -c
git ls-files | wc -l; wc -l < $WORK/move-paths.txt
git log --oneline | wc -l
git count-objects -vH | grep size-pack
```

**Expected.** Top-level names are exactly `LICENSE config css data elements gallery tests tools vendor`. The two file counts are
equal. A few hundred commits. A pack of some tens of megabytes (the maps are about 8 MB).

**STOP and ask the lead** if any other top-level name appears, if the counts differ, or if the pack is over 150 MB (a large file was
once committed under a listed path: find it with `git filter-repo --analyze` and ask before removing it).

The Node tests import the libraries by a relative path that is now one level too deep. One mechanical change, one commit:

```bash
grep -rl "web/static/" tests/elements | xargs sed -i 's#\.\./\.\./web/static/#../../#g'
grep -rc "web/static" tests/elements | grep -v ":0$" | wc -l          # prints 0
git commit -qam "Tests: the libraries are at the repository's root"   # with the owner's identity, as said above
```

---

## Step 3. Decoupling

**Who:** Opus (design of the host adapter and the loader's registration), Sonnet for the mechanical items. **Effort:** 4 days.

All work is in `$EXTRACT`. The rule: **a library in Elements names no Drishti route and needs no Drishti page.** Whatever was a
built-in default becomes an option, and Drishti supplies the option from one new page script on its side
(`static/js/drishti_elements-host.js`, written in step 9 from the list this step produces).

Find every coupling with these commands; each must print `0` when the step is done, apart from the allowances named in the table.

```bash
cd $EXTRACT
P1="['\"\`]/(api|embed|bi|pivot|explain|admin|terminal|static|m)/"     # a root-absolute Drishti route in code
P2="drishti_url|drishti_route|meta\[name=\"drishti-"                    # the console's base-path helpers and metas
P3="DRS-[0-9]{4}"                                                       # Drishti error codes
P4="X-Drishti-[A-Za-z-]+"                                               # Drishti request headers
P5="drishti-(insight|renderer|risk|genomics)"                           # the libraries that stay
P6="window\.Drishti\.(yaml|GridKeys|snapshots|ShapeTree|Explain|keys)|drishtiGeo|drishtiModules"   # globals a console script sets
P7="bi bi-[a-z0-9-]+"                                                   # Bootstrap Icons classes (the font is not in Elements)
P8="compute/drishti-rachana|drishti-console|packaging/"                 # Drishti paths in the tools
for p in "$P1" "$P2" "$P3" "$P4" "$P5" "$P6" "$P7"; do echo "== $p"; grep -rnE "$p" elements/*/src gallery tests/elements | grep -vE "^\S+:[0-9]+:\s*(//|\*|/\*)" | wc -l; done
echo "== $P8"; grep -rnE "$P8" tools tests/*.py | wc -l
```

Write the counts before and after into `$WORK/reports/decoupling.md`, with one row per file changed.

| # | Coupling (where it was found) | How it is cut in Elements | What Drishti's host script then does |
|---|---|---|---|
| C1 | The loader's default folder `/static/elements/` and the maps' default `/static/vendor/echarts/maps/` (`drishti_loader.js`, `drishti_map-view.js`, `drishti_chart-geo.js`) | The default is the folder the core bundle was loaded from (the bundle already reads `document.currentScript`); with no script URL (an ES module import) `configure({ base, staticBase })` is required and the error says so. No literal `/static/`. | Nothing: the bundle is loaded from the vendored folder, so the default is right. |
| C2 | Libraries outside the manifest: the loader only knows the manifest's names (`requirements`, `refused`) | New function `Drishti.elements.register(name, { base, entry, bundle, requires, css, module })`: a library that lives elsewhere, loaded and refused by the same rules. The manifest's `module` field stays, documented as "the host's own grouping". | Registers drishti-insight, drishti-renderer, drishti-risk (base `/static/elements/`) and each hosted module's library. |
| C3 | The base-path helpers: `window.drishti_url`, `window.drishti_route`, `meta[name="drishti-base"]`, `meta[name="drishti-modules"]` (`drishti_base.js`, `drishti_loader.js`) | Kept: they are named after the library and work for any host under a path prefix. Documented as API. **Allowance for P2.** | Nothing. |
| C4 | The grid's live and pivot defaults: `/pivot?source=`, `/api/live`, `/api/changes` (`drishti_live-model.js`) | Required options (`pivotUrl`, `base`, `changesUrl`); with none given the feature is off and says so in words. The shapes of the answers are documented as "the live-set contract" in `docs/CONTRACTS.md`. | `configure({ endpoints: { live: '/api/live', changes: '/api/changes', pivotPage: '/pivot' } })`. |
| C5 | The Arrow client's `capabilitiesUrl` default `/api/v1/data/capabilities` (`drishti_arrow.js`) | An option with no default; without it the client asks nothing and uses JSON pages. Contract documented. | Sets it in `endpoints`. |
| C6 | The API client: the as-of header `X-Drishti-As-Of`, the device header, the error shape `{code, detail}` with DRS codes (`drishti_api.js`) | The header names stay as defaults (they carry the library's own name) and become options (`asOfHeader`). **Allowance for P4.** The error shape is documented as "the error contract: any `code` string and a `detail`". Comments and tests use neutral example codes (`E-1001`), not DRS numbers. | Nothing. |
| C7 | Explain's drawing in core and any call to `/api/explain` or `/explain` from the eight | The drawing stays (it draws a tree it is given). Any fetch moves behind an option `explain: (ref) => Promise<tree>`; a component with no `explain` shows no Explain action. | drishti-insight supplies `explain`. |
| C8 | Globals a console page script sets (P6) | Each use is replaced by an import from the owning library, or by an option; a use that cannot be is listed for the lead. | None. |
| C9 | Bootstrap Icons classes (P7; three uses) | Inline SVG from the library, or a text label. No icon font is needed by the eight. | None. |
| C10 | The gallery: `/api/elements/visuals` (the hosted modules' visuals), demos of the three libraries that stay (`drishti_gallery-demos-renderer.js` and any demo importing them), the catalogue's entries for them | The address of extra visuals is a page option (`?visuals=<same-origin URL>`; absent: none asked). Demos and catalogue entries of the three are removed here and not replaced: their harness pages in Drishti remain their test surface. | Opens the vendored gallery with `?visuals=/api/elements/visuals`. |
| C11 | The build's inputs outside the tree (`tools/elements/build.py`: `compute/drishti-rachana/.../world-regions.yaml` and `formats.yaml`, `drishti-console/web/static/...`) | Paths relative to the repository root: `data/world-regions.yaml`, `data/formats.yaml`, `elements/`, `css/`, `config/license-header.txt`. New arguments `--root DIR --manifest FILE --header FILE` so the same tool bundles libraries that live elsewhere (Drishti's three). `tools/maps/build_world_regions.py` writes `data/world-regions.yaml`. | Runs the vendored `tools/elements/build.py --root … --manifest …` for its three libraries. |
| C12 | The distribution (`tools/elements/dist.py`, `cli.py`, `pack.py`): per-library npm tarballs, the WebJar tree, `product_version()`, domain libraries (`--include-domain banking`), the `drishti.py elements` command group | One release tree and its zip (step 6). The WebJar tree, the per-library tarballs and the domain switch are deleted. `cli.py` becomes `python3 tools/elements/cli.py pack|dist`. | The `elements` command group is removed from `tools/drishti.py`. |
| C13 | Product and domain words in the eight: banking vocabulary in comments, demo data and tests (VaR, desk, trade ids), "the console", release labels | Not a blocker (the existing guard `test_the_generic_libraries_hold_no_domain_vocabulary` already bounds it). Release labels and "product owner" notes are removed in step 4. Demo rows stay if they are plainly invented. | None. |
| C14 | The manifest: the entries and `requires` of the three libraries that stay | The three entries are removed. No remaining library may require them (true today). | Drishti keeps a manifest of its three (step 9). |

After the table is done, regenerate and test:

```bash
python3 tools/elements/build.py && python3 tools/elements/build.py --check && echo fresh
node --test tests/elements/*.test.mjs 2>&1 | grep -E "^# (pass|fail)"
```

**Expected:** `fresh`; `# fail 0`.

**STOP and ask the lead** when: a P1 hit cannot become an option without changing a name that Drishti's pages use (list the
name and its callers in `$SRC`); one of the eight turns out to import one of the three; or cutting a coupling would change what a
screen in Drishti shows. Do not improvise an API: C2, C4, C5 and C7 are the only new names this step may add.

---

## Step 4. Confidentiality scan

**Who:** Sonnet runs it; Opus decides any hit in old revisions. **Effort:** 1 day.

**This step is mandatory and comes before any push: the repository is public.** Two classes. **Blockers** must have zero hits in
every revision and every commit message. **Wording** must have zero hits in the tree at HEAD.

The proprietary header stays (O3), so the header's own lines are expected in every file and are not a hit: the commands below
take the header's copyright line out before looking for an e-mail address, and nothing else is excused.

```bash
cd $EXTRACT
# The bank and client list is NEVER committed anywhere, in either repository. Build it in the scratch folder:
#   one name or abbreviation per line: every bank on the FSB's list of global systemically important banks and on OSFI's list of
#   domestic systemically important banks (full name, short name, ticker), then every name the owner adds (employers, clients,
#   prospects). The owner reviews this file at Stop B.
$EDITOR $WORK/patterns/banks.txt

cat > $WORK/patterns/blockers.txt <<'EOF'
/home/|/Users/|[A-Z]:\\Users|IdeaProjects|PycharmProjects|drishti-stack|drishti-bench
localhost|127\.0\.0\.1|0\.0\.0\.0|192\.168\.|\b10\.[0-9]+\.[0-9]+\.[0-9]+|\b172\.(1[6-9]|2[0-9]|3[01])\.|:1[78]480\b|\.internal\b|\.corp\b|\.lan\b
(api[_-]?key|secret|passw(or)?d|token|bearer)["' ]*[:=]["' ]*[A-Za-z0-9+/_=-]{12,}
AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|npm_[A-Za-z0-9]{30,}|xox[abp]-|-----BEGIN [A-Z ]*PRIVATE KEY
[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[a-z.]{2,}
\b(Endur|Calypso|Murex|SwapOne|Openlink|ComSwap|qlrisk|sajha|sajhamcpserver|pravaha)\b
Co-Authored-By|Generated with|Signed-off-by
EOF
cat > $WORK/patterns/wording.txt <<'EOF'
product owner|\bthe owner\b|owner 20[0-9]{2}|decided 20[0-9]{2}
\b2\.9\.[0-9]+(\.[a-z][0-9a-z]*)?\b|\bi[1-7]b?\b release|\bwave [A-E]\b|mock-up g[0-9]|Drill [AB]\b
DRS-[0-9]{4}
drishti-console|compute/drishti-|docs/architecture/|docs/guides/|IMPLEMENTATION_PLAN|SHARED_SCREENS
EOF
HDR='Copyright \(c\) 2026 Ashutosh Sinha <'              # the header's copyright line: the one place an address is expected

# 4a. HEAD: banks, blockers, wording.
for f in banks blockers wording; do echo "== $f"; git grep -nIEf $WORK/patterns/$f.txt -- . ':!vendor' | grep -vE "$HDR" | tee $WORK/reports/scan-head-$f.txt | wc -l; done
# 4b. Every revision of every file (the diff text of all history), and every commit message.
git log -p --all --format='commit %H%n%B' -- . ':!vendor' > $WORK/reports/history.patch
for f in banks blockers; do echo "== history $f"; grep -nEf $WORK/patterns/$f.txt $WORK/reports/history.patch | grep -vE "$HDR" | tee $WORK/reports/scan-history-$f.txt | wc -l; done
# 4c. The vendored folders hold only what upstream shipped.
git grep -nIEf $WORK/patterns/banks.txt -- vendor | wc -l
# 4d. The file names and commit subjects that become public.
git log --all --name-only --format='%s' | sort -u | grep -nEf $WORK/patterns/banks.txt -f $WORK/patterns/blockers.txt | wc -l
```

**4e. One author and owner, and no mention of a tool or assistant (O10). Hard requirement: zero hits before any push.**

```bash
cd $EXTRACT
OWNER_ID="$(git -C $SRC log -1 --format='%an <%ae>' "$I6_TAG")"            # ajsinha <the e-mail of Drishti's header>; read it, confirm it
# every author and committer of every commit: exactly one identity
git log --all --format='%an <%ae>%n%cn <%ce>' | sort -u | tee $WORK/reports/identities.txt
test "$(cat $WORK/reports/identities.txt)" = "$OWNER_ID" && echo one-identity
# every commit: identity, message and trailers
STRICT='claude|anthropic|co-authored|co-author|coauthor|generated with|generated by (an? )?(ai|assistant|llm|model)|ai[- ]generated|\bassistant\b|copilot|chatgpt|openai|\bgpt\b|\bllm\b|noreply@|signed-off-by|🤖'
git log --all --format='%H %an %ae %cn %ce%n%B' | grep -niE "$STRICT" | tee $WORK/reports/scan-author-messages.txt | wc -l
git log --all --format='%(trailers)' | grep -v '^$' | sort | uniq -c | tee $WORK/reports/trailers.txt | wc -l
# the owner's broader word: every "generated" in a commit message is read; each must plainly name a build tool's output
git log --all --format='%h %s%n%b' | grep -niE "\bgenerated\b" > $WORK/reports/generated-in-messages.txt; wc -l < $WORK/reports/generated-in-messages.txt
# the tree at HEAD and every old revision: headers, comments, docs, metadata (\bAI\b is case-sensitive, so it is a second pass)
git grep -nIiE "$STRICT" -- . ':!vendor' | wc -l;  git grep -nIE "\bAI\b" -- . ':!vendor' | wc -l
grep -ciE "$STRICT" $WORK/reports/history.patch;   grep -cE "\bAI\b" $WORK/reports/history.patch
# every copyright line names the owner and nobody else
git grep -nIiE "copyright|\(c\)|©|@author|\bauthors?:" -- . ':!vendor' ':!LICENSE' | grep -viE "Ashutosh Sinha|copyright holder" | wc -l
```

**Expected:** `one-identity`; then `0` for the strict scan of messages and `0` trailers of any kind; the `generated` list is read
line by line (a line such as "catalogue generated from the JSDoc" names a build tool; anything else is a hit); `0` for all four
tree and history counts; `0` copyright or author lines naming anyone else.

**How a hit is cleared: only ever in the scratch extraction, never in Drishti's history.**

- A trailer or line in a commit message: add `regex:(?mi)^(co-authored-by|signed-off-by|generated with).*$==>` (or the literal
  line found) to `$WORK/replace-message.txt`, and repeat step 2 from 2b on a fresh clone with `--replace-message`.
- Another author or committer identity: write `$WORK/mailmap` with one line mapping that identity to the owner's (the standard
  `Proper Name <proper e-mail> <found e-mail>` form, the owner's values read from `$OWNER_ID`), and repeat 2b with
  `--mailmap $WORK/mailmap`. **STOP and ask the owner first**: a different identity may be a real person, and only the owner can say
  it is not.
- A mention inside a file at HEAD: edit the file. Inside an old revision: a line in `$WORK/replace-text.txt`, repeat 2b.
- A word that only looks like a hit (a variable named `assistant`, "AI" in a product sentence of a doc): reword it so the count is
  truly `0`; nothing is excused in an allow-list for this check.
- After any repeat of 2b, run all of step 4 again from the start.

The same holds for everything written later in this runbook: the README, the changelog, the docs, `brand/`, the CI workflows, the
repository's description and topics, and every commit and tag message name no author but the owner and no tool or assistant.
Step 8 runs 4e once more on the exact commit to be pushed.

**Expected at the end of the step:** every count of 4a to 4d is `0`, and 4e is as said above.

**How a hit is cleared (4a to 4d)**

- At HEAD: edit the file (a release label or an owner note is deleted or reworded; a DRS code in a comment becomes a neutral
  example; a Drishti path becomes the Elements path). One commit: "Wording for a separate project".
- In history (a blocker in an old revision or a commit message): add a line `literal:<found text>==><neutral text>` to
  `$WORK/replace-text.txt` (file content) or `$WORK/replace-message.txt` (commit messages), then **repeat step 2 from 2b on a
  fresh clone and replay the later commits with `git cherry-pick`**. Never edit history by hand.
- A short abbreviation that matches ordinary code (two or three letters) is read in context; a false match is recorded in
  `$WORK/reports/scan-allowed.md` with the line and the reason, and the pattern is tightened with `\b` and case so that the count
  is truly `0`.

**STOP and ask the owner** (Stop B) when: a real bank, client or person's name is found anywhere, at HEAD or in history (send the
file, line and revision; do not fix it silently); the history scan still shows blockers after two passes of 2b. The fallback the
owner may then choose is **one initial commit with no history** (`git checkout --orphan`, one commit of the tree); the filtered
history then stays in `$WORK` and is never pushed.

---

## Step 5. Header, licence file, notices (proprietary, unchanged)

**Who:** Sonnet. **Effort:** 0.25 day.

Nothing about the licence changes in this step. Elements carries the same proprietary header and the same `LICENSE` as Drishti;
both came across in step 2 with their history.

```bash
cd $EXTRACT
diff LICENSE $SRC/LICENSE && diff config/license-header.txt $SRC/config/license-header.txt && echo same-licence
# tools/license_headers.py: SKIP_DIRS reduced to {".git", "vendor", "node_modules", "__pycache__", "dist"}; nothing else changes
python3 tools/license_headers.py && echo headers-ok
python3 -m pytest -q tests/test_vendor_licences.py
```

**Expected:** `same-licence`, `headers-ok`, the licence guard passes.

- **The header's first line** stays "Project Drishti · …" as in `config/license-header.txt`: the file is byte-identical to
  Drishti's, so one text governs both. (Its last line, "See the LICENSE file in the root of this repository", is true here too.)
- **`vendor/VENDOR.md`** is cut down to the rows of the folders present in Elements (ECharts and ECharts GL if present, CodeMirror,
  the Natural Earth shapes, the world-regions table with its new home `data/world-regions.yaml`), the section "Algorithms and
  published constants used" and the licence policy. Each engine keeps its licence file, and its NOTICE where upstream ships one,
  beside it, as today.
- **`README.md`** (replaces the target repository's present one in step 8): what Drishti Elements is; the eight libraries; how to
  build and test; the version; and, near the top, in these words: "Proprietary. Copyright (c) 2026 Ashutosh Sinha. All rights
  reserved. This repository is visible to the public, but no licence is granted: you may not copy, use, modify or distribute this
  code without the copyright holder's written permission. See LICENSE. Third-party libraries under `vendor/` keep their own
  licences, listed in `vendor/VENDOR.md`. Contributions are not accepted at this time." No roadmap of Drishti, no Drishti release
  label, no promise of a future open licence. Its full outline is step 6A, I3.
- **Not in this step:** no Apache header, no `NOTICE`, no `THIRD-PARTY-NOTICES`, no `CONTRIBUTING.md`, no DCO, no
  `TRADEMARKS.md`. They are the later phase.

**STOP and ask the lead** if the licence guard refuses a vendored folder, or if a file under `elements/`, `gallery/` or `tools/`
carries a copyright line of anyone but the owner.

---

## Step 6. Standalone build, tests, CI, the release tree

**Who:** Sonnet for the build, the tree and CI; Opus for sorting the Python tests and for the design doc.
**Effort:** 3.5 days.

**6a. One version source.** `elements/drishti_elements.json`, key `elements` (2.4.0). Everything else is generated from it:
bundle headers, the loader's `ELEMENTS_VERSION`, the release tree's name and its `drishti-elements.json`, the catalogue, the
reference. `tests/test_elements_version.py` keeps failing on any other Elements version string; its checks of the renderer folder,
the WebJar and the module ranges are deleted here (they are Drishti's, step 9).

**6b. Tests.** Sort every moved Python test once, by this rule, and record the result in `$WORK/reports/tests.md`:

| The test… | Goes |
|---|---|
| reads only files under `elements/`, `css/`, `gallery/`, `vendor/`, `tools/` (the Node runner, budgets, guards on colours, prefixes and CDN addresses, the gallery and harness browser tests, the licence guard, dist and pack) | stays in Elements; `STATIC` becomes the repository root |
| starts the console, calls a route, reads a template, or names one of the three libraries | deleted here (it is still in Drishti, untouched, and step 9 keeps it there) |
| does both | split by test function; the Drishti half is kept in Drishti in step 9 |

A new `tests/conftest.py` holds only the browser fixture the moved tests use (copied from Drishti's `conftest.py`: the `browsers`
fixture and the static file routing; nothing else). `tests/requirements.txt`: `pytest`, `pytest-xdist`, `playwright`, `pyyaml`,
at the versions Drishti pins.

```bash
python3 tools/elements/build.py --check && python3 tools/docs/elements_api.py --check && python3 tools/license_headers.py
node --test tests/elements/*.test.mjs
python3 -m pytest -q tests -n 8
```

**Expected:** all three lines succeed; no test is skipped except the ones marked for a missing browser. These three lines are "the
gate" of the Elements project.

**6c. The release tree.** `tools/elements/dist.py` writes `dist/drishti-elements-<version>/` (git-ignored) and the same as
`dist/drishti-elements-<version>.zip`: `elements/<library>/` (`src/`, the bundle, the `.d.ts`, the sheet), `css/`, `themes/`,
`vendor/`, `gallery/`, `data/`, `tools/elements/build.py` and what it imports, `LICENSE`, `README.md`, `CHANGELOG.md`, and
`drishti-elements.json` (the version, the libraries, and a SHA-384 hash of every file). Harness pages and tests are not in it. The
build is reproducible: two runs give the same bytes. There is no `package.json` and nothing is uploaded anywhere.

```bash
python3 tools/elements/cli.py dist --out dist && sha256sum dist/drishti-elements-$V.zip > $WORK/reports/zip-1.txt
python3 tools/elements/cli.py dist --out dist && sha256sum dist/drishti-elements-$V.zip | diff - $WORK/reports/zip-1.txt && echo reproducible
python3 -c "import json; m=json.load(open('dist/drishti-elements-$V/drishti-elements.json')); print(m['elements'], len(m['libraries']))"   # 2.4.0 8
du -sh dist/drishti-elements-$V
(cd dist/drishti-elements-$V && python3 -m http.server 8765 --bind 127.0.0.1 & sleep 1; curl -sI http://127.0.0.1:8765/gallery/drishti_gallery.html | head -1; kill %1)   # 200
```

**STOP and ask the lead** if the two zips differ, or if the tree is over 40 MB.

**6d. CI.** `.github/workflows/ci.yml`: on push to `main` and on pull requests; one job on `ubuntu-latest` with
`actions/setup-python` 3.13 and `actions/setup-node` 22: `pip install -r tests/requirements.txt`, `playwright install --with-deps
chromium`, then the three gate lines of 6b, then `python3 tools/elements/cli.py dist --out dist`. The workflow uploads nothing,
publishes nothing and uses no secret. (On a public repository the workflow's logs are public too: the tests print no data beyond
the invented demo rows.)

**6e. The docs** (new text, no history; Opus). In `docs/`: `DESIGN.md` from `ELEMENTS.md` sections 1 to 11 (as the design is, with
no decision history, no release label, no mock-up id, no migration checklist); `API.md` generated by `tools/docs/elements_api.py`;
`USING.md` and `MOVING_FROM_AG_GRID.md` from `EMBED_GUIDE.md` sections 13 and 14 (served by a host, or from the release tree; no
npm, no CDN); `CONTRACTS.md` (the error, live-set, data capabilities and Explain contracts of step 3); `THEMING.md` (the `--d-*`
tokens); `CHANGELOG.md` at the root with one entry, 2.4.0. Every doc carries the proprietary header. Then run step 4's scan again,
HEAD and history: every count `0`.

**STOP and ask the lead** if a moved browser test cannot run without the console (it then belongs to the "deleted here" row), or if
a bundle is over its byte budget.

---

## Step 6A. The project's own identity

**Who:** Sonnet draws and writes; **the owner chooses the logo** and does the two GitHub settings that have no command.
**Effort:** 1.5 days. All work is in `$EXTRACT` unless a sub-step says otherwise.

**I1. The logo: three options, the owner picks one.** (Sonnet draws; the owner chooses.)

Rules, none of them open to judgement:

- Drawn in-house, by hand, as plain SVG (`<path>`, `<rect>`, `<circle>`; no `<image>`, no embedded raster, no `<text>`, no script,
  no external reference). No third-party artwork, icon set, clip art or generated image.
- No font file is needed: any lettering is converted to outlines. The only typefaces allowed as a source of outlines are the OFL
  fonts Drishti already vendors (Cinzel, Playfair Display; `drishti-console/web/static/vendor/fonts/`, licence `OFL-*.txt`). If
  lettering from them is used, the font's OFL text is copied to `brand/OFL-<font>.txt` and a row is added to `vendor/VENDOR.md`.
- Related to Drishti's mark but distinct from it: the same family (the colours come from the tokens in `css/drishti_tokens.css`),
  a different shape, so the two are never mistaken for each other at 16 pixels. Read Drishti's mark first:
  `ls $SRC/drishti-console/web/static/img/` (look, do not copy a path from it).
- Each option works on a dark and on a light ground, in one colour, and at 16 pixels.

```bash
mkdir -p brand/options
# write brand/options/option-a.svg, option-b.svg, option-c.svg (each: the mark, and the mark with the word "Drishti Elements"),
# and brand/options/index.html: the three side by side on dark and on light, at 16, 32, 64 and 256 pixels.
for f in brand/options/option-*.svg; do
  grep -cE "<image|<text|<script|href=|url\(|font-family" "$f"          # 0 for every file
  python3 -c "import sys, xml.dom.minidom as m; m.parse(sys.argv[1]); print('well-formed', sys.argv[1])" "$f"
done
cp -r brand/options $WORK/preview-logo && (cd $WORK/preview-logo && python3 -m http.server 8766 --bind <the machine's LAN address>)
```

The three directions to draw (so that the options really differ): **A** a building-block mark (a small grid of squares, one of
them lit, for "elements" and for the grid); **B** an eye or lens drawn from a few chart shapes ("drishti" means sight); **C** a
monogram "DE" built from the same square module.

**STOP for the owner's choice** (Stop L): send `http://<LAN address>:8766/index.html`. The owner names one option, or asks for a
change to one; nothing below I1 is made before that. `brand/options/` is not committed: only the chosen mark is.

**I2. The chosen logo as files.** (Sonnet.)

```bash
# the chosen option becomes brand/drishti_elements-logo.svg (the mark with the name) and brand/drishti_elements-mark.svg (the mark alone)
python3 tools/brand/render.py            # a new small tool: Playwright (already a test dependency) opens each SVG and writes the PNGs; no new library
ls brand
```

**Expected files:** `drishti_elements-logo.svg`, `drishti_elements-mark.svg`, `drishti_elements-mark-16.png`, `-32.png`, `-48.png`,
`-180.png` (Apple touch), `-192.png`, `-512.png`, `drishti_elements-favicon.svg`, `drishti_elements-favicon.ico` (16, 32 and 48 in
one file), `drishti_elements-social.png` (1280 by 640: the logo, the line "Grid, pivot, charts, maps, inputs, tree, graph:
plain JavaScript", on the dark ground), `brand/README.md` (what each file is for; that the mark is the owner's and is not licensed
for reuse). Every name carries the `drishti_` prefix. The gallery's pages and the docs use the favicon and the logo
(`<link rel="icon">`, the gallery's header); the release tree ships `brand/`. `rm -rf brand/options $WORK/preview-logo`.

**I3. README, changelog, docs, docs site.** (Sonnet; the design doc is Opus, as in 6e.)

`README.md` at the root, in this order, each a short section:

1. The logo, the name, one sentence: what Drishti Elements is.
2. **The libraries**: the table of the eight with one line each and what each requires (generated from the manifest; a test fails
   when it is stale).
3. **Quick start for a plain HTML page**: the three lines (the token sheet, the core bundle, `Drishti.elements.load('drishti-grid')`)
   with paths relative to a copy of the release tree served by the reader's own web server, then a ten-line grid with rows.
4. **The gallery**: the link to the docs site, and how to open it locally (`python3 -m http.server` in the release tree).
5. **Versioning**: one version for every library, semver, today 2.4.0; where the changelog is.
6. **Browser support**: the current and previous major versions of Chrome, Edge, Firefox and Safari; no build step and no
   framework needed. (CI tests Chromium; say so.)
7. **Build and test**: the three gate lines of 6b.
8. **Licence**: the "Proprietary … no licence granted" paragraph of step 5, word for word.
9. **Ownership and contact**: the copyright holder's name; "for permission or questions, contact the owner through the GitHub
   profile `ajsinha`". No e-mail address is written in the README.

`CHANGELOG.md` at the root (one entry, 2.4.0: the first version of the separate project, what the eight libraries are, the four new
names of step 3). `docs/` as in 6e, with `docs/README.md` as the index.

**The gallery as the docs site.** `.github/workflows/pages.yml`: on a tag `v*`; builds the release tree, copies it to `_site/`,
adds `_site/docs/` (the guides and the reference rendered to HTML by `tools/docs/` with the gallery's sheet and the logo) and an
`_site/index.html` that opens `gallery/drishti_gallery.html`; deploys with `actions/upload-pages-artifact` and
`actions/deploy-pages`. Address: `https://ajsinha.github.io/drishti-elements/`. The site serves the same proprietary files the
repository already shows; its footer carries the "no licence granted" line. This is a docs site, not a CDN: the README and the docs
never tell a reader to load a script from it, and the guard that forbids CDN addresses in the sources stays.

```bash
python3 -m pytest -q tests -k "readme or docs" && grep -c "no licence is granted" README.md        # passes; 1
grep -cE "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[a-z]{2,}" README.md CHANGELOG.md                       # 0 for both
```

**I4. The repository's description, topics and social preview.** (Sonnet prepares the text; done at step 8, after the push; the
social preview by **the owner**.) Recorded here so the preview of step 7 shows them:

- Description: `Drishti Elements: grid with pivot, charts and maps, inputs, tree, graph and a designer kit in plain JavaScript. Proprietary; all rights reserved.`
- Homepage: `https://ajsinha.github.io/drishti-elements/`
- Topics: `javascript`, `web-components`, `data-grid`, `pivot-table`, `charts`, `maps`, `echarts`, `ui-components`, `no-framework`, `drishti`
- Social preview: `brand/drishti_elements-social.png` (GitHub offers no command for it: Settings → General → Social preview → Upload).

**STOP and ask the lead** if a logo file fails the checks of I1 (an embedded image, a font reference), or if the README test finds
an address or a Drishti release label.

---

## Step 7. Local preview for the owner

**Who:** Sonnet prepares; the owner decides. **Effort:** 0.5 day, plus the owner's time.

```bash
cd $EXTRACT && git status --short | wc -l                      # 0
mkdir -p $WORK/preview && python3 tools/elements/cli.py dist --out dist
cp -r dist/drishti-elements-$V dist/drishti-elements-$V.zip $WORK/preview/
git bundle create $WORK/preview/drishti-elements-history.bundle main
git log --stat --format='%h %ad %an %s' --date=short > $WORK/preview/history.txt
git ls-files > $WORK/preview/files.txt
cp $WORK/reports/*.md $WORK/reports/scan-*.txt $WORK/preview/ && cp -r LICENSE README.md CHANGELOG.md brand $WORK/preview/
(cd $WORK/preview/drishti-elements-$V && python3 -m http.server 8765 --bind <the machine's LAN address>)     # the gallery: http://<LAN address>:8765/gallery/drishti_gallery.html
```

**What the owner reviews** (Stop C; send this list with the folder `$WORK/preview`):

1. The gallery, running from the release tree with no Drishti server: every library's page, dark and light.
2. `README.md` as the public will read it (the "proprietary, no licence granted" paragraph), and the docs.
3. `files.txt`: every file that becomes visible, and the tree's size.
4. `history.txt`: every commit subject and file name that becomes visible, with its author.
5. The scan reports: zero hits, and `scan-allowed.md` (each false match and why).
6. `LICENSE` and one source file's header (unchanged from Drishti).
7. The decoupling report: the four new names and what Drishti will set.
8. The identity of step 6A: the chosen logo in every size and the favicon, the social preview image, the repository's description, homepage and topics (I4), and the docs site's address.
9. The sole-author report of step 4 (4e): one identity, zero hits.
10. **The visibility of the target repository**, read on the day: `gh repo view ajsinha/drishti-elements --json visibility,defaultBranchRef`.
   Recorded: **confirmed public by the owner on 2026-11-16.** The builder states the value it reads; if it is not `PUBLIC`, or the
   owner has since said otherwise, the builder stops and asks rather than change it.

**STOP: nothing is pushed until the owner says "approved" in words.** A change the owner asks for is made in `$EXTRACT`, the gate
and the scan are run again, the preview is rebuilt, and the owner looks again.

---

## Step 8. Push and tag

**Who:** Sonnet. **Effort:** 0.25 day.

```bash
# 8a. Run the scan of step 4 one last time, 4e included, on the exact commit to be pushed: every count 0, one identity. Then:
gh repo view ajsinha/drishti-elements --json visibility -q .visibility        # PUBLIC, as confirmed on 2026-11-16
git clone -q https://github.com/ajsinha/drishti-elements.git $TARGET && cd $TARGET
git ls-files; git log --oneline | wc -l            # README.md only; one or a few commits
git remote add extract $EXTRACT && git fetch -q extract main
git merge --allow-unrelated-histories -X theirs extract/main \
    -m "Drishti Elements 2.4.0: the libraries, the gallery, the build and the tests"       # the owner's identity
git remote remove extract
git log --format='%an <%ae>%n%cn <%ce>' | sort -u | wc -l          # 1: the README commit already in the repository and the merge are the owner's too
diff -rq --exclude=.git --exclude=dist $TARGET $EXTRACT | wc -l       # 0
git push -q origin HEAD:main                       # never --force
```

**STOP and ask the owner** if the repository holds anything but a README, if its default branch is not `main`, if the visibility
read is not the one confirmed, if the identity count is not `1` (the repository's existing commit has another author or committer:
the owner decides; never rewrite the remote), or if the push is refused (never force; a branch protection or a newer commit means someone changed
the repository).

```bash
# 8b. Wait for CI on main to be green. Then the tag, on that commit.
gh run list --repo ajsinha/drishti-elements --branch main --limit 1          # completed  success
git tag -a v2.4.0 -m "Drishti Elements 2.4.0" && git push -q origin v2.4.0
git rev-parse v2.4.0^{commit} > $WORK/elements-tag-commit.txt
```

```bash
# 8c. The repository's face (step 6A, I4). Sonnet runs the command; the owner does the two settings that have no command.
gh repo edit ajsinha/drishti-elements \
  --description "Drishti Elements: grid with pivot, charts and maps, inputs, tree, graph and a designer kit in plain JavaScript. Proprietary; all rights reserved." \
  --homepage "https://ajsinha.github.io/drishti-elements/" \
  --add-topic javascript --add-topic web-components --add-topic data-grid --add-topic pivot-table --add-topic charts \
  --add-topic maps --add-topic echarts --add-topic ui-components --add-topic no-framework --add-topic drishti
gh repo view ajsinha/drishti-elements --json description,homepageUrl,repositoryTopics
# the owner: Settings → Pages → Source "GitHub Actions" (once; the tag's pages.yml run then deploys), and
#            Settings → General → Social preview → upload brand/drishti_elements-social.png
curl -sI https://ajsinha.github.io/drishti-elements/gallery/drishti_gallery.html | head -1      # HTTP/2 200, after the owner turned Pages on and the run ended
```

No GitHub release with attached files, no npm, no CDN: those are the later phase. **STOP and ask the owner** if CI is red on
`main` (fix forward with a new commit and wait again; do not tag a red commit), or if `gh repo edit` is refused.

---

## Step 9. The Drishti side

**Who:** Opus for 9c and 9e (loader wiring, routes, Maven, the short doc); Sonnet for the rest. **Effort:** 3.5 days. Starts only
after step 8, on a branch from `develop` in its own worktree. It reaches `main` through the next drill like any other change.

```bash
cd $DRISHTI && git fetch -q origin && git worktree add -q ../drishti-elements-vendored -b wip/elements-vendored origin/develop
cd ../drishti-elements-vendored && git rev-parse HEAD > $WORK/drishti-before.txt

# 9a. Measure before.
xargs -a $WORK/move-paths.txt -I{} sh -c 'test -f "{}" && cat "{}"' | wc -l
python3 tools/docs/code_volume.py > $WORK/reports/volume-before.txt

# 9b. The vendored copy: the release tree built from the tag in a fresh clone, never from a working folder.
git clone -q --branch v$V https://github.com/ajsinha/drishti-elements.git $WORK/pin && cd $WORK/pin
test "$(git rev-parse HEAD)" = "$(cat $WORK/elements-tag-commit.txt)" && echo tag-ok
python3 tools/elements/cli.py dist --out dist && cd -
mkdir -p $S/vendor/drishti-elements && cp -r $WORK/pin/dist/drishti-elements-$V $S/vendor/drishti-elements/$V
ls $S/vendor/drishti-elements/$V          # LICENSE README.md CHANGELOG.md css data drishti-elements.json elements gallery themes tools vendor
```

**The pin has one home: the name of the single folder under `static/vendor/drishti-elements/`.** A guard fails when there are two
folders, or when `drishti-elements.json` inside says another version.

**9c. Remove what moved and point everything at the copy.**

```bash
grep -vE "/vendor/|world-regions.yaml|formats.yaml|^LICENSE$|^config/|tools/license_headers.py" $WORK/move-paths.txt | xargs git rm -q --ignore-unmatch
git rm -rq --ignore-unmatch packaging/drishti-elements $S/vendor/echarts $S/vendor/echarts-gl $S/vendor/apache-arrow $S/vendor/codemirror docs/architecture/ELEMENTS.md
git rm -q compute/drishti-rachana/src/main/resources/world-regions.yaml compute/drishti-rachana/src/main/resources/formats.yaml
```

(`echarts-gl` and `apache-arrow` move with Elements (manifest `assets`: drishti-chart lists `vendor/echarts-gl/`, drishti-data lists `vendor/apache-arrow/`) and Drishti's staying code (drishti_table.js, drishti_charts.js, the renderer) loads them from the vendored copy: one copy; Drishti's `LICENSE`, header file, header tool and `VENDOR.md` stay.)

| What | Change |
|---|---|
| The address of the copy | One template global beside `ASSET_V` in `drishti-console/core/app.py`: `ELEMENTS_DIR = "/static/vendor/drishti-elements/<pin>"`, read from the folder name. No template or script spells the version. |
| Templates and plugins (31 files today) | `sed -i -E 's#\{\{ BASE \}\}/static/elements/drishti-(core\|inputs\|grid\|chart\|data\|tree\|graph\|designer)/#{{ BASE }}{{ ELEMENTS_DIR }}/elements/drishti-\1/#g'`; the six core sheets `/static/css/drishti_(tokens\|resize\|chips\|states\|command-palette\|dock).css` → `{{ ELEMENTS_DIR }}/css/…`; `/static/vendor/(echarts\|codemirror)/` → `{{ ELEMENTS_DIR }}/vendor/…`; `/static/gallery/` → `{{ ELEMENTS_DIR }}/gallery/` with `?visuals=`. |
| The host script | New `static/js/drishti_elements-host.js`, loaded in `base.html` straight after the core bundle: registers the three Drishti libraries and each hosted module's library (C2), sets `endpoints` (C4, C5) and `explain` (C7). The only place Drishti's routes are told to Elements. |
| The three libraries | Stay at `static/elements/drishti-{insight,renderer,risk}/`. `static/elements/drishti_elements.json` keeps only their three entries and loses the `elements` key (the version is the pin). Built by the vendored tool: `python3 $S/vendor/drishti-elements/<pin>/tools/elements/build.py --root $S --manifest $S/elements/drishti_elements.json --header config/license-header.txt`. Their imports of the eight go through the vendored entry files. |
| Python that reads Elements files | `core/render_dist.py` (`VERSION` from the pin; `LIBRARY` unchanged), `core/element_sheet.py` and `tools/render_sheet.py` (sheet paths under the copy), `core/element_api.py`, the embed routes (`embed_routes.py`, `embed_v2_routes.py`: files served from the copy), `routes/help_routes.py`, `core/schemakit/build.py` and `tools/packgen/banking/make_packs.py` (the path of `formats.yaml`). Find them all: `git grep -nE "static/elements\|formats\.yaml\|world-regions\.yaml\|tools/elements\|vendor/(echarts\|codemirror)" -- '*.py' '*.sh' '*.ps1' '*.xml' '*.yml'`. |
| The two tables (R3) | `compute/drishti-rachana/pom.xml`: a second `<resource>` whose directory is `${maven.multiModuleProjectDirectory}/drishti-console/web/static/vendor/drishti-elements/${drishti.elements.version}/data` with `<include>world-regions.yaml</include>` and `<include>formats.yaml</include>`; the property `drishti.elements.version` in the root `pom.xml`, checked against the pin by the guard. `WorldRegions.java` and `Formats.java` do not change. |
| The render folder | `static/render/<pin>/` stays and is built in Drishti (`tools/render_sheet.py`). **The redirect policy is unchanged:** when the pin moves, a new folder is written, the previous folder's paths answer `308` to the current one with `Deprecation: true` and `Drishti-Deprecated` for one release, then are removed; `1.2.0` stays frozen. `RETIRED = ("2.0.0",)` is removed in the release after i6 as already planned. |
| The CLI and the WebJar | The `elements` command group leaves `tools/drishti.py`; the profile `elements` and its module leave the root `pom.xml`. `tools/docs/code_volume.py` and the licence-header tool already skip `vendor`. |
| The module ranges | `elements: '>=2.4 <3'` in every `module.yaml` stays; it is now checked against the pin. |

**9d. `VENDOR.md`.** One new row; the ECharts, CodeMirror, Natural Earth and world-regions rows are replaced by one line saying
they now come inside Drishti Elements (their rows and licence files are in its own `vendor/`).

```text
| **Drishti Elements** (an external component with its own repository and docs: github.com/ajsinha/drishti-elements, ajsinha.github.io/drishti-elements; grid and pivot, charts and maps, inputs, tree, graph, data client, designer kit; with its engines: Apache ECharts, CodeMirror, the Natural Earth map shapes) | 2.4.0 (github.com/ajsinha/drishti-elements, tag v2.4.0, commit <id from $WORK/elements-tag-commit.txt>; the release tree built by `tools/elements/cli.py dist`) | Proprietary, the same owner and `LICENSE` as Drishti (`LICENSE` beside it); its engines' licences in its `vendor/VENDOR.md` | `drishti-elements/2.4.0/` | yes | every page |
```

Below it, "Updating": clone the Elements repository at the new tag into an empty folder outside this repository, check the commit
id, run `python3 tools/elements/cli.py dist`, copy the tree into `drishti-elements/<new>/`, delete the old folder, set
`drishti.elements.version`, rebuild the three libraries and the render folder, run the guards. Nothing else names the version.

**9e. Docs: one home per fact.**

- Deleted: `docs/architecture/ELEMENTS.md`; `EMBED_GUIDE.md` sections 13 and 14; the Elements README; the eight libraries' part of
  `docs/guides/ELEMENTS_API.md` (the file stays, generated by the vendored generator for the three Drishti libraries only, and its
  first line links to the Elements reference).
- New, short: `docs/architecture/ELEMENTS_IN_DRISHTI.md`: the pin and how to move it, the host script and what it sets, the three
  Drishti libraries, the two tables read from the copy, the render folder policy, and the checklist that was `ELEMENTS.md`
  section 12. It links to the Elements docs for everything else.
- Every other link to the deleted text (171 files name one of the three documents today) goes to the Elements repository's `docs/`
  (`https://github.com/ajsinha/drishti-elements/blob/v2.4.0/docs/…`, the tag taken from the pin) or to the new short doc:
  `git grep -lE "ELEMENTS\.md|ELEMENTS_API\.md|EMBED_GUIDE\.md#1[34]"` must end with only correct targets, and
  `tests/test_docs_rot.py` must pass. Help's "Elements gallery" entry opens the vendored gallery (offline).
- **Drishti's root `README.md`** (Sonnet): wherever it describes Elements as part of Drishti, it now says, in one short
  paragraph, that Drishti Elements is a separate project by the same owner, which Drishti uses as an external, vendored component
  at a pinned version (`static/vendor/drishti-elements/<pin>/`, see `VENDOR.md`), with a link to
  `https://github.com/ajsinha/drishti-elements` and to its docs site. Counts of JavaScript lines and libraries in the README are
  taken again from the file system. The `VENDOR.md` row of 9d says the same ("external component, its own repository") and
  carries both links.
- `CHANGELOG.md`: one entry for the release this lands in.

**9f. Tests and guards.**

- Deleted: every test file listed in `move-paths.txt` (already removed by 9c) and the halves the table of 6b sent to Elements.
- Kept: the three libraries' Node and browser tests, the embed and render tests, `test_elements_guards.py` (no console script keeps
  a second implementation).
- New guard `tests/test_elements_vendored.py`: exactly one folder under `vendor/drishti-elements/`; its manifest's version equals
  the folder name and `drishti.elements.version`; `LICENSE` is present; the folder's files match the SHA-384 hashes in its own
  `drishti-elements.json` (nobody edits the copy); no tracked file outside the copy lives under
  `static/elements/drishti-{core,inputs,grid,chart,data,tree,graph,designer}/`, `static/gallery/`, `tools/elements/`; no template
  or script spells `drishti-elements/2.` (the version comes from `ELEMENTS_DIR` only); `static/vendor/echarts` and
  `static/vendor/codemirror` do not exist; every module's `elements:` range admits the pin.
- `test_vendor_licences.py` stays: the allow-list gains "Proprietary (same owner)" for this one row, and the engines inside the
  copy are checked through the copy's own `vendor/VENDOR.md`.

**9g. The gate and the measure.**

```bash
git grep -nE "static/elements/drishti-(core|inputs|grid|chart|data|tree|graph|designer)/|static/gallery/|tools/elements/" -- . ":!$S/vendor" ':!docs/plans' ':!CHANGELOG.md' | wc -l    # 0
mvn -q clean && tools/drill.sh --gate                         # only when no drill is running; one heavy job at a time
tools/drill.sh --affected --base origin/develop
git diff --shortstat $(cat $WORK/drishti-before.txt) -- . ":!$S/vendor"
python3 tools/docs/code_volume.py > $WORK/reports/volume-after.txt; diff $WORK/reports/volume-before.txt $WORK/reports/volume-after.txt
```

**Expected.** The first count is `0`; the gate and the affected tests pass; **the shortstat shows about 100,000 lines deleted
outside `vendor/`** (the 9a number, less what the three libraries' reference keeps) against a few hundred inserted. Then commit,
push the branch as `wip/elements-vendored`, and hand it to the lead for the merge and the drill.

**STOP and ask the lead** when: a page draws differently with the vendored copy (the browser lane shows it); the Maven build cannot
see the copy from a module built alone (`-pl`); a module's own library fails to load through `register`; the gate fails for a
reason outside Elements.

---

## Step 10. Rollback, risks, effort, who does what

**Rollback**

| When it goes wrong | What to do |
|---|---|
| Steps 1 to 7 | Nothing was pushed and Drishti was not touched: `rm -rf $WORK` and start again from the i6 tag. |
| After the push (step 8) | The repository can be corrected with new commits. If something confidential was pushed: the owner makes the repository private at once (Settings), then decides; a pushed commit on a public repository may already have been copied or cached, so treat any secret as exposed and rotate it. Never force-push to hide it without the owner. |
| Step 9 fails the gate or the drill | The branch is not merged; Drishti's `develop` still holds the sources and works as before. Drop the worktree and branch, fix in Elements if the fault is there (tag 2.4.1), and take the new tag. |
| After step 9 is on `main` | Revert the merge commit in Drishti (the sources come back from git history). The Elements repository is unaffected. |

When step 9 is on `main` and the drill is green: `rm -rf $WORK` (after the owner has what they want from `$WORK/preview`), and
`git worktree remove ../drishti-elements-vendored`.

**Risks**

| Risk | Guard |
|---|---|
| **Proprietary code in a public repository** (the owner's confirmed choice, 2026-11-16). Anyone can read and clone it. GitHub's terms let other users view and fork a public repository inside GitHub whatever the licence says. The header calls each file "confidential" while the owner publishes it, which weakens any later claim that the code is a trade secret; "all rights reserved" (copyright) is not weakened. Search engines and code-search tools will index it. | The README's "no licence granted" paragraph; the scan with zero hits; the owner reads every file name and commit subject in the preview. Recommended, not required here: a lawyer's short look at the header's wording for a publicly visible proprietary repository (open question Q7). Making the repository private later does not recall copies. |
| A commit, trailer or line that names another author, a tool or an assistant reaches the public repository | Step 4e over every commit of the extraction, zero hits, repeated in step 8; cleared only in the scratch extraction; the target's own first commit is checked before the push. |
| Something internal becomes visible: a name in an old revision, a commit message, a demo row | Step 4 scans every revision, message and file name, three times (after step 3, after step 6, before the push); the fallback is one commit without history. |
| The freeze window is long and the two copies drift | Steps 1 to 8 are about two weeks of work; the lead announces the freeze of `move-paths.txt` on Drishti's side for that time; step 9 starts the day after step 8. |
| A Drishti screen changes because a default became an option | The host script sets every option to today's value; the browser lane and the page walk run in the gate and the drill. |
| The renderer needs a fix between two Elements versions, but its folder is named by the pin and a released folder never changes | Open question Q4. Until answered: such a fix waits for an Elements patch tag, which moves the pin. |
| The Maven build reads two resource files from the console's vendor folder | The guard checks the property against the pin; the path is anchored to the repository root, so a module built alone still finds it. |
| The docs site serves the proprietary bundles to anyone, like a file host | The same files are already visible in the public repository; the site's footer and the README carry "no licence granted"; no doc tells a reader to load a script from the site. The owner can turn Pages off in one setting. |
| A logo that resembles someone else's mark | Drawn in-house from plain shapes; three options; the owner chooses; no third-party artwork or font file. |
| CI on a public repository shows its logs to everyone | The workflow uses no secret and uploads nothing; tests print only invented rows. |
| The vendored copy is edited in place in Drishti | The guard compares every file with the hashes in the copy's own manifest. |

**Effort and who does what** (builder-days; the owner's time not counted)

| Step | Days | Sonnet | Opus | Owner |
|---|---|---|---|---|
| 1 Inventory | 0.5 | all | | **Stop A**: confirm R1 to R9 |
| 2 Extraction | 0.5 | all | | |
| 3 Decoupling | 4 | C1, C3, C6, C9, C12 to C14 (2 days) | C2, C4, C5, C7, C8, C10, C11 (2 days) | |
| 4 Confidentiality scan | 1 | runs and clears HEAD | decides history hits | **Stop B**: reviews the name list; any real name found |
| 5 Header, licence file | 0.25 | all | | |
| 6 Build, tests, CI, release tree, docs | 3.5 | build, tree, CI (1.5 days) | sorting the tests, the design doc (2 days) | |
| 6A Identity: logo, README, docs site, repository face | 1.5 | all | | **Stop L**: chooses the logo; later the social preview upload and the Pages setting |
| 7 Preview | 0.5 | prepares | | **Stop C**: approves the preview; visibility read on the day |
| 8 Push and tag | 0.25 | all | | |
| 9 Drishti side | 3.5 | 9a, 9b, 9d, 9f, 9g, links (2 days) | 9c wiring, routes, Maven, the short doc (1.5 days) | the drill and the merge, as for any release |
| **Total** | **15.5** | **about 10** | **about 5.5** | four stops |

With one builder that is about three working weeks from the i6 tag to the Drishti branch being ready for its drill; steps 3
and 6 can overlap with two builders (decoupling and CI touch different files), which brings it to about two weeks.

---

## Later phase, NOT NOW: open licence, npm, CDN

**Nothing in this section is done in the separation.** It is kept here so the later phase starts from decisions already taken
(O8). It begins only when the owner says so, on the Elements repository alone; Drishti is touched only at L7. About 3.5
builder-days plus the owner's and the lawyer's time. The README's licence section, the docs site's footer and the repository's
description lose the word "proprietary" in the same commit as L1.

**L1. The header changes to Apache-2.0, in one commit.** `config/license-header.txt` becomes exactly:

```text
Drishti Elements
Copyright 2026 Ashutosh Sinha

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

SPDX-License-Identifier: Apache-2.0
```

`python3 tools/license_headers.py --fix && python3 tools/elements/build.py`, one commit. Expected: `python3
tools/license_headers.py` passes and `git grep -L "SPDX-License-Identifier: Apache-2.0" -- 'elements/**/*.js' 'css/*.css'
'tools/**/*.py'` prints nothing. Drishti's own files keep the proprietary header. The lawyer is asked whether the older revisions,
which carry the proprietary header, need a statement in `NOTICE` that the whole history is offered under Apache-2.0 from that
commit on, or should be left as they are.

**L2. `LICENSE`** becomes the Apache-2.0 text, unchanged (`curl -fsS https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE`;
`grep -c "END OF TERMS AND CONDITIONS" LICENSE` prints `1`). **`NOTICE`**:

```text
Drishti Elements
Copyright 2026 Ashutosh Sinha

This product includes software developed by Ashutosh Sinha.

"Drishti" and "Drishti Elements" are trademarks of Ashutosh Sinha. The Apache
License, Version 2.0 grants no permission to use them (section 6). See
TRADEMARKS.md.

This product bundles third-party software and data. Their licences and
notices are in THIRD-PARTY-NOTICES.
```

followed by the full `NOTICE` of every bundled Apache-2.0 library that ships one (today Apache ECharts), as section 4(d) asks.

**L3. `THIRD-PARTY-NOTICES`, generated** by a new `tools/elements/notices.py` from `vendor/VENDOR.md` and the folders: for each
folder under `vendor/`, the row's name, version, licence and source, its licence file in full, its NOTICE if any; then the
"Algorithms and published constants used" section (Equal Earth, CRC-32), Natural Earth's public-domain statement and the boundary
note. `--check` fails when stale; the gate runs it.

**L4. `CONTRIBUTING.md` with DCO, and `TRADEMARKS.md`.** Every commit of a pull request carries `Signed-off-by:` (`git commit -s`),
certifying the Developer Certificate of Origin 1.1 (full text from https://developercertificate.org); no copyright assignment; a
CI job on pull requests fails on a commit without the line. `TRADEMARKS.md`: you may say truthfully that your work uses Drishti
Elements; you may not name a fork, product or package with the marks or suggest endorsement without written permission; "AG Grid"
is a trademark of AG Grid Ltd and the adapter was written from public documentation only.

**L5. The lawyer** reads `LICENSE`, `NOTICE`, `THIRD-PARTY-NOTICES`, `TRADEMARKS.md`, `CONTRIBUTING.md`, the header and
`vendor/VENDOR.md` before the first open release. **STOP until the owner relays the answer.**

**L6. npm, then the CDNs.** `dist.py` adds a generated `package.json` to the release tree (name `drishti-elements`, or
`@ajsinha/drishti-elements` if the name is taken: `npm view drishti-elements name` answers E404 when free; version from the
manifest; `license: Apache-2.0`; `author: Ashutosh Sinha` and no `contributors`; `main` the core bundle, `module` core's `src/drishti_index.js`, `types` its `.d.ts`; `exports`
with one entry per library (`./core`, `./grid`, … each with `types`, `import` → `src/drishti_index.js`, `require` → the bundle)
plus `./elements/*`, `./css/*`, `./themes/*`, `./vendor/*`, `./data/*`, `./manifest`; `files` the tree's folders with `LICENSE`,
`NOTICE`, `THIRD-PARTY-NOTICES`; no `dependencies`, no `scripts`), and a one-line `{"type":"module"}` `package.json` in each
`elements/<library>/src/`. Order: tag, CI green, `npm publish --dry-run`, then **the owner** runs `npm login` and `npm publish
--access public`. A published version can never be replaced: a fault is fixed by the next patch version. The CDNs then serve it by
themselves: `https://cdn.jsdelivr.net/npm/drishti-elements@<v>/elements/drishti-core/drishti_core.js` and
`https://unpkg.com/drishti-elements@<v>/…`; check each answers `200` and that `openssl dgst -sha384` of the file equals the hash
in `drishti-elements.json`. Docs pin an exact version and show the `integrity` attribute. Drishti itself never loads from a CDN.

**L7. Drishti's row.** In Drishti, the `VENDOR.md` row's licence
becomes Apache-2.0 with `NOTICE` and `THIRD-PARTY-NOTICES` beside the copy, the guard requires those two files, and the update
recipe may take the npm tarball (checked against `npm view … dist.integrity`) instead of building from the tag.

## Open questions for the owner

| # | Question | This runbook assumes |
|---|---|---|
| Q1 | Do drishti-insight and drishti-renderer stay in Drishti (R1), or should they move too, later? | They stay. drishti-risk never moves. |
| Q2 | `formats.yaml` moves its home to Elements together with `world-regions.yaml` (R3). Agreed, or should formats stay in Drishti and be passed to Elements at run time? | Both move. |
| Q3 | The existing README commit in the target repository was made on GitHub: is its author and committer the owner's identity (GitHub's web editor commits as "GitHub" for the committer)? If not, the owner decides between leaving it and recreating the empty repository. | Checked in step 8; the builder stops if the count is not 1. |
| Q4 | After the split, do Drishti's three libraries and the render folder keep the Elements version (the pin), or get Drishti's own? | The pin, as today's one-version rule says. |
| Q5 | Who is on the bank and client name list of step 4, beyond the two public regulator lists? | The owner adds names at Stop B. |
| Q6 | If the history scan cannot reach zero, is one initial commit without history acceptable? | Yes, as the fallback. |
| Q7 | With the repository public and the code proprietary, should the header's word "confidential" stay, and should a lawyer look at that now rather than only at the later phase? | The header stays unchanged; no lawyer step in the separation. |
| Q8 | While proprietary, are GitHub issues and pull requests on the public repository open or turned off? | Turned off in the repository's settings by the owner; the README says contributions are not accepted. |
