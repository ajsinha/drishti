<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The worldlake lake

This folder is kept in git with only this file. It is the place for a lake of world and public-sector data: the World Bank, the IMF, the United Nations and other official sources (`lake: worldlake`). Loads into it (the data inbox with `lake: worldlake` in a `.ready` file, `drishti.py lake build --lake worldlake`, or the parallel loader) fill it, one folder per domain and table. What is written here is never committed (IMF data stays internal only). The public-sector module's packs read it: their daily feeds (world-development, central-banks, national-statistics, oecd) fill `development/`, `centralbank/`, `statistics/` and `oecd/` ([PUBLIC_SECTOR_GUIDE.md](../../../docs/guides/PUBLIC_SECTOR_GUIDE.md)); the world-economy pack's data still sits in the demo lake (`data/delta/demo/macro/`, `civic/`). Its lake file, `config/lakes/worldlake.yaml`, ships with those tables' entries. See [DATA_LAKE_GUIDE.md](../../../docs/guides/DATA_LAKE_GUIDE.md).
