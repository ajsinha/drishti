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

import com.ash.drishti.deltalake.write.LakeCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Random;

/**
 * P&amp;L-vector rows for the Iceberg tests (VAR_GUIDE.md section 29.3): positions with a desk, a book, a quantity (a number, read as
 * text the way the Delta connector gives it) and a 509-value vector with a few missing elements, loaded into Iceberg by
 * {@link IcebergLoader} (either engine) and into Delta by Drishti's Delta writer, from the same rows.
 */
final class VectorLake {

    static final int N = 509;
    static final String KIND = "pnl-vector";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One position: {@code pnl} null for a row without a vector; NaN marks a missing element. */
    record Pos(String id, String desk, String book, double quantity, double[] pnl) {}

    private VectorLake() {
    }

    /** {@code n} positions; every 50th element of every vector is missing and the 5th position has no vector at all. */
    static List<Pos> positions(int n, long seed) {
        Random r = new Random(seed);
        List<Pos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] v = null;
            if (i != 5) {
                v = new double[N];
                for (int j = 0; j < N; j++) {
                    v[j] = j % 50 == 3 ? Double.NaN : Math.round(r.nextGaussian() * 50_000) + (j % 7 == 0 ? 0.25 : r.nextDouble() / 3);
                }
            }
            out.add(new Pos(String.format("POS-%05d", i), "DESK-" + (i % 2 == 0 ? "RATES" : "FX"), "BOOK-" + (i % 3 + 1), 1500.0 + i, v));
        }
        return out;
    }

    static String doc(Pos p) {
        ObjectNode d = JSON.createObjectNode();
        d.put("POSITION_ID", p.id()).put("DESK", p.desk()).put("TT_BOOK", p.book()).put("QUANTITY", p.quantity());
        d.putObject("PNL").put("$vector", "PNL");
        return d.toString();
    }

    private static ArrayNode array(double[] v) {
        ArrayNode a = JSON.createArrayNode();
        for (double x : v) {
            if (Double.isNaN(x)) {
                a.addNull();
            } else {
                a.add(x);
            }
        }
        return a;
    }

    /** The loader's line of one position on one date ({@code PNL} is given as {@code pnl}, which may be anything for a reject test). */
    static String line(Pos p, LocalDate day, Object pnl) {
        ObjectNode l = JSON.createObjectNode();
        l.put("domain", "risk").put("kind", KIND).put("id", p.id()).put("date", day.toString()).put("doc", doc(p));
        ObjectNode c = l.putObject("columns");
        c.put("DESK", p.desk()).put("TT_BOOK", p.book()).put("QUANTITY", p.quantity());
        if (pnl == null && p.pnl() != null) {
            c.set("PNL", array(p.pnl()));
        } else {
            c.set("PNL", JSON.valueToTree(pnl));
        }
        return l.toString();
    }

    /** Loads the positions for each day into {@code <root>/risk/pnl-vector} with {@link IcebergLoader} on {@code engine}. */
    static void loadIceberg(Path tmp, Path root, String engine, Map<LocalDate, List<Pos>> days) throws Exception {
        List<String> lines = new ArrayList<>();
        days.forEach((day, ps) -> ps.forEach(p -> lines.add(line(p, day, null))));
        loadLines(tmp, root, engine, lines);
    }

    static void loadLines(Path tmp, Path root, String engine, List<String> lines) throws Exception {
        Path file = Files.createTempFile(tmp, "rows", ".jsonl");
        Files.write(file, lines, StandardCharsets.UTF_8);
        IcebergLoader.load(new String[] {file.toString(), root.toString(), "--engine", engine, "--spill-dir", tmp.toString(), "--file-rows", "7"});
    }

    /** The same positions into a Delta table at {@code table} with Drishti's Delta writer (one overwrite of each date). */
    static void loadDelta(Path tmp, Path table, Map<LocalDate, List<Pos>> days) throws Exception {
        for (Map.Entry<LocalDate, List<Pos>> e : days.entrySet()) {
            List<String> rows = new ArrayList<>();
            for (Pos p : e.getValue()) {
                ObjectNode cols = JSON.createObjectNode();
                cols.put("DESK", p.desk()).put("TT_BOOK", p.book()).put("QUANTITY", p.quantity());
                if (p.pnl() == null) {
                    cols.putNull("PNL");
                } else {
                    cols.set("PNL", array(p.pnl()));
                }
                rows.add(JSON.createObjectNode().put("id", p.id()).put("doc", doc(p)).<ObjectNode>set("columns", cols).toString());
            }
            Path rowsFile = Files.createTempFile(tmp, "delta", ".rows");
            Files.write(rowsFile, rows, StandardCharsets.UTF_8);
            ObjectNode m = JSON.createObjectNode();
            m.put("table", table.toString());
            m.putArray("partitionColumns").add("business_date");
            ArrayNode c = m.putArray("columns");
            for (String[] nt : new String[][] {{"id", "string"}, {"doc", "string"}, {"business_date", "date"}, {"DESK", "string"},
                    {"TT_BOOK", "string"}, {"QUANTITY", "double"}, {"PNL", "vector"}}) {
                c.addObject().put("name", nt[0]).put("type", nt[1]);
            }
            m.putArray("promoted").add("DESK").add("TT_BOOK").add("QUANTITY").add("PNL");
            m.put("mode", "overwrite-dates").put("batchRows", 11);
            m.putArray("statsColumns").add("id").add("business_date");
            m.putArray("parts").addObject().put("file", rowsFile.toString()).putObject("values").put("business_date", e.getKey().toString());
            Path mf = Files.createTempFile(tmp, "manifest", ".json");
            Files.writeString(mf, m.toString());
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
            assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        }
    }

    /** A connector's settings over {@code root} (domain risk), its disk caches under {@code cache}. */
    static Map<String, String> settings(Path root, String engine, Path cache) {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "risk", "source-name", "risk-store", "engine", engine,
                "layout.pnl-vector.columns", "DESK,TT_BOOK,QUANTITY", "layout.pnl-vector.vectors", "PNL", "refresh-seconds", "3600"));
        s.put("id-map-disk.dir", cache.resolve("idmaps").toString());
        s.put("doc-disk-cache.dir", cache.resolve("docs").toString());
        return s;
    }
}
