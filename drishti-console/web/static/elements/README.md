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
# Drishti Elements

Drishti Elements is the console's JavaScript as reusable libraries: formats, theme tokens, the API client, dialogs and toasts, the state
kit and the command palette (drishti-core); inputs and forms; the grid and its pivot; charts, maps and the bullet bar; the data client;
trees and graphs; the designer kit; Explain and approvals; the screen renderer; and risk kinds. One implementation of each thing, every
screen built from them. The design is [docs/architecture/ELEMENTS.md](../../../../docs/architecture/ELEMENTS.md); the typed reference is
[docs/guides/ELEMENTS_API.md](../../../../docs/guides/ELEMENTS_API.md), generated from the JSDoc.

## The libraries

| Library | Version | Requires |
|---|---|---|
| `drishti-core` | 1.3.0 | none |
| `drishti-inputs` | 0.3.1 | `drishti-core` |
| `drishti-grid` | 0.6.0 | `drishti-core`, `drishti-inputs`, `drishti-chart` |
| `drishti-tree` | 0.2.0 | `drishti-core`, `drishti-inputs`, `drishti-grid` |
| `drishti-designer` | 0.2.0 | `drishti-core`, `drishti-inputs`, `drishti-tree` |
| `drishti-data` | 0.1.0 | `drishti-core` |
| `drishti-chart` | 0.3.0 | `drishti-core`, `drishti-inputs` |
| `drishti-graph` | 0.1.0 | `drishti-core`, `drishti-inputs`, `drishti-grid` |
| `drishti-insight` | 0.1.0 | `drishti-core` |
| `drishti-risk` | 0.2.0 | `drishti-core`, `drishti-grid`, `drishti-chart` |
| `drishti-renderer` | 2.0.0 | `drishti-core`, `drishti-grid`, `drishti-chart`, `drishti-data`, `drishti-graph`, `drishti-tree` |

The manifest [drishti_elements.json](drishti_elements.json) is the one list; each library is ES modules under `<library>/src/` and one
bundled classic script `<library>/drishti_<name>.js` written by `python3 tools/elements/build.py` (offline, no npm, no CDN).

## Using them

```html
<link rel="stylesheet" href="/static/css/drishti_tokens.css">
<script src="/static/elements/drishti-core/drishti_core.js"></script>
<script>
  Drishti.elements.load('drishti-grid').then((grid) => { /* <drishti-grid> is defined */ });
</script>
```

The loader loads a library's requirements, its style sheets and its vendored engine, once each. Under Node the bundle is a CommonJS module
of the same API. Colours come from the theme tokens only, so dark and light work; every component has a keyboard path, honours
`prefers-reduced-motion` and offers a table or text alternative to a drawing.

## The gallery

`/static/gallery/drishti_gallery.html` shows every component live with its options, its events and the code to use it; the elements
catalogue (`gallery/drishti_catalogue.js`) is generated from the same JSDoc as the reference.

## Roadmap: Drishti Elements as its own project

Design only; nothing here is built. The aim is that other teams use Elements without Drishti, while Drishti keeps using it unchanged.

**Repository and build.** A separate repository holding the libraries, the tokens, the gallery, the build (`tools/elements/`), the API
generator (`tools/docs/elements_api.py`) and the Node tests. Drishti takes a pinned copy of a released build into
`static/elements/` (vendored, as every other library: `VENDOR.md`), so the rule that Drishti's own pages load nothing from a CDN holds.

**Versioning.** Its own semantic version, one for the project (the libraries keep their own numbers inside the manifest, as now): a
breaking change in any library's public API is a major version of the project. Drishti records the version it vendors.

**Distribution.** An npm package (ES modules and the bundles, with the style sheets and the type declarations `dts.py` already writes) and
a public CDN build for other users (jsDelivr and unpkg serve the npm package; the bundles carry Subresource Integrity hashes in a
published manifest). The CDN is for other users only.

**Licence.** The owner decides (today: proprietary). The choice settles what the package and the CDN may carry and which vendored engines
(ECharts: Apache-2.0) can ship with it.

**API stability and deprecation.** The public API is what a library's entry exports and ELEMENTS_API.md lists. Additions are minor;
a rename or removal is a major and is deprecated for one minor release first (the old name still works, warns once in the console and is
named in the changelog). Options, events, CSS custom properties and class prefixes are API; undocumented internals are not.

**Docs site.** Built from the gallery and the generated reference: the same pages, static, with each demo's code and a live frame.

**Theming contract.** The tokens (`--d-*`) are the contract: a host sets them (or picks a theme) and every component follows; a token is
never removed in a minor; contrast is checked per theme (`contrastProblems`). A host may supply its own set under another name by
mapping it to `--d-*`.

**Test and CI.** The Node tests run on every change; a browser job runs the gallery and each harness in dark and light; guards fail the
build on a hard-coded colour, a CDN address, a class without its prefix, a duplicate capability name in the kind registry, a stale
bundle, catalogue or reference, and a bundle over its byte budget.

**What must be untangled first.**
- Drishti-specific calls: `/api/...` paths, the console's `drishti_url`, Drishti error codes (DRS-), the module licensing field of the
  manifest and the banking kinds (drishti-risk stays in Drishti or becomes a separate add-on).
- Server coupling: Explain, the pivot's server answers, the live feed and the data client assume the Drishti server's shapes; each becomes
  a documented adapter interface with a Drishti implementation beside it.
- Copyright and branding text in file headers and the gallery; product names only from configuration.
- The console's page scripts that still use their own helpers (the migration checklist of ELEMENTS.md section 12).

**Steps and rough effort.**
1. Finish the migrations of section 12 and the adapter interfaces (3 to 4 weeks).
2. Cut the repository with its history for `static/elements`, `tools/elements`, the gallery and the tests; Drishti consumes a pinned copy
   (1 week).
3. CI, guards, the byte budget and the docs site (1 to 2 weeks).
4. The npm package, the type declarations, the CDN build and the licence file once the owner decides (1 week).
5. First public release, then a deprecation policy review after two minors (ongoing).
