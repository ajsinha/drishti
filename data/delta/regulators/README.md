<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The regulators lake

This folder is kept in git with only this file. It is the place for a lake of regulators' and central banks' data: the SEC, the Federal Reserve, OSFI and other central banks and supervisors (`lake: regulators`). Loads into it (the data inbox with `lake: regulators` in a `.ready` file, `drishti.py lake build --lake regulators`, or the parallel loader) fill it, one folder per domain and table. What is written here is never committed. Today the shipped official data (SEC EDGAR filings in the US equities pack, official market data) sits in the demo lake; its connectors read the lake their files name. Its lake file, `config/lakes/regulators.yaml`, is created by the first load and stays local. See [DATA_LAKE_GUIDE.md](../../../docs/guides/DATA_LAKE_GUIDE.md).
