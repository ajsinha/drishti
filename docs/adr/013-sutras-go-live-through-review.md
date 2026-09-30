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
# ADR-013: Sutras go live through review

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
A Sutra decides what every user sees for a family of entities: which figures lead, which are hidden, how values are
formatted. Studio could write a Sutra straight to disk, and hot reload made it live for everyone at once. In a bank a
screen change of that reach needs a second pair of eyes and a record of who changed what, when and why.

## Decision
- **A save is a proposal.** With governance on (the default), saving in Studio validates the Sutra and records a
  proposal: the text, the author's note, and the live text it was written against. Nothing changes for users.
- **Approvers decide.** A role with `approve: true` (or an admin) approves (the Sutra is written and hot-reloaded)
  or rejects with a reason. Authors may withdraw their own.
- **Four eyes.** Nobody approves their own proposal (`drishti.governance.four-eyes`, on by default; it applies when
  security is on, since without it everyone is the same anonymous user).
- **No stale approvals.** Approval is refused when the live Sutra changed after the proposal was made: the reviewer's
  diff would no longer be what gets published.
- **A record.** Proposals are kept as JSON files (`drishti.governance.dir`) and every step is audited
  (`sutra-proposed`, `sutra-approved`, `sutra-rejected`, `sutra-withdrawn`); each Sutra has a history.
- Sutras committed to a pack or site directory through version control are unaffected: that review happens there.

## Consequences
- A change to a screen takes two people. Single-person authoring setups switch four-eyes off, or governance off
  (`DRISHTI_SUTRA_REVIEW=false`), which restores direct saving.
- Decisions are serialised per store, so two approvers cannot both decide one proposal.
