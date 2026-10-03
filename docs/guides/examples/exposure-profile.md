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
# Exposure profile

Expected and potential future exposure over tenor, against a limit.

**Panel kinds:** `area`.

**Open it:** Studio **File → Open** with `exposure-profile.sutra.yaml` and `exposure-profile.json`, or `/studio?example=exposure-profile`.

**What to look for:** two filled series (EE and PFE 95) rising then falling over the tenors, and the limit as a line across them.

**Lines to copy:** `series:` (one entry per measure, each with `label`, `value` and `tone`) and `limit: $.limit`.
