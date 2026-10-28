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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loader end to end on local disk: one commit per date, a re-run replacing the date, a load stopped before its commit leaving the
 * table as it was, rejects, and identical tables whatever the input's form (one file, ranges of it, gzip, several gzip members, many gzip
 * files, zstd), the thread counts and the memory budget (a tiny one spills every few rows).
 */
class LakeLoadTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        DateWriter.failAfterFiles = 0;
        DateWriter.resetCount();
    }

    /** Runs the loader; returns its summary (with "exit"). */
    static JsonNode load(Path tmp, String yaml, String... sets) throws Exception {
        Path cfg = Files.createTempFile(tmp, "load", ".yaml");
        Files.writeString(cfg, yaml);
        Path summary = Files.createTempFile(tmp, "summary", ".json");
        List<String> args = new ArrayList<>(List.of("--config", cfg.toString(), "--summary", summary.toString(), "-q"));
        for (String s : sets) {
            args.add("--set");
            args.add(s);
        }
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int code = LakeLoad.run(args.toArray(new String[0]), new PrintStream(said, true, StandardCharsets.UTF_8));
        JsonNode s = JSON.readTree(summary.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) s).put("exit", code).put("said", said.toString(StandardCharsets.UTF_8));
        return s;
    }

    private Path lake(String name) {
        return tmp.resolve(name);
    }

    @Test
    void eachDateIsOneCommitAndARerunReplacesTheDate() throws Exception {
        List<String> lines = PvData.lines(400, 40, 2, 1);
        Path in = PvData.write(tmp.resolve("in/pv.jsonl"), lines);
        for (String format : List.of("delta", "iceberg")) {
            Path lake = lake("lake-" + format);
            JsonNode s = load(tmp, PvData.config(in.toString(), lake.toString(), format));
            assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
            assertThat(s.path("commit").path("commits").asInt()).isEqualTo(2);
            assertThat(s.path("rows").path("2026-10-27").asLong()).isEqualTo(200);
            Map<String, Map<String, Object>> first = dump(format, lake);
            assertThat(first).hasSize(400);
            if (format.equals("delta")) {
                assertThat(Files.list(lake.resolve("risk/scenario-pv/_delta_log")).filter(p -> p.toString().endsWith(".json")).count()).isEqualTo(2);
            }

            // the second date again with other values: only that date changes, in one more commit
            List<String> again = PvData.lines(400, 40, 2, 99).stream().filter(l -> l.contains("2026-10-28")).toList();
            Path in2 = PvData.write(tmp.resolve("in2-" + format + "/pv.jsonl"), again);
            JsonNode s2 = load(tmp, PvData.config(in2.toString(), lake.toString(), format));
            assertThat(s2.path("exit").asInt()).as(s2.path("error").asText()).isZero();
            assertThat(s2.path("commit").path("commits").asInt()).isEqualTo(1);
            Map<String, Map<String, Object>> second = dump(format, lake);
            assertThat(second).hasSize(400);
            second.forEach((k, row) -> {
                if (k.startsWith("2026-10-27")) {
                    assertThat(row).isEqualTo(first.get(k));
                } else {
                    assertThat(row.get("doc")).isNotEqualTo(first.get(k).get("doc"));
                }
            });
            if (format.equals("delta")) {
                assertThat(Files.list(lake.resolve("risk/scenario-pv/_delta_log")).filter(p -> p.toString().endsWith(".json")).count()).isEqualTo(3);
            }
        }
    }

    @Test
    void aLoadStoppedBeforeItsCommitLeavesTheTableAsItWas() throws Exception {
        Path in = PvData.write(tmp.resolve("in/pv.jsonl"), PvData.lines(300, 30, 1, 2));
        for (String format : List.of("delta", "iceberg")) {
            Path lake = lake("lake-" + format);
            assertThat(load(tmp, PvData.config(in.toString(), lake.toString(), format)).path("exit").asInt()).isZero();
            Map<String, Map<String, Object>> before = dump(format, lake);
            Path in2 = PvData.write(tmp.resolve("in2/pv.jsonl"), PvData.lines(300, 30, 1, 7));
            DateWriter.resetCount();
            DateWriter.failAfterFiles = 2;
            JsonNode s = load(tmp, PvData.config(in2.toString(), lake.toString(), format), "target.layout.file-size-mb=0.01");
            DateWriter.failAfterFiles = 0;
            assertThat(s.path("exit").asInt()).isEqualTo(1);
            assertThat(s.path("error").asText()).contains("stopped after");
            assertThat(dump(format, lake)).isEqualTo(before);
        }
    }

    @Test
    void rejectsAreCountedNamedAndWrittenAndTooManyStopTheLoad() throws Exception {
        List<String> lines = new ArrayList<>(PvData.lines(50, 10, 1, 3));
        lines.add(3, "{\"POSITION_ID\":\"X1\",\"BUSINESS_DATE\":\"not a date\"}");
        lines.add(5, "{\"BUSINESS_DATE\":\"2026-10-27\"}");
        lines.add(7, "{\"POSITION_ID\":\"X2\",\"BUSINESS_DATE\":\"2026-10-27\",\"SCENARIO_PV\":[1,\"two\"]}");
        lines.add(9, "{not json");
        lines.add(11, "[1,2]");
        lines.add(13, "   ");
        Path in = PvData.write(tmp.resolve("in/pv.jsonl"), lines);
        Path rejects = tmp.resolve("out/rejects.jsonl");
        JsonNode s = load(tmp, PvData.config(in.toString(), lake("lake").toString(), "delta"), "rejects.file=" + rejects);
        assertThat(s.path("exit").asInt()).isEqualTo(2);
        assertThat(s.path("status").asText()).isEqualTo("partial");
        assertThat(s.path("documents").asLong()).isEqualTo(55);
        assertThat(s.path("rejected").asLong()).isEqualTo(5);
        JsonNode by = s.path("rejects").path("byReason");
        assertThat(by.path("no business date (field 'BUSINESS_DATE' is not a date)").asInt()).isEqualTo(1);
        assertThat(by.path("no id (field 'POSITION_ID' is missing or empty)").asInt()).isEqualTo(1);
        assertThat(by.path("the vector field 'SCENARIO_PV' is not a list of numbers").asInt()).isEqualTo(1);
        assertThat(by.path("not a JSON object").asInt()).isEqualTo(1);
        assertThat(s.path("rejects").path("first").get(0).path("where").asText()).isEqualTo("pv.jsonl:4");
        assertThat(s.path("rejects").path("first").get(3).path("where").asText()).isEqualTo("pv.jsonl:10");
        assertThat(Files.readAllLines(rejects)).hasSize(5).anyMatch(l -> l.contains("{not json"));
        assertThat(dump("delta", lake("lake"))).hasSize(50);

        JsonNode stop = load(tmp, PvData.config(in.toString(), lake("lake2").toString(), "delta"), "rejects.max-pct=1");
        assertThat(stop.path("exit").asInt()).isEqualTo(1);
        assertThat(stop.path("error").asText()).contains("more than rejects.max-pct");
        assertThat(Files.exists(lake("lake2").resolve("risk/scenario-pv/_delta_log"))).isFalse();
    }

    @Test
    void theInputsFormThreadsAndMemoryBudgetGiveIdenticalTables() throws Exception {
        List<String> lines = PvData.lines(1200, 60, 2, 5);
        Path plain = PvData.write(tmp.resolve("plain/pv.jsonl"), lines);
        Path gz = PvData.gzip(tmp.resolve("gz/pv.jsonl.gz"), lines, 1);
        Path members = PvData.gzip(tmp.resolve("members/pv.jsonl.gz"), lines, 5);
        for (int i = 0; i < 6; i++) {
            PvData.gzip(tmp.resolve("many/pv-" + i + ".jsonl.gz"), lines.subList(i * 200, (i + 1) * 200), 1);
        }
        Path zst = PvData.zstd(tmp.resolve("zst/pv.jsonl.zst"), lines);
        for (String format : List.of("delta", "iceberg")) {
            Map<String, Map<String, Object>> want = null;
            int n = 0;
            for (String[] variant : List.<String[]>of(
                    new String[] {plain.toString()},
                    new String[] {plain.toString(), "parallel.split-mb=0.1", "parallel.readers=4"},
                    new String[] {gz.toString()},
                    new String[] {members.toString()},
                    new String[] {tmp.resolve("many").toString() + "/*.jsonl.gz", "parallel.readers=2"},
                    new String[] {zst.toString()},
                    new String[] {plain.toString(), "parallel.parsers=1", "parallel.writers=1", "parallel.readers=1"},
                    new String[] {gz.toString(), "parallel.parsers=6", "parallel.writers=5", "target.layout.file-size-mb=0.3"},
                    new String[] {members.toString(), "memory.budget-mb=1", "parallel.parsers=3"})) {
                Path lake = lake(format + "-" + n++);
                String[] sets = java.util.Arrays.copyOfRange(variant, 1, variant.length);
                JsonNode s = load(tmp, PvData.config(variant[0], lake.toString(), format), sets);
                assertThat(s.path("exit").asInt()).as(String.join(" ", variant) + ": " + s.path("error").asText()).isZero();
                assertThat(s.path("accepted").asLong()).isEqualTo(1200);
                Map<String, Map<String, Object>> got = dump(format, lake);
                if (want == null) {
                    want = got;
                    assertThat(want).hasSize(1200);
                } else {
                    assertThat(got).as(String.join(" ", variant)).isEqualTo(want);
                }
                if (variant.length > 1 && variant[1].startsWith("memory.budget-mb")) {
                    assertThat(s.path("memory").path("spilledMb").asLong() + s.path("throughput").path("sortSpill").path("rows").asLong()).isPositive();
                }
            }
        }
    }

    @Test
    void theDocumentKeepsAVectorMarkerAndTheVectorItsNulls() throws Exception {
        List<String> lines = PvData.lines(30, 220, 1, 9);
        Path in = PvData.write(tmp.resolve("in/pv.jsonl"), lines);
        Path lake = lake("lake");
        assertThat(load(tmp, PvData.config(in.toString(), lake.toString(), "delta")).path("exit").asInt()).isZero();
        Map<String, Object> row = dump("delta", lake).get("2026-10-27|P-1000000");
        assertThat((String) row.get("doc")).contains("\"SCENARIO_PV\":{\"$vector\":\"SCENARIO_PV\"}").startsWith("{\"POSITION_ID\":\"P-1000000\"")
                .contains("\"TRADE_ID\":2000000,");
        @SuppressWarnings("unchecked")
        List<Double> v = (List<Double>) row.get("SCENARIO_PV");
        assertThat(v).hasSize(220);
        assertThat(v.get(0)).isNull();                                  // (i + s) % 211 == 0 at i = 0, s = 0
        assertThat(v.get(1)).isNotNull();
        assertThat(row.get("TRADE_ID")).isEqualTo("2000000");          // an id field is text
        assertThat(row.get("QUANTITY")).isInstanceOf(Double.class);
    }

    static Map<String, Map<String, Object>> dump(String format, Path lake) throws Exception {
        return format.equals("delta") ? TableDump.delta(lake.resolve("risk/scenario-pv").toString(), Map.of())
                : TableDump.iceberg(lake.toString(), "risk", "scenario-pv", Map.of());
    }
}
