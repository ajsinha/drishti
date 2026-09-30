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
# Runbook: live latency high

**Symptoms.**
- The top bar shows `Live, p99` above 40 ms.
- The `drishti_live_latency_p99_milliseconds` alert fires.

**Diagnosis.**
1. Run `curl -s :18480/api/v1/health/live` to see streams, topics, frames, p50 and p99.
2. If p50 is also high, the server is CPU-bound. Check `drishti_view_seconds` and JVM metrics, and
   look for a hot Sutra with very large tables.
3. If only p99 is high, look for GC pauses (ZGC should keep them sub-millisecond) or a burst of new streams.

**Steps.**
- Scale out servers. Topics are per server, so load spreads by user.
- Lower `drishti.live.max-streams` to protect the server under a burst.
- Raise `drishti.live.frame` (for example to 100 ms). Busy topics then coalesce more, trading
  freshness for throughput.

**Verification.** p99 returns below 40 ms within one 30 s window.
