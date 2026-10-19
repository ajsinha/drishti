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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Offline mode ({@code DRISHTI_OFFLINE}): a connector of an internet plugin never starts, so it can never reach the network; its kind is
 * served by the store that holds it, and the connector is reported OFFLINE rather than failed.
 */
class OfflineModeTest {

    /** Stands for the news or yfinance plugin: counts starts and fetches (each would be a call to the internet). */
    public static final class Web implements SourcePlugin {
        static final AtomicInteger STARTS = new AtomicInteger();
        static final AtomicInteger CALLS = new AtomicInteger();

        public PluginManifest manifest() {
            return new PluginManifest("web", "t", Set.of("quote"), SourceCapabilities.FETCH_ONLY);
        }

        public void start(SourceContext c) {
            STARTS.incrementAndGet();
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            CALLS.incrementAndGet();
            return Optional.empty();
        }

        public void close() {}
    }

    /** Stands for the Delta store built from the captured snapshot. */
    public static final class Store implements SourcePlugin {
        private final JsonCodec codec = new JsonCodec();

        public PluginManifest manifest() {
            return new PluginManifest("store", "t", Set.of("quote"), SourceCapabilities.FETCH_ONLY);
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            if (ref.id().equals("QUOTE-NONE")) {
                return Optional.empty();
            }
            return Optional.of(new EntityDocument(ref, codec.read("{\"QUOTE_ID\":\"" + ref.id() + "\",\"PRICE\":1.5}"),
                    new com.ash.drishti.api.Provenance("store", 1, java.time.Instant.now(), false)));
        }

        public void close() {}
    }

    private static SourceRegistry registry(OfflineMode offline, Map<String, SourcesProperties.PluginSettings> plain,
            Map<String, SourcesProperties.ConnectorSettings> connectors) {
        Web.STARTS.set(0);
        Web.CALLS.set(0);
        var props = new SourcesProperties(Map.of("quote", "yfinance"), null, plain, Duration.ofSeconds(1), null, connectors, null, "off", null,
                Duration.ZERO, null);
        return new SourceRegistry(List.of(new Web(), new Store()), props, new JsonCodec(), com.ash.drishti.common.RetryPolicy.defaults(), offline);
    }

    private static Map<String, SourcesProperties.ConnectorSettings> both() {
        var m = new java.util.LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        m.put("yfinance", new SourcesProperties.ConnectorSettings("web", true, List.of("quote"), Map.of()));
        m.put("quote-store", new SourcesProperties.ConnectorSettings("store", true, List.of("quote"), Map.of()));
        return m;
    }

    private static Map<String, SourcesProperties.PluginSettings> viaConnectorsOnly() {
        return Map.of("web", new SourcesProperties.PluginSettings(false, Map.of()), "store", new SourcesProperties.PluginSettings(false, Map.of()));
    }

    @Test
    void anInternetConnectorIsNotStartedAndTheStoreServesItsKind() throws Exception {
        try (SourceRegistry r = registry(new OfflineMode(true, Set.of("web")), viaConnectorsOnly(), both())) {
            assertThat(Web.STARTS.get()).as("the internet plugin never starts").isZero();
            assertThat(r.plugin("yfinance")).isEmpty();
            assertThat(r.connectorStatuses().get("yfinance").state()).isEqualTo("OFFLINE");
            assertThat(r.connectorStatuses().get("yfinance").problem()).contains("DRISHTI_OFFLINE=true");
            assertThat(r.connectorStatuses().get("quote-store").state()).isEqualTo("RUNNING");
            assertThat(r.failures()).isEmpty();
            var props = new SourcesProperties(Map.of("quote", "yfinance"), null, Map.of(), Duration.ofSeconds(1), null, Map.of());
            try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                EntityDocument d = new SourceRouter(r, props, exec).fetch(new EntityRef("quote", "QUOTE-AAPL")).get();
                assertThat(d.provenance().source()).isEqualTo("store");
            }
            assertThat(Web.CALLS.get()).as("no call to the internet").isZero();
            // a file saved while the server runs (Admin, Connectors) cannot start it either
            assertThat(r.apply("yfinance", new SourcesProperties.ConnectorSettings("web", true, List.of("quote"), Map.of())).state()).isEqualTo("OFFLINE");
            assertThat(Web.STARTS.get()).isZero();
        }
    }

    @Test
    void aPluginRunningAsItselfIsNotStartedEither() throws Exception {
        try (SourceRegistry r = registry(new OfflineMode(true, Set.of("web")), Map.of("web", new SourcesProperties.PluginSettings(true, Map.of())), Map.of())) {
            assertThat(Web.STARTS.get()).isZero();
            assertThat(r.plugin("web")).isEmpty();
            assertThat(r.plugin("store")).isPresent();
        }
    }

    @Test
    void withOfflineModeOffTheInternetConnectorStartsAsItsFileSays() throws Exception {
        try (SourceRegistry r = registry(OfflineMode.OFF, viaConnectorsOnly(), both())) {
            assertThat(Web.STARTS.get()).isEqualTo(1);
            assertThat(r.connectorStatuses().get("yfinance").state()).isEqualTo("RUNNING");
        }
    }

    @Test
    void withOfflineModeOffAnInternetConnectorSwitchedOffIsServedByTheStoreAsInOfflineMode() throws Exception {
        var m = both();
        m.put("yfinance", new SourcesProperties.ConnectorSettings("web", false, List.of("quote"), Map.of()));   // its shipped state
        try (SourceRegistry r = registry(new OfflineMode(false, Set.of("web")), viaConnectorsOnly(), m)) {
            assertThat(Web.STARTS.get()).isZero();
            assertThat(r.connectorStatuses().get("yfinance").state()).isEqualTo("DISABLED");
            assertThat(r.standIns("yfinance")).containsExactly("quote-store");
            assertThat(r.standIns("quote-store")).as("a running store stands in for nobody").isEmpty();
            var props = new SourcesProperties(Map.of("quote", "yfinance"), null, Map.of(), Duration.ofSeconds(1), null, Map.of());
            try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                SourceRouter router = new SourceRouter(r, props, exec);
                assertThat(router.fetch(new EntityRef("quote", "QUOTE-AAPL")).get().provenance().source()).isEqualTo("store");
                // what the store does not hold is "not found, the store was asked", not "the connector is disabled"
                assertThat(router.fetch(new EntityRef("quote", "QUOTE-NONE"))).failsWithin(Duration.ofSeconds(5))
                        .withThrowableOfType(java.util.concurrent.ExecutionException.class).havingCause()
                        .isInstanceOfSatisfying(com.ash.drishti.common.DrishtiException.class, e -> {
                            assertThat(e.errorCode()).isEqualTo(com.ash.drishti.common.ErrorCode.ENTITY_NOT_FOUND);
                            assertThat(e.getMessage()).contains("connector yfinance reaches the internet and is off").contains("served by quote-store");
                        });
            }
            assertThat(Web.CALLS.get()).isZero();
        }
    }

    @Test
    void aSwitchedOffConnectorOfAnOrdinaryPluginHasNoStandIns() throws Exception {
        var m = both();
        m.put("yfinance", new SourcesProperties.ConnectorSettings("web", false, List.of("quote"), Map.of()));
        try (SourceRegistry r = registry(new OfflineMode(false, Set.of("news")), viaConnectorsOnly(), m)) {
            assertThat(r.standIns("yfinance")).isEmpty();
        }
    }

    @Test
    void readFromTheConfiguration() {
        OfflineMode m = OfflineMode.of(Map.of("drishti.offline.enabled", "true")::get);
        assertThat(m.enabled()).isTrue();
        assertThat(m.plugins()).containsExactlyInAnyOrder("news", "yfinance", "feed");
        assertThat(m.blocks("YFinance")).isTrue();
        assertThat(m.blocks("delta")).isFalse();
        assertThat(OfflineMode.of(Map.<String, String>of()::get).reachesInternet("News")).as("known with offline mode off too").isTrue();
        assertThat(OfflineMode.of(Map.of("drishti.offline.enabled", "false")::get).blocks("news")).isFalse();
        assertThat(OfflineMode.of(Map.<String, String>of()::get).enabled()).isFalse();
        assertThat(OfflineMode.of(Map.of("drishti.offline.enabled", "on", "drishti.offline.plugins", "rest, news")::get).plugins())
                .containsExactlyInAnyOrder("rest", "news");
    }
}
