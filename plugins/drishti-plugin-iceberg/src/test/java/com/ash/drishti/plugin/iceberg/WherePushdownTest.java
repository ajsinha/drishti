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
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A connector's {@code where} reaches the lake (docs/guides/DATA_LAKE_GUIDE.md, "Several source systems"): a table shared by three trading
 * systems, each system's rows loaded by its own append (one file each), or partitioned by {@code SOURCE_SYSTEM}. Read under
 * {@code SOURCE_SYSTEM = 'ENDUR'} the Delta and the Iceberg connectors open only the files that may hold ENDUR rows (the partition value,
 * the file's statistics) and return only its rows; the columns and the documents are exactly the whole date's read filtered after it, in
 * the same order. A comparison that cannot be pushed down (a number, {@code <>}) reads the whole date, as before.
 */
class WherePushdownTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 30);
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> PATHS = List.of("SOURCE_SYSTEM", "DESK", "NOTIONAL");
    static final Map<String, Integer> SYSTEMS = new java.util.LinkedHashMap<>(Map.of("ENDUR", 4));

    static {
        SYSTEMS.put("MUREX", 3);
        SYSTEMS.put("CALYPSO", 5);
    }

    @TempDir
    Path tmp;

    /** One append of one system's trades into {@code <root>/desk/trade}. */
    private void load(Path root, String format, String system, int n, boolean partitioned) throws Exception {
        Path in = Files.createTempDirectory(tmp, "rows");
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String id = system.charAt(0) + "-" + i;
            ObjectNode doc = JSON.createObjectNode().put("TRADE_ID", id).put("SOURCE_SYSTEM", system).put("DESK", "DESK-" + (i % 2))
                    .put("NOTIONAL", 1000.0 * i);
            ObjectNode l = JSON.createObjectNode().put("domain", "desk").put("kind", "trade").put("id", id).put("date", DAY.toString())
                    .put("doc", doc.toString());
            l.putObject("columns").put("SOURCE_SYSTEM", system).put("DESK", "DESK-" + (i % 2)).put("NOTIONAL", 1000.0 * i);
            lines.add(l.toString());
        }
        Files.write(in.resolve("trade." + DAY + ".jsonl"), lines, StandardCharsets.UTF_8);
        Path config = tmp.resolve("load-" + System.nanoTime() + ".yaml");
        Files.writeString(config, "source:\n  files: '" + in + "/*'\n  format: loader-rows\n  promote: [SOURCE_SYSTEM, DESK, NOTIONAL]\n"
                + (partitioned ? "  partition-by: [SOURCE_SYSTEM]\n" : "")
                + "target:\n  format: " + format + "\n  path: '" + root + "/desk/trade'\n  domain: desk\n  kind: trade\n  mode: append\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = com.ash.lake.load.LakeLoad.run(com.ash.drishti.lakehost.DrishtiLake.runtime(), new String[] {"--config", config.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(code).as(out.toString(StandardCharsets.UTF_8)).isZero();
    }

    private SourcePlugin start(String format, Path root) throws Exception {
        SourcePlugin p = format.equals("delta") ? new DeltaSourcePlugin() : new IcebergSourcePlugin();
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "desk", "source-name", "trades-" + format,
                "layout.trade.columns", String.join(",", PATHS), "refresh-seconds", "3600"));
        s.put("id-map-disk.dir", tmp.resolve("cache-" + format + "/idmaps").toString());
        s.put("doc-disk-cache.dir", tmp.resolve("cache-" + format + "/docs").toString());
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static Map<String, Long> stats(SourcePlugin p) {
        return p instanceof DeltaSourcePlugin d ? d.pushdownStats() : ((IcebergSourcePlugin) p).pushdownStats();
    }

    /** The rows of a column read, one line each ({@code id|system|desk|notional}), in the order read. */
    private static List<String> lines(ColumnSet c, Predicate<String> system) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            String s = String.valueOf(c.value("SOURCE_SYSTEM", i));
            if (system.test(s)) {
                out.add(c.ids()[i] + "|" + s + "|" + c.value("DESK", i) + "|" + c.value("NOTIONAL", i));
            }
        }
        return out;
    }

    private static List<String> documents(SourcePlugin p, List<ScanRequest.Filter> where, Predicate<String> system) throws Exception {
        List<String> out = new ArrayList<>();
        java.util.function.Consumer<EntityDocument> sink = d -> {
            if (system.test(d.data().get("SOURCE_SYSTEM").asText())) {
                out.add(d.ref().id() + " " + d.data());
            }
        };
        assertThat(where == null ? p.documents("trade", AsOf.of(DAY), sink) : p.documents("trade", AsOf.of(DAY), where, sink)).isTrue();
        return out;
    }

    @ParameterizedTest(name = "{0}, partitioned {1}")
    @CsvSource({"delta, false", "delta, true", "iceberg, false", "iceberg, true"})
    void theWhereLeavesOutFilesAndRowsAtTheSourceAndTheAnswerIsTheSame(String format, boolean partitioned) throws Exception {
        Path root = tmp.resolve("lake-" + format);
        for (Map.Entry<String, Integer> e : SYSTEMS.entrySet()) {
            load(root, format, e.getKey(), e.getValue(), partitioned);
        }
        SourcePlugin p = start(format, root);
        try {
            ColumnSet whole = p.columns("trade", PATHS, AsOf.of(DAY)).orElseThrow();
            assertThat(whole.size()).isEqualTo(12);
            ScanRequest.Filter endur = new ScanRequest.Filter("SOURCE_SYSTEM", ScanRequest.Op.EQ, List.of("ENDUR"));

            // the columns: only the ENDUR file read, only its rows returned, the same rows in the same order as the whole read filtered
            ColumnSet some = p.columns("trade", PATHS, AsOf.of(DAY), List.of(endur)).orElseThrow();
            assertThat(lines(some, s -> true)).isEqualTo(lines(whole, "ENDUR"::equals)).hasSize(4);
            assertThat(stats(p)).containsEntry("filesRead", 1L).containsEntry("filesSkipped", 2L).containsEntry("rowsReturned", 4L);

            // the documents, as stored, the same
            assertThat(documents(p, List.of(endur), s -> true)).isEqualTo(documents(p, null, "ENDUR"::equals)).hasSize(4);
            assertThat(stats(p)).containsEntry("filesRead", 2L).containsEntry("filesSkipped", 4L).containsEntry("rowsReturned", 8L);

            // in (..): two systems' files
            ScanRequest.Filter two = new ScanRequest.Filter("SOURCE_SYSTEM", ScanRequest.Op.IN, List.of("ENDUR", "CALYPSO"));
            assertThat(lines(p.columns("trade", PATHS, AsOf.of(DAY), List.of(two)).orElseThrow(), s -> true))
                    .isEqualTo(lines(whole, s -> s.equals("ENDUR") || s.equals("CALYPSO"))).hasSize(9);
            assertThat(stats(p)).containsEntry("filesRead", 4L).containsEntry("filesSkipped", 5L);

            // comparisons that cannot be pushed down: the whole date, nothing counted
            ScanRequest.Filter notMurex = new ScanRequest.Filter("SOURCE_SYSTEM", ScanRequest.Op.NE, List.of("MUREX"));
            ScanRequest.Filter big = new ScanRequest.Filter("NOTIONAL", ScanRequest.Op.GE, List.of(3000.0));
            assertThat(lines(p.columns("trade", PATHS, AsOf.of(DAY), List.of(notMurex, big)).orElseThrow(), s -> true)).isEqualTo(lines(whole, s -> true));
            assertThat(stats(p)).containsEntry("filesRead", 4L);
        } finally {
            p.close();
        }
    }
}
