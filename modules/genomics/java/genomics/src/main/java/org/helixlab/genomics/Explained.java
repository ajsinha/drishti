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

package org.helixlab.genomics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Explain block as the showcase's answers carry it (drishti-core's ExplainResult, read by the chart's Explain and the page's records
 * grid): the sentence, the steps the server took with their tables and row counts, the records behind the figure and where to go next.
 * The page writes none of the words.
 */
final class Explained {

    private final Map<String, Object> out = new LinkedHashMap<>();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final List<Map<String, Object>> links = new ArrayList<>();

    Explained(String title, String value) {
        out.put("title", title);
        out.put("value", value);
    }

    /** Where it holds (the selection, the protein, the round). */
    Explained scope(String scope) {
        out.put("scope", scope);
        return this;
    }

    /** One step: what was done, and to which table with how many rows. */
    Explained step(String what, String table, long rows) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("label", what);
        s.put("value", table + " · " + rows + (rows == 1 ? " row" : " rows"));
        steps.add(s);
        return this;
    }

    /** The sentence (drawn as the note under the steps). */
    Explained sentence(String sentence) {
        out.put("sentence", sentence);
        out.put("note", sentence);
        return this;
    }

    /** The records behind the figure: their columns and rows (at most the first 500; the count says how many there are). */
    Explained records(List<String> columns, List<Map<String, Object>> rows) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("columns", columns);
        r.put("rows", rows.size() > 500 ? rows.subList(0, 500) : rows);
        r.put("count", rows.size());
        out.put("records", r);
        return this;
    }

    /** Where to go next. */
    Explained link(String label, String href) {
        links.add(Map.of("label", label, "href", href));
        return this;
    }

    Map<String, Object> build() {
        out.put("steps", steps);
        out.put("links", links);
        return out;
    }

    /** A row of the fields given, in order (a record of the grid). */
    static Map<String, Object> pick(Map<String, Object> row, List<String> fields) {
        Map<String, Object> r = new LinkedHashMap<>();
        fields.forEach(f -> r.put(f, row.get(f)));
        return r;
    }
}
