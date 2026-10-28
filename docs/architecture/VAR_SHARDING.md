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
# VaR sharding across Drishti*net

Status: **built in 2.9.6** (unreleased; the design agreed with the product owner on 2026-10-28, expanding
[VAR_GUIDE.md section 28](../guides/VAR_GUIDE.md#28-sharding-across-drishtinet-as-built-296)). **Off by default**: with no `sharding:` block in
the market-risk pack and no `drishti.var.sharding.enabled`, every date is loaded whole on each server exactly as in 2.9.5. The operator's notes
are in [NET.md](../admin/NET.md#var-shards-296); the settings in [CONFIGURATION.md](../admin/CONFIGURATION.md); the errors `DRS-5215` to
`DRS-5217` in [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md).

## Contents

1. [Why](#1-why) · 2. [The idea in one picture](#2-the-idea-in-one-picture) · 3. [The shard map](#3-the-shard-map) ·
4. [Owners: rendezvous hashing, no consensus protocol](#4-owners-rendezvous-hashing-no-consensus-protocol) ·
5. [Each owner loads its shards; every member puts the date together](#5-each-owner-loads-its-shards-every-member-puts-the-date-together) ·
6. [Queries: scatter-gather with a completeness check](#6-queries-scatter-gather-with-a-completeness-check) · 7. [Handover](#7-handover) ·
8. [Failure cases](#8-failure-cases) · 9. [Configuration](#9-configuration) · 10. [API and CLI](#10-api-and-cli) ·
11. [Fargate scale-in and drain](#11-fargate-scale-in-and-drain) · 12. [Tests and the equality evidence](#12-tests-and-the-equality-evidence) ·
13. [Decisions](#13-decisions) · 14. [Open items](#14-open-items)

## 1. Why

One date of a large bank is 509 scenario P&L values for every trade: 2 GB of float32 rows a million positions, 4 GB in float64. A server
holds whole dates (VAR_GUIDE.md sections 25 to 27); the rows of the dates in use live in its page cache. Sharding spreads a date's rows over
the Drishti*net members so each holds and reads from the lake only its part, while every member still answers every scope: a bank of many
millions of trades is served by several modest servers, and adding one moves only about a quarter (with four) of the rows.

## 2. The idea in one picture

```
              lake (Delta or Iceberg): pnl-vector, scenario-pv, ... (every member reads the same tables)
                │ each owner reads only its shards' key ranges (files skipped by their statistics, every row checked)
   ┌────────────┼──────────────────────────────┬───────────────────────────────┐
   ▼            ▼                              ▼                               ▼
 member A: S0, S2 (hot files)        member B: S0, S1                 member C: S1, S2         coordinator-only D: none
   │  every member puts the date together: its own shards + the others' skeletons (ids, columns, book aggregates)
   │  -> the same trades, positions, nodes and node aggregates (to the bit) as one server
   ▼
 a request for scope X on any member:
   node of a hierarchy  -> its aggregate, here (VAR BANK and the desks stay instant)
   anything else        -> the planner's rows split by shard; each shard's part to one ready owner
                           (POST /api/v1/var/partial, signed, one hop, as the caller), partial float64 vectors back,
                           exactly one per shard, added in shard order; VaR and ES taken once
```

## 3. The shard map

`ShardMap` (`drishti-compute`, `com.ash.drishti.compute.shard`): per business date, the rows counted per value of the shard key (VaR:
`LEGAL_ENTITY`, then `DESK`), read as columns with no vector (`SourceRouter.columns`), both trade kinds (`pnl-vector`, `scenario-pv`)
together; walked in key order, a shard closed when the next key would take it over `target-shard-mb` (a row's size is the measure's:
VaR 4 × scenarios bytes, its float32 hot row). A key value (a desk) is never split: a desk larger than the target is a shard of its own.
Shard *i* holds every key from its first counted key up to (excluded) the next shard's first; shard 0 also everything before, the last
everything after, so the ranges cover the whole key space and every row has exactly one shard.

The map is a pure function of the counts: every member that reads the same data version computes the same map. Its `digest` names the
cut, not the data (the date, the key, the target, each shard's first and last key): an intraday batch that adds rows without moving a cut
keeps the map. A source that cannot answer the key as columns gives a map of one shard (the whole date, with a `note`), owned by
`replicas` members: correct, only not spread.

## 4. Owners: rendezvous hashing, no consensus protocol

`ShardOwners`: a shard's owners are the top `replicas` members by rendezvous weight (the first 60 bits of SHA-256 of `node|key`, the key
the shard's range `LE-A/DESK-X..LE-B/DESK-Y`, so the same desks keep the same owners from one day to the next while the cut does not move)
over the members that may own shards; a second replica goes to another zone when the members carry zones (`zone-tag`); a shard pinned to a
member (`pins`, tests) is owned by it first.

**The members that may own shards** (`ShardMembership`, `com.ash.drishti.server.shard`): this server and the Drishti*net members alive and
routable whose capability digest says VaR sharding is on and they own shards (not `coordinator-only`). The view moves to the members
eligible now only once they have been the same for `stable-seconds` (until the membership has first been stable that long, the members
eligible now); a draining member leaves the view at once.

**Why no Raft or ZooKeeper.** Ownership is a pure function of shared inputs (the shard map, which every member computes from the same data,
and the membership, which gossip converges on in seconds). Two members that briefly disagree (one has heard of a join, the other not yet)
cannot produce a wrong number: a shard is served only by a member that *holds* it at the data version the asking member put the date
together from (section 6), so a disagreement costs at most a refused call and a try on the next member, never a partial or mixed answer.
A consensus protocol would add a quorum to keep alive and a leader to elect for no gain in correctness; the readiness announcements below
are what ordering a handover needs.

## 5. Each owner loads its shards; every member puts the date together

**A shard book** (`VarShardBooks`, `VarLoader.loadShard`): an owner reads the date's inputs for the rows of the shard's key range only:
`SourcePlugin.vectors(kind, column, texts, asOf, KeyRange, sink)` (2.9.6, `drishti-api`) lets a connector skip the files whose statistics
show none of the range's keys (Delta: the add action's `minValues`, `maxValues` and `nullCount` of the first key column; Iceberg: the data
file's lower and upper bounds and null count; a statistic not stated, or a string of 32 characters or more in Delta, is not used), and the
loader checks every row again. The rows go through the same intake as a whole date's and are written as an ordinary hot file
(`<hot>/<date>-shard-<i>/inputs-<digest>.vhot`, the same format, header flag `RANGE` (4) and a `RANG` section: the map, the shard, its range
and `data`, the digest of the inputs it was built from). A reporting currency's shard book is built from the USD one
(`<date>-<CCY>-shard-<i>/`, each trade converted as one server converts it). A shard book is the morning run: the day's updates are applied
by each member on the date it puts together.

**The skeleton**: the hot file without its matrices (`ShardSkeleton`: `META`, `SCEN`, `TIDS`, `TCOL`, `BOOK`, `AKEY`, `AGGS`, `CTOT`,
`CELL`, `RANG`, each with its CRC32C; flag `SKELETON` (8)), sent by an owner (`GET /api/v1/var/shards/skeleton`) to the members that put
the date together without holding the shard: about 0.8 KB a trade against 2 KB of rows at 509 scenarios.

**Putting a date together** (`ShardAssembly`, `drishti-compute`): the shards' trades side by side in shard order are the whole date's
trades in the order one server sorts them (a shard is a range of that order), so the positions, books, cells and nodes are derived by the
same code as a whole date's (`Cells.layout`, `Cells.derive`, `VarBook.nodes`); every book's and cell's aggregate is the one its shard summed
(float64, from the lake's rows), and every node's is summed from them bottom-up exactly as `VarBook.Builder` does: **a node's vector is the
unsharded date's to the bit**, on every member. The per-trade-currency totals of the nodes are the shards' added. The rows stay where they
are (`ShardedVectors`: the shards held here read from their mapped files, the others' through their owners). The intake's records are added
up and the date's checks made again across shards (`VarShardIntake`: one batch id, the date's scenario set, one form and one grain per
source system). Then the day's intraday updates are read and applied as one server applies them (a trade's morning row read back from the
lake by its id, never from a shard). Memory: each member holds the date's columns, ids and aggregates (about 0.8 KB a trade, plus
`M × N` doubles of aggregates: 20 MB for 5,000 nodes at 509 scenarios) and only its own shards' rows.

## 6. Queries: scatter-gather with a completeness check

A scope resolves on the coordinator (any member a request reaches; Drishti*net routes requests as before) exactly as on one server, as the
caller (their entitlements, the masks). A node of a hierarchy is its aggregate, here. For any other set, the planner (VAR_GUIDE.md section 25)
adds the aggregates it can and, on a sharded date, gathers the rows it needs into one set to add and one to take away (instead of summing
them run by run); each set is summed shard by shard (`ShardGather`, `com.ash.drishti.compute.shard`):

- a shard held here is summed here from its mapped rows;
- else its part (a bitmap of the shard's own ordinals) goes to one member, in this order: the shard's owners that announced it ready at the
  data version the date was put together from (first owner first), then any other member that announced it so (an old owner during a
  handover), then its owners not ready yet (they build it from the lake when the lake still holds that data). The call is
  `POST /api/v1/var/partial`, signed with the cluster key, one hop, with the caller's on-behalf-of token: the owner applies the caller's
  rights again (the rows asked must be in the caller's view of the shard, else `403`) and answers the float64 sum of the rows, labelled with
  the shard, the shard map's digest, the shard's data digest and the number of rows;
- an answer labelled otherwise is refused and the next member asked; a member that holds another data version refuses the call (`409
  DRS-5216`);
- **exactly one answer per shard asked**: the partials are added in shard order (the same bits whoever answered); a shard that no member
  answers fails the whole sum with `503 DRS-5215`, naming the shard, its range and what each member said. **Never a partial VaR.**

What-if (the basket's vector is the scope's plus the copied trades', each read through the same path), contributions (each part's vector
the same way: a position is one call to its shard's owner, cached for the date), backtests and history (each date sharded on its own), the
official records (computed on the coordinator from the same vectors) and the reporting currencies (their own shard books, or the
per-trade-currency totals of the USD book) all go through this path, so they give the same numbers as one server.

**The answer says so** (`provenance.shards`): `shards` (the date's), `heldHere`, `shardsAsked`, `answeredHere`, `answeredByOwners`,
`answeredByReplicas` (answered by a member that was not the shard's first owner), `nodes`.

## 7. Handover

Readiness travels with the capability digest: each member publishes, under the digest service `var-shards`, whether sharding is on there,
whether it owns shards, whether it is draining and, per date, the shard maps it holds and per shard the data digest it is ready to serve
(the first 16 characters). A change raises the digest's hash in gossip and the peers pull it within a round (the member also rebuilds its
digest at once when it built a shard), so "ready for shard X" is announced through gossip with no protocol of its own.

Every `rebalance-seconds` each member, once the membership has settled (`stable-seconds`):

1. builds the shards of the dates in use (put together here, or announced by any member) that it owns and holds no version of, from the
   lake, never from a peer, and announces them;
2. releases what `ShardHandover` allows: a shard it no longer owns once **every** owner has announced it ready for `grace-seconds`; an older
   data version of a shard it owns once the newer has been ready here for the grace period; a shard of a replaced cut once the grace period
   has passed. A shard book that the date put together here reads is released only after a replacement of the date, put together
   without it (its rows from the new owners), has been swapped in: the date is never dropped for it;
3. puts together again a date of its own whose remote shards' owners now announce newer data, or that nobody announces any more (after a few
   seconds, before the old holder's grace period ends), or whose cut changed.

**Putting a date together again never drops it (2.9.6 drill finding).** The replacement is put together beside the date loaded, on another
thread, and swapped in in one step once complete (`VarService.swap`: only when the date loaded is still the one the replacement started
from). Until then the date loaded answers: every node's aggregate (BANK, desks, legal entities), the shards held here, and the others' rows
at the data versions it was put together from. When the replacement cannot be completed (a shard with no live owner) the date loaded stays
and the replacement is tried again in a later round. Before 2.9.6 step 3 dropped the date and left the next request to put it together:
with both owners of a shard down that request needed the shard's skeleton, so **BANK** itself answered `503 DRS-5215` once a round had
passed (`VarShardClusterTest` under load in the drill; the test now runs those rounds and a forced replacement while both owners are down,
and BANK must still answer). Step 2 also dropped the date when it released an *older* version of a shard although the date read the newer one.

So there is never a moment when a shard in use has no ready holder: the old owner's release waits for the new owner's readiness, never the
other way round. An intraday batch changes no shard: the morning shard books stay, and each member applies the day's updates on the date
it put together.

**Convergence after a batch (2.9.6 drill finding).** A member never answers from data older than what it has read, and says so when it
cannot read the updates:

- The intraday layer is read by each member from the lake on its own data-load signal, never from a peer's skeleton, so peers still on the
  morning shard books cannot make a member's layer stale: the skeletons and shard books are the morning run on every member.
- When a member's signal is a full run (its earlier signal already applied every batch part, so the second finds none new and reloads, as
  one server does), it puts the date together again at the new data version; its owners build the shards at that version, and the other
  members put their dates together again once the old version is no longer announced (step 3 above).
- A reporting currency keeps its **morning run** per date and data version; each answer puts the USD date's intraday layer on it as the USD
  date holds it then (VAR_GUIDE.md section 8.4). Before this fix the official capture, which reads every currency as the morning run and
  runs after a full-run signal, could store its morning EUR book as the member's EUR book: the member then answered EUR without the day's
  updates (`intraday: null`) until the date was dropped. `VarShardClusterTest` saw it under load on one member
  (`BANK - TRD:T4 in EUR`: 5 positions, VaR 802 against 6 and 1,268); `VarIntradayApiTest` reproduces it on one server, every time.
- A date whose updates cannot be read is answered from its morning run with a warning on every answer, until the next signal reads them.
- How a client knows a member has the batches: `provenance.intraday.batches` (and `batchCount`, `lastAt`) on any answer, in each
  currency; the batches are listed in one order on every member (by each batch's newest `COMPUTED_AT`, then `BATCH_ID`), whatever order
  the signals applied them in.

## 8. Failure cases

| Case | What happens |
|---|---|
| an owner of a shard stops (or dies) | its replica answers the next request (the order of section 6); after `stable-seconds` a new owner builds the shard and the replica count is restored |
| every owner of a shard down | a scope that needs the shard's rows answers `503 DRS-5215`; a node's figures (aggregates on every member) still answer, and keep answering while the rebalancing rounds try to put the date together again (the date loaded is never dropped; a replacement that cannot read the shard is not swapped in); once the membership has been stable for `stable-seconds`, the shard's new owners build it from the lake and it answers again |
| a member holds another data version (a load landed between two members) | `409 DRS-5216`, the next member is asked; when every member refused as stale, this member puts the date together again at the new version and swaps it in once complete; until then the scopes that need those rows answer `409 DRS-5216` and the others answer from the date loaded, one data version throughout (a partial is asked at the data version the date was put together from, and refused at any other) |
| a load of the date's inputs (a full rerun) | the data-load signal reloads the date as before; the date is put together again at the new version, its shards built anew by their owners, the old ones released after the grace period |
| a book under two shards (one book under two legal entities or desks), a position under two shards with a hierarchy built from columns | the date is refused (`422 DRS-5217`, naming the book or position), never answered differently from one server; shard by legal entity only, correct the data, or switch sharding off |
| a skeleton or partial with another map or data version | refused and the next member asked |
| an unsigned call to `/partial` or `/shards/skeleton` | `401 DRS-5051` |
| Drishti*net off | the server is the only member: it owns every shard; the date is still cut and put together (each shard its own hot file) |

## 9. Configuration

What is sharded is the market-risk pack's (no block: off):

```yaml
# config/packs/market-risk/pack.yaml (or the pack's settings): off without it
sharding:
  enabled: true
  key: [LEGAL_ENTITY, DESK]          # ranges cut along this order; a desk is never split ([LEGAL_ENTITY] also allowed)
  kinds: [scenario-pv, pnl-vector]    # the trades' scenario P&L; every other kind (hierarchies, scenario sets, FX) read whole by each member
  target-shard-mb: 2048
  replicas: 2
```

The server's `drishti.var.sharding.*` (application.yaml): the same five keys, each winning over the block when set (a test, a site that does
not edit its pack; a value that is not valid refuses the start), and the server's own:

| Key | Default | Meaning |
|---|---|---|
| `stable-seconds` | 30 | how long the members must stay the same before shards move to new owners |
| `grace-seconds` | 30 | how long a new owner must have been ready before the old one releases a shard |
| `coordinator-only` | false | this member owns no shard: it puts dates together and coordinates queries only |
| `zone-tag` | (none) | the member tag prefix naming its zone (`az`: a tag `az:us-east-1a`); a second owner goes to another zone |
| `pins` | (none) | a shard (`S3`, or its range) pinned to a member id, first owner whatever the hashing; the same on every member (tests) |
| `drain` | false | start drained (section 11) |
| `call-timeout-seconds` | 30 | a partial's time on an owner before the next member is asked |
| `skeleton-timeout-seconds` | 600 | an owner's time to build and send a shard's skeleton |
| `rebalance-seconds` | 2 | how often a member looks at the members and its shards |

Sharding needs the tiered memory (`drishti.var.tier.mode: mapped`, the default).

## 10. API and CLI

| Endpoint | Who | What |
|---|---|---|
| `GET /api/v1/var/shards?asOf=` | anyone who may open `pnl-vector` | the settings, the members (owner, in the view, draining, zone, what each announces ready), the shard books held here, and the date's map with each shard's range, rows, owners and the members ready for it |
| `POST /api/v1/var/shards/drain {on}` | administrators | drain this member (`on: true`) or take it back |
| `POST /api/v1/var/partial` | members only (signed) | a shard's part of a set summed here |
| `GET /api/v1/var/shards/skeleton?date=&ccy=&shard=&map=&digest=` | members only (signed) | a shard's skeleton |

**API tokens** (`drishti.security.token-never` in application.yaml, TOKEN_SCOPES in API_GUIDE.md): both `POST`s are never open to a
personal API token. `/partial` reads data, but it is not a read `POST` for a token (`token-read-posts`): it is the members' own channel,
signed with the cluster key and made with the caller's on-behalf-of token (the owner applies the caller's rights again), like
`/compute/peer/**`; opening it to tokens would only add a door to that channel. `/shards/drain` is an administrator's action (the console,
the CLI, a task's stop hook signed in as an administrator), like `/var/reload`. `GET /api/v1/var/shards` reads as any `GET`.

`drishti.py var shards [--date D] [--json]` prints the same; `--drain` and `--undrain` (administrators). No new screen: a proposed Admin →
Health "Shards" panel is an open item (mock-ups first).

## 11. Fargate scale-in and drain

Before a task stops (a scale-in, a deploy), drain it: `drishti.py var shards --drain` (or `POST /api/v1/var/shards/drain`, from the task's
stop hook), then wait until `GET /api/v1/var/shards` says `"safeToStop": true` (it holds no shard: every one has been built by its new
owners, announced and held ready for the grace period). The draining member leaves the owners' view at once (it does not wait for
`stable-seconds`), keeps answering the requests it receives and serving its shards until each is handed over. Give the task's
`stopTimeout` at least the time to build its largest shard plus `grace-seconds`. A member started with `drain: true` takes no shard.

## 12. Tests and the equality evidence

| Test | What it shows |
|---|---|
| `ShardMapTest` | the map is the same whatever order the counts come in; contiguous ranges near the target, a desk never split, every key (counted or not) in one shard; the key must be a prefix of the measure's order |
| `ShardOwnersTest` | the same owners on every member; a fourth member takes 20 to 30 % of the first owners and of the replica slots (2,000 shards); a member leaving moves only its shards, each keeping its other replica; zones and pins |
| `ShardGatherTest` | every shard asked answers once, partials combine in shard order, a missing answer fails the whole gather; another measure's partials (a map by netting set) combine their own way |
| `ShardAssemblyTest` | the synthetic bank (1,800 trades) cut into six shards, three held, three through skeletons and an "owner": the same trades, positions and nodes, every node's aggregate to the bit, every scope's vector exactly equal (whole-unit rows), at most two partials per shard per sum; a damaged skeleton refused; a book under two shards refused |
| `VarShardHandoverTest` | the handover rule; a handover played second by second never leaves the shard without a ready holder; a partial labelled for another shard, map, data version or number of rows refused; the published state reads back |
| `VarShardingSettingsTest` | off by default; the server's keys and their defaults; settings that are not valid refuse the start |
| `VarShardSingleNodeTest` | Drishti*net off: the server owns both shards; the date put together equals the date loaded whole (aggregates to the bit, 40 random sets) |
| `VarShardClusterTest` | three real members and one reference server in one JVM (the next section) |

**The equality evidence** (`VarShardClusterTest`): the limit-letter fixture of `VarFormatEqualityTest` (two legal entities and desks, five
trades with letters, five scenario-PV trades in four currencies, 20 scenarios) cut into two shards with two owners each (S1 pinned to
the third member, so it takes a shard when it joins: with random node ids it would own neither about one run in nine). For 13 scopes (the
bank, desks, a book, both legal entities, the limit letters and a letter's desk node, a trade, a basket of trades in both shards, the bank
minus a trade, a desk plus a trade) in USD and EUR, the headline (VaR, ES, the tail, limit use, diversification), every child's
contribution, a page of the P&L path and the MGMT tree of every member equal the reference server's **to the cent** on Delta, with two
members, then with three, and on Iceberg; an intraday batch (an amendment, a cancel, a new trade, a scenario-PV update in EUR) applied live
equals the reference for ten scopes, once every server's answers list both batches in USD and EUR (the test polls the provenance as a
client would, no fixed sleep); the official record of the day has the same figures. While the third member joins and takes its
shards, 2 scopes are asked on two members every 300 ms: no request fails or differs. A partial with another data version is refused (409,
`DRS-5216`), an unsigned one too (401). With one owner of a shard stopped its replica answers; with both stopped a scope needing it answers
`503 DRS-5215` (the bank, a node, still answers its aggregate); 15 s later the member left owns the shard and answers the reference's numbers.

## 13. Decisions

- **Every member holds the date's skeleton** (ids, columns, positions, books, nodes and their aggregates), not only its shards: scope
  resolution, entitlements, the screens' tables and node figures need it, and it is a fraction of the rows (0.8 KB a trade against 2 KB of
  rows). Skeletons are fetched from owners, never rebuilt from the lake by non-owners (rows rejected for their vector would otherwise be
  unknown there).
- **Shard boundaries are LE/desk key ranges, not ordinal ranges**, so a member can build a shard from the lake without the rest of the date;
  a shard's ordinals follow from the order.
- **A shard book's identity is its data** (the digest of the inputs it was built from): partials and skeletons carry it and a mismatch is
  refused; the shard map's digest names only the cut, so an intraday batch neither moves shards nor rebuilds them.
- **Owners do not rebuild a shard when the version moves** (an intraday append): they build a newer version when asked for it (a member put
  the date together again, after a full rerun) or when they newly own the shard; old versions go after the grace period.
- **The coordinator plans and adds; owners only sum rows** (no planner on the owner), so the aggregates that make node figures exact are
  used once, on the coordinator; the summation order differs from one server's for scopes that are not a node (to the cent, in practice to
  well within it), while a node's figures are the same bits.
- **Both trade kinds are sharded together**, whatever `kinds` lists of them: a trade's P&L vector and scenario PVs are never split; every
  other kind is read whole by each member.
- **The intraday layer is held by every member** (the updates are few): updates go to the date each member put together, not only to the
  shard's owner; a trade's morning row is read back from the lake by its id.
- **A date that does not cut cleanly is refused** (`DRS-5217`) rather than answered differently; data that puts one book under two desks
  is rare and wrong upstream.
- **Readiness in the capability digest**, not a new gossip message: the digest is already pulled when its hash changes.
- **The checks across shards of a supplied position** (a position naming trades sent on their own) are made within each shard; a supplied
  position and its trades sit under one desk, so one shard.
- **Extension points (product owner, 2026-10-28): the machinery is measure-agnostic; VaR is its first plug-in.** Reusable, free of Spring and
  of the VaR engine: `com.ash.drishti.compute.shard` — `ShardMap` (the cut by key ranges; the key a prefix of the measure's row order, the
  row size the measure's), `ShardOwners` (rendezvous ownership with replicas, zones, pins), `ShardHandover` (the release rule),
  `ShardGather` (the coordinator's scatter-gather with its exactly-once check and the measure's `Combine`); and in the server,
  `com.ash.drishti.server.shard` — `ShardMembership` (the stable view, drain), `ShardPeers` (readiness in the digest under the measure's own
  service, signed one-hop calls as the caller) and `ShardedMeasure<P>`, what a measure brings: its name, its digest service, its key order,
  its bytes per row, its partial type `P` and how two combine. VaR's is `VarMeasure` (`LEGAL_ENTITY`, `DESK`; float32 rows; `double[]`
  partial P&L added element by element). A measure with another unit (exposure's netting sets, a map by netting set) brings its own
  `ShardedMeasure` and its own store and assembly; the VaR-specific parts are `VarShards`, `VarShardBooks`, `VarShardRows`,
  `ShardAssembly`, `ShardSkeleton` and `ShardedVectors`. No other measure is built.

## 14. Open items

- **Admin → Health "Shards" panel** (proposed, not built: mock-ups first): the members with their shards ready and held, each date's map
  with owners and readiness, a draining member's progress to "safe to stop", the last refusals (`DRS-5215`, `DRS-5216`). Until then
  `GET /api/v1/var/shards` and `drishti.py var shards`.
- Batching single-row reads: a contribution table's positions and an intraday batch's morning rows of shards held elsewhere are one call
  each (cached per position); a batched call would cut the calls for a table of thousands of parts.
- The 2.3 M benchmark sharded on real servers (2.9.6 scope) with the second builder's sorted lake loader: its per-file key ranges make the
  file skipping of section 5 exact (today it uses the first key column's statistics).
- Exposure and BI sharding (later releases) on the same machinery (section 13).
