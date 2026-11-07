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
package com.ash.drishti.lakeload;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.plugin.iceberg.IcebergSourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Append, the default mode (docs/architecture/DATA_ARCHITECTURE.md section 10.6.1), on Delta and Iceberg: every row carries the load that wrote
 * it ({@code _load_id}, {@code <run>.<file>}) and its file ({@code _source_file}); a second load adds rows; an id both loads wrote is read
 * as the newest load's copy by both connectors (a document and the bulk vector read); replace-date replaces the date.
 */
class AppendNewestLoadTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    @TempDir
    Path tmp;

    private static String row(String id, double v) {
        return "{\"POSITION_ID\":\"" + id + "\",\"BUSINESS_DATE\":\"" + DAY + "\",\"LEGAL_ENTITY\":\"LE\",\"DESK\":\"D\",\"SCENARIO_PV\":[" + v + "," + v + "]}";
    }

    private JsonNode load(Path drop, Path lake, String format, String... sets) throws Exception {
        return LakeLoadTest.load(tmp, PvData.config(drop.toString(), lake.toString(), format), sets);
    }

    private Map<String, Double> read(String format, Path root, Map<String, String> docs) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "risk", "source-name", "risk-store", "engine", "native",
                "layout.scenario-pv.vectors", "SCENARIO_PV", "refresh-seconds", "3600"));
        s.put("id-map-disk.dir", tmp.resolve("cache-" + format + "/idmaps").toString());
        s.put("doc-disk-cache.dir", tmp.resolve("cache-" + format + "/docs").toString());
        Map<String, Double> out = new LinkedHashMap<>();
        var ctx = DatedSourceContract.context(s);
        if (format.equals("delta")) {
            DeltaSourcePlugin p = new DeltaSourcePlugin();
            p.start(ctx);
            try {
                p.vectors("scenario-pv", "SCENARIO_PV", List.of(), AsOf.of(DAY), (k, v, n, t) -> out.put(k, v[0]));
                docs.put("A", p.fetch(EntityRef.of("scenario-pv", "A"), AsOf.of(DAY)).orElseThrow().data().toString());
            } finally {
                p.close();
            }
            return out;
        }
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(ctx);
        try {
            p.vectors("scenario-pv", "SCENARIO_PV", List.of(), AsOf.of(DAY), (k, v, n, t) -> out.put(k, v[0]));
            docs.put("A", p.fetch(EntityRef.of("scenario-pv", "A"), AsOf.of(DAY)).orElseThrow().data().toString());
        } finally {
            p.close();
        }
        return out;
    }

    @Test
    void appendsAddRowsAndTheNewestLoadsCopyOfAnIdIsRead() throws Exception {
        Path first = PvData.write(tmp.resolve("one/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 1), row("B", 2)));
        Path second = PvData.write(tmp.resolve("two/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 10), row("C", 3)));
        for (String format : List.of("delta", "iceberg")) {
            Path lake = tmp.resolve("lake-" + format);
            JsonNode s1 = load(first.getParent(), lake, format);
            assertThat(s1.path("exit").asInt()).as(s1.path("said").asText() + s1.path("error").asText()).isZero();
            assertThat(s1.path("mode").asText()).isEqualTo("append");
            String id1 = s1.path("loads").fieldNames().next();
            assertThat(s1.path("loads").path(id1).asText()).isEqualTo("scenario-pv." + DAY + ".jsonl");
            JsonNode s2 = load(second.getParent(), lake, format);
            assertThat(s2.path("exit").asInt()).as(s2.path("said").asText() + s2.path("error").asText()).isZero();
            String id2 = s2.path("loads").fieldNames().next();
            assertThat(id2).isGreaterThan(id1);                                    // load ids sort in time order

            Map<String, Map<String, Object>> rows;
            TableDump.keepLoadColumns = true;
            try {
                rows = LakeLoadTest.dump(format, lake);
            } finally {
                TableDump.keepLoadColumns = false;
            }
            assertThat(rows).as("an append adds: A twice").hasSize(4);
            assertThat(rows.values()).extracting(r -> String.valueOf(r.get("_load_id"))).containsOnly(id1, id2);
            assertThat(rows.values()).extracting(r -> String.valueOf(r.get("_source_file"))).containsOnly("scenario-pv." + DAY + ".jsonl");

            Map<String, String> docs = new HashMap<>();
            Map<String, Double> got = read(format, lake, docs);
            assertThat(got).as(format).containsOnlyKeys("A", "B", "C").containsEntry("A", 10.0).containsEntry("B", 2.0).containsEntry("C", 3.0);
            assertThat(docs.get("A")).isNotBlank();

            JsonNode s3 = load(second.getParent(), lake, format, "target.mode=replace-date");
            assertThat(s3.path("exit").asInt()).isZero();
            assertThat(LakeLoadTest.dump(format, lake)).as("replace-date: the date is the last load's alone").hasSize(2);
        }
        assertThat(Files.exists(tmp.resolve("lake-delta/risk/scenario-pv/_delta_log"))).isTrue();
    }

    /** Undo by load id (DATA_ARCHITECTURE.md section 10.6.3): an append's rows removed; a replace undone by restoring the date as it was. */
    @Test
    void undoRemovesALoadsRowsAndRestoresWhatAReplaceReplaced() throws Exception {
        Path first = PvData.write(tmp.resolve("u1/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 1), row("B", 2)));
        Path second = PvData.write(tmp.resolve("u2/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 10), row("C", 3)));
        Path lake = tmp.resolve("lake-undo");
        String id1 = load(first.getParent(), lake, "delta").path("loads").fieldNames().next();
        String id2 = load(second.getParent(), lake, "delta").path("loads").fieldNames().next();
        List<LakeUndo.Done> dry = LakeUndo.undo(lake.toString(), id2, List.of(), true);
        assertThat(dry).singleElement().satisfies(d -> assertThat(List.of(d.table(), d.date(), String.valueOf(d.removed())))
                .containsExactly("risk/scenario-pv", DAY.toString(), "2"));
        assertThat(LakeLoadTest.dump("delta", lake)).as("a dry run changes nothing").hasSize(4);
        LakeUndo.undo(lake.toString(), id2, List.of(), false);
        Map<String, Double> docs = read("delta", lake, new HashMap<>());
        assertThat(docs).containsOnlyKeys("A", "B").containsEntry("A", 1.0);
        assertThat(LakeUndo.undo(lake.toString(), id2, List.of(), false)).as("nothing left of it").isEmpty();

        // a replace, undone: the date as it was before it
        String id3 = load(second.getParent(), lake, "delta", "target.mode=replace-date").path("loads").fieldNames().next();
        assertThat(read("delta", lake, new HashMap<>())).containsOnlyKeys("A", "C");
        List<LakeUndo.Done> undone = LakeUndo.undo(lake.toString(), id3, List.of(), false);
        assertThat(undone).singleElement().satisfies(d -> {
            assertThat(d.replaced()).isTrue();
            assertThat(d.restored()).isEqualTo(2);
        });
        assertThat(read("delta", lake, new HashMap<>())).containsOnlyKeys("A", "B").containsEntry("A", 1.0);
        assertThat(id1).isLessThan(id3);
    }

    /** The same on Iceberg (the Iceberg maintenance's undo): an append's rows removed, a replace's date restored. */
    @Test
    void undoOnIceberg() throws Exception {
        Path first = PvData.write(tmp.resolve("i1/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 1), row("B", 2)));
        Path second = PvData.write(tmp.resolve("i2/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 10), row("C", 3)));
        Path lake = tmp.resolve("lake-iundo");
        load(first.getParent(), lake, "iceberg");
        String id2 = load(second.getParent(), lake, "iceberg").path("loads").fieldNames().next();
        var undone = com.ash.drishti.plugin.iceberg.IcebergMaintenance.undoLake(lake.toString(), id2, false, tmp);
        assertThat(undone).singleElement().satisfies(u -> assertThat(u).containsEntry("table", "risk/scenario-pv").containsEntry("removed", 2L));
        assertThat(read("iceberg", lake, new HashMap<>())).containsOnlyKeys("A", "B").containsEntry("A", 1.0);
        String id3 = load(second.getParent(), lake, "iceberg", "target.mode=replace-date").path("loads").fieldNames().next();
        var restored = com.ash.drishti.plugin.iceberg.IcebergMaintenance.undoLake(lake.toString(), id3, false, tmp);
        assertThat(restored).singleElement().satisfies(u -> assertThat(u).containsEntry("replaced", true).containsEntry("restored", 2L));
        assertThat(read("iceberg", lake, new HashMap<>())).containsOnlyKeys("A", "B").containsEntry("A", 1.0);
    }

    /** The table's data files (relative paths), to show an append into an old table rewrites none of them. */
    private static java.util.Set<String> dataFiles(Path table) throws Exception {
        try (var s = Files.walk(table)) {
            return s.filter(f -> f.toString().endsWith(".parquet")).map(f -> table.relativize(f).toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    /**
     * A table made before the load columns (as the lakes already built were made: no {@code _load_id}, no {@code _source_file}), on Delta
     * and Iceberg: the connector reads it as before; an append adds the two columns (Delta: one metadata commit; Iceberg: the columns
     * added) and rewrites no file; its old rows read as the oldest load, so the appended copy of an id wins; undoing the append leaves the
     * old rows as they were; a replace into it, undone, gives the old rows back.
     */
    @Test
    void anOldTableWithoutLoadColumnsTakesAppendsReplacesAndUndo() throws Exception {
        Path first = PvData.write(tmp.resolve("o1/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 1), row("B", 2)));
        PvData.write(tmp.resolve("o1/scenario-pv." + DAY.minusDays(1) + ".jsonl"), List.of(row("A", 5)));         // a date the append leaves alone
        Path second = PvData.write(tmp.resolve("o2/scenario-pv." + DAY + ".jsonl"), List.of(row("A", 10), row("C", 3)));
        for (String format : List.of("delta", "iceberg")) {
            Path lake = tmp.resolve("old-" + format);
            RowPlan.loadColumns = false;                                       // the loader before the load columns
            try {
                assertThat(load(first.getParent(), lake, format).path("exit").asInt()).isZero();
            } finally {
                RowPlan.loadColumns = true;
            }
            Map<String, Map<String, Object>> old;
            TableDump.keepLoadColumns = true;
            try {
                old = LakeLoadTest.dump(format, lake);
            } finally {
                TableDump.keepLoadColumns = false;
            }
            assertThat(old.values()).as(format + ": made without the load columns").allSatisfy(r -> assertThat(r).doesNotContainKey("_load_id"));
            assertThat(read(format, lake, new HashMap<>())).as(format + ": read as before").containsOnlyKeys("A", "B").containsEntry("A", 1.0);
            Path table = lake.resolve(format.equals("delta") ? "risk/scenario-pv" : "risk");
            java.util.Set<String> oldFiles = dataFiles(table);
            assertThat(oldFiles).isNotEmpty();

            JsonNode s2 = load(second.getParent(), lake, format);
            assertThat(s2.path("exit").asInt()).as(format + ": " + s2.path("said").asText() + s2.path("error").asText()).isZero();
            assertThat(s2.toString()).as(format + ": the load columns were added, not left out").doesNotContain("leftOut");
            String id2 = s2.path("loads").fieldNames().next();
            assertThat(dataFiles(table)).as(format + ": no file rewritten").containsAll(oldFiles);
            if (format.equals("delta")) {
                try (var logs = Files.list(table.resolve("_delta_log"))) {
                    for (Path l : logs.filter(f -> f.toString().endsWith(".json")).toList()) {
                        String text = Files.readString(l);
                        for (String f : oldFiles) {
                            String name = f.substring(f.lastIndexOf('/') + 1);
                            assertThat(text.contains("\"remove\"") && text.contains(name)).as(l + " removes " + f).isFalse();
                        }
                    }
                }
            }
            Map<String, Map<String, Object>> rows;
            TableDump.keepLoadColumns = true;
            try {
                rows = LakeLoadTest.dump(format, lake);
            } finally {
                TableDump.keepLoadColumns = false;
            }
            assertThat(rows).hasSize(5);
            assertThat(rows.values()).extracting(r -> String.valueOf(r.get("_load_id"))).containsExactlyInAnyOrder("null", "null", "null", id2, id2);
            assertThat(read(format, lake, new HashMap<>())).as(format + ": the old rows are the oldest load")
                    .containsOnlyKeys("A", "B", "C").containsEntry("A", 10.0).containsEntry("B", 2.0);

            assertThat(dryRunDates(format, lake, id2)).as(format + ": only the date the load wrote (the old files read for their column alone)")
                    .containsExactly(DAY.toString());
            undo(format, lake, id2);
            assertThat(read(format, lake, new HashMap<>())).as(format + ": undone, the old rows as they were")
                    .containsOnlyKeys("A", "B").containsEntry("A", 1.0).containsEntry("B", 2.0);

            String id3 = load(second.getParent(), lake, format, "target.mode=replace-date").path("loads").fieldNames().next();
            assertThat(read(format, lake, new HashMap<>())).containsOnlyKeys("A", "C");
            undo(format, lake, id3);
            assertThat(read(format, lake, new HashMap<>())).as(format + ": the replace undone").containsOnlyKeys("A", "B").containsEntry("A", 1.0);
        }
    }

    private List<String> dryRunDates(String format, Path lake, String id) throws Exception {
        if (format.equals("delta")) {
            return LakeUndo.undo(lake.toString(), id, List.of(), true).stream().map(LakeUndo.Done::date).toList();
        }
        return com.ash.drishti.plugin.iceberg.IcebergMaintenance.undoLake(lake.toString(), id, true, tmp).stream()
                .map(u -> String.valueOf(u.get("date"))).toList();
    }

    private void undo(String format, Path lake, String id) throws Exception {
        if (format.equals("delta")) {
            assertThat(LakeUndo.undo(lake.toString(), id, List.of(), false)).isNotEmpty();
        } else {
            assertThat(com.ash.drishti.plugin.iceberg.IcebergMaintenance.undoLake(lake.toString(), id, false, tmp)).isNotEmpty();
        }
    }
}
