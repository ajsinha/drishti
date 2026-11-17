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
package com.ash.drishti.bicompute.rows;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A pivot of rows the caller already holds ({@code POST /api/v1/pivot/inline}, bi/TABLES_AND_MODELS.md, Rows held in memory):
 * {@code {fields: [{name, type}], rows: [[...]], view}} read into one {@link RowsProvider} named {@code inline} and the engine's default
 * settings. The one reading of that body: the server's endpoint and every test that stands in for the server (a module's landing test)
 * answer through it, so a stand-in's figures are the engine's.
 *
 * @param core  the engine over the rows
 * @param view  the view asked, its source {@code inline}
 * @param rows  how many rows were given
 */
public record InlinePivot(PivotCore core, PivotView view, int rows) {

    /** The most rows an inline pivot takes (a chart of a table's shown rows is a page, never a lake). */
    public static final int MAX_ROWS = 100_000;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /**
     * The body read.
     *
     * @throws DrishtiException {@code PIVOT_BAD_VIEW} for a bad field, no fields or more than {@link #MAX_ROWS} rows
     */
    @SuppressWarnings("unchecked")
    public static InlinePivot of(Map<String, Object> body) {
        List<PivotProvider.Field> fields = new ArrayList<>();
        for (Object o : body.get("fields") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> f = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String name = String.valueOf(f.get("name"));
            String type = String.valueOf(f.getOrDefault("type", "string"));
            if (!FIELD.matcher(name).matches() || !List.of("string", "number", "date").contains(type)) {
                throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "an inline field is {name: letters, digits and _, type: string, number or date}: " + f);
            }
            fields.add(new PivotProvider.Field(name, type, String.valueOf(f.getOrDefault("label", name)), ""));
        }
        List<?> raw = body.get("rows") instanceof List<?> l ? l : List.of();
        if (fields.isEmpty() || raw.size() > MAX_ROWS) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "an inline pivot needs its fields and at most " + MAX_ROWS + " rows (it has "
                    + raw.size() + "): chart every row with the source's own pivot instead");
        }
        List<Object[]> rows = new ArrayList<>(raw.size());
        for (Object r : raw) {
            List<?> cells = r instanceof List<?> l ? l : List.of();
            Object[] row = new Object[fields.size()];
            for (int i = 0; i < row.length && i < cells.size(); i++) {
                Object x = cells.get(i);
                row[i] = x instanceof Number || x == null ? x : String.valueOf(x);
            }
            rows.add(row);
        }
        Map<String, Object> spec = new LinkedHashMap<>(body.get("view") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        spec.put("source", "inline");
        PivotCore core = new PivotCore(List.of(new RowsProvider("inline", "Rows", fields, rows)), StateCache.none(), PivotCore.Settings.defaults());
        return new InlinePivot(core, PivotView.parse(spec), rows.size());
    }

    /** The engine's answer for this viewer. */
    public Map<String, Object> query(Viewer viewer) {
        return core.query(viewer, view);
    }
}
