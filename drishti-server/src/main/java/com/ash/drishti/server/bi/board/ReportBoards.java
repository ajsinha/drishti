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

import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boards as a report layout (docs/architecture/BI_DESIGN.md section 11.1, item R7, docs/guides/REPORTS.md): the pages of a report with {@code layout: board}, read as the
 * {@link Board} the tile machinery knows (tile resolution, tile alerts and their notifications). A {@code tile} visual names a visual of another report
 * ({@code from:}) and may carry an {@code alert:}; the report's {@code board:} holds the kiosk rotation and the notification defaults. Ordinary visuals on a
 * board page are report visuals: they take the report's filters, slicers, cross-filter and drill, and are not part of the {@link Board}.
 *
 * <p>The checks are the board parser's own (the same thresholds, notify keys and rotation), run on a board built from the report, their paths given back in
 * the report's terms ({@code pages[1].visuals[3].alert.warn}).
 */
public final class ReportBoards {

    /** The board read from a report's board pages (null when it has no tile) and what the checks found. */
    public record Built(Board board, List<Problem> problems) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TILE_PATH = Pattern.compile("^pages\\[(\\d+)\\]\\.tiles\\[(\\d+)\\](.*)$");

    private ReportBoards() {}

    /** The board of a report: one board page per report page with {@code layout: board} that holds tiles, each tile from a {@code tile} visual. */
    public static Built of(Report r) {
        ObjectNode root = JSON.createObjectNode();
        root.put("rachana", 1);
        root.put("board", r.name() == null || r.name().isBlank() ? "board" : r.name());
        root.put("version", r.version());
        root.put("title", r.title());
        if (!r.description().isBlank()) {
            root.put("description", r.description());
        }
        if (r.sample()) {
            root.put("sample", true);
        }
        Object rotate = r.board().get("rotate");
        if (rotate != null) {
            root.put("rotate", String.valueOf(rotate));
        }
        if (r.board().get("notify") != null) {
            root.set("notify", JSON.valueToTree(r.board().get("notify")));
        }
        ArrayNode pages = root.putArray("pages");
        List<int[]> pageAt = new ArrayList<>();                         // board page index -> report page index
        List<List<Integer>> tileAt = new ArrayList<>();                 // board page index -> report visual index of each tile
        for (int pi = 0; pi < r.pages().size(); pi++) {
            Report.Page p = r.pages().get(pi);
            List<Integer> at = new ArrayList<>();
            ArrayNode tiles = JSON.createArrayNode();
            for (int vi = 0; vi < p.visuals().size(); vi++) {
                Report.Visual v = p.visuals().get(vi);
                if (!"tile".equals(v.type()) || v.from() == null) {
                    continue;
                }
                ObjectNode t = tiles.addObject();
                t.put("id", v.id());
                t.put("report", v.from().report());
                if (v.from().page() != null && !v.from().page().isBlank()) {
                    t.put("page", v.from().page());
                }
                t.put("visual", v.from().visual());
                if (!v.title().isBlank()) {
                    t.put("title", v.title());
                }
                t.set("at", JSON.valueToTree(v.at().toMap()));
                if (!v.alert().isEmpty()) {
                    t.set("alert", JSON.valueToTree(v.alert()));
                }
                at.add(vi);
            }
            if (p.board() && !at.isEmpty()) {
                ObjectNode bp = pages.addObject();
                bp.put("id", p.id());
                bp.put("title", p.title());
                bp.set("tiles", tiles);
                pageAt.add(new int[] {pi});
                tileAt.add(at);
            }
        }
        if (pages.isEmpty()) {
            return new Built(null, List.of());
        }
        BoardParser.Result parsed = BoardParser.parse(root);
        List<Problem> out = new ArrayList<>();
        for (Problem p : parsed.problems()) {
            out.add(new Problem(p.level(), p.code(), path(p.path(), pageAt, tileAt), p.message()));
        }
        return new Built(parsed.board(), out);
    }

    /** A board path in the report's terms: a tile is a visual of its page, its report and visual are under {@code from}, the board's settings under {@code board}. */
    static String path(String boardPath, List<int[]> pageAt, List<List<Integer>> tileAt) {
        Matcher m = TILE_PATH.matcher(boardPath);
        if (m.matches()) {
            int bp = Integer.parseInt(m.group(1));
            int bt = Integer.parseInt(m.group(2));
            if (bp < pageAt.size() && bt < tileAt.get(bp).size()) {
                String rest = m.group(3);
                if (rest.equals(".report") || rest.equals(".visual") || rest.equals(".page")) {
                    rest = ".from" + rest;
                }
                return "pages[" + pageAt.get(bp)[0] + "].visuals[" + tileAt.get(bp).get(bt) + "]" + rest;
            }
        }
        if (boardPath.equals("rotate") || boardPath.startsWith("notify")) {
            return "board." + boardPath;
        }
        return boardPath;
    }

    /**
     * The board an item's text holds: a report's board pages, or (until the start-up migration has converted it) a 2.3 {@code board} item; null for a report
     * without tiles or a text that does not parse.
     */
    public static Board boardOf(String kind, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        if ("board".equals(kind)) {
            return BoardParser.parse(body).board();
        }
        Report r = ReportParser.parse(body).report();
        return r == null ? null : of(r).board();
    }

    /** The board of a text of either kind: a 2.3 board, or a report with board pages; null when it has none. */
    public static Board boardOfText(String body) {
        return boardOf(BoardConverter.isBoard(body) ? "board" : "report", body);
    }

    /** Whether an item's text shows tiles (a board item, or a report with board pages that hold tiles): what board notifications and publishing look at. */
    public static boolean isBoard(String kind, String body) {
        if ("board".equals(kind)) {
            return true;
        }
        if (!"report".equals(kind) || body == null || !body.contains("board")) {
            return false;
        }
        Report r = ReportParser.parse(body).report();
        return r != null && r.hasBoard();
    }

    /** The settings a converted board keeps at the report's top: {@code rotate} and {@code notify}, as written. */
    static Map<String, Object> settings(Board b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b.rotateSeconds() != null) {
            m.put("rotate", b.rotateSeconds() + "s");
        }
        if (b.tell() != null) {
            m.put("notify", b.tell().toMap());
        }
        return m;
    }

    /** The problems of a report's board pages, for the report parser. */
    public static List<Problem> check(Report r) {
        List<Problem> out = new ArrayList<>(of(r).problems());
        for (int pi = 0; pi < r.pages().size(); pi++) {
            Report.Page p = r.pages().get(pi);
            for (int vi = 0; vi < p.visuals().size(); vi++) {
                Report.Visual v = p.visuals().get(vi);
                String path = "pages[" + pi + "].visuals[" + vi + "]";
                if ("tile".equals(v.type()) && !p.board()) {
                    out.add(ReportParser.error("DRS-9032", path + ".type", "a tile belongs on a board page: set the page's layout to board (`layout: board`)"));
                }
                if ("tile".equals(v.type()) && v.from() == null) {
                    out.add(ReportParser.error("DRS-9032", path + ".from", "a tile shows a visual of a report: from: { report: <report>, visual: <visual id> }"));
                }
                if (!"tile".equals(v.type()) && !v.alert().isEmpty()) {
                    out.add(new Problem("warning", "DRS-9030", path + ".alert", "only a tile takes an alert (a colour rule); for this visual use Conditional formatting, or an alert from its Alert me button"));
                }
            }
        }
        return out;
    }
}
