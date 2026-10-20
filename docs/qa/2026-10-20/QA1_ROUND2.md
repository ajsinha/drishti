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
# QA-1 round 2: re-test of the Fix-1 fixes (2.6.5, 2026-10-20)

Tester QA-1. Scope: [QA_PLAN.md](QA_PLAN.md) section 1 (round 2) and section 8; the findings of [QA1_FINDINGS.md](QA1_FINDINGS.md) that
Fix-1 fixed (QA1-01 to QA1-06, QA1-08, QA1-09, QA1-11, QA1-12, QA1-13, QA1-18), each re-run with its own steps and then the area next to it.
QA1-07, QA1-10 and QA1-14 to QA1-17 are with Fix-2 and were not re-tested. Round 2 ran from 09:55 to 10:30 local time.

## The stack

| Part | What |
|---|---|
| Version | `develop` at `3aa2fc646` (Fix-1's fixes `cbab1d38e` … `22559bfec`), `./mvnw -q -T 4 -DskipTests install`, `drishti-server-2.6.5-exec.jar` |
| Node `qa1-n1` | server on 18610, `-Xmx4g`, packs core, trading, market-data; `drishti.py lake demo --only core,trading,market-data` (sixty business days, 2026-07-27 to 2026-10-20) |
| Node `qa1-n2` | server on 18611, `-Xmx4g`, the `trading` pack, `trade-murex` off; a 60-day synthetic lake (1,500 trades a day each for ENDUR and CALYPSO, 300 counterparties, 2026-07-23 to 2026-10-16), then 2026-10-19 and 2026-10-20 loaded during the test. `tools/bench/trades.py` itself fails on the current test data (QA1-201), so a scratch copy pointing at the current snapshot was used |
| Console | 17610, given `http://192.168.2.77:18610` only |
| Drishti*net | both nodes enabled, one cluster key, `advertise-url` on `192.168.2.77` (`tools/hostaddr.py`), n2 seeded with n1, gossip 1 s / 3 s / 10 s |
| Security | on; `drishti.security.redact` = the default list plus `MTM` |
| Users | `drishti-dev-admin` (admin); `rita` (role `qa-trader`: every kind, `raw: false`; packs core, trading, market-data) |

API calls use an HS256 token of the user, as the console mints them; console steps sign in on `http://192.168.2.77:17610`.

## Verdicts

| Finding | Sev. | Verdict | Evidence in short |
|---|---|---|---|
| QA1-01 history through a peer stale | P1 | **Fixed** | the peer's new day shows on n1 at the same poll as on n2 (twice) |
| QA1-02 peer down gives "no data", sticks | P1 | **Fixed** | `DRS-1014` / `DRS-5052` naming qa1-n2; rows as soon as it is back (frozen and stopped) |
| QA1-03 bad ranges on the command line | P2 | **Fixed** (the ten forms of the finding); two neighbours still escape, QA1-203 | every form gives DRS-4021/4022 with what to type, in `POST /command` and on the console |
| QA1-04 knownAt refused on Delta | P2 | **Fixed** | history as known at an instant on the demo lakes and through the peer |
| QA1-05 type-ahead merge in a cluster | P2 | **Fixed**; a neighbour on the node that does not hold the id, QA1-204 | ranges first, one row each, with both nodes up |
| QA1-06 type-ahead labels for a masked user | P2 | **Fixed** | rita's range rows keep their labels; `TRD 1000002 3` offers 30, 3M, 30 DIFF |
| QA1-08 plain words for market kinds, bare ids | P2 | **Fixed**; neighbours QA1-207 | the four documented phrases become the documented commands |
| QA1-09 loopback spellings in advertise-url | P2 | **Fixed** for every spelling of the finding (server and CLI); new: QA1-202 (port), QA1-206 (CLI and server disagree) | |
| QA1-11 DRS-1013 names the first day | P3 | **Fixed**; neighbour QA1-205 | "its days with data run from 2026-07-27 to 2026-10-20" |
| QA1-12 FROM after TO | P3 | **Fixed** | `DRS-4022 FROM 2026-10-15 is after TO 2026-10-01: put the earlier date first` everywhere |
| QA1-13 bad knownAt names the business date | P3 | **Fixed** | `DRS-4003 knownAt 'garbage' is not an instant …` |
| QA1-18 unknown kinds and fields | P3 | **Partly fixed**: the mnemonic, the plural and the field are named; a kind in another case or no kind at all is still `DRS-1001 no source holds …` (QA1-208) | |
| QA1-07, QA1-10, QA1-14 … QA1-17 | | not re-tested (Fix-2) | |

New findings: **no P1**, two P2 (QA1-201, QA1-202), six P3 (QA1-203 to QA1-208).

---

## Re-tests

### QA1-01 · Fixed

Steps of the finding: trade `10000005` is held only by qa1-n2. `GET n1 /history/trade/10000005?days=5` (last row 2026-10-16, then `cache: hit`
on the next calls); while polling both nodes every 2 s, `trades.py … --days 61 --start 2026-10-19 --end 2026-10-19` into n2's lake.

```text
10:15:12 18611 200 2026-10-16 5 hit
10:15:12 18610 200 2026-10-16 5 hit
10:15:14 18611 200 2026-10-19 5 miss      <- the load committed
10:15:15 18610 200 2026-10-19 5 miss      <- n1 at the same poll
10:15:17 … 10:16:14   both 2026-10-19, hit
```

n1's `dataVersion` now carries the peer's versions: `trade-endur=299@…|trade-calypso=2822@…|trade-murex=4307@…|net:qa1-n2{trade-calypso=59@…,
trade-endur=59@…}`. **Second run:** trade `10000007` with `days=5&diff=1` and `range=1M` both cached on n1 (`hit`), then 2026-10-20 loaded
into n2: the next call on n1 answers 2026-10-20 (`miss`) for both queries, and the console shows the new day
([img/qa1r2-peer-history.png](img/qa1r2-peer-history.png): *2026-10-14 → 2026-10-20*).

### QA1-02 · Fixed

Steps of the finding with `kill -STOP` on qa1-n2, then `kill -CONT` and 25 s:

```text
10:17:02 504 10027ms DRS-1014 the node qa1-n2 did not answer the history of trade/10000901 within 10 s; try again in a moment
10:17:02 502   22ms  DRS-5052 no node that answered holds the history of trade/10000902, and the nodes that hold trade cannot be reached now: qa1-n2 (suspect); try again in a moment
10:17:08 502   36ms  (the same for 10000902 again and for 10000903: nothing cached)
         kill -CONT, 25 s
10:17:34 200   29ms  10000902: 10 rows to 2026-10-20 (miss); 10000903 and 10000901 the same
```

**Second run** with `kill -TERM` and a restart of qa1-n2: `DRS-5052 … qa1-n2 (left)` for 10000910 and 10000911; after the restart both
answer 10 rows. Never `DRS-1013`. While the peer was away, a trade n1 holds itself (`1000015`) answered at once, and its type-ahead still
offered the ranges.

### QA1-03 · Fixed

The table of the finding, through `POST /command` (n1 and n2) and the console's `/go` (signed in as drishti-dev-admin, twice):

| Typed | Now |
|---|---|
| `TRD 1000001 0` | `DRS-4022 a history of 0 days is empty: ask for 1 or more (TRD 1000001 30)` |
| `-5`, `1.5M`, `3X`, `0M`, `0D`, `+5` | `DRS-4022 '<x>' is not a range: use a number of days (30), a span (30D, 2W, 3M, 1Y), YTD, MTD, QTD or FROM yyyy-mm-dd [TO yyyy-mm-dd], then DIFF for the changes only` |
| `99999` | `DRS-4021 366 business days at most (asked 99999): ask for fewer, or narrow it with TRD 1000001 FROM yyyy-mm-dd TO yyyy-mm-dd` |
| `DIFF DIFF`, `30 DIFF DIFF` | `DRS-4022 DIFF is said once, after the range: TRD 1000001 30 DIFF` |
| `DIFF` | `DRS-4022 DIFF follows a range: TRD 1000001 30 DIFF` |
| `FROM 2026-02-30`, `FROM yesterday`, `FROM 2026-10-01 TO yesterday` | `DRS-4022 '<x>' is not a date: write it as yyyy-mm-dd, for example …` |
| `30 30`, `FROM a FROM b` | `DRS-4022 two ranges: '30' and '30'; give one, for example TRD 1000001 30` |

The console shows the message on `/t` ([img/qa1r2-bad-range.png](img/qa1r2-bad-range.png)), never today's view and never plain words that drop
the id. Also right: lower case (`trd 10000005 -5`), with `<GO>`, a bare id (`MX-20000001 -5`), market and counterparty kinds (`EQ EQ-CSCA 0M`,
`CPTY CP-ALDERSHOT -1`), and a trade held only on the peer (`TRD 10000005 -5` on n1). A trade that does not exist (`TRD 99999999 -5`) still
opens the empty pick list, which is fair. What still escapes: QA1-203.

### QA1-04 · Fixed

On n1's demo lakes (written 13:58 to 14:13 UTC): `GET /history/trade/1000015?days=5&knownAt=2026-10-20T14:15:00Z` → 200, 5 rows, window
2026-10-14 … 2026-10-20, `knownAt` echoed; the same for `counterparty/CP-ALDERSHOT?days=3`, `equity/EQ-CSCA?range=1M` and with `diff=1`.
Through the peer: trade `10000005` as known at 14:15:30Z (before 2026-10-20 was loaded on n2 at 14:16:35Z) ends on 2026-10-19; without
`knownAt` it ends on 2026-10-20. On n2 alone, 10000005 as known at 14:00:30Z (mid-load) ends on 2026-07-28, as known now on 2026-08-04.
CLI: `drishti.py history trade 1000001 --known-at 2026-10-20T14:15:00Z --days 3` prints the three days, exit 0. Neighbour: QA1-205.

### QA1-05 · Fixed

`GET /command/suggest?q=TRD 1000002 ` on n1 with both nodes up: `30`, `3M`, `YTD`, `30 DIFF`, then `1000002`, `10000020`, `10000021`, …
(the peer's). The same for `TRD 1000015 `, `TRD CLY-3000157 `, `CPTY CP-HARBORPT `, `EQ EQ-CSCA ` and the peer-held `TRD 10000005 `; also
asked on n2. Twice. Neighbour: QA1-204.

### QA1-06 · Fixed

As rita (no `raw`), the same six prefixes: the four range rows read `30 · the last 30 days with data`, `3M · the last 3 months`, `YTD · since
1 January`, `30 DIFF · …`; the entity row follows with its own title. `TRD 1000002 3` offers `30`, `3M`, `30 DIFF`; `TRD 1000002 Y` offers
`YTD`; `TRD 1000002 30 ` offers `30 DIFF` (the same as the admin). Asked on n1 and on n2, twice.

### QA1-08 · Fixed

`GET /phrase?text=…` and the console's `/s?words=…` (which now redirects to `/go`):

| Typed | Becomes |
|---|---|
| `equity EQ-CSCA last 3 months` | `EQ EQ-CSCA 3M` |
| `fx audusd since 2026-10-01` | `FX FX-AUDUSD FROM 2026-10-01` |
| `BOOK-RATES-3 last week` | `BOOK BOOK-RATES-3 FROM 2026-10-12 TO 2026-10-16` |
| `1000015 30` | `TRD 1000015 30` |

Also right: `EQ-CSCA 3M`, `eq-csca last 3 months`, `equity csca last 3 months`, `1000015 3M DIFF`, `1000015 changes last week` (… DIFF),
`CP-ALDERSHOT last month`, a trade held only on the peer (`10000005 30`, `10000005 last week`), and `trades in BOOK-RATES-3 since 2026-01-01`
stays a search. Neighbours: QA1-207.

### QA1-09 · Fixed (with two new findings next to it)

Server (`POST /net/file/check?id=node.yaml` on n2) and CLI (`DRISHTI_NET_DIR=<copy> drishti.py net set node.advertise-url <url>`) refuse
every spelling of the finding and more, each with the reason: `oryxpro`, `ORYXPRO`, `oryxpro.` (*oryxpro is 127.0.1.1 here*), `0`,
`localhost.`, `LOCALHOST`, `ip6-localhost`, `ip6-loopback`, `2130706433`, `0x7f000001`, `0x7f.1`, `127.1`, `017700000001`, `127.0.0.01`,
`0177.0.0.1`, `172.17.0.1` (*this machine's docker0 bridge*), `172.18.0.1` (*br-54cb8bb5248d*), `169.254.1.1` (*link-local*),
`127.0.0.1.nip.io`, `localtest.me`, `0.0.0.0.`, `user@127.0.0.1`, `192.168.2.77@127.0.0.1`, `127.0.0.1#@192.168.2.77`, a leading space; the
server also `[::1]`, `[::]`, `[::ffff:127.0.0.1]`, `[0:0:0:0:0:0:0:1]`, `[fe80::1]`. A `PUT` of node.yaml with `oryxpro`, `0` or `0x7f000001`
is `409 DRS-5055 node.yaml was not written: …`. `http://192.168.2.77:18611` and `http://10.0.0.1:18611` are accepted. New: QA1-202,
QA1-206.

### QA1-11 · Fixed

`GET /history/trade/1000015?from=2026-01-01&to=2026-03-31` → `DRS-1013 trade/1000015 has no data from 2026-01-01 to 2026-03-31; its days with
data run from 2026-07-27 to 2026-10-20`; `equity/EQ-CSCA?from=2026-01-01&to=2026-06-30` the same; on n2 for 10000005 with three windows.
Neighbour: QA1-205.

### QA1-12 · Fixed

`TRD 1000001 FROM 2026-10-15 TO 2026-10-01`: `POST /command`, the endpoint and the console all say `DRS-4022 FROM 2026-10-15 is after TO
2026-10-01: put the earlier date first`; the CLI `--from 2026-10-15 --to 2026-10-01` the same (exit 1). The console no longer swaps the dates.

### QA1-13 · Fixed

`knownAt=garbage`, `2026-10-20` (a date), `2026-10-20T14:00:30` (no zone), `1700000000000`, `2026-13-40T99:00:00Z` → `400 DRS-4003 knownAt
'<x>' is not an instant: use ISO-8601 with a time and a zone (2026-10-19T18:00:00Z)`; the CLI `--known-at garbage` the same. An empty
`knownAt=` reads as latest; a future instant reads as latest.

### QA1-18 · Partly fixed

API and CLI: `history TRD 1000001` → `DRS-4004 TRD is a mnemonic: use the kind trade (history trade <id>)`; `history trades 1000001` →
`DRS-4004 unknown kind trades: did you mean trade?`; `fields=TRADE_STATUS,NOPE` and `fields=NOPE` → `DRS-4004 no trade has a field NOPE on these
days`; `fields=TRADER_ID` shows the column. Still `DRS-1001`: QA1-208.

---

## New findings

### QA1-201 · P2 · Tools: `tools/bench/trades.py` fails on the current test data; stale snapshot names in tools and docs

**Steps**
1. `drishti-console/.venv/bin/python tools/bench/trades.py --lake <scratch>/x --tmp <scratch>/y --days 1 --min 10 --max 10` (the command of
   QA_PLAN section 2, scaled down).

**Expected:** the synthetic lake is written (QA_PLAN section 2 names this tool for the 60-day lake).
**Actual:** `FileNotFoundError: … data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz`. The sixty-day test data (`546aada4b`, today 09:00)
keeps only `snapshot-2026-07-08`; `trades.py` line 167 still opens `snapshot-2026-09-22`. `--dry-run` passes because it never opens the
file. The same stale name is in the examples of `tools/drishti.py` (line 1046, `pack make`), `tools/packmake.py` line 18, `tools/authoring.py`
line 40, `data/testdata/README.md` lines 244 and 249, `docs/guides/IDE_GUIDE.md` line 258 and `docs/guides/TOOLS.md` lines 145, 164, 220:
copied as written, each fails the same way.
**Reproduced:** twice (the 60-day run at 09:58, the small run at 10:27). Round 2 used a scratch copy with `snapshot-2026-07-08`.

### QA1-202 · P2 · Drishti*net: an advertise-url with port 0 or a port past 65535 is accepted; the member is "alive" and every read through it fails

**Steps**
1. Two nodes up. On n2, `GET /api/v1/net/file?id=node.yaml`, then `PUT` it (If-Match) with `advertise-url: http://192.168.2.77:0`.
2. `GET n1 /api/v1/net/members`; `GET n1 /api/v1/history/trade/10000920?days=5` (a trade only n2 holds); `GET n1 /api/v1/views/trade/10000920`.
3. Restore `http://192.168.2.77:18611`.

**Expected:** refused like the loopback addresses (`DRS-5055`, *the port must be 1 to 65535*), as `http://192.168.2.77:18611` is the only
address other machines can call.
**Actual:** `200 {"problems":[]}`; n1 lists `qa1-n2 http://192.168.2.77:0 alive`; the history answers `502 DRS-1003 net:qa1-n2 failed reading
the history of trade/10000920: ConnectException`, the view `502 DRS-5052 the node qa1-n2 that holds trade failed reading trade/10000920`.
The check (`POST /net/file/check`) and `drishti.py net set node.advertise-url http://192.168.2.77:0` (and `:99999`) accept it too. The node
stays *alive* because gossip still reaches it, so nothing in the member table shows why every read fails.
**Reproduced:** twice (`:0` at 10:20, `:99999` at 10:22), restored to `:18611` each time (n1 sees it again at once).

### QA1-203 · P3 · Command line: a few range forms still escape DRS-4022

| Typed (`POST /command` and the console) | Now | Expected |
|---|---|---|
| `TRD 1000001 30.` | today's view, the range dropped ([img/qa1r2-trailing-dot.png](img/qa1r2-trailing-dot.png)) | `DRS-4022 '30.' is not a range` |
| `TRD 1000001 FROM 2026-10-01 TO` | `DRS-4004 cannot read the condition: DRS-2101 unexpected '2026' at 7`; the console goes to plain words | `DRS-4022 TO needs a date` |
| `TRD 1000001 TO 2026-10-01` | `DRS-4004 … unexpected '2026' at 5` | `DRS-4022 TO follows FROM` |
| `TRD 1000001 DIFF 30` | `DRS-4004 no trade has a field 'DIFF'; its fields include …` | `DRS-4022 DIFF follows a range: TRD 1000001 30 DIFF` |
| `TRD 1000001 1e3` | `DRS-4004 unknown amount suffix 'e' in 1e (use k, m or bn)` | `DRS-4022 '1e3' is not a range` |
| `TRD 1000001 ０` (full-width digit), `TRD 1000001 ３0` | `400 DRS-5001 For input string: "０"` (a Java message; the console shows plain words) | `DRS-4022`, or read as 0 / 30 as `?days=０` already is (`DRS-4022 a history of 0 days is empty`) |
| `1000015 -5`, `1000015 since yesterday` (bare id) | plain words: *say what to look for: a kind such as …* | `DRS-4022`, as `MX-20000001 -5` gives |

**Reproduced:** twice (n2 at 10:13 through the API; n1 at 10:20 through the console's `/go`).

### QA1-204 · P3 · Type-ahead, cluster: on a node that does not hold the id, the exact id is crowded out by the node's own longer ids

**Steps:** ask n2 (which holds 10000010 … 10009999 but not 1000002) `GET /command/suggest?q=TRD 1000002` and `?q=TRD 1000002 `.
**Expected:** `1000002` (held by n1, an exact match) first, as n1 answers `[1000002, 10000020, …]`.
**Actual:** `TRD 1000002` → `[10000020 … 10000029]`, no `1000002`; `TRD 1000002 ` → the four ranges for `1000002`, then `10000020 …
10000025`, no entity row for `1000002`. Enter still opens the trade. A console given n2 shows a list without the trade the trader typed.
**Reproduced:** three ids (`1000002`, `1000015`, `1000001`), twice for `1000002`.

### QA1-205 · P3 · History "known at" before the data: DRS-1013 names the days known now

**Steps:** `GET /history/trade/1000015?days=5&knownAt=2026-10-20T13:00:00Z` (n1, whose lakes were written from 13:58 UTC).
**Expected:** a message that nothing was known at that instant (the view says `DRS-1001` for the same instant), or the days with data as known
then.
**Actual:** `404 DRS-1013 trade/1000015 has no data in the 367 business days to 2026-10-20; its days with data run from 2026-07-27 to
2026-10-20`: the hint names days that were not known at 13:00, so following it (`from=2026-07-27`) gives the same 404.
**Reproduced:** twice (n1 1000015 at 13:00Z and 13:30Z; n2 10000005 at `2026-10-20T14:00:30+02:00`).

### QA1-206 · P3 · Drishti*net: `drishti.py net set` and the server disagree on some advertise-urls

**Steps:** the same address through `POST /net/file/check` (server) and `DRISHTI_NET_DIR=<copy> drishti.py net set node.advertise-url <url>`.

| Address | Server | CLI |
|---|---|---|
| `http://%31%32%37.0.0.1:18611`, `http://127.0.0.1%2e:18611` | refused (*not an http or https address with a host*; *loopback*) | **written** |
| `http://192.168.2.77.:18611` (trailing dot) | refused (*not an http or https address with a host*) | **written** |
| `http://[2001:db8::5]:18611` (a global IPv6 address) | accepted | refused: *not an http or https address with a host* |
| `http://[::1]:18611`, `[fe80::1]` | refused as loopback / link-local | refused, but as *not an http or https address* |

So the CLI writes a node.yaml the server then refuses at start, and refuses an IPv6 address the server takes.
**Reproduced:** twice (two percent-encoded spellings; two IPv6 spellings).

### QA1-207 · P3 · Plain words: half of a time phrase is dropped silently

| Typed | Becomes | Expected |
|---|---|---|
| `trade 1000015 since 2026-10-01 until 2026-10-10` | `TRD 1000015 FROM 2026-10-01`, `until 2026-10-10` ignored | `… FROM 2026-10-01 TO 2026-10-10` (`from … to …` works) |
| `fx eurusd since 2026-10-15 until 2026-10-01` | `FX FX-EURUSD FROM 2026-10-15` | the dates in order, or DRS-4022 |
| `trades in BOOK-RATES-3 last week`, `trades in BOOK-RATES-1 last month` | the search, `last week` / `last month` ignored | the search on that window, or a word that the time phrase was left out |
| `fx audusd` (no range) | `FX` with `audusd` ignored | `FX FX-AUDUSD` (the id is read when a range follows) |

The page lists the ignored words, so nothing is hidden, but the answer is not what was asked. **Reproduced:** twice (`until` with trade and fx;
`last week` and `last month`).

### QA1-208 · P3 · History: a kind in another case, or no kind, is still "no source holds"

`GET /history/Trade/1000001?days=3`, `/history/TRADE/1000001`, `/history/nokind/1000001` and the CLI `drishti.py history Trade 1000001` →
`404 DRS-1001 no source holds Trade/1000001`. Expected (as for `TRD` and `trades` now): `DRS-4004 unknown kind Trade: did you mean trade?`
and `unknown kind nokind`. Reproduced twice (API on n2, CLI on n1).

## Not covered

- QA1-07, QA1-10, QA1-14 … QA1-17: with Fix-2.
- Phone width, themes and the keys on the touched pages (no console page of Fix-1 changed except `/go`).
- A third node; a peer whose clock is off (knownAt across nodes with skew).
