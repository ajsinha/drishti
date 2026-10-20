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
package com.ash.drishti.plugin.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Equality with Delta Lake: the Delta connector's sample lake is read through the Delta connector, every document of every
 * day is loaded into Cassandra through the loader, and then every read path answers the same from both: one entity on every
 * date (before, on and after each day), type-ahead, a day's documents, reverse lookups, the inventory and the latest day,
 * and a day's columns equal the values in Delta's documents. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class CassandraDeltaEqualityTest {

    private static final List<String> KINDS = List.of("trade", "counterparty");
    private static final com.ash.drishti.common.JsonCodec CODEC = new com.ash.drishti.common.JsonCodec();
    private static Path lake;
    private static DeltaSourcePlugin delta;
    private static CassandraSourcePlugin cassandra;

    @BeforeAll
    static void bothStores() throws Exception {
        lake = Files.createTempDirectory("drishti-cassandra-eq");          // under target/tmp (surefire's java.io.tmpdir)
        Path src = Path.of("../drishti-plugin-delta/src/test/resources/lake");
        try (var files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = lake.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        delta = new DeltaSourcePlugin();
        delta.start(DatedSourceContract.context(Map.of("root", lake.toString(), "domain", "desk", "mode.counterparty", "effective", "source-name", "lake")));
        // every document Delta holds, day by day, as the loader's JSON lines
        List<String> lines = new ArrayList<>();
        for (String kind : KINDS) {
            for (LocalDate day : delta.inventory(kind, 100).keySet()) {
                for (EntityDocument d : dayDocuments(kind, day)) {
                    lines.add(CassandraNode.line("desk", new DatedSourceContract.Row(kind, d.ref().id(), day, CODEC.toJson(d.data()))));
                }
            }
        }
        String node = CassandraNode.cassandra();
        CassandraNode.load(CassandraNode.args(node, "eq_desk"), lines);
        cassandra = new CassandraSourcePlugin();
        cassandra.start(DatedSourceContract.context(CassandraNode.settings(node, "eq_desk", "eq-cassandra")));
    }

    /** The entities whose own row is dated {@code day} in Delta (an effective kind's documents are read entity by entity). */
    private static List<EntityDocument> dayDocuments(String kind, LocalDate day) throws Exception {
        List<EntityDocument> out = new ArrayList<>();
        if (!delta.documents(kind, AsOf.of(day), out::add)) {
            for (var hit : delta.search(kind, "", 10_000)) {
                delta.fetch(hit.ref(), AsOf.of(day)).filter(d -> day.equals(d.provenance().businessDate())).ifPresent(out::add);
            }
        }
        return out;
    }

    @AfterAll
    static void close() throws Exception {
        if (cassandra != null) {
            cassandra.close();
        }
        if (delta != null) {
            delta.close();
        }
        if (lake != null) {
            try (var w = Files.walk(lake)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static List<LocalDate> dates() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = DatedSourceContract.D1.minusDays(2); !d.isAfter(DatedSourceContract.D3.plusDays(3)); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    private static Optional<String> read(SourcePlugin p, String kind, String id, LocalDate day) throws Exception {
        return p.fetch(EntityRef.of(kind, id), AsOf.of(day)).map(d -> d.provenance().businessDate() + " " + d.data());
    }

    @Test
    void oneEntityOnEveryDateIsTheSame() throws Exception {
        for (LocalDate day : dates()) {
            for (String id : List.of("T-1", "T-2", "T-3", "NOPE")) {
                assertThat(read(cassandra, "trade", id, day)).as("trade %s on %s", id, day).isEqualTo(read(delta, "trade", id, day));
            }
            assertThat(read(cassandra, "counterparty", "CP-X", day)).as("counterparty on %s", day).isEqualTo(read(delta, "counterparty", "CP-X", day));
        }
        assertThat(cassandra.fetch(EntityRef.of("trade", "T-1")).map(d -> d.data().toString()))
                .isEqualTo(delta.fetch(EntityRef.of("trade", "T-1")).map(d -> d.data().toString()));
    }

    @Test
    void typeAheadDocumentsInventoryAndLatestDayAreTheSame() throws Exception {
        for (String kind : KINDS) {
            for (String text : List.of("", "t-", "cp", "-1", "zzz")) {
                assertThat(new TreeSet<>(cassandra.search(kind, text, 100).stream().map(h -> h.ref().id()).toList())).as("%s %s", kind, text)
                        .isEqualTo(new TreeSet<>(delta.search(kind, text, 100).stream().map(h -> h.ref().id()).toList()));
            }
            assertThat(cassandra.inventory(kind, 10)).as(kind).isEqualTo(delta.inventory(kind, 10));
            assertThat(cassandra.latestDate(kind)).as(kind).isEqualTo(delta.latestDate(kind));
        }
        for (LocalDate day : dates()) {
            assertThat(docs(cassandra, day)).as("trade documents on %s", day).isEqualTo(docs(delta, day));
        }
    }

    private static Map<String, String> docs(SourcePlugin p, LocalDate day) throws Exception {
        Map<String, String> out = new TreeMap<>();
        p.documents("trade", AsOf.of(day), d -> out.put(d.ref().id(), d.provenance().businessDate() + " " + d.data()));
        return out;
    }

    @Test
    void reverseLookupsAreTheSame() throws Exception {
        for (LocalDate day : dates()) {
            for (String ns : List.of("NS-A", "NS-B", "NS-C")) {
                assertThat(cassandra.reverse(EntityRef.of("netting-set", ns), "trade", AsOf.of(day))).as("%s on %s", ns, day)
                        .isEqualTo(delta.reverse(EntityRef.of("netting-set", ns), "trade", AsOf.of(day)));
            }
        }
    }

    @Test
    void aDaysColumnsEqualTheValuesInDeltasDocuments() throws Exception {
        for (LocalDate day : dates()) {
            Map<String, String> fromDelta = new TreeMap<>();
            delta.documents("trade", AsOf.of(day), d -> fromDelta.put(d.ref().id(), d.data().get("mtm").asDouble() + " " + d.data().get("nettingSet").asText()));
            Optional<ColumnSet> c = cassandra.columns("trade", List.of("mtm", "nettingSet"), AsOf.of(day));
            Map<String, String> fromCassandra = new TreeMap<>();
            c.ifPresent(cs -> {
                for (int i = 0; i < cs.size(); i++) {
                    fromCassandra.put(cs.ids()[i], cs.value("mtm", i) + " " + cs.value("nettingSet", i));
                }
            });
            assertThat(fromCassandra).as("trade columns on %s", day).isEqualTo(fromDelta);
        }
    }
}
