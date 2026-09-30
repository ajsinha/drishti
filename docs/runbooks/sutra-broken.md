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
# Runbook: a Sutra is broken

**Symptoms.**
- `/api/v1/sutras/problems` is not empty.
- A view still shows the old layout after an edit.
- The server log says `sutra problem ... DRS-2xxx`.

**What Drishti does.** An invalid edit never takes a view down. The last good version of that file
stays live, and the problems are reported with file, line and column.

**Steps.**
1. Run `curl -s :18480/api/v1/sutras/problems | jq` to see each problem's code, message and location.
2. Open the Sutra in Studio (`/studio`) and press Ctrl+Enter. Problems are listed by line; click one to jump to it.
3. Fix the file and save it, in Studio (if saving is enabled) or in the repository. Hot reload applies it within 250 ms.

**Verification.** `problems` is `{}`, and *How this view was built* shows the new version.
