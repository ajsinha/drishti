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
package com.ash.drishti.rachana.el;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Text with embedded {@code ${expr}} parts, such as {@code "Leg 2 · ${$.legs[1].label}"}. Immutable. */
public final class Template {

    private final List<Object> parts;

    private Template(List<Object> parts) {
        this.parts = List.copyOf(parts);
    }

    static Template parse(String src, ElCompiler compiler) {
        List<Object> parts = new ArrayList<>();
        int i = 0;
        while (i < src.length()) {
            int start = src.indexOf("${", i);
            if (start < 0) {
                parts.add(src.substring(i));
                break;
            }
            if (start > i) {
                parts.add(src.substring(i, start));
            }
            int end = matchingBrace(src, start + 2);
            if (end < 0) {
                throw new ElException("unclosed '${'", start);
            }
            parts.add(compiler.compile(src.substring(start + 2, end)));
            i = end + 1;
        }
        return new Template(parts);
    }

    private static int matchingBrace(String s, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    public String render(EvalContext c) {
        if (parts.size() == 1 && parts.get(0) instanceof String s) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p instanceof Expr e ? Values.text(e.eval(c)) : p);
        }
        return sb.toString();
    }

    public void paths(Consumer<String> sink) {
        parts.stream().filter(Expr.class::isInstance).map(Expr.class::cast).forEach(e -> e.paths(sink));
    }
}
