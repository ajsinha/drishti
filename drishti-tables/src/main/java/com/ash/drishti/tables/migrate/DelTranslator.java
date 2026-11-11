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
package com.ash.drishti.tables.migrate;

import com.ash.drishti.tables.check.ExpressionChecker;
import java.util.List;

/**
 * The migration's pluggable step: one DEL expression into Rachana text (HOW_IT_FITS.md §A28 sections 2.4, 2.5 and 4.1). DEL is parsed by
 * the old parser kept inside the tool (read, never evaluated), mapped to Rachana's tree by part A's mapping and printed by Rachana's
 * printer. The server implements it ({@code server.bi.expr.DelTablesTranslator}: DEL's parser, part A's {@code DelToRachana}); this module
 * never reads DEL.
 *
 * <p>The translator never does the layer's own rewrites: a bare column to its path (section 3.2) and the null rule (Q1) run after it, on
 * Rachana's tree ({@link Rewrites}), whichever translator is plugged in.
 */
public interface DelTranslator {

    /**
     * Translates {@code del}.
     *
     * @param del the DEL text as written in the item
     * @param scope where it is written (row, rule, aggregate, model rule)
     */
    Result translate(String del, ExpressionChecker.Scope scope);

    /**
     * What a translation gives.
     *
     * @param rachana the Rachana text, or null when refused
     * @param notes what was changed and why, in words (for the report)
     * @param refusal why it cannot be translated (the report names the item and the expression), or null
     */
    record Result(String rachana, List<String> notes, String refusal) {

        public Result {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public static Result ok(String rachana, List<String> notes) {
            return new Result(rachana, notes, null);
        }

        public static Result refused(String why) {
            return new Result(null, List.of(), why);
        }

        public boolean refused() {
            return refusal != null;
        }
    }
}
