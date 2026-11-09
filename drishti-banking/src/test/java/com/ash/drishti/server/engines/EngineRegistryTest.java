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
package com.ash.drishti.server.engines;

import com.ash.drishti.kernel.api.engines.ComputeEngine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The registry (ADMIN_GUIDE.md sections 5 to 8, tests EngineRegistryTest, EngineSwitchLiveTest and EngineDependencyTest): an engine
 * off at start makes no thread and holds nothing, on it does; every dependency refusal with its words (DRS-1021), --with-needs and
 * --with-dependents; a file with frtb-sa on and var off starts with frtb-sa blocked (DRS-1022) and everything else up; off drains the
 * requests in flight then releases; the in-memory lake on says "restart needed"; History and the audit log; an edit by hand takes effect.
 */
class EngineRegistryTest {

    @TempDir Path dir;

    /** A fake engine that starts a thread named after it, holds 64 MB while it runs and counts its starts and stops. */
    static final class Fake implements ComputeEngine {
        final String id;
        volatile Boolean running;
        volatile Thread thread;
        int starts;
        int stops;

        Fake(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void start() {
            starts++;
            thread = Thread.ofPlatform().name("drishti-test-" + id + "-pool").daemon(true).start(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    // stopped
                }
            });
        }

        @Override
        public void stop() {
            stops++;
            Thread t = thread;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            thread = null;
        }

        @Override
        public long memoryBytes() {
            return thread == null ? 0 : 64L << 20;
        }

        @Override
        public List<String> threadPrefixes() {
            return List.of("drishti-test-" + id + "-");
        }

        @Override
        public void running(boolean on) {
            running = on;
        }

        @Override
        public Boolean running() {
            return running;
        }
    }

    final List<String> audited = new ArrayList<>();

    EngineRegistry registry(Map<String, Object> props, Fake... fakes) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("Config resource 'file [" + dir.resolve("engines") + "/var.yaml]' via location 'x'", props));
        EngineCatalogue c = EngineCatalogue.load();
        EngineFiles files = new EngineFiles(dir.resolve("engines"), n -> null);
        return new EngineRegistry(c, new EngineSwitches(c, env), files, new EngineHistory(dir.resolve("history.jsonl")),
                new com.ash.drishti.identity.AuditLog() {
                    @Override
                    public void record(String actor, String action, String subject, String detail) {
                        audited.add(action + " " + subject + " " + actor);
                    }

                    @Override
                    public List<Event> recent(int limit, String subject) {
                        return List.of();
                    }
                }, () -> "node-a", Duration.ofSeconds(2),
                List.of(fakes));
    }

    static Fake[] all() {
        return new Fake[] {new Fake("var"), new Fake("exposure"), new Fake("sensitivities"), new Fake("frtb-sa"), new Fake("simm"), new Fake("memory-lake")};
    }

    @Test
    void anEngineOffAtStartMakesNoThreadAndHoldsNothingWhileOneOnDoes() {
        Fake[] f = all();
        EngineRegistry r = registry(Map.of("drishti.engines.exposure.enabled", "false"), f);
        assertThat(r.state("var")).isEqualTo(EngineRegistry.State.RUNNING);
        assertThat(r.state("exposure")).isEqualTo(EngineRegistry.State.OFF);
        assertThat(r.state("memory-lake")).as("off as shipped").isEqualTo(EngineRegistry.State.OFF);
        assertThat(f[1].starts).isZero();
        assertThat(ComputeEngine.threadsNamed(f[1].threadPrefixes())).isZero();
        assertThat(f[1].memoryBytes()).isZero();
        assertThat(ComputeEngine.threadsNamed(f[0].threadPrefixes())).isEqualTo(1);
        assertThat(f[0].memoryBytes()).isEqualTo(64L << 20);
        assertThatThrownBy(() -> r.enter("exposure")).isInstanceOf(EngineOffException.class)
                .hasMessageContaining("DRS-1020 Exposure is not running on this server");
        r.apply(List.of(new EngineRegistry.Change("var", false)), "tidy", "ann", false, true, null);
        assertThat(ComputeEngine.threadsNamed(f[0].threadPrefixes())).as("its threads end").isZero();
    }

    @Test
    void theDependenciesAreEnforcedWithWordsAndTheFlagsAddWhatIsNeeded() {
        Fake[] f = all();
        EngineRegistry r = registry(Map.of(), f);
        assertThatThrownBy(() -> r.apply(List.of(new EngineRegistry.Change("var", false)), "market-risk elsewhere", "ann", false, false, null))
                .isInstanceOf(DrishtiException.class).extracting(e -> ((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.ENGINE_DEPENDENCY);
        assertThatThrownBy(() -> r.apply(List.of(new EngineRegistry.Change("sensitivities", false)), "x", "ann", false, false, null))
                .hasMessageContaining("Sensitivities is needed by FRTB-SA and SIMM on this server. Turn them off first, or all three together.");
        assertThat(r.running("var")).as("nothing changed").isTrue();
        // FRTB-SA off, then VaR off: allowed; FRTB-SA on again is refused while VaR is off
        r.apply(List.of(new EngineRegistry.Change("frtb-sa", false), new EngineRegistry.Change("var", false)), "capital on node b", "ann", false, false, null);
        assertThatThrownBy(() -> r.apply(List.of(new EngineRegistry.Change("frtb-sa", true)), "back", "ann", false, false, null))
                .hasMessageContaining("FRTB-SA needs VaR on this server: its scopes (desks, books, limit letters) are VaR's")
                .hasMessageContaining("Turn on VaR first, or both together.");
        var out = r.apply(List.of(new EngineRegistry.Change("frtb-sa", true)), "back", "ann", true, false, null);
        assertThat(out).extracting(EngineRegistry.Outcome::engine).containsExactly("var", "frtb-sa");
        assertThat(r.running("frtb-sa")).isTrue();
        out = r.apply(List.of(new EngineRegistry.Change("sensitivities", false)), "x", "ann", false, true, null);
        assertThat(out).extracting(EngineRegistry.Outcome::engine).containsExactly("simm", "frtb-sa", "sensitivities");
        assertThat(r.history().newestFirst(50)).anyMatch(c -> "refused".equals(c.outcome()) && c.detail().contains("needed by"));
        assertThat(audited).anyMatch(a -> a.startsWith("engine-refused engine/var"));
        assertThat(audited).anyMatch(a -> a.startsWith("engine-disabled engine/sensitivities ann"));
        assertThatThrownBy(() -> r.apply(List.of(new EngineRegistry.Change("var", true)), " ", "ann", false, false, null))
                .hasMessageContaining("give a reason");
    }

    @Test
    void frtbOnWithVarOffStartsBlockedAndEverythingElseUp() {
        Fake[] f = all();
        EngineRegistry r = registry(Map.of("drishti.engines.var.enabled", "false", "drishti.engines.frtb-sa.enabled", "true"), f);
        assertThat(r.state("frtb-sa")).isEqualTo(EngineRegistry.State.BLOCKED);
        assertThat(r.reason("frtb-sa")).isEqualTo("FRTB-SA needs VaR on this server, which is off");
        assertThat(f[3].starts).isZero();
        assertThat(r.running()).containsExactly("exposure", "sensitivities", "simm");
    }

    @Test
    void offDrainsTheRequestInFlightThenReleasesAndOnAnswersAgain() throws Exception {
        Fake[] f = all();
        EngineRegistry r = registry(Map.of(), f);
        AutoCloseable inFlight = r.enter("exposure");
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            r.apply(List.of(new EngineRegistry.Change("exposure", false)), "moved to node b", "ann", false, false, null);
            done.countDown();
        });
        Thread.sleep(150);
        assertThat(r.state("exposure")).isEqualTo(EngineRegistry.State.STOPPING);
        assertThatThrownBy(() -> r.enter("exposure")).as("the next request").isInstanceOf(EngineOffException.class);
        assertThat(f[1].stops).as("not released while a request runs").isZero();
        inFlight.close();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(f[1].stops).isEqualTo(1);
        assertThat(f[1].memoryBytes()).isZero();
        assertThat(r.history().newestFirst(1).get(0).detail()).contains("1 request(s) in flight finished; 64 MB released");
        r.apply(List.of(new EngineRegistry.Change("exposure", true)), "back", "ann", false, false, null);
        r.enter("exposure").close();
    }

    @Test
    void theInMemoryLakeOnIsWrittenAndWaitsForARestart() throws Exception {
        Fake[] f = all();
        EngineRegistry r = registry(Map.of(), f);
        var out = r.apply(List.of(new EngineRegistry.Change("memory-lake", true)), "news feed must not touch disk", "a.sinha", false, false, null);
        assertThat(out.get(0).outcome()).isEqualTo("restart needed");
        assertThat(r.restartNeeded("memory-lake")).contains("by a.sinha");
        assertThat(r.state("memory-lake")).isEqualTo(EngineRegistry.State.OFF);
        assertThat(Files.readString(dir.resolve("engines/memory-lake.yaml"))).contains("enabled: ${DRISHTI_MEMORY_LAKE_ENABLED:true}")
                .contains("reason: \"news feed must not touch disk\"").contains("stores: {}");
    }

    @Test
    void anEditByHandTakesEffectAndLandsInHistoryWithoutANameOrReason() throws Exception {
        Fake[] f = all();
        EngineRegistry r = registry(Map.of(), f);
        r.apply(List.of(new EngineRegistry.Change("simm", false)), "IM on ccr-1 only", "ann", false, false, null);
        Path file = dir.resolve("engines/simm.yaml");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text.replace("${DRISHTI_SIMM_ENABLED:false}", "${DRISHTI_SIMM_ENABLED:true}"));
        r.scan();
        assertThat(r.running("simm")).isTrue();
        var last = r.history().newestFirst(1).get(0);
        assertThat(last.change()).isEqualTo("edited in the file (on)");
        assertThat(last.who()).isEmpty();
        assertThat(last.why()).isEmpty();
        assertThat(last.outcome()).isEqualTo("live");
    }
}
