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

  The docs fragments tools/dev/asset_prefix.py apply adds (once each: a fragment whose first line is already in its file is skipped).
  A fragment starts with a line: fragment: <file> | <where>[ | else-before:<line start> | heading:<heading line>], in an HTML comment.
  <where> is after:<line start> (after that section's heading line... the fragment goes at the end of that section) or
  before:<line start>; with no match, else-before places "heading" and the fragment before that line; else the fragment is appended.
-->
<!-- fragment: CHANGELOG.md | end-of:## 2.9.11.c | else-before:## 2.9.11.b | heading:## 2.9.11.c — The lake configuration, Arrow Flight and the client examples (unreleased) -->
- **Every static asset we wrote is named `drishti_<name>`, and the console puts one global on `window` (product owner, 2026-11-03).** The
  console's own files under `web/static/` (`js/`, `js/build/`, `css/`, `img/`, `calc/`, the web manifest) are renamed by
  `tools/dev/asset_prefix.py` (`bi-runs-tab.js` -> `drishti_bi-runs-tab.js`, `drishti-logo.png` -> `drishti_logo.png`), with every template, route,
  test, tool and document that names them; vendored libraries and the published renderer versions keep their names. Every global the scripts
  set moves under `window.Drishti` (`window.drishtiWorkbench` -> `window.Drishti.workbench`, `window.DrishtiBusy` -> `window.Drishti.Busy`), except
  `drishtiModules`, `drishtiBi` and `drishtiGeo`, which the published renderer and Elements versions read. Custom events start `drishti:`
  (`bi:reload` -> `drishti:bi-reload`). The embed routes keep their public names (`/embed/v2/js/tables.js` serves `drishti_tables.js`), and
  `/static/js/<name>.js` for an old name answers with the `drishti_` file, so renderer 1.0.0-1.2.0 pages keep working. Guard:
  `drishti-console/tests/test_asset_names.py` (file names, window globals, storage keys, custom elements and events, no new generic CSS class
  or third-party data- attribute). The mapping is `tools/dev/asset_prefix_map.yaml`.
<!-- fragment: drishti-console/web/guides/developer.md | append -->
## Static asset names and the one namespace

The console's static files, scripts and styles share pages with other libraries (vendored ones) and, embedded, with a host's own. So that
nothing of ours collides with theirs:

* **Files.** Every file we write under `drishti-console/web/static/` (`js/`, `js/build/`, `css/`, `img/`, `img/guide/`, `img/icons/`, `calc/`
  and the web manifest) is named `drishti_<name>.<ext>`: the rest of the name says what it is (`drishti_bi-runs-tab.js`,
  `drishti_tokens.css`, `drishti_logo.png`). The Calc module `calc/drishti.py` is already ours by name. Vendored libraries
  (`static/vendor/`, listed in VENDOR.md) keep their own names, and a published renderer version (`static/render/<version>/`) is never
  changed.
* **One global.** Our scripts put one name on `window`: `Drishti`. A script that exports something starts with
  `(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis);` and sets
  `window.Drishti.<name>` (`Drishti.workbench`, `Drishti.Busy`, `Drishti.pivotEngine`, `Drishti.drsMessage`). Three older names stay on
  `window` because the published renderer and Elements versions read them: `drishtiModules` (the root-scoped behaviours), `drishtiBi` (the
  report code) and `drishtiGeo` (the map).
* **Events, storage, elements.** A custom event is named `drishti:<what>` (`drishti:bi-reload`, `drishti:var-painted`); a browser storage
  key starts `drishti.` or `drishti-`; a custom element is `drishti-<name>`.
* **Classes and data- attributes.** A new CSS class is `drishti-<name>`, never a generic word. The generic classes the sheets already
  define (`on`, `ok`, `mono`, `pill`...) and the data- attributes other libraries also read (`data-theme`, `data-state`, `data-target`,
  `data-action`, `data-toggle`...) are shared with the published renderer and Elements versions, so they keep their names while those
  versions are served.
* **Addresses.** The embed routes keep their public names (`/embed/v1/js/<name>.js`, `/embed/v2/js/<name>.js`, `/embed/v2/css/<name>.css`
  serve `drishti_<name>`), and `/static/js/<name>.js` or `/static/css/<name>.css` for a name without the prefix answers with the
  `drishti_` file: what a page pinned to an older renderer version asks for.

`drishti-console/tests/test_asset_names.py` refuses a new file, global, event, storage key or custom element without the prefix, and a new
generic class or third-party data- attribute. `tools/dev/asset_prefix.py` makes the change on any checkout (`scan`, `apply`, `check`), from
the mapping in `tools/dev/asset_prefix_map.yaml`; a second run changes nothing.
<!-- fragment: docs/architecture/CLIENT_RENDERER.md | before:## 13. The Elements, versioning and distribution -->
### Names a host page sees

The console's scripts and styles keep out of a host's way: every static file we wrote is named `drishti_<name>` (`drishti_tables.js`,
`drishti_tokens.css`), our scripts set one global, `window.Drishti`, custom events are `drishti:<what>`, custom elements `drishti-<name>`
and storage keys start `drishti`. The names a published renderer or Elements version uses are its contract and stay as they are while it
is served: the globals `drishtiModules`, `drishtiBi` and `drishtiGeo`, the classes and data- attributes of its markup, and the addresses
it loads (`/static/js/<name>.js` and the embed routes' `/js/<name>.js` answer with the `drishti_` file). The developer guide
(`drishti-console/web/guides/developer.md`, *Static asset names and the one namespace*) has the rules.

