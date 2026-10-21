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
package com.ash.drishti.rachana.ui.migrate;

import com.ash.drishti.rachana.ui.ChipCatalog;
import com.ash.drishti.rachana.ui.UiDocumentException;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The dry run of a pack's conversion (docs/architecture/UI_BOARD.md 11.2, phase P5): every entity page (Sutra) of the pack and every BI report
 * page over the pack's kinds converted in memory, with the board check run on each board. Nothing is saved. Used by {@code drishti.py ui convert
 * --pack P --dry-run} and by {@code POST /api/v1/ui/migrate/dry-run}.
 */
public final class PackDryRun {

    private PackDryRun() {}

    /** Every conversion of {@code pack}: its Sutras (blocks are not screens), then the BI reports whose model reads its kinds. */
    public static List<Conversion> convert(Path packsDir, Path biDir, String pack, ChipCatalog chips) throws IOException {
        Path dir = packsDir.resolve(pack);
        if (!Files.isRegularFile(dir.resolve("pack.yaml"))) {
            throw new IOException("no pack '" + pack + "' in " + packsDir);
        }
        List<Conversion> out = new ArrayList<>();
        PageConverter pages = new PageConverter(chips);
        Path sutras = dir.resolve("sutras");
        if (Files.isDirectory(sutras)) {
            try (Stream<Path> s = Files.walk(sutras)) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".sutra.yaml")).sorted().toList()) {
                    Conversion c = pages.convert(pack, Files.readString(f, StandardCharsets.UTF_8));
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
        }
        if (biDir != null && Files.isDirectory(biDir.resolve("reports"))) {
            Map<String, String> kindPack = kindPacks(packsDir);
            ReportConverter reports = new ReportConverter(chips);
            try (Stream<Path> s = Files.list(biDir.resolve("reports"))) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".report.yaml")).sorted().toList()) {
                    JsonNode r = read(f);
                    if (r != null && pack.equals(packOf(r, biDir, kindPack))) {
                        out.addAll(reports.convert(pack, r));
                    }
                }
            }
        }
        return out;
    }

    /** The pack a report belongs to: the pack owning the kind its model's first dataset reads; {@code (bi)} when none does. */
    static String packOf(JsonNode report, Path biDir, Map<String, String> kindPack) {
        String model = report.path("model").asText("").replaceAll("@.*", "").trim();
        JsonNode m = read(biDir.resolve("models").resolve(model + ".model.yaml"));
        if (m == null) {
            return "(bi)";
        }
        var it = m.path("datasets").elements();
        while (it.hasNext()) {
            JsonNode ds = read(biDir.resolve("datasets").resolve(it.next().path("dataset").asText("") + ".dataset.yaml"));
            String kind = ds == null ? null : ds.path("source").path("kind").asText(null);
            if (kind != null && kindPack.containsKey(kind)) {
                return kindPack.get(kind);
            }
        }
        return "(bi)";
    }

    static Map<String, String> kindPacks(Path packsDir) throws IOException {
        Map<String, String> m = new HashMap<>();
        try (Stream<Path> s = Files.list(packsDir)) {
            for (Path d : s.sorted().toList()) {
                JsonNode p = read(d.resolve("pack.yaml"));
                if (p != null) {
                    String name = p.path("pack").asText(d.getFileName().toString());
                    p.path("kinds").forEach(k -> m.putIfAbsent(k.asText(), name));
                }
            }
        }
        return m;
    }

    private static JsonNode read(Path f) {
        try {
            return Files.isRegularFile(f) ? UiDocuments.tree(Files.readString(f, StandardCharsets.UTF_8)) : null;
        } catch (IOException | UiDocumentException e) {
            return null;
        }
    }

    /** The report (11.2): counts, the board-local chips as Chip Store candidates, and one line per screen. */
    public static ObjectNode report(String pack, List<Conversion> all) {
        ObjectNode r = BoardSutra.JSON.createObjectNode();
        r.put("pack", pack);
        r.put("screens", all.size());
        r.put("clean", all.stream().filter(c -> c.status() == Conversion.Status.CLEAN).count());
        r.put("local", all.stream().filter(c -> c.status() == Conversion.Status.LOCAL).count());
        r.put("fails", all.stream().filter(c -> c.status() == Conversion.Status.FAILS).count());
        r.put("entityPages", all.stream().filter(c -> c.screen().startsWith("sutra:")).count());
        r.put("reportPages", all.stream().filter(c -> c.screen().startsWith("report:")).count());
        r.put("localChips", all.stream().mapToInt(c -> c.locals().size()).sum());
        ArrayNode items = r.putArray("items");
        for (Conversion c : all) {
            ObjectNode o = items.addObject();
            o.put("screen", c.screen()).put("board", c.board()).put("status", c.status().text());
            ArrayNode seeds = o.putArray("chips");
            c.seeds().forEach(seeds::add);
            ArrayNode locals = o.putArray("localChips");
            c.locals().forEach(l -> locals.addObject().put("chip", l.chip()).put("from", l.from()).put("reason", l.reason()));
            o.put("links", c.links()).put("traces", c.traces());
            if (c.reason() != null) {
                o.put("reason", c.reason());
            }
            o.put("warnings", c.problems().stream().filter(p -> !p.error()).count());
        }
        return r;
    }

    /** The report as text: a summary line, then the screens that need board-local chips or fail. */
    public static void print(ObjectNode r, PrintStream out) {
        out.println("pack " + r.path("pack").asText() + ": " + r.path("screens").asInt() + " screen(s) (" + r.path("entityPages").asInt() + " entity pages, "
                + r.path("reportPages").asInt() + " report pages): " + r.path("clean").asInt() + " clean, " + r.path("local").asInt()
                + " with board-local chips (" + r.path("localChips").asInt() + " chips), " + r.path("fails").asInt() + " fail");
        for (JsonNode i : r.path("items")) {
            String st = i.path("status").asText();
            if ("clean".equals(st)) {
                continue;
            }
            out.println("  " + st + "  " + i.path("screen").asText() + " -> board " + i.path("board").asText()
                    + ("fails".equals(st) ? ": " + i.path("reason").asText() : ": " + i.path("localChips").size() + " local chip(s)"));
        }
    }

    /** True when no screen failed. */
    public static boolean ok(List<Conversion> all) {
        return all.stream().noneMatch(c -> c.status() == Conversion.Status.FAILS);
    }
}
