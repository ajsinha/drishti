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
package com.ash.drishti.rachana.ui;

import java.util.Locale;
import java.util.Optional;

/**
 * The pin types of UI_BOARD.md section 4.1, by their document names ({@code id}, {@code rowset}, ...). {@code spec} (2.7.0 P5) is a structured
 * setting in the form of the panel or visual it configures (a list of columns, series or markers, a pivot, a format): a control constant only.
 */
public enum PinType {
    ID, TEXT, NUMBER, BOOL, ENUM, DATE, DATETIME, PERIOD, MEASURE, DIMENSION, FILTER, SELECTION, DOCUMENT, ROWSET, SPEC;

    /** The name as written in a chip or board document. */
    public String yamlName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True for a single value (what may feed a {@code dimension}). */
    public boolean scalar() {
        return switch (this) {
            case ID, TEXT, NUMBER, BOOL, ENUM, DATE, DATETIME, MEASURE, DIMENSION -> true;
            default -> false;
        };
    }

    /** The type named {@code s}, or empty. */
    public static Optional<PinType> of(String s) {
        if (s == null) {
            return Optional.empty();
        }
        for (PinType t : values()) {
            if (t.yamlName().equals(s.strip())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
