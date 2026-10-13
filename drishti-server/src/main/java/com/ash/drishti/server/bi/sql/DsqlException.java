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
package com.ash.drishti.server.bi.sql;

import com.ash.drishti.server.bi.mq.MqException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Drishti SQL statement refused (docs/architecture/BI_DESIGN.md section 3.4; codes {@code DRS-9170} to {@code DRS-9179}): the code, what is wrong, what
 * to write instead, and where (character offsets into the statement). Over HTTP it is a model-query refusal ({@code 422} with {@code code}, {@code path},
 * {@code fix}, {@code problems}).
 */
public final class DsqlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Not a Drishti SQL statement: syntax, more than one statement, DDL or DML. */
    public static final String SYNTAX = "DRS-9170";
    /** A table or column the model does not have for the caller, or a column of a table the statement does not read. */
    public static final String NAME = "DRS-9171";
    /** A join on a predicate that is not one of the model's relationships. */
    public static final String JOIN = "DRS-9172";
    /** A function the dialect does not take: table functions, file or URL access, user functions, {@code random()}. */
    public static final String FUNCTION = "DRS-9173";
    /** An aggregate over a measure, or a measure in {@code WHERE}. */
    public static final String MEASURE = "DRS-9174";
    /** A window over the rows of a dataset (windows run over the answer's rows). */
    public static final String WINDOW = "DRS-9175";
    /** Another model, a connector, a kind or a file named. */
    public static final String SCOPE = "DRS-9176";
    /** Two different times in one statement, or a time clause that is not valid. */
    public static final String TIME = "DRS-9177";
    /** {@code SELECT DISTINCT} on a fact without grouping (a silent de-duplication changes the grain). */
    public static final String DISTINCT = "DRS-9178";
    /** A statement the dialect does not take (yet): the message says what to write instead. */
    public static final String SHAPE = "DRS-9179";

    private final String code;
    private final String fix;
    private final int start;
    private final int end;

    public DsqlException(String code, String message, String fix, int start, int end) {
        super(message);
        this.code = code;
        this.fix = fix;
        this.start = Math.max(0, start);
        this.end = Math.max(this.start, end);
    }

    public DsqlException(String code, String message, String fix, int at) {
        this(code, message, fix, at, at);
    }

    public String code() {
        return code;
    }

    public String fix() {
        return fix;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    /** The problem as the validate answer lists it. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("level", "error");
        m.put("message", getMessage());
        m.put("fix", fix);
        m.put("at", Map.of("start", start, "end", end));
        return m;
    }

    /** The refusal as an HTTP model-query refusal. */
    public MqException toMq() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("at", start);
        if (fix != null) {
            f.put("write", fix);
        }
        return new MqException(List.of(new MqException.Problem(code, "sql@" + start, getMessage(), f)));
    }
}
