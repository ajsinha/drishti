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
package com.ash.drishti.rachana.el.check;

import java.util.Map;

/**
 * How the checker words a problem's fix: in Rachana's own spelling, or in DEL's while DEL is still read (its checker calls this one
 * until the migration of BI_FOUNDATION_2911E.md part B deletes it). The rules are the same; only the words a person is told to write
 * differ.
 */
public enum Spelling {
    RACHANA("true or false", "!= null", "== null", " = ", "c ? a : b", "x > 0 || x != null", Map.of()),
    DEL("True or False", "is not None", "is None", "=", "a if c else b", "x > 0 or x is not None",
            Map.of("starts_with", "startswith", "ends_with", "endswith", "latest", "last", "earliest", "first"));

    private final String booleans;
    private final String notNull;
    private final String isNull;
    private final String keyword;
    private final String conditional;
    private final String conditionExample;
    private final Map<String, String> names;

    Spelling(String booleans, String notNull, String isNull, String keyword, String conditional, String conditionExample,
            Map<String, String> names) {
        this.booleans = booleans;
        this.notNull = notNull;
        this.isNull = isNull;
        this.keyword = keyword;
        this.conditional = conditional;
        this.conditionExample = conditionExample;
        this.names = names;
    }

    /** {@code true or false}. */
    public String booleans() {
        return booleans;
    }

    /** {@code x != null}'s operator. */
    public String notNull() {
        return notNull;
    }

    /** {@code x == null}'s operator. */
    public String isNull() {
        return isNull;
    }

    /** A named argument as written: {@code where = ...} or DEL's {@code where=...}. */
    public String named(String name) {
        return name + keyword;
    }

    /** The conditional as written. */
    public String conditional() {
        return conditional;
    }

    /** An example condition. */
    public String conditionExample() {
        return conditionExample;
    }

    /** A function's name as this spelling writes it. */
    public String name(String rachana) {
        return names.getOrDefault(rachana, rachana);
    }
}
