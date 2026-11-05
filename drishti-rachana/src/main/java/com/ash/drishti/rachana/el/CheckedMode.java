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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.el.check.Checker;
import com.ash.drishti.rachana.el.check.Fields;
import com.ash.drishti.rachana.el.check.Problem;
import com.ash.drishti.rachana.el.check.Scope;
import com.ash.drishti.rachana.el.check.Spelling;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rachana's checked mode, from text (BI_FOUNDATION_2911E.md 2.1): the one parser, then the {@link Checker} with the types, masks and
 * measures of a table or model. Wherever the types are known (a virtual table's source contract, a model's tables, a pivot over a typed
 * source) an expression is read here; a Sutra over untyped documents stays unchecked ({@link ElCompiler}). The same text means the same
 * thing in both: checked mode only refuses more, with DEL's codes ({@code DRS-9127}, {@code DRS-9129}, {@code DRS-9152},
 * {@code DRS-9161}).
 */
public final class CheckedMode {

    private CheckedMode() {}

    /** A parsed expression with where each node was written. */
    public record Parsed(String text, Expr expr, Map<Expr, int[]> spans) {
        /** Where {@code e} was written ({start, end}); the whole text when not recorded. */
        public int[] span(Expr e) {
            int[] s = spans.get(e);
            return s == null ? new int[] {0, text.length()} : s;
        }
    }

    /** Parses with spans (a syntax error is {@code DRS-2101}, as everywhere in Rachana). */
    public static Parsed parse(String text) {
        Parser.Parsed p = Parser.parseWithSpans(text, ElLimits.DEFAULTS);
        return new Parsed(text, p.expr(), p.spans());
    }

    /** Parses and checks; a text that does not parse is one {@code DRS-9127} problem at the place it stops. */
    public static Checker.Checked check(String text, Scope scope, Fields fields) {
        Parsed p;
        try {
            p = parse(text);
        } catch (ElException e) {
            int at = Math.max(0, e.position());
            return new Checker.Checked(com.ash.drishti.rachana.el.check.ElType.ANY,
                    List.of(new Problem(Problem.LANGUAGE, Problem.ERROR, e.getMessage(), null, at, Math.min(text.length(), at + 1))),
                    Set.of(), Set.of(), Set.of());
        }
        return Checker.check(p.expr(), p.spans(), text, scope, fields, Spelling.RACHANA);
    }

    /**
     * The checked expression, or a refusal: the first error's code ({@code DRS-9127} type or scope, {@code DRS-9129} not yet,
     * {@code DRS-9152} unknown name, {@code DRS-9161} masked field filtered) and its words.
     */
    public static Expr require(String text, Scope scope, Fields fields) {
        Checker.Checked c = check(text, scope, fields);
        Problem p = c.firstError().orElse(null);
        if (p != null) {
            throw new DrishtiException(code(p.code()), p.message() + (p.fix() == null ? "" : " (" + p.fix() + ")"));
        }
        return parse(text).expr();
    }

    static ErrorCode code(String code) {
        return switch (code) {
            case Problem.LATER -> ErrorCode.LATER_WAVE;
            case Problem.NAME -> ErrorCode.MQ_NAME;
            case Problem.MASKED -> ErrorCode.MQ_MASKED;
            default -> ErrorCode.EXPR_INVALID;
        };
    }
}
