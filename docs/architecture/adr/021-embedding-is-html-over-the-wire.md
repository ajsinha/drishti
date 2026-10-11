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
# ADR-021: Embedding is HTML over the wire for views, and the viewer's own code for reports

| Status | Date | Decider |
|---|---|---|
| Accepted (built: `<drishti-view>`, `<drishti-panel>`, `<drishti-report>`) | 2026-10-11 | Ashutosh Sinha |

## Context
Host applications (a CRM, an intranet, a risk portal) want Drishti's live views and BI reports inside their own pages, signed in as their own users, themed by their
own tokens, without an iframe (an iframe cannot share a layout, a theme, focus or one network connection, and a strict host policy usually forbids it). Two things
had to be decided: **where the rendering happens**, and **how the code reaches the host**.

## Decision
1. **A view is rendered by the console and sent as HTML**; the element is a thin client. The console already turns a ViewModel into a screen with the panel macros
   (tables, trees, pivots, strips, About, masks, number formats, the *No data* rules). The embed API (`/embed/v1/views/...`) returns those fragments and live patches,
   and the element swaps them into a Shadow DOM after a sanitiser has rebuilt them from an allow-list. A second renderer in JavaScript would be a second
   implementation of every panel kind and every accessibility detail, for ever. We accept larger payloads (3 to 6 times before compression) and that a host
   cannot restyle a panel's inner structure beyond the custom properties and parts we expose.
2. **A report is drawn in the browser by the console viewer's own code**, because a report is not server-rendered HTML: it is a model plus one query per visual, drawn by
   `bi-visuals.js` and friends. `<drishti-report>` loads that same code (served with CORS under `/embed/v1/js/`), builds its DOM with `textContent` only, and asks the
   embed API for the model and the rows. There is one renderer of a report, shared by the viewer, the element and the designer's preview.
3. **The embed token is the identity, on every call.** The host's backend exchanges its credentials and its user's identity for a short-lived, read-only token
   (RFC 8693) at the Drishti server; the server verifies it on every call, applies the user's row rules and **always masks**. Which hosts may show which kinds and
   which reports is the host application's `kinds` list (`report:<id>`, `report:*`).
4. **The console serves the library; the npm package is the same bytes.** An exact semantic version is immutable with an `integrity.json` of SHA-384 hashes, a partial
   version redirects, and a committed record fails the build when a module changed under an unchanged version. Sheets, fonts and chart code come from the console that
   serves the data, so the library and the data always agree. The element API (attributes, properties, methods, events) and the embed API (`/embed/v1`) are two
   semantic-version contracts; a major of the embed API is announced with `Deprecation` and `Sunset` and then `410 DRS-8006`.

## Consequences
- Every fix to a panel or a visual reaches embedded views and reports with the console, with no second change.
- A report in an element cannot do what needs WebAssembly (Perspective) under a strict host policy, nor the viewer's navigation to other console pages: those are
  listed, not faked ([ELEMENTS.md section 18](../ELEMENTS.md#185-not-built)).
- The console is a dependency of the host page (it must be reachable from the browser and named in the host's policy); the element has no offline mode.
- Considered and rejected: an iframe (the reasons above); a JavaScript renderer of ViewModels (a second renderer); a library bundled with every host (it would not match
  the console's data); embedding by the host's server fetching HTML (no live updates, no per-user token on every call).

Design and as-built notes: [ELEMENTS.md](../ELEMENTS.md). Developer guide: [guides/ELEMENTS.md](../../guides/ELEMENTS.md).
