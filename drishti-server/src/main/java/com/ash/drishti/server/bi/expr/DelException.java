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
package com.ash.drishti.server.bi.expr;

import com.ash.drishti.server.bi.dataset.ExprParser;

/**
 * A DEL expression the server refuses: the problem code ({@code DRS-9127} for the language, {@code DRS-9129} for a function of a later wave,
 * {@code DRS-9161} for a masked field, {@code DRS-9100} for the old syntax once it is refused), what is wrong, how to fix it, and where ({@code start} to
 * {@code end}, character offsets). It is an {@link ExprParser.ExprException}, so every caller that handled the old parser's errors handles these.
 */
public final class DelException extends ExprParser.ExprException {

    private final String code;
    private final String fix;
    private final int end;

    public DelException(String code, String message, String fix, int start, int end) {
        super(message, start);
        this.code = code;
        this.fix = fix;
        this.end = Math.max(start, end);
    }

    public DelException(Problem p) {
        this(p.code(), p.message(), p.fix(), p.start(), p.end());
    }

    public String code() {
        return code;
    }

    public String fix() {
        return fix;
    }

    public int start() {
        return position();
    }

    public int end() {
        return end;
    }

    public Problem problem() {
        return new Problem(code, Problem.ERROR, getMessage(), fix, position(), end);
    }

    /**
     * One finding about an expression.
     *
     * @param level {@code error} or {@code warning}
     * @param fix what to write instead (null when there is no mechanical fix)
     */
    public record Problem(String code, String level, String message, String fix, int start, int end) {
        public static final String ERROR = "error";
        public static final String WARNING = "warning";

        public boolean error() {
            return ERROR.equals(level);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("code", code);
            m.put("level", level);
            m.put("message", message);
            m.put("fix", fix);
            m.put("at", java.util.Map.of("start", start, "end", end));
            return m;
        }
    }
}
