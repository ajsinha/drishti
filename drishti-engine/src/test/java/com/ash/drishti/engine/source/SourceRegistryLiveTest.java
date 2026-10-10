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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** One connector at a time can be started, restarted and stopped while the others keep serving. */
class SourceRegistryLiveTest {

    /** Records its life: every instance notes {@code start:<setting x>} and {@code close:<setting x>}. */
    public static final class Recorder implements SourcePlugin {
        static final List<String> LOG = new CopyOnWriteArrayList<>();
        static final AtomicInteger OPEN = new AtomicInteger();
        private String x = "?";

        public PluginManifest manifest() {
            return new PluginManifest("recorder", "t", Set.of("trade"), SourceCapabilities.FETCH_ONLY);
        }

        public void start(SourceContext c) {
            x = c.setting("x", "-");
            if ("boom".equals(x)) {
                throw new IllegalStateException("cannot reach boom");
            }
            if ("idle".equals(x)) {
                throw new com.ash.drishti.api.PluginNotConfigured("nothing to read");
            }
            OPEN.incrementAndGet();
            LOG.add("start:" + x);
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        public void close() {
            OPEN.decrementAndGet();
            LOG.add("close:" + x);
        }
    }

    private static SourcesProperties.ConnectorSettings cs(String x, boolean on) {
        return new SourcesProperties.ConnectorSettings("recorder", on, List.of("trade"), Map.of("x", x));
    }

    private static SourceRegistry registry(Duration drain, Map<String, SourcesProperties.ConnectorSettings> initial) {
        Recorder.LOG.clear();
        Recorder.OPEN.set(0);
        var props = new SourcesProperties(Map.of(), null, Map.of("recorder", new SourcesProperties.PluginSettings(false, Map.of())), Duration.ofSeconds(1), null, initial, null, "off", null, drain, null);
        return new SourceRegistry(List.of(new Recorder()), props, new JsonCodec());
    }

    @Test
    void aNewConnectorStartsAndADeletedOneStops() {
        SourceRegistry r = registry(Duration.ZERO, Map.of());
        assertThat(r.plugins()).isEmpty();
        assertThat(r.apply("lake", cs("a", true)).state()).isEqualTo("RUNNING");
        assertThat(r.plugin("lake")).isPresent();
        assertThat(r.plugin("lake").get().manifest().name()).isEqualTo("lake");
        assertThat(r.connectorStatuses().get("lake").state()).isEqualTo("RUNNING");
        r.remove("lake");
        assertThat(r.plugin("lake")).isEmpty();
        assertThat(r.connectorStatuses()).doesNotContainKey("lake");
        assertThat(Recorder.LOG).containsExactly("start:a", "close:a");
    }

    @Test
    void aChangedConnectorRestartsAloneAndOthersAreNotTouched() {
        var initial = new java.util.LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        initial.put("one", cs("1", true));
        initial.put("two", cs("2", true));
        initial.put("three", cs("3", true));
        SourceRegistry r = registry(Duration.ZERO, initial);
        SourcePlugin twoBefore = r.plugin("two").get();
        Recorder.LOG.clear();
        assertThat(r.apply("one", cs("1b", true)).state()).isEqualTo("RUNNING");
        assertThat(Recorder.LOG).containsExactly("close:1", "start:1b");
        assertThat(r.plugin("two").get()).isSameAs(twoBefore);
        assertThat(r.plugins().stream().map(p -> p.manifest().name())).containsExactly("one", "two", "three");   // config order survives a restart
    }

    @Test
    void switchingOffStopsAndSwitchingOnStarts() {
        SourceRegistry r = registry(Duration.ZERO, Map.of("lake", cs("a", true)));
        assertThat(r.apply("lake", cs("a", false)).state()).isEqualTo("DISABLED");
        assertThat(r.plugin("lake")).isEmpty();
        assertThat(Recorder.OPEN.get()).isZero();
        assertThat(r.apply("lake", cs("a", true)).state()).isEqualTo("RUNNING");
        assertThat(Recorder.OPEN.get()).isEqualTo(1);
    }

    @Test
    void aConnectorThatCannotStartIsReportedNotThrown() {
        SourceRegistry r = registry(Duration.ZERO, Map.of());
        var st = r.apply("lake", cs("boom", true));
        assertThat(st.state()).isEqualTo("FAILED");
        assertThat(st.problem()).contains("cannot reach boom");
        assertThat(r.failures()).containsEntry("lake", "cannot reach boom");
        assertThat(r.apply("lake", cs("fine", true)).state()).isEqualTo("RUNNING");
        assertThat(r.failures()).doesNotContainKey("lake");
        assertThat(r.apply("quiet", cs("idle", true)).state()).isEqualTo("IDLE");
        assertThat(r.apply("ghost", new SourcesProperties.ConnectorSettings("nope", true, List.of(), Map.of())).problem()).contains("no plugin named 'nope'");
    }

    @Test
    void theOldInstanceStaysOpenForTheDrainSoReadsInFlightFinish() throws Exception {
        SourceRegistry r = registry(Duration.ofMillis(400), Map.of("lake", cs("a", true)));
        Recorder.LOG.clear();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            r.apply("lake", cs("b", true));
            done.countDown();
        });
        Thread.sleep(150);                                                    // mid-drain: out of routing, not yet closed
        assertThat(r.plugin("lake")).isEmpty();
        assertThat(Recorder.LOG).doesNotContain("close:a");
        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(Recorder.LOG).containsExactly("close:a", "start:b");
    }

    @Test
    void changesToTheSameConnectorRunOneAtATimeAndDifferentOnesInParallel() throws Exception {
        SourceRegistry r = registry(Duration.ofMillis(200), Map.of("a", cs("a", true), "b", cs("b", true)));
        long t0 = System.nanoTime();
        Thread t1 = Thread.ofVirtual().start(() -> r.apply("a", cs("a1", true)));
        Thread t2 = Thread.ofVirtual().start(() -> r.apply("b", cs("b1", true)));
        t1.join();
        t2.join();
        long parallelMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(parallelMs).as("two connectors restart together, not one after the other").isLessThan(380);
        t0 = System.nanoTime();
        Thread s1 = Thread.ofVirtual().start(() -> r.apply("a", cs("a2", true)));
        Thread s2 = Thread.ofVirtual().start(() -> r.apply("a", cs("a3", true)));
        s1.join();
        s2.join();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("the same connector is reconfigured one change at a time").isGreaterThanOrEqualTo(380);
        assertThat(Recorder.OPEN.get()).isEqualTo(2);                         // never two instances of one connector open for good
    }

    @Test
    void fileReferencesOnCredentialsAreReadWhenTheConnectorStarts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path secret = dir.resolve("pw");
        java.nio.file.Files.writeString(secret, "s3cret\nignored\n");
        Map<String, String> out = com.ash.drishti.common.ConnectorSecrets.resolve(Map.of("password", "file:" + secret, "url", "file:" + secret));
        assertThat(out).containsEntry("password", "s3cret").containsEntry("url", "file:" + secret);   // only credential keys are read from files
    }

    @Test
    void aKindRoutedToAConnectorNobodyDefinedSaysSo() {
        var props = new SourcesProperties(Map.of("trade", "trading-lake"), null, Map.of(), Duration.ofSeconds(1), null, Map.of());
        SourceRegistry r = new SourceRegistry(List.of(new Recorder()), props, new JsonCodec());
        var router = new SourceRouter(r, props, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        var e = router.noSource("trade");
        assertThat(e.errorCode()).isEqualTo(com.ash.drishti.common.ErrorCode.CONNECTOR_NOT_CONFIGURED);
        assertThat(e.getMessage()).contains("connector trading-lake is not configured").contains("Admin -> Connectors");
        assertThat(router.noSource("other").getMessage()).endsWith("no source serves kind 'other'");
        r.apply("trading-lake", cs("boom", true));
        assertThat(router.noSource("trade").getMessage()).contains("trading-lake is failed").contains("cannot reach boom");
    }
}
