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
# Runbook: source down or slow

**Symptoms.**
- Views fail with `DRS-1003` (source failed) or `DRS-1004` (timed out).
- Linked entities show *pending*.
- `/api/v1/sources` reports a plugin that is not `UP`.

**Diagnosis.**
1. Run `curl -s :18480/api/v1/sources | jq`. Check each plugin's `health`, and the `failures` list
   (plugins that never started).
2. Search the server log for `source plugin ... failed to start` or `failed reading`.
3. Try the source directly (for REST, call the `base-url`; for JDBC, run the query in a SQL client).

**Steps.**
- If the source is slow but healthy, raise `drishti.sources.fetch-timeout` (default 2 s) and, for
  links, `drishti.graph.link-budget` (default 40 ms).
- If the source is down, other sources keep serving. Views of its kinds fail with a clear code, and
  links to them show *pending*/*missing*.
- If a plugin failed to start, fix its settings and restart the server. The other plugins are unaffected.

**Verification.** `/api/v1/sources` shows `UP`, and the view loads with no pending links.
