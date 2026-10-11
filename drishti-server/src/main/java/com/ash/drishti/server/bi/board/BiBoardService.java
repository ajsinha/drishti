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
 * Boards for the people who watch them (RUPAKA.md section 12): finds the board item the caller may read, parses it, and resolves each tile to the report item
 * it names, so the viewer can ask that report for the tile's data (the report service decides what the caller may see: its dataset, their rows and masks). A
 * board holds no data and no query of its own.
 */
public final class BiBoardService {

    private final BiService bi;
    private final BiReportService reports;

    public BiBoardService(BiService bi, BiReportService reports) {
        this.bi = bi;
        this.reports = reports;
    }

    private BiStore.Item boardItem(Principal p, String id) {
        BiStore.Item i = bi.readableItem(p, id);
        if (!"board".equals(i.kind())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + id + "' is not a board");
        }
        return i;
    }

    /** The report a tile names: by catalogue id, shipped file name, the report's own name or its title, among the reports the caller may see. */
    BiStore.Item reportFor(Principal p, String ref) {
        List<BiStore.Item> pool = bi.reportItems(p);
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

    /** What a tile names inside its report, checked: the report exists for the caller, it has the page and the visual. */
    private List<ReportParser.Problem> tiles(Principal p, Board board, Map<String, Map<String, Object>> resolved) {
        List<ReportParser.Problem> out = new ArrayList<>();
        Map<String, Report> parsed = new LinkedHashMap<>();
        for (int pi = 0; pi < board.pages().size(); pi++) {
            Board.Page page = board.pages().get(pi);
            for (int ti = 0; ti < page.tiles().size(); ti++) {
                Board.Tile t = page.tiles().get(ti);
                String path = "pages[" + pi + "].tiles[" + ti + "]";
                BiStore.Item item = t.report().isBlank() ? null : reportFor(p, t.report());
                Map<String, Object> info = new LinkedHashMap<>();
                if (item == null) {
                    if (!t.report().isBlank()) {
                        out.add(ReportParser.error(BoardParser.INVALID, path + ".report", "no report '" + t.report() + "' that you can open (a catalogue id, a file name such as live-trading-desk, or the report's name)"));
                    }
                    info.put("report", null);
                    resolved.put(page.id() + "/" + t.id(), info);
                    continue;
                }
                Report r = parsed.computeIfAbsent(item.id(), k -> ReportParser.parse(bi.bodyFor(p, item)).report());
                info.put("report", item.id());
                info.put("reportTitle", item.title());
                if (r == null) {
                    out.add(ReportParser.error(BoardParser.INVALID, path + ".report", "the report '" + item.title() + "' does not parse, so its visuals cannot be shown"));
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
                        out.add(ReportParser.error(BoardParser.INVALID, path + ".visual", "report '" + item.title() + "' has no visual '" + t.visual() + "'" + (t.page().isBlank() ? "" : " on page '" + t.page() + "'")));
                    } else {
                        info.put("page", rp.id());
                        info.put("type", v.type());
                        if ("slicer".equals(v.type())) {
                            out.add(ReportParser.error(BoardParser.INVALID, path + ".visual", "a slicer is a control, not a tile; pick a card, chart or table"));
                        }
                    }
                }
                resolved.put(page.id() + "/" + t.id(), info);
            }
        }
        return out;
    }

    /** One board: its model, its problems, and for each tile the report item it resolves to. */
    public Map<String, Object> get(Principal p, String id) {
        BiStore.Item item = boardItem(p, id);
        BoardParser.Result parsed = BoardParser.parse(bi.bodyFor(p, item));
        List<ReportParser.Problem> problems = new ArrayList<>(parsed.problems());
        Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        if (parsed.board() != null) {
            problems.addAll(tiles(p, parsed.board(), resolved));
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.id());
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

    /** Checks board YAML without storing it (authors): its structure and what its tiles name. */
    public Map<String, Object> validate(Principal p, String yaml) {
        bi.requirePower(p, BiAccess.AUTHOR);
        BoardParser.Result parsed = BoardParser.parse(yaml);
        List<ReportParser.Problem> problems = new ArrayList<>(parsed.problems());
        if (parsed.board() != null) {
            problems.addAll(tiles(p, parsed.board(), new LinkedHashMap<>()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", problems.stream().noneMatch(ReportParser.Problem::error));
        m.put("problems", problems.stream().map(ReportParser.Problem::toMap).toList());
        if (parsed.board() != null) {
            m.put("board", parsed.board().toMap());
        }
        return m;
    }
}
