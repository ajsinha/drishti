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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The R1 contract (docs/architecture/BI_RUNS.md sections 14.3 and 15): the run object has every documented field, {@code can} is the caller's,
 * and {@code config/runs/runs.yaml} reads to the documented defaults (the shipped file and no file agree).
 */
class RunsContractTest {

    static final Instant T0 = Instant.parse("2026-10-20T09:14:03Z");

    @Test
    void theRunObjectHasEveryFieldOfTheContract() {
        Run r = RunStateTest.planned().toBuilder().build().moveTo(RunState.QUEUED, null, null, T0).moveTo(RunState.RUNNING, null, null, T0.plusSeconds(1))
                .progress(0.29, "building 12 of 41 dates");
        Map<String, Object> m = RunJson.of(r, RunJson.View.of("ajsinha"), T0.plusSeconds(113));
        assertThat(m.keySet()).containsExactly("id", "title", "source", "runner", "owner", "workspace", "params", "asOf", "data", "tier", "engine", "class",
                "state", "progress", "phase", "estimate", "actual", "code", "reason", "node", "attempt", "submittedAt", "startedAt", "finishedAt",
                "expiresAt", "reuse", "shared", "group", "rerunOf", "promotedTo", "can", "links", "schedule", "confirmToken");
        assertThat(m.get("state")).isEqualTo("running");
        assertThat(m.get("class")).isEqualTo("background");
        assertThat(((Map<?, ?>) m.get("actual")).get("seconds")).isEqualTo(112L);
        assertThat(keys(m, "source")).containsExactly("type", "id", "title", "model", "page", "visual", "href");
        assertThat(keys(m, "data")).containsExactly("version", "words", "tables");
        assertThat(keys(m, "can")).contains("open", "cancel", "rerun", "export", "share", "promote", "schedule", "keep", "delete");
        assertThat(((Map<?, ?>) m.get("can")).get("cancel")).isEqualTo(true);
        assertThat(((Map<?, ?>) m.get("can")).get("open")).isEqualTo(false);
        Map<?, ?> other = (Map<?, ?>) RunJson.of(r, RunJson.View.of("bob"), T0).get("can");
        assertThat(other.get("cancel")).isEqualTo(false);
        assertThat(other.get("schedule")).isEqualTo(false);
        Map<?, ?> admin = (Map<?, ?>) RunJson.of(r, RunJson.View.of("ops1").withPowers(true, false), T0).get("can");
        assertThat(admin.get("cancel")).isEqualTo(true);
        assertThat(admin.get("open")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    static java.util.Set<String> keys(Map<String, Object> m, String key) {
        return ((Map<String, Object>) m.get(key)).keySet();
    }

    @Test
    void aDoneRunMayBeOpenedByItsOwnerOnlyWhileItsResultIsThere() {
        Run done = RunStateTest.planned().moveTo(RunState.RUNNING, null, null, T0).moveTo(RunState.DONE, null, null, T0.plusSeconds(3));
        Map<?, ?> kept = (Map<?, ?>) RunJson.of(done, RunJson.View.of("ajsinha").withResult(true).withPowers(false, true), T0).get("can");
        assertThat(kept.get("open")).isEqualTo(true);
        assertThat(kept.get("promote")).isEqualTo(true);
        assertThat(kept.get("cancel")).isEqualTo(false);
        Map<?, ?> gone = (Map<?, ?>) RunJson.of(done, RunJson.View.of("ajsinha").withResult(false), T0).get("can");
        assertThat(gone.get("open")).isEqualTo(false);
        Map<?, ?> stranger = (Map<?, ?>) RunJson.of(done, RunJson.View.of("bob").withResult(true), T0).get("can");
        assertThat(stranger.get("open")).isEqualTo(false);
        Map<?, ?> recipient = (Map<?, ?>) RunJson.of(done, RunJson.View.of("bob").withResult(true).asRecipient(true), T0).get("can");
        assertThat(recipient.get("open")).isEqualTo(true);
        assertThat(recipient.get("share")).isEqualTo(false);
    }

    @Test
    void asOfIsFromToAndCount() {
        assertThat(RunJson.asOf("[\"2026-09-30\",\"2026-01-02\"]")).containsEntry("from", "2026-01-02").containsEntry("to", "2026-09-30").containsEntry("dates", 2);
        assertThat(RunJson.asOf("2026-09-30")).containsEntry("dates", 1);
        assertThat(RunJson.asOf(null)).isNull();
    }

    @Test
    void theShippedFileAndNoFileGiveTheSameDefaults(@TempDir Path data) {
        Path shipped = Path.of("..", "config", "runs", "runs.yaml");
        assertThat(Files.isRegularFile(shipped)).isTrue();
        RunsProperties a = RunsProperties.load(shipped, data, s -> s.replace("${drishti.data.dir:./data}", data.toString()));
        RunsProperties b = RunsProperties.defaults(data);
        assertThat(a.enabled()).isTrue();
        assertThat(a.inline().inlineWait()).isEqualTo(Duration.ofSeconds(2)).isEqualTo(b.inline().inlineWait());
        assertThat(a.inline().maxWait()).isEqualTo(Duration.ofSeconds(10));
        assertThat(a.planner()).isEqualTo(b.planner()).isEqualTo(PlannerSettings.defaults());
        assertThat(a.queue()).isEqualTo(b.queue());
        assertThat(a.engine()).isEqualTo(b.engine());
        assertThat(a.engine().memoryLimitBytes()).isEqualTo(2L << 30);
        assertThat(a.store()).isEqualTo(b.store());
        assertThat(a.store().dir()).isEqualTo(data.resolve("bi-runs").toAbsolutePath().normalize());
        assertThat(a.retention()).isEqualTo(b.retention());
        assertThat(a.restart()).isEqualTo(b.restart());
        assertThat(a.lease()).isEqualTo(b.lease());
        assertThat(a.maxDuration()).isEqualTo(Duration.ofHours(4));
        assertThat(a.confirmTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(a.notifyOn()).isEqualTo(List.of("done", "failed"));
        assertThat(a.suggest()).isEqualTo(b.suggest());
        assertThat(a.console()).containsEntry("inlineWait", "PT2S").containsEntry("eventsUrl", "/api/v1/bi/runs/events");
    }

    @Test
    void everyExampleReads(@TempDir Path data) throws Exception {
        try (var files = Files.list(Path.of("..", "config", "runs.examples"))) {
            for (Path f : files.toList()) {
                RunsProperties p = RunsProperties.load(f, data, s -> s);
                assertThat(p.origin()).isEqualTo(f.toString());
            }
        }
        assertThat(RunsProperties.load(Path.of("..", "config", "runs.examples", "off.yaml"), data, s -> s).enabled()).isFalse();
    }

    @Test
    void spansAndSizes() {
        assertThat(RunsProperties.span("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(RunsProperties.span("15m")).isEqualTo(Duration.ofMinutes(15));
        assertThat(RunsProperties.span("7d")).isEqualTo(Duration.ofDays(7));
        assertThat(RunsProperties.size("768MB")).isEqualTo(768L << 20);
        assertThatThrownBy(() -> RunsProperties.size("lots")).isInstanceOf(IllegalArgumentException.class);
    }
}
