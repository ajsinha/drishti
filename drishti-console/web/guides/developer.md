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
# Developer: API references

Every programming surface of Drishti in one place: the server's REST API, the renderer's TypeScript API, and the Java and Python API
references, generated from the code itself. The table above links each one; this page says what each covers and how the references are
made.

## What each reference covers

| Reference | Covers | Read it with |
|---|---|---|
| **REST (OpenAPI)** | every endpoint of the server, with its parameters and answers; try a call in the browser with a token | the [API reference](/help/api) and the [developer guide](/help/developer-guide#11-the-api-calling-the-server-from-scripts-notebooks-and-excel) |
| **Renderer (TypeScript)** | `drishti-render.d.ts`: the client renderer's public API; Drishti Elements' own types ship in its npm package | [Embed guide](/help/embed-guide) |
| **Java: plugin and connector SPI** (`drishti-api`) | `SourcePlugin` and its context, manifest and capabilities; entity documents, references and provenance; dated reads, scans and history; the compute engine SPI; the shared TLS, proxy and pool modules; write-back commands | the [connector developer guide](/help/developer-guide#10-connectors-choosing-combining-and-writing-one) |
| **Java: shard machinery** (`drishti-compute`) | the shard map and its cut, rendezvous ownership, handover, the coordinator's scatter-gather and the peers' fan-out | [Cluster deployment](/help/cluster-deployment) |
| **Java: sharded measures** (`drishti-server`) | `ShardedMeasure`, what a measure brings to the sharding machinery, and the members' view of it | [Cluster deployment](/help/cluster-deployment) |
| **Python: compute worker SDK** (`drishti_compute`) | `Engine`, `Request`, `Inputs`, `Progress`, `Result` and the Arrow tables an engine reads and writes | the [Notebook and Python guide](/help/bi-guide#part-4--the-notebook-and-python) |
| **Python: BI Python items** (`core.python_items`) | the function each interface calls: a Transform's `run`, a measure's `compute` and `compute_groups`, a column's `derive`, a visual's `render` | the [Notebook and Python guide](/help/bi-guide#part-4--the-notebook-and-python) |
| **Python: `drishti.py` and its client library** | the command line's reusable parts (the server API client, the Java launcher, the parser) and `drishti_client` for scripts and notebooks | the [command line guide](/help/cli-guide) |
| **Python: pack generator helpers** | README and data-contract sections, samples, Sutra cases, UPPER_SNAKE names, the live-demo walks | the [build guide](/help/build-guide) |
| **Python: test-data generator helpers** (`samplegen`) | business calendars and tenors, deterministic ids, curves, swap legs, trade blocks and the lake layout | [Tools](/help/tools) |

## How the references are made

The Java and Python references are **generated at build time and never kept in the repository**. One command builds both:

```
python3 tools/api_docs.py build --maven        # Javadoc through Maven's api-docs profile, then pdoc
python3 tools/api_docs.py zip                  # drishti-api-reference-<version>.zip, attached to the GitHub release
```

They are written to the console's `help.api_docs_dir` (default `drishti-console/web/apidocs`), one folder per version, and this page
links the newest; `/help/reference/latest/...` always opens the newest built version, so the developer guides link straight to a class
or function. The pages are self-contained: their scripts, styles and search index are files beside them, nothing is loaded from the
internet. A reader without a running console opens the release's zip instead.

What is documented is listed in `tools/api_docs.yaml`: the Java modules (each names its packages in its `pom.xml`) and the Python
surfaces (their modules). A module's `__all__`, when it has one, says what of it is public.

## The checks every build makes

* **Java**: Javadoc runs with doclint (`-Xdoclint:all,-missing`, warnings fail), so a broken `{@link}`, bad HTML or a malformed tag
  fails the build; `tools/api_docs.py check-java` then fails on any public or protected type, member or package without a comment.
* **Python**: `tools/api_docs.py check-python` reads each surface without importing it and fails on a public function, class or method
  without a docstring or a type hint on each parameter and the return.

The drill runs both: its Java 21 build uses the `api-docs` profile, and its tool tests run `tools/api_docs.py check`.

## Documenting what you add

* **Java**: a sentence or two of prose on every public type and member (what it is, what it returns, when it throws), linking the
  types it names with `{@link}`. A method that overrides a documented one inherits its comment. `@param` and `@return` are used where
  they add something.
* **Python**: a docstring on every public function, class and method, and type hints on every parameter and return. A helper that is
  not part of the surface starts with `_`, or the module lists its public names in `__all__`.
* A new surface (a module, a package) is added to `tools/api_docs.yaml` and, for Java, to the module's `drishti.apidocs.packages`.

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
