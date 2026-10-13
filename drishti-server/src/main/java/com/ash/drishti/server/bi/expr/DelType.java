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

import java.util.Locale;

/**
 * The types of DEL values (docs/architecture/BI_DESIGN.md section 3.3.3): no implicit casts, integer with decimal gives decimal, {@code date - date} gives
 * integer days, booleans only where a condition is expected. {@link #NUMBER} is a number whose kind is not known (a measure), {@link #ANY} a value whose
 * type is not known (a field when the expression is checked without a model).
 */
public enum DelType {
    INTEGER, DECIMAL, NUMBER, STRING, BOOLEAN, DATE, TIMESTAMP, NONE, LIST, TABLE, ANY;

    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean numeric() {
        return this == INTEGER || this == DECIMAL || this == NUMBER;
    }

    public boolean temporal() {
        return this == DATE || this == TIMESTAMP;
    }

    /** True when a value of this type can stand where {@code want} is expected ({@link #ANY} and {@link #NONE} fit anywhere). */
    public boolean fits(DelType want) {
        if (this == ANY || want == ANY || this == NONE) {
            return true;
        }
        if (want.numeric()) {
            return numeric();
        }
        if (want.temporal()) {
            return temporal();
        }
        return this == want;
    }

    /** True when two values may be compared or stand in the two branches of a conditional. */
    public boolean comparable(DelType other) {
        return fits(other) || other.fits(this);
    }

    /** The type of {@code a op b} for {@code + - * %}: integer only when both are. */
    public static DelType arithmetic(DelType a, DelType b) {
        if (a == ANY || b == ANY) {
            return a.numeric() || b.numeric() ? NUMBER : ANY;
        }
        if (a == INTEGER && b == INTEGER) {
            return INTEGER;
        }
        if (a == DECIMAL || b == DECIMAL) {
            return DECIMAL;
        }
        return NUMBER;
    }

    /** The type of a column type word ({@code string}, {@code integer}, {@code decimal}, {@code boolean}, {@code date}, {@code timestamp}). */
    public static DelType ofColumn(String type) {
        if (type == null) {
            return ANY;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string", "text" -> STRING;
            case "integer", "int", "long" -> INTEGER;
            case "decimal", "double", "number", "float" -> DECIMAL;
            case "boolean", "bool" -> BOOLEAN;
            case "date" -> DATE;
            case "timestamp", "datetime" -> TIMESTAMP;
            default -> ANY;
        };
    }

    /** The type a catalogue {@code returns:} word names; {@code same} is resolved by the caller. */
    public static DelType ofReturn(String word) {
        return switch (word == null ? "" : word) {
            case "integer" -> INTEGER;
            case "decimal" -> DECIMAL;
            case "number" -> NUMBER;
            case "string" -> STRING;
            case "boolean" -> BOOLEAN;
            case "date" -> DATE;
            case "timestamp" -> TIMESTAMP;
            default -> ANY;
        };
    }
}
