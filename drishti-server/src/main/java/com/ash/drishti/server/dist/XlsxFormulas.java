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
package com.ash.drishti.server.dist;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The formulas a generated workbook may hold (the playgrounds, docs/architecture/VAR_PLAYGROUND.md section 10): only functions that
 * Excel, LibreOffice Calc and Google Sheets share, so one file recalculates the same in all three. Never Excel-365-only dynamic arrays
 * ({@code FILTER}, {@code SORT}, {@code SEQUENCE}, {@code LET}, {@code LAMBDA}, {@code XLOOKUP}), volatile {@code INDIRECT} or
 * {@code OFFSET}, structured references or macros. {@link #check} refuses a formula that names anything else; {@link #scan} lists every
 * formula of a finished .xlsx that does (the drill's formula scanner).
 */
public final class XlsxFormulas {

    /** The functions a formula may call. */
    public static final Set<String> ALLOWED = Set.of("SUM", "SUMPRODUCT", "SMALL", "LARGE", "AVERAGE", "COUNT", "INDEX", "MATCH", "ROUNDDOWN",
            "ROUNDUP", "SQRT", "IF", "ABS", "MAX", "MIN", "AND", "OR", "ISNUMBER", "EXP", "LN", "SUMIFS", "SUMIF");

    /** A function call: a name then an opening parenthesis (a sheet name or a defined name is never followed by one). */
    private static final Pattern CALL = Pattern.compile("([A-Za-z_][A-Za-z0-9_.]*)\\s*\\(");
    private static final Pattern FORMULA = Pattern.compile("<f(?:\\s[^>]*)?>([^<]*)</f>");

    private XlsxFormulas() {}

    /** The functions {@code formula} calls, upper case, in order of first use (string literals and quoted sheet names skipped). */
    public static List<String> functions(String formula) {
        StringBuilder plain = new StringBuilder(formula.length());
        boolean inString = false;
        boolean inSheet = false;
        for (int i = 0; i < formula.length(); i++) {
            char c = formula.charAt(i);
            if (c == '"' && !inSheet) {
                inString = !inString;
                plain.append(' ');
            } else if (c == '\'' && !inString) {
                inSheet = !inSheet;
                plain.append(' ');
            } else {
                plain.append(inString || inSheet ? ' ' : c);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CALL.matcher(plain);
        while (m.find()) {
            out.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    /** {@code formula} when it calls only {@link #ALLOWED} functions; IllegalArgumentException naming the others. */
    public static String check(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("an empty formula");
        }
        if (formula.startsWith("=")) {
            throw new IllegalArgumentException("a formula is written without its leading '=': " + formula);
        }
        List<String> bad = functions(formula).stream().filter(f -> !ALLOWED.contains(f)).toList();
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException("functions outside the Excel, LibreOffice and Google Sheets common set " + bad + " in " + formula);
        }
        return formula;
    }

    /** Every formula of an .xlsx that calls a function outside {@link #ALLOWED}: {@code part: function in formula}; empty when clean. */
    public static List<String> scan(byte[] xlsx) {
        return scan(new ByteArrayInputStream(xlsx));
    }

    public static List<String> scan(InputStream xlsx) {
        List<String> out = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(xlsx)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (!e.getName().startsWith("xl/worksheets/") || !e.getName().endsWith(".xml")) {
                    continue;
                }
                String xml = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = FORMULA.matcher(xml);
                while (m.find()) {
                    String f = unescape(m.group(1));
                    for (String fn : functions(f)) {
                        if (!ALLOWED.contains(fn)) {
                            out.add(e.getName() + ": " + fn + " in " + f);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out;
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
