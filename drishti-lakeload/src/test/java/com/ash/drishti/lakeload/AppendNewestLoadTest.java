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
 * Append, the default mode (docs/architecture/DATA_LOAD_RULES.md section 6.1), on Delta and Iceberg: every row carries the load that wrote
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
}
