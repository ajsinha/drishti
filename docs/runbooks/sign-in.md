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
- The login page says "Unknown user or wrong password" or "locked".
- API calls from the console return `DRS-5010`.

**Steps.**
1. **Wrong password or locked:** an admin opens `/admin/users` and uses *Reset password* on the user,
   which also clears a lockout. The audit log (`/admin/audit`, filter by user) shows the failures and
   the lockout.
2. **Disabled:** the admin uses *Enable*.
3. **All sign-ins fail with "Sign-in unavailable":** the console cannot reach the server, or
   `DRISHTI_TOKEN_SECRET` differs between them (the service token is refused). Align the secret, and
   check NTP (tokens tolerate 30 s of skew).
4. **No admin can sign in:** stop the server, move `data/identity/users.json` aside, and start it with
   `DRISHTI_SEED_ADMIN=true`. The development admin is recreated. Then restore users through the admin
   page, and change the password.
5. **A user sees "no access" on links:** this is expected. Their roles lack that kind. Edit their
   roles in `/admin/users`.

**Verification.** The user signs in and opens `TRD IRS-48213 <GO>`.
