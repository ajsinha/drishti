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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.lakeconfig.Lakes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Demo and real data never mix (docs/guides/DATA_LAKE_GUIDE.md section 6): once a kind's connector reads a lake other than the demo lake,
 * the packs' samples (the {@code demo} plugin, live and asked first on a live read) no longer answer for that kind, by id or in a search;
 * a server whose connectors all read the demo lake is unchanged. Synthetic documents and lake files in a temporary folder only.
 */
class DemoAndRealNeverMixTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    @TempDir Path tmp;

    /** The packs' samples: any kind, live, holding the demo trade 1000001. */
    public static final class Samples implements SourcePlugin {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("demo", "t", Set.of(), new SourceCapabilities(true, true, true));
        }

        @Override
        public void start(SourceContext c) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return "1000001".equals(ref.id())
                    ? Optional.of(new EntityDocument(ref, DataNode.of(Map.of("SOURCE", "samples")), new Provenance("demo", 1, Instant.now(), false, null)))
                    : Optional.empty();
        }

        @Override
        public List<EntityHit> search(String kind, String text, int limit) {
            return List.of(new EntityHit(EntityRef.of("trade", "1000001"), "1000001", "samples"));
        }
    }

    /** A Delta connector's stand-in: the ids of its {@code ids} setting, on the business day. */
    public static final class Lake implements SourcePlugin {
        private List<String> ids = List.of();

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("delta", "t", Set.of("trade"), new SourceCapabilities(false, false, true, true));
        }

        @Override
        public void start(SourceContext c) {
            ids = Arrays.stream(c.setting("ids", "").split(",")).filter(s -> !s.isBlank()).toList();
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.of(DAY));
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            return ids.contains(ref.id())
                    ? Optional.of(new EntityDocument(ref, DataNode.of(Map.of("SOURCE", "lake")), new Provenance("trade-endur", 1, Instant.now(), false, DAY)))
                    : Optional.empty();
        }

        @Override
        public List<EntityHit> search(String kind, String text, int limit) {
            return ids.stream().map(i -> new EntityHit(EntityRef.of("trade", i), i, "lake")).toList();
        }

        @Override
        public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
            return search(kind, text, limit);
        }
    }

    @BeforeEach
    void lakes() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("config/lakes"));
        for (String lake : List.of("demo", "risklake")) {
            Files.writeString(dir.resolve(lake + ".yaml"), "lake: " + lake + "\nformat: delta\nroot: " + tmp.resolve("delta/" + lake)
                    + "\ntables:\n  trading.trade_endur:\n    kind: trade\n");
        }
        System.setProperty(Lakes.PROPERTY, dir.toString());
        Lakes.useProperties(k -> null);
    }

    @AfterEach
    void reset() {
        System.clearProperty(Lakes.PROPERTY);
        Lakes.useProperties(null);
    }

    private static SourceRouter router(String lake, String ids) {
        var cfg = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        cfg.put("trade-endur", new SourcesProperties.ConnectorSettings("delta", true, List.of("trade"),
                Map.of("lake", lake, "lake-tables", "trading.trade_endur", "ids", ids)));
        cfg.put("demo", new SourcesProperties.ConnectorSettings("demo", true, List.of(), Map.of()));
        var props = new SourcesProperties(Map.of("trade", "trade-endur"), "demo", Map.of(), Duration.ofSeconds(2), null, cfg);
        return new SourceRouter(new SourceRegistry(List.of(new Lake(), new Samples()), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    private static boolean found(SourceRouter r, String id, AsOf asOf) {
        return r.fetch(EntityRef.of("trade", id), Duration.ofSeconds(2), asOf).handle((d, e) -> d != null).join();
    }

    @Test
    void onTheDemoLakeTheSamplesStillAnswer() {
        SourceRouter r = router("demo", "2000001");
        assertThat(r.ownLake("trade")).isNull();
        assertThat(found(r, "1000001", new AsOf(DAY, null, true))).as("a single-lake (demo) server: unchanged").isTrue();
        assertThat(r.candidates("trade").stream().map(p -> p.manifest().name())).contains("demo");
    }

    @Test
    void aConnectorSwitchedToAnotherLakeShowsNoDemoEntityOfItsKind() {
        SourceRouter r = router("risklake", "7000001");
        assertThat(r.ownLake("trade")).isEqualTo("risklake");
        assertThat(r.candidates("trade").stream().map(p -> p.manifest().name())).doesNotContain("demo");
        assertThat(found(r, "1000001", new AsOf(DAY, null, true))).as("the demo trade: not served once trades read risklake").isFalse();
        assertThat(found(r, "1000001", AsOf.of(DAY))).as("nor on a picked date").isFalse();
        assertThat(found(r, "7000001", new AsOf(DAY, null, true))).as("your trade").isTrue();
        assertThat(r.list("trade", "", 10, Duration.ofSeconds(2), AsOf.of(DAY), new SourceFailures()).hits().stream().map(h -> h.ref().id()))
                .containsExactly("7000001");
        assertThat(r.list(null, "", 10, Duration.ofSeconds(2), AsOf.of(DAY), new SourceFailures()).hits().stream().map(h -> h.ref().id()))
                .as("a search over every kind: no demo trade either").doesNotContain("1000001");
        assertThat(r.ownLake("country")).as("other kinds keep their samples").isNull();
    }
}
