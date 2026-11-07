<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The capitalmarket lake

This folder is kept in git with only this file. It is the place for a lake of capital-markets data: prices, curves, volatility surfaces, indices, funds and other market data (`lake: capitalmarket`). Loads into it (the data inbox with `lake: capitalmarket` in a `.ready` file, `drishti.py lake build --lake capitalmarket`, or the parallel loader) fill it, one folder per domain and table. What is written here is never committed. Today the shipped market data (`data/delta/demo/market/`, `funds/`, `us-equities/`) sits in the demo lake; its connectors read the lake their files name. Its lake file, `config/lakes/capitalmarket.yaml`, is created by the first load and stays local. See [DATA_LAKE_GUIDE.md](../../../docs/guides/DATA_LAKE_GUIDE.md).
