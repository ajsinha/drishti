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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 2.9.11.e's one parity proof (HOW_IT_FITS.md §A28 sections 5.2 and 5.3): every answer the code gives now equals the
 * fixture {@link BiFoundationRecorder} recorded before anything moved, every person's rows and masks included; floating point within 1e-9
 * relative, everything else exactly. A difference fails naming the file, the person, the case and both values. Until the fixtures are
 * recorded and committed (the release's first heavy step) the test is skipped and says so.
 */
class BiFoundationParityTest {

    @TempDir Path store;
    @TempDir Path liveStore;

    @Test
    void everyAnswerEqualsTheRecordedOne() throws Exception {
        Assumptions.assumeTrue(Files.exists(BiFoundationCases.FIXTURES.resolve("index.json")),
                "record the fixtures first: BiFoundationRecorder on the tree before the switch (-Ddrishti.bi.foundation.record=true)");
        Map<String, Object> now = BiFoundationRecorder.record(store, liveStore);
        List<String> problems = new ArrayList<>();
        int files = 0;
        try (var recorded = Files.list(BiFoundationCases.FIXTURES)) {
            for (Path f : recorded.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.equals("index.json")) {
                    continue;                                               // how it was recorded: the words change, the figures may not
                }
                files++;
                Object want = BiFoundationCases.read(name);
                Object got = BiFoundationCases.json().readValue(BiFoundationCases.json().writeValueAsString(now.get(name)), Object.class);
                if (got == null) {
                    problems.add(name + ": recorded, not answered now");
                    continue;
                }
                BiFoundationCases.compare(name, want, got, problems);
            }
        }
        now.keySet().stream().filter(k -> !Files.exists(BiFoundationCases.FIXTURES.resolve(k))).forEach(k -> problems.add(k + ": answered now, not recorded"));
        assertThat(problems).as(files + " fixture files compared").isEmpty();
        assertThat(files).isGreaterThan(10);
    }
}
