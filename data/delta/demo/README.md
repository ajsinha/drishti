<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The demo lake

This folder is kept in git with only this file. `drishti.py lake demo` (or `tools/build_demo_lakes.py`) fills it from the committed test data in `data/testdata/` (the `.jsonl.gz` files), one folder per domain and table (`trading/trade_endur/`, `risk/scenario-pv/`, ...). What it writes is never committed. Its lake file is `config/lakes/demo.yaml`; see [DATA_LAKE_GUIDE.md](../../../docs/guides/DATA_LAKE_GUIDE.md).
