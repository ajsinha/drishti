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
package com.ash.drishti.enginehost;

import com.ash.engine.Answer;
import com.ash.engine.ColumnSchema;
import com.ash.engine.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An engine's answer as plain data (maps, lists, scalars) for the REST answer and the Data Protocol table {@code engine.<id>.<output>}. */
public final class AnswerShapes {

    private AnswerShapes() {
    }

    /** An answer as plain data for the REST answer and the Data Protocol: status, tables (schema and rows), missing in words, stamp, notes. */
    public static Map<String, Object> plain(Answer a) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("status", a.status().name());
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Table t : a.tables()) {
            Map<String, Object> tm = new java.util.LinkedHashMap<>();
            tm.put("name", t.schema().name());
            List<Map<String, Object>> cols = new ArrayList<>();
            for (ColumnSchema c : t.schema().columns()) {
                Map<String, Object> cm = new HashMap<>();
                cm.put("name", c.name());
                cm.put("type", c.type());
                cm.put("measure", c.measure());
                if (c.unit() != null) {
                    cm.put("unit", c.unit());
                }
                if (c.format() != null) {
                    cm.put("format", c.format());
                }
                cols.add(cm);
            }
            tm.put("columns", cols);
            tm.put("key", t.schema().key());
            tm.put("rows", com.ash.engine.Tables.rows(t));
            tables.add(tm);
        }
        m.put("tables", tables);
        m.put("missing", a.missing().stream().map(com.ash.engine.Missing::words).toList());
        if (a.stamp() != null) {
            Map<String, Object> s = new java.util.LinkedHashMap<>();
            s.put("engine", a.stamp().engine());
            s.put("version", a.stamp().version());
            s.put("descriptor", a.stamp().descriptor());
            s.put("options", a.stamp().options());
            s.put("inputs", a.stamp().inputs());
            s.put("request", a.stamp().request());
            m.put("stamp", s);
        }
        m.put("notes", a.notes());
        return m;
    }
}
