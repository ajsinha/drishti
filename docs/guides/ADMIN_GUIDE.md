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
# Administration guide: people, access and lenses

This guide is for the people who run Drishti for others. It covers who may sign in and how (users, passwords, single sign-on, API
tokens, embedding), what each person may open (roles, powers, packs), where all of that is stored, what each person is shown (lenses and
editions, Home, branding), and the switches that turn a command, a compute engine or a module off. You need the **admin** role for
almost everything here; it is all under **Admin** in the top bar. Every setting named here is described in full in
[CONFIGURATION.md](../admin/CONFIGURATION.md); the endpoints are in [API_REFERENCE.md](API_REFERENCE.md).

## Contents

1. [Signing in and out](#1-signing-in-and-out)
2. [Your first ten minutes: add a person](#2-your-first-ten-minutes-add-a-person), with [three demo users](#27-three-demo-users)
3. [Roles](#3-roles)
4. [Packs per user](#4-packs-per-user)
5. [Single sign-on (OpenID Connect)](#5-single-sign-on-openid-connect)
6. [API tokens](#6-api-tokens)
7. [Embedding host applications](#7-embedding-host-applications)
8. [Who looked at what: the access log](#8-who-looked-at-what-the-access-log)
9. [The identity database](#9-the-identity-database)
10. [Rules Drishti enforces](#10-rules-drishti-enforces)
11. [The users and roles API](#11-the-users-and-roles-api)
12. [What a lens is](#12-what-a-lens-is)
13. [Run a whole instance as Drishti Risk or Drishti Finance](#13-run-a-whole-instance-as-drishti-risk-or-drishti-finance)
14. [Offer lenses to people](#14-offer-lenses-to-people)
15. [Home: landings, cards and chips](#15-home-landings-cards-and-chips) ([15.1 Configuring role Homes](#151-configuring-role-homes))
16. [The shipped lenses](#16-the-shipped-lenses)
17. [Customise a lens without forking it, and white-label it](#17-customise-a-lens-without-forking-it-and-white-label-it)
18. [Make your own lens](#18-make-your-own-lens)
19. [Lenses in a cluster](#19-lenses-in-a-cluster)
20. [Functions (FUNC): every command, and turning one on or off](#20-functions-func-every-command-and-turning-one-on-or-off)
21. [Compute engines on or off](#21-compute-engines-on-or-off)
22. [Modules on or off](#22-modules-on-or-off)
23. [Data bindings](#23-data-bindings)
24. [White label: distributions](#24-white-label-distributions)
25. [Modules](#25-modules)
26. [Troubleshooting](#26-troubleshooting)

## 1. Signing in and out

Where the links are, when sign-in is **on** (`DRISHTI_AUTH_ENABLED=true` with the session and token secrets on both the server and the
console: [CONFIGURATION.md, Turn security on](../admin/CONFIGURATION.md#turn-security-on-production)):

- The landing page (when you are not signed in) has a **Sign in** button in the top navigation. It returns you to the page you were on.
  Any protected page (the terminal, Search, Admin) sends you to the sign-in page first. The sign-in page links back to the home page.
- Signed in, your initial and name at the right of the top bar (or of the landing page's navigation) open a menu with **My account** and
  **Sign out**. Signing out ends the session on the server and lands on the landing page with a "Signed out" notice. It is a POST from the
  console's own pages only. The live views and alerts of that session stop at once on that console; on another console, or after an
  administrator disables or deletes the user, they stop within `auth.recheck_seconds` (10 s by default), when the console asks the server
  again whether the session stands.
- With several servers on one console, each server's session is its own: live views, pack jobs and AI limits belong to the person on
  that server, never to the same name on another server.

When sign-in is **off** (the local default), everyone who reaches the console acts as the development user, so there is nothing to sign
in to and no **Sign out**. The top bar's user menu says **Sign-in is off on this server** and links here, and `/login` says so and offers
to open the terminal.

Every user can see their own profile and roles on **My account** (`/account`), change their password (giving the current one), make
personal API tokens ([section 6](#6-api-tokens)), and choose their theme, clock zone, density and landing page.

## 2. Your first ten minutes: add a person

The example: Priya joins the credit desk. She should see counterparties and credit curves, nothing else, and she works with the *Banking
core* and *Counterparty credit risk* packs.

### 2.1 Sign in as the development admin

On a fresh installation the server creates one account the first time it starts:

| User | Password | Roles |
|---|---|---|
| `drishti-dev-admin` | `drishti-dev-admin123` | `admin` |

The demo profile (`drishti.profile=demo`, which `start all --demo` uses) also creates three demo users on that first start
([section 2.7](#27-three-demo-users)):

| User | Password | Role |
|---|---|---|
| `drishti-dev-risk` | `drishti-dev-risk123` | `risk-trading` |
| `drishti-dev-genomics` | `drishti-dev-genomics123` | `genomics-reader` |
| `drishti-dev-market` | `drishti-dev-market123` | `market-data-reader` |

Open the console (for example `http://localhost:17480`), click **Sign in** and use those details. Until you change the password, every
admin page shows a red warning naming every account that still has a published password (the development admin, the demo users). Under
the production profile (`drishti.profile=production`) none of them is ever created and a start that would create them is refused
([the production standard](../admin/PRODUCTION_STANDARD.md#1-the-production-profile), [section 10](#10-rules-drishti-enforces)).

### 2.2 Change that password

Click your name in the top bar, then **My account → Change password**. Type the current password and the new one twice. The new one needs
at least 10 characters, with letters and digits, and must not be your user name. You should see *Password changed.* The warning
disappears.

### 2.3 Make a role for the credit desk

Go to **Admin → Roles** and click **New role**. Fill in:

| Field | Value |
|---|---|
| Name | `credit-analyst` |
| Description | `Reads counterparties and credit curves` |
| Kinds it opens | `counterparty` and [`credit-curve`](../contracts/packs/market-data.md#example-market-data-credit-curve) ([contract](../contracts/packs/market-data.md#cds-credit-curve-credit-curve)) (one per line) |
| Powers | leave them unticked |

Click **Save**. You should see *Created credit-analyst.* The row shows the two kinds, *0* users, and who changed it and when. Instead of
typing kinds, you can click a pack's button under *Add every kind of a pack*; tick **Opens every kind** for a role that may open
everything.

### 2.4 Create the user

Go to **Admin → Users** and click **New user**:

| Field | Value |
|---|---|
| User name | `priya` (3–64 of `a-z 0-9 . _ -`; it never changes) |
| Display name | `Priya Raman` |
| Desk | `Credit` |
| Email | `priya@bank.example` |
| Roles | tick `credit-analyst` |
| Packs | untick all but `core` and `counterparty-risk` |
| Initial password | at least 10 characters; tell Priya it, privately |
| Must change at first sign-in | tick it if Priya should choose her own |

Click **Save**. Priya appears in the list as *active*.

### 2.5 Check what Priya sees

Sign out and sign in as `priya`. Then:

- `CPTY CP-NORTHBRIDGE <GO>` opens the counterparty.
- `TRD IRS-48213 <GO>` is refused: *priya may not open trade entities*. Links to trades on her pages are still shown, but greyed out with
  the reason. Drishti shows that something exists without showing its content.
- The pack switcher in the top bar lists only her two packs.

### 2.6 Read the audit log

Back as the admin, open **Admin → Audit log**. You should see, newest first, `login` (priya), `user-created` (priya) and `role-created`
(credit-analyst), each with who did it and when. Type `priya` in the filter to see only what concerns her.

### 2.7 Three demo users

The demo profile creates these three users for you; on any other installation make them by hand with the steps below. Each sees one
slice of the product: trades and risk, genomics, or market data. Access has two parts and a user needs both: a **role** says which kinds
of data they may open, and **packs** say which domains appear for them at all. Opening a kind needs a role that allows it **and** the pack
that holds it.

| User | Sees | Role | Packs |
|---|---|---|---|
| `drishti-dev-risk` | trades, market data, VaR and ES, exposure, limits | `risk-trading` | `core`, `trading`, `market-data`, `market-risk`, `counterparty-risk` |
| `drishti-dev-genomics` | genomics only | `genomics-reader` | `genomics`, `genomics-showcase`, `protein-engineering` |
| `drishti-dev-market` | market data only | `market-data-reader` | `market-data` |

**By hand.** Sign in as an administrator. **Admin → Roles → New role**, once per role: fill in the name and description, click the pack
buttons of the table under **Add every kind of a pack** (this fills *Kinds it opens*), leave the powers unticked (`drishti-dev-risk`'s role may
have `bi` for pivots and reports) and click **Save**; you should see *Created <name>*. Never tick *Opens every kind*. Then **Admin → Users
→ New user**, once per user: the user name, the display name, tick the role, tick exactly the packs of the table (an empty list means *no*
packs; a user never given a list sees the default packs), an initial password of at least 10 characters with letters and digits, and
**Save**. The demo profile does the same on the first start and gives each user the password in the table of [section 2.1](#21-sign-in-as-the-development-admin);
until a password is changed, Admin shows the red default-password warning, so never run the demo profile in production.

**Check each one.** Sign out and sign in as each user (a private window is quickest):

| As | Try | Expect |
|---|---|---|
| `drishti-dev-risk` | `VAR BANK <GO>`, `TRD T-1 <GO>`, a curve | all open; the pack switcher lists its five packs; the risk manager's Home first |
| `drishti-dev-risk` | `GENE HLXK7 <GO>` | refused: *may not open gene entities* |
| `drishti-dev-genomics` | `/m/genomics/showcase`, `GENE HLXK7 <GO>` | open |
| `drishti-dev-genomics` | `VAR BANK <GO>`, `TRD T-1 <GO>` | refused; risk menus absent |
| `drishti-dev-market` | a curve or an FX rate | opens |
| `drishti-dev-market` | `TRD T-1 <GO>`, `VAR BANK <GO>` | refused; links to trades show greyed out with the reason |

A refusal shows that something exists without showing its content, by design. Back as the administrator, **Admin → Audit log** lists
`role-created` and `user-created` for each, then each user's `login`; filter by `demo-` to see only these. To remove access later,
untick a role or pack on the user or **Disable** the user (nothing is deleted). With single sign-on, map the bank's groups to roles
instead ([section 5](#5-single-sign-on-openid-connect)).

The same steps from a script are in [section 11](#11-the-users-and-roles-api).

## 3. Roles

A role says two things: **which kinds of entity** its holders may open (`trade`, `counterparty`, … or `*` for every kind), and **which
powers** they hold. A person may hold several roles: they may open a kind if **any** of their roles allows it, and they have a power if
**any** of their roles grants it.

### 3.1 The powers

| Power | Lets the holder |
|---|---|
| `raw` | see every field, in raw JSON (F9) and everywhere else. Others see the fields in `drishti.security.redact` as `•••` wherever their value could be seen or inferred: views, search, Impact, type-ahead, alerts ([CONFIGURATION.md, Field masks](../admin/CONFIGURATION.md#field-masks)). Masks work on fields: a masked value copied into another text field (a timeline note) stays visible unless that field is masked too or `drishti.security.mask-copies` is on (best effort, exact match); fix the source so free text does not carry sensitive values |
| `author` | save Sutras from Studio and propose them (where Studio saving is on). **Designing is open to everyone**: any signed-in user may use Studio and the Screen Builder and preview against a pasted document; saving, proposing, approving and deleting need the power, and previewing a stored entity needs the right to its kind |
| `approve` | approve or reject proposed Sutras, but never their own (four eyes) |
| `admin` | everything in this guide: users, roles, caches, health, audit |
| `calc` | use Calc (`Alt+C`): Python run in their own browser on the views a pack offers it, reading only what their roles open ([section 3.5](#35-calc)) |
| `layout` | use layout mode (`Alt+L`): arrange a view's panels for themselves and keep that personal layout. **On unless a role says `layout: false`** ([section 3.6](#36-layouts)) |
| `collaborate` | comment on entities and send views to colleagues; **on unless a role says `collaborate: false`** ([section 3.8](#38-discussion-sharing-and-shared-workspaces)) |
| `compliance` | read any share and the full record of any thread, place legal holds, run the compliance export; an administrator does not have it unless a role grants it ([section 3.8](#38-discussion-sharing-and-shared-workspaces)) |
| `bi`, `bi-author`, `bi-steward`, `bi-admin` | business intelligence, granted as a list on the role ([section 3.4](#34-bi-powers-rupaka)) |
| permission `var:system-baskets` | create, edit, retire and import the VaR engine's system baskets, the bank's own baskets everyone reads as `PORT:<id>` ([VAR_GUIDE.md section 5.7](VAR_GUIDE.md#57-system-baskets)). A **module permission**: granted as a list on the role (`permissions: [var:system-baskets]` in configuration or a pack; the *Permissions* group of Admin → Roles); every administrator holds it. Each change is audited and its owner told |

### 3.2 Built-in roles and your own roles

| | Built-in roles | Roles you define |
|---|---|---|
| Come from | `drishti.security.roles` in configuration, and the `roles:` section of each pack | **Admin → Roles** |
| Stored in | configuration files | the identity database |
| Changed by | editing configuration, then a restart | an admin, at once, from the browser |
| Shown as | *built-in*, without Edit or Delete | normal rows, with Edit and Delete |

Configuration ships with these:

| Role | Opens | Raw JSON | Author | Approve | Admin | Calc | Layout |
|---|---|---|---|---|---|---|---|
| `viewer` | everything | masked | — | — | — | — | — (`layout: false`) |
| `author` | everything | yes | yes | — | — | yes | yes |
| `approver` | everything | yes | yes | yes | — | yes | yes |
| `pivot-approver` | everything | masked | — | — | — | — | yes |
| `admin` | everything | yes | yes | — | yes (and admins may approve) | yes | yes |

`pivot-approver` approves custom pivot functions someone else wrote (four eyes: never one's own; administrators may too); it is the role
`drishti.pivot.functions.approver-role` names as shipped ([PIVOT.md](bi/PIVOT.md)). Its `approve` is not the Sutra review's: a
pivot approver does not make Sutras live.

Packs add their own. The shipped packs add `trader` (trading), `market-risk` (market-risk), `credit-risk` (counterparty-risk), `economist`
and `analyst` (world-economy), `news-analyst` and `market-analyst` (news-and-markets); core and market-data add none. The roles of the packs
that offer Calc hold `calc`: `trader`, `market-risk` and `credit-risk`; the roles of world-economy and news-and-markets do not. Every pack
role has `layout`, and so has every role you define in Admin → Roles unless you untick *Customise layouts*. A pack declares one like this:

```yaml
# modules/economy/packs/world-economy/pack.yaml
roles:
  economist:
    kinds: [economy, macro-indicator, economic-forecast, labour-market, fiscal-position, country-group, ...]
```

Rules for your own roles:

- Names are 2–64 of `a-z 0-9 -` and start with a letter (`credit-analyst`, `ops-l2`).
- A built-in name always means the built-in role, so you cannot create a role called `admin` or `trader`.
- A role opens at least one kind.
- A role that someone holds cannot be deleted. The Delete button says how many hold it. Take the role away from them on the Users page
  first; the server refuses (`DRS-6009`) if you try anyway.
- A change applies at each holder's next click: no restart and no new sign-in.
- Every create, change and delete is audited (`role-created`, `role-updated`, `role-deleted`), with what the role allows.

### 3.3 How Drishti decides whether someone may open an entity

Two checks, and both must pass:

```
may open trade MX-20000001  =  one of my roles opens "trade"          (roles: this section)
                   AND the pack that defines "trade" is mine   (packs: section 4)
```

A lens ([section 12](#12-what-a-lens-is)) never widens or narrows this: it changes only what a person is shown.

### 3.4 BI powers (Rupaka)

Business intelligence ([README.md](bi/README.md)) has four powers of its own. They are a list, `bi`, on a role:
`drishti.security.roles.<role>.bi: [bi, bi-author]` in configuration or a pack, or, for a role you define, **Admin → BI settings** (a
table of roles with four tick boxes each; built-in roles are read-only there) or the `bi` list of `PUT /api/v1/admin/role-definitions/{name}`.
Saving a role in **Admin → Roles** keeps the BI powers it has.

| Power | Lets the holder |
|---|---|
| `bi` | open BI (the **BI** top-bar menu, `RUPAKA <GO>`, `/bi`), the catalogue and the published items their audience allows; favourites and recents |
| `bi-author` | create, edit, move, propose and publish items in the workspaces they are a contributor or member of, and create shared workspaces (when the policy is `authors`, the default) |
| `bi-steward` | **certify** published items (owners only *promote*); **review** proposals in any workspace; **import bundles**; create shared workspaces when the policy is `stewards` |
| `bi-admin` | Admin → BI settings (the workspace policy, powers per role); admin of every shared workspace; always allowed to create workspaces |

A higher power also opens BI, so a role with only `bi-author` sees the catalogue. An administrator holds all four; with security off
everyone does. The built-in `viewer` holds `bi`; `author` and `approver` hold `bi` and `bi-author`; the roles packs add hold **none** until
you grant them. A power opens the door; what a person may do in a given workspace is then decided by their **workspace role** (viewer,
contributor, member, admin) there. Approving a proposal needs the `approve` power as well, and with four eyes on never your own proposal.
Changes to roles, settings, workspaces, members and items are in the audit log (`role-updated` with `bi=[...]`, `bi-settings-changed`,
`bi-workspace-*`, `bi-member-*`, `bi-item-*`).

### 3.5 Calc

Calc ([USER_GUIDE.md](bi/CALC.md)) runs Python in the user's browser on the view they are looking at.

- **Who:** holders of a role with `calc`. Tick *Use Calc* in the role's dialog (Admin → Roles), or `calc: true` in configuration or a pack.
- **What it reads:** only what the user could open by clicking. Every read goes through the console with the user's session; roles, packs
  and masking apply call by call. Nothing runs on the server.
- **Where:** on views of kinds whose pack enables Calc (`python.enabled` in `pack.yaml`).
- **What is kept:** each user's own snippets (`drishti_preference`, namespace `calc-snippets`, at most 50), deleted with the user. Reads
  are in the access log like any read (`drishti.columns()` as a `search`).
- **For everyone:** Calc is a Labs feature, off as shipped; `DRISHTI_LABS_CALC=true` (server and console) switches it on.

### 3.6 Layouts

Layout mode ([USER_GUIDE.md](USER_GUIDE.md#layout-mode-arrange-a-view-your-way)) lets a user drag, resize and hide a view's panels for
themselves.

- **Who:** holders of a role with `layout`, which is **every role unless it says otherwise**: `viewer` without it; `author`, `approver`,
  `admin` and every pack role with it; roles defined in Admin → Roles with it unless *Customise layouts* is unticked (stored as a
  `no-layout` row in `drishti_role_power`). To take it away from a role in configuration or a pack, write `layout: false`. Without it the
  footer's **Layout** key is greyed with the reason and `/api/v1/me/layouts/**` answers `403 DRS-5002`.
- **What is kept:** each user's layout per Sutra (`drishti_preference`, namespace `layouts`, one document per Sutra: panel order, column,
  `span`, `height`, `hidden`; at most 50), deleted with the user. A layout never changes the Sutra.
- **Promoting a layout** needs `author` as well (and `drishti.rachana.studio-save`): it is a Sutra proposal like any Studio save, reviewed
  by an approver (four eyes) and audited as `sutra-proposed`.
- **For everyone:** `DRISHTI_LAYOUTS_ENABLED=false` (server and console) switches layouts off; saved layouts are then not applied either.

### 3.7 Pivots

The Pivot ([USER_GUIDE.md](USER_GUIDE.md#pivot-slice-a-table-your-way)), offered where a Sutra or a pack opts in, has **no power of its
own**:

- **Who:** anyone who may open the kind may pivot its tables and search results and save their arrangement; it changes nothing for anyone
  else. The pivot reads only what the view or search would show them: a masked field groups under `•••` and is never added up.
- **What is kept:** each user's arrangement per Sutra and panel, and per kind for searches (`drishti_preference`, namespace `pivots`),
  deleted with the user.
- **Promoting a pivot** to a Sutra needs `author` (and `drishti.rachana.studio-save`), as for layouts.
- **Reads** are in the access log: a panel's rows for its pivot as a `view`, a search pivot and its drill-downs as a `search`.
- **For everyone:** `DRISHTI_PIVOT_ENABLED=false` (server) switches the Pivot tab off; the pivot engine itself is a compute engine
  ([section 21](#21-compute-engines-on-or-off)).

### 3.8 Discussion, sharing and shared workspaces

- **Discussion:** anyone who may open a kind reads and writes comments on its entities, and sends a view to colleagues with a note, if their
  role has the `collaborate` power (`roles.<role>.collaborate: false` takes it away). Only the author edits, and only within
  `drishti.collab.threads.edit-window`; nobody erases: a retracted comment reads "Retracted by the author" and its text stays in the
  revisions. Comments, edits, retractions and moderation are in the audit log (`collab.comment.*`, `collab.thread.*`); a share is in the
  access log as `share`.
- **What people can learn about each other.** The people picker lists only users who share a pack with the person searching
  (`drishti.collab.directory.scope`), needs two letters, is rate limited, and shows a role's size, never its members. A notice is never sent
  to, and a mention never reaches, someone who may not open that kind of view; the sender is told who was left out and why (never what the
  view holds) under `share.undeliverable: tell`.
- **Moderation** (`admin`): **Hide** a comment with a reason (readers see "Hidden by a moderator: <reason>"; the text stays in the record),
  **Lock** a thread, and remove a whole thread for good with `DELETE /admin/collab/threads/{id}` (audited; refused with `423 DRS-7010`
  under a legal hold). The API is in [API_REFERENCE.md](API_REFERENCE.md#32-comment-threads).
- **Compliance** (`roles.<role>.compliance`): a compliance officer reads any share and the full record of any thread (hidden and retracted
  comments, every revision, unscrubbed), places and releases **legal holds**, runs the **export** and **verifies** the hash chains. The
  runbooks are in [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#123-retention-legal-holds-and-the-compliance-export).
- **Admin → Collaboration** (`/admin/collab`) shows what the signed-in roles may use, and the server decides every call: *Threads and
  moderation* (search by user, kind, id and dates; read a thread with its hidden comments; **Hide**, **Unhide**, **Lock**, **Unlock**;
  `admin`), *Legal holds* (list, place by scope, release; `compliance`), *Compliance export* (start, wait, download once; `compliance`),
  *Verify the chains* (one thread or everything; `compliance`), *Retention* (a dry run that counts what would go and what a hold keeps,
  never deletes; `admin`) and *Bridges* (the configured chat bridges with their queue, and a **Test** that posts one message; `admin`). A
  role that holds only `compliance` sees the compliance sections; an administrator without the power sees the others.
- **Shared workspaces:** a workspace's owner shares it with everyone, roles or named people. Readers see it read-only; panes on kinds a
  reader's roles may not open stay hidden. Share records are kept with the preferences (`drishti_preference`, namespace
  `workspace-shares`).

## 4. Packs per user

A pack is a domain's worth of kinds, mnemonics, layouts and roles ([BUILD_GUIDE.md](BUILD_GUIDE.md)). The server may
have many installed; each person sees the ones assigned to them.

- **Default packs.** A user who was never given a list of packs (the field left out, not an empty list) sees the packs in
  `drishti.packs.default-for-users`, or every installed pack when that is empty. In the Users list this shows as *default packs*.
- **Assigned packs.** Tick packs in the user dialog to give exactly those. Untick all of them (an empty list, `"packs": []`) and the user
  sees none: every kind answers `DRS-5002`. This is not the same as the default.
- **Choosing among them.** Someone with several packs picks which to see from the pack switcher in the top bar. That choice is theirs, and
  it is saved with their settings.

A pack's role is still needed to open its kinds. Giving Priya the *trading* pack without a role that opens `trade` shows her trade links
greyed out.

## 5. Single sign-on (OpenID Connect)

Staff can sign in through the bank's identity provider (Keycloak, Microsoft Entra ID, Okta, Ping, …). The login page then offers **Sign in
with single sign-on** beside the password form. The server verifies every ID token itself; the console only runs the browser redirect.

Register Drishti at the provider as a web application:

- redirect URI `https://<console>/auth/oidc/callback`;
- scopes `openid profile email`;
- a groups claim in the ID token.

Then set, for both console and server:

```bash
DRISHTI_OIDC_ENABLED=true
DRISHTI_OIDC_ISSUER=https://login.bank.example/realms/staff
DRISHTI_OIDC_CLIENT_ID=drishti
DRISHTI_OIDC_CLIENT_SECRET=…          # console only; omit for a public client (PKCE alone)
```

Map the provider's groups to Drishti roles, built-in ones or your own, in the server's configuration
([CONFIGURATION.md, single sign-on](../admin/CONFIGURATION.md#drishtisecurityoidc--single-sign-on)):

```yaml
drishti:
  security:
    oidc:
      groups-claim: groups                 # Keycloak realm roles: realm_access.roles; Entra ID: groups or roles
      username-claim: preferred_username   # or email, upn
      role-map:
        desk-rates: [trader]
        credit-desk: [credit-analyst]      # a role defined in Admin → Roles works too
        sutra-authors: [author]
        sutra-approvers: [approver]
        drishti-admins: [admin]
      default-roles: []                    # empty: users whose groups map to nothing are refused
      roles-from-provider: true            # groups replace roles at each sign-in
```

- The first sign-in creates the user, with no password. Later sign-ins update the name, email and roles.
- Disabling a user in Drishti keeps them out, whatever the provider says. The last enabled admin is never demoted by the provider.
- A mapped role that does not exist is dropped, and the server log says so.
- Every sign-in, first-time creation and refusal is in the audit log (`login-sso`, `user-provisioned`, `login-sso-refused`). Refusals tell
  the user only that sign-on was refused; the reason is in the server log.
- Password sign-in stays for accounts created in Drishti, so keep one break-glass admin.

### 5.1 The sign-in window for pages that embed views

A page that shows Drishti views but has **no backend of its own** (a static intranet page, a SharePoint page) cannot run the token exchange
of [section 7](#7-embedding-host-applications). With single sign-on on, it can use the renderer's **sign-in window** (`auth: 'popup'`;
[INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md#113-drishti-elements-libraries-in-your-own-app)): the page shows *Sign in to see this*; on the click, one small window runs this same
single sign-on and the page gets a short-lived, read-only, masked embed token, as from the exchange.

What to set, once:

- At the provider, add the redirect URI `https://<console>/embed/v2/sign-in/callback` to Drishti's client (beside `/auth/oidc/callback`),
  or set `embed.sign_in.redirect_uri` to the one you registered. For silent renewal the provider must issue refresh tokens (Keycloak does by
  default; Entra ID and Okta with `offline_access` in `auth.oidc.scopes`); without them the person clicks again when the token ends.
- Register the page's origin as a **host application** (Admin → Embedding, [section 7](#7-embedding-host-applications)): its origins,
  scopes and kinds apply. A page whose origin is not registered gets the button disabled with `DRS-8002` naming its origin, and no window.
- The window is on by default; `embed.sign_in.enabled: false` (console) or `drishti.embed.sign-in-window: false` (server) turns it off, and
  pages then need the exchange. `embed.sign_in.max_hours` (default the console's `auth.session_hours`) bounds how long it is renewed.

What is recorded: the sign-in itself as any single sign-on (`login-sso`, the user created or updated from the provider's groups), then
`embed-sign-in-window` with the page's origin, the user, the host application and the time (renewals say `(renewed)`), or
`embed-sign-in-window-refused` with the reason (an origin nobody registered, an ID token refused, a disabled user). The token never reaches
storage or a cookie in the page; the provider's refresh token is sealed by the console (AES-GCM) so the page holds it without reading it.

## 6. API tokens

People read Drishti from scripts, notebooks and Excel with personal API tokens they make on **My account → API tokens**
([API_REFERENCE.md, clients](INTEGRATION_GUIDE.md#33-clients-scripts-notebooks-and-excel)). What an administrator needs to know:

- **A token acts as its owner**, with the owner's roles and packs at the time of each call, and, unless it was made with a **write scope**,
  **only reads**: every `GET`, and the `POST`s that carry a query and change nothing, listed in `drishti.security.token-read-posts` (the
  search pivot, `/api/v1/command`, the search-form compile, and the BI query, validate and format calls). Any other `POST`, `PUT`, `PATCH`
  or `DELETE` is refused with `403 DRS-5002 API tokens only read`; add a path to the list only if it writes nothing.
- **Write scopes (for tools, CI and GitOps).** When making a token a person may tick `design:write`, `design:approve`, `packs:admin`,
  `bi:write`, `bi:approve`, `bi:admin`, `ai:use`, `ai:admin`, `loads:write` (an ETL job announcing that a batch landed:
  [DATA_GUIDE.md](DATA_GUIDE.md#10-permissions)), `risk:write` (VaR baskets and captures, what-if sandboxes), `risk:system-baskets` (the VaR system baskets: create, edit, retire, import; the user still needs the permission `var:system-baskets`), `pivot:write` (end live
  pivots, save and submit custom functions), `pivot:comment` (cell comments on saved pivot views), `pivot:approve` or `pivot:admin` (pivot
  rollups) (`drishti.security.token-scopes`; the account page lists them in words). What a token may do is the scope **and** the roles its
  user holds at the time of each call: `design:approve` is useless to someone who is not an approver, `packs:admin` to someone who is not an
  admin. `packs:admin` covers loading and deploying packs, the registry, Retry now and writes to `/api/v1/admin/connectors/**`; the `bi:*`
  scopes cover the write calls of business intelligence and still need the BI power and the workspace role each call needs; `ai:use` opens
  `POST /api/v1/ai/chat` and the MCP tool calls. So demoting or disabling a user cuts their tokens at once.
- A token with a write scope must expire (`drishti.security.token-write-max-days`, 90). Every write done with a token is in the audit log as
  `token-write` (token id, method, path, status; never the secret) and each refusal as `token-denied`.
- Users, roles, tokens, sign-in, caches, engine and command switches, and personal or collaboration state are never open to a token
  (`token-never`); there is no `admin` scope.
- Disabling a user stops their tokens at once (and they cannot make new ones); deleting a user deletes them.
- **Admin → Tokens** lists every token (owner, name, scopes: *read only* or the write scopes it holds, created, expires, last used in the
  console's time zone) and revokes any of them.
- Secrets are never stored, only their SHA-256; nobody, administrators included, can see a secret after it is made.
- `token-created` and `token-revoked` are in the audit log; so are `signed-out` (a console sign-out) and `sessions-ended` (an
  administrator's disable, delete or password reset ended the user's console sessions).

## 7. Embedding host applications

Another web application (a CRM, a portal) can show live Drishti views in its own pages with `<drishti-view>`
([INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md#113-drishti-elements-libraries-in-your-own-app)), without an iframe. An administrator allows it, one **host application** at a time.
Embedding is off until `drishti.embed.enabled` is on ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiembed--embedded-views-for-other-web-applications));
register applications on **Admin → Embedding** (`/admin/embedding`), or with the API
([API_REFERENCE.md](API_REFERENCE.md#24-embedding-views-in-another-web-application)), for example:

```bash
curl -s -X POST $SERVER/api/v1/admin/embed/apps -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{
  "id": "crm", "name": "Client CRM", "contact": "crm-team@bank.example",
  "origins": ["https://crm.bank.example"], "kinds": ["trade", "counterparty"], "scopes": ["embed:view"],
  "jwks": "{\"keys\": [ ... the host backend public key ... ]}"}'      # the answer shows the client secret once
```

- The host's **backend** keeps the client secret (or signs with its key) and tells Drishti who its signed-in user is with that user's ID
  token or a JWT it signs itself; it can never name a user with the secret alone. The browser only ever holds a five-minute, read-only
  embed token.
- Embedded views **always mask** the fields in `drishti.security.redact`, even for users whose roles have `raw`, and show only the kinds the
  application was registered for (`kinds`) that the user's roles open now.
- `enabled: false` (a `PUT`) stops the application at once: its tokens are refused at the next call. Disabling a user does the same for
  that user. **Rotate** the secret with a grace period (`rotate-secret`) so the host can switch over.
- A page with no backend may instead sign its user in through single sign-on in a window it opens on a click ([section 5.1](#51-the-sign-in-window-for-pages-that-embed-views)).
- The audit log has `embed-app-created`, `-changed`, `-enabled`, `-disabled`, `-secret-rotated`, `-deleted`, for every token request
  `embed-token` or `embed-token-refused`, and for the sign-in window `embed-sign-in-window` (with the page's origin) or
  `embed-sign-in-window-refused`. The access log shows each embedded view as the user's own with the detail `embed:<application>`, so
  "which application showed what to whom" is Admin → Access.
- Rates (`callsPerMinute` per application, `userCallsPerMinute` per user) answer `429 DRS-8004` with `Retry-After`.

### 7.1 The Admin → Embedding page

Admin menu → **Embedding** (`/admin/embedding`, administrators only) lists every host application: name and id, its origins, how it signs
in (client secret, public key, or both), scopes and kinds, whether it is enabled, and what it did **since the server started**: tokens
issued, embed calls, live streams open now, refusals by code (`DRS-8002` wrong origin, `DRS-8003` unknown or disabled, `DRS-8004` rate
limit, and so on), and when it was last used. Refusals that name no registered application are counted together in a line above the table.
The counters are in memory (they start again at a restart) and are also Prometheus metrics (`drishti_embed_tokens_total`, `_calls_total`,
`_refusals_total{code}`, `drishti_embed_streams`, each with an `app` label).

- **New application** (key `n`): name, id (made from the name; lower-case letters, digits and hyphens), origins one per line, how it signs
  in, scopes (the ones this server defines), the kinds it may show (empty: every kind its users' roles open), rates and token life. With a
  client secret, the secret is **shown once** in its own dialog, with a Copy button; closing the dialog forgets it, and a reload does not
  bring it back. With a public key, paste a JWK set (public keys only).
- **Rotate secret** asks for a grace period (seconds; the old secret keeps working that long, `0` ends it at once) and shows the new secret
  once.
- **Disable** stops the application at once (no new tokens; its tokens fail at the next call) but keeps its registration; **Enable** brings
  it back. **Delete** asks first and removes it for good. All of them are audited.
- Applications **declared in configuration** (`drishti.embed.apps`, for GitOps) are marked *from config* and read-only: change them in the
  file and restart. A configured id cannot be registered again here.
- Keys: `n` opens *New application*, `/` focuses the filter, `Esc` closes a dialog. The page works at phone width and in both themes.

## 8. Who looked at what: the access log

Every read that was answered is recorded: a view, a raw document (F9), a history or compare read, a search and a CSV export, with the
person, the time, the entity (or the search text) and the business date asked for (blank for live). Refused reads are not recorded (they
returned nothing). Administrators see it in **Admin → Access**, filtered by person, action, kind, id and dates; a view's **Viewed by**
(administrators only) opens the list for that entity. Worked example: "who looked at MX-20000001 last week" is Access with kind `trade`,
id `MX-20000001`, from and until.

It is in the identity database (`drishti_access`), kept `drishti.access-log.keep-days` (90) days. Recording is asynchronous and never slows a
read; if the database cannot keep up, events are dropped and the page says how many. The console's own service identity is not recorded.
Through the API: `GET /api/v1/admin/access?user=&action=&kind=&id=&from=&to=&limit=`.

## 9. The identity database

Everything in sections 1 to 8 is in **one database**: users with their roles and packs; the roles you define; each person's saved
workspaces, monitors, alert rules and settings; the audit and access logs; the command and pack switches ([section 20](#20-functions-func-every-command-and-turning-one-on-or-off)).
The server reaches it through JPA (Hibernate and Spring Data repositories), with no hand-written SQL in the code.

| Database | When | URL |
|---|---|---|
| **SQLite** (default) | one server; nothing to install | `jdbc:sqlite:./data/identity/drishti.db` |
| **PostgreSQL** | several servers sharing users, or your database team's backups | `jdbc:postgresql://host:5432/drishti` |

### 9.1 The schema: two files, no migrations

The tables are written out in full, once per database, in `kernel/drishti-identity/src/main/resources/db/`: `schema-sqlite.sql` and
`schema-postgres.sql`. The server runs the right file every time it starts. Each statement is `CREATE … IF NOT EXISTS`, so running it on an
existing database changes nothing. There is no migration tool and no migration history: the file *is* the schema. On PostgreSQL the server
also checks at start that its entities match the tables, and refuses to start, naming the column, if they do not.

| Table | Holds |
|---|---|
| `drishti_user` | one row per person: name, email, desk, password hash, enabled, lockout, timestamps |
| `drishti_user_role` | (user, role) pairs |
| `drishti_user_pack` | (user, pack) pairs; `drishti_user.packs_assigned` says whether they apply or the defaults do |
| `drishti_role` · `drishti_role_kind` · `drishti_role_power` | roles defined in Admin → Roles, the kinds each opens, and their extra powers (`calc`; `no-layout` for a role that may not customise layouts), one row each |
| `drishti_preference` | (user, namespace, name) → a JSON document: workspaces (with their divider sizes), monitors, alert rules, settings, Calc snippets (`calc-snippets`), personal layouts (`layouts`), pivots (`pivots`), shares (`workspace-shares`) |
| `drishti_audit` | one row per audited action, numbered in order |
| `drishti_api_token` | personal API tokens: owner, name, a SHA-256 of the secret (never the secret), scopes, created, expires, last used, revoked |
| `drishti_session` | console sign-in sessions: a SHA-256 of the session id (never the id), user, created, expires. A row is removed at sign-out, when the user is disabled, deleted or has their password reset, and (hourly) once expired |
| `drishti_access` | the access log: when, who, action (view, raw, history, search, export), kind, id, search text or field, business date |
| `drishti_switch` · `drishti_switch_log` | the command and pack switches and every change to them ([section 20.6](#206-where-it-is-kept)) |

Deleting a user removes their role and pack rows with them (`ON DELETE CASCADE`), and their saved documents too.

### 9.2 Use PostgreSQL

1. Create a database and a user (as a PostgreSQL superuser):

   ```sql
   CREATE USER drishti WITH PASSWORD 'choose-a-long-one';
   CREATE DATABASE drishti OWNER drishti;
   ```

2. Point the server at it, through environment variables or `application.local.yaml`
   ([CONFIGURATION.md, identity](../admin/CONFIGURATION.md#drishtiidentity--users-passwords-and-the-identity-database)):

   ```bash
   export DRISHTI_IDENTITY_DB_URL=jdbc:postgresql://db.bank.example:5432/drishti
   export DRISHTI_IDENTITY_DB_USER=drishti
   export DRISHTI_IDENTITY_DB_PASSWORD='choose-a-long-one'
   ```

   ```yaml
   # application.local.yaml
   drishti:
     identity:
       database-url: jdbc:postgresql://db.bank.example:5432/drishti
       database-user: drishti
       database-password: ${DRISHTI_IDENTITY_DB_PASSWORD}
       # database-pool-size: unset = drishti.db.pool.size (20) on PostgreSQL
   ```

3. Start the server. The log says:

   ```
   identity database ready: jdbc:postgresql://db.bank.example:5432/drishti (db/schema-postgres.sql)
   ```

   If PostgreSQL is not up yet, the server waits for it, retrying with growing pauses for up to about four minutes:
   *identity database not reachable yet (…); retrying in 2000 ms*.

To try it locally with Docker (the repository's compose file runs PostgreSQL 18 on port 5432, or `DRISHTI_PG_PORT`):

Run these from the Drishti folder (the repository root, or the install folder of a release).

```bash
docker compose -f deploy/docker/compose.data.yaml up -d postgres
docker compose -f deploy/docker/compose.data.yaml exec postgres psql -U drishti -c 'CREATE DATABASE drishti_identity'
DRISHTI_IDENTITY_DB_URL=jdbc:postgresql://localhost:5432/drishti_identity DRISHTI_IDENTITY_DB_USER=drishti \
DRISHTI_IDENTITY_DB_PASSWORD=drishti java -jar lib/drishti-server-*.jar
```

### 9.3 SQLite: what to know

- The database is one file, plus `-wal` and `-shm` files beside it while the server runs. Keep the folder on local disk, not a network
  share.
- Readers never wait. Writers take turns: SQLite allows one writer at a time, and Drishti's writes (a sign-in, a saved workspace) take
  milliseconds.
- For more than one server sharing the same users, use PostgreSQL.

### 9.4 Back it up

| Database | Command |
|---|---|
| SQLite (safe while running) | `sqlite3 data/identity/drishti.db ".backup 'backup/drishti-$(date +%F).db'"` |
| PostgreSQL | `pg_dump -Fc -h db.bank.example -U drishti drishti > drishti-$(date +%F).dump` |

To restore SQLite, stop the server and copy the backup over `drishti.db`. For PostgreSQL, use `pg_restore -d drishti --clean
drishti-….dump`. Keep `data/` out of version control (it is git-ignored). Drishti has no back door: if every admin's password is forgotten,
restore the database from a backup.

## 10. Rules Drishti enforces

| Rule | Default | Setting (`drishti.identity.*`) |
|---|---|---|
| Hashing | PBKDF2-HMAC-SHA256, 240,000 iterations, 16-byte salt, constant-time check | `iterations` |
| Password policy | at least 10 characters, letters and digits, not the user name | `min-password-length` |
| Lockout | 5 failed sign-ins lock the account for 15 minutes; an admin's password reset unlocks it | `max-failed-attempts`, `lockout` |
| Unknown users | answered like a wrong password, in the same time | — |
| Forced password change | off; an admin can tick it per user. Until it is done the console answers only the account page, the change and sign-out (other pages lead back to *My account*; console API calls `403 DRS-6010`) | `force-password-change-on-create`, `force-password-change-on-reset` |
| Sessions follow the user | each console sign-in is a session the server keeps; the console checks it, the user's roles and enabled flag per request (reusing an answer for the console's `auth.recheck_seconds`, 10). Disabling, deleting or resetting the password of a user signs them out everywhere (enabling them again does not bring a session back); a role change applies within those seconds, at once on every worker process of the console you used. Sign-out ends the session on the server | console `auth.recheck_seconds` |
| Development admin | created when the database has no users; never under the production profile, which refuses the built-in name and password | `seed-admin` (off by default in production), `seed-username`, `seed-password`, `seed-roles` |
| Demo users | `drishti-dev-risk`, `drishti-dev-genomics`, `drishti-dev-market`, created beside the development admin under the demo profile only | `seed-demo-users` |
| Last admin | there is always one enabled admin: it cannot be disabled, demoted or deleted | — |
| Self-protection | nobody can disable or delete their own account | — |
| Database | SQLite file | `database-url` (`DRISHTI_IDENTITY_DB_URL`), `database-user`, `database-password`, `database-pool-size` (unset: `drishti.db.pool.size`, 20, on PostgreSQL; 8 on SQLite) |

### 10.1 What the console keeps of each person

The console keeps a few answers for a moment so pages draw quickly. Nothing it keeps for one person is served to another:

- **One key for everything read as a person**: the server of the request, the user, their roles (sorted), the as-of date and its known-at,
  and the lens the page is seen through. The same person on two servers, or with a role changed, is someone else to every cache. Each store
  is bounded and drops the least recently used entry, never everyone's.
- **Calc's permission, BI's powers and the sign-in check** follow the person's change stamp, so a role change made through one worker
  process is seen at once by the others.
- **Responses**: every page and answer is sent `Cache-Control: private, no-store` (the static files excepted), so neither a shared proxy nor
  the browser's back button shows a person's page after they sign out.
- **The browser**: what pages keep in this browser (the VaR basket, Calc's drafts and history, column filters, table sizes, the capacity
  plan, BI Studio's tabs and drafts, the controls' choices) is named per person, and **Sign out** clears it (`Clear-Site-Data: "storage"`).
- **Links that change something** (open or leave a lens, choose Home's landing) are posted; opening their address shows a form, so a
  crawler, a prefetch or another site's link changes nothing.
- **The server connection** keeps no cookie a server sets: who calls is said by each call's own headers.
- **SQL over a BI extract** runs in four shared worker threads, at most two of them one person's at a time.

### 10.2 The other Admin pages

The pages for running the server (Admin → Connectors, Health, Caches, Capacity, Loads, Lakes, Cluster, Packs) are in
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md); for example Admin → Capacity in
[section 14.6](OPERATIONS_GUIDE.md#146-admin--capacity-today-against-the-plan). Admin → Functions and Admin → Engines are sections 20 and 21 below; modules (no page: a file, a setting or the API) are section 22.

## 11. The users and roles API

Every call is under `/api/v1`. Anything under `/admin` needs a signed-in administrator (the console) and is never open to a personal API
token. With security off (local development) the examples work as they are; with security on, add `-H "Authorization: Bearer <token>"`.

```bash
# Define a role
curl -s -X PUT localhost:18480/api/v1/admin/role-definitions/credit-analyst \
  -H 'Content-Type: application/json' \
  -d '{"description":"Reads counterparties and credit curves","kinds":["counterparty","credit-curve"]}'
# → {"name":"credit-analyst","kinds":["counterparty","credit-curve"],"raw":false,…,"builtIn":false,"updatedBy":"…"}

# Create a user holding it, with two packs
curl -s -X POST localhost:18480/api/v1/admin/users -H 'Content-Type: application/json' \
  -d '{"username":"priya","displayName":"Priya Raman","desk":"Credit","roles":["credit-analyst"],
       "packs":["core","counterparty-risk"],"password":"credit-desk-2026"}'

# Every role, with how many people hold it
curl -s localhost:18480/api/v1/admin/role-definitions
# → [{"name":"admin","kinds":["*"],"builtIn":true,"users":1,…},{"name":"credit-analyst",…,"users":1}]

# What happened to priya
curl -s 'localhost:18480/api/v1/admin/audit?subject=priya&limit=20'
```

| Method | Path | Does |
|---|---|---|
| `POST` | `/auth/login` | verify a sign-in (the console's service token only) |
| `POST` · `GET` · `DELETE` | `/auth/sessions[/{id}]` | open, check and end console sign-in sessions (the console's service token only) |
| `GET` | `/auth/me` | the caller's profile |
| `POST` | `/auth/password` `{current, next}` | change your own password |
| `GET` / `POST` | `/admin/users[?q=]` | list or search / create |
| `GET` / `PUT` / `DELETE` | `/admin/users/{u}` | read / update profile, roles and packs / delete |
| `POST` | `/admin/users/{u}/enabled` `{enabled}` | enable or disable |
| `POST` | `/admin/users/{u}/password` `{password}` | reset a password (also clears a lockout) |
| `GET` | `/admin/roles` | every role name a user may be given |
| `GET` | `/admin/role-definitions` | every role: kinds, powers (`raw`, `author`, `approve`, `admin`, `calc`, `layout`, `collaborate`, `compliance`, and the `bi` list), built-in or not, holders |
| `GET` / `PUT` / `DELETE` | `/admin/role-definitions/{name}` | read / create or replace / delete a role you defined (the body: `description`, `kinds`, the powers as booleans, `bi` as a list; a `bi` entry that is not one of the four powers is `422 DRS-9003`; leave `bi` out to keep the role's) |
| `GET` | `/admin/audit?limit=&subject=` | audit events, newest first; `subject` matches who did it or to whom |
| `GET` | `/admin/status` | the default-password warning, user count, installed packs |
| `GET` | `/admin/access?user=&action=&kind=&id=&from=&to=&limit=` | the access log ([section 8](#8-who-looked-at-what-the-access-log)) |

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-5001` | 400 | bad request (for example a role name with capitals, or no kinds) |
| `DRS-5002` | 403 | not allowed (not an admin, or a kind none of your roles opens) |
| `DRS-6001` | 404 | no such user |
| `DRS-6002` | 409 | the user already exists |
| `DRS-6003` | 422 | weak password |
| `DRS-6004` | 401 | bad credentials |
| `DRS-6005` | 423 | locked after repeated failures |
| `DRS-6006` | 409 | that would leave no enabled admin |
| `DRS-6007` | 422 | invalid user data (name, email, or a role that does not exist) |
| `DRS-6008` | 404 | no such role |
| `DRS-6009` | 409 | the role is held by someone; take it away from them first |
| `DRS-6010` | 403 | (console) the user must choose a new password on *My account* first |

## 12. What a lens is

A **lens** is a **workspace**: Drishti arranged for one kind of person. It sets the starting Home (landings, cards, chips), which menus
and menu items come first, the command line's placeholder and the default screens. It **never hides anything**: every command, page,
menu item, Help page, Admin page and BI dataset the running distribution has stays there, inside a lens or not. A person opens one for
their own session (*Open Drishti Risk*, [section 14](#14-offer-lenses-to-people)); the distribution may name one as everyone's starting
workspace (`lenses.default`).

What exists is not a lens's to say. Three layers of switches, each with one job ([section 12.3](#123-the-three-layers-of-switches)):
the **distribution** says what exists, the **module switch** whether a module is on at this site, the **engine switch** whether a compute
engine runs on this server. What a person may read is decided by their roles, packs and masks (sections 3 and 4).

### 12.1 Modules, functions and lenses

Every lens belongs to a **module** and bundles **functions**:

```
licence  →  modules on  →  lenses offered  →  functions this person may use  →  rows and columns inside
```

- **The module** decides whether the lens exists here. A lens names the modules it needs and is offered only while they are all on
  (installed, licensed and enabled: [section 22](#22-modules-on-or-off)). A module that is off offers none of its lenses
  and none of its packs.
- **The functions** are the command words the lens bundles (`VAR`, `TRD`, `CRV`, `ECON` ...). A card, chip, menu item or command whose
  function is off for the person (its module off, or an administrator's switch on Functions: [section 20](#20-functions-func-every-command-and-turning-one-on-or-off))
  is not shown.
- **The lens** arranges the experience: the top menus it puts first and their items' order, Home (landings, cards, chips), the
  placeholder, the phone display. It loads no pack, presets no feature and hides nothing.

### 12.2 Where a lens is defined

| Where | What it holds |
|---|---|
| **The registry** (`META-INF/drishti/lenses.yaml` in each module's jar: [the banking module's](../../modules/banking/java/banking-service/src/main/resources/META-INF/drishti/lenses.yaml), [the core server's](../../kernel/drishti-server/src/main/resources/META-INF/drishti/lenses.yaml)) | the lens's id and title, the modules it needs, the functions it bundles, its Home template, its Home **cards** (`cards:`) and **chips** (`chips:`), and for a lens with no file its menus and Help pages; a site's `config/access/lenses.yaml` adds a lens or replaces one by its id |
| **The lens file** (`config/lenses/<module>/<lens>.yaml`) | the arrangement: its title and description, the menus it puts first (`menus:`), Home (`home:`), the phone display (`phone:`) and its landings' own settings (`trading:`, `quality:`) |

The shipped lenses:

| Lens | Id | Module | File | Modules it needs | Packs, by module |
|---|---|---|---|---|---|
| **Drishti Risk** | `risk` | banking | [`config/lenses/banking/risk.yaml`](../../config/lenses/banking/risk.yaml) | banking | core: core · banking: market-data, trading, counterparty-risk, market-risk |
| **Drishti Trading** | `trading` | banking | [`config/lenses/banking/trading.yaml`](../../config/lenses/banking/trading.yaml) | banking | core: core · banking: market-data, trading, market-risk, counterparty-risk |
| **Drishti Market** | `market` | banking | [`config/lenses/banking/market.yaml`](../../config/lenses/banking/market.yaml) | capitalmarkets, banking | core: core · banking: market-data |
| **Drishti World** | `world` | economy | [`config/lenses/economy/world.yaml`](../../config/lenses/economy/world.yaml) | economy (capitalmarkets for its company, filing and fund parts) | economy: world-economy, news-and-markets, official-news, gdelt-events · capitalmarkets: us-equities, funds · core: gleif |
| **Public sector** | `publicsector` | economy | none: the registry entry | economy | the public sector's six packs ([section 16.5](#165-public-sector)) |

The registry also lists lenses that have no lens file or Home yet (CRO, MR cube, CCR cube, CIO, Oversight); they are not offered to people.

**One folder.** The lens files are under `config/lenses` (the server's `drishti.lenses-dir`, default `<config>/lenses`; the console reads none: the server sends each lens in its installation answer): a file sits in its module's
folder (`config/lenses/<module>/<lens>.yaml`), or in the folder itself for a lens of no module; only the console reads them. The feature
presets a distribution may name are in [`config/distributions/kernel.yaml`](../../config/distributions/kernel.yaml) (`features:`). The
full product's packs are the kernel's (`kernel.yaml` `kernel.packs`) and every module's that is on, less those a module's manifest marks
`packs.off-by-default` (capital markets' us-equities and funds, which read a lake built first); a distribution's are its modules'. The
lenses offered follow the modules the same way (`drishti.lenses=default`).

### 12.3 The three layers of switches

Each switch has one job and one home:

| Layer | Its one job | Where | Who changes it |
|---|---|---|---|
| **Distribution** | what exists: the modules (their packs, commands, menus, Help, engines), the brand, the feature presets | `config/distributions/drishti.<name>.yaml` (`features:` presets through `kernel.yaml`), chosen at start ([section 24](#24-white-label-distributions)) | the product owner or licensee; a restart |
| **Module switch** | whether a module the distribution has is on at this site | `config/modules/<module>.yaml`, `DRISHTI_MODULES_<MODULE>_ENABLED`, Admin → Modules ([section 22](#22-modules-on-or-off)) | an administrator |
| **Engine switch** | whether a compute engine (VaR, exposure, FRTB-SA, SIMM ...) runs on this server | `config/engines/<id>.yaml`, Admin → Engines ([section 21](#21-compute-engines-on-or-off)) | an administrator, per server |

An engine whose module the distribution leaves out does not exist there: it shows *not installed* (Admin → Engines, Health), never
**off** or **on**, and its switch is not read. Where an engine of a module the distribution holds runs is its own switch. On top, an administrator's function switch (Functions, [section 20](#20-functions-func-every-command-and-turning-one-on-or-off))
turns one command off for everyone. A command word is shown when its module exists and is on, its engine runs somewhere in the cluster
and its function is on. A module's menus (its manifest's `console.menus`) are in the top bar only while the module is in the distribution,
installed, loaded and on; they go and come back at the next page when that changes. A lens changes none of this.

## 13. Run a whole instance as Drishti Risk or Drishti Finance

Name a **distribution** ([section 24](#24-white-label-distributions)): `python tools/drishti.py start all --distribution risk`, `DRISHTI_DISTRIBUTION=risk` or
`drishti.distribution: risk`; the console follows the server. Drishti Risk has the banking and market-data modules, so economy and
capital-markets commands, menus and Help do not exist there; its starting workspace is the Risk lens. A lens file is never an instance's
filter: the old setting `drishti.edition` (`DRISHTI_EDITION`) is no longer read, and Health names it when a site still sets it.

## 14. Offer lenses to people

`drishti.lenses` (`DRISHTI_LENSES`) lists the lenses a person may open (a distribution offers its own: `lenses.offered`). Set it in the server's
configuration and restart the server:

```yaml
drishti:
  lenses: default                      # every lens of the modules that are on; DRISHTI_LENSES= (empty): no lens link, the full product only
  # lenses: risk                       # one: "Open Drishti Risk"
  # lenses: risk,world                 # two: "Open Drishti Risk" and "Open Drishti World"
```

Each person then sees **Open Drishti Risk** (and the others listed) in the Views menu and on Home, each with its lens's description.
Opening one arranges, on every page and every server of the cluster, their Home (the lens's landings, cards and chips) and the menus
(the lens's first, then the rest); every command, page and dataset stays where it was. **Leave Drishti Risk** comes back (to the
distribution's starting workspace, which has no Leave link of its own). Inside one lens the menu shows
**Leave …** first, then **Switch to …** each of the others: switching needs no leaving first. The default, `default`,
offers every lens of the modules that are on: a module switched off takes its lenses with it (genomics off: no Research), and a lens
file may say `offered-by-default: false` (Drishti Finance's Markets lens, which its distribution names). A lens you name that cannot be
offered (its module off, or no lens of that name) never stops the start: it is left out, the log has an ERROR naming it, why and the
fix, and **Admin → Health** shows it in red. The 3-node stack uses the default too.

- **Kept per person.** The lens is the person's `lens` setting (the server's user store), so a refresh, another browser or another server
  keeps it. Other people are unaffected. The server's answers are the same inside a lens or not.
- **Only lenses the registry offers.** A name in `drishti.lenses` whose registry lens is not offered (a module it needs is off) is dropped:
  no link, and `/lens/<name>` answers `DRS-4023`.
- **Build and Studio are off inside a lens.** A page names the Sutra that drew it ("How this view was built", About this page, a pack's
  page) **without** a link to the designer; in the full product the link opens it.
- **Who sees the link.** `lens.roles` in the console's configuration (who sees the link, which roles start in the lens) is read and is the
  hook for granting lenses per role.

To turn lenses off, set `DRISHTI_LENSES=` (empty) and restart: people inside one see the full product at their next page.

## 15. Home: landings, cards and chips

**Landings.** Each shipped lens with a lens file has three Home pages, each filled from the engines' and packs' own answers. The links at
the top of Home switch between them, and **the choice is remembered** in the person's settings (`homeLanding`, read back by the server's
`/api/v1/me/settings`), so it follows them to every server and browser. A landing the current lens does not offer gives that lens's
default; a landing id two lenses share (`desk`, `explorer`, `map`) follows the person from one to the other. "The classic Home"
(`?landing=today`) opens the product's Home with the lens's sections.

The lens file sets which landings are offered, the default, the page that draws them and their titles:

```yaml
home:
  landing: desk                    # the default
  landings: [desk, command, map]
  template: home/risk_landing.html # a home/<name>_landing.html page; a file that names none gets Drishti Risk's
  titles: { desk: Risk desk, command: Command first, map: Risk map }
  landing-by-role: {}              # e.g. { risk-manager: map, trader: command }
  sections: { stats: off, grammar: off, pipeline: off, capabilities: off, views: off }   # the product's Home sections
```

`landing-by-role: {role: landing}`, when configured, wins over a person's remembered choice (Drishti Trading's own trading role sits below
the choice: [section 16.2](#162-drishti-trading)).

**Cards and chips** come from the lens's registry entry (`cards:`, each `{title, cmd, icon}`; `chips:`, command lines). A card or chip is
shown only while its function is on for the person, so a lens whose capital markets functions are off loses `USEQ AAPL` and `FUND SPY`
without a change to any file. A site replaces them in the console's `edition:` block (`home.cards`, `home.chips`:
[section 17](#17-customise-a-lens-without-forking-it-and-white-label-it)).

The landings are not Sutras: a Sutra lays out one entity's view, and these pages span the engines with no entity. Each lens's landing is
one template under its module's console plugin (`home/<lens>_landing.html`), drawn in the terminal look with the theme's own variables in
every theme, one column on a phone. A figure the data cannot give is left out or labelled, never invented.

### 15.1 Configuring role Homes

Everyone lands on **Your Home** (`/home`) after signing in, unless they asked for another page or chose a landing of their own in their
settings. What it holds is configured in three places, all files, none code:

| What | Where | Says |
|---|---|---|
| Which Home a role has | the role's `home` field (Admin, Roles), preset by the distribution | the Home name (`risk-manager`, `trader`, `risk-officer`, `credit-officer`, `operations`, `quant`, `economist`, `genomicist`); the Home files are `config/homes/<module>/<home>.home.yaml` ([ROLE_HOME_SUTRAS.md](../architecture/ROLE_HOME_SUTRAS.md)) |
| Which cards exist and for whom | a module's console `plugin.yaml`, `home: role-cards:`; the console's own in `drishti-console/config/home-cards.yaml` | a card's `id`, `title`, `kind` (`metric`, `bars`, `table`, `scatter`), the home `table` it reads, `pick`, `columns`, `open`, `explain` and `rank` |
| Which layout | `layouts:` in `home-cards.yaml` | `risk-manager: cockpit`; any Home not named is calm |

A card is a declaration over a **home table** (`home_kpis`, `home_desk_limits`, `home_exposure`, `home_changes`, `home_loads`, ...): the server serves each
table for the person, so the figures are theirs and nothing is computed in the console.

```yaml
home:
  role-cards:
    - id: limits                      # lower-case words; the person's pins and hidden cards are kept under this id
      title: Limit utilisation by desk
      kind: bars                      # metric: one figure of a table row; bars: use against a limit; table: columns of rows
      table: home_desk_limits
      name: DESK
      value: VAR
      limit: LIMIT
      open: VLIM                      # the Open button's command
      explain: { kind: var, measure: var, scope: BANK }   # the Explain button (optional)
      rank: { risk-manager: 80, risk-officer: 85, trader: 50 }   # weight per role Home; "*" is any Home
```

**Ranking.** A card is offered when one of the person's Homes has a weight for it (or it has a `*` weight); the highest weight over all their
Homes puts it first, so a person with several roles sees the cards of every one ranked together. A card of a module that is switched off is
not offered. A metric card whose figure the server does not have (its engine is off) is left out; a table card says its own empty words
(`empty:`, or `always: true` to keep it on the page). A `light: [4, 9]` on a metric colours it green up to 4, amber up to 9, red above.

**Needs you** reads three things that need no configuration: the person's raised alerts, their approvals inbox and the data loads that are
late, missing or failed; the high exceptions of the Home's measures file (`exceptions:`) join them.

**Per person.** Pins, hidden cards, order, snoozes, the last visit (`lastVisit`, and `prevVisit`, the visit before it, which the digest
reads against), and the role they **View as** (`viewAs`, one of their own Homes: the console refuses a Home they are not offered) are the
person's `homeCards` setting (`PATCH /api/v1/me/settings`); a card an administrator removes simply stops being drawn.

**Since your last visit.** The digest panel reads the home table `home_digest` from the person's last visit (`GET /api/v1/homes/{home}/tables/home_digest?since=<UTC time>`;
with no `since` the Home's reference cut-off): the change events of the kinds the Home's measures file lists (`events:`), and each followed
measure (`follow:`) that moved since the stored snapshot of that day, for the subjects the person may see; the console adds the alerts fired since.

**A module's own Home (genomics).** A module brings figures, tables and Needs-you items to a Home with no kernel code: a `MeasureSource` bean
(engine `genomics`: `SAMPLES`, `VARIANTS`, `HITS`, and the variants and samples of each cohort, which the Home's `follow:` compares with the cut-off
snapshot), `HomeTable` beans (a table names the Homes it is served to by `homes()`, so a person who is not offered that Home never reads it),
and the console `plugin.yaml`:

```yaml
home:
  role-cards:                         # the genomics cards; rank names the genomicist Home only, so no other role is offered them
    - {id: genomics-gwas, title: Top associations, kind: table, table: genomics_gwas_top, rank: {genomicist: 90}}
    - {id: genomics-manhattan, title: Association map, kind: scatter, table: genomics_manhattan, x: X, value: Y, name: GENE, group: CHROM, rank: {genomicist: 85}}
  needs:                              # what the module adds to Needs you: each row of the table is one item
    - {table: genomics_needs, what: WHAT, detail: DETAIL, tone: bad, open: LOADS, homes: [genomicist]}
```

The module's tables are `genomics_gwas_top` and `genomics_manhattan` (the association table of the genomics-showcase pack), `genomics_clinvar`
(the ClinVar feed's table, symbolic name clinvar-variant: `GENE`, `VARIANT_ID`, `SIGNIFICANCE`, `UPDATED`, `BUSINESS_DATE`, for the genes the cohorts carry), `genomics_loads` (the ClinVar
feed, the Ensembl feed's table, ensembl-gene, and each VCF file read) and `genomics_needs` (a VCF that failed validation, with its first problem). The lake
tables are read by their symbolic names, never by a path (the site's bindings say where they live); a table that is not loaded shows as *not loaded*
and its card says so in its own words. The feed rows are read at most once a minute. A role opens the Home through `drishti.homes.roles`
(`genomicist` and `geneticist` open `genomicist`; add your own roles there, or in Admin, Roles); the Home files are `config/homes/genomics/`.

## 16. The shipped lenses

### 16.1 Drishti Risk

**Drishti Risk** (`risk`, banking module) is a one-stop shop to manage risk: VaR and exposure first; liquidity, climate and more join as
more lines in the same file.

| Area | Put first | Left to the other menus (still there) |
|---|---|---|
| **Commands** | VaR (`VAR` and its page with the basket what-if; `VRES`, `VOFF`, `VLIM`, `PORT`, `POS`, `PNLV`, `SPV`, `FXS`, `SSET`); exposure (`EXP` and its page; `XRUN`, `XPATH`, `XOFC`, `AGR`, `CSA`, `NSET`, `LIM`, `COLL`, `MC`); the derived totals `NSMTM`, `CPMTM`; reference data `CPTY`, `GRP`, `LE`, `BOOK`, `DESK`, `TRDR`, `CCP`; trades `TRD`, `CTRD`, `PTRD`, `LTRD`; `FX`; the words `BI`, `FUNC`, `WHAT`, `DATAMAP`, `DATA` (Export data), `SMAT` (the scenario matrix), `PLAY` (the VaR Playground) | stress (`STR`, `SCN`, `SSPNL`), `PNL`, `TVAL`, `CVA`, `SACCR`, the curves and vol surfaces, `ISS`, `CAL`, `CLR`; the words `FORM`, `RUPAKA` |
| **Menus** | Views (Terminal, Search, Functions, Data map, Export data, Reports, Workspaces, Monitors, Alerts), Risk (the VaR, exposure and other engines' pages, each while its engine runs), BI, Admin, Help (Help centre, Knowledge, Install and run, About); footer: Help, About, Release notes | Build, Forms |

The command line suggests
`VAR BANK`, `VRES DESK:RATES`, `EXP CPTY:CP-ALDERSHOT`; the registry's chips add `VAR DESK RATES`, `EXP BANK`, `VLIM` and `VOFF`, and its
cards open the bank's and the rates desk's VaR, VaR results by desk and a counterparty's exposure.

| Landing | Id | What leads |
|---|---|---|
| **Risk desk** (the default) | `desk` | tiles: VaR and ES of the bank, the backtest, exposure EPE and peak PFE, limits in breach, counterparties and netting sets; VaR by desk with limit use; exposure by counterparty against its credit limit; quick cards |
| **Command first** | `command` | a big command line, the lens's chips, a status strip, the person's VaR baskets, their monitors, links to learn |
| **Risk map** | `map` | the hierarchy with VaR and limit use per node and a switch between the hierarchies the engine holds (`home.hierarchies` names the one it opens on); a limit-use heat map; Today: intraday batches, official records, breaches |

Every figure is the engines' own answer through the console's `/api/var/...` and `/api/exposure/...` (`scope`, `scope/history`, `tree`,
`intraday/streams`, `official`, `baskets`). The map shows VaR and limit use per node (exposure is netted per netting set across desks, so
there is no EPE per VaR node), and the heat map shows each node's VaR limit use; the Today feed names the date an official record captured.

### 16.2 Drishti Trading

**Drishti Trading** (`trading`, banking module) is for traders and desk heads: my books, my P&L today, my VaR and limits, today's trades,
my counterparties, and whether a new trade fits. It loads the same five packs as Drishti Risk and shows the blotter, books and positions,
risk on my book, counterparties and the market data a trader reads; regulatory, stress, collateral operations and macro economics are left
out.

| Area | Put first | Left to the other menus (still there) |
|---|---|---|
| **Commands** | the blotter `TRD`, `CTRD`, `PTRD`, `LTRD`, `ITRD`; books and positions `BOOK`, `DESK`, `TRDR`, `POS`; risk on my book `VLIM`, `VRES`, `TVAL`, `PNLV`, `PORT`, `DPNL`, `PNL`, `SENS`; counterparties `CPTY`, `GRP`, `NSET`, `NSMTM`, `CPMTM`, `LIM`, `EXP`; market data `CRV`, `REPO`, `FIX`, `IRV`, `CPV`, `FX`, `FXF`, `FXV`, `EQ`, `EQX`, `CMD`, `CMDC`, `BND`, `CDS`; the words `VAR`, `EXP`, `BI`, `FUNC`, `WHAT`, `DATA`, `SMAT`, `PLAY` | regulatory (`SACCR`, `CVA`), stress (`SCN`, `STR`, `SSPNL`), collateral operations (`COLL`, `MC`), the exposure engine's runs, paths and official records, VaR's scenario inputs, macro economics; the words `DATAMAP`, `FORM`, `RUPAKA` |
| **Menus** | Views (Terminal, Search, Functions, Export data, Reports, Workspaces, Monitors, Alerts); Engines; one **Markets** menu (rates, vols, FX, credit, equities and commodities: `menus.markets`); Blotter, Positions, Risk (with the engines' pages), Counterparties; Help. BI and Admin by command only (`BI <GO>`; `ADMIN <GO>` for administrators) | Forms, Data map, Build; BI and Admin in the bar |

`SMAT BOOK BOOK-RATES-1 20` opens the scenario matrix: the book's scenarios across its last 20 business dates
([VAR_GUIDE.md section 3.9](VAR_GUIDE.md#39-the-scenario-matrix-smat)).

| Landing (green accent) | Id | What leads |
|---|---|---|
| **Trader desk** (the default; traders) | `trader` | P&L today (MtM change since the business day before, over my books), my VaR (intraday when updated) and the highest limit use of my books, DV01 (when the valuations carry it), trades today (new, amended, cancelled); **Check a new trade**; today's blotter; top positions by VaR contribution; quick commands for my trader and book |
| **Desk head** (desk heads) | `desk` | desk P&L today, desk VaR and its limit use, breaches, books near their limit, the largest counterparty's share of the desk's positive MtM; limit use by book over the last 5 business dates; by trader (P&L, VaR, trades); P&L against VaR over 60 days (official history only); overnight movers |
| **Explorer** | `explorer` | a big command line, the chips, the commands grouped (blotter, books and positions, risk on my book, counterparties, market data), the person's recent Trading commands |

The figures are worked out on the server by the trading module (`GET /api/v1/trading/landing/{role,me,valuations,blotter,risk,limits,pnl-var,traders}`,
`POST /api/v1/trading/landing/check`, as the person; [API_REFERENCE.md](API_REFERENCE.md)); the console passes them on, read-only, as `/api/home/trading/{part}`
and `POST /api/home/trading/check` (`404 DRS-4023` unless the person's lens shows `TRD`; the check also needs the words `VAR` and `EXP`),
cached two minutes per person, date and scope.

**"My": who a person is.** The landings open on **the person's own** books or desk. Who that is, in order:

1. **Configuration.** `trading.people` in the lens file, with the server's own `drishti.trading.people` (its `application.local.yaml`)
   added on top (a name in both: the server's). User names are compared ignoring case.

   ```yaml
   trading:
     people:
       a.shah: { trader: TRDR-ASHAH }                      # a trader: their trader record's books; opens on the Trader desk
       t.brennan: { desk: DESK-COMM, role: desk-head }      # a desk head: the desk's books; opens on Desk head
   ```

2. **A trader record** (`trading.match-records`, on): a `trader` whose id is `TRDR-<USER>` (case, dots and dashes ignored) or whose `NAME`
   is the person's display name, read with their own search.
3. **Their own choice**: a person with neither sees a small **View as** picker (the traders and desks their search returns); the choice is
   kept in their settings (`tradingView`: `trader:<id>` or `desk:<id>`, validated by the server's settings API).
4. An **administrator** always sees the picker, and their choice wins over a mapping; a mapped person who is not an administrator cannot
   repoint themselves (`/home/trading/view-as` refuses with `DRS-4023`).

**Which landing opens first** follows the trading role: `trading.landing-by-role: {desk-head: desk, trader: trader}` (a View as desk counts
as a desk head). It sits **below the person's own choice**, so a desk head who picks the Trader desk keeps it; `home.landing-by-role` still
wins over both when configured. **It never widens access:** every figure is read through the existing routes as the person, with their
roles, masks, lens and date; scoping only chooses which books the landing asks about. A role without `raw` sees `TRADER_ID` masked, so the
Desk head's figures by trader say so instead of guessing. Other settings (all in `trading:`): `limit-days` (5), `pnl-var-days` (60),
`max-rows` (20,000 valuations a date before the figures say **partial**), `live-statuses` (`NEW`, `VALIDATED`, `AMENDED`).

**How the figures are made.**

- **P&L today**: the MtM change of every trade of my books valued on both the business date and the business day before (`TVAL` on both
  dates; the person's date box moves both), summed. The valuations' own `PNL_1D` is a different figure and is not used.
- **My VaR**: the VaR engine's figure of the union of my books (`BOOK:A + BOOK:B`), labelled **intraday** with the trades updated and the
  time when the engine applied intraday updates. **Limit use**: the highest of my books' VaR ÷ its limit, with the book and limit named.
- **Totals** (P&L today, MtM, DV01, by book, by trader, the largest counterparty's share) are the server's pivot engine's
  (`POST /api/v1/pivot/inline`); with the pivot engine off the tiles show no figure and say why.
- **Trades today**: the trades of my books whose `SOURCE_UPDATE_TIME` is on the business date: **cancelled** (`TRADE_STATUS` CANCELLED),
  **new** (`TRADE_ACTION` NEW, or `TRADE_DATE` that day), else **amended**. When that search does not answer in time on a large lake
  (`DRS-1004`), the events come from the two days' valuations, with no time of day, and the tile says so.
- **The day**: when the lake's latest valuations are older than the business date, the figures are of that day against the business day
  before it, and the page says "the latest valuations are of …".
- **Limit use by book**: each cell VaR ÷ limit from that date's **official VaR record**; a date without a record shows "—" and is named
  under the grid.
- **By trader**: each trader's component VaR over every trade of the desk, adding up to the desk's VaR. With `TRADER_ID` masked the P&L and
  VaR are "—" and the card says why.
- **P&L against VaR**: the desk's official history, the last `pnl-var-days`, exceptions circled; a replayed history is scenario P&L, not a
  day's, so then the card is left out with that note.
- **Largest counterparty**: its share of the desk's positive MtM over live trades (named so; a desk's share of each counterparty's netted,
  collateralised exposure is not a figure the exposure engine serves).

Left out or labelled, never invented: the DV01 tile when no live trade holds a DV01; P&L today, by book and by trader, and the movers
without valuations on the day before; P&L against VaR without official history; by-trader P&L and VaR for a role that sees `TRADER_ID`
masked; a book's limit use on a date without an official record. A scope larger than `trading.max-rows` is read in part and every figure
says **partial**.

**The new-trade check.** Pick a product (the products of your live trades), the side, a notional (`250m`, `1.5bn`), the counterparty and
the book, then **Check**. The engines price a **copy of a booked trade**: a live trade of the product with a notional in my books (the
counterparty's own first, then the chosen book's, then the largest notional), scaled to the notional (negative when the side differs). One
answer from the two engines, each asked once, as the person:

- **VaR**: the VaR page's what-if on `BOOK:<book>` gives VaR before and after and the book's limit, its use before and after and the status
  after: **fits** (`within`), **near** (from `drishti.risk.limit-near`), **breaches**; no comparable limit: the change only.
- **Counterparty**: the exposure engine's pre-deal check ([EXPOSURE_GUIDE.md section 18.9](EXPOSURE_GUIDE.md#189-the-pre-deal-check)):
  the decision and the counterparty's credit limit before and after: **fits**, **near** (from `drishti.ccr.exposure.limit-near`),
  **breaches** (a decline), or **refer** with the reason. When the copied trade has no paths on the run the check is asked described, the
  engine's conservative proxy, labelled **proxy**, which refers.

A verdict reads: *Fits my VaR · 6.12m → 6.30m · limit use 83% → 85%* and *Counterparty near its limit · peak PFE 401.5m → 409.2m · 97% of
limit*. Both halves are the server's engines and need no compute workers.

### 16.3 Drishti Market

**Drishti Market** (`market`, banking module, needing capitalmarkets too) is the bank's own market data: curves, repo, fixings, FX spot,
forwards and vols, swaption and cap vols, credit curves, bonds, equities, indexes, dividends, inflation, commodities and correlations (the
market-data pack, with the core pack it extends). No trades and no VaR. The market-data lake must be built (`lake demo` builds it).

| Area | Put first | Left to the other menus (still there) |
|---|---|---|
| **Commands** | the pack's: `CRV`, `REPO`, `IRV`, `CPV`, `FIX`, `INF`, `INFC`; `FX`, `FXF`, `FXV`; `CDS`, `BND`; `EQ`, `EQX`, `DIV`, `EQV`, `CMD`, `CMDC`, `CMDV`; `CORR`, `CORRC`, `EQB`; the words `BI`, `FORM`, `FUNC`, `WHAT`, `DATAMAP`, `DATA` | `VAR`, `EXP`, `SMAT`, `PLAY` and every risk and trade command; core's own (`CPTY`, `BOOK`, `CAL`, `ISS` …); `RUPAKA` |
| **Menus** | Views (Terminal, Search, Forms, Functions, Data map, Export data, Reports, Workspaces, Monitors, Alerts), Curves, Volatility, FX, Credit, BI, Admin, Help | Build, the risk engines |

The landings (violet accent) are worked out on the server by the market-data module from the pack's own answers, read as the person,
cached two minutes per person (`GET /api/v1/market-data/landing/{desk,moves,quality}`), and passed on by the console as
`/api/home/market/desk`, `/moves` and `/quality` (`404 DRS-4023` unless the person's lens shows `CRV`).

| Landing | Id | What leads |
|---|---|---|
| **Market desk** (the default) | `desk` | USD OIS 10Y, USD 2s10s, EUR/USD 1M ATM vol, the CDS 5Y median, the data exceptions; the USD curves (today against a week ago when the history has it); the biggest moves in σ of their own history; quick cards for CRV, FXV, IRV and FIX |
| **Explorer** | `explorer` | a big command line, the chips, counts by asset class, the commands grouped by asset class with their counts; the person's recent commands, Knowledge, Data map, Learn |
| **Quality** | `quality` | the checks below, critical first, then warnings, each opening its command; the clean share; a Checks run card |

- **Changes only from real data.** A day-over-day change appears only when the history holds real data for both days; otherwise the tile
  says "no change: no history for the day before".
- **Biggest moves**: each curve's `TEN_Y`, credit curve's `SPREAD_5Y`, FX spot's `MID`, commodity's `FRONT` and index's `LEVEL` over 21
  business days; the latest move in standard deviations of the item's own daily moves when ten or more are held (a "jump" at 3σ or more);
  with less history, the largest moves against the day before.
- **The quality checks**, worst first: **stale** (warning: a document older than the business date by more than `quality.stale-days`
  business days, weekends and the server's holidays skipped); **gaps** (warning: a curve missing a tenor that every other curve of its type
  has, for a type of three or more); **discount factors and forwards** (a discount factor that does not fall with maturity or is outside
  (0, 1]: critical; a negative forward rate: warning); **vol surfaces** (critical: a vol at or below zero, and ATM total variance falling
  with expiry on the FX and equity surfaces). Jumps and a second source are not checks.

The stale threshold and the chips are configuration:

```yaml
edition:
  quality:
    stale-days: 3                  # default 2 (config/lenses/banking/market.yaml)
  home:
    chips: [CRV CRV-USD-OIS, FXV FXV-USDJPY, CDS CDS-SOLARIS, DATAMAP]
```

### 16.4 Drishti World

**Drishti World** (`world`, economy module) is the public-data packs in one product: economies and regions (world-economy), news and quotes
(news-and-markets), official news (official-news: `PRESS`, `PREL`; gdelt-events: `GDELT`), legal entities (gleif), and, while the
**capitalmarkets** module is on, US companies and filings (us-equities) and funds and ETFs (funds). The public-data lakes must be built
(`lake demo` builds them; [WORLD_DATA_GUIDE.md](WORLD_DATA_GUIDE.md) says how each source is refreshed). The IMF figures and Yahoo prices in these
packs are shown inside the bank only; showing Drishti World outside it needs the product owner's decision first. While capitalmarkets is
off, its packs, its functions (`USEQ`, `IDX`, `SIC`, `EDGAR`, `FILING`, `FACT`, `PXH`, `FUND`, `HELD`, `TRACK`), its cards and chips, and
the landings' company, filing and fund parts are gone; the rest of World stands.

| Area | Put first | Left to the other menus (still there) |
|---|---|---|
| **Commands** | the packs' own: `ECON`, `MACRO`, `FCST`, `LABR`, `FISC`, `ECG`, `REGE`, `JUR`, `PARTY`, `OFFC`, `ELEC`, `REGN`, `SOCI`; `TOPIC`, `NEWS`, `CO`, `QUOTE`; `USEQ`, `IDX`, `SIC`, `EDGAR`, `FILING`, `FACT`, `PXH`; `LEI`, `LEIG`, `LEIR`, `LEIC`, `LEIM`; `FUND`, `HELD`, `TRACK`; `PRESS`, `PREL`, `GDELT`; the words `BI`, `FORM`, `FUNC`, `WHAT`, `DATAMAP`, `DATA` | `VAR`, `EXP`, `SMAT`, `PLAY` and every risk command; `RUPAKA` |
| **Menus** | Views (Terminal, Search, Forms, Functions, Data map, Export data, Reports, Workspaces, Monitors, Alerts), Economies, Markets (news and quotes), Companies, BI, Admin, Help | Build, the risk engines |

The landings (teal accent) read one thin read-only route, `/api/home/search`, which runs the server's own search grammar
(`ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12`), refused with `404 DRS-4023` for a command the person's lens does not
show; the server filters again by the lens it is told. Each panel paints when its answer arrives; one refused or empty says so in place.

| Landing | Id | What leads |
|---|---|---|
| **World desk** (the default) | `desk` | World GDP growth (GDP-weighted), quotes up and down in the market snapshot, the biggest mover, new SEC filings, funds and ETFs; economies that moved (the twelve largest, growth against the year before); today's news by topic (`home.topics`); quick cards |
| **Explorer** | `explorer` | a big command line, the chips, the counts of economies, companies, legal entities, funds, filings and topics; the person's recent commands (World's only), Knowledge, Data map, Learn |
| **World map** | `map` | the world by region, income group, G7 or G20, by growth, inflation or debt to GDP (each group GDP-weighted from its economies); US sectors by revenue; the largest funds and the index each tracks; what changed (the latest of each source) |

The desk's topics, the chips and the default landing are configuration, in the file or the console's `edition:` block:

```yaml
edition:
  home:
    topics: [TOPIC-RATES, TOPIC-FED, TOPIC-OIL]            # the desk's news topics (TOPIC ids)
    chips: [ECON USA, USEQ MSFT, FUND QQQ, DATAMAP]          # the Explorer's chips
    landing: map                                            # the default landing
```

### 16.5 Public sector

**Public sector** (`publicsector`, economy module) is development indicators, central banks, national statistics, the OECD, official news
and world events: the packs world-development, central-banks, national-statistics, oecd, official-news and gdelt-events
([WORLD_DATA_GUIDE.md](WORLD_DATA_GUIDE.md), section 2 to turn it on). It is defined by its registry entry alone
([the core server's lenses](../../kernel/drishti-server/src/main/resources/META-INF/drishti/lenses.yaml)):

| Part | As shipped |
|---|---|
| **Modules** | economy |
| **Functions** | `WDI`, `WDIS`, `CBANK`, `CBSER`, `NSTAT`, `OECD`, `OECDS`, `PRESS`, `PREL`, `GDELT` |
| **Menus** | Views, BI, Admin, Help |
| **Cards** | a country's development (`WDI WDI-CAN`), one indicator's history, a central bank, a rate series, Statistics Canada, OECD members by inflation, official press releases, the world's news by tone (GDELT) |
| **Chips** | `WDI WDI-USA`, `CBANK CBANK-FED`, `OECD OECD-USA`, `PRESS PRESS-ECB` |

With the economy module off, all its packs and functions are gone and neither World nor Public sector is offered. It ships no lens file and
no landing template of its own, so it is not run as a whole instance's lens; to do that, or to give it landings, write a lens file for it
in `config/lenses/economy/` ([section 18](#18-make-your-own-lens)).

## 17. Customise a lens without forking it, and white-label it

A lens only arranges, so customising one is editing its file (or a copy: [section 18](#18-make-your-own-lens)): the menus it puts
first, Home's landing, cards and chips, the placeholder. What exists, the brand and the feature presets are the distribution file's
([section 24](#24-white-label-distributions)); a site changes them in its own distribution file, and any feature preset can be overridden by its own
setting (for example `DRISHTI_LABS_CALC=true` turns Calc, a Labs feature, on). A site's `config/access/lenses.yaml` adds a lens to the
registry or replaces a shipped one by its id (its modules, functions, cards and chips).

### 17.1 On a phone

A lens's `phone:` block sets and caps the phone tier of panels over every Sutra, after the site's `ui.phone`
([BUILD_GUIDE.md](BUILD_GUIDE.md#32-designing-for-phones), [USER_GUIDE.md, On a phone](USER_GUIDE.md#on-a-phone));
`max:` is a cap no Sutra can exceed. `pages:` does the same for the parts of the VaR and exposure pages, and `home.phone` for the lens's
landing. Drishti Risk ships one rule: a VaR contribution panel waits under *More on this page*.

```yaml
phone:
  rules:
    - { title: "*contribution*", tier: more }       # keys: panel, kind, title, sutra, entity (globs); tier or max
    - { kind: pivot, max: desk }
  pages:
    var: { hist: glance, whatif: desk }              # parts: tiles, method, path-dist, path-tail, path-series, path-table, hist,
                                                     # contrib, captures, ccy, tree, prov, whatif, basket
home:
  phone: { table: more }                             # parts: hero, tiles, quick, card-1, table, chart, map, feed
```

On a phone the lens's subject menus fold into one menu named after it (its title without the product's name: **Trading**).

### 17.2 White-labelling checklist

**The distribution file's `brand:` is the one home of the product's name, tagline, owner, copyright and notice (section 24), on the server and the
console alike.** A site that keeps its own `drishti.branding.*`, `DRISHTI_PRODUCT`, `ui.product`, `ui.tagline`, `ui.owner`, `ui.copyright` or `ui.notice` is
not read: Health names each one as no longer read. A lens keeps Drishti's name and eye by default. A licensee who wants its own name writes its own
distribution file (`--distribution=<path>`); the rest of the look is set on the console:

| What | Server | Console |
|---|---|---|
| Product name, tagline, owner, copyright, notice | the distribution file's `brand:` | the same file |
| Logo (instead of the eye) | | `ui.logo`: an image under `/static/` (a site file in `web/static/`) or a `data:` URL |
| Browser icon, home-screen icon | | `ui.favicon`, `ui.apple_touch_icon`; the web manifest (`/manifest.webmanifest`) follows them and the product name |
| Colours | | a theme (`ui.default_theme`) or the UI kit's `brand.yaml` (`drishti.ui-kit.site-dir`) |
| CLI name | | `drishti.py` keeps its name; wrap it in a script of yours if needed |
| Mail | `drishti.collab.email.*` sender and subject prefix | |

`{product}` in Help titles and summaries follows the product name. Check with `drishti-console/tests/test_branding_guard.py`: it renders the
console with another product name and fails on any visible "Drishti" left.

## 18. Make your own lens

Copy a shipped file into the folder of the module it belongs to (or into `config/lenses/` itself for a lens of no module) and edit it:

```bash
cp config/lenses/banking/risk.yaml config/lenses/banking/my-desk.yaml
```

| Key | What it does |
|---|---|
| `edition`, `title`, `description` | the lens's name (the file's), the product title shown in About, the top bar and the Domains menu, and its one line |
| `menus.top` | the top menus it puts first, by id, in order; the others follow (a subject menu it names is drawn too where the distribution names no list) |
| `menus.<menu>` | the items it puts first per menu (`views`, `bi`, `build`, `admin`, `help`, `footer`, or a module's menu), by the ids in `drishti-console/config/sitemap.yaml` and the modules' manifests (`console.menus`); nothing is left out |
| `menus.placeholder` | the command line's placeholder |
| `home.landing`, `home.landings`, `home.template`, `home.titles`, `home.landing-by-role`, `home.sections`, `home.hierarchies`, `home.topics` | Home ([section 15](#15-home-landings-cards-and-chips)); `template` is a `home/<name>_landing.html` page (none: Drishti Risk's); `sections` switches off `house`, `stats`, `grammar`, `pipeline`, `capabilities`, `views`, `close` |
| `phone` | the phone display ([section 17.1](#171-on-a-phone)) |

Then add `my-desk` to `drishti.lenses` (or a distribution's `lenses.offered`) and restart. A lens a person opens also needs a registry
entry (the modules it needs and its functions, cards and chips): add it to the site's `config/access/lenses.yaml` with the same id.
**Adding a measure later** (liquidity, climate) is a module: its packs, commands and menus come with it, and a lens names its page in
`menus.views` to put it first.

## 19. Lenses in a cluster

A person's lens is kept in their settings (the server's user store), so every member and every console worker sees it. A lens changes
nothing a server answers, so VaR sharding, the exposure engine and every other cluster service work the same inside one. Every member
runs the same distribution ([section 24](#24-white-label-distributions)); compute engines are per node ([section 21.7](#217-in-a-cluster)).

## 20. Functions (FUNC): every command, and turning one on or off

**Functions** is the command directory, for everyone: Views → **Functions**, or type `FUNC <GO>`. It lists every command you can use,
grouped by pack, with what each does and an example; click one to open it. An administrator sees the same page with the switches: any
command of the packs can be turned off for everyone (`FRTB`, `EQV`, `TRD` and every other mnemonic). The code stays; a command that is off
is **not active** everywhere, and nothing about it leaks. Turn it back on at any time. Packs are switched on the same page and in the same
record, so a cluster of servers always agrees on both. **Admin → Functions** is that page (`/func`; the old address `/admin/commands` redirects to it).

### 20.1 The page, for everyone

Views → **Functions** (`/func`), or `FUNC <GO>` on the command line, lists every command **you can use**: the commands that are on, of the
packs switched on for you, of the kinds your roles open. They are grouped by pack; each row says what the command opens, **what it does**
(the one-line description of its data contract, the same text as the [command catalog](COMMAND_CATALOG.md)) and an example. A command an
administrator turned off is not listed at all. The count reads *N commands you can use*.

![Functions as a user: the commands that are on, by pack, with what each does and its example; no switches](../admin/img/functions/functions-user.png)

- **The command** (`TRD`) opens it with the pack's example (`TRD 1000015 <GO>`): the pack's own example command for it, else the mnemonic
  with the example id its contract records. A command with no example opens its kind's search form instead (`FORM <pack> <kind>`), and the
  Example column says *search form*.
- **The pack's name** opens the pack's search form, exactly what `FORM <pack>` opens.
- **Find** (or `/`) narrows the list: every word you type must appear in the command's code, title, description, kind, or its pack's name
  or title. `FUNC <words>` opens the page with them typed: `FUNC exposure` (every command about exposure), `FUNC VAR`, `FUNC trading` (one
  pack's commands). The filter is kept in the address (`/func?q=exposure`), so a reload or a shared link shows the same list. **Pack** keeps
  one pack's commands.

`FUNC` is a reserved command word, like `BI`, `RUPAKA`, `FORM`, `WHAT` and `DATAMAP`: no pack may define a mnemonic named `FUNC`, in any
case. F1 or the `?` beside the title opens this page of help.

### 20.2 The administrator's view

An administrator (someone with the `admin` role, the same rule that shows the Admin menu) sees everything above and more:

- The commands that are **off** too, greyed, struck through, with an *off* chip, and never a link. **Show** keeps only the commands that are
  on or off. The count reads *N commands · X on · Y off*.
- The **Everyone** column turns a command on or off for everyone; a pack's row has a **Pack** switch that turns the whole pack on or off, as
  Admin → Packs does. Each switch records its grantee (`*` for everyone).
- **Undo** and **Review changes**, with the side panel and its reason ([section 20.4](#204-review-reason-and-history)).
- **Cluster** (a section that opens): the version of the switches each server of the cluster holds (*in step*, or *catching up* for a few
  seconds after a change) and the **History** of changes.

![Functions as an administrator, filtered to one pack (FUNC market-risk): FRTB is off, struck through and not a link; the Everyone and
Pack switches, Undo and Review changes](../admin/img/functions/functions-admin.png)

The server alone decides who may change a switch: the switches' API refuses anyone who is not an administrator (`403`), whatever page or
script asks.

### 20.3 What "not active" means

A command that is off answers the same way whatever asks for it:

| Where | What happens |
|---|---|
| The command line (`FRTB FRTB-RATES-1 <GO>`) and its page (`/v/frtb-sensitivity/…`) | The **not active** page: "FRTB is not active on this server", since when, and "ask an administrator if you need it" |
| The API (views, search, history, raw, exports), the command line tool, subscriptions, the compute ask | `403 DRS-1016` with `command`, `kind` and `since`; no data |
| Search, type-ahead and pick lists, the packs' menus, example commands on the landing page | Left out: it is not suggested |
| A link to it on another page (a linked-entity row, a table cell, a link key) | Struck through, *not active*, not clickable |
| A panel of another page that reads it | *Not active (turned off by an administrator)*, with nothing of its data |
| BI datasets and models that read its kind, BI SQL, AI answers about a page | Refused, or the tables of the kind left out, exactly as for a kind the person may not open |

![The not active page of a command that is off](../admin/img/functions/command-not-active.png)

An open page follows a change at once: the server tells every console on its live channel, a page of a command just turned off is drawn
again (as the not active page), and links to it stop being links.

### 20.4 Review, reason and history

![Functions as an administrator: PNL is being turned off, and the side panel says everywhere it will be blocked](../admin/img/functions/functions-review.png)

Ticking a box only records a change: the page counts the changes not saved, **Undo** takes back the last one, and **Review N changes** (or
**Turn FRTB off** for a single one) opens the side panel. It lists everything each change blocks: the typed command, its page and API, the
command line tool, exports and AI tools, search and type-ahead, links and panels on other pages, and what is saved that reads its kind (BI
datasets and what is built on them, saved searches, monitors, alerts, subscriptions, workspaces, shares, embed hosts). Write the
**reason**: it is kept with the change. **Apply** sends the whole batch; a pack another switched-on pack builds on cannot be switched off
(switch that one off first).

**History** (in **Cluster**) lists every change, newest first: when, what, on or off, who, why and the server it was made on. Each change is
also in the audit log (`command-disabled`, `command-enabled`, `pack-disabled`, `pack-enabled`).

### 20.5 Several servers

On a cluster ([OPERATIONS_GUIDE.md](CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)) a change made on any server reaches
every other within a gossip round or two, and is written to each server's own database before it takes effect there:

- Every change carries a **stamp** (a hybrid logical clock: the wall time in milliseconds and a counter, never behind a stamp the server has
  seen) and the **node** it was made on. When two changes of one switch meet, the newer by (stamp, node) wins, so every server reaches the
  same state whatever order it hears them in.
- Each member's gossip entry carries the hash of its switches (`switchesHash`, beside `digestHash`). A server whose hash differs from a
  peer's pulls the peer's switches (`GET /api/v1/net/switches`, signed with the cluster key, on the gossip port too) and merges them. Both
  sides of a difference pull, so a server that was down or cut off while changes were made catches up when it comes back, and a change it
  made meanwhile reaches the others.
- A server starts from what its own database holds, then reconciles with its peers.

### 20.6 Where it is kept

The switches live in the identity database ([section 9](#9-the-identity-database)), in both complete schema files:

| Table | Holds |
|---|---|
| `drishti_switch` | The newest state of each switch: `scope` (`command` or `pack`), `name` (the mnemonic or the pack), `grantee` (`*` is everyone), `enabled`, `reason`, `actor`, `stamp`, `node`, `updated_at` |
| `drishti_switch_log` | Every change applied, made here or learned from a peer, once each: the same columns, with `at` (when it was made) and `applied_at` (when this server applied it) |

### 20.7 From a script

What a user sees on Functions is `GET /api/v1/functions` ([API_REFERENCE.md](API_REFERENCE.md)): the commands the caller can use, by pack, with
nothing of the switches; a personal API token reads it too.

The admin API is `/api/v1/admin/commands`: `GET` the list, `POST` a batch
`{"reason": "...", "changes": [{"scope": "command", "name": "FRTB", "on": false}]}`, `GET /history`, `GET /{code}/impact`. Like every
admin endpoint that changes access, it is never for a personal API token: use the console, or `drishti.py server commands` where sign-in
is off:

```
$ python3 tools/drishti.py server commands off FRTB EQV --reason "quarter-end freeze" --user ash
FRTB, EQV off; 2 of 90 commands off; every server of the cluster follows within seconds
$ python3 tools/drishti.py server commands list --off --user ash
$ python3 tools/drishti.py server commands history --user ash
```

## 21. Compute engines on or off

A **compute engine** is the part of the server that computes figures when asked: VaR (with regulatory VaR), exposure, the position
sensitivities, FRTB-SA, SIMM, the MR cube, the pivot engine, the in-memory lake, the data inbox, Arrow Flight and the OpenBLAS kernels. Not
every server runs every engine: a market-risk node has no use for exposure, a small console node needs none. Each engine has **its own
file**, `config/engines/<engine>.yaml`, with its switch and every one of its settings, and **one switch with one meaning**: off, the engine
does not exist on that server. Every key of every file is in [CONFIGURATION.md, Compute engines](../admin/CONFIGURATION.md#compute-engines-configenginesyaml).
How the engines are built is in [ARCHITECTURE.md section 27](../architecture/ARCHITECTURE.md#27-compute-engines).

### 21.1 The engines

| Id | Engine | Switch (environment variable) | As shipped | Brings to the screen |
|---|---|---|---|---|
| `var` | VaR, ES, stressed VaR, the what-if, why it moved, parametric VaR, regulatory VaR (backtesting, report runs, sign-off) | `DRISHTI_VAR_ENABLED` | on | `VAR`, `VAR EXPLAIN`, `SMAT`, `PLAY VAR`; its pages in the Risk menu; the VaR landings and tiles; the VaR guide's Help pages; `drishti.py var`; BI `market-risk-var-*` |
| `exposure` | EE, ENE, PFE, EPE, EEPE, the exposure what-if, the pre-deal check and the playground | `DRISHTI_EXPOSURE_ENABLED` | on | `EXP`, `PLAY EXP`; its column and page; tiles; the exposure guide's Help pages; `drishti.py exposure`; BI `counterparty-exposure-*`, `live-exposure*` |
| `sensitivities` | position sensitivities and the factor dictionary: load, checks, sums by bucket | `DRISHTI_SENSITIVITIES_ENABLED` | on | `SENS`, `RFAC`, the factor dictionary |
| `frtb-sa` | FRTB-SA: SBM, DRC, RRAO and its playground | `DRISHTI_FRTB_ENABLED` | on | `FRSA`, `PLAY FRTB`; the capital page; the FRTB-SA guide's Help pages; `drishti.py frtb` |
| `simm` | ISDA SIMM initial margin per netting set and regime, and its playground | `DRISHTI_SIMM_ENABLED` | on | `SIMM`, `PLAY SIMM`; the netting set page's IM panel |
| `pivot` | the pivot engine ([PIVOT.md](bi/PIVOT.md)) | `DRISHTI_PIVOT_ENGINE_ENABLED` | on | `PIVOT`, Views → Pivot, Pivot on an Export data table, `drishti.py pivot` |
| `mr-cube` | the market-risk cube on the pivot engine ([PIVOT_SCALE_AND_CUBES.md section 29](bi/PIVOT_SCALE_AND_CUBES.md#7-the-mr-cube-preset)) | `DRISHTI_MR_CUBE_ENABLED` | on | `MCUBE`, `/mcube` |
| `memory-lake` | Delta tables held in memory only, with their replenish jobs ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md)) | `DRISHTI_MEMORY_LAKE_ENABLED` | off (it needs stores configured) | Admin → Lakes' Memory tab, `drishti.py lake memory`, `mem://` stores |
| `dataload` | the data inbox: files dropped in its inbox with a `.ready` marker are loaded into the lake ([DATA_GUIDE.md](DATA_GUIDE.md)) | `DRISHTI_DATALOAD_ENABLED` | off | the inbox and its loads |
| `flight` | Arrow Flight, data out to any client ([INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)) | `DRISHTI_FLIGHT_ENABLED` | off | the Flight port |
| `openblas` | linear algebra on the bundled OpenBLAS; off: the pure-Java kernels, the same figures | `DRISHTI_OPENBLAS_ENABLED` | off | nothing hidden: on switches the many-scope sums to OpenBLAS live, after a self-test; `drishti.py server blas` |

The compute workers are not an engine: they keep their own file, `config/compute/compute.yaml`, and switch, `compute.enabled`. Neither are
BI, Export data and the other platform features: their switches stay in `application.yaml` and the lenses' `features:`.

### 21.2 The files

```
config/engines/                 DRISHTI_ENGINES_DIR (default ./config/engines), shipped with every distribution
├── var.yaml                    drishti.engines.var.enabled, drishti.var.*, drishti.regulatory.*
├── exposure.yaml               drishti.engines.exposure.enabled, drishti.ccr.exposure.*
├── sensitivities.yaml          drishti.engines.sensitivities.enabled, drishti.frtb.sensitivity-kind, factor-kind, cache-minutes
├── frtb-sa.yaml                drishti.engines.frtb-sa.enabled, drishti.frtb.*
├── simm.yaml                   drishti.engines.simm.enabled, drishti.simm.*
├── mr-cube.yaml                drishti.engines.mr-cube.enabled, drishti.cube.mr.*
├── pivot.yaml, dataload.yaml, flight.yaml, openblas.yaml   each engine's switch and settings
└── memory-lake.yaml            drishti.engines.memory-lake.enabled, drishti.lake.memory.*
```

Every file has the same first block, `drishti.engines.<id>`, then the engine's own settings under the prefix they always had:

```yaml
# config/engines/frtb-sa.yaml: the FRTB-SA engine. Read at start, imported by application.yaml.
# Admin -> Engines writes this file (the switch and the reason); every other key is edited here by hand.
drishti:
  engines:
    frtb-sa:
      enabled: ${DRISHTI_FRTB_ENABLED:true}   # run FRTB-SA on this node (live: on and off without a restart)
      changed: { by: "", at: "", reason: "" } # the last change made from Admin -> Engines or drishti.py (written by the server)
  frtb:
    parameters-dir: ${DRISHTI_FRTB_PARAMETERS:./modules/banking/packs/market-risk/capital}
    max-factors: 2000
    # ... the engine's other settings
```

**What wins, from strongest to weakest:** `--key=value` on the command line, then environment variables; `application.local.yaml`; the
site's `config/engines/<id>.yaml`; the copy of the same file inside the server jar; `application.yaml` (which holds no engine keys). A site
that deletes a file gets the shipped values. A lens's feature presets (`var: off`) sit above the engine files.

**Old switch names stop the start.** `drishti.var.enabled`, `drishti.ccr.exposure.enabled`, `drishti.frtb.enabled`, `drishti.simm.enabled`
and `drishti.pivot.enabled` are not read: a site that still sets one gets a refusal naming the key to use instead
(`drishti.engines.<id>.enabled`, in `config/engines/<id>.yaml`).

`drishti.frtb.sensitivity-kind`, `factor-kind` and `cache-minutes` are the sensitivities engine's settings.
`drishti.py server engines show var --sources` says where each key comes from.

### 21.3 What "off" means

Off, the engine is not started: no date is opened or loaded, no cache, no thread of its own, no warm-up, no pinned date, no official capture
at start, no memory reserved for it; its own connectors and the kinds it computes are not registered; the engine-input kinds it reads (P&L
vectors, exposure paths, sensitivities) are not read ahead on a node where no running engine reads them (reference kinds such as trades and
counterparties are never affected). Its API answers `404` in words, never `403` (nothing is forbidden; the engine is not here):

```json
{"status": 404, "code": "DRS-1020", "engine": "frtb-sa",
 "detail": "DRS-1020 FRTB-SA is not running on this server, and no other server of this cluster runs it. An administrator can turn it on in Admin → Engines."}
```

When no server of the cluster runs it, it disappears from the console everywhere: its command words (typed, they answer the same words),
type-ahead, FUNC and examples; its pages in the Risk menu; its Help pages (a direct link opens a short page saying
the engine is not running here); its Home tiles, cards and chips, and a lens whose engine is off is not offered (the MR cube); its pages and
playgrounds (404); its BI datasets and the reports on them; its `drishti.py` commands (exit code 3 with the same words).
`/actuator/health` lists each engine's state under `engines`; an engine that is off is `off`, never down.

**Who opens a running engine.** The engine's catalogue entry names the roles that see its pages (`opens:` in
the banking jar's `META-INF/drishti/engines.yaml`, which the server sends to the console in its installation answer: `role:<name>`,
`pack:<name>`; administrators always;
none: everyone). A role it does not open does not see its pages under Risk, is not offered its words in the
type-ahead and is told "<title> is not opened for your role; ask an administrator." when it types one. Help is documentation and stays
listed. The server's own entitlements still decide the data.

### 21.4 Dependencies

| Engine | Needs (on the same server) | Uses (its parts hidden without it) |
|---|---|---|
| FRTB-SA | VaR (its scopes, hierarchies and limit letters) and the sensitivities | |
| SIMM | the sensitivities | exposure |
| MR cube | VaR, the pivot engine | the sensitivities, FRTB-SA |
| pivot | | VaR (the `var` source) |

Needs are local because the needing engine calls the needed one in process: a node running FRTB-SA runs VaR too. Turning on an engine
whose need is off, or off an engine another running one needs, is refused with words (`409 DRS-1021`): "FRTB-SA needs VaR on this server:
its scopes (desks, books, limit letters) are VaR's ... Turn on VaR first, or both together." Admin → Engines offers **Turn on both** or
**Turn off all three**; the command line takes `--with-needs` and `--with-dependents`. A server started with FRTB-SA on and VaR off in the
files starts; FRTB-SA is `blocked` with the reason, the start log says it in one line (`DRS-1022`), and nothing else fails.

### 21.5 Turning an engine on or off while the server runs

| Change | Effect | When |
|---|---|---|
| **Off** (any engine) | new requests answer `404` at once (or go to another node in a cluster); requests in flight finish within `drishti.engines.drain` (30 s), then are cancelled with a clear message; its open dates, caches and buffers are released, its threads end, its connectors and computed kinds are unregistered; open pages are told on the live channel and redraw without it | live |
| **On** (every engine but `memory-lake` and `flight`) | parameter files read and checked, connectors registered, threads created; the newest business date loads on first use (or ahead, per the engine's own warm-up settings); the console is told and shows the words | live |
| **On** `memory-lake` | written to the file; **restart needed** (the launchers add the stores' caps to the direct-memory limit at start, reading the engine's file) | restart |
| **On** `flight` | written to the file; **restart needed** (the port opens at start) | restart |
| A setting other than `enabled` | each key says `live` or `restart` in CONFIGURATION.md; Admin → Engines edits only the switch and names the file for the rest | as the key says |

**Restart needed** is a pill on the engine's row, kept until the server restarts, with the time of the change and who made it. The files are
also **watched** (`drishti.engines.watch-seconds`, 5): an edit by hand takes effect as a change from the page does and shows in History as
"edited in the file", with no name and no reason.

### 21.6 Admin → Engines

![Admin → Engines: FRTB-SA staged off, the review panel](../img/engines/review.png)

**Admin → Engines** (`/admin/engines`, administrators only; admin page id `engines`, so a lens can list or hide it as any Admin page) lists
every engine with its switch, its **state** (running, off, starting, stopping, blocked with the reason, restart needed, not installed), the
**memory** it holds now, its threads, what it **needs** and what **uses** it, and **where its switch is set** (the shipped copy, the server's or its module's, *shipped copy (module banking)*; the engine
file, the local file, the environment, the lens, another name).

1. Flip a switch. This only **stages** the change; the panel on the right opens.
2. Read the panel: the dependency check (a refusal in words, before anything is sent), **what disappears for people** (its words, menus,
   Help pages, tiles, BI datasets, CLI commands, playgrounds, and the engines that need or use it), what happens on the server, and whether
   it is live or needs a restart.
3. Write the **reason** (required) and **Apply**. The page writes the engine's file.

![Admin → Engines: a refused change, said before it is sent](../img/engines/refused.png)

**Writing the file.** The server writes only `enabled` (keeping its environment variable: `${DRISHTI_FRTB_ENABLED:false}`) and `changed`
(`by`, `at`, `reason`), keeping every comment and every other key; atomically (a temporary file in the same folder, then a rename); the file
is made from the shipped copy when the site has none. It is refused (`409 DRS-1023`) when the file was edited by hand since the page read it
("reload"), or when the folder cannot be written (the page names the folder and `DRISHTI_ENGINES_DIR`). When the switch is set above the
file (the environment, the local file, another name), the change is written and the page says that setting still decides until it is
removed.

![Admin → Engines: restart needed after turning on the in-memory lake](../img/engines/restart.png)

**History** (below the table, and its own tab) lists every change, newest first: when, the engine, on or off, who, why, the node and the
outcome (live, restart needed, written, refused with the words). The audit log holds the same (`engine-enabled`, `engine-disabled`,
`engine-refused`) with the file and its checksum before and after. Only an administrator may change an engine (the API refuses anyone else
with `403`); a personal API token never may.

![Admin → Engines: the History tab](../img/engines/history.png)

### 21.7 In a cluster

Each node reads its own `config/engines/` folder, so nodes differ by design (the VaR shard owners run `var`; the counterparty nodes
`exposure`; a console node none). Each node publishes its engines in its capability digest (the service `engines`), so a change reaches
the peers within a gossip round. A request for an engine's API arriving at a node that does not run it is **forwarded** to a node that
does (the least loaded, signed with the cluster key, for the caller; the answer names the node in `X-Drishti-Engine-Node`); an engine that
is starting or stopping on a node is not routed to; no node runs it: `404 DRS-1020`. People are offered an engine when any reachable node
runs it; a node going down that was the only one running an engine makes its words disappear within the failure detector's time, and come
back when it returns. A node with `var` off is never a VaR shard owner.

The **Cluster** tab of Admin → Engines is the matrix of nodes and engines with an "available to people" column; an administrator may change
another node's engine from there (the change goes to that node, signed, which writes its own file; both nodes audit it).

![Admin → Engines: every engine with its state, memory and file](../img/engines/list.png)

### 21.8 The command line

```
$ python3 tools/drishti.py server engines list
ENGINE         STATE           MEMORY    NEEDS                 FILE
var            running         3.1 GB    -                     config/engines/var.yaml
exposure       running         1.4 GB    -                     config/engines/exposure.yaml
sensitivities  running         210 MB    -                     config/engines/sensitivities.yaml
frtb-sa        off             -         var, sensitivities    config/engines/frtb-sa.yaml
simm           running         12 MB     sensitivities         config/engines/simm.yaml
memory-lake    off             -         -                     config/engines/memory-lake.yaml (restart to turn on)

$ python3 tools/drishti.py server engines off var --reason "a counterparty node"
drishti: refused (DRS-1021): VaR is needed by FRTB-SA on this server. Turn it off first, or both together.

$ python3 tools/drishti.py server engines on frtb-sa --with-needs --reason "capital desk asked"
var already running; frtb-sa on (live). Written to config/engines/frtb-sa.yaml.
```

`list` (`--cluster`: every node side by side; `--json`), `show <id>` (state, memory, needs, used by, what it hides; `--sources`: where each
key comes from), `on <id>... [--with-needs]`, `off <id>... [--with-dependents]` (both need `--reason` and an administrator; `--node` changes
another node), `history`. `drishti.py admin engines` is the same command. Exit codes: 0 done, 2 refused, 3 not running anywhere.

The API behind them ([API_REFERENCE.md](API_REFERENCE.md)): `GET /api/v1/admin/engines` (every engine: state, memory, threads, needs, used by,
where the switch comes from, restart needed), `GET /api/v1/admin/engines/{id}/impact` (what turning it off hides), `POST /api/v1/admin/engines`
(a batch `{"reason": "...", "node": "<id or blank>", "changes": [{"engine": "frtb-sa", "on": false}]}`, checked as a whole for dependencies,
answering each change's outcome), `GET /api/v1/admin/engines/history`; `GET /api/v1/about` carries `engines: [{id, title, here, cluster}]`,
what the console needs to show or hide. The codes: `DRS-1020` (not running here or anywhere, 404), `DRS-1021` (a dependency refuses the
change, 409), `DRS-1022` (an engine blocked at start by a missing need, logged), `DRS-1023` (the engine file changed on disk or cannot be
written, 409).

## 22. Modules on or off

Drishti runs as a core plus **modules**: **banking** (VaR and ES, exposure, FRTB-SA, SIMM, sensitivities, the MR cube, the official results,
and the trading, market-data, market-risk and counterparty-risk packs), **capitalmarkets** (the us-equities and funds packs and the market
data's functions) and **economy** (the world-economy, news-and-markets and public-sector packs, and the World and Public sector lenses). A
module is **on** when it is installed, licensed and enabled, and every module it needs is on; the core is always on. A module that is off
offers none of its lenses, functions, packs or engines (its engines show *not installed* on Admin → Engines), and its API answers
`404 DRS-1025`; a function that is off answers `404 DRS-1026`. The switch is `config/modules/<module>.yaml` (`POST /api/v1/admin/modules` writes it, with a reason, audited) or
`DRISHTI_MODULES_<MODULE>_ENABLED`, and a change takes effect at the next start. The how-to, the licence file and what each module brings
to the console are in [OPERATIONS_GUIDE.md, Modules on or off](OPERATIONS_GUIDE.md#65-engines-modules-and-editions).

### 22.1 Module packages and the shared lib folder

Besides the modules built into the server, the kernel loads **module packages** (`.dmod`), each in its own class loader. Two folders,
as in Tomcat: the modules folder beside the server's jar, the shared lib folder under the install folder (every jar sits under the install
folder, every piece of data under the data folder):

| Folder | Setting | What it holds | When a change counts |
|---|---|---|---|
| `<home>/modules/` | `drishti.modules.home` (`DRISHTI_MODULES_HOME`) | the `.dmod` packages of the server: one file per module, named `<id>-<version>.dmod` | at the next restart |
| `<home>/lib/ext/` | `drishti.lib.path` (`DRISHTI_LIB_PATH`) | the site's jars every connector and module may use: JDBC drivers, connector plugins, a company's own libraries; kept on an upgrade | at the next restart |
| `<home>/lib/` | none | Drishti's own server jar, shipped with a release | replaced on an upgrade |

**A module is a file and a restart.** At start the server reads the `.dmod` files of the modules folder: each is verified (its hash list,
the kernel's API ranges, the allowance and licence checks), unpacked once into `<data folder>/cache/modules/<id>/<version>-<hash>/` and
loaded; a file that fails a check is refused in words (Health says which and why) and the rest start. Install a module by copying its
file into the folder and restarting; upgrade by replacing the file (one package per module id: two versions of one module are refused);
remove it by taking the file out and restarting. Nothing is deployed to a running server and nothing is copied from one node to another.
A module is placed like any other (`config/placement.yaml`; on a single node, its parts on `local`). **Production loads only verified
`.dmod` packages**; the development switch (`drishti.modules.dev`) reads the module source folders of the modules folder instead, loads
them unverified, and is for a developer's own server: it is refused in a cluster and under the `prod` or `production` profile, and Health
marks each such module *development (unverified)* ([DEVELOPER_GUIDE.md section 1.6](DEVELOPER_GUIDE.md#16-the-module-sdk)). Old folders
of the earlier deployment store (`installed`, `store`, `shipped`, `staging`, `tombstones` under `<data folder>/modules`) are never read;
Health names each as safe to delete.

**The shared lib folder.** Every jar in `<home>/lib/ext/` (`drishti.lib.path`) is read once, at start, into one *lib* class loader under the kernel's.
Connectors and modules see its classes, a JDBC driver in it is registered (a connector's `jdbc:` URL finds it), and a connector plugin
in it (its `META-INF/services` entry) is offered like a built-in one. It never replaces a kernel class: the kernel is asked first. Health
(`lib`) and Admin → Modules list each jar with its size and SHA-256, marked **lib**: code the site added beside the product. A module's own
third-party jars belong in its package (`java.lib`), not here. A Drishti server jar (`drishti-server-*-exec.jar`) found there is skipped
with a warning: it belongs in `<home>/lib`.

**Upgrades.** An upgrade replaces the Drishti jar in `<home>/lib` only and keeps `<home>/lib/ext`; the start refuses when `<home>/lib`
holds more than one Drishti server jar. Jars in `<data folder>/lib/` (the previous default) are moved once into `<home>/lib/ext/` at the
first start that finds them, listed in `moved-<date-time>.bak` there, and Health says so; when `drishti.lib.path` names `<data folder>/lib`
itself, that is the site's choice and nothing moves.

## 23. Data bindings

A module says which **kinds** it needs (banking: `trade`, [`scenario-pv`](../contracts/packs/market-risk.md#example-market-risk-scenario-pv) ([contract](../contracts/packs/market-risk.md#spv-scenario-pvs-of-a-position-or-trade-one-pv-per-scenario-its-currency-scenario-pv))
or [`pnl-vector`](../contracts/packs/market-risk.md#example-market-risk-pnl-vector) ([contract](../contracts/packs/market-risk.md#pnlv-pl-vector-one-pl-per-scenario-pnl-vector)),
[`scenario-set`](../contracts/packs/market-risk.md#example-market-risk-scenario-set) ([contract](../contracts/packs/market-risk.md#sset-scenario-set-scenario-set)),
[`netting-set`](../contracts/packs/counterparty-risk.md#example-counterparty-risk-netting-set) ([contract](../contracts/packs/counterparty-risk.md#nset-netting-set-netting-set)) …); the site decides
which connector or lake table serves each one. That decision is the **binding**, one per kind, shared by every module that needs the kind,
kept in `config/bindings.yaml` (`drishti.bindings.file` names another place). A binding names connectors and lakes only; hosts, URLs and
credentials stay in the connector files and the secret store, and no module ever sees them. The file's format and examples:
[DATA_LAKE_GUIDE.md, Data bindings](DATA_LAKE_GUIDE.md#61-data-bindings); the design: [KERNEL_AND_MODULES.md section 5](../architecture/KERNEL_AND_MODULES.md#5-data-needs-and-connector-binding).

**Admin → Data bindings** shows one row per kind: the modules that need it, the functions that use it, what serves it in read order (with
*union* and the live connector), where the answer came from, the last load and the **state**:

| State | Meaning | Effect |
|---|---|---|
| `BOUND` | the site's binding, or the distribution's default (a pack's route), serves it | normal |
| `AUTO` | nobody bound it, but a lake holds it (found by convention) | normal; bind it to confirm |
| `DEMO` | the demo lake's samples serve it (only while demo is on) | works; demo data |
| `STALE` | bound, but nothing loaded within its contract's cadence | works; amber |
| `MISMATCH` | the source breaks the contract | treated as unbound for the needs |
| `UNBOUND` | nothing serves it | a **required** need switches off exactly the functions that use it, and they explain themselves: the page is the needs-data page (*Exposure needs data: exposure-path is not bound*, with links to Admin → Data bindings and Help), the command line says the same and the API answers `409 DRS-1035`; an optional one hides its features |
| `COMPUTED` | a module computes it (`position`, `system-basket`, `var-result`) | never bound |

**Tables read** (on the same page; `GET /api/v1/admin/bindings/map`) is the binding map: for every kind, each connector a read asks in
order, its layer (`template`, `override` or `site`), its plugin and lake, the table it reads resolved to its full location
(`<root>/<domain>/<folder>`) and whether that table exists. A connector of a Delta or Iceberg lake that points at a missing table shows
**missing**: the place to look first when a page says a kind has no rows. The release's upgrade test reads the same map and fails when a
connector reads a missing or empty table where the release before held data.

A kind finds its data in this order, first hit wins: the site's binding, the distribution's default, a lake by convention, the demo lake,
else unbound. The red banner names every required need that is unbound and the functions it switched off (they answer `404 DRS-1026` with
that reason until the kind is bound).

**Changing a binding takes two administrators.** *Bind…* on a row opens the form (connectors in read order, union, live, the demo lake,
why); *Propose* records it; a second administrator approves or rejects it under *Waiting for a decision*, and the proposer may withdraw it.
An approval writes `config/bindings.yaml` (the previous file kept as `bindings.yaml.bak`) and applies it **without a restart**: the
connectors whose kinds change are applied again and the read order is swapped. Every step is in the audit log (`binding-proposed`,
`binding-approved`, `binding-rejected`, `binding-withdrawn`). A refusal says why (`422 DRS-1029`): a computed kind, a connector no file
defines, a host or credential in the binding, demo and real together; deciding your own proposal is `403 DRS-2007`. An edit of the file
by hand is picked up within `drishti.bindings.refresh-seconds` (30), or at once with `POST /api/v1/admin/bindings/reload`.

**Connector files name no kinds.** `config/bindings.yaml` is the one home of which connectors serve each kind; a connector file keeps its plugin, settings and `lake:`, and serves what its plugin and lake hold (a `kinds:` line left in a file by an earlier release is ignored and dropped at the file's next save). Health's `bindings` line gives the count of bound kinds and of required needs that nothing serves; Admin → Data bindings has the detail. The API: `GET /api/v1/admin/bindings`, `POST /api/v1/admin/bindings/proposals` and `…/proposals/{id}/approve|reject|withdraw`.

### 23.0 The connector and binding designer (Create → New connector)

**Create → New connector** (`/build/connector/designer`) is the one way to make a connector and bind it. It has the same left-rail workspace as the
Pack designer, six steps, and **nothing is saved until step 6**. It replaces hand-editing `config/connectors/<name>.yaml` and `config/bindings.yaml`;
**Admin → Connectors → New connector** (and a pack's *Create* link, `?new=<name>`) opens it, pre-filled from the pack's suggestion. Editing a connector that
exists stays on Admin → Connectors (its editor, test, history and restore). Administrators only.

1. **Connector type.** One row per type installed on this server (file, jdbc, kafka, delta, iceberg, s3, …) with what it reads; the settings of the one you pick
   are generated from its own description.
2. **Settings.** The connector's name (the file's name), what it is, the **kinds to try** (the kinds this source holds) and a form of the type's settings,
   a group each. **A secret is only a reference** to the site's secret store: `${ENV_VAR}` or `file:/path` (the file's first line is read when the connector
   starts); a typed value is refused in the page and again by the server, and the connector file holds only the reference.
3. **Test connection.** A throwaway instance is started on the draft (through a proxy when one is configured), asked what it holds, and closed: connected or
   not (with the reason and a hint in words), the time it took, notes (for example TLS off) and **what it can see**: each kind's business dates and row counts.
4. **Preview data.** The first real rows of a kind (and date) read from the source, in a grid, and the **type detected for each field** (integer, decimal, date,
   timestamp, boolean, text, list, object) with an example and how many are empty. The server reads them (`POST /admin/connectors/api/<name>/preview`, a
   throwaway instance like the test; at most 200 rows); nothing is kept.
5. **Bind to kinds.** Per kind, the mode: **read** (this connector is the source), **union** (read together with the sources already bound), **live** (its live
   feed) or **write** (where written records go: a lake and a table). The grid shows the resolution **now** and **after**; **Functions that come on** lists the
   functions an unserved need switched off that these kinds bring back.
6. **Review and apply.** The diff of the new connector file and of `bindings.yaml` (the proposed lines among the current ones), and *why* (kept in the audit
   log). **Save the connector and propose the bindings** saves the connector file through Admin → Connectors' API and proposes each kind's binding to the
   bindings service. From there it is the one path of section 23: a second administrator approves it in **Admin → Data bindings** (four eyes: the proposer's own
   approval is `403 DRS-2007`), it is audited with both names and applies **without a restart**. The page shows the two eyes (*proposed*, then *approved and
   applied*) and the health of each kind after the apply.

The designer adds no second path: it calls the connector API and the bindings service, so everything those enforce (a literal credential is refused, a connector
no file defines is refused, a computed kind is not bound) holds here. The connector is saved when you propose and stays until you remove it in Admin → Connectors;
the bindings change only when the second administrator approves. A connector that already exists cannot be created again (the name is refused).

### 23.1 Module placement (Admin → Cluster → Modules)

**Admin → Cluster → Modules** shows where every module part runs: one row per part (its resource class, max-nodes, memory), one column per
node (its labels, up or down), each cell **active**, **standby**, **down**, **not placed** or **in process**, the owner of a feed or
singleton starred. A part with no placement runs nowhere, and the page says so. To change it, **Plan a placement** (the whole
`config/placement.yaml` and why): the server refuses heavy work on every node without `allow-all: true` or over `max-nodes` (`DRS-1030`);
another administrator **Applies** it (four eyes, `DRS-2007` for the proposer; `DRS-1031` when the cluster changed since the plan). It takes
effect at once on every member, with no restart. Banking and the other modules still built into the server run on every node until they
become modules. The rules: [CLUSTER_GUIDE.md section 1.9](CLUSTER_GUIDE.md#19-module-placement).

## 24. White label: distributions

A **distribution** makes the product: its name, tagline, copyright, logo and theme, its modules, lenses, menus, Home cards and Help.
It is one file, `config/distributions/drishti.<name>.yaml`, and the one way to brand Drishti
([DISTRIBUTIONS.md](../architecture/KERNEL_AND_MODULES.md), [KERNEL_AND_MODULES.md section 6](../architecture/KERNEL_AND_MODULES.md#6-the-distribution-file-white-label)).
Shipped: **Drishti All** (every module, the default), **Drishti Risk** (banking and market data; Risk, Trading and Market lenses; navy
dark), **Drishti Finance** (capital markets, economy and market data; no VaR or any risk number; light) and **Drishti Genomics** (the
sample). What a distribution leaves out does not exist in it: its commands, words, menus, Home cards, Help pages and the catalogue the
AI reads; a command typed anyway answers *DRS-4023 VRES is not in Drishti Finance*.

**Choose one** at start: `python tools/drishti.py start all --distribution finance` (Windows `py -3.13 tools\drishti.py start all --distribution finance`), or
`DRISHTI_DISTRIBUTION=finance`, or `drishti.distribution: finance` (server) and `distribution: finance` (console). A path instead of a
name loads a site's own file. A console started with its own `--distribution` keeps it whatever the server runs, so one cluster can serve
a Drishti Risk console and a Drishti Finance console ([DEPLOYMENT_AND_QA_GUIDE.md 6.7](../DEPLOYMENT_AND_QA_GUIDE.md#77-starting-a-white-labelled-instance)).

**Your own brand.** Copy a shipped file to a new `drishti.acme.yaml` beside it in config/distributions, change `distribution: drishti.acme` and its
`brand:` (`product`, `tagline`, `headline`, `headline_second` and `headline_second_accent` (the landing page's second line, blank for none), `owner`, `copyright`, `notice`, `logo`, `favicon`, `about`, `support`), `theme:` (`tokens`: one of the
console's themes, `mode`, `density`), `modules:` (module ids: each module's own manifest under `modules/<id>/`), `lenses:`, `menus:`, `home:` and
`docs: { title }`; start with `--distribution acme`. A distribution names modules; it never contains code, connectors or credentials,
and it cannot widen the licence.

**Admin → White label** (mock-up A, side by side): every distribution file as a card, the running one first, each with its brand, theme,
modules (and whether each is loaded on this server), lenses, menus, Home cards, Help title and the command that starts it. Read only:
change the file and restart. **Admin → Health** summarises: the console's and the server's distribution, how many modules loaded, how many kinds are bound (and how many required needs are unbound) and the placement version, one line each with a link to the page that holds the detail (White label, Modules, Data bindings).

**The old keys.** `drishti.branding.*`, `DRISHTI_PRODUCT`, `ui.product`, `ui.tagline`, `ui.copyright`, `ui.owner` and `ui.notice` are no longer read (the
distribution file's `brand:` decides), nor is `drishti.edition` (`DRISHTI_EDITION`: a lens file is never an instance's filter); Health names any a
site still sets.

## 25. Modules

**A module's settings.** A module describes its own settings in its manifest (`settings:`), and a site sets them as `drishti.module.<id>.<key>`. Banking's
are the VaR window and confidences, the hierarchies' labels, the system-basket seed and the settings of its risk engines (var, exposure, frtb-sa, simm,
sensitivities, mr-cube). The server keys they replaced (`drishti.module.banking.var.confidence`, `drishti.module.banking.var.window`, ...) are read for one release, the module's
key winning; Health lists the old keys a site still sets. `GET /api/v1/admin/modules/banking/settings` gives each setting, its value and where the value
comes from (the module's key, the old key, or the default). Banking also declares its parts (`api` light, `compute` heavy, `feeds`), so Placement can put
the compute on its own nodes.

**Admin → Modules** answers one question first: did every node boot the same modules, and is anything stale or refused? From the top: six
tiles (modules, nodes that agree, the cluster fingerprint, problems, stale, not loaded); a red *Nodes disagree* banner when a module's content differs
between nodes, with a **Copy fix command** button (the install commands with that module's file named); then the **nodes × modules matrix**, one row per
module and one column per node, each cell the short content hash that node booted, red when it differs from the majority (or *stale* or *refused*). The filter
box and the chips (all, problems, off, development) narrow the rows; the table sorts and pages like every console table, and on a phone it scrolls inside
its card. A click on a module shows its detail below: version, origin, content hash, file, the switch (section 22) and where it was set, the parts placed on
each node, and its problems. **Install or upgrade** lists the commands and the modules folder; nothing deploys from the browser: a module is a file and a
restart (section 22.1; on a cluster, [CLUSTER_GUIDE.md section 1.10](CLUSTER_GUIDE.md#110-installing-a-module-on-a-cluster)). **Saved work that needs a
module that is not installed** lists the saved items (Build's kinds) kept while the module they use is off or gone. The page is for administrators; the
data is `GET /api/v1/admin/modules` (contract `admin-modules/1`, [KERNEL_AND_MODULES.md section 4.2.2](../architecture/KERNEL_AND_MODULES.md)).

A module that is switched off answers `404` on its pages and API (*Not available*); a module whose file was taken out is unknown (`404`).
Alert rules that use either show *paused · needs module X*; nothing saved is deleted, and the module back, it works again.

**What the console shows of a module.** Its console part (its pages under `/m/<id>/`, menu entries, Home cards, command words, Help
section, AI tools; [DEVELOPER_GUIDE.md section 1.7](DEVELOPER_GUIDE.md#17a-a-modules-console-part)) appears on every console within
`modules.parts_refresh_seconds` (5) of the server's start, and goes when the module is
switched off or its file taken out and the server restarted: an open page of it is frozen under the notice, Build's lists and Forms' saved searches keep saved Sutras, reports, dashboards, baskets and searches
that use it with a *needs module X* chip (not openable until it is back). A part that carries its own copy of Drishti Elements or of a
vendored library, code, or a template that reaches past the kernel's layouts is refused (not shown anywhere); `GET /api/modules/hosted`
lists what the console hosts, what is gone and what it refused, with the reasons.

## 26. Troubleshooting

**Users, roles and sign-in**

| You see | Why | Do |
|---|---|---|
| *unknown role 'x'* when saving a user | the role does not exist (a typo, or it was deleted) | check Admin → Roles |
| *'trader' is a built-in role* | you tried to define a role with a built-in name | pick another name |
| *… is held by [priya]* when deleting a role | someone still has it | remove it from them on the Users page |
| a user cannot open a kind their role lists | the kind's pack is not one of theirs | tick the pack in their user dialog |
| the server does not start: *Schema-validation: missing column …* (PostgreSQL) | the database was changed by hand, or is another application's | point it at Drishti's own database |
| *identity database not reachable yet* in the log | PostgreSQL is down, or the URL, user or password is wrong | check with `psql "postgresql://drishti@host:5432/drishti"` |
| every admin is locked after failed sign-ins | the lockout (15 minutes) | wait, or clear it in the database: `sqlite3 data/identity/drishti.db "UPDATE drishti_user SET failed_attempts=0, locked_until=NULL WHERE username='drishti-dev-admin'"` |
| a user is sent to the sign-in page every few seconds | their account was disabled, deleted or had its password reset (audit: `sessions-ended`), or the console cannot keep a session the server opened (`auth.recheck_seconds`) | check the audit log for the user |
| every admin's password is forgotten | — | restore the identity database from a backup ([section 9.4](#94-back-it-up)) |
| a script's write answers `403 DRS-5002 API tokens only read` | the token has no write scope for that call | make a token with the scope ([section 6](#6-api-tokens)) |

**Lenses**

| You see | Why | Do |
|---|---|---|
| No "Open …" link for a lens named in `drishti.lenses` | its registry lens is not offered (a module it needs is off), it has no file, or the instance runs a lens itself | switch the module on (`config/modules/<module>.yaml`, `DRISHTI_MODULES_<MODULE>_ENABLED` or `POST /api/v1/admin/modules`; restart); the server's log says "lens … is not offered" with the reason |
| A command answers `DRS-1026` or "not available to you" | its function is off (its module off, or Functions) | switch the module or the function on |
| A Home card or chip is missing | its function is off for the person | switch the function or its module on |
| World shows no companies, filings or funds | the capitalmarkets module is off | switch it on (`config/modules/<module>.yaml`, `DRISHTI_MODULES_<MODULE>_ENABLED` or `POST /api/v1/admin/modules`; restart) |
| The console still shows the full product | it has not reached the server yet, or the server runs no lens | check `/public/about`; the console follows within `backend.check_seconds` |
| A BI report is missing | its dataset or model is not in `bi.datasets` / `bi.models` | add it there |

**Commands and engines**

| You see | Why | Do |
|---|---|---|
| A command shows the **not active** page, or the API answers `403 DRS-1016` | an administrator turned it off on Functions | turn it on in Functions ([section 20](#20-functions-func-every-command-and-turning-one-on-or-off)) |
| A word answers "… is not running on this server" (`DRS-1020`) | its engine is off on every node | turn it on in Admin → Engines ([section 21.6](#216-admin--engines)) |
| Turning an engine on or off is refused (`DRS-1021`) | another engine needs it, or it needs one that is off | turn both on or off together (**Turn on both**, `--with-needs`, `--with-dependents`) |
| An engine shows `blocked` after a start (`DRS-1022` in the log) | its file turns it on but a need is off | turn the need on |
| Apply answers `DRS-1023` | the engine file was edited by hand since the page read it, or the folder is not writable | reload the page; check `DRISHTI_ENGINES_DIR` and the folder's permissions |
| The page says a change is written but another setting still decides | the switch is set in the environment or in `application.local.yaml` | remove that setting, or change it there |
| An engine stays *restart needed* | `memory-lake` and `flight` start only at a server start | restart the server |
| An engine shows *not installed* | its module is off | switch the module on ([section 22](#22-modules-on-or-off)) |
| An engine's pages are missing from the Risk menu for some people | the engine's `opens:` does not name their role | add the role to its `opens:` in the banking jar's `META-INF/drishti/engines.yaml` (the console keeps no copy) |

## 27. AI: model providers and MCP servers

> **Off in this release.** The console of this release shows no AI page, menu item or button (no Admin → AI, no assistant, no
> *Ask about this page*, no AI in BI), and the server's AI layer is **off**: one switch, `drishti.ai.enabled` (`false` as shipped),
> turns all of it on together (the model providers, the MCP client, *Ask about this page* and the BI hooks each also need it). While it
> is off no provider or MCP file is read, no key is asked for and no model is called; Admin → Health says **AI: off**.

The server's AI layer has two parts, both built and both off by default: the **model providers** (27.1: one interface, one file per
provider, seventeen vendor types beside `openai`, `anthropic`, `server` and `mock`) and the **MCP client** (27.2: other servers' tools,
resources and prompts behind Drishti's policy; off until `drishti.mcp.enabled=true`). The pages that use the layer are
[*Ask about this page*](USER_GUIDE.md#about-this-page) and BI's AI hooks
([GOVERNANCE_AND_AI.md](bi/GOVERNANCE_AND_AI.md#1-ai-in-bi-report-from-words-ask-the-data-explain-this-number)); the settings are in
[CONFIGURATION.md](../admin/CONFIGURATION.md). The designs of AI management and of server-side pricing, which are not built, are in
[AI_MANAGEMENT.md](../plans/AI_MANAGEMENT.md) and [QUANT_SERVICE.md](../plans/QUANT_SERVICE.md).

### 27.1 Model providers

Drishti talks to language models through its **own** interfaces. Which vendor, library or endpoint answers is a file in
`config/ai/`, not a decision in a feature. Spring AI (`spring-ai-starter-model-openai`) is an implementation detail behind the
`openai` provider type; no feature sees it. The layer is **off by default** (`drishti.ai.enabled=false`): while off, no
provider file is read and no model is called. The console calls this layer only (section 27.1.8).
MCP servers (tools a model or a feature can call) have a sibling layer: [27.2](#272-the-mcp-client-other-servers-tools-behind-drishtis-policy). Beyond `openai`, `anthropic` and `mock`, seventeen more provider types (Groq, Together, Fireworks, DeepSeek, xAI, OpenRouter, Perplexity, Hugging Face, vLLM, LM Studio, Mistral, any OpenAI-compatible server, Azure OpenAI, Gemini, Cohere, Ollama, AWS Bedrock) come from sajhamcpserver: [section 11](#27111-more-providers-the-way-sajhamcpserver-has-them).

#### 27.1.1 The pieces (server, module `drishti-ai`)

`drishti-ai` has no controllers and depends only on `drishti-api` (the shared `tls.*` module) and `drishti-common`; an ArchUnit
rule (`aiLayerDependsOneWay`) keeps it that way. REST lives in `drishti-server`.

| Type | Role |
|---|---|
| `LlmProvider` | One configured provider: `id()`, `type()`, `capabilities()`, `models()`, `chatModel(name)`, `embeddingModel(name)`. |
| `LlmModel` / `EmbeddingModel` | A model by its logical name: `chat(ChatRequest)` returns a `ChatResponse`; `embed(texts)`. |
| `ChatRequest` / `ChatResponse` | Provider-independent: model reference, system text, messages, tools, temperature, max tokens, JSON mode, timeout; text, tool calls, finish reason, `Usage` (tokens and cost). |
| `Capability` | `chat`, `streaming`, `tool-calls`, `embeddings`, `json-mode`. A model lists what it can do; the gateway refuses a request that needs more (`UNSUPPORTED`). |
| `ProviderFactory` | The extension point: `type()` and `create(ProviderConfig)`. |
| `LlmRegistry` | Providers and models by logical name (`provider/model`, or a bare name only one provider has), health, `reload()`. |
| `LlmPolicy` / `DefaultLlmPolicy` | Hooks before and after every call (section 27.1.4). |
| `LlmGateway` | **The one door features use**: resolve the model, run the policy, apply the timeout, record health. |

A feature calls it like this:

```java
ChatResponse r = gateway.chat(new CallContext(user, roles, "ask"),
        ChatRequest.of("provider/model", systemText, userText, maxTokens, Duration.ofSeconds(15)));
```

Failures are `LlmException` with a kind (`CONFIG`, `NOT_FOUND`, `UNSUPPORTED`, `POLICY`, `RATE_LIMITED`, `TIMEOUT`, `FAILED`); the
message never carries a key, a prompt or an upstream response body.

#### 27.1.2 Provider files

One YAML per provider in `drishti.ai.dir` (`DRISHTI_AI_DIR`, default `./config/ai`), watched and reloaded live like connectors
(`drishti.ai.watch`: `auto` / `poll` / `off`). A bad file is listed with its reason (Admin -> AI) and the others still load.
Files ending `.example` are ignored; copy one and drop the suffix. Ready-made: `config/ai/openai.yaml.example`,
`anthropic.yaml.example`, `mock.yaml.example` (and `server.yaml.example` for the console).

```yaml
type: openai                         # the provider type (the factory); required
enabled: true
base-url: https://api.openai.com
completions-path: /v1/chat/completions
api-key: ${OPENAI_API_KEY}           # an ${ENV_NAME} placeholder ONLY; a literal key is refused and the file does not load
timeout: 60s
retries: 0
default-model: fast                  # used when a call names no model and this is the provider the registry falls to
header: {X-Team: red}                # extra headers; credential-looking ones (auth, key, token, secret) must be ${ENV}
tls: {ca-file: /etc/drishti/ca.pem}  # the shared tls.* keys; https:// trusts the JVM's authorities unless told otherwise
proxy:                               # optional outbound HTTP proxy (see "Outbound proxy" below); `proxy: none` goes direct
  url: http://proxy.corp.example:3128
  username: svc-drishti
  password: ${PROXY_PASSWORD}   # ${ENV} only; a literal is refused and the file does not load
  no-proxy: [localhost, .corp.example]
models:
  - name: fast                       # logical name: what features and policy refer to
    id: gpt-4o-mini                  # the provider's own id
    max-tokens: 400
    temperature: 0.2
    capabilities: [chat, tool-calls, json-mode]
    cost-per-1k-input: 0.00015       # drives Usage.cost, audit and the daily cost limit
    cost-per-1k-output: 0.0006
```

The provider name is the file name without its extension (or `name:`). A model is referred to as `provider/model` (for example
`openai/fast`); a bare name works when only one provider has it. The registry default is `drishti.ai.default-model`
(`DRISHTI_AI_DEFAULT_MODEL`), else the only chat model there is.

##### 27.1.2.1 Outbound proxy

A site that reaches the model endpoint only through a forward proxy puts a `proxy:` block in the provider file (`url`, `username`, `password` as `${ENV}`, `no-proxy`, `enabled`), or
sets the site default once: `drishti.http.proxy.*` on the server, `DRISHTI_HTTP_PROXY_URL` and friends in the console. `proxy: none` in a file goes direct whatever the site says.
`http://` and `https://` endpoints both go through an `http://` proxy; an https endpoint is tunnelled with `CONNECT`, so `tls.*` (a private CA, a client certificate) still applies to the
endpoint itself. OpenAI-compatible and Anthropic providers use it on the server (the JDK HTTP client) and in the console (`httpx`). A literal `proxy.password` refuses the file without
repeating it; a wrong proxy login is reported as a failure of the call without the password. Everything is in [DATA_GUIDE.md, the outbound proxy](DATA_GUIDE.md#proxy-9-the-console-ai-and-mcp-clients). Tested in
`AiProxyTest` against a fake proxy on loopback. Admin -> AI does not display the proxy yet.

##### 27.1.2.2 A provider that cannot be set up is retried

A file that is well formed can still give a provider that cannot be built (its type is not installed, a setting the type needs is
wrong, an `${ENV}` key is not set). It is listed in Admin -> AI as **ERROR** and **tried again by itself**: after `drishti.retry.ai.initial`
(5 s), then twice as long each time, never longer than `drishti.retry.ai.max` (5 min), with jitter. The row says
*failed - next retry in 20 s (attempt 3)* and the last error; an error that retrying cannot fix (a missing key, an unknown type, three
refusals in a row) is retried only every `max`. A provider that was built is not tested until a call is made: if a call fails it
shows **DOWN** until the next call goes through (every call is attempted afresh; there is no circuit breaker that holds calls back).
A file that cannot be read (bad YAML, a literal key) is read again at every look at the folder (`drishti.ai.poll-ms`), so it clears the moment it is fixed.
**Retry now** (Admin -> AI, `POST /api/v1/admin/retry/ai`, admin, audited as `ai-retry-now`; an API token needs the `packs:admin` scope) builds
every failing provider at once. Failures are logged and audited on attempts 1, 2, 4, 8, ... (`ai-retry-failed`) and a recovery always
(`ai-recovered`). Settings: [CONFIGURATION.md](../admin/CONFIGURATION.md#retry-settings), `drishti.retry.*` and `drishti.retry.ai.*`.

##### 27.1.2.3 Types shipped

| `type` | Backed by | Settings beyond the common ones |
|---|---|---|
| `openai` | Spring AI 1.0.3 (`OpenAiApi`, `OpenAiChatModel`, `OpenAiEmbeddingModel`) over the JDK HTTP client | `base-url`, `completions-path`, `embeddings-path`, `header.*`, `retries`. Works with Azure OpenAI, vLLM, Ollama's OpenAI endpoint, LiteLLM, company gateways. Chat, tool calls, JSON mode, embeddings. |
| `anthropic` | the JDK HTTP client (the former Ask provider, ported) | `base-url`, `messages-path`, `anthropic-version`, `header.*`. Spring AI's Anthropic starter is **not** used: one HTTP stack, one TLS module. |
| `mock` | nothing | section 27.1.6 |

Spring AI's own auto-configuration is switched off in `application.yaml` (`spring.ai.model.chat: none`, ...): Drishti builds
the clients from the provider files, and nothing starts without a key. Spring AI 1.0.x is compiled for Java 17, so the
enforcer rule `maxJdkVersion 21` still passes and production runs on JDK 21.

#### 27.1.3 Add a provider in 30 minutes

1. **Write the class** (10 minutes). Extend `BaseProvider` (it reads the model list from the file) and implement `chatModel`
   (and `embeddingModel` if you can):

   ```java
   public final class AcmeProvider extends BaseProvider {
       AcmeProvider(ProviderConfig c) { super(c, Set.of(Capability.JSON_MODE)); }

       @Override public Optional<LlmModel> chatModel(String name) {
           return find(name, false).map(info -> new LlmModel() {
               public ModelInfo info() { return info; }
               public ChatResponse chat(ChatRequest r) {
                   // call Acme with config.baseUrl(), config.apiKey(), info.id(), maxTokens(r, info, 1024), systemText(r), r.messages()
                   // map failures to LlmException(Kind.FAILED / TIMEOUT, "message without keys or prompts")
                   return new ChatResponse(text, List.of(), info.id(), "stop", new Usage(in, out, info.cost(in, out)));
               }
           });
       }

       public static final class Factory implements ProviderFactory {
           public String type() { return "acme"; }
           public LlmProvider create(ProviderConfig c) { return new AcmeProvider(c); }
       }
   }
   ```
2. **Register it** (1 minute): either list `com.example.AcmeProvider$Factory` in
   `META-INF/services/com.ash.drishti.ai.ProviderFactory` (a jar on the server's class path is enough), or declare the factory as a
   Spring `@Bean`.
3. **Write the file** (2 minutes): `config/ai/<name>.yaml` (here `acme.yaml`; the shipped providers are `config/ai/*.yaml.example`) with `type: acme`, `base-url`, `api-key: ${ACME_KEY}` and the models.
   Anything extra you need goes in the file under your own keys; read it with `config.setting("key", default)` (nested maps are
   flat: `a.b.c`, lists `a.0.b`).
4. **Test it** (15 minutes) with the `StubServer` pattern from `kernel/drishti-ai/src/test/.../StubServer.java` (a loopback HTTP server, no
   network) and the `mock` provider for everything that sits above you. Then Admin -> AI shows your provider and
   `POST /api/v1/admin/ai/test` sends one real call.

The console needs nothing: it calls the server's layer only.

#### 27.1.4 Policy

`drishti.ai.policy.*` (Admin does not edit these; they are configuration):

| Setting | Meaning |
|---|---|
| `mask-redacted` (true) | Fields named in `drishti.security.redact` are masked in every prompt: `salary: 90000` becomes `salary: •••` (also `name=value` and `"name":"value"`). This is in addition to the masking a page already applied. |
| `mask-patterns` | Extra regular expressions replaced by `•••` in every prompt (card numbers, ...). |
| `audit` (true) | One access-log row per call, action `ai`: user, feature, `provider/model`, prompt and answer sizes, tokens, outcome, milliseconds. **Never the prompt or the answer.** |
| `per-user-per-minute`, `per-user-per-day` | Call limits (0 = none); `features.<feature>.per-user-per-minute` overrides per feature. `RATE_LIMITED` (HTTP 429, DRS-4017). |
| `cost-per-user-per-day` | Stops further calls once a user's cost (from the models' prices) reaches it. |
| `features.<feature>.roles` | Roles allowed to use a feature; empty or absent = every signed-in user. `POLICY` (HTTP 403, DRS-4018). |

Feature names: `ask`, `admin-test`, and `api:<name>` for callers of `POST /api/v1/ai/chat` (the prefix stops an API caller posing as a
built-in feature). Replace the whole policy by providing your own `LlmPolicy` bean.

#### 27.1.5 REST

| Call | Who | What |
|---|---|---|
| `GET /api/v1/admin/ai` | admin | on/off, folder, provider types, providers with health, models, file errors, MCP servers. |
| `GET /api/v1/admin/mcp/{server}/tools` | admin | an MCP server's tools ([27.2](#272-the-mcp-client-other-servers-tools-behind-drishtis-policy)). |
| `POST /api/v1/admin/ai/test` `{model?, prompt?}` | admin | a real call through the policy; audited (`ai-test`); token scope `ai:admin`. |
| `GET /api/v1/ai/models` | signed in | the models features may name. |
| `POST /api/v1/ai/chat` `{model?, system?, messages? \| prompt?, temperature?, maxTokens?, jsonMode?, timeoutSeconds?, feature?}` | signed in | the feature-facing API (used by the console's `server` provider); token scope `ai:use`. |

Errors: DRS-4015 off (404), DRS-4016 failed (502) or slow (504), DRS-4017 over a limit (429), DRS-4018 policy (403), DRS-4019 unknown
model, provider or server (404). Health in Admin -> AI comes from configuration and from the calls seen: `OFF`, `ERROR` (the file
or its type failed), `UNKNOWN` (no call yet), `UP`, `DOWN` (the last call failed). No probe is sent on its own; **Test** sends one.

#### 27.1.6 The mock provider

`type: mock` (server and console): deterministic, no network, so features and tests run end to end without a real model. Settings
under `mock:`: `reply` (fixed text; omit to echo `echo: <last user message>`), `latency-ms`, `fail` (every call fails), `fail-every`
(every Nth), `dimensions` (embedding size), and `rules` (the first whose regex is found in the last user message applies:
`reply`, `tool` + `tool-args` for a tool-call script, `error`, `latency-ms`). Usage is characters / 4, priced by the model's
`cost-per-1k-*`, so limits and audit can be exercised. `config/ai/mock.yaml.example` is a complete file.

#### 27.1.7 Ask on the AI layer

Ask about this page ([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help)) builds its prompt and calls the gateway with feature `ask`.
Point it at a model with `drishti.explain.ask.model: <provider>/<model>`. The original settings (`endpoint`, `api`, `api-key`) are
**deprecated but unchanged in behaviour**: they become provider `ask` (with the endpoint split into `base-url` and path), work even
when `drishti.ai.enabled` is false, and log a warning at start. A provider file may not be named `ask` while they are set.

##### 27.1.7.1 BI uses (stage 2 of Rūpaka)

Seven features use the layer through `LLMFactory.model(...)` ([27.1.10](#27110-one-design-in-two-languages-the-sajhamcpserver-abstractions)): **`bi-draft`** (report from words), **`bi-ask`**
(ask the data), **`bi-explain`** (the narrative of "explain this number", only when `drishti.bi.ai.values` is `aggregates`), **`bi-suggest`** (the designer copilot's visuals, wells and filters),
**`bi-explain-checks`** (the Check panel's problems in plain words), **`bi-helper`** (the dataset helper's relationships, measures and column descriptions) and **`subscription-narrative`** (the written
summary at the top of a subscription's email, made **as the recipient** from their own unmasked figures, only in `aggregates` mode). Each is a feature name for the policy (section 27.1.4), so masks,
per-feature roles and limits, the cost limit and the audit row apply; each runs under the caller's identity and sends the dataset **model** (names, types, descriptions) and the person's words,
never a row. Point them at a model with `drishti.bi.ai.model`. Details, settings, errors (`DRS-4020`: an answer that cannot be used) and tests: [GOVERNANCE_AND_AI.md section 29](bi/GOVERNANCE_AND_AI.md#1-ai-in-bi-report-from-words-ask-the-data-explain-this-number).

#### 27.1.8 The console

The console calls no model and no MCP server itself ([THIN_CONSOLE_DESIGN.md](../architecture/THIN_CONSOLE_DESIGN.md), step S7): keys,
policy, masking, limits and audit have one home, the server. Console features call the console's `POST /api/ai/chat` (JSON only, signed
in, feature `console:<name>`), which passes the call to the server's `POST /api/v1/ai/chat` as the person. **Admin -> AI** is a read-only
page of the server's layer: on or off, providers with health and models, MCP servers and (one server at a time, by link) their tools,
and the AI tools the hosted modules describe. **Test connection** beside a provider makes one live call to its first model on the server
(`POST /api/v1/admin/ai/test`, audited there); **Retry now** asks the server to try again what failed. A site that configured a provider
only on the console moves the file to the server's `config/ai` (the same format).

#### 27.1.9 Tests

`drishti-ai`: `AiProxyTest` (the proxy: credentials from the environment, the site default, `proxy: none`, no-proxy, a literal password refused), `LlmLayerTest` (mock, files, registry and reload, a stub OpenAI-compatible server incl. headers, cost and error
handling, a stub Anthropic server, policy), `McpLayerTest`. `drishti-server`: `AiApiTest` (REST end to end with the mock),
`AskEndToEndTest` and friends (Ask on the layer, incl. the legacy settings), `AskOffByDefaultTest`. None touches a network beyond
loopback. The providers of section 27.1.11 have their own tests ([27.1.11.10](#2711110-tests)).

#### 27.1.10 One design in two languages: the sajhamcpserver abstractions

The AI layer exposes the **same abstractions as the user's other project, sajhamcpserver** (`sajha/ai/llm/`), in Java (`drishti-ai`, package
`com.ash.drishti.ai.llm`; the console's Python copy was removed with the thin console, step S7), so that code written against one reads like code written against the other. sajhamcpserver is the model; nothing is imported from it. Two things differ on purpose: the
extension field of a request and a response is **`drishti`** (sajhamcpserver's is `sajha`), and the Java names are the Python names in camelCase.

**Layers.** Under the abstractions the *wire* providers are unchanged (`openai` through Spring AI, `anthropic`, `mock`, and in the console `server`): they speak the small request and response types of the
earlier layers (`ChatRequest`, `ChatResponse`) and carry the HTTP, TLS and proxy code. `ProviderBase` implements `LLMProvider` over a wire provider and translates in one place
(`Convert`). The provider file format (`config/ai/<name>.yaml`) is **unchanged**; `from_settings` takes the same block as a map.
The policy (masks, audit, limits, feature roles: section 27.1.4) is applied by `GovernedModel`, which `LLMFactory.model()` hands out; so both the old `LlmGateway.chat` and the new `LLMModel`
calls pass the same policy and count in the same health.

| sajhamcpserver (Python) | Java (`com.ash.drishti.ai.llm`) | Role |
|---|---|---|
| `LLMModel` | `LLMModel` | one model: `info`, `chat_completions_create` / `chatCompletionsCreate`, `achat_completions_create` / `achatCompletionsCreate`, `chat_completions_stream` / `chatCompletionsStream`, `achat_completions_stream` / `achatCompletionsStream`, `embeddings_create` / `embeddingsCreate`, `aembeddings_create` / `aembeddingsCreate` |
| `LLMProvider` | `LLMProvider` | `from_settings`, `active`, `models`, `model(name, kind)`, `chat_model(model_id, **options)`, `embedding_model`, `health`, `describe_config`, `close` (camelCase in Java; `from_settings` is `LLMProviderFactory.fromSettings`, found by type in `LLMProviders.fromSettings`, because a Java interface cannot require a static) |
| `ChatModel`, `EmbeddingModel` | `ChatModel`, `EmbeddingModel` | base classes: implement `_create` / `_acreate` (Java `create`) or `_embed` / `_aembed` (Java `embed`); streaming is emulated from one completion |
| `ProviderBase` | `ProviderBase` | `LLMProvider` over a wire provider |
| `ChatCompletionRequest`, `ChatCompletion`, `ChatCompletionChunk` | same | OpenAI Chat Completions, typed (`coerce` builds a request from keyword fields; Java: `ChatCompletionRequest.coerce(map)`) |
| `EmbeddingsRequest`, `EmbeddingsResponse`, `EmbeddingData`, `EmbeddingsUsage` | same | OpenAI Embeddings |
| `ChatMessage`, `ToolCall`, `FunctionCall`, `ToolDefinition`, `FunctionDefinition`, `ResponseFormat`, `CompletionUsage`, `Choice`, `ChoiceDelta`, `ChunkChoice` | nested in `Canonical` | the parts |
| `DrishtiRequest` (`context`, `trace_id`, `input_purpose`), `ResponseDrishti` (`provider`, `qualified_model`, `cost_usd`, `cached`, `latency_ms`, `trace_id`) | `DrishtiRequest`, `ResponseDrishti` | the extension fields; `context` is the caller (`CallContext`) the policy decides on |
| `ModelInfo` (`id`, `provider`, `kind`, `capabilities`, `display_name`, `source`, `qualified_id`), `ModelCapabilities`, `HealthStatus` | same | catalogue entry; capability costs are per million tokens |
| `register_provider`, `registered_providers`, `provider_class`, `load_class` | `LLMProviders.register`, `.registered`, `.fromSettings` | the provider-type registry (the shipped types are registered at start) |
| `LLMFactory.model(name, *, kind, context)` -> `GovernedModel`, `.provider(name)`, `.provider_names()`, `.provider_types()` and the six passthrough calls | `LLMFactory.model(name, kind, context)`, `.provider`, `.providerNames`, `.providerTypes` and the passthroughs | the one door application code uses; policy applied |
| `LLMError` and `RateLimited`, `ProviderUnavailable`, `AuthenticationFailed`, `ContextTooLong`, `ContentFiltered`, `UnsupportedFeature`, `ModelFailed`, `InvalidRequest`, `PolicyDenied`, `BudgetExceeded`, `NoModelAvailable`, `ConfigurationError` | `LlmException` with `Kind` | each Python class has a `kind` equal to the Java `Kind`: `FAILED`, `UNSUPPORTED`, `NOT_FOUND`, `POLICY`, `RATE_LIMITED`, `TIMEOUT`, `CONFIG` |
| `BaseMCPTool` | `BaseMcpTool` | the tool base class: `execute`, `get_input_schema`, `get_output_schema` (abstract), `validate_arguments`, `execute_with_tracking`, `get_metrics`, `to_mcp_format`, `load_from_config`, `enable` / `disable` ([27.2](#272-the-mcp-client-other-servers-tools-behind-drishtis-policy) section 27.1.7) |

```python
factory = request.app.state.ai.llm                                   # an LLMFactory over the console's gateway and policy
model = factory.model("default", context=CallContext(user, roles, "my-feature", ident))
completion = await model.achat_completions_create(messages=[{"role": "user", "content": "hello"}])
completion.text, completion.usage.total_tokens, completion.drishti.cost_usd
```

```java
LLMModel model = factory.model("default", "chat", new CallContext(user, roles, "my-feature"));
ChatCompletion c = model.chatCompletionsCreate(ChatCompletionRequest.of("default", "be brief", "hello"));
c.text(); c.usage().totalTokens(); c.drishti().costUsd();
```

**What differs from sajhamcpserver, honestly.** Streaming is **emulated** (a completion cut into role, text and finish chunks; the signatures are final, the wire providers do not stream yet); the
canonical request fields beyond those in `ChatCompletionRequest` (images, logprobs, ...) are not carried to the wire providers; Python's `**options` of `chat_model` and Java's options map are accepted
and ignored; the fallback, cache, budget and breaker layers of sajhamcpserver's `Governor` are not built (Drishti's policy is section 27.1.4). The registry, factory and settings are the same shape; the layered
environment overrides (`SAJHA_AI_*`) are Drishti's own `DRISHTI_AI_*` and the provider files.

**Tests.** One shared scenario file, `kernel/drishti-ai/src/test/resources/contract/llm_contract.json` (catalogue, chat, tool call, a scripted failure, a stream, embeddings, refusals), is run against the mock
by `LLMContractTest`; `BaseMcpToolTest` covers the tool base.

#### 27.1.11 More providers, the way sajhamcpserver has them

Seventeen provider types joined `openai`, `anthropic`, `server` and `mock`, mirroring `sajha/ai/llm/providers/*.py` of sajhamcpserver: the type names, the settings (sajha's `snake_case` fields are
`kebab-case` keys of the provider file), the model catalogue and the presets. They sit on the same abstractions (section 27.1.10), in **both** languages, behind the same policy (masks, audit,
limits, roles, labels-only: section 27.1.4). **Every one is off until you copy its example from `config/ai/<type>.yaml.example` to `config/ai/<name>.yaml`, set `enabled: true`** and
`drishti.ai.enabled=true`.

| `type:` | Vendor and API | Server (Java, `com.ash.drishti.ai`) |
|---|---|---|
| `groq`, `together`, `fireworks`, `deepseek`, `xai`, `openrouter`, `perplexity`, `huggingface`, `vllm`, `lmstudio`, `mistral`, `openai_compatible` | OpenAI Chat Completions | `OpenAiCompatProvider` | `openai_compat.py` |
| `azure_openai` | Azure OpenAI: api key, bearer or Microsoft Entra ID | `AzureOpenAiProvider`, `EntraTokenSource` |
| `gemini` | Google Gemini API (AI Studio) | `GeminiProvider` |
| `cohere` | Cohere v2 | `CohereProvider` |
| `ollama` | a local Ollama server, no key | `OllamaProvider` |
| `bedrock` | Amazon Bedrock: Converse, InvokeModel, ListFoundationModels | `BedrockProvider` (AWS SDK v2) |

**What every one of them does.**

* **Chat** with text turns, a system text, tools (OpenAI-style `tool_calls` mapped to and from the vendor's shape), JSON mode, temperature and the max-token limit; **embeddings** where the API has them
  (OpenAI-compatible, Azure, Gemini, Cohere, Ollama, Bedrock); the cost of a call from the catalogue prices.
* **Streaming** is the layer's emulation (section 27.1.10): the signature is final, the wire call is one request.
* **Model listing.** `models()` is the catalogue (`ai/llm_catalog.json`, mirrored from sajha's `catalog.py`, prices per million tokens) plus the file's `models:`; a file model with the same name wins, and
  `catalogue: false` keeps only the file's. `liveModels()` asks the endpoint what it serves now and returns the ones not already listed, with source `live`: `GET /models`
  for the keyless OpenAI-compatible servers (`vllm`, `lmstudio`, `openai_compatible`; `live-models: true` turns it on for others), `/api/tags` for Ollama, `/v1beta/models` for Gemini, `/v1/models` for Cohere,
  `ListFoundationModels` for Bedrock. A vendor's new model id is a line in `models:`, never a code change. A logical name is the id with characters outside `A-Za-z0-9._-` replaced by `_`
  (`openai/gpt-oss-20b` is `openai_gpt-oss-20b`); `groq/llama-3.3-70b-versatile` addresses a model as `<provider>/<logical name>`.
* **Health.** `health()` never calls the network: it is *down* for `enabled: false` and for a missing key, credential or region (with the variable to set), else *ok*; once a certificate given by `tls.*` is
  less than 30 days from expiry the text carries the sentence of [DATA_GUIDE.md section 10](DATA_GUIDE.md#109-certificate-expiry). `probe()` (Python also `aprobe()`) makes one cheap live call (the model list; Ollama
  `/api/tags`) and answers the same shape; Admin shows both.
* **`describe_config()`** shows the file with every secret as `********` (the key, `header.*` credentials, `proxy.password`, `aws-*`, `client-secret`, `tls.*` passwords); the file's other settings are shown as written.
* **Keys only as `${ENV}`.** `api-key`, `aws-access-key-id`, `aws-secret-access-key`, `aws-session-token`, `client-secret` and the `tls.*` passwords accept an `${ENV_NAME}` placeholder or nothing; a literal is
  refused at load, naming the setting. When `api-key` is left out, a preset reads the vendor's own variable (the table below).
* **Proxy.** The shared `proxy.*` block (or the site default) applies to every call, an `https://` target tunnelled with CONNECT: [DATA_GUIDE.md, the outbound proxy](DATA_GUIDE.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers).
* **TLS.** The shared `tls.*` block of the connectors applies to every provider type (section 27.1.11.9).
* **Errors** name the cause and never carry the response body: HTTP 401/403 `(authentication failed ...)`, 404 `(not found: check base-url, the path and the model id)`, 429 kind `RATE_LIMITED`, a slow
  endpoint kind `TIMEOUT`, anything else `FAILED`. `retries: n` tries again after a 5xx, a 429 or a connection failure.

##### 27.1.11.1 OpenAI-compatible presets

A preset is a base URL, a key variable and a catalogue; the code is the same. `openai_compatible` has none of them: give `base-url`.

| `type:` | Default `base-url` | Key read from (when `api-key` is left out) | Needs a key | Embeddings |
|---|---|---|---|---|
| `groq` | `https://api.groq.com/openai/v1` | `GROQ_API_KEY` | yes | no |
| `together` | `https://api.together.xyz/v1` | `TOGETHER_API_KEY` | yes | yes |
| `fireworks` | `https://api.fireworks.ai/inference/v1` | `FIREWORKS_API_KEY` | yes | yes |
| `deepseek` | `https://api.deepseek.com/v1` | `DEEPSEEK_API_KEY` | yes | no |
| `xai` | `https://api.x.ai/v1` | `XAI_API_KEY` | yes | no |
| `openrouter` | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` | yes | no |
| `perplexity` | `https://api.perplexity.ai` | `PERPLEXITY_API_KEY`, `PPLX_API_KEY` | yes | no |
| `huggingface` | `https://router.huggingface.co/v1` | `HF_TOKEN`, `HUGGING_FACE_HUB_TOKEN` | yes | no |
| `vllm` | `http://localhost:8000/v1` | `VLLM_API_KEY` | no | yes |
| `lmstudio` | `http://localhost:1234/v1` | `LMSTUDIO_API_KEY` | no | yes |
| `mistral` | `https://api.mistral.ai/v1` | `MISTRAL_API_KEY` | yes | yes (`mistral-embed`) |
| `openai_compatible` | none (required) | `api-key` only | no | yes |

```yaml
# config/ai/fast.yaml
type: groq
enabled: true
api-key: ${GROQ_API_KEY}
timeout: 60s
default-model: llama-3.3-70b-versatile
models:                                  # optional: add to or override the catalogue
  - {name: gpt-oss, id: openai/gpt-oss-20b, capabilities: [chat, tool-calls, json-mode], cost-per-1k-input: 0.000075, cost-per-1k-output: 0.0003}
```

Settings: `base-url`, `completions-path` / `chat-path` (`/chat/completions`), `embeddings-path` (`/embeddings`), `max-tokens-param` (`max_tokens`; `max_completion_tokens` for a server that wants it; Mistral
declares `max_tokens`), `live-models`, `models-path` (`/models`), `header.<Name>` (extra headers; a credential as `${ENV}`), `retries`, `proxy.*`, `tls.*`. A request goes to
`POST {base-url}/chat/completions` with `Authorization: Bearer <key>`:

```json
{"model": "llama-3.3-70b-versatile", "messages": [{"role": "user", "content": "ping"}], "max_tokens": 1024, "stream": false}
```

Built: chat, tools, JSON object mode, embeddings, model list. Not built (the layer's request type does not carry them yet): `tool_choice` and the vendor spellings of it (`tool-choice-required`),
`seed`, `parallel_tool_calls`, `reasoning_effort`, `strict` JSON schemas, images.

##### 27.1.11.2 Azure OpenAI

```yaml
# config/ai/azure.yaml
type: azure_openai
enabled: true
base-url: https://my-resource.openai.azure.com        # also AZURE_OPENAI_ENDPOINT
api-style: v1                                          # v1: /openai/v1/chat/completions, the deployment as "model"
auth: api_key                                          # api_key: the api-key header | bearer | entra
api-key: ${AZURE_OPENAI_API_KEY}
deployments:                                           # model id: deployment name; each is also a model of this provider
  gpt-4o: my-gpt4o-deployment
  text-embedding-3-small: my-embedding-deployment
```

`api-style: deployments` addresses `/openai/deployments/<deployment>/chat/completions?api-version=<api-version>` (`api-version` defaults to 2024-10-21). The limit is sent as
`max_completion_tokens`. **Microsoft Entra ID** (`auth: entra`): `tenant-id`, `client-id`, `client-secret: ${AZURE_CLIENT_SECRET}` (the client-credentials grant) or `federated-token-file` (workload identity, the
grant with a client assertion); `entra-mode: auto | client_secret | workload_identity`, `entra-scope` (`https://cognitiveservices.azure.com/.default`), `entra-authority`
(`https://login.microsoftonline.com`). The token is cached until a minute before it expires and is fetched through the provider's own proxy and TLS settings. **Built in both languages without an SDK; managed identity
(the instance metadata endpoint) is not built**, and a file that asks for it (`entra` with no secret and no federated file) is *down* with that sentence. Azure has no model-list call every key may make: the models
are the `deployments` (and `models:`).

##### 27.1.11.3 Gemini

```yaml
# config/ai/gemini.yaml
type: gemini
enabled: true
api-key: ${GEMINI_API_KEY}              # GEMINI_API_KEY or GOOGLE_API_KEY is also read when this line is left out
```

`POST {base-url}/v1beta/models/<id>:generateContent` with `x-goog-api-key`; embeddings by `:batchEmbedContents` (`embedding-task-type`, default `RETRIEVAL_DOCUMENT`). `schema-mode: json_schema`
sends tool schemas as `parametersJsonSchema`; `openapi` sends the OpenAPI subset as `parameters`. `api-version` (v1beta), `base-url` (also `GEMINI_BASE_URL`). **Vertex AI (`platform: vertex`) is not built**
and is refused at load.

##### 27.1.11.4 Mistral

`type: mistral` is the OpenAI-compatible provider with Mistral's declarations (`max_tokens`, key `MISTRAL_API_KEY`, `mistral-embed`). Copy `config/ai/mistral.yaml.example`; the settings are those of 11.1.

##### 27.1.11.5 Cohere

```yaml
type: cohere
enabled: true
api-key: ${COHERE_API_KEY}              # COHERE_API_KEY or CO_API_KEY is also read when this line is left out
```

`POST /v2/chat` and `/v2/embed` with a bearer key (`chat-path`, `embed-path`, `embedding-input-type` default `search_document`); the model list is `GET /v1/models`. The `document` tool-result format is not built.

##### 27.1.11.6 Ollama

```yaml
type: ollama
enabled: true
base-url: http://localhost:11434         # also OLLAMA_HOST; a bare host:port gets http://
timeout: 300s                            # a model that is not loaded yet is slow to answer
options: {top_k: 40}
```

No key. `POST /api/chat` (`stream: false`; tool-call ids are made up as `call_<n>`), `/api/embed`; `options.<name>` (any runtime option), `num-ctx`, `keep-alive`, `think`. An optional `api-key` is sent as a bearer
token, for a proxy in front of Ollama. `live_models()` lists what is pulled (`/api/tags`); `probe()` says `http://host:11434: 2 model(s) pulled`, or that it is not answering, without raising. `/api/show` capability
detection is not built: declare `capabilities` in `models:`.

##### 27.1.11.7 AWS Bedrock

Built in **both** languages on the AWS SDKs, so the whole AWS credential chain works: environment keys, a shared-credentials `profile`, SSO, container and instance roles, web identity (IRSA). Java uses the
SDK v2 modules `bedrockruntime` and `bedrock` (with the JDK's HTTP clients only, as the S3 connector does: no Netty); Python uses `boto3` (in `requirements.txt`).

```yaml
# config/ai/bedrock.yaml
type: bedrock
enabled: true
region: us-east-1                        # also AWS_REGION / AWS_DEFAULT_REGION
# profile: my-profile                    # else the default chain
# aws-access-key-id: ${AWS_ACCESS_KEY_ID}
# aws-secret-access-key: ${AWS_SECRET_ACCESS_KEY}
# aws-session-token: ${AWS_SESSION_TOKEN}
default-model: anthropic.claude-sonnet-5-5
```

Chat is the **Converse** API (`POST /model/<id>/converse`, system, messages, `inferenceConfig`, `toolConfig`); embeddings are **InvokeModel** (`/model/<id>/invoke`: Titan `inputText`, one call per text; Cohere Embed
`texts`); the live list is `ListFoundationModels`. Settings: `region`, `profile`, the three `aws-*` keys as `${ENV}`, `base-url` (an endpoint override: a VPC endpoint, or a fake in a test), `control-url` (the
same for the model list), `embedding-input-type`, `timeout`, `retries` (SDK standard retries), `proxy.*`, `tls.*`. Every request is signed with SigV4 by the SDK (the tests check the `Authorization` header's
`AWS4-HMAC-SHA256 Credential=<key>/<date>/<region>/bedrock/aws4_request`, the date and the session token). Not built: guardrails, `additional-model-request-fields`, the `document` and image content blocks,
and `tls.verify-hostname: false` (both AWS clients always check the name; the file is refused with that reason).

##### 27.1.11.8 Keys, environment variables and what `describe_config()` shows

```json
{
  "name": "fast",
  "type": "groq",
  "enabled": true,
  "baseUrl": "https://api.groq.com/openai/v1",
  "apiKey": "********",
  "timeoutSeconds": 60.0,
  "defaultModel": "llama-3.3-70b-versatile",
  "models": ["llama-3.3-70b-versatile", "llama-3.1-8b-instant", "..."],
  "settings": {"catalogue": "true", "header.Authorization": "********", "header.X-Team": "red",
               "proxy.password": "********", "proxy.url": "http://proxy.corp:3128", "proxy.username": "svc"},
  "tls": []
}
```

With the key variable unset, `health()` says `down` and what to set (captured): `{'status': 'down', 'detail': 'no api key: set GROQ_API_KEY (or api-key: ${ENV_NAME})'}`.

##### 27.1.11.9 TLS: the same block as the connectors

Every provider type (and the console's MCP client) reads the connectors' `tls:` block ([DATA_GUIDE.md section 10](DATA_GUIDE.md#10-tls-and-the-outbound-proxy) is the reference; in a provider file `tls:` is flattened to `tls.*` as everywhere):
trust (`ca-file` as a PEM file or the PEM text, `truststore`, `trust-jvm-default`), a client identity for mutual TLS (`cert-file` + `key-file` + `key-password`, or `keystore`), `protocols`, `cipher-suites`,
`verify-hostname` (on by default), `insecure-trust-all` (refused unless `DRISHTI_ALLOW_INSECURE_TLS=true`). Passwords are `${ENV}` placeholders or `...-password-file`, never literals. A `tls.*` key against an
`http://` address is an error at load, never a silent downgrade. Through an HTTP proxy the same settings hold (CONNECT tunnel, TLS end to end). The expiry of every certificate given is read at start and shown in
`health()` and `describe_config()["tls"]` (Admin → AI) as the connectors show it.

```yaml
type: vllm
base-url: https://llm.bank.example:8443/v1
tls:
  ca-file: /etc/drishti/tls/bank-ca.pem
  cert-file: /etc/drishti/tls/drishti.pem        # mutual TLS
  key-file: /etc/drishti/tls/drishti.key
  key-password-file: /run/secrets/drishti-key-password
```

Differences by language and type, honestly: the **server** reads the full set (PEM, PKCS12, JKS, all keys). The **console** reads PEM and **PKCS12** (a JKS store is refused with the `keytool -importkeystore` command that
converts it), `protocols` as `TLSv1.2` / `TLSv1.3`, `cipher-suites` in OpenSSL names, and `trust-jvm-default` means the system's authorities. **Bedrock** takes the CA, the truststore and the client identity (both
SDKs accept a trust and key manager or a bundle) but not `verify-hostname: false` or `protocols`. Hostname verification uses the address you connect to, so a private-CA certificate needs it as a SAN.

##### 27.1.11.10 Tests

No test touches a network beyond loopback.

* Per provider, in both languages, against a fake server that speaks that API (`kernel/drishti-ai/src/test/java/com/ash/drishti/ai/FakeLlmServer.java`, `drishti-console/tests/llm_fakes.py`; the same rules: the last user text is
  echoed, `weather` asks for the tool, `boom` is 500, `limit` is 429, `slow` sleeps): the request (path, auth header, body fields), a reply with usage and cost, a tool call, embeddings, the model list, 401, 429,
  500 (the body never appears in the message), a timeout, retries, health, masking, the proxy, Azure's three auth modes and both address styles, Bedrock's SigV4 header and session token. `HttpProvidersTest`.
* The **shared contract** `llm_contract_http.json` (catalogue, chat, tool call, system turn, 500, 429, stream, embeddings, refusals) runs against an OpenAI-compatible preset, Ollama, Gemini, Cohere and Bedrock in
  `HttpProvidersContractTest`; the older `llm_contract.json` still runs against the mock.
* **TLS** with a test CA (`TestPki`): the private CA is trusted only when configured, mutual TLS is refused without the client certificate and accepted with it (PEM, encrypted
  key, PKCS12, JKS), the expiry note, the plain-address error, a CONNECT proxy tunnel, every provider type and Bedrock: `ProviderTlsTest`.
* The catalogue file is `kernel/drishti-ai/src/main/resources/ai/llm_catalog.json` (one copy).

### 27.2 The MCP client: other servers' tools, behind Drishti's policy

Drishti can act as a client of **MCP (Model Context Protocol)** servers: list their tools, resources and prompts, call a tool, read a
resource. Like the LLM layer ([27.1](#271-model-providers)) it is interface-driven, configured by one file per server,
**off by default** (`drishti.mcp.enabled=false`), and lives on the server (`drishti-ai`, package `com.ash.drishti.ai`); the console calls
no MCP server itself.

#### 27.2.1 Interfaces

| Type | Role |
|---|---|
| `McpClient` | One server connection: `connect()`, `tools()`, `resources()`, `prompts()`, `callTool(name, args)`, `readResource(uri)`, `close()`. |
| `McpClientFactory` | The extension point per `transport:` value (ServiceLoader `META-INF/services/com.ash.drishti.ai.McpClientFactory`, or a Spring bean). |
| `McpRegistry` | Servers by logical name, health, `reload()` (watched like connectors). Connections open on first use. |
| `McpGateway` | The one door: policy, masking, audit, health around every call. |

**Implementation choice.** Spring AI has MCP client starters, but they bring their own transports and sessions and an
auto-configuration per server; the protocol part Drishti needs is small (JSON-RPC over HTTP), so `StreamableHttpMcpClient` is a
~250-line client over the JDK HTTP client (httpx in the console) that shares the `tls.*` module and the policy. A heavier SDK can
be added later as another `McpClientFactory` without touching callers.

Transports: `streamable-http` (the MCP Streamable HTTP transport: one POST endpoint, JSON or Server-Sent-Events answers, the
`Mcp-Session-Id` kept and sent back, `MCP-Protocol-Version` negotiated at `initialize`, session closed with DELETE), `mock` (in-process,
scripted), and in the console `server` (delegates to the Drishti server). The legacy HTTP+SSE transport and stdio are not
implemented (stdio would run a local process; not wanted on a server by default).

#### 27.2.2 Server files

One YAML per server in `drishti.mcp.dir` (`DRISHTI_MCP_DIR`, default `./config/mcp`), reloaded live:

```yaml
transport: streamable-http
url: https://tools.example/mcp
timeout: 30s
headers:
  Authorization: Bearer ${TOOLS_TOKEN}     # ${ENV} placeholders only; a credential header without one is refused
allowed-tools: [calc_percentage_change]    # empty = all the server lists; others can be neither listed nor called
tls: {ca-file: /etc/drishti/ca.pem}        # the shared tls.* keys
proxy: {url: http://proxy.corp.example:3128, username: svc-drishti, password: ${PROXY_PASSWORD}}   # optional; `proxy: none` goes direct
```

##### 27.2.2.1 Outbound proxy

A Streamable HTTP server is reached through the file's `proxy:` block, the site default (`drishti.http.proxy.*`; in the console the `DRISHTI_HTTP_PROXY_*` environment
variables) or directly, exactly as an AI provider and a connector are: `http://` proxy, https servers tunnelled with `CONNECT` and keeping `tls.*`, the password an `${ENV}` placeholder
(a literal refuses the file), a wrong login reported without the password. The initialize handshake, `tools/list`, calls and the closing DELETE all use it. Reference:
[DATA_GUIDE.md, the outbound proxy](DATA_GUIDE.md#proxy-9-the-console-ai-and-mcp-clients). The `mock` transport has no network and ignores it.

A server is **connected on first use**. Two things can go wrong, and both are retried by themselves (`drishti.retry.mcp.*`, defaults: 5 s,
doubling, up to 5 min, jitter; the console has `retry.mcp.*`):

- the file is fine but the server cannot be set up (its transport is not installed, a header's `${ENV}` is not set): Admin -> AI lists it **ERROR**
  with *failed - next retry in Ns (attempt k)* and builds it again on the schedule;
- the connection was lost (the last call failed, so the server shows **DOWN**): it is listed the same way, and when its retry is due the
  client is closed and connected afresh (a new handshake). When that works the server is **UP** again and the line disappears; open
  tool lists simply work on the next call. Calls made while it is down are still attempted (they are not held back).

**Retry now** (Admin -> AI, `POST /api/v1/admin/retry/mcp`, admin, audited as `mcp-retry-now`) reconnects at once. Failures are logged and
audited on attempts 1, 2, 4, 8, ... (`mcp-retry-failed`) and recoveries always (`mcp-recovered`). The console's MCP client does the same with `retry.mcp.*`.

#### 27.2.3 Policy (`drishti.mcp.*`)

| Setting | Meaning |
|---|---|
| `server-roles.[server]` | Roles allowed to use a server (absent = every signed-in user). |
| `tool-roles.[server/tool]` | Roles allowed to see and call one tool. |
| `server-features.[server]` | Features allowed to use a server. |
| `audit` (true) | Access-log row per call, action `mcp`: user, feature, `server/tool`, sizes, outcome, ms. Never arguments or results. |
| masking | Arguments named in `drishti.security.redact` are replaced by `•••`; string arguments and every result pass through the same masker (`drishti.ai.policy.mask-patterns`). |
| timeouts | the file's `timeout`. |

REST: `GET /api/v1/admin/mcp/{server}/tools` (admin), `POST /api/v1/ai/mcp/{server}/tools/{tool}` `{arguments, feature}` (signed in; token
scope `ai:use`). Errors as the LLM layer (DRS-4015..4014). Admin -> AI lists the servers and, by link, their tools.

#### 27.2.4 The mock server

`transport: mock`: scripted tools (`mock.tools.N.{name, description, reply, error}`; `${arg}` in `reply` is replaced), resources
(`uri`, `text`) and prompts, no network. `config/mcp/mock.yaml.example`.

#### 27.2.5 Connecting to SAJHA (the user's MCP server)

SAJHA (`sajhamcpserver`) serves one `/mcp` endpoint (Streamable HTTP, also SSE, WebSocket and stdio) for both spec eras, chosen per
request; Drishti speaks the session-based 2025-11-25 one. Default `http://localhost:3002/mcp`. Anonymous callers see **no tools**
by default, so Drishti needs a credential: an `X-API-Key` (Admin -> API keys in SAJHA) or `Authorization: Bearer <JWT>`. Copy
`config/mcp/sajha.yaml.example` to `sajha.yaml` in the same folder, export `SAJHA_API_KEY`, set `drishti.mcp.enabled=true`.

Verified live (a scratch copy of SAJHA on a spare port, its own virtualenv; the project itself untouched), with
`kernel/drishti-ai/src/test/java/com/ash/drishti/ai/SajhaMcpLiveTest.java` (skipped unless `DRISHTI_SAJHA_MCP_URL` and `DRISHTI_SAJHA_MCP_KEY`
are set) and the console client:

```
$ DRISHTI_SAJHA_MCP_URL=http://127.0.0.1:18991/mcp DRISHTI_SAJHA_MCP_KEY=<test key> ./mvnw -o -pl :drishti-ai test -Dtest=SajhaMcpLiveTest
SAJHA tools: 501, e.g. [av_ad_line, av_adx, av_aroon, av_atr, av_balance_sheet]
calc_percentage_change -> { "old_value": 80, "new_value": 100, "percentage_change": 25.0 }
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

To repeat it by hand: copy the SAJHA project to a scratch folder (it writes `data/` and `logs/` next to itself), create a virtualenv,
`pip install -r requirements.txt` (at the time of writing `sse-starlette<3` in that file conflicts with `mcp>=2.3`; relax it in the
copy), seed SAJHA's own config/apikeys.json from its config/apikeys.json.example (files of the SAJHA project, not of this repository), then
`SERVER_HOST=127.0.0.1 SERVER_PORT=18991 python run_sajha_web.py`, run the test above, and stop the process by its PID.

#### 27.2.6 Tests

`AiProxyTest` (the MCP client through a fake proxy, plain and `CONNECT` to a TLS server with its own CA), `McpLayerTest` (mock under policy: roles, masking, `allowed-tools`, credential headers; the Streamable HTTP client against a loopback
stub: handshake, session id, JSON and event-stream answers, DELETE on close), `AiApiTest` (REST).

#### 27.2.7 The tool base class

A tool (something an MCP server serves, or a locally defined one) is written against `BaseMcpTool` (Java, `com.ash.drishti.ai.llm`), whose abstract methods and signatures
are those of sajhamcpserver's `sajha/tools/base_mcp_tool.py`: `execute(arguments)`, `get_input_schema()` and `get_output_schema()` (Java: `execute`, `getInputSchema`, `getOutputSchema`). A tool is configured by a map
(`name`, `description`, `version`, `enabled`), or `load_from_config(path)`; `validate_arguments` checks the required and (with `additionalProperties: false`) the known names; `execute_with_tracking` validates, runs
and counts (`get_metrics`: calls, errors, last call, last error); `to_mcp_format()` is the `tools/list` entry. The client side (calling a remote server's tools, section 27.2.1) is unchanged. The mapping table of the whole
layer is in [27.1](#271-model-providers), its 27.1.10.

## 28. Write-back and approvals: switch on and run

Write-back is the part of Drishti that lets people change records from forms, the command line and the API. Using it (correcting a record,
drafts, submitting, the Approvals queue) is in the [User guide](USER_GUIDE.md#approvals); this section is for the administrator who switches it on
and runs it.

### 28.1 Switching write-back on

#### 28.1.1 Off at three levels

Write-back is **off as shipped**, at three levels, and the ones a change needs must all be on before anything is written:

| Level | Off because | Turned on by |
|---|---|---|
| the site | `edit.enabled: false` in `config/edit/edit.yaml`: no change is accepted anywhere and no Submit is drawn | an administrator: `edit.enabled: true` (or `DRISHTI_EDIT_ENABLED=true`) and a restart |
| the field | a field is editable only when a Sutra's `form:` has `save:` | a Sutra author, through review like any Sutra change ([BUILD_GUIDE.md](BUILD_GUIDE.md)); the trading pack's book desk ships with `save:` (corrections of a trade, one approver) |
| the source | a connector writes to its source only when its file declares `writes:` and its writes are switched on | an administrator: the connector file's `writes:`, then **Switch on** in Admin → Edits (audited) ([28.4](#284-writing-to-a-source-writes)) |

Corrections and owned records need the first two; an amendment sent to a source needs all three. A fresh install or an upgrade is
read-only. With the site switch off, corrections applied earlier are still shown.

#### 28.1.2 Switch it on

```yaml
# config/edit/edit.yaml
edit:
  enabled: true          # or leave ${DRISHTI_EDIT_ENABLED:false} and set the variable
  log: auto
```

Restart the server. The start logs:

```text
write-back: on; edit log sqlite (auto: this node is alone), 64 lanes, node local
```

Then give the people who edit a role with the edit power (Admin → Roles → *Submit edits*; the shipped `admin` role has it), and the
people who approve a role with the approve power, or name them in `policy.approver-roles`. Check it from the command line:

```bash
drishti.py edits status
# {"enabled": true, "mode": "single", "store": "sqlite", "why": "auto: this node is alone", ...}
```

#### 28.1.3 The approval policy

`config/edit/policy.yaml` decides:

| Key | Says |
|---|---|
| `policy.approval.overlay` / `owned` / `source` | approvals a change needs per target: `none`, `one` or `two`. Shipped: `none`, `none`, `one` |
| `policy.kinds.<kind>.approval.<target>` | a stricter rule for one kind |
| `policy.approver-roles` | who approves; empty means holders of the approve power and administrators |
| `policy.self-approval` | `false`: nobody approves their own change (four eyes) |
| `policy.restate-role` | the role a change to a past business date (a restatement) needs |
| `policy.override-role` | the role *Apply mine anyway* over a newer value needs |

A form's `save.approval` may ask for more approvals than the policy, never fewer. Who may do what, in one place:

| To | You need |
|---|---|
| submit any change | the **edit** power on one of your roles; a token also needs the **edit** scope |
| edit a given field | a role the field allows (`editable-by` on the form's field) |
| approve a change | an approver role or the approve power, and **never your own change** |
| *Apply mine anyway* on a conflict | the override role; audited with the overwritten value |
| change a past business date | the restate role |
| switch a connector's writes on | administrator; audited, shown on Admin → Edits |

Everything is checked again when the change is applied, not only when it is submitted: the switch, the edit power, the kind, the masks,
the form's roles and read-only fields. A field hidden from a person can never be edited by them, and a change never reveals a hidden value
(the difference shows the mask for fields the reader cannot see).

### 28.2 The edit log, lanes and the cluster

Every change is a row of the **edit log** (`drishti_edit_log`) in the Drishti database, acknowledged only after it is committed. Changes
run in **lanes** (`lanes.lanes`, 64): a record always maps to the same lane, a lane applies one change at a time in its order, and lanes
apply in parallel. So two changes to one record never happen at once, and a change is never lost once you are told it was received.

Each lane has one owner node, taken in the database with a lease (`lanes.lane-lease`, 10 s). A node that lost its lane (cut off from the
others, or paused) can no longer write anything for it. Where the edit log lives is set by `edit.log: auto`:

| Setup | The edit log | When a node stops |
|---|---|---|
| one node | its SQLite Drishti database | it owns every lane; after a restart it takes its lanes back and applies what was acknowledged |
| a cluster on PostgreSQL | the shared database; lanes spread over the members | another node takes the lanes after the lease, carries on from the last applied change and resends unconfirmed commands |
| a cluster with `edit.primary` | the primary's SQLite; every other node forwards edits to it and copies its corrections | no failover: edits pause with a message while the primary is down; reading carries on |
| a cluster with neither | none | edits are refused in the cluster (`DRS-9306`); reading carries on |

A change can be submitted on any node; it is forwarded to its lane's owner. An explicit `edit.log` (`sqlite`, `postgres`) is checked at
start: `postgres` needs a PostgreSQL Drishti database, `sqlite` in a cluster needs `edit.primary`; otherwise the server starts with
editing off and says why. On AWS, use PostgreSQL (RDS, Aurora) for the Drishti database. Moving the edit primary is a configuration change
made with the old one stopped.

A lane deeper than `lanes.max-lane-depth` (1,000) refuses new changes with *busy, try again* (`503 DRS-9304`); the draft is kept.

### 28.3 Admin → Edits

**Admin → Edits** shows:

- the store in use, and why (the auto rule), and this node;
- each lane that has an owner or waiting changes: owner, epoch, lease, depth, oldest wait;
- the warnings: an edit primary without failover, a lane whose oldest change waited past `lanes.lane-warn` (2 s), a lane with waiting
  changes and no live owner, source commands not confirmed past `lanes.command-timeout` (30 s);
- each connector that declares `writes:`: its commands, their problems, and the **Switch on** / **Switch off** for its writes;
- the **open overlays**: every correction with the source's value beside it. An administrator may withdraw one; the views and the other
  nodes are told at once.

`drishti.py edits status` prints the same as JSON.

### 28.4 Writing to a source: `writes:`

A connector says **how** to change a record in its system; the Sutra's form (or a blotter's `edit:`) says **what** may change; the lane
owner says **when** and in what order. The JDBC and Kafka plugins write (Kafka's `kafka:` commands: [Live results and blotters, writing back to the record's own source](USER_GUIDE.md#writing-back-to-the-records-own-source)); the file, Delta and Iceberg plugins never do (a lake is not written per change), and
any other plugin is refused with *plugin … cannot write*.

A command needs a **concurrency guard** (it changes the row only if it is still the version the person saw) and an **idempotency clause**
(it recognises its own change id, so a resend is applied at most once):

```yaml
# config/connectors/trade-murex.yaml (excerpt)
writes:
  amend-notional:
    kind: trade
    params: [leg]
    jdbc:
      sql: >
        UPDATE MX_TRADE_LEG SET NOTIONAL = :value, VERSION = VERSION + 1, DRISHTI_CHANGE_ID = :changeId
         WHERE TRADE_ID = :entity AND LEG_NO = :leg AND VERSION = :basedOn
      expect-rows: 1
      already-applied: SELECT 1 FROM MX_TRADE_LEG WHERE TRADE_ID = :entity AND LEG_NO = :leg AND DRISHTI_CHANGE_ID = :changeId
    confirm: reply           # reply | next-load (ack-topic is read as a reply until a messaging plugin writes)
    timeout: 30s
```

| Key | Means |
|---|---|
| `kind` | the records the command changes |
| `params` | extra parameters the form supplies (read from the row being edited) |
| `jdbc.sql` | the statement, with bound parameters |
| `jdbc.expect-rows` | the guard: any other row count means the record moved, and the change is *conflicted* |
| `jdbc.already-applied` | the idempotency clause: a query that finds the change id already written |
| `confirm` | when the change counts as applied at the source: the statement's `reply`, or the `next-load` showing the value |
| `timeout` | after this, an unconfirmed command is listed on Admin → Edits |

Drishti supplies `:entity`, `:kind`, `:businessDate`, `:value`, `:from`, `:basedOn`, `:changeId`, `:by`, `:reason`, `:at` and `:field`,
plus the names in `params:`. Parameters are always bound, never pasted into the SQL. A command without its guard or idempotency clause, or
naming a parameter that is neither, is refused when the connector loads: Admin → Connectors and Admin → Edits say why, and the connector
stays read-only for it. A new record uses a `create:` command of the same connector, with the guard *the key does not exist yet*. A change
of several fields sends one command per field, each with its own change id (`<id>#1`, `#2`).

Commands go out through an **outbox**: the change is recorded as pending in the same transaction that applies it, then sent in order, one
at a time per record, so a slow source never holds up the lane's other changes. After a failover the new owner resends what was not
confirmed with the **same change id**, so the source applies it at most once. A source that cannot guard or de-duplicate (no version, no
change-id column) is edited through corrections only.

Once the file declares `writes:`, an administrator switches the connector's writes on in Admin → Edits (audited). The connector file's
keys are in [DATA_GUIDE.md section 9](DATA_GUIDE.md#writes-saving-to-the-source) and the JDBC plugin's in
[its README](../../plugins/drishti-plugin-jdbc/README.md#writes-commands-that-change-a-record).

### 28.5 Audit and retention

Every step is in the audit log:

| Event | When |
|---|---|
| `edit-submitted`, `edit-withdrawn` | a change is submitted or withdrawn |
| `edit-approved`, `edit-rejected`, `edit-self-approval-refused` | an approver decides; someone tries to approve their own |
| `edit-applied`, `edit-conflicted`, `edit-refused` | the lane owner applies or refuses it |
| `edit-confirmed`, `edit-refused-at-source` | the source answers a command |
| `form-draft-closed` | a draft is closed with its change id |
| `connector-writes-on`, `connector-writes-off` | an administrator switches a connector's writes |
| `edit-overlay-withdrawn` | an administrator withdraws a correction |
| `edit-log-purged` | finished entries past retention are deleted |

Values of fields hidden from the submitter are written as the mask. Each edit-log row carries a SHA-256 digest over its place and body,
so a changed row is evident. The log is kept `retention.keep` (7 years); finished entries older are deleted. A record's history is
`drishti.py change history <kind> <id>` (or `GET /api/v1/entities/{kind}/{id}/history`).

### 28.6 When something is wrong

| You see | Why | Do |
|---|---|---|
| `403 DRS-9301` | editing is off | `edit.enabled: true`, restart |
| `403 DRS-9302 … no Sutra's form with save: names it` | the field is not editable | a Sutra author adds `save:` to the form ([BUILD_GUIDE.md](BUILD_GUIDE.md)) |
| `403 DRS-9302 … does not hold the edit power` | the person's roles | Admin → Roles: *Submit edits* |
| `403 DRS-9302 … you submitted this change` | four eyes | another approver decides |
| `503 DRS-9304` busy | the lane is deeper than `lanes.max-lane-depth` | try again; the draft is kept |
| `503 DRS-9306 Edits are paused …` | the edit primary is down, or the cluster has no shared edit log | start the primary; for failover use PostgreSQL |
| a change stays *queued* | no node owns its lane (Admin → Edits: a lane with waiting changes and no live owner) | check the server log for `edit lanes:`; a node takes the lane when its lease runs out |
| *unconfirmed* commands | the source did not confirm within `lanes.command-timeout` | check the source; the outbox keeps resending with the same change id |
| *Conflicted: the record moved* | the record changed since you opened it | open it again and edit from what is there now ([4.5](USER_GUIDE.md#when-the-record-moved-conflicted)) |
| no **Submit…** on a form | the form has no `save:`, editing is off, or you lack the edit power | the three switches of [28.1.1](#2811-off-at-three-levels) |

### 28.7 Settings and further reading

- Every setting (`config/edit/edit.yaml`, `lanes.yaml`, `policy.yaml`, `retention.yaml`, and `drishti.approvals`):
  [CONFIGURATION.md](../admin/CONFIGURATION.md#write-back-configedit).
- Designing a form that submits (`form:`, `save:`, cells, the drawer, rules and hints, the designer, `sutra lint`):
  [BUILD_GUIDE.md](BUILD_GUIDE.md); every key is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#forms-form-on-a-panel).
- Recomputing on a change: a computed connector with a `write-back` trigger ([tutorial 19, Part C](../tutorials/25-computed-panels.md)).
- The endpoints: [API_REFERENCE.md](API_REFERENCE.md). The commands: [CLI_REFERENCE.md](CLI_REFERENCE.md).
- How it is built (lanes, fencing, the outbox, the applied event, the one queue): [ARCHITECTURE.md, section 31](../architecture/ARCHITECTURE.md).
- The worked example: [tutorial 11](../tutorials/11-correct-and-approve.md).

## 29. The Admin pages at a glance

Admins see the **Admin** menu in the top bar. The admin pages also share a row of tabs, so you can move
between them without the menu.

| Page | Address | What you do there |
|---|---|---|
| Users | `/admin/users` | Create users; set roles and the packs each user may use; enable, disable, reset passwords |
| Roles | `/admin/roles` | Define roles: the kinds each opens, and its powers |
| Packs | `/admin/packs` | Switch a domain pack off or on for everyone; deploy an archive; see which connectors a pack reads through (**Data source**, read only) |
| Artifacts | `/admin/artifacts` | Everything installed in one list (packs, connector files, shipped BI files): see what depends on each, **disable** it, **uninstall** it to an archive and **restore** it, install a BI file ([OPERATIONS_GUIDE.md section 18](OPERATIONS_GUIDE.md#10-admin--artifacts-install-and-uninstall-safely)) |
| Connectors | `/admin/connectors` | Where data comes from: one connector per YAML file in `config/connectors/`; create, edit, test, switch off and see which packs use each ([DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors)) |
| Tokens | `/admin/tokens` | Every personal API token (never its secret): its owner, its scopes (*read only* or what it may write), when it was last used (in the console's time zone, named), and revoking any of them ([API_REFERENCE.md (clients)](INTEGRATION_GUIDE.md#33-clients-scripts-notebooks-and-excel)) |
| Access | `/admin/access` | Who read which entity, filtered by user, action, kind, id and dates ([ADMIN_GUIDE.md](ADMIN_GUIDE.md)) |
| AI | `/admin/ai` | The AI layer: off by default; providers, models, MCP servers and a test call ([ADMIN_GUIDE.md › 27.1 Model providers](#271-model-providers)) |
| Data loads | `/admin/loads` | What your ETL announced, what is due and what is late ([DATA_GUIDE.md](DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)); its **Inbox** tab (`/admin/loads/inbox`, while the data inbox runs) is the inbox's status board: files waiting, the run loading table by table, the last cycles, failures with **Retry**, and **Load now** ([DATA_LAKE_GUIDE.md section 3.1](DATA_LAKE_GUIDE.md#31-the-data-inbox)) |
| Collaboration | `/admin/collab` | Moderation of shares and threads, legal holds, export, the mail outbox and chat bridges ([ARCHITECTURE.md §31](../architecture/ARCHITECTURE.md#31-collaboration-write-back-and-approvals)) |
| Embedding | `/admin/embedding` | Host applications that show Drishti views in their own pages ([INTEGRATION_GUIDE.md](../architecture/ARCHITECTURE.md#30-the-client-renderer-and-drishti-elements)) |
| BI | `/admin/bi` | Powers per role and who creates workspaces (needs `bi-admin`; [README.md](bi/README.md)) |
| Ad-hoc scopes | `/admin/scopes` | Every person's saved VaR baskets (owner, name, scope, shared with, dates, size), filtered by owner; **Delete** any of them with an optional reason, audited and told to the owner ([OPERATIONS_GUIDE.md section 9b-2](OPERATIONS_GUIDE.md#126-deleting-peoples-var-baskets)) |
| Audit log | `/admin/audit` | Sign-ins, failures, lockouts, user, role and pack changes, Sutra proposals and approvals, cache purges |
| Health | `/admin/health` | Every connector and pack: up or down, reads, timings. Refreshes every 5 seconds |
| Caches | `/admin/caches` | Every cache with where it lives and its size against its limit; **Purge** one, **Purge a date**, or **Purge all**, on this server or the whole cluster (safe at any time) |
| Capacity | `/admin/capacity` | Today against the plan: heap, RAM, disk and cores each with a state and a headroom bar, **what breaks first** in words with the fix, this server beside the plan, growth ×1 to ×3; **Edit the plan** for the single-node planner (inputs, every result with its Why?, the settings and the JVM command line with Copy); **Download the working (.xlsx)**, a live formula workbook ([OPERATIONS_GUIDE.md 12.4](OPERATIONS_GUIDE.md#146-admin--capacity-today-against-the-plan)) |

### 29.1 Admin → Artifacts: install and uninstall safely

*Admin → Artifacts* lists every pack, connector file and shipped BI file, with its status, origin, version and problems, and a filter (name, kind, status,
origin; **/** focuses it). *Uninstall…* (here, and on the Packs, Connectors and BI pages) first asks what depends on the artifact. With nothing depending on it,
it **moves** the folder or file to `data/uninstalled/<time>-<kind>-<name>/`; with dependants it is refused and **Disable instead** (keep it, switch it off) is the
default; taking the dependants along needs the name typed. The **Uninstalled** tab restores an archive (refused if the name exists again, unless you replace it) or
deletes it for good (name typed); archives older than 30 days are removed. *Install* uploads a BI file with a dry run first. Every action is audited, and no API
token can do any of it. Full guide: [OPERATIONS_GUIDE.md section 18](OPERATIONS_GUIDE.md#10-admin--artifacts-install-and-uninstall-safely).

### 29.2 Admin → Roles: what a role may do

A role says which **kinds** of entity its holders may open, and which **powers** they have. The table lists
every role with its kinds (or *every kind*), its powers, how many users hold it, and who changed it last.

| Power | Lets its holders |
|---|---|
| raw JSON | see every field: nothing is masked for them, in the raw JSON (`F9`, **JSON**) or anywhere else ([Masked fields](USER_GUIDE.md#masked-fields)) |
| author Sutras | save and propose Sutras from the workbench (designing and previewing there is open to everyone) |
| approve Sutras | approve proposed Sutras in Reviews |
| administer | use every admin page |
| Calc | use Calc (`Alt+C`): Python in the browser on what the role opens ([section 9](bi/CALC.md#9-roles-who-may-use-calc)) |
| BI (`bi`, `bi-author`, `bi-steward`, `bi-admin`) | four powers for Rūpaka, granted on roles ([REPORTS.md](bi/REPORTS.md#3-who-may-do-what-the-four-powers), [ADMIN_GUIDE.md](#34-bi-powers-rupaka)); the Roles form above lists only the first six powers |
| layouts | use [layout mode](USER_GUIDE.md#layout-mode-arrange-a-view-your-way) (`Alt+L`) and keep personal layouts. On for every role unless unticked; the built-in `viewer` role does not have it |

There is no power for [Pivot](USER_GUIDE.md#pivot-slice-a-table-your-way): anyone who may open a kind may arrange and save its pivots, as they
may sort a table; promoting one to a Sutra needs **author Sutras** (and goes through review).

Roles marked **built-in** come from the server's configuration and from packs (`viewer`, `author`,
`approver`, `admin`, and pack roles such as `trader`, `credit-risk` or `economist`). They are shown read-only.

Worked example: a role for credit analysts.

1. Open *Admin → Roles* and click **New role**.
2. **Name** `credit-analyst` (lower case, digits and hyphens); **Description** `Reads counterparties and
   credit exposure`.
3. Under *Add every kind of a pack*, click **Counterparty credit risk**: its kinds (`netting-set`,
   [`credit-limit`](../contracts/packs/counterparty-risk.md#example-counterparty-risk-credit-limit) ([contract](../contracts/packs/counterparty-risk.md#lim-credit-limit-credit-limit)), `cva`, …) fill the *Kinds it opens* box, one per line. Add `counterparty`
   on a line of its own.
4. Leave every power unticked, and click **Save**. The role appears in the table with `0` users.
5. Open *Admin → Users*, edit a user, tick `credit-analyst` and save. At that user's next click, netting sets
   open for them; a trade does not, and links to trades show disabled with the reason.

Changes apply at the holder's next request and are written to the audit log. **Delete** is greyed while
anyone holds the role (*Held by 1 user(s): take it away from them first*); the server answers `DRS-6009`
if asked anyway. Roles only matter when sign-in is on. More in [ADMIN_GUIDE.md](ADMIN_GUIDE.md).

### 29.3 Admin → Packs: switching a pack off for everyone

*Admin → Packs* lists every pack in the server's packs folder, one row each:

| Column | Example |
|---|---|
| Pack | *Trading* `trading 1.0.0` |
| What it brings | its description |
| Builds on | `core, market-data`, and under it *needed by market-risk, counterparty-risk, …* |
| Kinds | `1 (TRD)`: the number of kinds and the first four mnemonics |
| Connectors | `trade-endur, trading-stream` |
| Status | **on**, **off**, or **not loaded**; under it, the problem if the pack's files could not be used (the last good version keeps running) |
| Changed | when and by whom it was last switched |

A **Sample** badge beside the title marks a demonstration pack. **Load** and **Unload** take effect at once, with no restart; a pack folder you add,
edit or delete on the server is picked up the same way (see [BUILD_GUIDE.md](BUILD_GUIDE.md#296-live-reload-the-packs-folder-is-watched)). Above the table, the
*Sample packs* section says who sees samples and offers **Hide samples from business users**: only authors and administrators then see sample
packs, everyone else has them filtered out as if switched off. *Hide samples from everyone* unloads them; *Show samples to everyone* brings them back.
The choice is saved, applied at once and recorded in the audit log (`pack-samples`).

Switching a pack **off** takes effect for every user at their next click: its kinds cannot be opened, its
mnemonics and suggestions disappear, and it leaves every pack menu. Switching it **on** brings it all back.
The choice is kept in the database, so it survives restarts, and each switch is in the audit log
(`pack-disabled`, `pack-enabled`).

Two safeguards keep packs consistent:

- **A pack another switched-on pack builds on cannot be switched off.** Its **Switch off** button is greyed
  with the reason (*Needed by market-risk, counterparty-risk: switch those off first*).
- **Switching a pack on switches on what it builds on.** If you switched off `counterparty-risk`, then
  `trading`, switching `counterparty-risk` on again switches `trading` (and anything it needs) on as well.

Worked example: take operational risk away for everyone.

1. Open *Admin → Packs*. The *News and markets* row says **on**; nothing builds on it.
2. Click **Switch off**. The status turns to **off**, and *Changed* shows the time and your name.
3. Type `TOPIC` in the command line: no suggestions come. In the box menu, the pack is gone.
4. Open *Admin → Audit log*: the newest line is `pack-disabled news-and-markets` by you.
5. Click **Switch on** to bring it back.

**Not loaded** packs are folders on disk that the server did not load at start-up. Press **Load**: the server
first checks the pack together with the loaded ones (a clash, such as the retired example pack `finance` beside `trading`, both defining `trade`, is refused
with the reason and nothing changes), then records it and puts it to use at once: no restart, nobody is signed out, and
open views carry on. The page confirms and refreshes. A pack loaded this way shows **Unload**, which takes it back the
same way (not while another loaded pack builds on it). See
[BUILD_GUIDE.md](BUILD_GUIDE.md#292-loading-a-pack-while-the-server-runs).

#### 29.3.1 One pack: its own page

In the *Pack* column every title is a link (Tab to it, Enter opens it) to `/admin/packs/<name>`; *Builds on*, *needed by* and *Connectors* link to the
packs and connectors they name. The page gathers what the list can only hint at:

![Admin → Packs → one pack: status, kinds with their mnemonics, Sutras, connectors, loads and history](img/connector_files/12-pack-detail.jpg)

- **Summary:** title, version, code, description, the **Sample** badge, who changed it and when, and the status: **on**, **off**, **not loaded**, or **refused**
  (a pack whose files could not be used) with the reason and, when the server is trying again, *failed — next retry in Ns (attempt k)* and **Retry now**.
  **Builds on** and **Needed by** are links to the other packs' pages. **Switch on/off** and **Load/Unload** are the list's buttons, with the same safeguards.
- **Kinds and mnemonics:** each kind with its mnemonic, a link that **opens a record of the kind** (where the pack has one), a link to `FORM <pack>`
  for that kind, and its form fields (declared in the pack's `form:` section, or *inferred*).
- **Sutras:** the pack's Sutras with their versions, each with **Open in Build** (the Build workbench).
- **Connectors it names:** state and kinds for each, a link to its page, and, for a name nothing defines, **missing** with a **Create** button that opens the editor
  pre-filled from the pack's suggestion.
- **Query by form and About:** how many kinds declare a form, how many carry About text and how many glossary terms.
- **Data loads:** today's expectations and their state, the last load, and a link to the full [data loads page](DATA_GUIDE.md#1-telling-drishti-new-data-has-landed).
- **Deploy history:** the pack's deployments and rollbacks with **Roll back to this** for each kept version.

#### 29.3.2 One connector: its own page

In *Admin → Connectors* the connector name is a link to `/admin/connectors/<name>` (the *Used by* column links to the packs):

![Admin → Connectors → one connector: summary, TLS certificates and their expiry, packs, settings, the file and its history](img/connector_files/11-connector-detail.jpg)

It shows the plugin, where the connector is defined (file, pack or the deprecated `application.yaml`), whether it is on, its **status** and the plugin's own health,
the time of its last update, and, when it failed, *next retry in Ns (attempt k)* with **Retry now**. **TLS** says whether it is on, which protocols, whether the
host name is checked and, for every certificate the settings name, **when it expires** (a warning under 30 days, *expired* in red). Below: the kinds it serves, the
packs and kinds that use it (links), the **settings in effect** with credentials masked as `***`, the file as read-only text and the kept earlier versions.
**Edit** opens the same editor as the list, **Test connection** opens it on the Test tab and runs the test, **Compare or restore** opens the History tab,
**Disable/Enable** and **Reset to pack default** (or **Delete**, for a file no pack suggests) work as on the list, with the same questions when packs use the connector.
Both pages are for administrators, work in both themes and on a phone, where every link and button is at least 44 px:

![The connector page on a phone](img/connector_files/13-connector-detail-phone.jpg) ![The pack page on a phone](img/connector_files/14-pack-detail-phone.jpg)

### 29.4 Admin → Connectors → Index: reload type-ahead for a past day

Worked example: a trade was corrected in last Friday's data and type-ahead for that date still misses it.

1. Open *Admin → Connectors* and click the connector (for example `trading-lake`). Under **Index** every kind is listed by
   business day with where it is held (memory or disk), its ids, size and when it was built, and the line above says when the
   automatic check last ran, runs next and last found a change.
2. Click **Reload** on Friday's row (or **Reload kind** for the kind, or **Reload all**). The row changes to
   *rebuilding* with a progress message, then to *fresh* with a new build time. Type-ahead for that date keeps answering from
   the old day until the new one is ready.
3. If a reload of the connector is already running, the message says so; wait for it. If a day cannot be read, its row
   shows *failed* and the reason, and the old day stays in use.

*Admin → Health* has **Reload index** on each connector that keeps several days. Reloads are for administrators only and are
written to the audit log. See [OPERATIONS_GUIDE.md section 12.2](OPERATIONS_GUIDE.md#144-the-multi-day-type-ahead-index).

### 29.5 Admin → Health: is everything up?

Worked example: check that every data source is up.

1. Open *Admin → Health* (`/admin/health`). At the top is the overall state, **OK**, **DEGRADED** or
   **DOWN**, and a summary. Below it, each connector (for example `trade-endur`, `market-store`, `demo`)
   shows its state, reads, errors and p50/p99 read times. A Delta Lake connector whose folder is missing
   shows `DOWN: no Delta tables under …`; one with a table or date it cannot read shows **DEGRADED** and names it
   (`DEGRADED: cannot read trade 2026-09-30: …`).
2. The page refreshes itself every 5 seconds. The same figures are at `GET /api/v1/admin/health` for
   external monitoring:

   ```bash
   curl -s localhost:18480/api/v1/admin/health | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], d["summary"])'
   ```

   You should see:

   ```text
   OK {'packs': 12, 'sources': 18, 'sourcesDown': 0, 'failedToStart': 0, 'packsWithProblems': 0}
   ```

3. A shorter list of sources, with their kinds and health, needs no admin rights:

   ```bash
   curl -s localhost:18480/api/v1/sources
   ```

A connector whose plugin is installed but has no settings (a Kafka connector without `topics`, a feed without
`feed`) stays **idle**: it is not started and is not counted as failed. Anything else that is not **UP** is
explained in [runbooks/source-down.md](OPERATIONS_GUIDE.md#201-source-down-or-slow). A connector a pack names but that has no file shows as *not configured* (its kinds answer `DRS-1011`);
an administrator creates it in *Admin → Connectors*, and a connector file with a mistake is listed on Health as a bad file while the last good configuration keeps running.

Managing users and roles is explained in [ADMIN_GUIDE.md](ADMIN_GUIDE.md).
