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
package com.ash.drishti.server.capacity;

import com.ash.drishti.common.Branding;
import com.ash.drishti.server.dist.XlsxWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The planner workbook for a download (Admin → Capacity, "Download the working"): the template {@code tools/sizing_planner.py} writes into
 * {@code capacity/template/} (the committed workbook's parts, branding as {@code {{PRODUCT}}} placeholders, and {@code template.json} naming
 * each input's row), with only the Inputs sheet's value cells filled, so Results, Coefficients and Explain go out untouched and every
 * figure stays a live Excel formula (recalculated on opening: {@code fullCalcOnLoad}). An optional second Inputs column (Live or Plan)
 * holds the other set of figures beside them, and an optional block under the inputs lists measured figures that are not inputs.
 * The parts are copied into the zip on the response's stream, as the VaR Playground's workbook is.
 */
public final class CapacityWorkbook {

    static final String BASE = "capacity/template/";
    private static final Pattern COLS_END = Pattern.compile("</cols>");

    private final JsonNode meta;
    private final Map<String, String> parts = new LinkedHashMap<>();

    private CapacityWorkbook(JsonNode meta, Map<String, String> parts) {
        this.meta = meta;
        this.parts.putAll(parts);
    }

    /** The built-in template, from the classpath. */
    public static CapacityWorkbook builtIn() {
        try {
            JsonNode meta = new ObjectMapper().readTree(read(BASE + "template.json"));
            Map<String, String> parts = new LinkedHashMap<>();
            for (JsonNode p : meta.get("parts")) {
                parts.put(p.asText(), read(BASE + p.asText()));
            }
            return new CapacityWorkbook(meta, parts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = CapacityWorkbook.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("the server has no " + resource + " (tools/sizing_planner.py writes it)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The parts' names, in the zip's order. */
    public List<String> partNames() {
        return List.copyOf(parts.keySet());
    }

    /** A template part as it is, placeholders unfilled. */
    public String part(String name) {
        return parts.get(name);
    }

    /** The row of an input on the Inputs sheet. */
    public int row(String input) {
        JsonNode n = meta.get("inputs").get(input);
        if (n == null) {
            throw new IllegalArgumentException("the template has no input " + input);
        }
        return n.get("row").asInt();
    }

    /**
     * Writes the workbook.
     *
     * @param values every input's value for the named column (B), by input name
     * @param extraHeader the second column's heading ({@code Live, measured …} or {@code Plan}), null for none
     * @param extra the second column's values by input name (an input without one is left empty)
     * @param measured label, value, unit rows listed under the inputs (measured figures that are not inputs); may be empty
     */
    public void write(OutputStream out, Map<String, Object> values, String extraHeader, Map<String, Object> extra, List<String[]> measured,
            Branding branding) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new java.io.FilterOutputStream(out) {
            @Override
            public void close() throws IOException {
                flush();                                              // the response's stream stays open for the container
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }
        }, StandardCharsets.UTF_8);
        zip.setLevel(Deflater.BEST_SPEED);
        String inputsSheet = meta.get("inputsSheet").asText();
        for (Map.Entry<String, String> p : parts.entrySet()) {
            String text = p.getKey().equals(inputsSheet) ? inputs(p.getValue(), values, extraHeader, extra, measured) : p.getValue();
            zip.putNextEntry(new ZipEntry(p.getKey()));
            zip.write(brand(text, branding).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    static String brand(String text, Branding b) {
        return text.replace("{{PRODUCT}}", XlsxWriter.esc(b.product())).replace("{{OWNER}}", XlsxWriter.esc(b.owner()))
                .replace("{{COPYRIGHT}}", XlsxWriter.esc(b.copyright()));
    }

    /** The Inputs sheet with its value cells (column B) replaced, the second column and the measured block added. */
    String inputs(String sheet, Map<String, Object> values, String extraHeader, Map<String, Object> extra, List<String[]> measured) {
        String col = meta.get("valueColumn").asText();
        String xcol = meta.get("extraColumn").asText();
        JsonNode styles = meta.get("styles");
        String s = sheet;
        int lastRow = 0;
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) meta.get("inputs")::fields) {
            int row = e.getValue().get("row").asInt();
            lastRow = Math.max(lastRow, row);
            if (values.containsKey(e.getKey())) {
                s = replaceCell(s, col + row, values.get(e.getKey()));
            }
            if (extraHeader != null && extra.get(e.getKey()) != null) {
                Object v = extra.get(e.getKey());
                String style = styles.get(v instanceof Number ? "num0" : "text").asText();
                s = appendToRow(s, row, cell(xcol + row, style, v));
            }
        }
        if (extraHeader != null) {
            int head = meta.get("headerRow").asInt();
            s = appendToRow(s, head, cell(xcol + head, styles.get("head").asText(), extraHeader));
            s = COLS_END.matcher(s).replaceFirst("<col min=\"6\" max=\"6\" width=\"30\" customWidth=\"1\"/></cols>");
        }
        if (measured != null && !measured.isEmpty()) {
            StringBuilder rows = new StringBuilder();
            int r = lastRow + 2;
            rows.append("<row r=\"").append(r).append("\">").append(cell("A" + r, styles.get("head").asText(), "Measured on this server (not inputs)"))
                    .append(cell("B" + r, styles.get("head").asText(), "Value")).append(cell("C" + r, styles.get("head").asText(), "Unit")).append("</row>");
            for (String[] m : measured) {
                r++;
                rows.append("<row r=\"").append(r).append("\">").append(cell("A" + r, styles.get("text").asText(), m[0]))
                        .append(cell("B" + r, styles.get("num0").asText(), number(m[1]))).append(cell("C" + r, styles.get("note").asText(), m[2]))
                        .append("</row>");
            }
            s = s.replace("</sheetData>", rows + "</sheetData>");
        }
        return s;
    }

    private static Object number(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException | NullPointerException e) {
            return v == null ? "" : v;
        }
    }

    /** One cell: a number, or inline text. */
    static String cell(String ref, String style, Object v) {
        if (v instanceof Number n) {
            return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>" + plain(n.doubleValue()) + "</v></c>";
        }
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + XlsxWriter.esc(String.valueOf(v))
                + "</t></is></c>";
    }

    /** 300000, not 300000.0 or 3.0E5. */
    static String plain(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** The cell {@code ref} with a new value, its style kept. */
    static String replaceCell(String sheet, String ref, Object v) {
        Matcher m = Pattern.compile("<c r=\"" + ref + "\"(?: s=\"(\\d+)\")?[^>]*?(?:/>|>.*?</c>)").matcher(sheet);
        if (!m.find()) {
            throw new IllegalStateException("the template's Inputs sheet has no cell " + ref);
        }
        String style = m.group(1) == null ? "0" : m.group(1);
        return sheet.substring(0, m.start()) + cell(ref, style, v) + sheet.substring(m.end());
    }

    private static String appendToRow(String sheet, int row, String cell) {
        Matcher m = Pattern.compile("<row r=\"" + row + "\"[^>]*>.*?(</row>)").matcher(sheet);
        if (!m.find()) {
            throw new IllegalStateException("the template's Inputs sheet has no row " + row);
        }
        return sheet.substring(0, m.start(1)) + cell + sheet.substring(m.start(1));
    }
}
