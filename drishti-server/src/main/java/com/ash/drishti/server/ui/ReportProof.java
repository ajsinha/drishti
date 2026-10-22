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
package com.ash.drishti.server.ui;

import com.ash.drishti.rachana.ui.migrate.Conversion;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The data proof of a pack's BI report pages (docs/architecture/UI_BOARD.md 12.4): per page and person, the report as written and the report
 * served from its converted boards are both opened ({@code get}) and every visual that reads data is asked ({@code query}, what a BI run of
 * the visual computes, as that person: their row rules and masks); the two answers are compared as JSON trees after dropping what names
 * the run (timings, sequence numbers). A page is proven when every answer agrees for every person, and the boards one also says it was
 * served from its board. Two refusals with the same message agree.
 */
public final class ReportProof {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** What names a run, not the data: dropped before comparing. */
    static final Set<String> RUN_KEYS = Set.of("timings", "millis", "elapsedMs", "tookMs", "ms", "seq", "since", "live", "runId", "cache", "cached", "servedFrom",
            "computedAt", "at");

    private final PackScreens screens;
    private final BiReportService reports;
    private int withRows;

    public ReportProof(PackScreens screens, BiReportService reports) {
        this.screens = screens;
        this.reports = reports;
    }

    /** Proves the report pages of {@code pack} for {@code users}. */
    public ObjectNode prove(String pack, List<Principal> users) {
        ObjectNode r = JSON.createObjectNode();
        r.put("pack", pack);
        ArrayNode items = r.putArray("screens");
        int proven = 0;
        int differ = 0;
        int pairs = 0;
        withRows = 0;
        for (Conversion c : screens.dryRun(pack)) {
            if (!c.screen().startsWith("report:")) {
                continue;
            }
            String[] np = c.screen().substring("report:".length()).split("/", 2);
            String id = "file:report:" + np[0];
            ObjectNode o = items.addObject();
            o.put("screen", c.screen()).put("board", c.board());
            if (c.status() == Conversion.Status.FAILS || c.document() == null) {
                o.put("result", "not converted").put("reason", c.reason());
                differ++;
                continue;
            }
            ObjectNode diff = null;
            int compared = 0;
            for (Principal u : users) {
                JsonNode served;
                try {
                    served = JSON.valueToTree(PackScreens.as(PackScreens.Mode.BOARDS, () -> reports.get(u, id)));
                } catch (RuntimeException e) {
                    served = null;                                   // refused: compared below, both must refuse
                }
                if (served != null && !c.board().equals(served.path("servedFrom").path(np[1]).asText())) {
                    diff = difference(u, np[1], "(served)", "the old page", "not served from its board");
                    break;
                }
                diff = compare(u, np[1], "(report)", () -> reports.get(u, id));
                compared++;
                var vis = c.document().path("chips").fields();
                while (diff == null && vis.hasNext()) {
                    var v = vis.next();
                    if (!v.getValue().has("supply")) {
                        continue;                                    // a tile, text or image: no data of its own (a tile's is its report's)
                    }
                    String visual = v.getKey();
                    diff = compare(u, np[1], visual, () -> reports.query(u, id, null, np[1], visual, null, false));
                    compared++;
                }
                if (diff != null) {
                    break;
                }
            }
            o.put("compared", compared);
            pairs += compared;
            if (diff == null) {
                o.put("result", "proven");
                proven++;
            } else {
                o.put("result", "differs").set("difference", diff);
                differ++;
            }
        }
        r.put("proven", proven).put("differs", differ).put("comparisons", pairs).put("answersWithRows", withRows);
        return r;
    }

    private ObjectNode compare(Principal u, String page, String visual, Supplier<Object> ask) {
        JsonNode old = run(() -> PackScreens.as(PackScreens.Mode.SUTRAS, ask));
        if (old.path("rows").size() > 0) {
            withRows++;                                              // an answer with rows: data compared, not two empty answers
        }
        JsonNode board = run(() -> PackScreens.as(PackScreens.Mode.BOARDS, ask));
        String path = BoardProof.firstDifference(old, board, "");
        if (path == null) {
            return null;
        }
        String a = String.valueOf(at(old, path));
        String b = String.valueOf(at(board, path));
        return difference(u, page, visual + ":" + (path.isEmpty() ? "(answer)" : path), a, b);
    }

    private static JsonNode at(JsonNode n, String path) {
        JsonNode cur = n;
        for (String seg : path.isEmpty() ? new String[0] : path.split("\\.")) {
            String name = seg.contains("[") ? seg.substring(0, seg.indexOf('[')) : seg;
            cur = name.isEmpty() ? cur : cur == null ? null : cur.get(name);
            var m = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(seg);
            while (cur != null && m.find()) {
                cur = cur.get(Integer.parseInt(m.group(1)));
            }
        }
        String s = cur == null ? "(absent)" : cur.isValueNode() ? cur.asText() : cur.toString();
        return JSON.getNodeFactory().textNode(s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }

    private static ObjectNode difference(Principal u, String page, String path, String old, String board) {
        ObjectNode d = JSON.createObjectNode();
        d.put("user", u.user()).put("page", page).put("path", path).put("report", old).put("board", board);
        return d;
    }

    private static JsonNode run(Supplier<Object> ask) {
        try {
            return strip(JSON.valueToTree(ask.get()));
        } catch (RuntimeException e) {
            return JSON.createObjectNode().put("error", String.valueOf(e.getMessage()));
        }
    }

    private static JsonNode strip(JsonNode n) {
        if (n instanceof ObjectNode o) {
            RUN_KEYS.forEach(o::remove);
            o.forEach(ReportProof::strip);
        } else if (n instanceof ArrayNode a) {
            a.forEach(ReportProof::strip);
        }
        return n;
    }
}
