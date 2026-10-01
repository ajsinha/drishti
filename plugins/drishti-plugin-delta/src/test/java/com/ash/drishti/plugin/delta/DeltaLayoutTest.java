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
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A table laid out as a pack declares (src/test/resources/lake-layout, written by tools/samplegen/layout.py: 40
 * trades a day over two days, sorted by id into files of 15 rows, row groups of 4, with mtm, book, nettingSet and
 * counterparty.id promoted beside the document). The fast paths (one file and row group per read, ids from the id
 * column, columns for searches and reverse lookups) answer exactly what the documents say.
 */
class DeltaLayoutTest {

    static Path root;
    static final List<String> PROMOTED = List.of("mtm", "book", "nettingSet", "counterparty.id");

    @BeforeAll
    static void copy() throws Exception {
        root = Files.createTempDirectory("drishti-lake-layout");
        Path src = Path.of("src/test/resources/lake-layout");
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
    }

    private static DeltaSourcePlugin plugin(boolean laidOut, String... extra) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "desk", "source-name", "lake"));
        if (laidOut) {
            s.put("layout.trade.columns", String.join(",", PROMOTED) + (extra.length > 0 ? "," + String.join(",", extra) : ""));
        }
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Test
    void oneEntityIsReadFromItsFileOnEachDate() throws Exception {
        DeltaSourcePlugin p = plugin(true);
        assertThat(p.fetch(EntityRef.of("trade", "T-033")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(13_100);
        assertThat(p.fetch(EntityRef.of("trade", "T-033"), AsOf.of(LocalDate.of(2026, 9, 29))).orElseThrow().data().get("mtm").asDouble())
                .isEqualTo(13_000);
        assertThat(p.fetch(EntityRef.of("trade", "T-001")).orElseThrow().data().get("counterparty").get("name").asText()).isEqualTo("Party 1");
        assertThat(p.fetch(EntityRef.of("trade", "T-999"))).isEmpty();
        assertThat(p.search("trade", "T-03", 50)).hasSize(10);                 // ids from the id column alone
        assertThat(p.cacheStats()).containsEntry("partitions", 0L);           // no whole day was loaded
    }

    @Test
    void columnsAnswerWhatTheDocumentsSay() throws Exception {
        DeltaSourcePlugin p = plugin(true);
        assertThat(p.columnar("trade")).containsExactlyInAnyOrderElementsOf(PROMOTED);
        ColumnSet c = p.columns("trade", List.of("mtm", "book", "counterparty.id"), AsOf.LATEST).orElseThrow();
        assertThat(c.size()).isEqualTo(40);
        assertThat(c.businessDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            Object mtm = c.value("mtm", i);
            if (doc.get("mtm").isMissing()) {
                assertThat(mtm).isNull();                                        // T-007 has none
            } else {
                assertThat((Double) mtm).isEqualTo(doc.get("mtm").asDouble());
            }
            assertThat(c.value("book", i)).isEqualTo(doc.get("book").asText());
            assertThat(c.value("counterparty.id", i)).isEqualTo(doc.get("counterparty").get("id").asText());
        }
        ColumnSet before = p.columns("trade", List.of("mtm"), AsOf.of(LocalDate.of(2026, 9, 29))).orElseThrow();
        int first = java.util.Arrays.asList(before.ids()).indexOf("T-001");            // rows come in file order
        assertThat(before.value("mtm", first)).isEqualTo(-19_000.0);
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();      // not a column: documents are read
    }

    @Test
    void reverseLookupsFromColumnsMatchTheDocuments() throws Exception {
        DeltaSourcePlugin laidOut = plugin(true);
        DeltaSourcePlugin documents = plugin(false);
        for (String target : List.of("NS-2", "BOOK-A", "CP-1")) {
            List<EntityRef> fast = laidOut.reverse(EntityRef.of("x", target), "trade", AsOf.LATEST);
            assertThat(fast).isNotEmpty().containsExactlyInAnyOrderElementsOf(documents.reverse(EntityRef.of("x", target), "trade", AsOf.LATEST));
        }
        assertThat(laidOut.reverse(EntityRef.of("netting-set", "NS-2"), "trade", AsOf.LATEST)).hasSize(15);
        assertThat(documents.columnar("trade")).isEmpty();
    }

    @Test
    void healthSaysWhenATableIsNotLaidOutAsDeclared() throws Exception {
        assertThat(plugin(true).health()).isEqualTo("UP");
        assertThat(plugin(true, "notional").health()).contains("not laid out as the pack declares: trade (4 of 5 columns)");
    }
}
