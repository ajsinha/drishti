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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.enginehost.RowRule;
import com.ash.drishti.enginehost.WhereCompiler;
import com.ash.drishti.rachana.LiveCompiler;
import com.ash.rachana.el.EvalContext;
import com.ash.rachana.el.Expr;
import com.ash.rachana.el.Values;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An engine file's {@code inputs.<name>.where}: one Rachana-EL expression over a row ({@code @.STATUS != 'DEAD'}), compiled by the server's
 * one EL compiler (as a connector's {@code where} and a role's row rule are), evaluated by the host before delivery. {@code @.X} names the
 * row's field {@code X}; a row the expression cannot be worked out on does not pass.
 */
final class ElWhere implements WhereCompiler {

    private static final Pattern FIELD = Pattern.compile("@\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");

    @Override
    public RowRule compile(String where) {
        String text = where.strip();
        Set<String> fields = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(text);
        while (m.find()) {
            fields.add(m.group(1));
        }
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("'" + text + "' reads no field of the row; write it over the row's fields, as @.STATUS != 'DEAD'");
        }
        String el = FIELD.matcher(text).replaceAll("$1");
        Expr e;
        try {
            e = LiveCompiler.current().compile(el);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("'" + text + "' is not a Rachana-EL expression over a row: " + ex.getMessage(), ex);
        }
        Set<String> read = Set.copyOf(fields);
        return new RowRule() {
            @Override
            public boolean all() {
                return false;
            }

            @Override
            public Set<String> fields() {
                return read;
            }

            @Override
            public boolean test(Function<String, Object> row) {
                Map<String, Object> values = new HashMap<>();
                for (String f : read) {
                    Object v = row.apply(f);
                    if (v != null) {
                        values.put(f, v);
                    }
                }
                try {
                    return Values.truthy(e.eval(EvalContext.of(DataNode.of(values), null)));
                } catch (RuntimeException x) {
                    return false;
                }
            }

            @Override
            public String text() {
                return text;
            }
        };
    }
}
