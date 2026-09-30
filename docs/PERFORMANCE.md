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

The numbers below were measured, not estimated. Each row names the date, the machine class and the
command, so any number can be checked again.

## Hot paths (JMH)

`drishti-benchmarks/HotPathBenchmark` runs against the IRS-48213 reference document.

```bash
./mvnw -q -pl drishti-benchmarks -am install -DskipTests
./mvnw -q -pl drishti-benchmarks dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "drishti-benchmarks/target/classes:$(cat cp.txt)" org.openjdk.jmh.Main
```

| Benchmark | 2026-09-30, developer workstation | What it is |
|---|---|---|
| `evalTernary` | 0.011 µs | `$.direction == 'PAY_FIXED' ? … : …` (compiled Rachana-EL) |
| `evalSum` | 0.049 µs | `sum($.legs[0].cashflows, 'pv')` |
| `formatSigned` | 0.090 µs | `signed2` of −1,962,430.56 |
| `fingerprint` | 3.5 µs | shape fingerprint of the whole IRS document (cached per generation) |
| `inferColdLayout` | 12.9 µs | full inference for the IRS document (cached per shape) |

## End-to-end view (gate)

`ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99` builds the full IRS view 2,000 times
after warm-up: fetch, match, layout (cached), link fan-out on virtual threads, parallel binding.
The build fails if p99 ≥ 50 ms or if the layout cache hit rate is ≤ 99 %.

## Live latency (measured)

| Measure | 2026-09-30, developer workstation | How |
|---|---|---|
| tick → frame built, p50 | 2.4 ms | `GET /api/v1/health/live` after streaming IRS-48213 through the console |
| tick → frame built, p99 | 11.2 ms | same; target < 40 ms |
| browser top bar | "Live, p99 2 ms" | read from real Chrome over the DevTools protocol |
| fan-out | 10,000 listeners on one topic all receive the latest of 50 ticks | `TopicHubTest` |

The first version delayed every tick by a whole frame (p50 52 ms). Switching to a leading-edge
throttle (send at once after a quiet frame, coalesce inside a busy one) brought p50 to 2.4 ms.

## Why it is fast

- **Layouts are data-free and cached** by (Sutra version, kind, shape fingerprint). Inference and
  Sutra merging run once per shape.
- **Fingerprints are cached** by (entity, generation). A tick with a new generation costs one 3.5 µs hash.
- **Expressions are compiled once** into immutable closure trees, cached by source text and shared
  across threads.
- **I/O runs on virtual threads.** Linked entities are fetched in parallel under a 40 ms budget;
  late ones render as *pending*.
- **Panels bind in parallel** on a bounded pool sized to the cores.
