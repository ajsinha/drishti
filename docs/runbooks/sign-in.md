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

How sign-in works, in one paragraph: the user types a name and password on the console's `/login` page. The
console asks the server to check them (`POST /api/v1/auth/login`), presenting its own short-lived service token
signed with `DRISHTI_TOKEN_SECRET`. The server checks the password, counts failures and locks the account after
5 of them (for 15 minutes). On success the console sets a signed session cookie (`drishti_session`, signed with
`DRISHTI_SESSION_SECRET`) and, for every later call, sends the server a fresh token for that user. With single
sign-on (OIDC), the provider replaces the password step. Users, roles and the audit log are described in
[USER_MANAGEMENT.md](../USER_MANAGEMENT.md).

The examples use `http://localhost:18480` for the server and `http://localhost:17480` for the console.
Admin calls to the server need an admin's token when security is on (see [API_GUIDE.md](../API_GUIDE.md)); the
console's admin pages do the same for you.

## Symptoms

| What the user sees | Most likely cause | Go to |
|---|---|---|
| "Unknown user or wrong password." for one user | Wrong name or password, or the account is disabled. | Step 1 |
| "This account is locked after repeated failures. Try again later or ask an administrator." | 5 failed attempts. | Fix A |
| "Unknown user or wrong password." for **everyone**, correct passwords included | Console and server disagree on `DRISHTI_TOKEN_SECRET`, or the console has none. | Step 3 |
| "Sign-in unavailable: backend unreachable: …" | The console cannot reach the server. | Step 4 |
| Signed in, but pages fail with `DRS-5010` | Tokens refused: secrets differ, or the clocks differ by more than 30 s. | Step 3 |
| Signed in, but an entity or a link says "no access" / `DRS-5002` | The user's roles do not include that kind, or its pack is not among theirs, or is switched off for everyone. | Fix D |
| "Single sign-on is unavailable" or "The sign-in provider said: …" | OIDC configuration or provider. | Step 5 |
| Nobody with the admin role can sign in | All admins locked out or passwords lost. | Fix E |

## Diagnosis

### Step 1. Look the user up

In the console open **Admin → Users** (`/admin/users`). The user's status reads `active`, `locked` or
`disabled`. Or ask the server:

```bash
curl -s http://localhost:18480/api/v1/admin/users | python3 -c '
import json,sys
for u in json.load(sys.stdin): print(u["username"], "enabled" if u["enabled"] else "DISABLED", "LOCKED" if u["locked"] else "", u["roles"], u["lastLoginAt"])'
```

```text
drishti-dev-admin enabled  ['admin'] None
```

- The user is not listed: the name is wrong, or the user was never created. Create the user (Fix C).
- `DISABLED`: Fix B. A disabled user who types the right password still sees "Unknown user or wrong
  password." on the login page; the audit log (step 2) shows the real reason.
- `LOCKED`: Fix A.

### Step 2. Read the audit log

**Admin → Audit** (`/admin/audit`) lists every sign-in attempt; filter by the user. Or:

```bash
curl -s "http://localhost:18480/api/v1/admin/audit?subject=jdoe&limit=10"
```

| `action` | `detail` | Meaning |
|---|---|---|
| `login-failed` | `unknown user` | No such user name. |
| `login-failed` | `attempt 3` | Wrong password, third failure in a row. |
| `locked` | `attempt 5` | The fifth failure locked the account. |
| `login-refused` | `locked until …` | A try while locked; it does not count as a failure. |
| `login-refused` | `disabled` | Right password, but the account is disabled. |
| `login` | | A successful sign-in. |
| `password-reset` | | An admin reset the password. |

No audit entries at all for a user who says they tried: their attempts never reached the server. Go to step 3.

### Step 3. Check the shared token secret and the clocks

With `drishti.security.enabled: true`, the server refuses any request without a valid token, including the
console's own sign-in check. The console signs with its `auth.token_secret` (environment `DRISHTI_TOKEN_SECRET`)
and the server verifies with `drishti.security.secret` (the same variable). They must be identical and at least
32 bytes long.

1. Compare the value both processes were started with: in your service environment file (for example
   `/etc/drishti/drishti.env`, see [OPERATIONS.md](../OPERATIONS.md)) or the compose `.env`. Both processes
   must read the same variable.
2. Look for the server's refusals in its log, or reproduce one: a request with a token signed by another
   secret is answered with

   ```json
   {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"bad token signature"}
   ```

   Other `detail` texts: `missing bearer token` (the console has no token secret), `token expired` (clock skew),
   `malformed token`, `unsupported token algorithm`.
3. Check the clocks. Tokens live 300 seconds and the server allows 30 s of skew (`drishti.security.clock-skew`).
   On both hosts:

   ```bash
   timedatectl | grep -E "synchronized|NTP"
   ```

   You should see `System clock synchronized: yes`.

### Step 4. Can the console reach the server?

Ask the console itself; its readiness check calls the server exactly as sign-in does:

```bash
curl -s -w ' %{http_code}\n' http://localhost:17480/readyz
```

You should see `{"status":"UP","server":"reachable"} 200`. A `503` with `"server":"unreachable"` and a `detail`
means the console cannot reach the server; the `detail` says why (a refused connection, a timeout, or
`bad token signature` when the secrets differ, step 3).

Then, from the console's host, ask the server directly:

```bash
curl -s http://localhost:18480/actuator/health/readiness
```

You should see `{"status":"UP"}`. If not, the server is down or the console points elsewhere: the console's
server address is `backend.url` (environment `DRISHTI_BACKEND_URL`, default `http://127.0.0.1:18480`).

### Step 5. Single sign-on

1. The console's OIDC settings (`DRISHTI_OIDC_ENABLED`, `DRISHTI_OIDC_ISSUER`, `DRISHTI_OIDC_CLIENT_ID`,
   `DRISHTI_OIDC_CLIENT_SECRET`, `DRISHTI_OIDC_REDIRECT_URI`) and the server's (`drishti.security.oidc.*`, same
   issuer and client id) must agree.
2. The provider must accept the redirect URI, by default `<console URL>/auth/oidc/callback`.
3. "The sign-in provider said: …" quotes the provider's own error (for example an unknown client or a redirect
   URI it does not accept). Fix it at the provider.
4. Signed in at the provider but refused by Drishti: none of the user's groups maps to a role and
   `default-roles` is empty. Map the group in `drishti.security.oidc.role-map`.

## Fixes

**Fix A. Unlock.** A lock ends by itself after 15 minutes (`drishti.identity.lockout`). To unlock now, an admin
opens Admin → Users and uses **Reset password** on the user. A reset also clears the failure count and the lock.
Give the user the new password over a separate channel.

**Fix B. Enable.** Admin → Users → **Enable**.

**Fix C. Create the user.** Admin → Users → **New user**, and give it the roles it needs. Passwords need at least
10 characters (`drishti.identity.min-password-length`).

**Fix D. Give access to a kind.** Edit the user's roles in Admin → Users, or the role's kinds in Admin → Roles.
If the role is fine, check the packs: the kind's pack must be ticked for the user in Admin → Users (or the user
must have *default packs*), ticked in the user's own pack menu, and **on** in Admin → Packs. "No access" on a link
is the expected result when a role lacks that kind; nothing is broken.

**Fix E. No admin can sign in.**

1. If one admin is merely locked, wait 15 minutes; the lock ends by itself.
2. Otherwise an administrator of the host recovers the development admin, `drishti-dev-admin`. The server creates
   it on start-up only when the user store holds **no** users and `DRISHTI_SEED_ADMIN` is `true`. Where the user
   store lives, and so how to start the server on an empty one, depends on the release: see
   [USER_MANAGEMENT.md](../USER_MANAGEMENT.md). Back the existing store up first and keep it
   ([OPERATIONS.md](../OPERATIONS.md) lists what to back up), so the audit log and users can be restored.
3. Sign in as `drishti-dev-admin`, change its password at once (the console warns until you do), restore real
   admins, then turn `DRISHTI_SEED_ADMIN` back to `false`.

The server refuses to delete, disable or demote the last admin (`DRS-6006`), so this situation arises only from
lost passwords or lockouts, not from an admin removing the last admin.

**Fix F. Align the secrets.** Set the same `DRISHTI_TOKEN_SECRET` (32 or more bytes, for example from
`openssl rand -base64 48`) for both server and console, and restart both. Sessions signed with
`DRISHTI_SESSION_SECRET` survive a console restart as long as that secret does not change.

## Verification

1. The user signs in at `http://localhost:17480/login` and lands on the terminal.
2. They open an entity their roles allow, for example `TRD T-10001 <GO>`, and it loads.
3. Admin → Audit shows a `login` entry for them and no new `login-failed` entries.
