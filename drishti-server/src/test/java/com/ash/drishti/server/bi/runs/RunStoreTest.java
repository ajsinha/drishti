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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The result store (docs/architecture/BI_RUNS.md section 8, test 19.1 RunStoreTest and RunSweeperTest): under the data folder, read back equal, served
 * only to the same signature, the key changes with each part, paging, retention and the budget.
 */
class RunStoreTest {

    @TempDir
    Path data;

    static final Map<String, Object> ANSWER = Map.of("rowCount", 3, "columns", List.of(Map.of("id", "DESK")), "rows",
            List.of(List.of("A", 1), List.of("B", 2), List.of("C", 3)), "meta", Map.of("masked", List.of("PRICE")));

    @Test
    void aResultReadsBackEqualOnlyForItsSignatureAndUnderTheDataFolder() {
        RunStore s = new RunStore(RunsProperties.defaults(data));
        String key = "ab".repeat(32);
        long bytes = s.write(key, "sig-a", "r-x", "ajsinha", ANSWER, 3);
        assertThat(bytes).isPositive();
        assertThat(s.root()).startsWith(data);
        assertThat(Files.isRegularFile(data.resolve("bi-runs/results/ab").resolve(key).resolve("answer.json"))).isTrue();
        assertThat(s.read(key, "sig-a")).contains(ANSWER);
        assertThat(s.read(key, "sig-b")).isEmpty();
        Map<String, Object> page = RunStore.page(s.read(key, "sig-a").orElseThrow(), 1, 1);
        assertThat(page.get("rows")).isEqualTo(List.of(List.of("B", 2)));
        assertThat(page.get("page")).isEqualTo(Map.of("offset", 1, "limit", 1, "total", 3));
        s.write(key, "sig-a", "r-y", "ajsinha", Map.of("rowCount", 0, "rows", List.of()), 0);       // replaced in place (FileSwap)
        assertThat(s.read(key, "sig-a").orElseThrow()).containsEntry("rowCount", 0);
        assertThat(s.usage()).containsEntry("results", 1);
        s.delete(key);
        assertThat(s.has(key)).isFalse();
    }

    @Test
    void theKeyChangesWithTheQuestionTheSignatureAndTheData() {
        Principal p = new Principal("u", List.of("r"));
        RunService.Question base = new RunService.Question(p, "mq", null, null, null, null, null, "{q}", null, "sig", "v1", false, null, () -> null);
        String k = RunService.key(base);
        assertThat(RunService.key(new RunService.Question(p, "mq", null, null, null, null, null, "{q2}", null, "sig", "v1", false, null, () -> null))).isNotEqualTo(k);
        assertThat(RunService.key(new RunService.Question(p, "mq", null, null, null, null, null, "{q}", null, "sig2", "v1", false, null, () -> null))).isNotEqualTo(k);
        assertThat(RunService.key(new RunService.Question(p, "mq", null, null, null, null, null, "{q}", null, "sig", "v2", false, null, () -> null))).isNotEqualTo(k);
        assertThat(RunService.key(new RunService.Question(p, "mq", "other", "x", "t", "w", "m", "{q}", "{}", "sig", "v1", true, "s", () -> null))).isEqualTo(k);
        Principal q = new Principal("v", List.of("r"));
        String unversioned = RunService.key(new RunService.Question(p, "mq", null, null, null, null, null, "{q}", null, "sig", null, false, null, () -> null));
        assertThat(RunService.key(new RunService.Question(q, "mq", null, null, null, null, null, "{q}", null, "sig", null, false, null, () -> null)))
                .isNotEqualTo(unversioned);                                     // no data version: never shared between people
    }

    @Test
    void theSweeperRemovesResultsNotOpenedWithinRetentionAndKeepsTheBudget() throws Exception {
        RunStore s = new RunStore(RunsProperties.defaults(data));
        String old = "0a".repeat(32);
        String fresh = "0b".repeat(32);
        s.write(old, "s", "r1", "u", ANSWER, 3);
        s.write(fresh, "s", "r2", "u", ANSWER, 3);
        Files.setLastModifiedTime(data.resolve("bi-runs/results/0a").resolve(old).resolve("answer.json"), FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        assertThat(s.sweep(Instant.now())).containsExactly(old);
        assertThat(s.has(fresh)).isTrue();
    }

    @Test
    void aResultPastItsRowLimitIsRefused() {
        RunStore s = new RunStore(RunsProperties.defaults(data));
        assertThatThrownBy(() -> s.write("cd".repeat(32), "s", "r", "u", ANSWER, 60_000_000)).isInstanceOf(DrishtiException.class).hasMessageContaining("DRS-9086".isEmpty() ? "" : "max-result-rows");
    }
}
