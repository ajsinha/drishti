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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Delta Lake connector over the fixture lake (written by delta-rs with the contract's rows), plus what only a
 * versioned store can do: read data as known before a later correction. Run with the native engine here and with
 * Hadoop's in {@link DeltaSourcePluginHadoopTest}.
 */
class DeltaSourcePluginTest extends DatedSourceContract {

    static final Instant T0 = Instant.parse("2026-09-30T20:00:00Z");
    private static final Map<String, DeltaSourcePlugin> PLUGINS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The Delta engine these tests read with. */
    protected String engine() {
        return "native";
    }

    /**
     * Delta time travel for these tables resolves instants against the commit files' modification times, which a
     * checkout or copy rewrites. The test works on a copy with pinned times: version 0 at T0, version 1 ten
     * seconds later.
     */
    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        DeltaSourcePlugin cached = PLUGINS.get(engine());
        if (cached != null) {
            return cached;
        }
        Path root = Files.createTempDirectory("drishti-lake");
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
                    Files.setLastModifiedTime(log, FileTime.from(T0.plusSeconds(10 * version)));
                }
            }
        }
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective", "source-name", "lake",
                "engine", engine())));
        PLUGINS.put(engine(), p);
        return p;
    }

    @Test
    void knownAtReadsTheTableBeforeALaterCorrection() throws Exception {
        EntityDocument before = plugin().fetch(EntityRef.of("trade", "T-1"), new AsOf(LocalDate.of(2026, 9, 30), T0.plusSeconds(5))).orElseThrow();
        assertThat(before.data().get("mtm").asDouble()).isEqualTo(120);
        assertThat(before.data().get("restated").isMissing()).isTrue();
        assertThat(before.provenance().generation()).isZero();
    }

    @Test
    void aWholeDayIsReadInOnePassAndAgreesWithReadingEachEntity() throws Exception {
        LocalDate d = LocalDate.of(2026, 9, 30);
        Map<String, EntityDocument> all = new java.util.LinkedHashMap<>();
        assertThat(plugin().documents("trade", AsOf.of(d), doc -> all.put(doc.ref().id(), doc))).isTrue();
        assertThat(all).isNotEmpty();
        assertThat(all.size()).isEqualTo(plugin().inventory("trade", 10).get(d).intValue());      // every row of the date, once
        for (EntityDocument doc : all.values()) {
            EntityDocument one = plugin().fetch(doc.ref(), AsOf.of(d)).orElseThrow();
            assertThat(doc.data().unwrap()).isEqualTo(one.data().unwrap());
            assertThat(doc.provenance().businessDate()).isEqualTo(d);
        }
        assertThat(plugin().documents("counterparty", AsOf.of(d), doc -> { })).isFalse();          // an effective table: entity by entity
        assertThat(plugin().documents("no-such-kind", AsOf.of(d), doc -> { })).isFalse();
    }

    @Test
    void inventoryListsTheNewestDatesWithTheirRowCountsAndSaysNothingForAnUnknownKind() throws Exception {
        Map<LocalDate, Long> inv = plugin().inventory("trade", 5);
        assertThat(inv).isNotEmpty();
        assertThat(new java.util.ArrayList<>(inv.keySet())).isSortedAccordingTo(java.util.Comparator.reverseOrder());      // newest first
        assertThat(inv.values()).allMatch(n -> n > 0);
        assertThat(plugin().inventory("trade", 1)).hasSize(1);
        assertThat(plugin().inventory("no-such-kind", 3)).isEmpty();
    }

    @Test
    void knownAtBeforeTheTableExistedFindsNothingAndAfterTheLastCommitReadsTheLatest() throws Exception {
        EntityRef t1 = EntityRef.of("trade", "T-1");
        LocalDate d = LocalDate.of(2026, 9, 30);
        assertThat(plugin().fetch(t1, new AsOf(d, T0.minusSeconds(86_400)))).isEmpty();          // nothing was known yet
        EntityDocument later = plugin().fetch(t1, new AsOf(d, java.time.Instant.parse("2099-01-01T00:00:00Z"))).orElseThrow();
        assertThat(later.data().get("mtm").asDouble()).isEqualTo(125);                            // as known now
    }

    private static void copy(Path from, Path to) throws java.io.IOException {
        try (var files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(f, t);
                }
            }
        }
    }

    @Test
    void aTableAddedToTheLakeIsServedFromTheNextReindexWithoutARestart() throws Exception {
        Path root = Files.createTempDirectory("drishti-lake-grow");
        Path src = Path.of("src/test/resources/lake/desk");
        copy(src.resolve("trade"), root.resolve("desk").resolve("trade"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "source-name", "growing", "engine", engine())));
        try {
            assertThat(p.manifest().kinds()).containsExactly("trade");
            copy(src.resolve("counterparty"), root.resolve("desk").resolve("counterparty"));     // loaded while running
            p.reindex();
            assertThat(p.manifest().kinds()).containsExactlyInAnyOrder("trade", "counterparty");
            assertThat(p.search("counterparty", "", 10)).isNotEmpty();
        } finally {
            p.close();
        }
    }

    @Test
    void aDatesDataVersionMovesOnlyWhenACommitChangesThatDatesFiles() throws Exception {
        Path root = Files.createTempDirectory("drishti-lake-version");
        Path table = root.resolve("desk").resolve("trade");
        copy(Path.of("src/test/resources/lake/desk/trade"), table);
        Path correction = table.resolve("_delta_log").resolve("00000000000000000001.json");
        byte[] restate = Files.readAllBytes(correction);
        Files.delete(correction);                                                  // the table before the correction of 2026-09-30
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "source-name", "versioned", "refresh-seconds", "1", "warm-dates", "0",
                "engine", engine())));
        try {
            LocalDate d28 = LocalDate.of(2026, 9, 28);
            LocalDate d30 = LocalDate.of(2026, 9, 30);
            String table0 = p.dataVersion("trade").orElseThrow();
            String day28 = p.dataVersion("trade", d28).orElseThrow();
            String day30 = p.dataVersion("trade", d30).orElseThrow();
            assertThat(p.dataVersion("trade", LocalDate.of(2026, 1, 1))).contains("none");
            Files.write(correction, restate);                                      // the correction lands (a rebuild, a restatement)
            Thread.sleep(1_200);                                                   // past refresh-seconds: the latest version is read again
            assertThat(p.dataVersion("trade").orElseThrow()).isNotEqualTo(table0);
            assertThat(p.dataVersion("trade", d30).orElseThrow()).isNotEqualTo(day30);   // its files changed: stored layers of the date are stale
            assertThat(p.dataVersion("trade", d28)).contains(day28);                     // untouched: kept
            assertThat(p.dataVersion("no-such-kind")).isEmpty();
        } finally {
            p.close();
        }
    }

    /**
     * QA2-23: a stopped (disabled) connector is quiet. Its reindex was still scheduled after the close and read through the closed
     * loaders: "trade log cannot be read: RejectedExecutionException" per stopped connector.
     */
    @Test
    void aClosedConnectorStopsReindexingAndRecordsNoProblem() throws Exception {
        Path root = Files.createTempDirectory("drishti-lake-closed");
        copy(Path.of("src/test/resources/lake/desk/trade"), root.resolve("desk").resolve("trade"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "source-name", "stopped", "warm-dates", "0", "engine", engine())));
        assertThat(p.reindexScheduled()).isTrue();
        p.close();
        assertThat(p.reindexScheduled()).isFalse();
        copy(Path.of("src/test/resources/lake/desk/counterparty"), root.resolve("desk").resolve("counterparty"));   // a log not read yet
        p.reindex();                                                                // a run that had already started when it was closed
        assertThat(p.listingProblem("counterparty")).isEmpty();
        assertThat(p.listingProblem("trade")).isEmpty();
    }

    /** Drishti*net's replicas: every server that reads this table names it the same, by the id in its log. */
    @Test
    void theTableIsNamedByTheIdInItsLog() throws Exception {
        var p = plugin();
        String id = p.tableIdentity("trade").orElseThrow();
        assertThat(id).startsWith("delta:").hasSizeGreaterThan(10);
        assertThat(plugin().tableIdentity("trade")).contains(id);               // another reader of the same table
        assertThat(p.tableIdentity("no-such-kind")).isEmpty();
    }

    @Test
    void healthAndCacheStatsNameTheEngine() throws Exception {
        assertThat(plugin().health()).isEqualTo("UP (engine: " + engine() + ")");
        assertThat(plugin().cacheStats()).containsEntry("engine", engine());
    }
}
