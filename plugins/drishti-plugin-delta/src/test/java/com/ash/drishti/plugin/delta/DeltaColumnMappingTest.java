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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Column mapping (the Delta protocol's {@code columnMapping}, mode {@code name}, as Databricks writes it): the data files name every
 * column {@code col-<uuid>}, the add actions' {@code partitionValues} and statistics use those names, and partition folders are random
 * prefixes. Each {@code *-colmap} fixture is the plain fixture's rows written again by delta-rs 1.6.6 with
 * {@code delta.columnMapping.mode=name}; every read path (documents, the id map and type-ahead, columns with a partition column, reverse
 * lookups, vectors with two loads and partition texts, row estimates) answers exactly what it answers on the plain table, with both
 * engines.
 */
class DeltaColumnMappingTest {

    static Path copy(String fixture) throws Exception {
        Path root = Files.createTempDirectory("drishti-colmap");
        Path src = Path.of("src/test/resources/" + fixture);
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
        return root;
    }

    static DeltaSourcePlugin plugin(String fixture, String domain, String engine, String... settings) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", copy(fixture).toString(), "domain", domain, "source-name", "lake", "engine", engine));
        for (int i = 0; i < settings.length; i += 2) {
            s.put(settings[i], settings[i + 1]);
        }
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    /** What every path answers on a table, as plain text, so the plain and the mapped table compare in one assertion. */
    static Map<String, Object> answers(DeltaSourcePlugin p, String kind, List<String> columns, List<LocalDate> days) throws Exception {
        Map<String, Object> out = new TreeMap<>();
        for (LocalDate d : days) {
            ColumnSet c = p.columns(kind, columns, AsOf.of(d)).orElse(null);
            List<String> rows = new ArrayList<>();
            if (c != null) {
                for (int i = 0; i < c.size(); i++) {
                    StringBuilder b = new StringBuilder(c.ids()[i]);
                    for (String col : columns) {
                        b.append('|').append(c.value(col, i));
                    }
                    rows.add(b.toString());
                }
                java.util.Collections.sort(rows);
            }
            out.put(d + " columns", rows);
            List<String> docs = new ArrayList<>();
            for (var h : p.search(kind, "", 500)) {
                docs.add(h.ref().id() + "=" + p.fetch(h.ref(), AsOf.of(d)).map(e -> e.data().toString()).orElse("-"));
            }
            java.util.Collections.sort(docs);
            out.put(d + " documents", docs);
            out.put(d + " estimate", String.valueOf(p.rowEstimate(kind, d)));
        }
        out.put("columnar", new java.util.TreeSet<>(p.columnar(kind)));
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "hadoop"})
    void aColumnMappedTableReadsAsThePlainOne(String engine) throws Exception {
        List<LocalDate> days = List.of(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30));
        String cols = "mtm,book,nettingSet,counterparty.id";
        DeltaSourcePlugin plain = plugin("lake-layout", "desk", engine, "layout.trade.columns", cols);
        DeltaSourcePlugin mapped = plugin("lake-layout-colmap", "desk", engine, "layout.trade.columns", cols);
        try {
            Map<String, Object> want = answers(plain, "trade", List.of("mtm", "book", "counterparty.id"), days);
            assertThat(((List<?>) want.get("2026-09-30 columns"))).hasSize(40);
            assertThat(answers(mapped, "trade", List.of("mtm", "book", "counterparty.id"), days)).isEqualTo(want);
            assertThat(mapped.reverse(EntityRef.of("book", "BOOK-A"), "trade", AsOf.of(days.get(1))))
                    .isEqualTo(plain.reverse(EntityRef.of("book", "BOOK-A"), "trade", AsOf.of(days.get(1)))).isNotEmpty();
            assertThat(mapped.search("trade", "T-03", 50)).extracting(h -> h.ref().id())
                    .isEqualTo(plain.search("trade", "T-03", 50).stream().map(h -> h.ref().id()).toList()).hasSize(10);
        } finally {
            plain.close();
            mapped.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "hadoop"})
    void aMappedPartitionColumnReadsItsValueFromTheLog(String engine) throws Exception {
        List<LocalDate> days = List.of(LocalDate.of(2026, 9, 30));
        DeltaSourcePlugin plain = plugin("lake-partitioned", "desk", engine, "layout.trade.columns", "PRODUCT_TYPE,CURRENCY");
        DeltaSourcePlugin mapped = plugin("lake-partitioned-colmap", "desk", engine, "layout.trade.columns", "PRODUCT_TYPE,CURRENCY");
        try {
            Map<String, Object> want = answers(plain, "trade", List.of("PRODUCT_TYPE", "CURRENCY"), days);
            assertThat(want.get("2026-09-30 columns").toString()).contains("T1|ComSwap", "T3|Cash");
            assertThat(answers(mapped, "trade", List.of("PRODUCT_TYPE", "CURRENCY"), days)).isEqualTo(want);
        } finally {
            plain.close();
            mapped.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "hadoop"})
    void mappedVectorsReadAsThePlainOnes(String engine) throws Exception {
        LocalDate day = LocalDate.of(2026, 9, 30);
        String[] s = {"layout.pnl-vector.columns", "DESK,TT_BOOK", "layout.pnl-vector.vectors", "PNL"};
        DeltaSourcePlugin plain = plugin("lake-vectors", "risk", engine, s);
        DeltaSourcePlugin mapped = plugin("lake-vectors-colmap", "risk", engine, s);
        try {
            Map<String, String> want = vectors(plain, day);
            assertThat(want).hasSize(4).containsEntry("T3", "[100.0, -0.0, 0.25]|FX|B0");      // the second load's copy wins
            assertThat(vectors(mapped, day)).isEqualTo(want);
            assertThat(mapped.fetch(EntityRef.of("pnl-vector", "T2"), AsOf.of(day)).orElseThrow().data().toString())
                    .isEqualTo(plain.fetch(EntityRef.of("pnl-vector", "T2"), AsOf.of(day)).orElseThrow().data().toString());
        } finally {
            plain.close();
            mapped.close();
        }
    }

    private static Map<String, String> vectors(DeltaSourcePlugin p, LocalDate day) throws Exception {
        Map<String, String> got = new TreeMap<>();
        boolean read = p.vectors("pnl-vector", "PNL", List.of("DESK", "TT_BOOK"), AsOf.of(day), (id, values, len, texts) ->
                got.put(id, java.util.Arrays.toString(java.util.Arrays.copyOf(values, Math.max(len, 0))) + "|" + String.join("|", texts)));
        assertThat(read).isTrue();
        return got;
    }
}
