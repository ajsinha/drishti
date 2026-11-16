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

All at Drishti Elements **2.4.0**.

| Library | Requires |
|---|---|
| `drishti-core` | none |
| `drishti-inputs` | `drishti-core` |
| `drishti-grid` | `drishti-core`, `drishti-inputs`, `drishti-chart` |
| `drishti-tree` | `drishti-core`, `drishti-inputs`, `drishti-grid` |
| `drishti-designer` | `drishti-core`, `drishti-inputs`, `drishti-tree` |
| `drishti-data` | `drishti-core` |
| `drishti-chart` | `drishti-core`, `drishti-inputs` |
| `drishti-graph` | `drishti-core`, `drishti-inputs`, `drishti-grid` |
| `drishti-insight` | `drishti-core` |
| `drishti-risk` | `drishti-core`, `drishti-grid`, `drishti-chart` |
| `drishti-renderer` | `drishti-core`, `drishti-grid`, `drishti-chart`, `drishti-data`, `drishti-graph`, `drishti-tree` |

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

## Versioning

**One version for all of Drishti Elements**, and it is not Drishti's own (which is 2.9.11.i…). Core, inputs, grid, chart, data, insight,
renderer, risk, designer, graph, tree and every domain library (drishti-genomics) are released together at one semantic version; no
library has a version of its own. The one source is `elements` in [drishti_elements.json](drishti_elements.json); everything else reads
it or is generated from it (`python3 tools/elements/build.py`): the bundles' headers, the loader's cache key (`Drishti.elements.core.ELEMENTS_VERSION`), the
renderer's folder `static/render/<version>/` and its `VERSION`, the npm packages and the WebJar, `ELEMENTS_API.md`, the gallery's
catalogue, this README. `tests/test_elements_version.py` fails the build on any other Elements version string.

The number is semver. A removed or changed public name, option, event or CSS custom property is a major; a new capability a minor; a fix a
patch. The first uniform version is **2.4.0**, the next release after the highest version in use before it (the Elements 2.x embed at
2.3.0 and the renderer at 2.0.0). Each release is a new renderer folder (a released folder never changes); the previous renderer's
folder is retired, except 1.2.0, which stays frozen for the embedders that pinned it. For one release the retired paths redirect: `render/2.0.0/…` (and `/embed/v2/elements/2.3.0/…`) answer `308` to the current folder with `Deprecation: true` and a `Drishti-Deprecated` header: the retired paths are removed in the release after i6. A domain library's compatibility with the console is
the `elements:` range in its module's `module.yaml`.

## Roadmap: Drishti Elements as its own project

Design only; nothing here is built.

**The end state.** Drishti Elements becomes a standalone project that hosts its own components, gallery, docs and published builds (npm and
a public CDN, like any CDN library). Drishti then no longer hosts Elements' sources, docs or gallery: it consumes Elements as a vendored,
pinned copy under `static/vendor/` (with its `VENDOR.md` entry, the same as ECharts), updated by a version bump. After the split the docs
live in the Elements project and Drishti links to them (one home per fact).

**What moves out of the Drishti repository at the split**
- the sources: `static/elements/` (every library's `src/`, bundles, style sheets, harnesses), the shared tokens and component sheets in `static/css/` that the libraries load, and the build (`tools/elements/`, `tools/docs/elements_*.py`, the sheet generators);
- the gallery (`static/gallery/`) and its catalogue;
- the documents: `ELEMENTS.md`, `ELEMENTS_API.md`, the Elements parts of `EMBED_GUIDE.md`, this README;
- the Elements tests (`tests/elements/*.test.mjs`, the `test_elements_*` browser and static guards);
- the renderer's hosting: `static/render/<version>/` and the `/embed/v2/render` and `/elements` routes' files become the vendored copy served by Drishti (the folders are built in the Elements project).

**What stays in Drishti**
- the vendored, pinned copy of a released Elements build (`static/vendor/drishti-elements/<version>/`, `VENDOR.md`: version, source, licence, how to update);
- Drishti's own pages, templates and page scripts that call the libraries, and the console's routes (embed API, `render_dist`, the visuals API);
- domain glue that is not reusable (the banking module's risk screens and kinds, the genomics module's library) and each module's `elements:` range;
- the server contracts the adapters call (Explain, pivot, live), which Elements documents as interfaces.

The aim is that other teams use Elements without Drishti, while Drishti keeps using it unchanged.

**Repository and build.** A separate repository holding the libraries, the tokens, the gallery, the build (`tools/elements/`), the API
generator (`tools/docs/elements_api.py`) and the Node tests. Drishti takes a pinned copy of a released build into
`static/elements/` (vendored, as every other library: `VENDOR.md`), so the rule that Drishti's own pages load nothing from a CDN holds.

**Versioning.** The one Elements version (section Versioning above) becomes the project's own: a
breaking change in any library's public API is a major version of the project. Drishti records the version it vendors.

**Distribution.** An npm package (ES modules and the bundles, with the style sheets and the type declarations `dts.py` already writes) and
a public CDN build for other users (jsDelivr and unpkg serve the npm package; the bundles carry Subresource Integrity hashes in a
published manifest). The CDN is for other users only.

**Licence.** See *Licences and notices* below (decided: Apache-2.0 at the split).

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

## Licences and notices

Decided by the owner for the split; until then nothing changes and every file keeps the current proprietary header.

- **Elements' own licence is Apache-2.0** (with a `NOTICE` file and the patent grant) from the split on. **"Drishti" and "Drishti Elements" stay the owner's trademarks**: the licence grants no trademark right, and the project's trademark policy says so.
- **Contributions** from outside need a sign-off (the Developer Certificate of Origin, `Signed-off-by:` on each commit); no copyright assignment.
- **File headers** change from the proprietary header to the Apache-2.0 header for Elements' files only, at the split, in one commit (the licence-header tool does it; Drishti's own files keep theirs).
- **A lawyer reviews** the licence, the notices and the trademark policy before the first public release.
- **Third-party notices.** The published bundle ships a `THIRD-PARTY-NOTICES` file generated from `static/vendor/VENDOR.md` (every vendored library: name, version, licence, its licence text, and Apache-2.0 NOTICE files where upstream has them). Only permissive licences are vendored (MIT, Apache-2.0, BSD-2/3, ISC, SIL OFL 1.1, MPL-2.0 unmodified, public domain or CC0, PSF); `tests/test_vendor_licences.py` refuses anything else (GPL, AGPL, LGPL, SSPL, CC-BY-NC/ND, the Commons Clause, BUSL, an unknown licence), a folder without its row, or a licence file or NOTICE that went missing. Elements' own sources were scanned for copied code: none. The two published methods used (the Equal Earth coefficients, the CRC-32 table) are credited in `VENDOR.md`.
- **Map data and boundaries.** Natural Earth is public domain, and it draws *de facto* boundaries: disputed areas appear as it draws them. That is a reputational point, not a licence one. A map spec can say `disclaimer: true` (or give its own words) to show a one-line statement beside the map ("Boundaries are Natural Earth's (de facto); disputed areas are drawn as it draws them, and no position is taken."). The world-regions grouping is derived from Natural Earth's own fields, not from the UN's M49 list, whose reuse terms are not plainly public domain.
