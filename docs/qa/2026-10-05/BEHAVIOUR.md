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
# QA behaviour pass (B3), 2026-10-05: collaboration, About/Ask, time, failure modes

Tester: adversarial QA agent; product code untouched. HEAD = develop `fcb22ef6` plus the jar built from it (JDK 21).
Scratch server :18981 and console :17981 (:18971/:17971 were already taken by another tester, so I moved), security and sign-in on,
nine QUICKSTART packs, users `ann` (author), `vic` (viewer), `cora` (viewer + a `compl` role with the compliance power), the seed admin.
Fake in-process endpoints on :18982 (Ask, one Slack-format bridge, each switchable to ok / 500 / 40 s slow) and an SMTP sink on :18983.
Driven with Playwright and the console's own JSON routes. Page used: `/v/var/VAR-COMM` (market-risk, Ask opted in) and
`/v/counterparty/CP-NORTHBRIDGE` (has masked fields). All my processes were stopped by PID. Settings that I changed on the
scratch server only: `comments-per-minute`, `max-per-entity`, `snapshots.cache-ttl=1s`, `threads.edit-window=5s`.

## Findings

| ID | Sev | Screen / endpoint | Repro | Expected | Actual |
|---|---|---|---|---|---|
| B3-01 | High | Share picture: `GET /api/share/{id}/picture` (and the email attachment, drawn "when asked") | 1. `vic` (viewer, so the id is masked: his page title reads `•••`) shares `counterparty/CP-NORTHBRIDGE` to `cora` with `picture: true`. 2. Fetch the picture as vic: title `Counterparty`. 3. Give `cora` a role with `raw`; wait out the 1-minute principals cache (and `snapshots.cache-ttl`, default 10 m). 4. Fetch the picture as vic again. | A picture never shows the sender more than the sender may see, nor more than the audience at send time. | The PNG is redrawn from the pin for the audience **as it is now**: the title now reads `CP-NORTHBRIDGE` (hash changed). vic, who has no `raw`, reads a masked value through the picture. Same path for the email attachment of a retried or late mail. A recipient promoted later, or a recipient list that changes in role, widens what every holder of the share can read. The design note ("drawn from the pin") accepts this; I think it is wrong for the sender and for compliance export of what was shown. Fix idea: take the *minimum* of (audience at send, audience now, the viewer asking). <br>**Status:** **Fixed.** The share keeps a frozen rights profile (roles of recipients and sender at send time); a redraw binds that, the recipients now and whoever asks, so it only narrows. Test `SnapshotApiTest.aPictureNeverUnmasksLater...`. |
| B3-02 | Medium | Discussion > "(edited)" revision history | Alt+N on a thread with an edited comment, click "(edited)". | Every time in the same zone and label as the comment above it (`2026-10-05 03:25 New York`). | The comment's own times read `... New York`; the revision rows read `2026-10-05 07:24 UTC`. The API rows use `at` not `...At`, so `localise()` skips them. Same page, two zones. |
| B3-03 | Medium | Share picture watermark and footer | Open any share picture. | Same zone and label as the rest of the UI (`New York`). | `Shared by Ann with 1 recipient on 2026-10-05 07:40 UTC` and the diagonal watermark are UTC; every other screen is New York. |
| B3-04 | Medium | Admin > Collaboration, Bridges; outbox | Set the bridge endpoint to answer 500; post a comment; open Admin > Collaboration; wait 90 s. Server log shows `bridge 18 ... tried again (attempt 2)`. | The administrator can see a delivery is failing (attempt count, last error, next try) and, in the end, the dead letters, with a retry button (the doc lists `GET /admin/collab/outbox`, `POST .../retry`). | The bridge row says `status: ok` and `Queue: pending 1`, nothing else. No attempts, no error, no next time, no dead-letter list, no retry or mail-outbox view anywhere in the console (no console route for `outbox`, `retry` or `mail-test`). With the default backoff (30 s doubling to 1 h, 8 attempts) a dead letter appears only hours later, and then only as a count. SMTP down is invisible the same way: with the sink killed, a share with `email: true` answers `201`, no warning, no admin trace. |
| B3-05 | Low | Admin bridge Test button, slow endpoint | Bridge endpoint sleeps 40 s; click Test (`POST /admin/collab/api/bridges/fakeb/test`). | `DRS-7013` after the bridge `timeout` (10 s), naming the bridge. | After 5.0 s the console's own client gives up: `DRS-1004 the server did not answer in time ... raise the server's drishti.sources.fetch-timeout`. Wrong cause and wrong setting named; the bridge timeout (10 s) is longer than the console's (5 s) so `DRS-7013` can never be shown for a slow endpoint. |
| B3-06 | Low | Share dialog and Discussion error line | Send more than 5 shares a minute (`shares-per-minute`), or send with an empty note. | `DRS-7003: too many shares; try again in 35 s`. | The code is printed twice: `DRS-7003: DRS-7003 too many shares; try again in 35 s`; `DRS-7011: DRS-7011 write a note: ...`. The console prefixes a code the detail already starts with. |
| B3-07 | Low | `POST /api/share` 429 through the console | As B3-06, read response headers. | `Retry-After` (COLLABORATION.md says it is set). | The JSON body carries the seconds; the header is absent through the console (`Retry-After: None`). Scripts and proxies cannot back off properly. |
| B3-08 | Low | Edit window | Server `edit-window=5s`. Post a comment, keep the drawer open 6 s, press Edit and save (or `PATCH /api/comment/{id}`). | Edit control hidden when the window closes, and a plain duration in the message. | The Edit control stays until the drawer is reloaded; the server then answers `DRS-7008 the edit window (PT5S) has passed` (raw ISO duration; with the default it will say `PT15M`). Retract after the window is allowed, as the message says. |
| B3-09 | Low | Discussion compose on a phone (390 px) | Open any view at 390 px width, Alt+N (or the Discussion tab). | The "About" select and the comment box stay inside the sheet with the 14 px gutter. | `#discBody` and `.disc-box` run L14 to R412 in a 390 px viewport: the right edge is clipped by 22 px (no page scroll bar, so it is silent). Screenshot: scratchpad `b3/phone-discussion.png`. |
| B3-10 | Low | Setting "Clock time zone" (Account) | Set `Asia/Kolkata`, save; open Discussion, the inbox, a share page. | The user's chosen clock zone (or the docs say it does not apply). | Collaboration times always read `... New York`: `local_zone()` uses the business-date zone, then the console-wide `CLOCK_TZ`, never the user's `clockZone`. Consistent (and labelled), but the setting's name implies otherwise; USER_GUIDE says only that the clock shows the zone. The label is a city name (`New York`), not an abbreviation, so it does not say EDT or EST; across the 1 November DST end the same label covers two offsets. |
| B3-11 | Low | Email digest window | Give `vic` an address; `ann` sends one share with `email: true`; watch the SMTP sink. | The sender is told when the mail will go, or a single notice goes at once. | The single mail arrives exactly 2 min after the share (`email.coalesce-window`). The dialog says only "Sent to 1 person." and a single notice is delayed as if it were part of a digest. Sizing: constant 2 m, no per-user variance seen. |
| B3-12 | Low | Compliance export through the console | 8 300 comments of about 4 000 characters (one thread, 33 MB raw) made as `ann`; as `cora`: `POST /admin/collab/api/exports` then `GET .../download`. | Streams. | Works: 2.0 s to build, 3.1 s to download 19.8 MB, console RSS 75 to 95 MB (about 1 x the zip is held, `raw=True` in memory, once). Scales linearly, so a 1 GB export needs about 1 GB of console heap; no size cap or streaming. A second download answers `400 DRS-5001 ... downloaded once` (a code for a bad request, not a gone/consumed resource), so an interrupted download means a new export. |
| B3-13 | Low | Admin > Collaboration, hidden comments | Hide a comment (`POST /api/comment/{id}/hide`), then use the thread search. | A way to list threads with hidden comments. | Confirmed gap (known): search rows carry only `comments: n`, no hidden count or flag; the admin must open each thread and use the "Only hidden comments" filter. In a thread of 8 300 comments that is paging. |
| B3-14 | Info | Compliance role on a share it was not sent | `cora` (compliance only) `POST /api/share/{id}/replies`. | The doc says compliance reads but cannot write. | Correct behaviour, but answered `404 DRS-7001 no share 'sh_...'`: the share exists and the same user reads it (200). The code says "does not exist". Not harmful (the page hides the reply box). |
| B3-15 | Info | Ask rate limit | `per-user-per-minute: 3`; ask ok, ask while model 500, ask while model slow, then ask. | Failed calls do not use the allowance, or the docs say they do. | The 4th call is `429 DRS-4009 try again in 56 s`: the two failures and the timeout each used one of the three. Defensible; undocumented. |
| B3-16 | Info | Discussion compose, `@` and `{` | Alt+N, type `hi @vi`, Down, Enter. | As documented. | Works (listbox, `aria-activedescendant`, quote becomes `{$.es975}`), but the textarea has no `aria-label`; its accessible name is its placeholder only. |
| B3-17 | Info | Setup | Start the documented scratch ports. | n/a | Collision note, not a product fault: :18970 to :18973 were already used by another tester's processes, so I used 18981-18983 / 17981. |

## Tested and held

- **Keyboard**: `?` opens and closes the drawer, `F1` opens it on About with the heading focused, `Esc` closes it and puts focus back where it was, `Alt+N` lands in the Discussion textarea, `Alt+S` focuses the "To" box of the share dialog, `Alt+I` goes to the inbox. The share dialog traps Tab (40 of 40 Tabs stayed inside), `Esc` closes it and returns focus to the Share button, also after clicking inside the dialog. Panel `?` popover closes on `Esc`. The drawer is `aria-modal=false` on desktop and traps Tab as a bottom sheet on phone (by design).
- **Phone 390 px**: no page-level sideways scroll on the view, About drawer, share dialog, inbox and Admin > Collaboration (only B3-09's clipped box).
- **Themes**: terminal, parchment, blue, crimson-dark; no real contrast failure in the drawer, Discussion, share dialog or inbox (the only hits are the gradient accent buttons, tool false positives as in the 2026-10-03 pass).
- **Live updates**: with `vic`'s Discussion open, `ann`'s new mention appeared in 0.6 s, the bell went 6 to 7 and a "1 new comment on this view" toast showed; no reload.
- **Ask failure modes**: model 500 gives `502 DRS-4008` (endpoint HTTP 500), a 40 s model gives `504 DRS-4008` after exactly the 4 s `timeout`, connection refused gives `502 DRS-4008`; the drawer's explanation stays complete and says "Ask is unavailable; the page explanation above is complete."; an off pack gives `404 DRS-4007`; the rate limit gives `429 DRS-4009` with seconds.
- **Bridge**: endpoint 500 retried (attempts at 30 s) and queued (`pending`), recovered after the endpoint came back; Test button: unknown name `404 DRS-7014`, 500 gives `502 DRS-7013` naming the status, connection refused gives `502 DRS-7013 (ConnectException)`.
- **Limits and codes**: shares 5 a minute then `DRS-7003`; comments 10 a minute then 429; empty body, 4 100 characters, empty hide reason all `DRS-7011` with a plain message; edit by another user `403 DRS-7008`; stale revision `409 DRS-7009`; lock by non-admin `403`, by admin works, comment on a locked thread `409 DRS-7007`, unlock works; follow, mute and unfollow work; retract hides the body from readers; hidden comment hides the body from `vic`; revisions show edits to readers.
- **Share**: post-to-thread tick is off by default (the configured default) and the label reads "Also start a discussion on the view"; share with the tick made a thread with the note; compliance reads a share it was not sent but cannot reply; a non-recipient gets 404; a disabled user's picture request gets 401.
- **Timestamps**: the About/Discussion/inbox/share page/admin threads all read `... New York` regardless of the browser zone (Asia/Kolkata and UTC tried), except B3-02 and B3-03.
- **Export**: end to end for 8 300 comments (B3-12), the zip downloads once.
- **Mail**: the share email is correct (subject, link to the share page), goes only to a user with an address; an address-less user shows `email: false` in the directory.

## Not tested (and why)

- "Open as it was" on a past generation, and the drawer refreshing on a new generation: the demo/sample sources never produced a second generation in this session.
- Outbox dead letters: reaching the 8th attempt needs hours at the default backoff; B3-04 is about the missing view, not the end state.
- A true DST-edge day: the console label has no offset (B3-10), so only reasoned about, not driven.
- Million-row or 1 GB exports (local-size rule).
