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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.layer.ModelDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wave B part of a model's {@code describe} (the console's editors, parameter slicers and AI read it): {@code calcGroups} (each with its precedence and
 * items), {@code parameters} (kind, default, range, values or fields), {@code calendar} and {@code fx}. Pure.
 */
final class MqDescribe {

    private MqDescribe() {}

    static Map<String, Object> calculation(ModelDef def) {
        ModelDef.Calculation c = def.calculation();
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        c.calcGroups().values().forEach(g -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", g.name());
            x.put("precedence", g.precedence());
            x.put("description", g.description());
            x.put("items", g.items().entrySet().stream().map(e -> Map.of("name", e.getKey(), "expr", e.getValue())).toList());
            groups.add(x);
        });
        m.put("calcGroups", groups);
        List<Map<String, Object>> params = new ArrayList<>();
        c.parameters().values().forEach(p -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", p.name());
            x.put("kind", p.kind());
            x.put("title", p.title());
            x.put("description", p.description());
            x.put("default", p.defaultValue());
            x.put("min", p.min());
            x.put("max", p.max());
            x.put("step", p.step());
            x.put("values", p.values());
            x.put("fields", p.fields());
            params.add(x);
        });
        m.put("parameters", params);
        m.put("calendar", c.calendar() == null ? null : Map.of("holidays", c.calendar().holidays(), "fiscalYearStart", c.calendar().fiscalYearStart()));
        if (c.fx() != null) {
            Map<String, Object> fx = new LinkedHashMap<>();
            fx.put("rates", c.fx().rates());
            fx.put("from", c.fx().from());
            fx.put("to", c.fx().to());
            fx.put("rate", c.fx().rate());
            fx.put("date", c.fx().date());
            fx.put("base", c.fx().base());
            m.put("fx", fx);
        } else {
            m.put("fx", null);
        }
        return m;
    }
}
