<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The referencelake lake

This folder is kept in git with only this file. It is the place for a lake of core reference data: legal entities and their LEIs, their ownership (direct and ultimate parents) and the identifiers mapped to them (ISIN, BIC), from GLEIF (`lake: referencelake`). The GLEIF data feed loads it every day (`drishti.py feed run gleif`, [EXTERNAL_DATA.md](../../../docs/guides/EXTERNAL_DATA.md)); the data inbox with `lake: referencelake` in a `.ready` file, `drishti.py lake build --lake referencelake` and the parallel loader fill it too, one folder per domain and table. What is written here is never committed. Today the shipped GLEIF data (`data/delta/demo/gleif/`) sits in the demo lake; its connectors read the lake their files name. Its lake file is `config/lakes/referencelake.yaml`; a load into a table it does not describe yet copies the pack's recommended entry into it first. See [DATA_LAKE_GUIDE.md](../../../docs/guides/DATA_LAKE_GUIDE.md).
