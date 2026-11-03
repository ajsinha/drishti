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

import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.tables.check.ExpressionChecker;
import com.ash.drishti.tables.migrate.DelTranslator;
import java.util.List;

/**
 * The migration's DEL step (BI_FOUNDATION_2911E.md section 4.1), from part A: DEL read by its own parser (read, never evaluated), mapped to
 * Rachana's tree by {@link DelToRachana} (section 2.4: the words, {@code a if c else b}, {@code not}, {@code **}, {@code //}, chains,
 * {@code is None}, the renamed functions) and printed in the one spelling by Rachana's {@link Printer}. The layer's own rewrites (columns
 * to paths, date parts, the null rule of Q1) run after it in drishti-tables. Lives with DEL so the table layer never reads DEL; deleted
 * with DEL's reader when the migration tool goes (Q24).
 */
public final class DelTablesTranslator implements DelTranslator {

    @Override
    public Result translate(String del, ExpressionChecker.Scope scope) {
        try {
            DelAst tree = DelParser.parse(del);
            String rachana = Printer.print(DelToRachana.map(tree).expr());
            return Result.ok(rachana, rachana.equals(del.strip()) ? List.of() : List.of("DEL into Rachana (section 2.4)"));
        } catch (DelException e) {
            return Result.refused("DEL does not read it: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.refused("no Rachana form: " + e.getMessage());
        }
    }
}
