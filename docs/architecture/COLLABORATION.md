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
# Collaboration: share with a note, and comment threads anchored to the data

Status: proposed architecture (both features agreed with the product owner; build *share with a note* first, then
*comment threads*; the decisions marked **Decision** are open, each with a recommendation). Owner: the identity module
(stores), the server's API and security layer, the console's view page, top bar and admin pages.

## Contents

1. [Why](#why)
2. [Goals and non-goals](#goals-and-non-goals)
3. [What exists today](#what-exists-today)
4. [User experience](#user-experience)
5. [The pin: which data a share or comment is about](#the-pin-which-data-a-share-or-comment-is-about)
6. [Data model](#data-model)
7. [API](#api)
8. [Security and privacy](#security-and-privacy)
9. [Notifications pipeline](#notifications-pipeline)
10. [Retention, legal hold and export](#retention-legal-hold-and-export)
11. [Configuration](#configuration)
12. [Console design](#console-design)
13. [Performance budget](#performance-budget)
14. [Phase 2: bridges and watermarked snapshots](#phase-2-bridges-and-watermarked-snapshots)
15. [Documentation changes](#documentation-changes)
16. [Build plan](#build-plan)
17. [Decisions for the product owner](#decisions-for-the-product-owner)

## Why

People look at a trade, a VaR result or a gene variant in Drishti and then talk about it somewhere else: a chat
message with a pasted screenshot, an email with a figure copied out of the strip. Each copy leaves Drishti's
controls behind. The screenshot shows what the *sender* may see (unmasked trader names, a counterparty a recipient
has no right to), it records nothing about which business date and version of the data it showed, and nobody can
later say who sent what to whom. In a regulated firm that is three problems at once: a leak, a lost audit trail, and
an argument about which numbers were meant.

Drishti already has the pieces to do this properly: every read is built for one caller with their masks
(`Entitlements.redactor`), every page knows its business date, "known at" instant and generation
(`ViewModel.Provenance`), the access log records reads (`AccessRecorder`), the bell receives live events
(`alerts.js` on the browser's one channel), and notes already hang off entities (`NoteStore`). This design joins them
so that the conversation happens **inside** the controls: what travels is a link and the people's own words, never
the data; the recipient sees the data with their own rights; and every message is pinned to the data it was about.

## Goals and non-goals

**Goals**

1. **Share with a note.** From any view, or one panel of it, a user writes a short note, picks people and roles from
   the directory, and Drishti notifies them in the app (always) and by email (when configured). The link opens the
   same view, pinned to the same business date, "known at" instant and generation, with the **recipient's** rights.
2. **Comment threads anchored to the data.** Threads on an entity, a panel or a field, visible to whoever may see
   that anchor, with `@mentions` of users and roles, each comment pinned to the data it was written against and an
   *Open as it was* link.
3. **Never weaker than the view.** Nothing a person may not see on the page reaches them through a share, a comment,
   a notification, an email or an export they are not entitled to.
4. **A record a compliance officer accepts.** Every share and every comment change is kept, immutable and
   tamper-evident, under configurable retention and legal hold, exportable for eDiscovery; every share is in the
   access log.
5. **Works with nothing configured.** In-app notification needs no mail server; email is an opt-in channel.
6. **Off the view's hot path.** A view costs the same as today; counts and threads are asked for after it paints.

**Non-goals**

- **No general chat.** No channels, rooms, direct messages without a data anchor, presence, typing indicators or
  read receipts between people. Every message hangs off a share or an anchor in the data.
- **No buddy lists or social graph.** Recipients come from the directory Drishti already has (users, and roles as
  groups); nobody adds contacts, follows people or builds a network.
- **No data in transit by default.** No figure, table or chart is placed in an email or a notification body. The
  phase 2 snapshot is an explicit administrator choice, off by default.
- **Not a records-archive product.** Drishti keeps an immutable, tamper-evident record and exports it; the firm's
  archive (WORM storage under SEC 17a-4, MiFID II record keeping) is where it is *retained* long-term. Drishti does
  not claim WORM compliance itself.
- **No rich text.** Comments are plain text with mentions and value quotes; no markdown, HTML, attachments or
  images in v1 (as with *About this page* text, [CONTEXT_HELP.md](CONTEXT_HELP.md#masking-and-security)).
- **No external identities.** Only Drishti users receive shares; there is no "share with an email address".

## What exists today

The design reuses these; each claim is grounded in the code named.

| Piece | Where | What it gives us | What it lacks for this feature |
|---|---|---|---|
| Share button | `console/web/templates/terminal/view.html` (`data-share`), `view.js` (copy), `share_url()` in `console/routes/terminal_routes.py` | A link that reopens the view live, or through `/asof?d=…&ki=…&next=…` on a picked date and "known at" instant | No note, no recipients, no record; **the link loses its date when the recipient is signed out**: `AuthGate` (`console/core/app.py`) redirects to `/login?next=` with `request.url.path` only, so `/asof`'s query (and `srv=`) is dropped. Opening it also changes the recipient's sticky as-of cookie (12 h) for every later page |
| Notes | `NoteStore`, `NoteEntity` (`drishti_note`), `NoteController` (`/api/v1/notes`), `notes.js`, the Notes drawer in `view.html` | Text on an entity or a field `path`, readable by everyone who may open the kind, author edits, author or admin deletes, every change in the audit log | Not masked (a note may quote a masked value, and `notes.js` writes the note into `data-note` on a masked field's element); no pin to data; hard delete (nothing kept for compliance); no replies, mentions or notifications; JPA only; 200 per entity |
| Bell and alerts | `AlertEngine`, `AlertHistory` (`drishti_alert`, newest `keep` per user), `AlertController` (`/me/alerts`, SSE `/me/alerts/stream`), `alerts.js`, `channel` subscription `alerts` in `console/routes/api_routes.py` | A per-user live event stream on the browser's single channel, a counter badge, toasts, system notifications; per-user masks in `JsonConfiguration.alertEngine` (`Caffeine` cache of `entitlements.redactor(new Principal(user, roles))`) | Only alerts; listeners are in-process (a second server never hears them); no read state |
| Access log | `AccessLog` (`drishti_access`, queued, batched, *drops when full*), `AccessRecorder` (route → action map) | Who read what, when, for which business date | No `share` action; a queued, droppable path is not enough for a record of sending |
| Audit log | `AuditLog`, `JpaAuditLog` (`drishti_audit`) | Who changed what (`note.add`, `note.edit`, `note.delete`, …) | Not tamper-evident; free-text detail |
| Rights | `Entitlements.mayOpen` (role kinds **and** `PackAccess.kindAllowed`: the kind's pack active for the user), `redactor`, `restrict`, `PanelView.denied` | The one place masks and kind rights are decided, and copies of masked values scrubbed (`mask-copies`) | Works on a `Principal` of the caller; deciding for a *recipient* is done ad hoc today (`JsonConfiguration`) |
| Directory | `UserService`, `User` (`username`, `displayName`, `email`, `desk`, `roles`, `packs`), roles (`RoleStore`); single sign-on maps provider groups to roles (`drishti.security.oidc.role-map`) | Users with an optional email, and roles as the groups the directory already feeds | No lookup for non-administrators (`/admin/users` needs `admin`); **no group concept apart from roles** |
| Shared workspaces | `SharedWorkspaceController`, `PUT /me/workspaces/{name}/share` (`{everyone}` or `{roles, users}`) | The precedent for addressing people: users and roles | No notification |
| Business date | `AsOf(businessDate, knownAt, live)`, `AsOfResolver` (`X-Drishti-As-Of`, `X-Drishti-Known-At`, or `asOf`/`knownAt` query), the console sends them on every call (`asof.headers()` in `core/backend.py`) | The server always knows which day a request is about | Generation is evidence only: no store reads "generation N"; versioned stores (Delta Lake, Iceberg) answer `knownAt`, others answer `DRS-1007` |
| Explain | `ExplainService`, `ViewPipeline.built(...)`, `GET /views/{k}/{id}/explain` | Rebuilds a view for a caller, with the layout (panel ids, `denied`) | — |
| Webhooks | `ReportService`, `ReportProperties.webhookAllowed` (`drishti.reports.webhooks` prefixes), JDK `HttpClient` with redirects off | An allow-list pattern for posting out | — |
| Identity database | `IdentityDatabase` applies `db/schema-sqlite.sql` or `schema-postgres.sql`, idempotent `CREATE … IF NOT EXISTS`, **no migrations**; file or JPA stores chosen by config (`DesignStore`: `FileDesignStore`, `JpaDesignStore`) | One place for durable per-user state, SQLite or PostgreSQL | New columns on existing tables need a migration mechanism that does not exist: new tables only |

## User experience

### Share with a note

`Share` in the view header (today a copy button) opens a dialog; `Alt+S` opens it from the keyboard. *Copy link* stays
inside the dialog as the first action, so the old one-click use is one click plus `Enter`.

```
┌ Share TRD MX-20000001 ───────────────────────────────────────────────────── ✕ ┐
│ What      (•) the whole view   ( ) panel: Cashflows                            │
│ As of     30 Sep 2026 · known at 18:00 London · gen 1674        [ Live ▾ ]     │
│ To        [ravi ×] [risk (role, 7 people) ×]  [ type a name or role…    ]      │
│            ┌──────────────────────────────────────────┐                        │
│            │ Ravi Kumar · ravi · Rates desk           │                        │
│            │ Rachel Ng  · rng  · Credit desk          │                        │
│            │ risk       · role · 7 people             │                        │
│            └──────────────────────────────────────────┘                        │
│ Note      ┌──────────────────────────────────────────────────────────────┐     │
│           │ MTM moved after the fixing correction — can you confirm      │     │
│           │ the curve? {MTM (USD)}                                       │     │
│           └───────────────────────────────────────────────────── 92/2000 ┘     │
│           ! "J. Smith" is the value of a field hidden from some readers;       │
│             people without full access will see •••                            │
│ Send by   [x] in Drishti   [x] email (2 of 8 have no address: in Drishti only) │
│           [ ] also post to the discussion on MX-20000001                       │
│                                                                                │
│ [Copy link]                                         [Cancel]  [Send  Ctrl+↵]   │
└────────────────────────────────────────────────────────────────────────────────┘
```

- **What.** The whole view, or one panel (the panel's own menu has *Share this panel*, which preselects it).
- **As of** is the page's pin ([below](#the-pin-which-data-a-share-or-comment-is-about)), shown so the sender knows what
  the recipient will open; *Live* lets the sender send a live link instead.
- **To.** Type-ahead over the directory (users and mentionable roles, scoped by [Decision 3](#decisions-for-the-product-owner));
  a role shows its size. After *Send*, the dialog says what happened per name, e.g. "Sent to 7. Not sent to 1: dkim
  may not open trade views." (the policy in [Decision 6](#decisions-for-the-product-owner)).
- **Note.** Plain text, up to `max-text` characters. `{` opens a picker of the labelled values on the page
  (`data-path` elements, as the Notes drawer collects them today) and inserts a **value quote**: a token that each
  reader sees rendered from their *own* view (`•••` where masked), never the sender's text.
- **Masked-copy warning.** Typing a value of a masked field (detected on the server, with the same rules as
  `mask-copies`) shows the warning above; the policy decides warn, reject or allow
  ([Decision 7](#decisions-for-the-product-owner)).

The **recipient** gets a bell notice at once (and an email if on):

```
 ┌ Bell (3) ──────────────────────────────────────────────────── Mark all read ┐
 │ [All] [Mentions] [Shares] [Alerts]                                          │
 │ ● Ashutosh shared TRD MX-20000001 · 30 Sep, known 18:00          2 min      │
 │   "MTM moved after the fixing correction — can you confirm the curve? …"    │
 │ ● Rachel mentioned you on VAR VAR-COMM · panel VaR contribution  1 h        │
 │   "@ravi is the commodities limit still 18.94M?"                            │
 │   warn  MX-20000003  MTM below −450k                              3 h       │
 │                                                     All notifications →     │
 └─────────────────────────────────────────────────────────────────────────────┘
```

Opening it goes to `/share/sh_01J…`, which the console resolves into the view **as shared**:

```
┌ Shared by Ashutosh Sinha · 30 Sep 2026 14:02 ────────────────────────────────────────────────────────┐
│ "MTM moved after the fixing correction — can you confirm the curve? MTM (USD) +1,595,251"            │
│ You are seeing 30 Sep 2026 as known at 18:00 London (gen 1674), as it was shared. [Go live] [Reply]  │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
 [Rates · IRS]  MX-20000001  with •••                Alert Pin Share JSON Print About Discussion (2)
 MTM (USD) +1,595,251   Notional 50,000,000   …
```

- The banner says how the pin was honoured: "as it was" (versioned store), "the latest for 30 Sep; this source keeps
  no earlier versions" (`DRS-1007` fallback), or "live; the data has changed since it was shared (gen 1674 → 1690) ·
  [What changed]" (a link to `/compare/{kind}/{id}?fromKnownAt=…`).
- A shared panel is scrolled to and outlined (`#p-<panel id>`, the id `_macros/panels.html` already writes).
- *Reply* answers the sender (a share reply, in the same share record), or, if the share was also posted to the
  discussion, opens the thread.
- **No access**: a clean page, no title, no figures, no hint of what the entity holds:

```
┌ A shared view you cannot open ──────────────────────────────────────────────┐
│ Ashutosh Sinha shared a trade view with you on 30 Sep 2026.                 │
│ Your roles do not open trade views.                                         │
│ Ask your administrator for access; this notice stays in your inbox.         │
│ [Back to the terminal]                                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

When the kind's pack is assigned but switched off for the recipient, the page says so and offers *Switch on
<pack>* (the existing `/me/packs` choice) instead.

### Discussion (comment threads)

`Discussion` in the view header (replacing *Notes*, [Decision 1](#decisions-for-the-product-owner)) or `Alt+N`
opens the side drawer. The drawer host is shared with *About this page*: one right-hand drawer with two tabs, so the
two never fight for the same space ([Decision 9](#decisions-for-the-product-owner)). `?` opens it on *About*,
`Alt+N` on *Discussion*.

```
┌ VAR VAR-COMM · Market risk ────────────────────────────┬ [ About | Discussion (3) ] ─────────────── ✕ ┐
│ VaR 99% 1D 10.96M  ES 97.5% 12.38M  Limit 18.94M       │ Filter: [All ▾] [Open ▾]      [+ New thread] │
│ ┌ Hypothetical P&L ── [2] F2 ? ┐ ┌ VaR contrib [1] ? ┐ │ ▾ VaR contribution (panel) · open            │
│ │  (line chart)                │ │  (bars)           │ │   Rachel Ng · 1 h · gen 1702 · 2 Oct         │
│ └──────────────────────────────┘ └───────────────────┘ │   @ravi is the commodities limit still       │
│                                                        │   {Limit} = 18.94M?         [Open as it was] │
│                                                        │   Ravi Kumar · 40 min · gen 1702             │
│                                                        │   Yes, until the 15 Oct review.   (edited)   │
│                                                        │   [Reply…]  [Resolve]  [Follow ✓]            │
│                                                        │ ▸ Hypothetical P&L (panel) · 2 · resolved    │
│                                                        │ ▸ VAR-COMM (whole view) · 1                  │
│                                                        │ ┌────────────────────────────────────────┐   │
│                                                        │ │ Reply… @ to mention, { to quote        │   │
│                                                        │ └────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┴──────────────────────────────────────────────┘
```

- **Anchors.** A thread is on the whole view, on a panel (the `[n]` comment badge in the panel header, beside `?`), or on a
  labelled field (a dotted marker on the strip or `kv` value, as notes do today with `.has-note`).
- **Each comment shows its pin** ("gen 1702 · 2 Oct") and, when the page shows another business date or generation,
  *Open as it was* (same mechanism as a share link).
- **Mentions.** `@` opens the same directory picker as the share dialog; `@risk` mentions a role.
- **Edit and delete.** The author edits within the edit window (a revision is kept, "(edited)" opens the history);
  after it, the author may only *retract* (the comment shows "Retracted by the author, 2 Oct 14:10"). Administrators
  *hide* with a reason ("Hidden by a moderator: client name"). Nothing is erased before retention allows
  ([Decision 8](#decisions-for-the-product-owner)).
- **Resolve and lock.** Participants resolve and reopen a thread; administrators lock it (no new comments).
- **Follow.** Participants follow a thread automatically; anyone who can see it may follow or mute it.

### Inbox

The bell becomes the inbox (alerts included, [Decision 10](#decisions-for-the-product-owner)); `/inbox` is the full
page, and `Alt+I` opens it.

```
┌ Inbox ───────────────────────────────────────────────────────────────────────────────────┐
│ [All 12] [Unread 3] [Mentions 2] [Shares 4] [Replies 5] [Alerts →/alerts]  [Mark read]   │
│ ● today 14:02  Share     Ashutosh Sinha  TRD MX-20000001          "MTM moved after …"    │
│ ● today 13:05  Mention   Rachel Ng       VAR VAR-COMM · VaR contr "@ravi is the comm…"   │
│   yesterday    Reply     Dana Ito        VAR VAR-COMM             "Agreed, closing."     │
│   29 Sep       Share     (no access)     a trade view             —                      │
│                                                         Older →                          │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

The row text is rendered **when read**, for the reader's current rights: a share or mention on a kind the reader can
no longer open shows "(no access) · a trade view", and a hidden or retracted comment shows as such.

### Administrators and compliance

`Admin → Collaboration` (`/admin/collab`): search shares and threads by user, entity, date; open any thread with
hidden comments visible; hide, unhide, lock; place and release legal holds; start an export; see the outbox
(pending, failed, dead letters, *Retry*) and the email test (*Send a test mail to me*).

## The pin: which data a share or comment is about

A **pin** is recorded with every share and every comment:

| Field | Meaning | Source |
|---|---|---|
| `businessDate` | the date the page showed (`null` for undated sources) | the request's `AsOf` (the console sends `X-Drishti-As-Of` on every call) and the document's `Provenance.businessDate` |
| `live` | the page was live | `AsOf.live` |
| `knownAt` | the instant the page was read *as known at*; for a live or plain-date page, the moment of writing | `AsOf.knownAt`, else the server clock at write |
| `generation` | the generation the page showed | sent by the console from `provenance.generation`; accepted only if ≤ the generation the server holds now (else `422 DRS-7011`) |
| `source` | the source that answered | `Provenance.source` |
| `sutra` | the Sutra name and version that laid the page out | `provenance.layout` (evidence only) |

**Opening a pin** (shares and *Open as it was*): the console asks the view with `asOf=<businessDate>` and
`knownAt=<knownAt>` **as request parameters**, not cookies, so the recipient's own sticky date is untouched
([Decision 4](#decisions-for-the-product-owner)). Then:

1. Versioned store (Delta Lake, Iceberg): the data as it was; the banner says "as it was".
2. `DRS-1007` (dated store without versions): the console asks again without `knownAt`; banner: "the latest for
   <date>; this source keeps no earlier versions".
3. Undated or live source: today's data; if the view's generation differs from the pin's, the banner says so and
   links to *What changed* (`/compare/...?fromKnownAt=<knownAt>`, the History diff that exists today).
4. The view's own rules apply unchanged: a date outside the history window is `DRS-4003`, the page says so.

Generation is **evidence**, never an address: no store can read "generation 1674", and the design does not pretend
otherwise.

## Data model

All tables are new (the identity schema has no migrations: `IdentityDatabase`), in the identity database, added to
both `schema-sqlite.sql` and `schema-postgres.sql`. Each store is an interface with a JPA and a file implementation,
chosen by `drishti.collab.store`, as `DesignStore` is ([Decision 2](#decisions-for-the-product-owner)).

### Ids

Shares, threads and comments use a 26-character time-ordered, random id (Crockford base32 ULID) with a prefix:
`sh_`, `th_`, `cm_`. They are made on the server, sort by time, work without a database sequence (the file store, and
several servers), and are unguessable in URLs (a guess still needs the right to read). Inbox and outbox rows use the
database's identity column (`seq`, monotonic, the SSE event id, as `AlertHistory` does).

### Tables

```
drishti_share                      one share (the record of sending)
  id            VARCHAR(30) PK     sh_…
  sender        VARCHAR(64)        username
  created_at    TIMESTAMP
  kind          VARCHAR(64)        entity kind
  entity_id     VARCHAR(200)
  panel_id      VARCHAR(100) NULL  a shared panel
  gate_kind     VARCHAR(64)  NULL  the kind a panel's source names (PanelView.denied for those who may not open it)
  pin_date      DATE NULL · pin_live BOOLEAN · pin_known_at TIMESTAMP · pin_generation BIGINT · pin_source VARCHAR(100)
  body          VARCHAR(4000)      the note, as written (the record)
  masked_spans  VARCHAR(2000)      JSON [[start,end],…]: copies of masked values in body, shown as ••• to readers without raw
  channels      VARCHAR(40)        in-app,email
  thread_id     VARCHAR(30) NULL   when also posted to the discussion

drishti_share_recipient            who it was addressed to (as typed) and who it reached (as expanded)
  share_id, seq                    PK
  addressed     VARCHAR(80)        "user:ravi" or "role:risk"
  username      VARCHAR(64)        one row per person after expansion
  state         VARCHAR(16)        notified | no-access | no-pack | disabled | over-limit
  opened_at     TIMESTAMP NULL     first open through the link

drishti_thread
  id            VARCHAR(30) PK     th_…
  kind, entity_id                  INDEX (kind, entity_id)
  anchor        VARCHAR(8)         entity | panel | field
  panel_id      VARCHAR(100) NULL · path VARCHAR(200) NULL · gate_kind VARCHAR(64) NULL
  anchor_label  VARCHAR(200)       panel title or field label when written (shown if the anchor disappears)
  state         VARCHAR(10)        open | resolved | locked
  created_by, created_at, last_at, comments INT

drishti_comment                    the current state, for fast reads
  id            VARCHAR(30) PK     cm_…
  thread_id     INDEX (thread_id, created_at)
  author, created_at, edited_at NULL, revision INT
  pin_*                            as on drishti_share
  body          VARCHAR(4000) · masked_spans VARCHAR(2000)
  state         VARCHAR(10)        live | retracted | hidden
  state_reason  VARCHAR(400) NULL

drishti_comment_revision           insert-only: the immutable record (never updated; deleted only by the retention purge)
  comment_id, revision             PK
  at, actor
  action        VARCHAR(12)        created | edited | retracted | hidden | unhidden | moved | purged
  body          VARCHAR(4000) NULL the text after the action (null for actions that do not change it)
  reason        VARCHAR(400) NULL
  prev_hash     CHAR(64) · hash CHAR(64)    SHA-256 chain per thread (see below)

drishti_mention                    INDEX (target, created_at): "mentions of me / of my roles"
  comment_id, target VARCHAR(80)   "user:ravi" | "role:risk"

drishti_follow                     PK (thread_id, username); muted BOOLEAN; since TIMESTAMP

drishti_inbox                      pointers, not content
  seq           BIGINT identity PK
  username      INDEX (username, seq)
  at, type      VARCHAR(12)        share | mention | reply | thread | moderation
  kind, entity_id, panel_id NULL, share_id NULL, thread_id NULL, comment_id NULL, actor
  read_at       TIMESTAMP NULL

drishti_outbox                     deliveries to leave the server (email; phase 2 webhooks)
  seq           BIGINT identity PK
  channel       VARCHAR(12)        email | webhook
  recipient     VARCHAR(200)       username (email resolved at send) or bridge name
  template      VARCHAR(40)        share | mention | reply | test
  ref_id        VARCHAR(30)        sh_… or cm_…
  state         VARCHAR(10)        pending | sending | sent | dead | cancelled
  attempts INT · next_at TIMESTAMP · lease_until TIMESTAMP NULL · leased_by VARCHAR(64) NULL · last_error VARCHAR(400) NULL
  created_at, sent_at NULL

drishti_collab_hold                legal holds
  id BIGINT identity PK · scope VARCHAR(8) (entity | user | thread | all) · kind, entity_id, username, thread_id NULL
  reason VARCHAR(400) · placed_by, placed_at · released_by NULL, released_at NULL
```

**Tamper evidence.** Each revision's `hash` is `SHA-256(prev_hash ‖ canonical JSON of the row without hash)`, chained
per thread (the first revision of a thread chains from the thread id). Edits and deletes never change a revision;
the purge removes whole threads only (never the middle of a chain) and writes a `purged` audit event with the last
hash. `GET /admin/collab/verify?thread=` recomputes the chain. Shares are single rows and carry their own hash.
This is *evidence* of tampering with the database, not prevention: a DBA could rewrite the whole chain; exports to
the firm's archive (which carry the hashes) are what make rewriting detectable.

**File variant.** `FileCollabStore` under `drishti.collab.dir`: one append-only JSON-lines log per entity
(`threads/<kind>/<sha1(entity id)>.jsonl`, every thread, comment and revision event, read and folded into memory per
entity on demand, cached), `shares/<yyyy-MM>/<id>.json`, `inbox/<user>.jsonl` with a read-marker file,
`outbox/{pending,sent,dead}/<seq>.json` moved atomically between folders, `holds.json`. Writes are serialised per
file with a `ReentrantLock` keyed by path (as `AlertHistory` locks per user) and fsync'd. One server only; the server
refuses to start with `store: file` and `drishti.identity.database-url` on PostgreSQL (a shared database means several
servers).

**Notes.** See [Decision 1](#decisions-for-the-product-owner): existing `drishti_note` rows are imported once into
threads (anchor `entity` or `field` with the note's `path`; pin with `businessDate` null, `knownAt` = `created_at`,
generation `0` = unknown) by an idempotent `NoteImport` (like `LegacyImport`: marks itself done in
`drishti_pack_state`-style state, leaves the table in place).

## API

All under `/api/v1`, problem+json as everywhere ([API_GUIDE §errors](../guides/API_GUIDE.md#when-something-goes-wrong-errors)).
Reads take the as-of headers. Personal API tokens read only (as for notes). Every write needs the `collaborate`
power ([Configuration](#configuration)).

### Directory

| Method | Path | Notes |
|---|---|---|
| `GET` | `/directory?q=ra&limit=10&kind=trade` | `[{type:"user", name, displayName, desk, email: bool}, {type:"role", name, size}]`. `q` at least `min-query` characters; only enabled users; scope per Decision 3; with `kind`, each entry gets `reach: true/false` only if the policy is `tell` (Decision 6). Rate limited |

### Shares

| Method | Path | Notes |
|---|---|---|
| `POST` | `/shares` | body `{kind, id, panel?, generation, note, to: {users:[…], roles:[…]}, channels: {inApp: true, email: true}, live: false, postToThread: false}`; as-of from headers. `201 {id, link, pin, delivered: n, skipped: [{name, reason}], warnings: [...]}`. Rights: the sender must open the kind (and the panel's `gate_kind`) |
| `GET` | `/shares/{id}` | for the sender, a recipient or `compliance`: `{id, sender, createdAt, ref, panel, pin, note (rendered for the caller), recipients (sender and compliance only), replies}`; anyone else `404 DRS-7001` (never 403: the share's existence is not revealed). Recording: the first open sets `opened_at` |
| `POST` | `/shares/{id}/replies` | a reply from a recipient or the sender, `{note}` (same rules as the note); notifies the other party |
| `GET` | `/me/shares?box=sent\|received&limit=&before=` | the caller's shares |

### Threads and comments

| Method | Path | Notes |
|---|---|---|
| `GET` | `/threads/{kind}/{id}?anchor=&panel=&path=&state=&limit=&before=` | the threads the caller may see, newest activity first, each with its comments (paged at `page-size`), rendered for the caller |
| `GET` | `/threads/{kind}/{id}/counts` | `{entity: n, panels: {id: n}, fields: {path: n}}` for badges; the cheap call the view page makes after it paints |
| `POST` | `/threads/{kind}/{id}` | new thread with its first comment: `{anchor, panel?, path?, generation, body}`; `201` |
| `POST` | `/threads/{tid}/comments` | `{generation, body}`; `409 DRS-7007` when locked |
| `PATCH` | `/comments/{cid}` | `{body, revision}`: author only, within `edit-window` (`403 DRS-7008`), `revision` must be current (`409 DRS-7009`) |
| `POST` | `/comments/{cid}/retract` | author: body hidden from readers, kept in the record |
| `GET` | `/comments/{cid}/revisions` | the history, rendered for the caller (readers see edits, not hidden text) |
| `POST` | `/threads/{tid}/state` | `{state: open\|resolved}` (participants), `locked` (admin) |
| `PUT` · `DELETE` | `/threads/{tid}/follow` | follow (`{muted}`) or stop |
| `GET` | `/me/mentions?limit=&before=` | comments that mention the caller or one of the caller's roles |

`/notes/...` stays for one release as a thin facade over threads (`GET` lists the entity's single-comment threads in
the old shape; `POST` creates a thread with `anchor: field|entity`), marked deprecated in API_GUIDE.

### Inbox

| Method | Path | Notes |
|---|---|---|
| `GET` | `/me/inbox?type=&unread=&limit=50&before=` | rows rendered for the caller now (title, actor display name, excerpt scrubbed, `access: false` when the caller can no longer open it) |
| `POST` | `/me/inbox/read` | `{seqs: [..]}` or `{upTo: seq}` |
| `GET` | `/me/inbox/count` | `{unread}` for the badge at page load |
| SSE | `/me/alerts/stream` | gains event `notice` (`{seq, type, …}` as one inbox row), beside `alert`; the console's channel already carries it (`s=alerts`), so no new connection |
| `GET` · `PATCH` | `/me/settings` | gains `notify: {email: {share, mention, reply}, inApp: {reply}}` |

### Administration and compliance

| Method | Path | Who |
|---|---|---|
| `GET` | `/admin/collab/shares?user=&kind=&id=&from=&to=` · `/admin/collab/threads?…` | `admin` or `compliance` |
| `POST` | `/admin/collab/comments/{cid}/hide` `{reason}` · `/unhide` | `admin` |
| `GET` · `POST` · `DELETE` | `/admin/collab/holds` · `/holds/{id}` (release) | `compliance` |
| `POST` | `/admin/collab/exports` `{from, to, kind?, id?, user?, includeShares, includeThreads}` → `202 {id}`; `GET /admin/collab/exports/{id}` (status, then a zip) | `compliance` |
| `GET` | `/admin/collab/verify?thread=` | `compliance` |
| `GET` | `/admin/collab/outbox?state=` · `POST /admin/collab/outbox/{seq}/retry` · `POST /admin/collab/mail-test` | `admin` |

### Problem codes

A new first digit: **7, collaboration** (`ErrorCode` documents 1–6 today; 7 is unused anywhere in code or docs).

| Code | HTTP | Title | When |
|---|---|---|---|
| DRS-7001 | 404 | share not found | no share with that id, or the caller is not its sender, a recipient or `compliance` |
| DRS-7002 | 422 | bad recipients | no recipient, an unknown user or role, a role not mentionable, or over `max-recipients` / `max-expanded` |
| DRS-7003 | 429 | too many | over a collaboration rate limit; `Retry-After` set |
| DRS-7004 | 403 | sharing off | collaboration, the channel, or sharing for the kind's pack is off by policy |
| DRS-7005 | 404 | thread not found | no thread, or not visible to the caller |
| DRS-7006 | 404 | comment not found | no comment, or not visible to the caller |
| DRS-7007 | 409 | thread locked | a comment on a locked thread |
| DRS-7008 | 403 | not editable | not the author, or the edit window has passed |
| DRS-7009 | 409 | stale comment | an edit built on an older revision |
| DRS-7010 | 423 | on hold | a purge or permanent removal of something under a legal hold |
| DRS-7011 | 422 | text refused | empty, too long, a `deny-patterns` match, a masked copy with `on-masked-copy: reject`, or a bad pin |
| DRS-7012 | 503 | mail unavailable | `mail-test`, or `channels.email` asked explicitly, while email is off or SMTP fails at once |
| DRS-7013 | 503 | bridge unavailable | a bridge test while bridges are off, the bridge cannot post (variable unset, URL not allowed) or the endpoint failed |
| DRS-7014 | 404 | no such bridge | `POST /admin/collab/bridges/{name}/test` for a name not configured |

`DRS-5002` keeps meaning "may not open the kind"; it is never used for a share id (that is `DRS-7001`, so share ids
are not an oracle). The console's `ui.error_advice` gains advice for each code (`drs_advice` in `core/backend.py`).

## Security and privacy

The rule: **what a person receives is computed for that person, when they look, from what they may see now.** A share
or comment stores the author's words and a pin; data is never copied in.

### Rights for someone other than the caller

A `Principals` service (`drishti-server`, `security` package) makes the principal of any user — current roles of an
enabled user, none otherwise — cached a minute, exactly what `JsonConfiguration.alertEngine` does inline today (that
bean then uses it). Two checks:

- `Entitlements.mayOpen(principal, kind)` — at open and at read time, unchanged.
- `Entitlements.mayReach(principal, kind)` (new) — at delivery: the role opens the kind **and** the kind's pack is
  *assigned* to the user (not necessarily active), so a recipient with the pack switched off is still told and the
  page offers to switch it on.

### Threat cases

| Threat | Defence | Test |
|---|---|---|
| A recipient without rights to the kind | delivery checks `mayReach`; the recipient row is `no-access`; nothing is sent; the open page re-checks `mayOpen` and shows the no-access page with no title; the inbox renders `(no access) · a trade view` | `ShareServiceTest`: role without the kind; pack not assigned; pack assigned not active; user disabled |
| Rights removed after sending | every read renders for current rights (inbox, share, thread); email already sent cannot be recalled, which is why email carries no data ([below](#what-an-email-contains)) | read after role change → no title, no excerpt |
| A masked field on the shared page | the recipient's view is built by `ViewController` for the recipient: masks apply as on any view | console test: shared link, non-raw recipient sees `•••` |
| A masked value typed into a note or comment ("J. Smith"), in any language case | at write, the server takes the author's document at the pin (the stored, unmasked document through `SourceRouter`), collects the masked fields' values with `Entitlements`' `mask-copies` routine (extracted from `copies(...)` as `maskedValues(doc)`), and records the **spans** of exact occurrences in the text; readers without `raw` see those spans as `•••`. Same bounds as `mask-copies` (min length, numbers of four digits or more, exact match). The author is warned; `on-masked-copy: reject` refuses instead. Spans are recomputed on edit. ([Decision 7](#decisions-for-the-product-owner)) | `CommentMaskTest`: masked value in text → `•••` for viewer, intact for raw; a JSON scan of every collab answer for a non-raw caller never contains the value |
| Values the author knows from elsewhere (not in this document) | not detectable by the document; `deny-patterns` (regexes, e.g. an MRN format) refuse them; documented as a limit, with the advice to use value quotes | deny-pattern test |
| Quoting a value | `{$.mtm}` tokens are stored as paths and rendered for each reader from **their** view at the comment's pin (the pinned view built once per (reader mask class, pin), cached), `•••` when masked, `—` when the path is gone | quote test, masked and unmasked |
| A field anchor on a masked field | the thread is visible (the label is on the page); the marker never carries text (`notes.js`' `data-note` with the body is removed) | console test |
| A panel anchor on a panel the reader sees as denied | the thread records the panel's `gate_kind`; readers must `mayOpen` it too, else the thread is not listed and counts omit it | `ThreadVisibilityTest` |
| Cross-pack leakage through `@mention` | a mention of a user or role notifies only people who `mayReach` the kind; others get nothing (not even "someone mentioned you"); the author is told who was not notified only under `undeliverable: tell` ([Decision 6](#decisions-for-the-product-owner)) | mention of a genomics-only user on a trade → no inbox row, no email |
| Learning other people's rights through the directory | the directory is scoped to users who share a pack with the caller ([Decision 3](#decisions-for-the-product-owner)); `reach` is shown only under `tell`; rate limited | directory scope test |
| A role mention as a broadcast tool | `mentionable-roles`, `max-group-size`; `admin` and `service` are never mentionable | — |
| Email leaking data | default email has no values: see below; per-pack override to `link-only`; links go to the console only (`console-url`), never to the API; no tracking pixels, no remote images; `Auto-Submitted: auto-generated` | `MailRenderTest`: rendered mail for a non-raw recipient contains no masked value and no strip value |
| Email to a wrong address | addresses come only from `User.email` set by an administrator or the provider; users cannot type addresses; changing a user's email is audited (existing) | — |
| Injection: HTML or script in notes and comments | plain text only; the console escapes (Jinja autoescape, `textContent` in JS); mention and quote tokens are parsed on the server into structured parts, the console renders parts, never HTML; email HTML is built by escaping every inserted value | console test with `<script>`, `javascript:` and `{{` |
| Injection: header injection in email | subjects are built from escaped parts with CR/LF removed; recipients are single validated addresses | mail test |
| Injection: template expressions | email templates are fixed files using Rachana-EL `template` with a closed variable map (no document access), compiled at start; user text is a variable, never a template | template test with `${…}` in a note |
| Share id guessing | ULID randomness plus the party check; a wrong id is `404 DRS-7001` whether it exists or not | — |
| Abuse: spam, mass mail | per-user rate limits (shares, comments, directory), `max-recipients`, `max-expanded`; outbox caps per recipient per hour | `RateLimitTest` |
| CSRF on console posts | the console's existing CSRF guard (`core/csrf.py`) covers the new `POST` routes | existing CSRF test pattern |
| Security off (local development) | collaboration works with `X-Drishti-User`; email refuses to start when `drishti.security.enabled` is false (identities are not real) | startup test |

### What an email contains

Default (`email.content: comment`): the product name, the sender's display name, the **kind's label and the entity id**
(the same id that is in the link), the panel title if one was shared, the pin's date, and the note **as the recipient
would see it** (masked spans as `•••`, quotes rendered as their label, not their value: "MTM (USD)"), plus the link.
No strip values, no table rows, no counterparty, no title `with` part. `link-only` drops the id and the note ("Ashutosh
Sinha shared a view with you"), for packs whose ids are themselves sensitive (a patient sample id). Justification:
mail leaves the firm's controls (forwarding, mobile clients, retention elsewhere); an id is already in the link and is
needed to triage, values are not; the note is the user's own words, scrubbed like any other reader's copy.
([Decision 5](#decisions-for-the-product-owner).)

### Access log and audit

- `AccessRecorder.ACTIONS` gains `share` (the sender; `detail` = `sh_… to 8 (user:ravi, role:risk)`; `kind`, `id`,
  `businessDate` of the pin). It is written **synchronously, in the share's transaction**, through a new
  `AccessLog.recordNow(Event)`, because the queued path drops events under pressure and a share must never be
  unrecorded.
- A view opened through a share carries `X-Drishti-Share: sh_…` (the console adds it); `AccessRecorder` writes the
  `view` row with `detail = share:sh_…`, and the share recipient's `opened_at` is set.
- Comment writes go to the audit log (`collab.comment.add`, `.edit`, `.retract`, `.hide`, thread state changes,
  holds, exports), as notes do today; the revision table is the full record.

## Notifications pipeline

```
 share / comment request
        │  one transaction (identity database)
        ├─► drishti_share / drishti_comment (+ revision, mentions)
        ├─► audience = recipients | mentioned | followers − author, expanded, each checked with mayReach
        ├─► drishti_inbox rows (in-app, always)            ──► InboxHub ──► SSE event "notice" ──► bell
        ├─► drishti_outbox rows (email, if on and wanted)
        └─► access log "share" row (recordNow)
        commit
                  OutboxDispatcher (virtual thread, every tick)
                  claim: UPDATE … SET state='sending', lease_until=now+lease, leased_by=me
                         WHERE state='pending' AND next_at<=now (batch)
                  render for the recipient NOW (current rights; scrubbed; or cancelled if no longer reachable)
                  send (JavaMailSender) ──ok──► state='sent'
                                         └fail─► attempts+1, next_at=now+backoff·2^n, state='pending' | 'dead'
```

- **Transactional outbox.** The domain row and its deliveries commit together, so a crash never loses a notification
  or sends one for a share that was rolled back. `Notifier` is an interface (`InAppNotifier` in step 2,
  `EmailNotifier` in step 4, `WebhookNotifier` in phase 2); `ShareService` and `ThreadService` hand the audience to
  every `Notifier` bean, so a new channel adds a bean and touches no service.
- **Several servers.** Claims use a lease, so any server may dispatch; `outbox.enabled: false` keeps a server out
  of it (as `drishti.reports.enabled` does). Live push is in-process (`AlertEngine`'s listener pattern); each
  server's `InboxHub` also polls `drishti_inbox` for rows above its high-water mark every `inbox.poll` *only while it
  has open streams*, so a notice written on server A reaches a browser on server B within seconds.
- **Retries.** Exponential backoff from `outbox.backoff` to `max-backoff`, `max-attempts` then `dead`. Permanent SMTP
  failures (5xx) go to `dead` at once. Dead letters are visible and retryable in Admin. Sent rows are kept
  `keep-sent-days`.
- **Idempotence.** A row is sent at most once per lease; a crash after send and before `sent` can resend once (the
  `Message-ID` is `<seq.ref@host>` so mail clients collapse duplicates).
- **Coalescing.** Several notices to one person about one thread within `inbox.coalesce` (60 s) become one email
  ("3 new comments on VAR-COMM"); in-app rows stay separate.
- **Preferences.** Per user in `/me/settings` (`notify`), stored in `PreferenceStore` (namespace `settings`): email
  for shares, mentions and replies on or off. Mentions and shares cannot be muted in-app (they are addressed); thread
  replies can.

## Retention, legal hold and export

- **Retention.** `retention.keep-days` (default `0` = keep forever, [Decision 8](#decisions-for-the-product-owner)).
  A daily `CollabPurge` removes threads whose last activity, and shares whose creation, is older than that, unless a
  hold covers them; inbox rows go after `inbox.keep-days`, outbox rows after `keep-sent-days`. A purge writes an audit
  event per thread with its final hash.
- **Retract and hide are not deletion.** They change what readers see; the revision keeps the text. "Delete
  permanently" exists only for administrators, only outside every hold, only for whole threads, and is audited
  (`423 DRS-7010` under hold).
- **Legal hold.** Scope `entity` (kind and id), `user` (everything a user wrote or received), `thread`, or `all`.
  Holds stop purge and permanent removal, not ordinary use. Placing and releasing are audited.
- **Export (eDiscovery).** An asynchronous job writes a zip to `drishti.collab.export-dir`:
  `manifest.json` (filters, who, when, counts, per-thread first and last hash, the software version),
  `shares.ndjson` (each share with recipients, states and opens), `threads.ndjson` (each thread with every revision,
  **unscrubbed** — the record — with author, pin, action, reason and hashes), `holds.ndjson`, and `README.txt`
  (field meanings). One JSON object per line, ISO-8601 UTC times, user names plus display names, stable ids. The export
  itself is audited and needs the `compliance` power; the zip is downloadable once per request by its requester and
  deleted after `export-keep`.
- **Verification.** `verify` recomputes a thread's chain; the manifest's hashes let the archive check that a later
  export did not rewrite history.

## Configuration

Server, `application.yaml` (all keys under `drishti.collab`; per-pack overrides by pack **name in configuration**, never
in code):

```yaml
drishti:
  collab:
    enabled: true                     # shares, threads, inbox
    store: jpa                        # jpa (identity database) | file
    dir: ${DRISHTI_COLLAB_DIR:./data/collab}            # the file store, and exports
    console-url: ${DRISHTI_CONSOLE_URL:}                 # links in emails; empty turns email off
    directory:
      scope: shared-packs             # shared-packs | all
      min-query: 2
      limit: 10
    mentionable-roles: ["*"]          # roles usable as groups; admin and service never
    max-group-size: 200
    share:
      max-recipients: 25
      max-expanded: 200
      max-text: 2000
      undeliverable: tell             # tell | silent
      post-to-thread: false           # the dialog's default
    threads:
      max-text: 4000
      max-per-entity: 500
      edit-window: 15m
      page-size: 50
    text:
      on-masked-copy: warn            # warn | reject | allow
      deny-patterns: []               # regexes refused in notes and comments
    limits:
      shares-per-minute: 5
      shares-per-day: 100
      comments-per-minute: 10
      directory-per-minute: 60
      mails-per-recipient-per-hour: 30
    inbox:
      keep: 1000
      keep-days: 180
      poll: 5s
      coalesce: 60s
    email:
      enabled: false                  # also needs spring.mail.host and console-url, and security on
      content: comment                # link-only | title | comment
      from: ${DRISHTI_MAIL_FROM:drishti@localhost}
      templates-dir: ""               # override share.subject / share.txt / share.html, mention.*, reply.*
    outbox:
      enabled: true                   # this server dispatches
      tick: 2s
      batch: 50
      max-attempts: 8
      backoff: 30s
      max-backoff: 1h
      lease: 60s
      keep-sent-days: 30
    retention:
      keep-days: 0                    # shares and threads; 0 keeps them forever
    export-keep: 24h
    packs: {}                         # e.g. genomics: { email: { content: link-only } }
    bridges: { enabled: false, webhooks: [] }      # phase 2
    snapshots: { enabled: false }                  # phase 2

spring:
  mail:                               # standard Spring Boot mail settings
    host: ${DRISHTI_SMTP_HOST:}
    port: ${DRISHTI_SMTP_PORT:587}
    username: ${DRISHTI_SMTP_USER:}
    password: ${DRISHTI_SMTP_PASSWORD:}
    properties.mail.smtp.starttls.required: true
    properties.mail.smtp.connectiontimeout: 10000
    properties.mail.smtp.timeout: 10000
```

Role powers (`drishti.security.roles.<role>`, stored as `drishti_role_power` rows, so earlier roles gain the default
without a migration, as `layout` did): `collaborate` (default **true**: share, comment, mention; the bundled `viewer`
keeps it) and `compliance` (default false: holds, exports, verify, read any share). Moderation is `admin`.

Console (`console/config/application.yaml`): `collab.enabled` (hide the UI when the server says off),
`collab.inbox_page_size`. Every new key goes into CONFIGURATION.md's tables and placeholder list
(`test_docs_settings.py`, `test_docs_placeholders.py` check them).

A new Maven dependency: `spring-boot-starter-mail` (Jakarta Mail, Angus Mail implementation; EPL-2.0 / GPL-2.0 with
Classpath Exception / EDL-1.0), recorded in `THIRD-PARTY-NOTICES.md`. Nothing else is added server-side; the console
adds no library.

## Console design

- **Routes** (`console/routes/collab_routes.py`, new): `POST /api/share` (proxy), `GET /share/{id}` (resolve the
  pin, redirect to `/v/{kind}/{id}?asOf=&knownAt=&gen=&share=` or render `terminal/share_denied.html`),
  `GET /v/{kind}/{id}/discussion` (the drawer body partial, like `/about`), `POST /api/threads/...` proxies,
  `GET /inbox`, `POST /api/inbox/read`, `GET /api/directory`. Server calls through `core/collab.py` (a small client per
  area, so steps do not collide in `core/backend.py`).
- **Pinned links survive sign-in**: `AuthGate` keeps the full path *and query* in `next=` (it keeps only the path
  today), and `_safe_next` keeps accepting only same-site paths.
- **Per-request as-of**: `AuthGate` already honours `?asOf=` per request without a cookie; it gains `?knownAt=` the
  same way. The view template shows the *as shared* banner when `share=` is present, and the console sends
  `X-Drishti-Share` on the view call.
- **Templates**: `terminal/_share.html` (dialog), `terminal/_discussion.html` and `_macros/collab.html` (thread,
  comment, mention and quote parts), `terminal/share_denied.html`, `inbox.html`, `admin/collab.html`; the drawer host in
  `terminal/view.html` becomes `<aside class="side" id="sideDrawer">` with two tab panels, *About* (the existing
  `#aboutDrawer` body, unchanged inside) and *Discussion*.
- **JS** (each under 300 lines, no library): `share.js` (dialog, `Alt+S`, value quote picker), `people.js` (the
  directory type-ahead used by the dialog and by `@` in comments: `role="combobox"`, `aria-activedescendant`, arrow
  keys, `Enter`, `Esc`), `discussion.js` (drawer tab, `Alt+N`, threads, reply, edit, retract, follow, badges from
  `/counts` fetched after paint, field markers replacing `notes.js`), `alerts.js` (bell: notices beside alerts, unread
  count from `/me/inbox/count` at load then `notice` events), `inbox.js` (`/inbox` page, `Alt+I`). `about.js` changes
  only to open inside the shared host.
- **CSS**: `collab.css`, colours only from `tokens.css` (`--d-*`), so all seven themes work; `test_contrast.py` gains
  the new tokens.
- **Keys.** `Alt+S` share, `Alt+N` discussion, `Alt+I` inbox (none is bound today: `Alt+C` Calc, `Alt+L` layout,
  `Alt+←`, `Alt+0…4` in workspaces); `Ctrl+Enter` sends in the dialog and in a reply box; `Esc` closes and returns
  focus to the opener. Keys are ignored while typing in a field, as `about.js` does for `?`.
- **Phone** (`max-width: 640px`): the share dialog is a full-screen sheet; the side drawer is the bottom sheet the
  About drawer already uses (one tab at a time, swipe down closes, focus trapped as `pivot.js:dialog()` does); the
  inbox is a single column.
- **Accessibility.** The dialog is `role="dialog" aria-modal="true"` with a focus trap; the drawer stays
  `aria-modal="false"`. New comments arriving live are announced in a polite live region ("1 new comment on VaR
  contribution"); badges have text alternatives ("2 comments"); the value-quote and mention tokens are buttons with
  labels.
- **Workspace panes** (`embed=1`): Discussion opens inside the pane; Share opens in the parent window (one dialog).
- **Vendored only.** No new third-party asset; `test_assets_policy.py` rules apply to every new file; no inline
  script or style (the CSP).

## Performance budget

| Item | Budget |
|---|---|
| View page (`GET /views/...`) | unchanged: no new work, no new bytes |
| `GET /threads/{k}/{id}/counts` | p95 ≤ 10 ms (one indexed query, cached per entity 30 s, invalidated on write) |
| `GET /threads/{k}/{id}` (50 comments) | p95 ≤ 40 ms server; quote rendering builds the pinned view at most once per (mask class, pin), cached; scrubbing is span replacement, no document read |
| `POST /shares` (25 recipients, 200 expanded) | p95 ≤ 80 ms, one transaction; the masked-copy scan reads the document the router has cached |
| Notice to bell | ≤ 1 s same server; ≤ `inbox.poll` + 1 s across servers |
| Email | first attempt within `outbox.tick` after commit |
| Console JS + CSS | `share.js` + `people.js` + `discussion.js` + `inbox.js` ≤ 40 KB, `collab.css` ≤ 8 KB unminified; loaded only on pages that use them |
| Outbox dispatch | 50 mails per tick per server; the SMTP connection reused within a batch |

Two mask classes exist per entity (`raw` or not: `Entitlements.masks` is binary), so rendered comments cache well.

## Phase 2: bridges and watermarked snapshots

Design only; not in the numbered build steps except as steps 9–10, after everything above ships.

**Bridges (Teams, Slack, generic webhooks).** An administrator binds a pack, a kind or a thread to a named bridge
(`drishti.collab.bridges.webhooks`: name, URL prefix allowed like `drishti.reports.webhooks`, format
`teams`/`slack`/`json`). New comments and shares that opt in post *the comment and the link* through the outbox
(`channel: webhook`), rendered **as an unprivileged reader** (the role named by `bridges.render-as`, default the
bundled `viewer`), so masked spans read `•••` and value quotes render as labels. Never a data value. Redirects are not
followed (as `ReportService`'s client). Inbound replies from chat are out of scope.

**Watermarked snapshot.** An administrator policy (`snapshots.enabled: false`, per pack) lets a share include a PNG of
the view. It is rendered on the server for the **most restrictive recipient**: if any recipient may not open the kind
or a panel's gate kind, that panel is left out; if any recipient lacks `raw`, the view is built with masks (masks are
binary, so this is the non-raw view). Rendering is a server-side Java2D drawing of the ViewModel's title, strip and
tables (charts are drawn as their title with "open in Drishti"), so no browser runs on the server
([Decision 11](#decisions-for-the-product-owner)); a diagonal tiled watermark carries the recipients, the share id and
the time; the image is stored with the share (counted in retention and export) and attached to the email only under
`email.content: comment`. Each snapshot is recorded in the access log as `export`.

## Documentation changes

Each step updates the documents it changes; nothing is described twice — guides link here for the design and keep only
what their reader needs.

| Document | Change |
|---|---|
| [USER_GUIDE.md](../guides/USER_GUIDE.md) | *Export, print and share* → *Share with a note* (dialog, recipients, as-shared banner, no-access page); a new *Discussion* section (threads, anchors, mentions, quotes, open as it was, edit window, resolve, follow); *The inbox*; the key table (`Alt+S`, `Alt+N`, `Alt+I`); the old Notes section points to Discussion |
| [API_GUIDE.md](../guides/API_GUIDE.md) | endpoint reference for directory, shares, threads, inbox, admin; §Field masks lists shares, threads, inbox and email as masked answers; §access log adds `share` and `share:` detail; error table `DRS-7001`–`7012` and the new first digit; *Notes* marked deprecated with the mapping |
| [CONFIGURATION.md](../admin/CONFIGURATION.md) | `drishti.collab.*`, `spring.mail.*` and their placeholders; the `collaborate` and `compliance` powers in `drishti.security` |
| [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) | moderation, legal holds, exports, the compliance power, what users can learn about each other through the directory |
| [OPERATIONS.md](../admin/OPERATIONS.md) | the outbox (dead letters, retry, mail test), several servers (lease, `outbox.enabled`), retention purge, backups of the collab tables |
| [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md) | "the email never arrived", "the shared link opened today's data", "someone was not notified"; error rows |
| [ARCHITECTURE.md](ARCHITECTURE.md) | the collab package in the module list and security section (one paragraph, link here) |
| [HOW_IT_FITS.md](HOW_IT_FITS.md) | a full section, *Sharing and discussing a view, end to end*: worked on trade MX-20000001 — the dialog → `POST /shares` with the as-of headers → pin, `mayReach` per recipient, masked spans → one transaction (share, inbox, outbox, access log) → the `notice` SSE event on the channel → the bell → `/share/sh_…` → the view built for the recipient with `asOf`/`knownAt` per request → the banner; then a comment with `@risk` on the VaR contribution panel and *Open as it was*; the genomics variant in a few lines (`link-only` email); collab added to §1's picture and §2's pieces; §7 rows (who may share, email, retention) |
| `adr/020-collaboration-records-live-in-the-identity-database.md` | why the identity database, a transactional outbox, and links instead of data |
| `console/web/guides/monitors-and-alerts.md` | the bell becomes the inbox (notices beside alerts); `help.yaml` `contextual:` gains `inbox` |
| [docs/README.md](../README.md) | this document in the architecture list (done with this design) |

## Build plan

Each step ships on its own with tests that fail before it, its documents and a CHANGELOG bullet. Sizes: S ≤ 1 day,
M 2–3 days, L 4–5 days. Browser tests are Playwright against a scratch server and console, like
`test_about_browser.py`.

| Step | Scope | Files touched | Tests | Accepted when | Size |
|---|---|---|---|---|---|
| **1. Pinned links that survive sign-in** (console) **(built)** | `AuthGate` keeps the query in `next=`; `?knownAt=` per request like `?asOf=`; `share_url()` emits `/v/{k}/{id}?asOf=&knownAt=&gen=` instead of `/asof?…` (the old form still works); the *as shared* banner with the three outcomes (as it was, `DRS-1007` retry, generation changed → *What changed*); *Go live* | `console/core/app.py`, `core/asof.py`, `routes/terminal_routes.py` (`share_url`, `view`), `templates/terminal/view.html` (banner only), `static/css/terminal.css` | `test_share_link.py` (signed-out → login → lands on the pinned date; cookie untouched; `DRS-1007` fallback; banner text), `test_share_link_browser.py` | a copied link to 29 Sep known at 18:00 opened signed out by another user shows 29 Sep at 18:00 after sign-in, and their other tabs stay live | S |
| **2. Share with a note: server** (done) | `drishti.collab` properties (all keys), full collab schema (both SQL files, every table above so later steps add none), `ErrorCode` `DRS-7001`–`7012`; `Principals`, `Entitlements.mayReach` and `maskedValues` (extracted from `copies`); `ShareStore` (JPA + file), `InboxStore` (JPA + file), `ShareService`, `InboxService`, `InboxHub`, `Notifier` + `InAppNotifier`, `RateLimits`; `DirectoryController`, `ShareController`, `InboxController`; `notice` events on `/me/alerts/stream`; `AccessLog.recordNow`, `share` action and `X-Drishti-Share` detail; `collaborate`/`compliance` powers | `drishti-common/.../ErrorCode.java`; `drishti-identity/.../collab/*` (new), `db/schema-*.sql`, `IdentityConfiguration.java`, `AccessLog.java`, `RoleDefinition.java`/`RoleEntity.java` (powers); `drishti-server/.../collab/*` (new), `security/{Entitlements,Principals}.java`, `api/{AlertController,AccessRecorder}.java`, `JsonConfiguration.java` (uses `Principals`), `application.yaml` | `ShareServiceTest` (rights matrix, expansion, limits, spans), `ShareStoreContractTest` run against both stores, `ShareControllerTest` (codes, 404 for strangers), `CommentMaskTest`-style JSON scan, `InboxHubTest` (two servers on one SQLite file), `AccessRecorderTest` | `curl POST /shares` to a user and a role: inbox rows for those who may reach, skipped list for the rest, an access-log `share` row even with the queue full, the bell stream emits `notice` | L |
| **3. Share with a note: console** (done) | dialog, people picker, value quotes, *Copy link* kept; `/share/{id}` resolver and no-access page; bell notices and unread count; `/inbox` page | `routes/collab_routes.py` (new), `core/collab.py` (new), `templates/terminal/{_share,share_denied}.html`, `templates/inbox.html`, `templates/terminal/view.html` (Share button), `static/js/{share,people,inbox}.js` (new), `static/js/alerts.js`, `static/css/collab.css`, `templates/terminal/_topbar.html` (bell link → inbox), `config/application.yaml` (`ui.error_advice`) | `test_share.py`, `test_inbox.py`, `test_share_browser.py` (keyboard-only share, phone sheet, focus return, recipient opens and sees `•••`, no-access page), `test_assets_policy.py`, `test_contrast.py` | from MX-20000001 a user shares with `ravi` and `risk`; Ravi's bell counts 1 within a second; the link opens the pinned view; a user without `trade` sees the no-access page | M |
| **4. Email** (done; see *Step 4 as built*) | `EmailNotifier`, `OutboxStore` (JPA + file), `OutboxDispatcher` (lease, backoff, dead letters, coalescing), templates (`share`, `mention`, `reply`, `test`) with Rachana-EL over a closed map, `notify` settings, per-pack `content`; admin outbox endpoints and mail test; `spring-boot-starter-mail` | `drishti-server/.../collab/mail/*` (new), `drishti-identity/.../collab/Outbox*.java` (new), `api/SettingsController.java` (`notify`), `drishti-server/pom.xml`, `resources/collab/templates/*`, `THIRD-PARTY-NOTICES.md`; console `templates/account.html` (notify toggles) | `OutboxDispatcherTest` against an in-process SMTP stub on a free port (retry, 5xx → dead, lease takeover, coalescing), `MailRenderTest` (no values, masks, `link-only`, header injection), startup refusal with security off | with `spring.mail.host` at a stub, a share to Ravi sends one mail with the note and the link and no figures; stopping the stub leaves it pending and it is sent when the stub returns | M |
| **5. Threads: server** (done) | `ThreadStore` (JPA + file), `ThreadService` (anchors, gate kinds, pins, mentions, followers, edit window, retract, resolve, revisions with the hash chain, spans, quotes), `ThreadController`, `/me/mentions`, `NoteImport`, `/notes` facade | `drishti-identity/.../collab/Thread*.java`, `Comment*.java`, `NoteImport.java` (new); `drishti-server/.../collab/thread/*` (new), `api/NoteController.java` (facade) | `ThreadServiceTest`, `ThreadStoreContractTest` (both stores), `ThreadVisibilityTest` (kind, pack, gate kind), `HashChainTest`, `NoteImportTest` (idempotent), `NoteControllerTest` (facade, unchanged shape) | a comment on the VaR contribution panel with `@risk` reaches `risk` members who may open `var`, not a genomics-only member; editing after 15 min is `403 DRS-7008`; the chain verifies | L |
| **6. Threads: console** (done; see *Step 6 as built*) | shared side drawer (About and Discussion tabs), threads, reply, edit, retract, resolve, follow, `@` and `{` pickers, panel badges, field markers, *Open as it was*, live new-comment notices; Notes drawer and `notes.js` removed | `templates/terminal/{view,_discussion}.html`, `_macros/collab.html`, `static/js/discussion.js` (new), `static/js/about.js` (host only), `static/css/{about,collab}.css`, `routes/collab_routes.py` (discussion routes), `core/threads.py` (new); `static/js/notes.js` deleted | `test_discussion.py`, `test_discussion_browser.py` (keyboard-only thread, `Alt+N` / `?` switching tabs, phone sheet, `•••` in a quote for a viewer, *Open as it was*), `test_about_browser.py` still green, `test_fkeys.py` | on VAR-COMM a user opens a panel thread from its badge, mentions a colleague, and the colleague's *Open as it was* shows the comment's date | L |
| **7. Moderation, retention, legal hold, export** (server done; see *Step 7 as built*) | hide/unhide, lock, holds, `CollabPurge`, export job and zip, verify; admin console page | `drishti-server/.../collab/compliance/*` (new), `drishti-identity/.../collab/Hold*.java` (new); console `routes/compliance_routes.py`, `core/compliance.py`, `templates/admin/collab.html`, `static/js/admin-collab.js` (new) | `CollabPurgeTest` (holds win, whole threads only), `ExportTest` (manifest, NDJSON, hashes, unscrubbed only for `compliance`), `ModerationTest`, `test_admin_collab.py` | a hold on MX-20000001 keeps its threads past retention; an export of a day verifies against the chain | M |
| **8. End-to-end docs** | HOW_IT_FITS section, ARCHITECTURE paragraph, ADR-020, USER_GUIDE consolidation, the console's monitors-and-alerts guide | docs only | `test_help_links.py`, `test_docs_error_codes.py`, `test_docs_settings.py`, licence headers | every link resolves; every new code and key documented | S |
| **9. Bridges** (phase 2; done, see *Step 9 as built*) | `WebhookNotifier`, bridge bindings, render-as role | `drishti-server/.../collab/bridge/*` (new), admin page section | stub HTTP server test (allow-list, no redirects, masks) | a comment on a bound kind posts text and link only | M |
| **10. Watermarked snapshots** (phase 2) | `SnapshotRenderer` (Java2D), most-restrictive principal, policy, attachment | `drishti-server/.../collab/snapshot/*` (new) | golden-image test, a recipient without `raw` → masked image, a gate-kind panel left out | off by default; on, an image never shows more than its least-entitled recipient may see | L |

**Step 3 as built.** `core/collab.py` (client, `link_for`, the `X-Drishti-Share` contextvar `BackendClient` adds to every call while a view
opened through a share is built), `routes/collab_routes.py` (`/api/collab`, `/api/directory`, `/api/share`, `/share/{id}`, `/inbox`,
`/api/inbox/{count,read}`), `terminal/_share.html`, `terminal/share_denied.html`, `inbox.html`, `share.js`, `people.js`, `inbox.js`, `alerts.js`,
`collab.css`. Deviations: (1) the dialog posts the page's own `asOf` and `knownAt` in the body (a pinned link sets no cookie), which the route turns into the
as-of headers of the one call; (2) the bell is the inbox's door: it links to `/inbox` (Alerts stay at `/alerts`, reached from the inbox page), its count starts from
`GET /me/inbox/count` and rises with `notice` and `alert` events, and there is no dropdown tray yet; (3) the panel's own *Share this panel* is a
**send** icon `share.js` adds to each panel header (no panel menu exists), and `gateKind` is sent only when the panel element carries `data-gate-kind`,
which no panel does yet (the server's view marks a denied panel itself); (4) value quotes (`{`) and the email channel are steps 5 and 4; (5) a recipient
who may not open the share gets `403` with the clean page, a stranger `404` with a page that does not say a share exists, and *Switch on <pack>*
shows when the server says `pack-off`; (6) a link's `knownAt` is cut to whole seconds, the console's form.

**Step 2 as built, and where it differs from the design above.** `drishti.collab.*` is `CollabProperties` in `drishti-identity` (every key with
its default, in `application.yaml` and CONFIGURATION.md); the schema files hold every table of the design, the code maps share, share
recipient and inbox (the others arrive with their steps). `ShareStore` and `InboxStore` have a JPA and a file implementation each, chosen by
`drishti.collab.store`; one contract (`CollabStoreChecks`) runs against both, and the whole API contract (`ShareApiContract`) runs on both
stores. `CollabTx` is the transaction the share, its recipients, the inbox rows and the access-log row share (one database transaction; a
lock for files). `Principals` (server) is the principal of any user, cached a minute; `Entitlements.mayReach`, `reachState` and `maskedValues`
are the new checks (`maskedValues` is the `mask-copies` routine without its on/off switch: collaboration always scrubs). `ShareService`,
`DirectoryService`, `InboxService`, `InboxHub` (push, plus a poll of `drishti_inbox` only while a stream is open), `Notifier` with `InAppNotifier`,
`RateLimits` (sliding windows; the day limit counts shares in the store). `GET /collab` is added (not in the table above): the console asks it for
the limits, whether collaboration is on and what the caller may do. Deviations: (1) a recipient who may no longer open a share gets `200` with
`access: false` and the reason (the console draws the no-access page from it) rather than an error; (2) `gateKind` is supplied by the console
with the panel (the server does not re-derive it from the layout; the view still marks the panel denied when it is built); (3)
`POST /shares/{id}/replies` and `postToThread` were built in step 5; (4) value quotes
(`{$.mtm}`) were built in step 5; (5) a disabled or unknown user cannot be addressed by name (the picker never lists them), so the recipient state `disabled` is
only reached by expansion rules that exclude them; (6) a user's inbox is deleted with the user, the shares they sent stay; (7)
`drishti.collab.packs.<pack>.share-enabled: false` is the one per-pack key used so far; (8) the `compliance` power reads any share, and with
security off every caller is treated as holding it, as for every power.

**Step 4 as built, and where it differs from the design above.** `OutboxStore` (JPA and file, one contract, `CollabStoreChecks.outbox`), `EmailNotifier`
(writes the outbox row inside the share's transaction; skips people with no address, who turned `notify.email.share` off, or at the hourly cap),
`OutboxDispatcher` (claim under a lease, render for the recipient now, send, retry with doubling backoff, dead letters, cancelled rows, sent rows
purged after `keep-sent-days`, Micrometer `drishti.collab.mail` and `drishti.collab.outbox`), `MailRenderer`/`MailTemplates` (text and HTML, product
name from `drishti.branding.product`), `MailContentPolicy` (`comment`, `title`, `link-only`, per pack by `packs.<pack>.email.content`),
`ShareItemRenderer` (the note scrubbed for the recipient's rights), `OutboxAdminController` (`GET /admin/collab/outbox`, `POST .../outbox/{seq}/retry`,
`POST .../mail-test`) and `notify.email.*` in `/me/settings`. Deviations: (1) templates are fixed files filled by a one-pass `${name}` substitution over a
closed map, not Rachana-EL `template`: it needs no engine, a note can never be read as a template, and the same guarantees hold (tested); (2) the
dispatcher renders through `ItemRenderer` beans, one per outbox template; `share` and `test` exist, `mention` and `reply` arrive with the threads of
step 5 as new beans, with coalescing of several thread notices into one email (a share is always one email); (3) no panel *title* is in the mail, only
the panel id (the share stores the id); (4) `spring.mail.host` is not defaulted from a `DRISHTI_SMTP_*` placeholder (an empty host would still create the
mail client): use the standard `spring.mail.*` or `SPRING_MAIL_*`; (5) the mail health check is switched off so an unreachable mail server never turns
`/readyz` red; (6) the console toggles for `notify` (account page) belong to the console step and are not built here; (7) a missing `spring.mail.host` or
`console-url` leaves email off (`503 DRS-7012` on request); only email on with sign-in off refuses to start. Tests: `OutboxDispatcherTest`,
`MailRenderTest`, `ShareMailJpaTest` and `ShareMailFileTest` (GreenMail, in-process), `ShareMailLinkOnlyTest`, the outbox contract on both stores.
**Step 5 as built, and where it differs from the design above.** `CommentThread`, `Comment`, `Revision` (with `HashChain`: SHA-256 per thread,
the first step chaining from the thread id), `Mention`, `Follow` and `ThreadStore` live in `drishti-identity` with a JPA store (entities in
`ThreadEntities`) and a file store (one append-only JSON-lines log per entity, folded into memory once); one contract
(`CollabStoreChecks.threads`) runs against both, and the whole API contract (`ThreadApiContract`) runs on both stores. `ThreadService`
(server, `collab.thread`) holds the rules; `CommentRenderer` renders a comment for one reader (masked ranges as `•••`, `@name` and
`{$.path}` tokens as structured parts) from `PinnedDocs` (the stored document at the comment's pin, read through `SourceRouter`, cached a
minute, never sent unmasked); `Audience` resolves mentions and applies the cross-pack rule. `Notifier` gained `onComment` (default: nothing),
which `InAppNotifier` implements (inbox rows of type `mention` and `reply`); the email notifier of step 4 adds its own. Deviations: (1)
`hide` and `unhide` (`/admin/collab/comments/{cid}/...`) and `locked` are built here, not in step 7, because moderation is part of the comment's
life; export, purge and legal holds stay in step 7. (2) The chain per thread is found through the thread's comments (no `thread_id` column on
`drishti_comment_revision`), so a step's time is moved forward by a millisecond when it would equal the previous one. (3) A reply to a share
is a comment in a thread whose anchor is `share` (its `path` holds the share id): private to the share's parties, never listed with the
discussion, counted or mentioned; `postToThread: true` creates a normal thread whose first comment is the note, in the share's
transaction, and the share's `threadId` points at it. (4) A legacy note is linked to the comment it became in a new table,
`drishti_note_link` (`NoteImport` runs at start and imports only what is not linked; the facade allocates the next number above the linked
ones). Notes made through `/threads` are not listed by `/notes`. (5) Imported notes were never scanned for masked values (the document is
not known at import); they read as written. (6) Thread and comment counts count comments written, retracted ones included. (7) A comment
that cannot be checked against its document (the source is down) refuses an edit rather than skip the scrub, except for imported notes
(generation 0). (8) `GET /threads` pages threads by `before` = a thread id; each thread returns its first `page-size` comments and `more`.

**Step 9 as built, and where it differs from the design above.** `drishti-server/.../collab/bridge`: `BridgeRegistry` (the configured bridges, URL and
secret read from the environment variables named in configuration, the `bridges.allow` prefixes, the routing table), `BridgeNotifier` (a `Notifier` of
channel `bridge`: one outbox row per matching bridge, written in the share's or comment's transaction), `BridgeItemRenderer`, `BridgeFormat` (`json`, `teams`,
`slack`), `BridgeClient` (no redirects, no response body kept), `BridgeSender` (the `OutboxChannel` the dispatcher serves), `BridgeAdminController`
(`GET /admin/collab/bridges`, `POST /admin/collab/bridges/{name}/test`). Deviations from the design: (1) the bridge's URL and signing secret come from
**environment variables** named by `url-env` and `secret-env` (Teams and Slack URLs are credentials), not from a configured URL prefix list; `bridges.allow`
is the prefix list the resolved URL must start with, and it is empty (nothing may post) until an administrator fills it; (2) routing is per bridge,
`routes: [{packs, kinds, events}]` with events `share`, `comment` and `mention`, instead of binding a thread: a bridge posts every comment on the kinds it covers
(replies inside a share's private thread are never posted); a comment that mentions someone and matches both a `comment` and a `mention` route is one post, as a
mention; (3) the rendering reader is a synthetic principal holding only the role `bridges.render-as` (default `viewer`), so masked ranges read `•••`; a value quote
`{$.path}` is **never** filled in, even for a role with `raw` (it reads as its path); the pack's `email.content` (`link-only`, `title`, `comment`) applies to
bridges too; (4) the dispatcher gained `OutboxChannel`: a channel throws `Skip` (cancel), `Permanent` (dead letter at once: 4xx, a redirect, a bridge that cannot
post) or `Deferred` (over `bridges.per-minute`: pending again, no attempt counted), anything else is retried with the outbox backoff; the dispatcher runs when email
or any bridge is available; (5) every post is an audit-log entry `collab.bridge.post` (subject = the bridge, detail = event, id, delivery), every dead letter
`collab.bridge.dead`, every test `collab.bridge.test`; no access-log row is added, because the share's own `share` row already records the disclosure
decision; (6) `GET /admin/collab/bridges` shows each bridge's host, status (`ok`, or why not, naming the variables) and its outbox counts, never the URL path or the
secret; (7) no secret is ever logged: failures are described by status or exception class only; (8) problem codes `DRS-7013` (bridge unavailable: off, not usable, or the
test post failed) and `DRS-7014` (no such bridge). Follow-ups built with it: **mention and reply emails** (`CommentItemRenderer`, templates `mention` and `reply`,
`EmailNotifier.onComment`; coalescing of several notices into one email is still not built, each mention or reply is one email), **panel titles instead of ids** in
every email and bridge post (`PanelTitles`, read from the view as the recipient gets it, the id when it cannot be built), and the **inbox purge** by
`inbox.keep-days` (`InboxPurge`, every `retention.interval`; this closes deviation (7) of step 7). Tests: `BridgeDeliveryTest` (payload shapes against an in-process
fake endpoint, HMAC, masked values never sent, routing, retry, dead letter, rate limit, redirect, secrets), `BridgeRegistryTest`, `CommentMailTest`,
`InboxPurgeTest`, the inbox purge in the store contract.

**Step 7 as built, and where it differs from the design above.** `Hold`, `HoldStore` (JPA and `holds.json`), `drishti.collab.retention` (`keep-days`,
`kinds`, `interval`; `packs.<pack>.retention-days`), `CollabPurge` (daily, only when some retention is above 0; holds win; whole threads; audited with the
final hash), `HoldService`, `ChainVerifier`, `ExportService` and `ComplianceController` in `drishti-server/.../collab/compliance`; `ThreadStore` and
`ShareStore` gained `page` and `deleteThread` / `delete`. Moderation (hide, unhide, lock) was built in step 5. Deviations: (1) **API only: the admin console
page (`templates/admin/collab.html`, `compliance_routes.py`) is not built here**, it waits for the console work of step 6 to land; (2) a hold's scope also
has `kind`, and any scope takes a date range (`drishti_collab_hold` gained `date_from` and `date_to`; SQLite databases made earlier get the columns at
start); (3) the export streams a zip with `shares.ndjson`, `threads.ndjson`, `chains.ndjson` (first and last hash per thread, instead of a list inside the
manifest, so memory is bounded), `holds.ndjson`, `README.txt` and a last `manifest.json` with every file's SHA-256; "notices" are the share
recipients' delivery states and opens and each comment's mentions, the outbox and inbox rows are not exported; (4) jobs are kept in memory (a restart forgets
an unfinished one), one runs at a time with a queue of 8, and a download happens once; (5) `GET /admin/collab/verify` without `thread` checks everything
(or one entity) and `GET /admin/collab/threads/{id}` gives the compliance officer the full record; (6) administrators may also run retention by hand
(`POST /admin/collab/retention/run?dryRun=`) and remove one whole thread (`DELETE /admin/collab/threads/{id}`, `423 DRS-7010` under a hold); (7) inbox rows are
not yet purged by `inbox.keep-days` (they are capped by `inbox.keep`); (8) exports are audited in the audit log (`collab.export.*`), not the access log.
Tests: `ComplianceApiContract` on both stores (authorization, hold scopes and date ranges, purge skips held items, export contents and checksums, tamper
detection), `CollabPurgeDaysTest`, `HoldTest`, the store contract's `removal` and `holds`.
**Step 6 as built, and where it differs from the design above.** One side drawer (`#aboutDrawer`, kept so About's code and tests are unchanged) with
a tablist, an About panel and a Discussion panel (`terminal/_discussion.html`); `about.js` owns the host (open, close, focus, phone sheet, tabs, the
`drishti:drawer` event, `window.drishtiAbout.{open,close,setTab,tab}`), `?` and F1 always land on About, Alt+N on Discussion. `discussion.js` (controller),
`discussion-view.js` (drawing, `textContent` only) and `discussion-compose.js` (the `@` and `{` pickers, one ARIA combobox over the textarea) replace
`notes.js`, which is deleted along with the Notes drawer. Deviations: (1) the tab is drawn from JSON (`/api/threads/...`, `routes/thread_routes.py`,
`core/threads.py`), not from a server-rendered `/v/{kind}/{id}/discussion` partial: every comment arrives scrubbed for the reader and is set with
`textContent`, so there is no HTML to trust; (2) the console adds `href` (the pinned link) to every comment from its pin, and *Open as it was* shows only
when the page's date or generation differs from the comment's; (3) a post carries the page's `asOf` and `knownAt` in its body, which the route turns into the
as-of headers (as for a share), so a comment written on a past date is pinned to it; (4) the new-thread box is always at the bottom of the tab, and
anything else (a reply, an edit, a hide reason) is an inline form; (5) live updates ride the bell's channel: a `notice` of type `mention` or `reply` about
this entity refreshes the list and is announced in a polite live region (readers who follow no thread are not pushed anything; they see new comments when
they open the tab); (6) the panel's comment icon is added by `discussion.js` beside the `?` once `/api/collab` says collaboration is on, and the field
dot reuses `.has-note`; (7) the account page's `notify.email.{share,mention,reply}` toggles (left from step 4) are built, saved through `/me/settings`.
Tests: `test_discussion.py`, `test_discussion_browser.py` (two users, mention and bell, masked value and quote, edit, retract, hide, open as it was,
keyboard-only, phone).

Each step's own docs are part of it (USER_GUIDE, API_GUIDE, CONFIGURATION rows for what it adds); step 8 writes the
cross-cutting sections only.

**Parallel groups** (no two steps in a group touch the same file):

- **Group A:** step 1 (console core: `app.py`, `asof.py`, `terminal_routes.py`, `view.html` banner) ‖ step 2 (server
  and identity only).
- **Group B**, after A: step 3 (console share UI) ‖ step 4 (email: new server files, `SettingsController`, `pom.xml`;
  console `account.html` only) ‖ step 5 (threads server: new files, `NoteController`). Step 2 already landed every
  table, error code and configuration key, and `Notifier` beans are collected by Spring, so 4 and 5 edit no shared
  file.
- **Group C**, after B: step 6 (view page, drawer, discussion) ‖ step 7 (compliance: new server package, new console
  route, client and admin template).
- **Group D**, after C: step 8 ‖ step 9; step 10 alone, last, only if the product owner keeps it.

## Decisions for the product owner

| # | Decision | Recommendation |
|---|---|---|
| 1 | Notes and threads | Notes are **already shared**, not private (`NoteStore`: "everyone who may open the kind reads them"), but unmasked, unpinned and hard-deleted. Grow them into threads: import existing notes once as single-comment threads, keep `/notes` as a deprecated facade for one release, replace the Notes drawer with Discussion. Do not add private notes: a private channel in a regulated product is still a record to keep and export, without the controls |
| 2 | Store | JPA (identity database) as the default; the file store for single-server and tests, refused with a shared PostgreSQL database |
| 3 | Directory scope | `shared-packs`: a user sees in the picker only users who share at least one assigned pack, and mentionable roles; `all` for small firms |
| 4 | How a pinned link sets the date | per-request `asOf`/`knownAt` parameters with a banner, never the recipient's sticky cookie; fix the sign-in redirect to keep the query |
| 5 | Email content | `comment` by default (kind label, id, panel title, the note as the recipient would read it, the link; no values); `link-only` per pack for packs whose ids are sensitive (genomics); email off until an administrator configures SMTP and `console-url` |
| 6 | Telling the sender who was not notified | `tell` (names and a reason: "may not open trade views"): sharing needs it, and the sender learns no data, only that a colleague lacks a right; `silent` for firms that treat rights as confidential |
| 7 | Masked values typed into text | always scrub for readers without `raw` (write-time spans with the `mask-copies` rules, whatever `drishti.security.mask-copies` says), warn the author, `reject` available; offer value quotes as the safe way to cite a figure; document that values from outside the document are not caught (use `deny-patterns`) |
| 8 | Edit, delete, retention | 15-minute edit window with every revision kept; after it, retract only; administrators hide with a reason; nothing erased before retention; retention default keep-forever (`keep-days: 0`) so no record is destroyed by default |
| 9 | About and Discussion drawers | one side drawer with two tabs (`?` → About, `Alt+N` → Discussion), rather than two drawers that stack or overlap |
| 10 | Inbox and the bell | one bell for notices and alerts with tabs; alerts stay in `drishti_alert` and `/alerts`; notices ride the existing `/me/alerts/stream` (no new connection, the six-connection lesson of `live-hub.js`) |
| 11 | Snapshot renderer (phase 2) | server-side Java2D of strip and tables, no headless browser; off by default; only if the product owner keeps phase 2 |
| 12 | Groups | roles are the groups (single sign-on already maps directory groups to roles); no separate group store in this design; revisit if firms need mailing-list-style groups that grant no rights |
| 13 | Error code range | a new first digit, 7 for collaboration (`DRS-7001`–`7012`), documented in `ErrorCode`'s header |
