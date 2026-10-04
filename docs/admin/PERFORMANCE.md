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
# Performance

This document answers three questions:

1. **How fast is Drishti?** The [targets and measured numbers](#targets-and-measured-numbers).
2. **How do I measure it on my installation?** [Eight ways, from a single `curl` to JMH](#how-to-measure).
3. **What can I change when it is slow?** [The tuning knobs](#tuning-knobs), each with an example.

How the stores compare, and how each scales with the size of the book, is measured separately by the scale benchmark:
[SCALE_BENCHMARK.md](SCALE_BENCHMARK.md).

Every number below was measured, not estimated. Each row names the date, the machine and the command, so
anyone can check it again.

## Targets and measured numbers

| What | Target | Measured | Where the number comes from |
|---|---|---|---|
| Warm view build, server work | p99 < 50 ms (build gate) | 0.57–0.62 ms (`timings.total` of single views) | `timings` in the view JSON, below; the gate is `ViewPipelineTest` |
| Warm view over HTTP, p99 | < 50 ms | 3.0 ms (p50 1.7 ms) | the `curl` loop below, 2026-09-30, developer workstation (24 cores), local server |
| Live tick → frame built, p50 | — | 2.4 ms | `GET /api/v1/health/live` while streaming IRS-48213 through the console, 2026-09-30 |
| Live tick → frame built, p99 | < 40 ms | 11.2 ms | same |
| Browser top bar | — | `Live, p99 2 ms` | read from real Chrome over the DevTools protocol |
| Fan-out | — | 10,000 listeners on one topic all receive the latest of 50 ticks | `TopicHubTest` |
| Structured search over 750 trades | — | 7.2 ms (`elapsedMs`) | `GET /api/v1/search`, below |
| Pick list `TRD MX-200000` (99 of 99) | — | 1.2 ms (`elapsedMs`) | `GET /api/v1/search`, 2026-09-30; an id pattern reads only the ids it names |
| Pick list `TRD productType=Revolver` (6 of 750) | — | 5.9 ms (`elapsedMs`) | same; a field condition reads every trade |
| Command-line suggestions over HTTP | 30 ms budget per source | p50 5.2 ms, p99 8.0 ms (25 entries) | the `curl` loop in method 6, 2026-09-30, on a busy workstation |

### A book of a million trades a day

How the connectors achieve these figures is in [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md) and, for the same book in
Aerospike, [AEROSPIKE_CONNECTOR.md](../connectors/AEROSPIKE_CONNECTOR.md).

Measured 2026-10-01 on the developer workstation (24 cores, server heap 15.6 GB), the trading pack's lake laid out as
declared ([PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#large-kinds-the-lake-layout)), 1,000,000 trades a day over three business days (5.2 GB;
`tools/samplegen/bulk_trades.py --trades 1000000 --days 3`), times over HTTP:

| What | Time |
|---|---|
| Type-ahead `TRD CLY-40834` | 29 ms |
| Open a trade (first read of it / cached) | 137–157 ms / 48 ms |
| Open a trade on a past business date (that day's id map is read first) | 288 ms |
| Search over all 1,000,000 trades: `TRD where mtm < -50m order by mtm` | 134 ms (2,017 matches) |
| `TRD where currency = 'USD' and notional > 500m order by notional desc` | 322 ms (13,640 matches) |
| `TRD book=BOOK-RATES-3` | 123 ms (70,702 matches) |
| Pick list `TRD END-1100` | 41 ms |
| A search on another business date, the first time (its columns are read) / after | 6.6 s / 306 ms |
| Desk P&L of every desk from 1,000,000 trades / another desk | 327 ms / 48 ms |
| Impact (F8) of a netting set with 143,982 trades | 716–881 ms |
| Server heap in use | 2–3.5 GB |

Seven years of history (1,800 business days, 7,200 files, a 7.2 MB log with a checkpoint): the connector lists the
files in 84 ms (every `refresh-seconds`), and a trade on a 2019 date opens in 22 ms. Before the layout the same
book could not be served: every read loaded a whole day (gigabytes of documents) and timed out.

The first live implementation delayed every tick by a whole frame (p50 52 ms). Switching to a leading-edge
throttle (send at once after a quiet frame, coalesce inside a busy one) brought p50 to 2.4 ms.

### Every store, from 10,000 to 50,000 trades a day

The scale benchmark (`tools/bench/scale.sh`, [SCALE_BENCHMARK.md](SCALE_BENCHMARK.md)) loads the same book into each of
the eight stores at 10,000, 25,000 and 50,000 trades a day and asks the questions above over HTTP. Measured on
2026-10-01 at 50,000 trades a day (server `-Xmx2g`, containers capped at 2 GB), every search exact:

| What | Range across the eight stores |
|---|---|
| Type-ahead `TRD CLY-400` | 6.3–10.4 ms |
| Open a trade (first time) | 4.1–6.6 ms |
| `TRD where mtm < -50m order by mtm` | 3.6–7.9 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 7.7–10.6 ms |
| A search on another business date, first / again | 46–426 ms / 4.1–17.6 ms |
| Server live heap after a full GC | 114–174 MB |

Its linear fits carried to a million trades a day are extrapolations, labelled as such, and were checked against the
measurements above: store sizes within about 25%, searches mostly within a factor of two, impact not at all
([SCALE_BENCHMARK.md › How good is the straight line](SCALE_BENCHMARK.md#53-how-good-is-the-straight-line-checked-against-the-real-million)).

## How to measure

Start with method 1. It needs nothing but `curl` and tells you, for one view, where the time goes.

### 1. The `timings` of one view

Every view the server builds reports how long each stage took, in milliseconds:

```bash
curl -s http://localhost:18480/api/v1/views/trade/MX-20000001 \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["timings"]); print(d["provenance"]["layout"])'
```

You should see something like:

```text
{'fetch': 0.11, 'layout': 0.14, 'links': 0.16, 'bind': 0.21, 'total': 0.62}
Sutra irs-fixfloat v1 + inference
```

| Stage | What it covers | If it is the big one |
|---|---|---|
| `fetch` | Reading the entity from its source. | The source is slow. Check the source itself; see [runbooks/source-down.md](runbooks/source-down.md). A disk cache or a larger `cache-mb` helps lake and stream connectors. |
| `layout` | Finding the Sutra and merging it with inference. Cached by (Sutra version, kind, data shape), so normally a few microseconds. | High on every request: the layout cache is too small for the number of shapes, or Sutras are being edited (each edit is a new version). Raise `drishti.engine.layout-cache-size`. |
| `links` | Fetching linked entities (counterparty, curves) in parallel, up to `drishti.graph.link-budget`. | At most the link budget (40 ms). Links that miss it render as *pending*. |
| `bind` | Evaluating every panel's expressions, in parallel. | A Sutra with very large tables or heavy expressions. |
| `total` | All of the above. | — |

The same numbers appear in the console: open a view and look at the *How this view was built* panel.

### 2. A latency distribution with `curl`

Repeat the same request and sort the times. This measures what a client sees, HTTP included:

```bash
for i in $(seq 200); do
  curl -s -o /dev/null -w "%{time_total}\n" http://localhost:18480/api/v1/views/trade/MX-20000001
done | sort -n | awk '{a[NR]=$1} END {printf "n=%d p50=%.1f ms p99=%.1f ms max=%.1f ms\n",
  NR, a[int(NR*0.5)]*1000, a[int(NR*0.99)]*1000, a[NR]*1000}'
```

You should see something like (2026-09-30, developer workstation, server and client on one machine):

```text
n=200 p50=1.7 ms p99=3.0 ms max=3.8 ms
```

Numbers move with whatever else the machine is doing. The same loop on the same machine while two Maven builds
ran gave `n=200 p50=4.0 ms p99=8.3 ms max=12.5 ms`; the server-side `timings.total` stayed under 1 ms, so the
difference was the busy client and network stack, not view building. Compare like with like.

With security on, add `-H "Authorization: Bearer $TOKEN"` (see [API_GUIDE.md](../guides/API_GUIDE.md)). Run it from the
console's host to include the network path the console uses.

### 3. Server metrics (Prometheus)

The server publishes its own timers at `/actuator/prometheus`:

```bash
curl -s http://localhost:18480/actuator/prometheus | grep -E '^drishti_'
```

**With security on** (`DRISHTI_SECURITY_ENABLED=true`) only `/actuator/health` stays open; the rest of
`/actuator` answers `401` without a token. Send the scrape token the server was started with
(`DRISHTI_METRICS_TOKEN`), or an admin's token:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_METRICS_TOKEN" http://localhost:18480/actuator/prometheus | grep -E '^drishti_'
```

Prometheus does the same with `authorization: { type: Bearer, credentials: … }` in its scrape job (see
[TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md#monitoring-endpoints)). With security off, as below, no token is needed.

```text
drishti_live_frames_total 11680.0
drishti_live_latency_p99_milliseconds 1.917
drishti_live_streams 0.0
drishti_live_topics 0.0
drishti_view_seconds{quantile="0.5"} 0.0
drishti_view_seconds{quantile="0.99"} 0.0
drishti_view_seconds_count 119
drishti_view_seconds_max 0.017122086
drishti_view_seconds_sum 0.318121785
```

| Metric | Meaning |
|---|---|
| `drishti_view_seconds{quantile="0.5"/"0.99"}` | View build time percentiles over a short decaying window. They read `0.0` when no view was built recently. |
| `drishti_view_seconds_count`, `_sum`, `_max` | Views built, total time, slowest recent one. Average = `_sum / _count`. |
| `drishti_live_latency_p99_milliseconds` | Live tick → frame p99 over `drishti.live.window`. |
| `drishti_live_streams`, `drishti_live_topics`, `drishti_live_frames_total` | Open streams, entities with a source subscription, frames built. |
| `http_server_requests_seconds_*{uri="/api/v1/views/{kind}/{id}"}` | Spring's per-endpoint HTTP timing, with `status` and `outcome` tags. |
| `jvm_*`, `process_*` | Heap, GC pauses, threads, CPU. |

`deploy/grafana/drishti-dashboard.json` is a ready Grafana dashboard over these metrics (view p99, views per
second, live p99, streams and topics, frames per second). Import it in Grafana (Dashboards → Import) with a
Prometheus data source that scrapes `http://<server>:18480/actuator/prometheus` (with the scrape token when
security is on).

For one metric without Prometheus use the actuator's JSON form:

```bash
curl -s http://localhost:18480/actuator/metrics/drishti.view
```

```json
{"name":"drishti.view","description":"Time to build an entity view","baseUnit":"seconds",
 "measurements":[{"statistic":"COUNT","value":150.0},{"statistic":"TOTAL_TIME","value":0.430959793},{"statistic":"MAX","value":0.0920688}],"availableTags":[]}
```

### 4. Live latency

```bash
curl -s http://localhost:18480/api/v1/health/live
```

```json
{"streams":0,"topics":0,"frames":11652,"p50Ms":0.886,"p99Ms":1.917}
```

The percentiles cover only the last 30 seconds (`drishti.live.window`), so measure while something is
streaming. To generate load from the command line, open some streams in the background, measure, and stop them:

```bash
for i in $(seq 50); do curl -sN -o /dev/null http://localhost:18480/api/v1/views/trade/END-1000008/stream & done
sleep 10
curl -s http://localhost:18480/api/v1/health/live
kill $(jobs -p)
```

You should see `"streams":50,"topics":1`: fifty viewers of one entity share one topic and one source
subscription. Open fifty *different* live entities to load the source and frame builder harder. The meaning
of each field is in [LIVE.md](../architecture/LIVE.md#3-check-the-live-counters).

### 5. Search

A structured search reports how many entities it read and how long it took:

```bash
curl -s -G http://localhost:18480/api/v1/search --data-urlencode 'q=TRD where $.mtm > 1000000' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print({k: d[k] for k in ("scanned","matched","partial","elapsedMs")})'
```

```text
{'scanned': 750, 'matched': 163, 'partial': False, 'elapsedMs': 7.18}
```

`"partial": true` means the search stopped at `drishti.search.max-scan` entities or ran out of
`drishti.search.budget`, and the result says so to the user.

Pick lists go through the same endpoint. Compare an id pattern with a field condition:

```bash
for q in 'TRD MX-200000' 'TRD productType=Revolver' 'TRD'; do
  curl -s -G http://localhost:18480/api/v1/search --data-urlencode "q=$q" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["query"], {k: d[k] for k in ("scanned","matched","elapsedMs")})'
done
```

```text
TRD MX-200000 {'scanned': 99, 'matched': 99, 'elapsedMs': 1.2}
TRD productType=Revolver {'scanned': 750, 'matched': 6, 'elapsedMs': 5.88}
TRD {'scanned': 750, 'matched': 750, 'elapsedMs': 6.13}
```

An id pattern (`MX-200000`) reads only the entities whose ids it names (`scanned` 99); a field condition reads every
entity of the kind (`scanned` 750). On a large kind, put an id pattern in front of the condition
(`TRD MX-2* currency=usd`) when you can. The console adds `limit <your Search results setting>` (100 by
default), so only the first 100 rows travel to the browser; the table then pages them 25 at a time in the
browser, with no further requests.

### 6. Suggestions (type-ahead)

The command line asks for suggestions about 60 ms after each keystroke. Each source has 30 ms
(`drishti.commands.suggest-budget`) to answer; a slow source is left out of that answer rather than slowing it.
Measure what a user feels:

```bash
for i in $(seq 100); do
  curl -s -o /dev/null -w "%{time_total}\n" 'http://localhost:18480/api/v1/command/suggest?q=TRD%20T-10'
done | sort -n | awk '{a[NR]=$1} END {printf "n=%d p50=%.1f ms p99=%.1f ms max=%.1f ms\n",
  NR, a[int(NR*0.5)]*1000, a[int(NR*0.99)]*1000, a[NR]*1000}'
```

```text
n=100 p50=5.2 ms p99=8.0 ms max=10.3 ms
```

Each answer holds up to 25 entries (`drishti.commands.suggest-limit`, `DRISHTI_SUGGEST_LIMIT`). A larger number
costs a little more per keystroke for every user; a program may ask for up to 50 with `&limit=50`.

### 7. Micro-benchmarks (JMH)

`drishti-benchmarks/HotPathBenchmark` times the innermost hot paths against the IRS-48213 reference document
(`packs/finance/samples/trade/IRS-48213.json`). Run it from the repository root, because it reads that file
by a relative path:

```bash
./mvnw -q -pl drishti-benchmarks -am install -DskipTests
./mvnw -q -pl drishti-benchmarks dependency:build-classpath -Dmdep.outputFile="$PWD/cp.txt"
java -cp "drishti-benchmarks/target/classes:$(cat cp.txt)" org.openjdk.jmh.Main
```

The second command writes the benchmark's class path to `cp.txt` (an absolute path, so it lands in the
repository root whichever module Maven runs in). Add a name pattern to run one benchmark, for example `org.openjdk.jmh.Main evalSum`. Each benchmark does
3 one-second warm-up iterations and 5 measured ones in one fork, so the whole run takes about a minute.

| Benchmark | 2026-09-30, developer workstation | What it is |
|---|---|---|
| `evalTernary` | 0.011 µs | `$.direction == 'PAY_FIXED' ? … : …` (compiled Rachana-EL) |
| `evalSum` | 0.049 µs | `sum($.legs[0].cashflows, 'pv')` |
| `formatSigned` | 0.090 µs | `signed2` of −1,962,430.56 |
| `fingerprint` | 3.5 µs | shape fingerprint of the whole IRS document (cached per generation) |
| `inferColdLayout` | 12.9 µs | full inference for the IRS document (cached per shape) |

### 8. The build's performance gate

`ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99` (in `drishti-server`) builds the full IRS
view 300 times to warm up and then 2,000 times while timing: fetch, match, layout (cached), link fan-out on
virtual threads, parallel binding. The build fails if p99 ≥ 50 ms or if the layout cache hit rate is ≤ 99 %.
It runs with every `./mvnw verify`, so a change that makes views slow cannot be merged unnoticed.

## Why it is fast

- **Layouts are data-free and cached** by (Sutra version, kind, shape fingerprint). Inference and
  Sutra merging run once per shape.
- **Fingerprints are cached** by (entity, generation). A tick with a new generation costs one 3.5 µs hash.
- **Expressions are compiled once** into immutable closure trees, cached by source text and shared
  across threads.
- **I/O runs on virtual threads.** Every request runs on one (`spring.threads.virtual.enabled: true`).
  Linked entities are fetched in parallel under a 40 ms budget; late ones render as *pending*.
- **Panels bind in parallel** on a bounded pool sized to the cores.
- **Live views rebuild, then diff.** A tick re-binds the view with its cached layout and sends only the changed
  cells and panels; a busy entity costs at most one frame per 50 ms however fast it ticks ([LIVE.md](../architecture/LIVE.md)).
- **Responses are compressed** (`server.compression`, JSON and event streams).

## Tuning knobs

Set any of these in `application.local.yaml` next to the server jar, as an environment variable, or as a
`--key=value` argument (precedence and syntax: [CONFIGURATION.md](CONFIGURATION.md)). Change one at a time and
measure again with the methods above.

| Key | Default | Raise it when | Lower it when |
|---|---|---|---|
| `drishti.sources.fetch-timeout` | `2s` | A healthy but slow source makes views fail with `DRS-1004`. | You prefer a fast failure to a slow view. |
| `drishti.graph.link-budget` | `40ms` | Linked entities often show *pending* although their source is fine. Each step adds that much to a view's worst case. | Views must appear faster even if more links are pending. |
| `drishti.engine.layout-cache-size` | `10000` | `timings.layout` is high on warm views (many data shapes or many Sutra versions). | Memory is tight and you have few shapes. |
| `drishti.engine.fingerprint-cache-size` | `100000` | Many live entities tick at once (one entry per entity and generation). | Memory is tight. |
| `drishti.engine.bind-parallelism` | `0` (= cores) | — | The server shares its cores with other work; set a fixed number. |
| `drishti.live.frame` | `50ms` | CPU is high under many fast-ticking entities; trade freshness for throughput. | You need fresher frames and have CPU to spare. |
| `drishti.live.max-streams` | `20000` | You run a big server with many viewers. | You must protect a small server from a burst of tabs. |
| `drishti.commands.suggest-budget` | `30ms` | Type-ahead often misses entities from a slow source. | Typing feels sluggish. |
| `drishti.commands.suggest-limit` (`DRISHTI_SUGGEST_LIMIT`) | `25` | Users want a longer dropdown. | Many users type at once on a small server. |
| `drishti.search.max-scan` | `20000` | Searches report `partial` because a kind has more entities. | Searches over huge kinds load the source too much. |
| `drishti.search.budget` | `3s` | Searches over slow sources report `partial`. | — |

### Example 1: a slow database behind a pack

Views of `trade` sometimes fail with `DRS-1004 … timed out` and counterparty links show *pending*:

```yaml
# application.local.yaml
drishti:
  sources:
    fetch-timeout: 5s
  graph:
    link-budget: 100ms
```

Restart the server and check that `timings.fetch` is below the new timeout and the pending links are gone.

### Example 2: many fast-ticking entities on a small server

```bash
java -XX:+UseZGC -XX:+ZGenerational -jar drishti-server-1.15.0-exec.jar \
  --drishti.live.frame=100ms --drishti.live.max-streams=5000
```

Each busy entity now costs at most ten frames a second instead of twenty. `p99Ms` in `/api/v1/health/live`
rises by up to the extra 50 ms; CPU should drop.

### Example 3: connector caches

Lake and stream connectors keep data in memory, and the Kafka connector can also keep it on disk. Their cache
settings live under the connector's `settings` and are overridden by the connector's name:

| Connector plugin | Setting | Default | What it holds |
|---|---|---|---|
| `delta` | `cache-mb` | `512` | Lake partitions kept in memory, by size. |
| `kafka` | `cache-mb` | `256` | Latest documents in memory; a miss reads the record back from Kafka by offset. |
| `kafka` | `disk-cache.enabled`, `disk-cache.max-gb` | `false` (the `trading` pack turns it on), `10` | RocksDB disk cache in `./data/cache/<connector>` (root `DRISHTI_CACHE_ROOT`), cleared nightly at `disk-cache.reset-at` (`02:00`). |
| `s3` | `cache-entries`, `cache-seconds` | `10000`, `30` | Documents read from S3, and for how long. |

For example, give the `trading` pack's lake connector 2 GB:

```yaml
drishti:
  sources:
    connectors:
      trading-store:
        settings:
          cache-mb: 2048
```

Site settings override what a pack contributes, key by key.

### JVM settings

The server image runs with `-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational` (see
`deploy/server.Dockerfile`). Generational ZGC keeps pauses well under a millisecond, which matters for the live
p99. Use the same flags when you run the jar yourself:

```bash
JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational" java -jar drishti-server-1.15.0-exec.jar
```

Check heap use under load in Admin → Health (`server.heapUsedMb`, `heapMaxMb`) or with
`curl -s http://localhost:18480/actuator/metrics/jvm.memory.used`.

## Scaling out

A server holds its own topics, streams and caches; servers share nothing at run time. To serve more users,
run more servers behind a load balancer. Tokens are stateless, so any server can serve any request; console sign-in
sessions and users live in the identity database, so servers behind one load balancer share one (PostgreSQL,
`DRISHTI_IDENTITY_DB_URL`), or a session opened on one is unknown to the next. Live
streams stay on the server that opened them, and the browser's reconnect may land on another server, which
simply starts with a fresh `view` event. The console keeps each tab's live channel in its own memory, so
when you run several console processes, make sessions sticky (see [LIVE.md](../architecture/LIVE.md#one-connection-per-browser)).
