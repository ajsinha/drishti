<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# common: the feed engine (for developers)

The one engine behind every pull-from-the-internet loader: [external/](../external/README.md) and [news/](../news/README.md) (and
[genomics/](../genomics/README.md) when built). If you only run daily loads, you need those READMEs, not this one. There is **one** way to
pull, write, load, schedule and monitor a source; a feed adds a source class and a YAML file and nothing else. Depth:
[WORLD_DATA_GUIDE.md](../../../docs/guides/WORLD_DATA_GUIDE.md) sections 19.7 to 19.10.

## Files

| File | Role |
|---|---|
| `cli.py` | the `drishti.py feed list|run|status|schedule` command group (loaded by `tools/drishti_parser.py`) |
| `feedbase.py` | the feed file (`FeedSpec`), `Feed` base class, `FeedRun` (window, work folder, options, keys), the run lock, `feed_class` (finds `<module>.<Class>`) |
| `feedrunner.py` | one run: lock, pull, check, replace the date folders, state, manifest, ledger line, exit code |
| `feedload.py` | the three load routes (java, python, inbox) and `.ready` / tar writing |
| `feedschedule.py` | `--cron`, `--systemd`, `--windows` output |
| `feedreadme.py` | writes (`--check`: checks) `config/feeds/<feed>.md` from each feed file |
| `pullkit.py` | the polite HTTP client (User-Agent, pause, retries, `Retry-After`), the download cache, the manifest, the shared source parser for folded sources |
| `packscript.py` | `PackFeed`: a feed whose pull is one of the folded pack sources (`gleif.py`, `us_equities.py`, ...) |
| `publicsector.py` | helpers of the public-sector feeds: `History`, `provenance`, `tolerate`, kept-file reading and writing |
| `tests/` | every feed against recorded responses in `tests/fixtures/` (never the network) |

## Where a feed's pieces live

| Piece | Place |
|---|---|
| the declaration (source, licence, schedule, lake, datasets, options, keys) | `config/feeds/<feed>.yaml` |
| the generated page | `config/feeds/<feed>.md` (`PY tools/loaders/common/feedreadme.py`) |
| the source class `class: <module>.<Class>` | `tools/loaders/external/<module>.py` or `tools/loaders/news/<module>.py`; else beside the feed file (a site's own) |
| the state, cache, lock, files, ledger | `<data>/datafeeds/<feed>/...`, `<data>/datafeeds/ledger.jsonl` |

`feed_class` looks in `external/`, then `news/`, then the feed file's own folder, and imports the module by file path, so a site's feed
needs no change to the product.

## Add a feed

1. `config/feeds/<feed>.yaml` (copy a small one such as `gdelt-events.yaml`): `feed`, `title`, `class`, `source`, `lake`, `load`,
   `schedule`, `licence` (name, url, redistribution, `internal-only`, attribution), `datasets`, `options`, `keys` (environment variable
   names only).
2. A class deriving `Feed` in `external/` (reference, statistics, market data), `news/` (news and events) or beside the feed file:
   `plan(run)` for `--dry-run`, `pull(run)` writing dated JSON lines through the run's helpers (`run.window()` gives the dates to ask for).
   Write JSONL only, UPPER_SNAKE attribute names where the pack's contract says so, the licence in the manifest.
3. `PY tools/loaders/common/feedreadme.py` to write the page; `PY tools/drishti.py docs generate --check`.
4. Tests in `tests/` with recorded fixtures, no network. Add the feed to the table of the family README (a test checks that every feed
   is named in `external/README.md` or `news/README.md`).

## Checks

```text
PY -m pytest tools/loaders/common/tests -q                       the feeds (needs lib/drishti-server-<version>-exec.jar)
PY tools/loaders/common/feedreadme.py --check                    the generated pages are current
PY tools/drishti.py feed list                                    every feed file parses
PY tools/drishti.py feed run <feed> --dry-run                    the plan, no change
```

Run order of a feed run, for reading the code: lock, window (state), pull into a work folder, the feed's `check`, replace the date
folders, load by route, state, manifest, ledger line, exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial).
