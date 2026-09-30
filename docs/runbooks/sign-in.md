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
# Runbook: users cannot sign in

**Symptoms.**
- The login page says "Unknown user or wrong password".
- API calls from the console return `DRS-5010`.

**Steps.**
1. **Wrong password:** regenerate the hash with `python console/tools/hash_password.py` and update
   `console/config/users.yaml`.
2. **All users get `DRS-5010` after signing in:** `DRISHTI_TOKEN_SECRET` differs between console and
   server, or clocks are more than 30 s apart. Align the secret, and check NTP.
3. **A user sees "no access" on links:** this is expected. Their role lacks that kind. Adjust
   `drishti.security.roles` if it should not be.

**Verification.** The user signs in and opens `TRD IRS-48213 <GO>`.
