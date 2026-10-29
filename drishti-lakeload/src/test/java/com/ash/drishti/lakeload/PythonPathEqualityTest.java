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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.plugin.iceberg.IcebergSourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parallel loader against the Python path, through {@code drishti.py lake build} itself: the same input built with the Python path
 * ({@code --engine drishti}: Python staging and Drishti's Delta writer; {@code --format iceberg}: Python staging and the Iceberg loader) and
 * with {@code --engine java} gives the same table: every row, every document byte for byte, every column, every vector with its nulls. The
 * Delta and Iceberg connectors then read the loader's tables as they read the Python path's. Two inputs: synthetic positions with
 * escapes, non-ASCII text, number ids and nulls in the vectors; and the market-risk pack's scenario PVs (data/testdata, read only), with
 * the pack's own layout ({@code --pack}). Skipped where python3 is not found.
 */
class PythonPathEqualityTest {

    static final Path REPO = Path.of("").toAbsolutePath().getParent();

    @TempDir
    Path tmp;

    /** Runs lake build; returns its JSON summary. */
    private JsonNode build(List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", REPO.resolve("tools/drishti.py").toString(), "lake", "build"));
        cmd.addAll(args);
        Path summary = Files.createTempFile(tmp, "summary", ".json");
        cmd.addAll(List.of("--summary", summary.toString(), "-q", "--no-stamp"));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).directory(tmp.toFile());
        String cp = System.getProperty("java.class.path");
        pb.environment().put("DRISHTI_LAKE_CLASSPATH", cp);
        pb.environment().put("DRISHTI_ICEBERG_CLASSPATH", cp);
        pb.environment().put("DRISHTI_LAKE_LOADER_HEAP", "1g");
        pb.environment().put("DRISHTI_DATA_DIR", tmp.resolve("data").toString());
        pb.environment().put("DRISHTI_BI_STORE", tmp.resolve("data/bi-store").toString());
        pb.environment().put("DRISHTI_TMP_DIR", tmp.resolve("data/tmp").toString());
        Process p = pb.start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.MINUTES)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", args) + "\n" + said).isIn(0, 2);
        return LakeLoadTest.JSON.readTree(summary.toFile());
    }

    private static boolean python() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "import yaml").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void syntheticPositionsGiveTheSameTablesOnBothPathsAndBothFormats() throws Exception {
        assumeTrue(python(), "python3 with PyYAML is not available");
        List<String> lines = new ArrayList<>(PvData.lines(1500, 80, 2, 11));
        lines.add(17, "{\"POSITION_ID\":\"BAD\",\"BUSINESS_DATE\":\"2026-13-45\"}");
        Path in = PvData.gzip(tmp.resolve("in/pv.jsonl.gz"), lines, 3);
        List<String> common = List.of("--from", in.toString(), "--domain", "risk", "--kind", "scenario-pv", "--key", "POSITION_ID", "--date",
                "BUSINESS_DATE", "--promote", "LEGAL_ENTITY,DESK,TT_BOOK,TRADE_ID,QUANTITY,BASE_PV,CCY,POSITION_ID,TINY", "--vector", "SCENARIO_PV");
        compare(common, "LEGAL_ENTITY,DESK,TT_BOOK,TRADE_ID,QUANTITY,BASE_PV,CCY,POSITION_ID,TINY", "SCENARIO_PV", "P-1000003",
                LocalDate.parse("2026-10-28"));
    }

    @Test
    void theMarketRiskPacksScenarioPvsGiveTheSameTables() throws Exception {
        assumeTrue(python(), "python3 with PyYAML is not available");
        Path data = REPO.resolve("data/testdata/market-risk/scenario-pv");
        assumeTrue(Files.isDirectory(data), "no market-risk scenario PVs in data/testdata");
        List<String> common = List.of("--from", data.toString(), "--pack", REPO.resolve("config/packs/market-risk").toString(), "--kind",
                "scenario-pv", "--key", "SCENARIO_PV_ID", "--date", "BUSINESS_DATE");
        compare(common, null, "SCENARIO_PV", null, LocalDate.parse("2026-09-30"));
    }

    /**
     * The duplicate-record rule (TOOLS.md section 4.13) gives the same table on both paths and both formats: a plain {@code pv.jsonl}
     * beside its {@code pv.jsonl.gz} (the case that once made the paths differ) with ids repeated across the two files and within the
     * {@code .gz}; without {@code COMPUTED_AT} the last copy read is kept (pv.jsonl sorts before pv.jsonl.gz), with it the latest (some
     * copies without one, some ties).
     */
    @Test
    void duplicatesAcrossAndWithinFilesKeepTheSameCopyOnBothPathsAndBothFormats() throws Exception {
        assumeTrue(python(), "python3 with PyYAML is not available");
        for (boolean stamped : new boolean[] {false, true}) {
            java.util.Random r = new java.util.Random(stamped ? 7 : 3);
            List<String> base = PvData.lines(400, 20, 2, 5);
            List<String> gz = new ArrayList<>();
            for (int i = 0; i < base.size(); i++) {
                gz.add(tagged(base.get(i), "gz-" + i, stamped ? r : null));
            }
            for (int i = 0; i < 40; i++) {                                      // within the .gz: lines 0..39 again, later
                gz.add(tagged(base.get(i), "gz-again-" + i, stamped ? r : null));
            }
            List<String> plain = new ArrayList<>();
            for (int i = 20; i < 80; i++) {                                     // across the files: lines 20..79 in pv.jsonl
                plain.add(tagged(base.get(i), "plain-" + i, stamped ? r : null));
            }
            Path in = tmp.resolve("dups-" + stamped);
            PvData.gzip(in.resolve("pv.jsonl.gz"), gz, 2);
            PvData.write(in.resolve("pv.jsonl"), plain);
            List<String> common = List.of("--from", in.toString(), "--domain", "risk", "--kind", "scenario-pv", "--key", "POSITION_ID", "--date",
                    "BUSINESS_DATE", "--promote", "LEGAL_ENTITY,DESK,TT_BOOK,TRADE_ID,QUANTITY,BASE_PV,CCY,POSITION_ID,TINY", "--vector", "SCENARIO_PV");
            JsonNode s = compare("dups-" + stamped + "-", common, "LEGAL_ENTITY,DESK,TT_BOOK,TRADE_ID,QUANTITY,BASE_PV,CCY,POSITION_ID,TINY",
                    "SCENARIO_PV", "P-1000030", LocalDate.parse("2026-10-27"));
            long dropped = 0;
            for (JsonNode n : s.path("duplicates")) {
                dropped += n.asLong();
            }
            assertThat(dropped).isEqualTo(40 + 60);
            assertThat(s.path("duplicateExamples").size()).isEqualTo(LoadReport.KEEP_DUPLICATE_EXAMPLES);
        }
    }

    /** A line with a {@code COPY} tag and (with {@code r}) a {@code COMPUTED_AT} of a few minutes' spread: some missing, some equal. */
    private static String tagged(String line, String copy, java.util.Random r) {
        String at = "";
        if (r != null) {
            int k = r.nextInt(8);
            at = k == 0 ? "" : "\"COMPUTED_AT\":\"2026-10-27T1" + (k % 3) + ":0" + r.nextInt(3) + ":00Z\",";
        }
        return "{" + at + "\"COPY\":\"" + copy + "\"," + line.substring(1);
    }

    private void compare(List<String> common, String columns, String vector, String someId, LocalDate day) throws Exception {
        compare("", common, columns, vector, someId, day);
    }

    /** Builds both ways in both formats and compares; returns the loader's (Delta) summary. */
    private JsonNode compare(String prefix, List<String> common, String columns, String vector, String someId, LocalDate day) throws Exception {
        JsonNode first = null;
        for (String format : List.of("delta", "iceberg")) {
            Path py = tmp.resolve(prefix + "py-" + format);
            Path jv = tmp.resolve(prefix + "java-" + format);
            List<String> a = new ArrayList<>(common);
            a.addAll(List.of("--lake", py.toString(), "--format", format));
            if (format.equals("delta")) {
                a.addAll(List.of("--engine", "drishti"));
            }
            JsonNode sp = build(a);
            List<String> b = new ArrayList<>(common);
            b.addAll(List.of("--lake", jv.toString(), "--format", format, "--engine", "java", "--loader-set", "parallel.writers=3",
                    "--loader-set", "target.layout.file-size-mb=1"));
            JsonNode sj = build(b);
            assertThat(sj.path("engine").asText()).isEqualTo("java-loader");
            assertThat(sj.path("accepted").asLong()).isEqualTo(sp.path("accepted").asLong()).isPositive();
            assertThat(sj.path("rejected").asLong()).isEqualTo(sp.path("rejected").asLong());
            assertThat(sj.path("rejects").path("byReason")).isEqualTo(sp.path("rejects").path("byReason"));
            assertThat(sj.path("rejects").path("first")).isEqualTo(sp.path("rejects").path("first"));
            assertThat(sj.path("rows")).isEqualTo(sp.path("rows"));
            assertThat(sj.path("duplicates")).isEqualTo(sp.path("duplicates"));
            assertThat(sj.path("duplicateExamples")).isEqualTo(sp.path("duplicateExamples"));
            assertThat(sj.path("sampleId")).isEqualTo(sp.path("sampleId"));
            first = first == null ? sj : first;
            String kind = "scenario-pv";
            Map<String, Map<String, Object>> tp;
            Map<String, Map<String, Object>> tj;
            if (format.equals("delta")) {
                tp = TableDump.delta(py.resolve("risk").resolve(kind).toString(), Map.of());
                tj = TableDump.delta(jv.resolve("risk").resolve(kind).toString(), Map.of());
                assertThat(tj).isEqualTo(tp);                                    // every row, document, column and vector
            } else {
                tp = TableDump.iceberg(py.toString(), "risk", kind, Map.of());
                tj = TableDump.iceberg(jv.toString(), "risk", kind, Map.of());
                // the Python path's Iceberg loader makes no column for a field that is null on every row; the loader makes it (as Delta)
                assertThat(TableDump.withoutEmptyColumns(tj)).isEqualTo(TableDump.withoutEmptyColumns(tp));
            }
            long rows = 0;
            for (JsonNode n : sj.path("rows")) {
                rows += n.asLong();
            }
            assertThat(tj.size()).isEqualTo(rows);                               // one row per id and date: duplicates dropped
            connectorsAgree(format, py, jv, kind, columns, vector, someId != null ? someId : firstId(tj, day), day);
        }
        return first;
    }

    private static String firstId(Map<String, Map<String, Object>> t, LocalDate day) {
        return t.keySet().stream().filter(k -> k.startsWith(day + "|")).findFirst().orElseThrow().substring(11);
    }

    /** The connector reads both tables alike: the bulk vectors with a text column beside, and one fetched document. */
    private void connectorsAgree(String format, Path py, Path jv, String kind, String columns, String vector, String id, LocalDate day)
            throws Exception {
        Map<String, double[]> vp = new LinkedHashMap<>();
        Map<String, double[]> vj = new LinkedHashMap<>();
        String dp = read(format, py, kind, columns, vector, id, day, vp);
        String dj = read(format, jv, kind, columns, vector, id, day, vj);
        assertThat(vj).hasSameSizeAs(vp).isNotEmpty();
        vp.forEach((k, v) -> assertThat(vj.get(k)).as(k).containsExactly(v));
        assertThat(dj).isEqualTo(dp).contains(id);
    }

    private String read(String format, Path root, String kind, String columns, String vector, String id, LocalDate day, Map<String, double[]> out)
            throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "risk", "source-name", "risk-store", "engine", "native",
                "layout." + kind + ".vectors", vector, "refresh-seconds", "3600"));
        if (columns != null) {
            s.put("layout." + kind + ".columns", columns);
        }
        Path cache = tmp.resolve("cache-" + root.getFileName());
        s.put("id-map-disk.dir", cache.resolve("idmaps").toString());
        s.put("doc-disk-cache.dir", cache.resolve("docs").toString());
        var ctx = DatedSourceContract.context(s);
        if (format.equals("delta")) {
            DeltaSourcePlugin p = new DeltaSourcePlugin();
            p.start(ctx);
            try {
                p.vectors(kind, vector, List.of(), AsOf.of(day), (k, v, n, t) -> out.put(k, n < 0 ? new double[] {-1} : Arrays.copyOf(v, n)));
                return p.fetch(EntityRef.of(kind, id), AsOf.of(day)).orElseThrow().data().toString();
            } finally {
                p.close();
            }
        }
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(ctx);
        try {
            p.vectors(kind, vector, List.of(), AsOf.of(day), (k, v, n, t) -> out.put(k, n < 0 ? new double[] {-1} : Arrays.copyOf(v, n)));
            return p.fetch(EntityRef.of(kind, id), AsOf.of(day)).orElseThrow().data().toString();
        } finally {
            p.close();
        }
    }
}
