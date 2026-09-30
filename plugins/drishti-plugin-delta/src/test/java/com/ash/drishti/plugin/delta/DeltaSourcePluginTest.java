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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reads the fixture lake (written by delta-rs): dates, snapshot and effective tables, time travel, reverse, search. */
class DeltaSourcePluginTest {

    static final Instant T0 = Instant.parse("2026-09-30T20:00:00Z");
    static Path root;
    static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    static DeltaSourcePlugin plugin;

    /**
     * Delta time travel for these tables resolves instants against the commit files' modification times, which a
     * checkout or copy rewrites. The test works on a copy with pinned times: version 0 at T0, version 1 ten
     * seconds later.
     */
    @BeforeAll
    static void start() throws Exception {
        root = Files.createTempDirectory("drishti-lake");
        Path src = Path.of("src/test/resources/lake");
        try (var files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        for (String table : new String[] {"trade", "counterparty"}) {
            try (var logs = Files.list(root.resolve("desk").resolve(table).resolve("_delta_log"))) {
                for (Path log : logs.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                    long version = Long.parseLong(log.getFileName().toString().replace(".json", ""));
                    Files.setLastModifiedTime(log, java.nio.file.attribute.FileTime.from(T0.plusSeconds(10 * version)));
                }
            }
        }
        JsonCodec codec = new JsonCodec();
        plugin = new DeltaSourcePlugin();
        plugin.start(new SourceContext() {
            public Map<String, String> settings() {
                return Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective", "source-name", "lake");
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return SCHEDULER;
            }
        });
    }

    @AfterAll
    static void stop() {
        SCHEDULER.shutdownNow();
    }

    static Optional<EntityDocument> at(String kind, String id, String date) throws Exception {
        return plugin.fetch(EntityRef.of(kind, id), AsOf.of(LocalDate.parse(date)));
    }

    @Test
    void discoversTablesAndServesTheirKinds() {
        assertThat(plugin.manifest().kinds()).containsExactlyInAnyOrder("trade", "counterparty");
        assertThat(plugin.manifest().capabilities().dated()).isTrue();
        assertThat(plugin.health()).isEqualTo("UP");
    }

    @Test
    void eachBusinessDateReadsItsOwnPartition() throws Exception {
        EntityDocument d = at("trade", "T-1", "2026-09-29").orElseThrow();
        assertThat(d.data().get("mtm").asDouble()).isEqualTo(110);
        assertThat(d.provenance().businessDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(at("trade", "T-1", "2026-09-28").orElseThrow().data().get("mtm").asDouble()).isEqualTo(100);
        assertThat(at("trade", "T-1", "2026-09-30").orElseThrow().data().get("restated").asBoolean()).isTrue();
    }

    @Test
    void aSnapshotTableTakesTheNewestPartitionOnOrBeforeTheDate() throws Exception {
        assertThat(at("trade", "T-1", "2026-10-03").orElseThrow().provenance().businessDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(at("trade", "T-3", "2026-09-28")).isPresent();
        assertThat(at("trade", "T-3", "2026-09-30")).isEmpty();          // gone from the latest snapshot
        assertThat(at("trade", "T-1", "2026-09-01")).isEmpty();          // before any data
    }

    @Test
    void anEffectiveTableCarriesTheLastChangeForward() throws Exception {
        assertThat(at("counterparty", "CP-X", "2026-09-29").orElseThrow().data().get("rating").asText()).isEqualTo("A");
        assertThat(at("counterparty", "CP-X", "2026-09-30").orElseThrow().data().get("rating").asText()).isEqualTo("A-");
    }

    @Test
    void knownAtReadsTheTableBeforeALaterCorrection() throws Exception {
        EntityDocument before = plugin.fetch(EntityRef.of("trade", "T-1"), new AsOf(LocalDate.of(2026, 9, 30), T0.plusSeconds(5))).orElseThrow();
        assertThat(before.data().get("mtm").asDouble()).isEqualTo(120);
        assertThat(before.data().get("restated").isMissing()).isTrue();
        assertThat(before.provenance().generation()).isZero();
    }

    @Test
    void reverseLookupAndSearchFollowTheDate() {
        assertThat(plugin.reverse(EntityRef.of("netting-set", "NS-A"), "trade", AsOf.of(LocalDate.of(2026, 9, 29))))
                .containsExactly(EntityRef.of("trade", "T-1"), EntityRef.of("trade", "T-2"));
        assertThat(plugin.reverse(EntityRef.of("netting-set", "NS-B"), "trade", AsOf.of(LocalDate.of(2026, 9, 30)))).isEmpty();
        assertThat(plugin.search("trade", "t-", 10)).extracting(h -> h.ref().id()).contains("T-1", "T-2");
    }
}
