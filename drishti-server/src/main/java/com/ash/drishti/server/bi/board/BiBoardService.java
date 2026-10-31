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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boards for the people who watch them (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 12; since 2.4.0 a board is a report whose pages have {@code layout: board}, R7): finds the item the
 * caller may read (a report with board pages, or a 2.3 board item the start-up migration has not converted yet), reads its board, and resolves each tile to
 * the report item it names, so the viewer can ask that report for the tile's data (the report service decides what the caller may see: its dataset, their
 * rows and masks). A tile holds no data and no query of its own. The report service asks this for the tiles of a report it serves ({@link #tileCheck}).
 */
public final class BiBoardService {

    private final BiService bi;
    private final BiReportService reports;

    public BiBoardService(BiService bi, BiReportService reports) {
        this.bi = bi;
        this.reports = reports;
        reports.tileResolver(this::tileCheck);
    }

    /** The board of an item's text: a report's board pages, or a 2.3 board; null when the text has none. */
    private static BoardParser.Result read(BiStore.Item item, String body) {
        if ("board".equals(item.kind())) {
            return BoardParser.parse(body);
        }
        ReportParser.Result r = ReportParser.parse(body);
        if (r.report() == null) {
            return new BoardParser.Result(null, r.problems());
        }
        ReportBoards.Built b = ReportBoards.of(r.report());
        List<ReportParser.Problem> all = new ArrayList<>(r.problems());
        return new BoardParser.Result(b.board(), all);
    }

    private BiStore.Item boardItem(Principal p, String id) {
        BiStore.Item i = bi.readableItem(p, id);
        if (!"board".equals(i.kind()) && !"report".equals(i.kind())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + id + "' is not a board (a report with board pages)");
        }
        return i;
    }

    /** The report a tile names: by catalogue id, shipped file name, the report's own name or its title, among the reports the caller may see. */
    BiStore.Item reportFor(Principal p, String ref) {
        List<BiStore.Item> pool = bi.reportItemsWithDrafts(p);
        for (BiStore.Item i : pool) {
            if (i.id().equals(ref) || i.id().equals("file:report:" + ref)) {
                return i;
            }
        }
        for (BiStore.Item i : pool) {
            if (i.title().equalsIgnoreCase(ref)) {
                return i;
            }
        }
        return null;
    }

    /** The reports the designer offers for a tile: the published ones, then the caller's own drafts (marked {@code draft}); a draft shows for its author only. */
    public List<Map<String, Object>> reportChoices(Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiStore.Item i : bi.reportItemsWithDrafts(p)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("title", i.title());
            m.put("draft", !i.isPublished() && !BiFolder.isShipped(i.id()));
            m.put("status", i.status());
            Report r = ReportParser.parse(bi.bodyFor(p, i)).report();
            if (r != null) {                                                  // the visuals a tile can show, for the designer's picker (no tiles of tiles, no slicers)
                List<Map<String, Object>> visuals = new ArrayList<>();
                for (Report.Page pg : r.pages()) {
                    for (Report.Visual v : pg.visuals()) {
                        if (!"tile".equals(v.type()) && !"slicer".equals(v.type())) {
                            Map<String, Object> x = new LinkedHashMap<>();
                            x.put("page", pg.id());
                            x.put("id", v.id());
                            x.put("type", v.type());
                            x.put("title", v.title().isBlank() ? v.id() : v.title());
                            visuals.add(x);
                        }
                    }
                }
                m.put("visuals", visuals);
            }
            out.add(m);
        }
        return out;
    }

    /** Where a tile's visual is for one person: the report item and the page that holds the visual. */
    public record TileTarget(String report, String page, String reportTitle) {}

    /** The report and page a tile resolves to for this person, or empty when the report is not theirs to see or lacks the visual. */
    public java.util.Optional<TileTarget> target(Principal p, Board.Tile t) {
        BiStore.Item item = t.report().isBlank() ? null : reportFor(p, t.report());
        Report r = item == null ? null : ReportParser.parse(bi.bodyFor(p, item)).report();
        if (r == null) {
            return java.util.Optional.empty();
        }
        for (Report.Page c : r.pages()) {
            if ((t.page().isBlank() || c.id().equals(t.page())) && c.visual(t.visual()) != null) {
                return java.util.Optional.of(new TileTarget(item.id(), c.id(), item.title()));
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * What a tile names inside its report, checked: the report exists for the caller, it has the page and the visual, and the visual is not a slicer or another
     * tile. {@code path} gives a tile's path in the item's own terms (a board's {@code pages[0].tiles[1]}, a report's {@code pages[0].visuals[3]}).
     */
    private List<ReportParser.Problem> tiles(Principal p, Board board, Map<String, Map<String, Object>> resolved, java.util.function.BiFunction<Integer, Integer, String> path) {
        List<ReportParser.Problem> out = new ArrayList<>();
        Map<String, Report> parsed = new LinkedHashMap<>();
        for (int pi = 0; pi < board.pages().size(); pi++) {
            Board.Page page = board.pages().get(pi);
            for (int ti = 0; ti < page.tiles().size(); ti++) {
                Board.Tile t = page.tiles().get(ti);
                String tp = path.apply(pi, ti);
                BiStore.Item item = t.report().isBlank() ? null : reportFor(p, t.report());
                Map<String, Object> info = new LinkedHashMap<>();
                if (item == null) {
                    if (!t.report().isBlank()) {
                        out.add(ReportParser.error(BoardParser.INVALID, tp + "report", "no report '" + t.report() + "' that you can open (a catalogue id, a file name such as live-trading-desk, or the report's name)"));
                    }
                    info.put("report", null);
                    resolved.put(page.id() + "/" + t.id(), info);
                    continue;
                }
                Report r = parsed.computeIfAbsent(item.id(), k -> ReportParser.parse(bi.bodyFor(p, item)).report());
                info.put("report", item.id());
                info.put("reportTitle", item.title());
                if (r == null) {
                    out.add(ReportParser.error(BoardParser.INVALID, tp + "report", "the report '" + item.title() + "' does not parse, so its visuals cannot be shown"));
                } else {
                    Report.Visual v = null;
                    Report.Page rp = null;
                    for (Report.Page candidate : r.pages()) {
                        if ((t.page().isBlank() || candidate.id().equals(t.page())) && candidate.visual(t.visual()) != null) {
                            rp = candidate;
                            v = candidate.visual(t.visual());
                            break;
                        }
                    }
                    if (v == null) {
                        out.add(ReportParser.error(BoardParser.INVALID, tp + "visual", "report '" + item.title() + "' has no visual '" + t.visual() + "'" + (t.page().isBlank() ? "" : " on page '" + t.page() + "'")));
                    } else {
                        info.put("page", rp.id());
                        info.put("type", v.type());
                        if ("slicer".equals(v.type())) {
                            out.add(ReportParser.error(BoardParser.INVALID, tp + "visual", "a slicer is a control, not a tile; pick a card, chart or table"));
                        } else if ("tile".equals(v.type())) {
                            out.add(ReportParser.error(BoardParser.INVALID, tp + "visual", "'" + t.visual() + "' is itself a tile; show the visual it names instead"));
                        }
                    }
                }
                resolved.put(page.id() + "/" + t.id(), info);
            }
        }
        return out;
    }

    private void markLive(Principal p, Map<String, Map<String, Object>> resolved) {
        resolved.forEach((k, info) -> {
            if (info.get("report") != null) {
                try {
                    info.put("live", reports.isLive(p, String.valueOf(info.get("report"))));
                } catch (DrishtiException e) {
                    info.put("live", false);
                }
            }
        });
    }

    /** The tiles of a report's board pages, for the report service: resolved for the caller, with the problems at the report's paths. */
    private BiReportService.TileCheck tileCheck(Principal p, Report r) {
        Board board = ReportBoards.of(r).board();
        if (board == null) {
            return new BiReportService.TileCheck(Map.of(), List.of());
        }
        Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        List<ReportParser.Problem> problems = tiles(p, board, resolved, (pi, ti) -> visualPath(r, board, pi, ti));
        markLive(p, resolved);
        return new BiReportService.TileCheck(new LinkedHashMap<>(resolved), problems);
    }

    /** {@code pages[P].visuals[V].from.} of a board tile, in its report. */
    private static String visualPath(Report r, Board board, int pi, int ti) {
        Board.Page bp = board.pages().get(pi);
        Board.Tile t = bp.tiles().get(ti);
        for (int p = 0; p < r.pages().size(); p++) {
            if (r.pages().get(p).id().equals(bp.id())) {
                List<Report.Visual> vs = r.pages().get(p).visuals();
                for (int v = 0; v < vs.size(); v++) {
                    if (vs.get(v).id().equals(t.id())) {
                        return "pages[" + p + "].visuals[" + v + "].from.";
                    }
                }
            }
        }
        return "pages[" + pi + "].tiles[" + ti + "].";
    }

    /** One board: its model, its problems, and for each tile the report item it resolves to. A report with board pages answers its boards' tiles only. */
    public Map<String, Object> get(Principal p, String id) {
        BiStore.Item item = boardItem(p, id);
        BoardParser.Result parsed = read(item, bi.bodyFor(p, item));
        List<ReportParser.Problem> problems = new ArrayList<>(parsed.problems());
        Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        if (parsed.board() != null) {
            problems.addAll(tiles(p, parsed.board(), resolved, (pi, ti) -> "pages[" + pi + "].tiles[" + ti + "]."));
            markLive(p, resolved);
        } else if ("report".equals(item.kind())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + id + "' has no board page (a page with layout: board and tiles)");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.id());
        m.put("kind", item.kind());
        m.put("title", item.title());
        m.put("description", item.description());
        m.put("status", item.status());
        m.put("shipped", BiFolder.isShipped(item.id()));
        m.put("sample", item.tags().contains(BiFolder.SAMPLE_TAG));
        m.put("valid", problems.stream().noneMatch(ReportParser.Problem::error));
        m.put("problems", problems.stream().map(ReportParser.Problem::toMap).toList());
        if (parsed.board() != null) {
            m.put("board", parsed.board().toMap());
        }
        m.put("tiles", resolved);
        m.put("mayEdit", bi.mayAuthor(p));
        return m;
    }

    /** Checks 2.3 board YAML without storing it (authors): its structure and what its tiles name. Board pages of a report are checked with the report. */
    public Map<String, Object> validate(Principal p, String yaml) {
        bi.requirePower(p, BiAccess.AUTHOR);
        BoardParser.Result parsed = BoardParser.parse(yaml);
        List<ReportParser.Problem> problems = new ArrayList<>(parsed.problems());
        Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        if (parsed.board() != null) {
            problems.addAll(tiles(p, parsed.board(), resolved, (pi, ti) -> "pages[" + pi + "].tiles[" + ti + "]."));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", problems.stream().noneMatch(ReportParser.Problem::error));
        m.put("problems", problems.stream().map(ReportParser.Problem::toMap).toList());
        if (parsed.board() != null) {
            m.put("board", parsed.board().toMap());
        }
        m.put("tiles", resolved);
        return m;
    }

    /** A 2.3 board's YAML converted into the report with board pages it becomes (R7; nothing is stored): what {@code drishti.py bi migrate --boards} writes. */
    public Map<String, Object> convert(Principal p, String yaml) {
        bi.requirePower(p, BiAccess.AUTHOR);
        String report = BoardConverter.toReport(yaml, "");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("converted", report != null);
        if (report != null) {
            m.put("yaml", report);
        } else {
            m.put("problems", BoardParser.parse(yaml).problems().stream().map(ReportParser.Problem::toMap).toList());
        }
        return m;
    }

    /**
     * Writes a board model as the YAML a person writes (the designer's text, the same writer as reports): block style for the board and its pages, one flow-style
     * line for each tile. What it writes is read back by {@link BoardParser} to the same model.
     */
    public Map<String, Object> format(Principal p, com.fasterxml.jackson.databind.JsonNode model) {
        bi.requirePower(p, BiAccess.BI);
        if (model == null || !model.isObject()) {
            throw new DrishtiException(ErrorCode.BOARD_INVALID, "send the board as an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(model, LinkedHashMap.class);
        return Map.of("yaml", com.ash.drishti.server.bi.report.ReportYaml.write(m));
    }
}
