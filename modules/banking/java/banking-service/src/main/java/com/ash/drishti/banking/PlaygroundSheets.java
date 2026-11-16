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
package com.ash.drishti.banking;

import com.ash.drishti.banking.compute.MethodChoices;
import com.ash.drishti.server.playground.SheetStream;
import java.util.List;
import java.util.Map;

/** The sheets and rows a measure's playground workbook has in common: Explain, Provenance and the switch table of Config. */
public final class PlaygroundSheets {

    private PlaygroundSheets() {
    }

    /** The Explain sheet: {@code playground} is the measure's name in the title. */
    public static void explain(SheetStream s, String playground, List<String[]> steps) {
        s.width(0, 44).width(1, 150);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · " + playground + " · how each figure is worked out", "title");
        s.blank(1, "title");
        int row = 3;
        for (String[] step : steps) {
            s.row(row++).text(0, step[0], "label.wrap").text(1, step[1], "wrap");
        }
    }

    /** The Provenance sheet, one row per entry; a list is its size and then one row per element. */
    public static void provenance(SheetStream s, Map<String, Object> prov) {
        s.width(0, 38).width(1, 120);
        s.row(1).text(0, "Provenance", "title");
        s.blank(1, "title");
        int row = 3;
        for (Map.Entry<String, Object> en : prov.entrySet()) {
            Object v = en.getValue();
            s.row(row++).text(0, en.getKey(), "label");
            if (v instanceof List<?> list) {
                s.text(1, list.isEmpty() ? "none" : String.valueOf(list.size()), "text");
                for (Object o : list) {
                    s.row(row++).text(1, String.valueOf(o), "text");
                }
            } else {
                s.text(1, v == null ? "" : String.valueOf(v), "text");
            }
        }
    }

    /** The heading of the Config sheet's switch table, on row 3. */
    public static void switchHead(SheetStream s) {
        s.row(3).text(0, "Switch", "head").text(1, "Value (choose)", "head").text(2, "Default", "head").text(3, "Options", "head").text(4, "Rule", "head")
                .text(5, "Why the default", "head");
    }

    /** One switch of the Config sheet's table, its value cell a drop-down of the options. */
    public static void switchRow(SheetStream s, int row, MethodChoices.Switch x, String value) {
        s.row(row).text(0, x.name(), "label").text(1, value, "input.text").text(2, x.dflt(), "text").text(3, String.join(", ", x.options()), "text")
                .text(4, x.rule(), "text").text(5, x.why(), "wrap");
        s.list("B" + row, x.options());
    }
}
