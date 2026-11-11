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

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.tables.check.ExpressionChecker;
import com.ash.drishti.tables.migrate.DelTranslator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The migration's DEL step (HOW_IT_FITS.md §A28 section 4.1), from part A: DEL read by its own parser (read, never evaluated), mapped to
 * Rachana's tree by {@link DelToRachana} (section 2.4: the words, {@code a if c else b}, {@code not}, {@code **}, {@code //}, chains,
 * {@code is None}, the renamed functions) and printed in the one spelling by Rachana's {@link Printer}. The layer's own rewrites (columns
 * to paths, date parts, the null rule of Q1) run after it in drishti-tables. Lives with DEL so the table layer never reads DEL; deleted
 * with DEL's reader when the migration tool goes (Q24).
 */
public final class DelTablesTranslator implements DelTranslator {

    @Override
    public Result translate(String del, ExpressionChecker.Scope scope) {
        try {
            String t = del == null ? "" : del.strip();
            // a rule written as a YAML boolean (where: true) reached DEL as the text true, which BI read as True: the one spelling YAML chose
            DelAst tree = DelParser.parse(t.equals("true") ? "True" : t.equals("false") ? "False" : del);
            String rachana = Printer.print(DelToRachana.map(tree).expr());
            return Result.ok(rachana, rachana.equals(del.strip()) ? List.of() : List.of("DEL into Rachana (section 2.4)"));
        } catch (ExpressionException e) {
            if (e.getMessage() != null && e.getMessage().contains("is the old syntax")) {
                return firstSyntax(del, e);
            }
            return Result.refused("DEL does not read it: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.refused("no Rachana form: " + e.getMessage());
        }
    }

    /** {@code [Measure name]}; never a list of fields such as {@code by = [trades.desk]} (a dot) or a list of several items (a comma). */
    private static final Pattern MEASURE_REF = Pattern.compile("(?<![A-Za-z0-9_\\]])\\[([A-Za-z][^\\],.'\"\\[]*)\\]");
    private static final Pattern IN_PARENS = Pattern.compile("\\bin\\s*\\(([^()]*)\\)");

    /**
     * A text in BI's first expression syntax (an item without {@code expressions: 2}: Rachana-EL's operators and literals, {@code &&},
     * {@code ||}, {@code !}, {@code c ? a : b}, {@code null}), which DEL refuses as its old syntax: read by Rachana's one parser after the
     * first syntax's own spellings ({@code [Measure]} as {@code measure('Measure')}, {@code in (a, b)} as {@code in [a, b]}). A text Rachana's
     * parser does not read stays refused with DEL's reason. Inside the migration tool only, so an installation that never ran the old
     * converter still migrates.
     */
    private static Result firstSyntax(String del, ExpressionException old) {
        String t = MEASURE_REF.matcher(del.strip()).replaceAll(m -> java.util.regex.Matcher.quoteReplacement("measure('" + m.group(1) + "')"));
        t = IN_PARENS.matcher(t).replaceAll(m -> java.util.regex.Matcher.quoteReplacement("in [" + m.group(1) + "]"));
        try {
            String rachana = Printer.print(CheckedMode.parse(t).expr());
            return Result.ok(rachana, List.of("the first expression syntax into Rachana"));
        } catch (ElException | IllegalArgumentException e) {
            return Result.refused("DEL does not read it: " + old.getMessage());
        }
    }
}
