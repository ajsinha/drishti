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
package com.ash.drishti.server.bi.ai;

import com.ash.drishti.server.bi.report.VisualCatalogue;
import java.util.List;
import java.util.Map;

/**
 * The prompts of the BI hooks (docs/guides/BI_AI.md section 6). They are built from the dataset's MODEL only: names, types and descriptions of the fields the
 * reader may use (a field masked for them is left out), the measures' names and descriptions, the hierarchies. No row, no value and no measure definition goes
 * in; in aggregates mode the explain/ask summaries add the reader's own aggregate results (see {@link BiAiService}), never anything else.
 */
final class BiAiPrompts {

    private BiAiPrompts() {}

    /** The dataset model as plain text; fields are written the way a query and a report name them: {@code table.column}. */
    static String model(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        b.append("DATASET ").append(m.get("name")).append(": ").append(one(m.get("description"))).append('\n');
        b.append("FIELDS (write them as table.column):\n");
        for (Object t : list(m.get("tables"))) {
            Map<?, ?> tm = (Map<?, ?>) t;
            b.append("  table ").append(tm.get("name")).append(": ").append(one(tm.get("description"))).append('\n');
            for (Object c : list(tm.get("columns"))) {
                Map<?, ?> cm = (Map<?, ?>) c;
                if (Boolean.TRUE.equals(cm.get("masked"))) {
                    continue;
                }
                b.append("    ").append(tm.get("name")).append('.').append(cm.get("name")).append(' ').append(cm.get("type"));
                String d = one(cm.get("description"));
                b.append(d.isEmpty() ? "" : " - " + d).append('\n');
            }
        }
        b.append("MEASURES (write them by name):\n");
        for (Object x : list(m.get("measures"))) {
            Map<?, ?> mm = (Map<?, ?>) x;
            String d = one(mm.get("description"));
            b.append("  ").append(mm.get("name")).append(d.isEmpty() ? "" : " - " + d).append('\n');
        }
        if (m.get("hierarchies") instanceof Map<?, ?> h && !h.isEmpty()) {
            b.append("HIERARCHIES:\n");
            h.forEach((k, v) -> b.append("  ").append(k).append(": ").append(v).append('\n'));
        }
        return b.toString();
    }

    static String draftSystem() {
        StringBuilder b = new StringBuilder("""
                You write one Drishti report as YAML. Reply with the YAML only: no commentary, no code fence.
                Shape: top-level keys `rachana: 1`, `report: <name: letters, digits, hyphen>`, `version: 1`, `title`, `dataset: <the dataset's name given below>`,
                then `pages:` a list of pages `{id, title, visuals: [...]}`. A visual is
                `{ id, type, title, at: {x, y, w, h}, wells: {...}, alt: <one sentence describing it> }`; the page is a grid 12 columns wide (x + w <= 12, h 2 to 6).
                Wells hold NAMES FROM THE DATASET BELOW ONLY: a field well takes table.column, a measure well takes a measure name exactly as listed.
                Do not invent a field or a measure. Do not write SQL or expressions. Visual types and their wells (field / measure / any, [n] = most items):
                """);
        VisualCatalogue.names().forEach(n -> {
            var t = VisualCatalogue.type(n);
            if (t.wells().isEmpty() || VisualCatalogue.RESERVED.contains(n) || "python".equals(t.group()) || "perspective".equals(t.group())) {
                return;
            }
            StringBuilder w = new StringBuilder();
            t.wells().forEach((k, v) -> w.append(' ').append(k).append(':').append(v.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .append(v.max() > 0 ? "[" + v.max() + "]" : "").append(v.required() ? "" : "?"));
            b.append("  ").append(n).append(':').append(w).append('\n');
        });
        b.append("""
                Example visuals: { id: mtm, type: card, title: Mark to market, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }
                { id: by-desk, type: bar, title: MTM by desk, at: { x: 0, y: 2, w: 6, h: 5 }, wells: { axis: [trades.desk], values: [MTM] }, alt: Bar chart of MTM by desk }
                """);
        return b.toString();
    }

    static String draftUser(Map<String, Object> model, String request, String problems) {
        return "TASK: draft-report\n" + model(model) + "REQUEST: " + request + "\n"
                + (problems.isEmpty() ? "" : "YOUR PREVIOUS DRAFT WAS REFUSED: " + problems + "\nFix those and reply with the whole YAML again.\n");
    }

    static String askSystem() {
        return """
                You turn a question about a dataset into one query. Reply with one JSON object only, no commentary:
                {"fields": ["table.column", ...], "measures": ["Measure name", ...],
                 "filters": [{"field": "table.column", "op": "eq", "values": ["..."]}],
                 "dates": {"from": "yyyy-mm-dd", "to": "yyyy-mm-dd"}, "orderBy": [{"by": "Measure name", "desc": true}], "limit": 20}
                Use ONLY the fields and measures listed. "fields" are what to group by, "measures" what to add up. ops: eq ne in not-in lt le gt ge between contains starts-with is-null not-null.
                Leave out `dates` unless the question names dates. If the question cannot be answered from the dataset reply {"fields": [], "measures": []}.""";
    }

    static String askUser(Map<String, Object> model, String question) {
        return "TASK: ask-query\n" + model(model) + "QUESTION: " + question + "\n";
    }

    static String summariseSystem() {
        return "You word results for a business reader in at most three sentences. Use only the numbers given; never add a number. Reply with the sentences only.";
    }

    private static String one(Object o) {
        return o == null ? "" : String.valueOf(o).replaceAll("\\s+", " ").strip();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
