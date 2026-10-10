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
package com.ash.drishti.rachana.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What a control of a Sutra is: how it is drawn and what its value means. The value is read in Rachana-EL as {@code ctl.<id>}.
 */
public enum ControlKind {
    /** One choice from a list; empty when none is chosen (unless the control is required). */
    DROPDOWN(true),
    /** Several choices; the value is a list, and nothing chosen means no filter. */
    MULTI_SELECT(true),
    /** A dropdown whose options depend on a parent control: {@code parent} names it, {@code options} reads {@code ctl.<parent>}. */
    CASCADING(true),
    /** One choice from a short list, drawn as a switch (two options) or buttons. */
    TOGGLE(true),
    /** One choice from a short list, drawn as joined buttons. */
    SEGMENTED(true),
    /** One business date. */
    DATE(false),
    /** A {@code from..to} range of dates; the default may be relative ({@code next-90d}, {@code this-month}, {@code ytd}). */
    DATE_RANGE(false),
    /** A {@code min..max} range of numbers, with bounds fixed or read from the data. */
    NUMBER_RANGE(false),
    /** How many rows to show: one whole number from a short list. */
    TOP_N(true),
    /** Text to look for. */
    SEARCH(false);

    private final boolean listed;

    ControlKind(boolean listed) {
        this.listed = listed;
    }

    /** Whether the kind offers a list of options (and so takes {@code options}). */
    public boolean listed() {
        return listed;
    }

    /** Whether the value is a list of texts. */
    public boolean multiple() {
        return this == MULTI_SELECT;
    }

    /** Whether the value is a {@code from..to} range. */
    public boolean range() {
        return this == DATE_RANGE || this == NUMBER_RANGE;
    }

    /** The name in a Sutra ({@code multi-select}, {@code date-range}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<ControlKind> parse(String s) {
        for (ControlKind k : values()) {
            if (k.id().equals(s)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }
}
