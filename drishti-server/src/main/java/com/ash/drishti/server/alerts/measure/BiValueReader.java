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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.board.Board;
import com.ash.drishti.server.bi.board.BiBoardService;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the number of a BI source as one person: a visual of a report, a measure of a dataset, or the level of a board tile. The query goes through the same
 * services as the screen (the report service, the dataset engine), so the person's row rules and masks are applied by the engine; a measure that is masked for
 * the person is reported as masked and its value is never read.
 */
public final class BiValueReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BiReportService reports;
    private final DatasetService datasets;
    private final BiBoardService boards;

    public BiValueReader(BiReportService reports, DatasetService datasets, BiBoardService boards) {
        this.reports = reports;
        this.datasets = datasets;
        this.boards = boards;
    }

    public Reading read(AlertSpec spec, Principal who, LocalDate date) {
        AlertSpec.Source s = spec.source();
        try {
            return switch (s.type()) {
                case "visual" -> visual(s, who, date);
                case "dataset" -> dataset(s, who, date);
                default -> tile(s, who, date);
            };
        } catch (DrishtiException e) {
            return noAccessOrError(e);
        } catch (RuntimeException e) {
            return Reading.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static Reading noAccessOrError(DrishtiException e) {
        return switch (e.errorCode()) {
            case BI_NOT_FOUND, BI_FORBIDDEN, FORBIDDEN -> Reading.error("you cannot open the source any more (" + e.getMessage() + ")");
            default -> Reading.error(e.getMessage());
        };
    }

    // ---- a visual ------------------------------------------------------------------------------------------------------------

    private Reading visual(AlertSpec.Source s, Principal who, LocalDate date) {
        String page = s.page().isBlank() ? pageOf(who, s.report(), s.visual()) : s.page();
        Map<String, Object> answer = reports.query(who, s.report(), null, page, s.visual(), stateOf(s.state(), date), false);
        return reduce(answer, s.measure(), s.reduce());
    }

    private String pageOf(Principal who, String report, String visual) {
        Report r = reports.open(who, report).report();
        for (Report.Page p : r.pages()) {
            if (p.visual(visual) != null) {
                return p.id();
            }
        }
        throw new DrishtiException(ErrorCode.REPORT_STATE, "the report has no visual '" + visual + "'");
    }

    private static JsonNode stateOf(JsonNode state, LocalDate date) {
        ObjectNode st = state != null && state.isObject() ? ((ObjectNode) state).deepCopy() : JSON.createObjectNode();
        if (date != null) {
            st.set("dates", JSON.createObjectNode().put("from", date.toString()).put("to", date.toString()));
        }
        return st;
    }

    // ---- a dataset measure ---------------------------------------------------------------------------------------------------

    private Reading dataset(AlertSpec.Source s, Principal who, LocalDate date) {
        ObjectNode q = JSON.createObjectNode();
        q.putArray("measures").add(s.measure());
        if (s.filters() != null) {
            q.set("filters", s.filters());
        }
        if (date != null) {
            q.set("dates", JSON.createObjectNode().put("from", date.toString()).put("to", date.toString()));
        }
        q.put("limit", 1);
        Map<String, Object> answer = datasets.json(datasets.query(who, s.dataset(), QueryRequest.of(q), false));
        return reduce(answer, s.measure(), "first");
    }

    // ---- a board tile --------------------------------------------------------------------------------------------------------

    private Reading tile(AlertSpec.Source s, Principal who, LocalDate date) {
        Board.Tile t = new Board.Tile("t", s.report(), s.page(), s.visual(), "", new Report.Grid(0, 0, 1, 1), s.tile());
        Optional<BiBoardService.TileTarget> target = boards.target(who, t);
        if (target.isEmpty()) {
            return Reading.error("the tile's report or visual is not available to you");
        }
        Map<String, Object> answer = reports.query(who, target.get().report(), null, target.get().page(), s.visual(), stateOf(null, date), false);
        return level(answer, s.tile());
    }

    // ---- reading an answer ---------------------------------------------------------------------------------------------------

    /** The level of a tile ({@code 0} none, {@code 1} warn, {@code 2} crit), judged like the viewer does: the worst of the visual's rows. */
    @SuppressWarnings("unchecked")
    Reading level(Map<String, Object> answer, Board.Alert alert) {
        List<Map<String, Object>> cols = (List<Map<String, Object>>) answer.get("columns");
        List<List<Object>> rows = (List<List<Object>>) answer.get("rows");
        int ci = -1;
        if (alert != null && alert.on() != null && !alert.on().isBlank()) {
            ci = indexOf(cols, alert.on());
        }
        if (ci < 0) {
            ci = firstNumeric(cols);
        }
        if (ci < 0) {
            return Reading.noData("the tile shows no number");
        }
        if (Boolean.TRUE.equals(cols.get(ci).get("masked"))) {
            return Reading.masked(String.valueOf(cols.get(ci).get("name")));
        }
        List<Double> values = new ArrayList<>();
        for (List<Object> row : rows) {
            if (row.get(ci) instanceof Number n) {
                values.add(n.doubleValue());
            }
        }
        if (values.isEmpty()) {
            return Reading.noData("the tile has no rows");
        }
        return Reading.tile(TileLevel.rank(alert, values), TileLevel.relaxed(alert, values), asOf(answer));
    }

    @SuppressWarnings("unchecked")
    static Reading reduce(Map<String, Object> answer, String measure, String how) {
        List<Map<String, Object>> cols = (List<Map<String, Object>>) answer.get("columns");
        List<List<Object>> rows = (List<List<Object>>) answer.get("rows");
        int ci = measure == null || measure.isBlank() ? -1 : indexOf(cols, measure);
        if (ci < 0 && measure != null && !measure.isBlank()) {
            return Reading.error("the answer has no column '" + measure + "'");
        }
        if (ci < 0) {
            ci = firstNumeric(cols);
        }
        if (ci < 0) {
            return Reading.error("the answer has no number column");
        }
        if (Boolean.TRUE.equals(cols.get(ci).get("masked"))) {
            return Reading.masked(String.valueOf(cols.get(ci).get("name")));
        }
        List<Double> values = new ArrayList<>();
        for (List<Object> row : rows) {
            if (row.get(ci) instanceof Number n && Double.isFinite(n.doubleValue())) {
                values.add(n.doubleValue());
            }
        }
        if (values.isEmpty()) {
            return Reading.noData(rows.isEmpty() ? "no rows" : "the number is empty");
        }
        double v = switch (how) {
            case "last" -> values.get(values.size() - 1);
            case "sum" -> values.stream().mapToDouble(Double::doubleValue).sum();
            case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            case "avg" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            case "count" -> values.size();
            default -> values.get(0);
        };
        return Reading.ok(v, asOf(answer));
    }

    @SuppressWarnings("unchecked")
    private static LocalDate asOf(Map<String, Object> answer) {
        Object meta = answer.get("meta");
        Object dates = meta instanceof Map<?, ?> m ? m.get("dates") : null;
        if (dates instanceof List<?> l && !l.isEmpty()) {
            try {
                return LocalDate.parse(String.valueOf(l.get(l.size() - 1)));
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static int indexOf(List<Map<String, Object>> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            String n = String.valueOf(cols.get(i).get("name"));
            if (n.equals(name) || n.substring(n.lastIndexOf('.') + 1).equals(name.substring(name.lastIndexOf('.') + 1))) {
                return i;
            }
        }
        return -1;
    }

    private static int firstNumeric(List<Map<String, Object>> cols) {
        for (int i = 0; i < cols.size(); i++) {
            String t = String.valueOf(cols.get(i).get("type"));
            if (t.equals("decimal") || t.equals("integer")) {
                return i;
            }
        }
        return -1;
    }
}
