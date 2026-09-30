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
# ADR-014: Single sign-on, verified by the server

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Banks sign staff in through a corporate identity provider (Keycloak, Microsoft Entra ID, Okta, Ping) and manage
access through its groups. Drishti had its own users and passwords only. The console is the browser-facing part;
the server holds users, roles and the audit log, and trusts the console only to verify sign-ins.

## Decision
- **OpenID Connect, authorization code flow with PKCE.** The console redirects to the provider with `state`, `nonce`
  and a PKCE challenge kept in a short-lived signed cookie, and exchanges the code for an ID token (as a confidential
  client with `client_secret_basic` or `client_secret_post`, or a public client with PKCE alone).
- **The server verifies the ID token, not the console.** The console hands the token and the nonce to
  `POST /api/v1/auth/oidc` (callable by the console's service identity only). The server checks the signature
  against the provider's published keys (JWKS, discovered from the issuer, refreshed on an unknown key id at most
  once a minute), then issuer, audience and authorised party, expiry, not-before, issued-at and nonce. A console
  that is compromised still cannot sign anyone in without a token the provider signed.
- **JDK cryptography only.** RS256/384/512, PS256/384/512 and ES256/384/512 through `java.security`; `none` and
  shared-secret HMACs are never accepted, whatever the configuration says. No JOSE library is added.
- **Groups become roles** through `drishti.security.oidc.role-map`; users whose groups map to nothing are refused
  (or get `default-roles`). Users are created on first sign-in, with no usable password; the provider's groups
  replace their roles at each sign-in (`roles-from-provider`), except that the last enabled admin is never demoted.
- **Drishti still decides.** A user disabled in Drishti stays out whatever the provider says. Every sign-in,
  provisioning and refusal is audited (`login-sso`, `user-provisioned`, `login-sso-refused`).
- Password sign-in stays available (for break-glass admins); sites may keep only SSO users in practice.

## Consequences
- Operators configure the provider twice: the console (`auth.oidc.*`: issuer, client id and secret) and the server
  (`drishti.security.oidc.*`: issuer, client id, claims, role map). Both read the same environment variables.
- Provider URLs must be https (localhost excepted for tests).
