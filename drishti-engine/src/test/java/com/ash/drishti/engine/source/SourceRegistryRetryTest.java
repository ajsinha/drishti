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
import com.ash.drishti.common.RetryPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** A connector whose source is not there when it starts is started again on the retry schedule, and serves once it is. */
class SourceRegistryRetryTest {

    /** The stub "server": a switch the test flips; the plugin cannot start while it is off and reports DOWN when it goes off. */
    static final AtomicBoolean UP = new AtomicBoolean();
    static final List<String> LOG = new CopyOnWriteArrayList<>();

    public static final class Stubbed implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest("stubbed", "t", Set.of("trade"), SourceCapabilities.FETCH_ONLY);
        }

        public void start(SourceContext c) {
            if (!UP.get()) {
                throw new IllegalStateException("connection refused: stub is down");
            }
            LOG.add("start");
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public String health() {
            return UP.get() ? "UP" : "DOWN: stub is down";
        }
    }

    private static SourceRegistry registry(Map<String, SourcesProperties.ConnectorSettings> initial) {
        LOG.clear();
        var props = new SourcesProperties(Map.of(), null, Map.of("stubbed", new SourcesProperties.PluginSettings(false, Map.of())), Duration.ofSeconds(1),
                null, initial, null, "off", null, Duration.ZERO, null);
        RetryPolicy fast = new RetryPolicy(true, Duration.ofMillis(100), 2.0, Duration.ofMillis(400), 0, 3);
        return new SourceRegistry(List.of(new Stubbed()), props, new JsonCodec(), fast);
    }

    private static SourcesProperties.ConnectorSettings cs() {
        return new SourcesProperties.ConnectorSettings("stubbed", true, List.of("trade"), Map.of());
    }

    private static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 100 && !c.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
    }

    @Test
    void aConnectorToAStubThatComesUpLaterStartsAndServes() throws Exception {
        UP.set(false);
        try (SourceRegistry r = registry(Map.of("lake", cs()))) {
            assertThat(r.connectorStatuses().get("lake").state()).isEqualTo("FAILED");
            var s = r.retryStatus("lake").orElseThrow();
            assertThat(s.attempt()).isGreaterThanOrEqualTo(1);
            assertThat(s.lastError()).contains("connection refused");
            assertThat(s.nextAt()).isNotNull();
            Thread.sleep(1500);                                                   // still down: it keeps trying, with a growing wait
            assertThat(r.retryStatus("lake").orElseThrow().attempt()).isGreaterThan(s.attempt());
            assertThat(r.plugin("lake")).isEmpty();
            UP.set(true);
            await(() -> r.plugin("lake").isPresent());
            assertThat(r.plugin("lake")).isPresent();
            assertThat(r.connectorStatuses().get("lake").state()).isEqualTo("RUNNING");
            assertThat(r.retryStatus("lake")).isEmpty();
            assertThat(r.failures()).doesNotContainKey("lake");
        }
    }

    @Test
    void retryNowTriesAtOnceAndANewFileStartsTheCountAgain() throws Exception {
        UP.set(false);
        var props = new SourcesProperties(Map.of(), null, Map.of("stubbed", new SourcesProperties.PluginSettings(false, Map.of())), Duration.ofSeconds(1),
                null, Map.of("lake", cs()), null, "off", null, Duration.ZERO, null);
        RetryPolicy slow = new RetryPolicy(true, Duration.ofMinutes(5), 2.0, Duration.ofMinutes(5), 0, 3);
        try (SourceRegistry r = new SourceRegistry(List.of(new Stubbed()), props, new JsonCodec(), slow)) {
            assertThat(r.retryStatus("lake").orElseThrow().nextInSeconds(java.time.Instant.now())).isGreaterThan(200);
            assertThat(r.retryNow("lake")).isEqualTo(1);
            assertThat(r.retryStatus("lake").orElseThrow().attempt()).isEqualTo(2);
            UP.set(true);
            assertThat(r.retryNow("lake")).isEqualTo(1);
            assertThat(r.plugin("lake")).isPresent();
            assertThat(r.retryNow("lake")).isZero();                              // nothing failing any more
        }
    }

    @Test
    void aLostConnectionIsListedAndRecoveryClearsIt() throws Exception {
        UP.set(true);
        try (SourceRegistry r = registry(Map.of("lake", cs()))) {
            assertThat(r.plugin("lake")).isPresent();
            UP.set(false);
            await(() -> r.retryStatus("lake").isPresent());
            assertThat(r.retryStatus("lake").orElseThrow().lastError()).contains("connection lost");
            UP.set(true);
            await(() -> r.retryStatus("lake").isEmpty());
            assertThat(r.retryStatus("lake")).isEmpty();
            assertThat(r.plugin("lake")).isPresent();
        }
    }

    @Test
    void retryingCanBeSwitchedOff() throws Exception {
        UP.set(false);
        var props = new SourcesProperties(Map.of(), null, Map.of("stubbed", new SourcesProperties.PluginSettings(false, Map.of())), Duration.ofSeconds(1),
                null, Map.of("lake", cs()), null, "off", null, Duration.ZERO, null);
        RetryPolicy off = new RetryPolicy(false, Duration.ofMillis(100), 2.0, Duration.ofMillis(400), 0, 3);
        try (SourceRegistry r = new SourceRegistry(List.of(new Stubbed()), props, new JsonCodec(), off)) {
            UP.set(true);
            Thread.sleep(1500);
            assertThat(r.plugin("lake")).isEmpty();
            assertThat(r.retryStatus("lake").orElseThrow().nextAt()).isNull();
        }
    }
}
