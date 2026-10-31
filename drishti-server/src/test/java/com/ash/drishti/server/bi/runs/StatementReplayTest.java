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

import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A statement run interrupted by a restart (docs/architecture/BI_DESIGN.md 12.1) is made again as its runner from the request it was asked with, instead of
 * failing with DRS-9085; without the statement module it still fails with a reason.
 */
class StatementReplayTest {

    @TempDir Path data;

    static final String QUESTION = "{\"kind\":\"file\",\"request\":{\"format\":\"pdf\",\"report\":\"desk-pnl\"},\"type\":\"statement\"}";

    RunService service(RunRecords records) {
        RunsProperties p = RunsProperties.defaults(data);
        return new RunService(p, records, new RunStore(p), Clock.systemUTC(), "java-1", RunMetrics.NONE);
    }

    Run interrupted(RunRecords records) {
        Run r = Run.planned("r-java-1-01JA0000000000000000000000", "anna", "anna", "statement", QUESTION, "ab".repeat(32), "personal", Tier.AD_HOC_HEAVY, "local", "{}",
                "java-1", Instant.now()).moveTo(RunState.QUEUED, null, null, Instant.now()).moveTo(RunState.RUNNING, null, null, Instant.now());
        records.save(r);
        return r;
    }

    @Test
    void anInterruptedStatementRunIsMadeAgainAsItsRunner() throws Exception {
        RunRecords records = new RunRecords.Memory();
        Run r = interrupted(records);
        RunService runs = service(records);
        RunSources sources = new RunSources(runs, null, null, null, null);
        List<String> made = new CopyOnWriteArrayList<>();
        sources.statements((p, body, kind) -> {
            made.add(p.user() + " " + kind + " " + body.path("report").asText());
            return Map.of("format", "pdf", "filename", "desk-pnl.pdf", "contentType", "application/pdf", "pages", 2, "bytes", "JVBERi0=");
        });
        runs.replay(x -> sources.replay(x, new Principal(x.runner(), List.of("risk"))));
        try {
            assertThat(runs.recover()).isEqualTo(1);
            Run done = null;
            for (int i = 0; i < 300 && done == null; i++) {
                Optional<Run> now = runs.get(r.id());
                if (now.isPresent() && now.get().state() == RunState.DONE) {
                    done = now.get();
                } else {
                    assertThat(now.map(Run::state).orElse(RunState.QUEUED)).isNotEqualTo(RunState.FAILED);
                    Thread.sleep(20);
                }
            }
            assertThat(done).as("the statement run finished after the restart").isNotNull();
            assertThat(done.attempt()).isEqualTo(2);
            assertThat(made).containsExactly("anna file desk-pnl");
            assertThat(runs.result(done, done.signature())).containsEntry("filename", "desk-pnl.pdf");
        } finally {
            runs.close();
        }
    }

    @Test
    void withoutTheStatementModuleItFailsWithAReason() {
        RunRecords records = new RunRecords.Memory();
        Run r = interrupted(records);
        RunService runs = service(records);
        RunSources sources = new RunSources(runs, null, null, null, null);
        runs.replay(x -> sources.replay(x, new Principal(x.runner(), List.of("risk"))));
        try {
            runs.recover();
            Run failed = runs.get(r.id()).orElseThrow();
            assertThat(failed.state()).isEqualTo(RunState.FAILED);
            assertThat(failed.code()).isEqualTo("DRS-9085");
        } finally {
            runs.close();
        }
    }
}
