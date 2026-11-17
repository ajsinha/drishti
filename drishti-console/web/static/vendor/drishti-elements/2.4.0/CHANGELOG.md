<!--
  Drishti Elements

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY.

  This file is the proprietary property of Ashutosh Sinha.
  Copying, use, modification or distribution of this file, via any medium,
  is not permitted except with the express prior written permission of the
  copyright holder.

  See the LICENSE file in the root of this repository.
-->
# Changelog

Each release lists what changed, by library. One version for every library: the `elements` key of `elements/drishti_elements.json`.

## 2.4.0

The first version of Drishti Elements as its own project: the libraries, the gallery, the build and the tests in one repository, one release
tree built from a tag (`python3 tools/elements/cli.py dist`).

- **drishti-core 2.4.0**: formats and the themes' tokens, the API client with its error contract, events, accessibility helpers, resize, keymap, dialogs, drawers and toasts, the state kit, the command palette and the dock, the filter model every library shares, the loader. New: `configure({ endpoints })` and `endpoint(name)` name the addresses of the host's server (live, changes, pivotPage, capabilities); `register(name, options)` adds a library that lives outside the manifest to the loader; `ApiClient` takes `asOfHeader`. The loader needs no built-in folder: it uses the folder the core bundle was loaded from, or `configure({ base })`.
- **drishti-inputs 2.4.0**: date and range pickers with holiday calendars, the multi-select, the combobox, forms from a schema with four eyes, the filter bar, the code editor over CodeMirror.
- **drishti-grid 2.4.0**: the grid and its pivot in three layouts and three densities, cell editing, the live-set mode, export, the AG Grid-style adapter. New: the live-set client and the pivot link read their addresses from the configured endpoints (`base`, `changesUrl` and `pivotPage`); without one the feature says so in words.
- **drishti-chart 2.4.0**: one engine-neutral spec over ECharts, the general kinds, the maps from the world to every country's first-level regions, Explain on any mark, export, linked selection and live updates. New: the maps folder is required when the core bundle gives no folder.
- **drishti-data 2.4.0**: one data-source contract (memory, REST pages, server-side rows, Arrow, live), the live client over SSE and WebSocket, the Arrow client. New: `capabilitiesUrl` defaults to the configured endpoint `capabilities`; with none the client uses Arrow or JSON over HTTP only.
- **drishti-tree 2.4.0**: hierarchies as an indented tree grid, an org chart, a sunburst and an icicle, with measures, search, an edit mode that proposes moves, and the outline of an authoring screen.
- **drishti-graph 2.4.0**: networks on a canvas with force, layered, radial, circular and grouped layouts, paths, groups, selection and an adjacency table.
- **drishti-designer 2.4.0**: the designer kit: the layout canvas, palette, inspector, outline, YAML pane, undo and redo, and the designer shell.

Also in 2.4.0:

- **drishti-chart**: heatmap tracks, category cells and the overview strip; a scatter's segments, top labels and surface; the
  rectangle brush; frames with a slider and Play; box plots from given figures; the kinds `segmented`, `matrix`, `lollipop` and `trace3d`
  (on the vendored ECharts GL, loaded the first time, drawn flat or as its table without WebGL). Each has a gallery page and Node tests.
- **drishti-core**: `Tour`, a page walked in steps (marks for what each step says and shows, back, at and next controls).
- **drishti-grid**: Escape closes Explain whatever came before it (a pending focus follow never reopens a closed Explain).
- **drishti-inputs**: the query builder's choices on drishti-inputs' Select; the selection chip's history as buttons.
- **Build**: a host's own libraries (`build.py --root --manifest`) require this project's bundles under Node by their path from the
  library's folder, and a host's manifest may leave the version out (this project's is used).
- **Vendor**: ECharts GL 2.1.0 (BSD-3-Clause, one local change without eval, `vendor/echarts-gl/PATCHED.md`).
- **drishti-grid**: the grid's style sheet restored in full (a change to it had kept only its last block, so the grid, its pivot wells and
  its side panel were drawn without their look); a guard test now fails when a library's class names lose their rules.
- **drishti-core**: the loader adds a library's style sheets even when its bundle is on the page already (drishti-core by its script
  tag: the resize and chips sheets), and knows a sheet the page links by a relative address as the same sheet.
- **drishti-chart**: the map shapes are not asked for from a page opened from the file system (the browser refuses it); the map says so.
- **Gallery**: the States entry draws again (its compact switch); the documentation's Playground page works from the file system too.
- **Brand**: a light logo for dark backgrounds (`brand/drishti_elements-logo-light.svg`), used by the README in dark mode.
