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

import com.ash.drishti.rachana.el.check.Problem;

/**
 * An expression BI refuses: the problem code ({@code DRS-9127} for the language, {@code DRS-9129} for a function of a later wave,
 * {@code DRS-9161} for a masked field, {@code DRS-9100} for a text that is not Rachana), what is wrong, how to fix it, and where ({@code start}
 * to {@code end}, character offsets).
 */
public final class ExpressionException extends RuntimeException {

    private final String code;
    private final String fix;
    private final int start;
    private final int end;

    public ExpressionException(String code, String message, String fix, int start, int end) {
        super(message);
        this.code = code;
        this.fix = fix;
        this.start = start;
        this.end = Math.max(start, end);
    }

    public ExpressionException(Problem p) {
        this(p.code(), p.message(), p.fix(), p.start(), p.end());
    }

    public String code() {
        return code;
    }

    public String fix() {
        return fix;
    }

    /** Where the problem starts (a character offset). */
    public int position() {
        return start;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public Problem problem() {
        return new Problem(code, Problem.ERROR, getMessage(), fix, start, end);
    }
}
