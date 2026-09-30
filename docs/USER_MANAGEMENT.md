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
# User management

Users live in the **server** (`drishti-identity` module), so any number of console instances share
them. The console never sees password hashes. It verifies a sign-in by calling the server with its
own short-lived *service* token, then keeps a signed session cookie.

## The development admin

On first start, if the user store is empty, the server creates:

| User | Password | Roles |
|---|---|---|
| `drishti-dev-admin` | `drishti-dev-admin123` | `admin` |

It is a development convenience. The *Users* page shows a warning until its password is changed
(*My account → Change password*). In production, set `DRISHTI_SEED_ADMIN=false` and create real
admins, or change the seeded password on first sign-in.

## Roles

A user can hold any role defined in `drishti.security.roles`:

| Role | May open | Raw JSON | Propose Sutras | Approve Sutras | Manage users |
|---|---|---|---|---|---|
| `trader` | trades, curves, spot, index, book, contract specs, clearing accounts, counterparties | redacted | — | — | — |
| `risk` | everything | full | — | — | — |
| `author` | everything | full | yes (where Studio saving is on) | — | — |
| `approver` | everything | full | yes | **yes**, never their own | — |
| `admin` | everything | full | yes | yes | **yes** |

A role flag `approve: true` makes any role an approver. Proposals, approvals, rejections and withdrawals are in the
audit log (`sutra-proposed`, `sutra-approved`, `sutra-rejected`, `sutra-withdrawn`).

There is always at least one enabled admin. The last one cannot be disabled, demoted or deleted,
and nobody can disable or delete their own account.

## What admins can do (`/admin/users`)

- Create users: user name, display name, desk, email, roles, enabled flag, and an initial password.
  **Must change at first sign-in** is unticked by default. It is pre-ticked only when
  `drishti.identity.force-password-change-on-create` is on, and an admin can tick it for any one user.
- Edit a profile and roles, enable or disable, reset a password (this also clears a lockout), and delete.
- Search by name, email or role.
- Read the **audit log** (`/admin/audit`): sign-ins, failures, lockouts and every change, with the
  actor. It is append-only JSON lines on disk, never contains passwords, and can be filtered by user.

- Purge **caches** (`/admin/caches`): see what the engine and each connector hold (entries, memory, disk, hits)
  and purge one cache, or all, at any time. The next reads refill from the sources. Every purge is audited
  (`cache-purged`).

## Packs per user

In the user dialog, **Packs** sets which installed domain packs the user may use. Users with more than
one pack choose which to see from the pack switcher in the top bar. See PACKS.md.

## What every user can do (`/account`)

- See their profile and roles.
- Change their password (they must give the current one).

Nobody is forced to change a password unless it is configured:
- `force-password-change-on-create` covers new users, where the admin can still tick it per user;
- `force-password-change-on-reset` covers passwords reset by an admin.

Both default to off.

## Health (`/admin/health`)

Admins see, refreshed every 5 seconds:

- **Connectors**: UP or DOWN (with the reason while it reconnects), the kinds each serves, reads (found and not held),
  errors, p50/p99 read latency over the last 512 reads, the last error and when, and cache sizes. Connectors that
  failed to start are listed with the reason. What needs attention is listed first.
- **Packs**: status, what each requires, kinds, Sutras loaded, Sutra problems (file and message) and the state of
  the pack's connectors.
- **Live**: open streams, live topics, frames sent (and merged for slow clients), tick-to-screen latency.
- **Server**: version, uptime, heap, threads.

The overall status is **OK**, **DEGRADED** (a connector down or failed, or a pack with problems) or **DOWN**
(no connector can serve). Monitoring systems can poll the same data at `GET /api/v1/admin/health` (admin token).

## Single sign-on (OpenID Connect)

Staff can sign in through the bank's identity provider (Keycloak, Microsoft Entra ID, Okta, Ping, …). The login
page then offers **Sign in with single sign-on** beside the password form. The server verifies every ID token itself
(ADR-014); the console only runs the browser redirect.

Register Drishti at the provider as a web application with the redirect URI `https://<console>/auth/oidc/callback`,
scopes `openid profile email`, and a groups claim in the ID token. Then set, for both console and server:

```bash
DRISHTI_OIDC_ENABLED=true
DRISHTI_OIDC_ISSUER=https://login.bank.example/realms/staff
DRISHTI_OIDC_CLIENT_ID=drishti
DRISHTI_OIDC_CLIENT_SECRET=…          # console only; omit for a public client (PKCE alone)
```

and map the provider's groups to Drishti roles in the server's configuration:

```yaml
drishti:
  security:
    oidc:
      groups-claim: groups                 # Keycloak realm roles: realm_access.roles; Entra ID: groups or roles
      username-claim: preferred_username   # or email, upn
      role-map:
        desk-rates: [trader]
        market-risk: [risk]
        sutra-authors: [author]
        sutra-approvers: [approver]
        drishti-admins: [admin]
      default-roles: []                    # empty: users whose groups map to nothing are refused
      roles-from-provider: true            # groups replace roles at each sign-in
```

- The first sign-in creates the user (no password); later sign-ins update name, email and roles.
- Disabling a user here keeps them out, whatever the provider says. The last enabled admin is never demoted by
  the provider.
- Every sign-in, first-time creation and refusal is in the audit log (`login-sso`, `user-provisioned`,
  `login-sso-refused`). Refusals say only that sign-on was refused; the reason is in the server log.
- Password sign-in stays for accounts created here (keep one break-glass admin).

## Rules

| Rule | Default | Setting |
|---|---|---|
| Hashing | PBKDF2-HMAC-SHA256, 240,000 iterations, 16-byte salt, constant-time check | `drishti.identity.iterations` |
| Password policy | at least 10 characters, letters and digits, not the user name | `drishti.identity.min-password-length` |
| Lockout | 5 failed sign-ins lock the account for 15 minutes | `max-failed-attempts`, `lockout` |
| Unknown users | answered like a wrong password, in the same time | — |
| Storage | `./data/identity/users.json`, written atomically, mode 600 | `users-file` (`DRISHTI_USERS_FILE`) |
| Audit | `./data/identity/audit.jsonl` | `audit-file` (`DRISHTI_AUDIT_FILE`) |

Back up `data/identity/`, and keep it out of version control (it is git-ignored).

## API (admin role required under `/admin`)

| Method | Path | Does |
|---|---|---|
| `POST` | `/api/v1/auth/login` | verify a sign-in (console service token only) |
| `GET` | `/api/v1/auth/me` | the caller's profile |
| `POST` | `/api/v1/auth/password` `{current, next}` | change own password |
| `GET` / `POST` | `/api/v1/admin/users[?q=]` | list or search / create |
| `GET` / `PUT` / `DELETE` | `/api/v1/admin/users/{u}` | read / update profile and roles / delete |
| `POST` | `/api/v1/admin/users/{u}/enabled` `{enabled}` | enable or disable |
| `POST` | `/api/v1/admin/users/{u}/password` `{password}` | reset password |
| `GET` | `/api/v1/admin/audit?limit=&subject=` | audit events, newest first |
| `GET` | `/api/v1/admin/caches` | every cache: `[{name, type, stats}]` (the engine, and each connector that caches) |
| `POST` | `/api/v1/admin/caches/{name}/purge` | purge one cache, or `all`; `{purged, elapsedMs}`; `404 DRS-5004` for an unknown name |
| `GET` | `/api/v1/admin/roles` · `/api/v1/admin/status` | role names · seeded-password warning and settings |

Error codes:

| Code | Meaning |
|---|---|
| `DRS-6001` | not found |
| `DRS-6002` | the user already exists |
| `DRS-6003` | weak password |
| `DRS-6004` | bad credentials |
| `DRS-6005` | locked |
| `DRS-6006` | last admin |
| `DRS-6007` | invalid user data |
