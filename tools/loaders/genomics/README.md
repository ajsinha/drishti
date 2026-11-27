<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# genomics: planned (built next, nothing to run yet)

This folder is a documented placeholder. It holds no code and no commands today, so there is nothing to load from it. When it is built it
will follow the same rules as its neighbours, so the daily routine will look the same: [external/README.md](../external/README.md).

## Planned scope

1. **Public genomics sources as feeds.** Each source is a feed, exactly like the feeds in `external/`: a file `config/feeds/<source>.yaml`
   (provider, licence, schedule, datasets, options), a source class in this folder, a generated page `config/feeds/<source>.md`, run with
   `drishti.py feed run <source>`, scheduled with `drishti.py feed schedule`, incremental by business date, replacing only the dates it
   pulls, with the licence and any internal-only flag in every manifest and ledger line. The engine is the shared one in
   [common/](../common/README.md); no second pull-and-load machinery is added.
2. **Loading your own files as provided.** Files you already hold (variant calls, annotations, sample sheets and similar) are loaded as
   they are, without conversion by hand: the loader reads the file as delivered, checks it against the kind's contract, writes dated JSONL
   and hands it to the data inbox, the way `mrloader/` does for a bank's files (a declared mapping per kind, `--dry-run` first,
   rejects with reasons, `--inbox` or `--load`, a day that fails leaves the lake as it was).

## What this means for you today

- Your own JSON lines, now: the data inbox needs no loader (`drishti.py dataload ready`, `drishti.py data ingest`;
  [DATA_GUIDE.md](../../../docs/guides/DATA_GUIDE.md)).
- Genomics content in the product today (the genomics SDK build and its tests) is separate from this folder:
  `tools/test_sdk_builds_genomics.py`.

When this folder gains its first feed or loader, this README gets the full set of sections the others have: what it loads, prerequisites,
the daily routine for Windows and Linux, naming and date rules, scheduling, checks, errors, licences.
