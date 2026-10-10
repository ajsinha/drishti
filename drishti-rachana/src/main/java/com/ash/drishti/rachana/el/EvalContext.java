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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.format.Formats;

/**
 * What an expression can see: the document ({@code $}), the current row ({@code @}) and its index
 * ({@code #index}), and the named formats for {@code fmt(...)}. Immutable; derive a row context with
 * {@link #withRow}.
 *
 * @param root the document
 * @param row the current row or element, or null outside a row
 * @param index position of the row, or -1
 * @param formats named formats
 */
public record EvalContext(DataNode root, Object row, int index, Formats formats, java.util.Map<String, Object> ctl) {

    public EvalContext {
        ctl = ctl == null ? java.util.Map.of() : ctl;
    }

    public EvalContext(DataNode root, Object row, int index, Formats formats) {
        this(root, row, index, formats, java.util.Map.of());
    }

    public static EvalContext of(DataNode root, Formats formats) {
        return new EvalContext(root, null, -1, formats);
    }

    public EvalContext withRow(Object row, int index) {
        return new EvalContext(root, row, index, formats, ctl);
    }

    /**
     * The same context with the values of a Sutra's controls, read in expressions as {@code ctl.<id>}: text for most kinds, a
     * list of text for a multi-select, a whole number for top-n, {@code from..to} text for a range.
     */
    public EvalContext withControls(java.util.Map<String, Object> values) {
        return new EvalContext(root, row, index, formats, values);
    }
}
