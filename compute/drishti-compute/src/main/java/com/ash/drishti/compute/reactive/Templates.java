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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.api.compute.ComputeEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small templates of a connector file: an input's ids ({@code {id}}, {@code BOOK-{params.book}}, {@code {params.members}}) and the
 * ids a trigger's event affects ({@code {entity}}, {@code BOOK:{payload.TT_BOOK}}). A value that is a list (or comma-separated text,
 * for {@code ids}) gives one id per element. Single braces: {@code ${NAME}} stays what it is everywhere in a connector file, an
 * environment variable resolved when the file is read.
 */
final class Templates {

    private static final Pattern VAR = Pattern.compile("(?<!\\$)\\{([A-Za-z][A-Za-z0-9_.-]*)}");

    private Templates() {}

    static boolean isTemplate(String s) {
        return s != null && VAR.matcher(s).find();
    }

    /** One text: each variable replaced by its value (empty when absent). */
    static String one(String template, Map<String, Object> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            Object v = lookup(vars, m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(v == null ? "" : ParamSpec.text(v)));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Many ids: a template that is exactly one variable holding a list (or comma-separated text) gives each element. */
    static List<String> many(String template, Map<String, Object> vars) {
        Matcher m = VAR.matcher(template.trim());
        if (m.matches()) {
            Object v = lookup(vars, m.group(1));
            List<String> out = new ArrayList<>();
            if (v instanceof Collection<?> c) {
                c.forEach(x -> out.add(ParamSpec.text(x)));
            } else if (v != null) {
                for (String x : ParamSpec.text(v).split(",")) {
                    if (!x.isBlank()) {
                        out.add(x.trim());
                    }
                }
            }
            return out;
        }
        String one = one(template, vars);
        return one.isBlank() ? List.of() : List.of(one);
    }

    private static Object lookup(Map<String, Object> vars, String path) {
        Object cur = vars;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
        }
        return cur;
    }

    /** What an event offers a trigger's {@code affects:} template. */
    static Map<String, Object> of(ComputeEvent e) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", e.id());
        m.put("type", e.type());
        m.put("kind", e.kind());
        m.put("entity", e.entity());
        m.put("by", e.by());
        m.put("businessDate", e.businessDate() == null ? null : e.businessDate().toString());
        m.put("payload", e.payload());
        return m;
    }
}
