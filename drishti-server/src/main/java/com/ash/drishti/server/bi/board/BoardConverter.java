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
package com.ash.drishti.server.bi.board;

import com.ash.drishti.common.Branding;
import com.ash.drishti.server.bi.report.ReportYaml;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a 2.3 board ({@code board:} item, docs/guides/BOARDS.md) into a report whose pages have {@code layout: board} (R7, 2.4.0): each tile becomes a
 * {@code tile} visual with {@code from: { report, page, visual }} and its {@code alert:}, the board's {@code rotate} and {@code notify} move under the report's
 * {@code board:}, and the item keys (title, description, sample, tags, endorsement, owner, audience) are kept. The comment lines at the top of the file are kept
 * and a line says where the report came from. The start-up migration ({@code drishti.bi.migrate.on-start}) uses it on stored boards, the BI folder on shipped
 * board files, and {@code drishti.py bi migrate --boards} on files.
 */
public final class BoardConverter {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Item keys a board and a report share, copied as they are. */
    private static final List<String> KEPT = List.of("version", "title", "description", "sample", "owner", "tags", "endorsement", "endorsedBy", "audience");
    private static final Set<String> TILE_KEYS = Set.of("id", "report", "page", "visual", "title", "at", "alert");

    private BoardConverter() {}

    /** True for a text that is a 2.3 board ({@code board:} names it and its pages hold {@code tiles:}). */
    public static boolean isBoard(String text) {
        try {
            JsonNode root = text == null || text.isBlank() ? null : YAML.readTree(text);
            return root != null && root.isObject() && root.path("board").isTextual() && root.has("pages") && !root.has("report");
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * The report text of a board text, or null when the text is not a board that parses (it is left as it is and the migration lists it).
     *
     * @param origin where the board came from, for the note at the top ({@code boards/desk-wall.board.yaml}, or the item's title)
     */
    @SuppressWarnings("unchecked")
    public static String toReport(String text, String origin) {
        if (!isBoard(text) || !convertible(BoardParser.parse(text))) {                  // a board with errors stays as it is: the migration lists it
            return null;
        }
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (java.io.IOException e) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 1);
        m.put("report", root.path("board").asText());
        for (String k : KEPT) {
            if (root.has(k) && !root.get(k).isNull()) {
                m.put(k, JSON.convertValue(root.get(k), Object.class));
            }
        }
        Map<String, Object> board = new LinkedHashMap<>();
        if (root.has("rotate") && !root.get("rotate").isNull()) {
            board.put("rotate", root.get("rotate").asText());
        }
        if (root.has("notify") && !root.get("notify").isNull()) {
            board.put("notify", JSON.convertValue(root.get("notify"), Object.class));
        }
        if (!board.isEmpty()) {
            m.put("board", board);
        }
        List<Object> pages = new ArrayList<>();
        int pi = 0;
        for (JsonNode p : root.path("pages")) {
            pi++;
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", p.path("id").asText("page" + pi));
            page.put("title", p.path("title").asText(p.path("id").asText("page" + pi)));
            page.put("layout", "board");
            List<Object> visuals = new ArrayList<>();
            int ti = 0;
            for (JsonNode t : p.path("tiles")) {
                ti++;
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", t.path("id").asText("t" + ti));
                v.put("type", "tile");
                if (t.hasNonNull("title")) {
                    v.put("title", t.get("title").asText());
                }
                Map<String, Object> from = new LinkedHashMap<>();
                from.put("report", t.path("report").asText());
                if (t.hasNonNull("page") && !t.get("page").asText().isBlank()) {
                    from.put("page", t.get("page").asText());
                }
                from.put("visual", t.path("visual").asText());
                v.put("from", from);
                v.put("at", JSON.convertValue(t.path("at"), Map.class));
                if (t.hasNonNull("alert")) {
                    v.put("alert", JSON.convertValue(t.get("alert"), Map.class));
                }
                t.fieldNames().forEachRemaining(k -> {
                    if (!TILE_KEYS.contains(k)) {
                        v.put(k, JSON.convertValue(t.get(k), Object.class));             // kept for the report's parser to name (it warns)
                    }
                });
                visuals.add(v);
            }
            page.put("visuals", visuals);
            pages.add(page);
        }
        m.put("pages", pages);
        return header(text, origin) + ReportYaml.write(m);
    }

    /**
     * What an item created with the 2.3 kind {@code board} is stored as: a report with board pages (an empty one for an empty body, the conversion of a board
     * that parses, the text itself when it is already a report); null for a board text with errors, which stays a 2.3 board and is refused where it is used.
     */
    /** Parsed, and every error is a page with no tiles yet: an empty board page is a valid report page (the designer starts from one). */
    private static boolean convertible(BoardParser.Result r) {
        return r.board() != null && r.problems().stream().filter(p -> p.error()).allMatch(p -> p.path().endsWith(".tiles") && p.message().startsWith("this page has no tiles yet"));
    }

    public static String onCreate(String content, String title) {
        if (content == null || content.isBlank()) {
            return emptyBoard(title);
        }
        if (isBoard(content)) {
            return toReport(content, title);
        }
        return content.matches("(?s).*(?m:^report:).*") ? content : null;
    }

    /** A new, empty board: a report with one board page and nothing on it yet (what creating an item of the 2.3 kind {@code board} makes). */
    public static String emptyBoard(String title) {
        String t = title == null || title.isBlank() ? "Board" : title;
        String name = t.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 1);
        m.put("report", name.isEmpty() ? "board" : name.length() > 60 ? name.substring(0, 60) : name);
        m.put("title", t);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("id", "page1");
        page.put("title", "Page 1");
        page.put("layout", "board");
        page.put("visuals", List.of());
        m.put("pages", List.of(page));
        return ReportYaml.write(m);
    }

    /** The comment lines at the top of the board, then a note of the conversion. */
    private static String header(String text, String origin) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                out.append(line).append('\n');
            } else {
                break;
            }
        }
        String note = "# CONVERTED from the board " + (origin == null || origin.isBlank() ? "" : origin + " ") + "by " + Branding.name("the server") + " 2.4.0: a board is a report whose pages have layout: board\n"
                + "# (docs/guides/BOARDS.md). Each tile is a visual of type tile showing a visual of another report (from:); rotate and notify are under board:.\n\n";
        String head = out.toString();
        while (head.endsWith("\n\n")) {
            head = head.substring(0, head.length() - 1);
        }
        return head + (head.isEmpty() ? "" : "\n") + note;
    }
}
