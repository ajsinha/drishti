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
# Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Build fails with "Drishti builds and runs on OpenJDK 21" | wrong JDK | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` |
| Console shows "backend unreachable" (`DRS-5003`) | server not running, or wrong `backend.url` | start the server; check `DRISHTI_BACKEND_URL` |
| A view says `DRS-1001` | no source holds that id | check the id; type part of it to see suggestions |
| A panel shows an error but the rest of the view renders | a binding failed (bad path, a linked entity missing) | the message says which; fix the Sutra in Studio |
| A curve says "waiting for …" | its source entity missed the link budget | see runbooks/source-down.md |
| Suggestions don't appear | a source without `search`, or a slow one (30 ms budget) | declare `search` in the plugin, and index in memory with `HitIndex` |
| View does not tick | the entity or its source is not live | `/api/v1/sources` shows `live`; the demo ticks only live fixtures |
| "Reconnecting…" in the top bar | the stream dropped | the browser reconnects by itself and repaints from a fresh view |
| Charts are blank | ECharts not loaded (CSP or path) | check `/static/vendor/echarts/echarts.common.min.js` loads |
| Studio Save is disabled | saving is off, or you are not an author | `DRISHTI_STUDIO_SAVE=true` and an `author` role |
| `LicenseHeaderTest` fails | a new file has no header | `python3 tools/license_headers.py --fix` |
