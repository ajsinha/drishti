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
import com.ash.tools.xlsx.WorkbookTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planner workbook for a download (Admin → Capacity, "Download the working"): the template {@code tools/sizing_planner.py} writes into
 * {@code capacity/template/} (the committed workbook's parts, branding as {@code {{PRODUCT}}} placeholders, and {@code template.json} naming
 * each input's row), with only the Inputs sheet's value cells filled, so Results, Coefficients and Explain go out untouched and every
 * figure stays a live Excel formula (recalculated on opening: {@code fullCalcOnLoad}). An optional second Inputs column (Live or Plan)
 * holds the other set of figures beside them, and an optional block under the inputs lists measured figures that are not inputs.
 * The parts are copied into the zip on the response's stream by the shared workbook writer's template ({@code tools-xlsx}), as the VaR
 * Playground's workbook is written; this class knows only where the planner's cells are.
 */
public final class CapacityWorkbook {
    static final String BASE = "capacity/template/";

    private final JsonNode meta;
    private final WorkbookTemplate template;

    private CapacityWorkbook(JsonNode meta, WorkbookTemplate template) {
        this.meta = meta;
        this.template = template;
    }

    /** The built-in template, from the classpath. */
    public static CapacityWorkbook builtIn() {
        try {
            JsonNode meta = new ObjectMapper().readTree(read(BASE + "template.json"));
            Map<String, String> parts = new LinkedHashMap<>();
            for (JsonNode p : meta.get("parts")) {
                parts.put(p.asText(), read(BASE + p.asText()));
            }
            return new CapacityWorkbook(meta, WorkbookTemplate.of(parts));       // the copyright comments each part carries are dropped
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
        return template.partNames();
    }

    /** A template part as it is, placeholders unfilled. */
    public String part(String name) {
        return template.part(name);
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
        inputs(values, extraHeader, extra, measured).fill(brandValues(branding)).write(out);
    }

    /** The branding's placeholders: {@code {{PRODUCT}}}, {@code {{OWNER}}}, {@code {{COPYRIGHT}}}. */
    static Map<String, String> brandValues(Branding b) {
        return Map.of("PRODUCT", b.product(), "OWNER", b.owner(), "COPYRIGHT", b.copyright());
    }

    static String brand(String text, Branding b) {
        return WorkbookTemplate.fill(text, brandValues(b));
    }

    /** The template with the Inputs sheet's value cells (column B) replaced, the second column and the measured block added. */
    WorkbookTemplate inputs(Map<String, Object> values, String extraHeader, Map<String, Object> extra, List<String[]> measured) {
        String sheet = meta.get("inputsSheet").asText();
        String col = meta.get("valueColumn").asText();
        String xcol = meta.get("extraColumn").asText();
        JsonNode styles = meta.get("styles");
        WorkbookTemplate t = template;
        int lastRow = 0;
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) meta.get("inputs")::fields) {
            int row = e.getValue().get("row").asInt();
            lastRow = Math.max(lastRow, row);
            if (values.containsKey(e.getKey())) {
                t = t.setCell(sheet, col + row, values.get(e.getKey()));
            }
            if (extraHeader != null && extra.get(e.getKey()) != null) {
                Object v = extra.get(e.getKey());
                t = t.addCell(sheet, xcol + row, styles.get(v instanceof Number ? "num0" : "text").asInt(), v);
            }
        }
        if (extraHeader != null) {
            int head = meta.get("headerRow").asInt();
            t = t.addCell(sheet, xcol + head, styles.get("head").asInt(), extraHeader);
            t = t.width(sheet, 5, 30);
        }
        if (measured != null && !measured.isEmpty()) {
            int r = lastRow + 2;
            int head = styles.get("head").asInt();
            t = t.addCell(sheet, "A" + r, head, "Measured on this server (not inputs)").addCell(sheet, "B" + r, head, "Value")
                    .addCell(sheet, "C" + r, head, "Unit");
            for (String[] m : measured) {
                r++;
                t = t.addCell(sheet, "A" + r, styles.get("text").asInt(), m[0]).addCell(sheet, "B" + r, styles.get("num0").asInt(), number(m[1]))
                        .addCell(sheet, "C" + r, styles.get("note").asInt(), m[2]);
            }
        }
        return t;
    }

    private static Object number(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException | NullPointerException e) {
            return v == null ? "" : v;
        }
    }
}
