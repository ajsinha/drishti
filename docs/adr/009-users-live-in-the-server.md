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
# ADR-009: Users live in the server

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Wave 10 signed people in against a users file read by the console. Full user management (create,
edit, disable, reset, audit, lockout) needs one shared, writable source of truth, even when there are
several consoles.

## Decision
Users live in the server, in the `drishti-identity` module. The store is behind the `UserStore`
interface; the default `FileUserStore` writes JSON atomically with owner-only permissions. Hashes are
PBKDF2-HMAC-SHA256.

The console verifies a sign-in through `POST /api/v1/auth/login` using a service token, and only
receives the profile. Admin operations go through `/api/v1/admin/**`, which requires the `admin` role.
Every event is audited.

On an empty store, a development admin (`drishti-dev-admin`) is seeded, and the console warns until its
password changes. Forced password changes are off unless configured.

## Consequences
The console's users file and hashing tool are retired. A database-backed `UserStore` can replace the
file without changing the API, and OIDC can later map external identities onto these users and roles.
