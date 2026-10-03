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
# Operations status

The back office on one screen: where a trade stands and what comes next.

**Panel kinds:** `status`, `timeline`, `ladder`, `markdown`, `provenance`.

**Open it:** Studio **File → Open** with `operations-status.sutra.yaml` and `operations-status.json`, or `/studio?example=operations-status`.

**What to look for:** coloured status lights (`tone: status`); the timeline in date order with the pending event in amber; the coupon ladder with the next payment highlighted and a total; the note and provenance in the right-hand column.

**Lines to copy:** `highlight: "#index == $.nextIndex"` on the ladder, `label` and `detail` on the timeline, `area: right` to put a panel in the side column.
