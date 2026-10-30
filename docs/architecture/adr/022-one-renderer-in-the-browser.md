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
# ADR-022: One renderer, in the browser (supersedes ADR-021's rendering decision)

| Status | Date | Decider |
|---|---|---|
| Accepted (phase 1 built: the library and its parity tests; phase 2, the console drawn by it, next) | 2026-10-30 | Ashutosh Sinha |

## Context
[ADR-021](021-embedding-is-html-over-the-wire.md) chose to render a view on the console and send HTML to `<drishti-view>`, because a
JavaScript renderer would be "a second implementation of every panel kind and every accessibility detail, for ever". Since then the
ViewModel has become a published contract with a JSON Schema ([VIEWMODEL.md](../VIEWMODEL.md)), hosts want Drishti screens without
the console's HTML (their own pages, phones, frameworks), GenUI needs a screen that arrives panel by panel, and the product owner made
a client-side renderer the first item of 2.9.7. The reason ADR-021 gave stands: two renderers would drift. Its conclusion does not
follow from it: one renderer can live in the browser.

## Decision
1. **Views are rendered in the browser from the ViewModel by one library, Drishti Render** (`static/render/<version>/drishti-render.js`):
   plain ES modules, no build step, vendored dependencies only, nothing from a CDN. It renders every panel kind, state, chart, layout and
   behaviour the console's macros do.
2. **There is only ever one renderer.** Phase 1 builds the library and proves parity with the macros, ViewModel by ViewModel; phase 2
   moves the console's pages, `<drishti-view>` and `<drishti-panel>` onto it behind a setting, and then deletes the panel macros and the
   HTML path. Until a page moves, it keeps the server path unchanged.
3. **The sanitiser's rules are kept, applied while building**: no markup is ever parsed from data; elements, attributes and links pass
   the same allow-lists ADR-021's sanitiser used; no `eval`; the console's CSP unchanged; one Trusted Types policy (`drishti-render`), for
   script URLs only.
4. **Embeddable in place**: navigation, drill-downs and sheets stay in the host's container by default, with a back stack and a
   cancelable `drishti:navigate` event; nothing opens a window unless the host asks.
5. **Built for streaming**: the same `render()` draws a static, a live and a streamed ViewModel (skeleton, panel-add, panel-replace,
   patch, done, error), which is how GenUI's ephemeral screens will arrive.
6. ADR-021's decisions 2 (reports drawn by the viewer's code), 3 (the embed token on every call) and 4 (the console serves the library,
   immutable versions with SRI) are unchanged and apply to the renderer.

## Consequences
- The payload is the ViewModel, smaller than the HTML; hosts restyle through the same custom properties; screens answer to their
  container's width.
- Parity is a test, not a promise: `tests/test_render_parity_browser.py` draws every pack Sutra on its sample both ways and compares the
  accessible tree, text, cells, links, tones and chart series.
- During phase 2 both paths exist; the setting and the parity suite keep them equal until the macros are deleted.
- First paint needs the library and the ViewModel in the browser; the console will embed the ViewModel in its page.
- Considered and rejected: keeping HTML over the wire (no GenUI streaming of JSON, no host-side frameworks, payloads 3 to 6 times larger);
  a renderer bundled with a build toolchain (ADR-006: vendored, no build pipeline); a renderer per host framework (many renderers).

Design: [CLIENT_RENDERER.md](../CLIENT_RENDERER.md). Developer guide: [guides/ELEMENTS.md](../../guides/ELEMENTS.md#client-side-renderer-preview).
