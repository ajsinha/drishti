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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The native engine and Hadoop's answer every question the same on the fixture lakes: documents on every date, id
 * maps, column sets, reverse lookups, searches and time travel.
 */
class DeltaEnginesAgreeTest {

    static final List<String> PROMOTED = List.of("mtm", "book", "nettingSet", "counterparty.id");

    private static DeltaSourcePlugin start(Path root, String engine, Map<String, String> extra) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "desk", "engine", engine, "source-name", "lake"));
        s.putAll(extra);
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    /** Everything a plugin answers for a lake, as plain values. */
    private static Map<String, Object> answers(DeltaSourcePlugin p, List<LocalDate> dates, List<Instant> knownAt) throws Exception {
        Map<String, Object> out = new java.util.TreeMap<>();
        for (String kind : p.manifest().kinds().stream().sorted().toList()) {
            List<String> ids = p.search(kind, "", 10_000).stream().map(h -> h.ref().id()).sorted().toList();
            out.put(kind + " ids", ids);
            for (String id : ids) {
                for (LocalDate d : dates) {
                    for (Instant k : knownAt) {
                        Optional<EntityDocument> doc = p.fetch(EntityRef.of(kind, id), new AsOf(d, k));
                        out.put(kind + "/" + id + "@" + d + "/" + k, doc.map(e -> List.of(e.data(), String.valueOf(e.provenance().businessDate()),
                                e.provenance().generation())).orElse(null));
                    }
                }
                out.put(kind + " reverse " + id, p.reverse(EntityRef.of("x", id), null, AsOf.LATEST));
            }
            if (!p.columnar(kind).isEmpty()) {
                for (LocalDate d : dates) {
                    Optional<ColumnSet> c = p.columns(kind, p.columnar(kind), AsOf.of(d));
                    out.put(kind + " columns " + d, c.map(DeltaEnginesAgreeTest::plain).orElse(null));
                }
            }
        }
        return out;
    }

    private static List<Object> plain(ColumnSet c) {
        List<Object> out = new ArrayList<>(List.of(List.of(c.ids()), String.valueOf(c.businessDate())));
        c.numbers().forEach((k, v) -> out.add(k + "=" + java.util.Arrays.toString(v)));
        c.texts().forEach((k, v) -> out.add(k + "=" + java.util.Arrays.toString(v)));
        return out;
    }

    @Test
    void theContractLakeReadsTheSame() throws Exception {
        Path root = DeltaDeletionVectorTest.lake("lake");
        List<LocalDate> dates = List.of(DatedSourceContract.D1, DatedSourceContract.D2, DatedSourceContract.D3, LocalDate.of(2026, 10, 5));
        List<Instant> knownAt = new ArrayList<>();
        knownAt.add(null);
        knownAt.add(Instant.parse("2099-01-01T00:00:00Z"));
        Map<String, String> modes = Map.of("mode.counterparty", "effective");
        DeltaSourcePlugin nat = start(root, "native", modes);
        DeltaSourcePlugin had = start(root, "hadoop", modes);
        try {
            Map<String, Object> a = answers(nat, dates, knownAt);
            assertThat(a).hasSizeGreaterThan(20).isEqualTo(answers(had, dates, knownAt));
        } finally {
            nat.close();
            had.close();
        }
    }

    @Test
    void theLaidOutLakeReadsTheSame() throws Exception {
        Path root = DeltaDeletionVectorTest.lake("lake-layout");
        List<LocalDate> dates = List.of(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30));
        List<Instant> knownAt = new ArrayList<>();
        knownAt.add(null);
        Map<String, String> layout = Map.of("layout.trade.columns", String.join(",", PROMOTED));
        DeltaSourcePlugin nat = start(root, "native", layout);
        DeltaSourcePlugin had = start(root, "hadoop", layout);
        try {
            Map<String, Object> a = answers(nat, dates, knownAt);
            assertThat(a).containsKey("trade columns 2026-09-30").isEqualTo(answers(had, dates, knownAt));
        } finally {
            nat.close();
            had.close();
        }
    }

    @Test
    void idMapsAreTheSame() throws Exception {
        Path root = DeltaDeletionVectorTest.lake("lake-layout");
        try (LakeStore n = LakeStore.of(root.toString(), "desk", Map.of(), EngineKind.NATIVE);
             LakeStore h = LakeStore.of(root.toString(), "desk", Map.of(), EngineKind.HADOOP)) {
            DeltaTable tn = new DeltaTable(n.engine(), n.table("trade"), "id", "doc", "business_date");
            DeltaTable th = new DeltaTable(h.engine(), h.table("trade"), "id", "doc", "business_date");
            DeltaTable.Layout ln = tn.layout(null).orElseThrow();
            DeltaTable.Layout lh = th.layout(null).orElseThrow();
            assertThat(ln.version()).isEqualTo(lh.version());
            assertThat(ln.files().keySet()).isEqualTo(lh.files().keySet());
            for (LocalDate d : ln.files().keySet()) {
                DeltaTable.IdMap a = tn.ids(ln, d);
                DeltaTable.IdMap b = th.ids(lh, d);
                assertThat(a.ids()).hasSize(40).isEqualTo(b.ids());
                assertThat(a.files()).isEqualTo(b.files());
                assertThat(tn.read(ln, d)).isEqualTo(th.read(lh, d));
            }
            assertThat(n.tables()).isEqualTo(h.tables()).containsExactly("trade");
        }
    }
}
