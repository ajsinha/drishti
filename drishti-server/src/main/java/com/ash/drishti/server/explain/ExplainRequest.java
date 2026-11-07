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
package com.ash.drishti.server.explain;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What is asked: the kind and measure of a number, the part wanted, and the explainer's own arguments (a scope, a date, a view): the
 * query's parameters on a GET, the body's fields on a POST.
 *
 * @param kind    the explainer's kind ({@code var})
 * @param measure the measure (null: the explainer's first)
 * @param part    {@code all} (the drawer and the page), {@code preview} (the hover card) or one tab ({@code why})
 * @param args    everything else, as sent
 */
public record ExplainRequest(String kind, String measure, String part, Map<String, Object> args) {

    /** The parts a request may name. */
    public static final Set<String> PARTS = Set.of("all", "preview", "formula", "inputs", "why", "workbook");

    public ExplainRequest {
        kind = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
        measure = measure == null || measure.isBlank() ? null : measure.strip().toLowerCase(Locale.ROOT);
        part = part == null || part.isBlank() ? "all" : part.strip().toLowerCase(Locale.ROOT);
        if (!PARTS.contains(part)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "part is one of all, preview, formula, inputs, why, workbook (not " + part + ")");
        }
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /** Whether the part is wanted: {@code all} wants every part; the preview heads every answer. */
    public boolean wants(String which) {
        return "preview".equals(which) || part.equals("all") || part.equals(which);
    }

    /** An argument as text, stripped (null when absent or blank). */
    public String text(String name) {
        Object v = args.get(name);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).strip();
        return s.isEmpty() ? null : s;
    }

    /** An argument that must be there, refused in words when it is not. */
    public String required(String name, String example) {
        String s = text(name);
        if (s == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give " + name + ": " + example);
        }
        return s;
    }

    /** A business date argument (yyyy-MM-dd), null when absent. */
    public LocalDate date(String name) {
        String s = text(name);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is a business date, yyyy-MM-dd (not " + s + ")");
        }
    }

    /** A number argument, null when absent. */
    public Double number(String name) {
        String s = text(name);
        if (s == null) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is a number (not " + s + ")");
        }
    }

    /** The measure asked, or the default. */
    public String measureOr(String dflt) {
        return measure == null ? dflt : measure;
    }
}
