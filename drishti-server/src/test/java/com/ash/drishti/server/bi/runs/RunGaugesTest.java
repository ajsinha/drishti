/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The gauges of BI_DESIGN › BI runs, 17.1, Admin's snapshot storage and accuracy by basis, and what {@code /engine} adds for runs (R8). */
class RunGaugesTest {

    @TempDir Path data;

    @Test
    void theGaugesReadTheQueueTheWorkersAndTheStore() throws Exception {
        RunsProperties p = RunsProperties.defaults(data);
        RunService runs = new RunService(p, new RunRecords.Memory(), new RunStore(p), Clock.systemUTC(), "java-1", RunMetrics.NONE);
        try {
            SimpleMeterRegistry m = new SimpleMeterRegistry();
            RunMetrics.gauges(m, runs);
            assertThat(m.get("drishti.bi.runs.queue").tag("class", "background").gauge().value()).isZero();
            assertThat(m.get("drishti.bi.runs.running").tag("engine", "local").gauge().value()).isZero();
            runs.store().write(com.ash.drishti.server.bi.plan.SecuritySignature.sha("k1"), "sig", "r-1", "ann", Map.of("rowCount", 1, "rows", List.of(List.of(1))), 1);
            assertThat(m.get("drishti.bi.runs.store.bytes").tag("kind", "results").gauge().value()).isPositive();
            Path snap = p.store().dir().resolve("snapshots/desk-pnl/v1");
            Files.createDirectories(snap);
            Files.writeString(snap.resolve("answer.json"), "{}");
            assertThat(m.get("drishti.bi.runs.store.bytes").tag("kind", "snapshots").gauge().value()).isEqualTo(2.0);
            assertThat(runs.store().snapshotsUsage()).containsEntry("snapshots", 1L);
            Map<String, Object> admin = RunAdmin.of(runs, null, 3.5, List.of(), List.of());
            assertThat(((Map<?, ?>) admin.get("now")).get("admissionWaitP95Ms")).isEqualTo(3.5);
            assertThat(((Map<?, ?>) admin.get("storage")).get("snapshots")).isEqualTo(1L);
            assertThat(((Map<?, ?>) admin.get("accuracy")).get("basis")).isEqualTo(Map.of());
            runs.stats.ratio("delta-stats", 1.2);
            assertThat(((Map<?, ?>) RunAdmin.of(runs, null, null, List.of(), List.of()).get("accuracy")).get("basis"))
                    .isEqualTo(Map.of("delta-stats", Map.of("medianRatio", 1.2, "samples", 1)));
            assertThat(runs.engineStats()).containsKeys("queued", "running", "workers", "engine").doesNotContainKey("byUser");
        } finally {
            runs.close();
        }
    }
}
