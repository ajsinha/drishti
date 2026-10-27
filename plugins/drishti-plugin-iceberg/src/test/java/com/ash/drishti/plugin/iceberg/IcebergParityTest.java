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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.plugin.iceberg.VectorLake.Pos;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Iceberg connector answers as the Delta connector on the same rows (Delta is the default; Iceberg by configuration): the same
 * inventory, the same {@code holdsNone}, the same documents of a date in one pass, the same vectors bit for bit with the same texts, a
 * table identity that stays the same when the table is copied elsewhere and read through {@code relocate}, and a data-load signal
 * ({@code refreshVersions}) that makes the next read see a new date at once.
 */
class IcebergParityTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 21);
    static final LocalDate D2 = LocalDate.of(2026, 10, 22);
    static final LocalDate D3 = LocalDate.of(2026, 10, 23);

    @TempDir
    Path tmp;

    private static Map<LocalDate, List<Pos>> days() {
        Map<LocalDate, List<Pos>> m = new TreeMap<>();
        m.put(D1, VectorLake.positions(12, 1));
        m.put(D2, VectorLake.positions(15, 2));
        return m;
    }

    private SourcePlugin delta(Path root) throws Exception {
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        Map<String, String> s = new HashMap<>(VectorLake.settings(root, "native", tmp.resolve("cache-delta")));
        s.put("source-name", "risk-delta");
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private SourcePlugin iceberg(Path root, Map<String, String> extra) throws Exception {
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        Map<String, String> s = new HashMap<>(VectorLake.settings(root, "native", tmp.resolve("cache-iceberg-" + root.getFileName())));
        s.putAll(extra);
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static Map<String, Object> documents(SourcePlugin p, LocalDate day) throws Exception {
        Map<String, Object> out = new TreeMap<>();
        assertThat(p.documents(VectorLake.KIND, AsOf.of(day), (EntityDocument d) -> out.put(d.ref().id(), d.data().unwrap()))).isTrue();
        return out;
    }

    private static Map<String, String> vectors(SourcePlugin p, LocalDate day) throws Exception {
        Map<String, String> out = new TreeMap<>();
        assertThat(p.vectors(VectorLake.KIND, "PNL", List.of("DESK", "QUANTITY"), AsOf.of(day), (id, v, n, t) -> {
            long[] bits = n < 0 ? null : Arrays.stream(Arrays.copyOf(v, n)).mapToLong(Double::doubleToRawLongBits).toArray();
            out.put(id, Arrays.toString(bits) + Arrays.toString(t));
        })).isTrue();
        return out;
    }

    @Test
    void theIcebergConnectorAnswersAsTheDeltaConnectorOnTheSameRows() throws Exception {
        Path deltaRoot = tmp.resolve("delta");
        Path iceRoot = tmp.resolve("iceberg");
        VectorLake.loadDelta(tmp, deltaRoot.resolve("risk").resolve(VectorLake.KIND), days());
        VectorLake.loadIceberg(tmp, iceRoot, "native", days());
        SourcePlugin d = delta(deltaRoot);
        SourcePlugin i = iceberg(iceRoot, Map.of());
        String id;
        try {
            assertThat(i.inventory(VectorLake.KIND, 10)).isEqualTo(d.inventory(VectorLake.KIND, 10)).containsEntry(D2, 15L).containsEntry(D1, 12L);
            assertThat(i.inventory(VectorLake.KIND, 1)).isEqualTo(d.inventory(VectorLake.KIND, 1)).containsOnlyKeys(D2);
            assertThat(i.holdsNone(VectorLake.KIND)).isEqualTo(d.holdsNone(VectorLake.KIND)).isFalse();
            assertThat(i.holdsNone("nothing-here")).isEqualTo(d.holdsNone("nothing-here")).isTrue();
            for (LocalDate day : List.of(D1, D2)) {
                assertThat(documents(i, day)).as("documents of " + day).isEqualTo(documents(d, day)).hasSize(day.equals(D1) ? 12 : 15);
                assertThat(vectors(i, day)).as("vectors of " + day).isEqualTo(vectors(d, day));
            }
            assertThat(i.vectorColumns(VectorLake.KIND)).isEqualTo(d.vectorColumns(VectorLake.KIND));
            id = i.tableIdentity(VectorLake.KIND).orElseThrow();
            assertThat(id).startsWith("iceberg:").isEqualTo(i.tableIdentity(VectorLake.KIND).orElseThrow());
            assertThat(d.tableIdentity(VectorLake.KIND).orElseThrow()).startsWith("delta:");

            // a data-load signal: the next read sees the new date at once (both connectors refresh only hourly here)
            VectorLake.loadDelta(tmp, deltaRoot.resolve("risk").resolve(VectorLake.KIND), Map.of(D3, VectorLake.positions(9, 3)));
            VectorLake.loadIceberg(tmp, iceRoot, "native", Map.of(D3, VectorLake.positions(9, 3)));
            d.refreshVersions();
            i.refreshVersions();
            assertThat(i.latestDate(VectorLake.KIND)).isEqualTo(d.latestDate(VectorLake.KIND)).contains(D3);
            assertThat(i.inventory(VectorLake.KIND, 10)).isEqualTo(d.inventory(VectorLake.KIND, 10)).containsEntry(D3, 9L);
            assertThat(vectors(i, D3)).isEqualTo(vectors(d, D3));
            assertThat(i.tableIdentity(VectorLake.KIND)).contains(id);
        } finally {
            d.close();
            i.close();
        }

        // the table copied elsewhere and read through relocate: the same table, the same identity
        Path copy = tmp.resolve("copy");
        copyTree(iceRoot, copy);
        SourcePlugin moved = iceberg(copy, Map.of("relocate", iceRoot + " -> " + copy));
        try {
            assertThat(moved.tableIdentity(VectorLake.KIND)).contains(id);
            assertThat(moved.inventory(VectorLake.KIND, 10)).containsEntry(D3, 9L);
        } finally {
            moved.close();
        }
    }

    @Test
    void anEffectiveKindIsReadEntityByEntityAndAnUnknownKindNotAtAll() throws Exception {
        Path iceRoot = tmp.resolve("iceberg");
        VectorLake.loadIceberg(tmp, iceRoot, "native", days());
        SourcePlugin effective = iceberg(iceRoot, Map.of("mode.pnl-vector", "effective"));
        try {
            assertThat(effective.documents(VectorLake.KIND, AsOf.of(D2), x -> { })).isFalse();
            assertThat(effective.documents("nothing-here", AsOf.of(D2), x -> { })).isFalse();
            assertThat(effective.vectorColumns(VectorLake.KIND)).isEmpty();
        } finally {
            effective.close();
        }
        SourcePlugin p = iceberg(iceRoot, Map.of());
        try {
            Map<String, Object> none = new LinkedHashMap<>();
            assertThat(p.documents(VectorLake.KIND, AsOf.of(LocalDate.of(2020, 1, 2)), x -> none.put(x.ref().id(), x))).isTrue();
            assertThat(none).isEmpty();
            // a date between two loaded dates: the newest on or before it (a snapshot kind), as the Delta connector
            assertThat(documents(p, D2.plusDays(1))).hasSize(15);
        } finally {
            p.close();
        }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(f, target);
                }
            }
        }
    }
}
