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
package com.ash.drishti.engine.search.form;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The kind of input a form field offers, and the operators each one allows (first = the default). */
public enum FieldType {
    TEXT("text", List.of("equals", "contains", "starts", "regex")),
    NUMBER("number", List.of("=", "between", ">", "<", ">=", "<=")),
    AMOUNT("amount", List.of("=", "between", ">", "<", ">=", "<=")),
    DATE("date", List.of("on", "between", "relative", "before", "after")),
    ENUM("enum", List.of("in")),
    ID("id", List.of("in")),
    BOOL("bool", List.of("is"));

    private final String id;
    private final List<String> operators;

    FieldType(String id, List<String> operators) {
        this.id = id;
        this.operators = operators;
    }

    public String id() {
        return id;
    }

    /** Every operator the type knows, the default first. */
    public List<String> operators() {
        return operators;
    }

    public boolean numeric() {
        return this == NUMBER || this == AMOUNT;
    }

    public static Optional<FieldType> of(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (FieldType f : values()) {
            if (f.id.equals(t)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
