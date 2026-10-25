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
package com.ash.drishti.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.compute.arrow.ArrowTable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Warm mode, the way workers run on Windows (no fork; docs/architecture/PYTHON_COMPUTE.md section 7.2): it runs on every platform, so the
 * Windows QA machine runs these with the console's Python and no native binary. Registered state is cleared between requests, a crash or a
 * timeout replaces the worker, and a worker is recycled after {@code recycle-after} requests.
 */
class WarmModeTest {

    @TempDir
    Path dir;
    ComputeService compute;
    ComputeSettings settings;

    @BeforeEach
    void start() {
        assumeTrue(TestCompute.python() != null, "no Python 3.10+ interpreter to run workers");
        settings = TestCompute.settings(dir, TestCompute.onePool("w", 1, 2, "warm", "      recycle-after: 3\n      timeout: 20s\n"));
        compute = TestCompute.service(settings, dir);
    }

    @AfterEach
    void stop() {
        if (compute != null) {
            compute.close();
        }
    }

    private ComputeRequest.Builder req(String kind, String user) {
        return ComputeRequest.on("w", kind, user).engine(TestCompute.FAULTS);
    }

    @Test
    void registeredStateIsClearedBetweenRequests() {
        compute.run(req("leak", "asha").param("value", "asha's secret").build());
        assertThat(compute.run(req("who", "ben").build()).rows().get(0).get("leak")).isNull();
    }

    @Test
    void aWorkerIsRecycledAfterItsRequestsAndACrashReplacesIt() {
        Set<Object> pids = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            pids.add(compute.run(req("who", "asha").build()).rows().get(0).get("pid"));
        }
        assertThat(pids.size()).isGreaterThanOrEqualTo(3);                     // 7 requests, 3 per worker at most
        assertThatThrownBy(() -> compute.run(req("crash", "asha").build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.CRASHED));
        assertThat(compute.run(req("who", "asha").build()).rows()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> pool = ((java.util.List<Map<String, Object>>) compute.overview().get("pools")).get(0);
        assertThat(((Number) pool.get("recycled")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aTimeoutReplacesTheWorkerAndTheSandboxStillHolds() throws Exception {
        assertThatThrownBy(() -> compute.run(req("loop", "asha").timeout(Duration.ofMillis(1200)).build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.TIMEOUT));
        assertThatThrownBy(() -> compute.run(req("write-outside", "asha").param("path", dir.resolve("x.txt").toString()).build()))
                .isInstanceOfSatisfying(ComputeException.class, e -> assertThat(e.code()).isEqualTo(ComputeException.Code.LIMIT));
        ComputeResult r = compute.run(ComputeRequest.on("w", "echo", "asha").engine(TestCompute.VECTORS)
                .input("values", ArrowTable.builder().doubles("X", 1, 2).build()).build());
        assertThat(r.table("values").doubles("X")).containsExactly(1, 2);
        assertThat(TestCompute.scratchLeft(settings)).isEmpty();
    }
}
