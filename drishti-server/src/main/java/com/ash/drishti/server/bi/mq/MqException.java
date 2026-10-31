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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A model query refused (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, sections 5.3 and 5.7): the first failing step of the validation answers with every problem of
 * that step, each with its code, path, plain words and a {@code fix} where one exists. The HTTP answer carries {@code code}, {@code path}, {@code fix} (of
 * the first problem) and {@code problems}.
 */
public final class MqException extends DrishtiException {

    private static final long serialVersionUID = 1L;

    /** One problem: the DRS code, where in the query ({@code select.fields[0]}), what is wrong, and a fix ({@code {replace, with}}) or null. */
    public record Problem(String code, String path, String detail, Map<String, Object> fix) {

        public static Problem of(String code, String path, String detail) {
            return new Problem(code, path, detail, null);
        }
    }

    private final transient List<Problem> problems;

    public MqException(List<Problem> problems) {
        super(codeOf(problems.get(0).code()), problems.get(0).detail());
        this.problems = List.copyOf(problems);
    }

    public MqException(Problem p) {
        this(List.of(p));
    }

    /**
     * A refusal answered under an older endpoint's code ({@code DRS-9020} for a query the engine would not take, {@code DRS-9021} for a masked field): the
     * endpoints that predate the language keep their codes; the problems keep the model query's codes, paths and fixes.
     */
    public MqException(List<Problem> problems, ErrorCode legacy) {
        super(legacy, problems.get(0).detail());
        this.problems = List.copyOf(problems);
    }

    /** This refusal under the code the engine gave the same refusal before the model query language (see {@link #MqException(List, ErrorCode)}). */
    public MqException legacy() {
        String code = problems.get(0).code();
        ErrorCode old = switch (code) {
            case "DRS-9161" -> ErrorCode.QUERY_MASKED;
            case "DRS-9163" -> errorCode();                                   // hidden for the reader's roles: said so on every endpoint, never a raw 'no measure'
            case "DRS-9151" -> errorCode();
            default -> code.startsWith("DRS-915") || code.startsWith("DRS-916") ? ErrorCode.QUERY_INVALID : errorCode();
        };
        return old == errorCode() ? this : new MqException(problems, old);
    }

    public List<Problem> problems() {
        return problems;
    }

    /** The error code of a DRS code string (unknown codes are the shape code, which they never are). */
    public static ErrorCode codeOf(String code) {
        return Arrays.stream(ErrorCode.values()).filter(e -> e.code().equals(code)).findFirst().orElse(ErrorCode.MQ_SHAPE);
    }
}
