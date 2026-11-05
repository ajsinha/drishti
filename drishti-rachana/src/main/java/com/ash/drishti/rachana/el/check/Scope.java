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

import java.util.Locale;

/**
 * Where an expression is written decides what it may name (BI_FOUNDATION_2911E.md 2.2; moved from DEL, BI_DESIGN.md section 3.3.1).
 *
 * <ul>
 *   <li>{@link #ROW}: a table's column {@code expr}, its {@code filter}, {@code keep:}; a dataset's derive and calculated columns, MQ and
 *       DSQL row filters: the source ({@code $}, {@code @}, {@code ^}) or the columns of one table, {@code related()} one hop,
 *       {@code param.x}; one value per row; no aggregate.</li>
 *   <li>{@link #RULE}: a row rule (a table's {@code rules[].where}, a model's {@code security.rows}): the columns and {@code user.x};
 *       a condition per row; never removable by any function.</li>
 *   <li>{@link #AGGREGATE}: measures, MQ {@code define}, {@code having}, report local measures: {@code alias.COLUMN} inside an
 *       aggregate, {@code measure('name')}, {@code param.x}; one value per group.</li>
 *   <li>{@link #CONTEXT}: the first argument of {@code calc}, {@code share_of}, {@code parent_of}, {@code as_of}: as aggregate, in an
 *       edited filter context.</li>
 * </ul>
 */
public enum Scope {
    ROW, RULE, AGGREGATE, CONTEXT;

    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The scope a word names ({@code row}, {@code rule}, {@code aggregate}, {@code measure} for aggregate, {@code context}); null when none. */
    public static Scope of(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "row", "derive", "column", "filter", "where" -> ROW;
            case "rule", "security" -> RULE;
            case "aggregate", "measure", "having", "define" -> AGGREGATE;
            case "context" -> CONTEXT;
            default -> null;
        };
    }
}
