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
# ADR-020: Collaboration records live in the identity database; the notice is a link, never data

| Status | Date | Decider |
|---|---|---|
| Accepted (built through step 7; bridges and snapshots are phase 2) | 2026-10-05 | Ashutosh Sinha |

## Context
People who look at a trade or a gene variant in Drishti talk about it elsewhere (chat, email), losing the date and the data they were looking at, and
sometimes leaking a value the reader may not see. We added *share with a note* and comment threads anchored to the data, with an inbox and email.
Four questions had more than one reasonable answer: where the records live, how a notification is delivered, what a notification carries, and what
the records prove.

## Decision
1. **The records live in the identity database** (`drishti-identity`, package `collab`): SQLite by default, PostgreSQL in production, or a file store
   for one server (`drishti.collab.store`). They are users' words about data, not data, so they sit with users, roles and the audit trail, share its
   backup and transactions, and need no new store. One contract test runs against both implementations.
2. **Delivery is a transactional outbox.** A share or comment writes the record, the in-app notices, the email rows and the access-log row in one
   transaction; a dispatcher on any server claims rows under a lease, renders each mail for the recipient *at send time*, retries with backoff and keeps
   dead letters visible. A crash never loses a notice nor sends one for a rolled-back share. `Notifier` is an interface collected by Spring, so a new
   channel (a chat bridge, phase 2) is one more bean.
3. **A notice is a link, not data.** A share stores the sender's note and a **pin** (business date, "known at", generation, source), never a copy of
   the document. Whoever opens it gets the view built for them, with their own roles and masks, at the pinned date. A value of a masked field typed
   into a note or comment is found at write time and recorded as spans, so every reader without `raw` sees `•••`; a quote (`{$.mtm}`) is rendered
   per reader. Email carries the kind, the id and the note as that recipient may read it, and nothing else (`link-only` per pack for sensitive ids).
4. **Edits are history, and history is chained.** A comment is never overwritten: each revision is a row, hashed with the one before it (SHA-256,
   per thread). Retract and hide change what readers see, not the record. Retention removes whole threads, never one held by a legal hold; a compliance
   export is a zip with a manifest of hashes; `verify` recomputes the chains. The chain is evidence of tampering, not prevention.

## Consequences
- The identity database now grows with discussion; retention (off by default) and its backup are an operations matter
  ([OPERATIONS.md](../../admin/OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export)).
- Mail already sent cannot be recalled: that is why it carries no values. A reader who loses a right sees the notice stop showing the title and
  excerpt at once, because every read renders for current rights.
- Replacing an email provider, adding a bridge or moving the records to PostgreSQL touches configuration or one bean, not the services.
- Considered and rejected: a separate collaboration service and store (a second thing to back up, with no gain at this size); storing a rendered
  snapshot with the share (it would freeze rights at send time and leak to later readers); an in-memory queue (loses mail on a crash).

Design and as-built notes: [COLLABORATION.md](../COLLABORATION.md). The whole path worked through on one trade:
[HOW_IT_FITS.md §3.10](../HOW_IT_FITS.md#310-share-and-discussion-end-to-end).
