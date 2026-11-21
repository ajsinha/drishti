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
package com.ash.drishti.server.playground;

import com.ash.tools.xlsx.Cells;
import com.ash.tools.xlsx.PrintSetup;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.Map;

/**
 * A playground workbook written as it is made (docs/guides/VAR_GUIDE.md section 5), on the shared workbook writer ({@code tools-xlsx}):
 * straight into the response's stream, sheet after sheet and row after row, so a workbook of ten thousand positions is never held whole on
 * the server. The template gives the look and the document properties; the generator gives each sheet ({@link #sheet}) and the defined names
 * ({@link #name}). Excel is asked to recalculate on open; every formula also carries its cached result, so a viewer that never recalculates
 * shows the numbers. Every tab prints landscape, fitted to the page's width, with the copyright, the product and the template's name and version
 * in the footer: the words are the branding configuration's and the template's, handed to the writer here.
 */
public final class PlaygroundWorkbook implements AutoCloseable {
    private static final double[] MARGINS = {0.5, 0.4, 0.6, 0.4, 0.2, 0.25};

    private final Workbook book;
    private final PlaygroundTemplate template;
    private final Map<String, String> values;

    /**
     * @param out where the .xlsx goes (not closed here)
     * @param values the template's placeholders ({@link PlaygroundTemplate#brandingValues} and the export's own: TITLE, SUBJECT, USER, NOW)
     */
    public PlaygroundWorkbook(OutputStream out, PlaygroundTemplate template, Map<String, String> values) {
        this.template = template;
        this.values = Map.copyOf(values);
        this.book = Workbook.to(out, template.styles(this.values)).recalculateOnOpen()
                .properties(template.part("docProps/core.xml", this.values), template.part("docProps/app.xml", this.values));
    }

    public PlaygroundTemplate template() {
        return template;
    }

    /** The next sheet (the previous one is finished first); {@code tab} a tab colour ({@code 1F3A5F}) or null. */
    public Sheet sheet(String name, String tab) {
        Sheet s = book.sheet(name);
        if (tab != null) {
            s.tab(tab);
        }
        String copyright = Cells.headerFooterText(values.getOrDefault("COPYRIGHT", ""));
        String product = Cells.headerFooterText(values.getOrDefault("PRODUCT", ""));
        String tpl = Cells.headerFooterText(values.getOrDefault("TEMPLATE", "") + " " + values.getOrDefault("TEMPLATE_VERSION", ""));
        return s.print(new PrintSetup(true, 9, MARGINS, null, "&L&8" + copyright + "&C&8" + product + " · " + Cells.headerFooterText(s.name())
                + "&R&8Template " + tpl + " · page &P of &N", true));
    }

    /** A workbook-level defined name: {@code Confidence} is {@code Main!$B$5}. */
    public void name(String name, String sheet, String ref) {
        book.name(name, sheet, ref);
    }

    @Override
    public void close() {
        book.close();
    }
}
