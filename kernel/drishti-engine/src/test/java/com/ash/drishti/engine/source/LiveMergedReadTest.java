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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.live.LiveIntake;
import com.ash.drishti.api.live.LiveMessage;
import com.ash.drishti.api.live.LivePosition;
import com.ash.drishti.api.live.LiveTransport;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.common.live.KindContracts;
import com.ash.drishti.common.live.LiveRules;
import com.ash.drishti.common.live.LiveSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The one read path's merge of the live state over the lake (LIVE_CONNECTORS_DESIGN.md section 7.1): one entity, Live, the newer of the
 * live row and the lake's by the kind's {@code live.order}; a live delete hides an older lake row; the rows a live set is handed when it
 * opens.
 */
class LiveMergedReadTest {

    /** A transport that hands the test's messages to the kernel's intake. */
    public static final class Transport implements SourcePlugin, LiveTransport {
        volatile LiveIntake intake;

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("trade-live", "t", Set.of("trade"), new SourceCapabilities(true, false, true, false));
        }

        @Override
        public void start(SourceContext c) {
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public void receive(LiveIntake i) {
            intake = i;
        }

        @Override
        public boolean replayable() {
            return true;
        }
    }

    /** A dated store holding one version per trade. */
    static final class Lake implements SourcePlugin {
        final Map<String, Integer> versions = new ConcurrentHashMap<>();

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("lake", "t", Set.of("trade"), new SourceCapabilities(false, false, false, true));
        }

        @Override
        public void start(SourceContext c) {
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.LATEST);
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            Integer v = versions.get(ref.id());
            return v == null ? Optional.empty() : Optional.of(new EntityDocument(ref, DataNode.of(Map.of("TRADE_ID", ref.id(), "V", v, "FROM", "lake")),
                    new Provenance("lake", 1, Instant.now(), false)));
        }
    }

    static final KindContracts TRADE = new KindContracts() {
        final LiveRules rules = LiveRules.parse(Map.of("kind", "trade", "class", "fundamental", "key", List.of("TRADE_ID"),
                "fields", List.of(Map.of("name", "TRADE_ID"), Map.of("name", "V"), Map.of("name", "FROM")), "live", Map.of("order", List.of("V"))), null).rules();

        @Override
        public LiveRules rules(String kind) {
            return rules;
        }

        @Override
        public Map<String, Object> contract(String kind) {
            return Map.of();
        }
    };

    private long offset;

    private void send(Transport t, String id, Integer v) {
        String body = v == null ? null : "{\"TRADE_ID\":\"" + id + "\",\"V\":" + v + ",\"FROM\":\"live\"}";
        t.intake.accept(new LiveMessage(id, body, new LivePosition("trades", 0, offset++), Instant.now()));
    }

    private SourceRouter router(Transport t, Lake lake) {
        LiveSource live = new LiveSource(t, TRADE, Set.of("trade"), null);
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofMillis(2000), null, null);
        SourceRegistry registry = new SourceRegistry(List.of(live, lake), props, new JsonCodec());
        registry.liveContracts(TRADE);
        return new SourceRouter(registry, props, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static String from(SourceRouter r, String id) {
        return r.fetch(EntityRef.of("trade", id)).join().data().get("FROM").asText();
    }

    @Test
    void theNewerOfTheLiveRowAndTheLakesAnswers() {
        Transport t = new Transport();
        Lake lake = new Lake();
        SourceRouter r = router(t, lake);
        lake.versions.put("T-1", 1);
        lake.versions.put("T-2", 3);
        send(t, "T-1", 2);                                           // booked after the extract: the live row is newer
        send(t, "T-2", 2);                                           // the load already has a newer version
        assertThat(from(r, "T-1")).isEqualTo("live");
        assertThat(from(r, "T-2")).isEqualTo("lake");
        lake.versions.put("T-1", 2);                                 // a load brings version 2: as new as the live row, the lake's answers
        assertThat(from(r, "T-1")).isEqualTo("lake");
    }

    @Test
    void aLiveDeleteHidesAnOlderLakeRowButNotANewerOne() {
        Transport t = new Transport();
        Lake lake = new Lake();
        SourceRouter r = router(t, lake);
        lake.versions.put("T-5", 1);
        send(t, "T-5", 2);
        send(t, "T-5", null);                                        // deleted after version 2
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "T-5")).join()).hasMessageContaining("was deleted by");
        lake.versions.put("T-5", 3);                                 // the source brought it back at version 3
        assertThat(from(r, "T-5")).isEqualTo("lake");
    }

    @Test
    void aLiveSetIsHandedTheRowsTheLiveStateHolds() {
        Transport t = new Transport();
        SourceRouter r = router(t, new Lake());
        send(t, "T-7", 1);
        send(t, "T-8", 1);
        send(t, "T-8", null);
        List<String> rows = new ArrayList<>();
        r.liveRows("trade", d -> rows.add(d.ref().id()));
        assertThat(rows).containsExactly("T-7");
    }
}
