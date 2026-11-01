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
| **REST (OpenAPI)** | every endpoint of the server, with its parameters and answers; try a call in the browser with a token | the [API guide](/help/api) |
| **Renderer (TypeScript)** | `drishti-render.d.ts`: the client renderer's public API; Drishti Elements' own types ship in its npm package | [Client renderer and Elements](/help/client-renderer) |
| **Java: plugin and connector SPI** (`drishti-api`) | `SourcePlugin` and its context, manifest and capabilities; entity documents, references and provenance; dated reads, scans and history; the compute engine SPI; the shared TLS, proxy and pool modules; write-back commands | the [connector developer guide](/help/connector-developer-guide) |
| **Java: shard machinery** (`drishti-compute`) | the shard map and its cut, rendezvous ownership, handover, the coordinator's scatter-gather and the peers' fan-out | [Cluster deployment](/help/cluster-deployment) |
| **Java: sharded measures** (`drishti-server`) | `ShardedMeasure`, what a measure brings to the sharding machinery, and the members' view of it | [Cluster deployment](/help/cluster-deployment) |
| **Python: compute worker SDK** (`drishti_compute`) | `Engine`, `Request`, `Inputs`, `Progress`, `Result` and the Arrow tables an engine reads and writes | the [pack developer guide](/help/pack-developer-guide) |
| **Python: BI Python items** (`core.python_items`) | the function each interface calls: a Transform's `run`, a measure's `compute` and `compute_groups`, a column's `derive`, a visual's `render` | the [BI guide](/help/bi-guide) |
| **Python: `drishti.py` and its client library** | the command line's reusable parts (the server API client, the Java launcher, the parser) and `drishti_client` for scripts and notebooks | the [command line guide](/help/cli-guide) |
| **Python: pack generator helpers** | README and data-contract sections, samples, Sutra cases, UPPER_SNAKE names, the live-demo walks | the [pack developer guide](/help/pack-developer-guide) |
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
