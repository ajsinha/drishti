<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The newslake lake

This folder is kept in git with only this file. It is the place for a lake of news and headlines (`lake: newslake`): loads into it (the data inbox with `lake: newslake` in a `.ready` file, `drishti.py lake build --lake newslake`, or the parallel loader) fill it, one folder per domain and table. What is written here is never committed. The economy module's public-sector free news packs read it: their daily feeds (official-news, gdelt-events) fill `officialnews/` and `gdelt/` ([PUBLIC_SECTOR_GUIDE.md](../../../docs/guides/PUBLIC_SECTOR_GUIDE.md)); the news-and-markets pack's snapshot still sits in the demo lake (`data/delta/demo/news/`). Its lake file, `config/lakes/newslake.yaml`, ships with those tables' entries. See [DATA_LAKE_GUIDE.md](../../../docs/guides/DATA_LAKE_GUIDE.md).
