<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. PROPRIETARY AND CONFIDENTIAL.
  See the LICENSE file in the root of this repository for the full terms.
-->
# Recorded feed responses

`recorded/` holds public responses captured on 2026-09-30 so the feed tests run without the network. They are the
publishers' data, not Drishti's, and carry no Drishti header:

| File | Source |
|---|---|
| `nyfed-sofr.json` | Federal Reserve Bank of New York, Markets API, SOFR (last 20) |
| `ecb-estr.csv` | European Central Bank Data Portal, €STR (last 20 observations) |
| `ecb-fx-90d.xml` | European Central Bank, euro foreign exchange reference rates, last 90 days |
| `us-treasury-202609.xml` | U.S. Department of the Treasury, Daily Treasury Par Yield Curve Rates, September 2026 |
| `fred-dgs10.json` | Written by hand in the FRED API format (FRED needs an API key) |
