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
package com.ash.drishti.server.tables;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Python-driven table editor's text work (HOW_IT_FITS.md section S4), none of which runs Python: the build function's item (a BI Python item,
 * {@code interface: transform}, {@code entry: build}, on the server) written from the editor's fields and read back; the templates for common
 * cases; a notebook's cells turned into a build function; and the output contract proposed from the code (the columns it assigns and
 * selects, typed from its inputs' columns where it keeps them). The sample run itself is a run on the server's compute pool
 * {@code python} through {@code PythonRuns}.
 */
public final class PythonDrafts {

    /** The templates shipped with the server: id, title, says, inputs, params, code and contract. */
    public static final String TEMPLATES = "tables/python-templates.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES).enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE));
    private static final Pattern ITEM_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern READ_TABLE = Pattern.compile("drishti\\.table\\(\\s*['\"]([a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*)['\"]\\s*\\)");
    private static final Pattern ASSIGN = Pattern.compile("\\[\\s*['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]\\s*]\\s*=(?!=)");
    private static final Pattern ASSIGN_KW = Pattern.compile("\\.assign\\(([^)]*)\\)");
    private static final Pattern KW = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)");
    private static final Pattern SELECT = Pattern.compile("\\[\\s*\\[([^\\]]+)]\\s*]");
    private static final Pattern QUOTED = Pattern.compile("['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]");

    private PythonDrafts() {}

    /** The Python item's name for a table's name ({@code market.fx_daily} is {@code market-fx_daily}). */
    public static String itemName(String table) {
        String n = table == null ? "" : table.replace('.', '-').replaceAll("[^A-Za-z0-9_-]", "_");
        return ITEM_NAME.matcher(n).matches() ? n : "python-table";
    }

    /**
     * The manifest of the build function's item from the editor's fields: {@code {name, source, inputs: [table, ...], packages, timeout,
     * memory}}.
     */
    @SuppressWarnings("unchecked")
    public static String manifest(Map<String, Object> python) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 1);
        m.put("python", itemName(String.valueOf(python.getOrDefault("name", ""))));
        m.put("title", String.valueOf(python.getOrDefault("title", python.getOrDefault("name", "build"))));
        m.put("interface", "transform");
        m.put("entry", "build");
        List<String> inputs = new ArrayList<>();
        if (python.get("inputs") instanceof List<?> l) {
            l.forEach(x -> inputs.add(String.valueOf(x)));
        }
        m.put("inputs", inputs);
        if (python.get("packages") instanceof List<?> l && !l.isEmpty()) {
            m.put("packages", l);
        }
        m.put("target", "server");
        m.put("timeout", number(python.get("timeout"), 60));
        m.put("memory", number(python.get("memory"), 512));
        m.put("source", String.valueOf(python.getOrDefault("source", "")));
        try {
            return YAML.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the build function cannot be written: " + e.getOriginalMessage(), e);
        }
    }

    /** True when {@code body} is the manifest of the Python item named {@code ref}. */
    public static boolean named(String body, String ref) {
        Map<String, Object> m = parse(body);
        return m != null && ref != null && (ref.equals(String.valueOf(m.get("python"))) || itemName(ref).equals(String.valueOf(m.get("python"))));
    }

    /** The editor's fields of a Python item. */
    public static Map<String, Object> read(String id, String body) {
        Map<String, Object> m = parse(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", id);
        if (m == null) {
            out.put("missing", true);
            return out;
        }
        out.put("name", m.get("python"));
        out.put("source", m.getOrDefault("source", ""));
        out.put("inputs", m.getOrDefault("inputs", List.of()));
        out.put("packages", m.getOrDefault("packages", List.of()));
        out.put("timeout", m.getOrDefault("timeout", 60));
        out.put("memory", m.getOrDefault("memory", 512));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Object o = YAML.readValue(body, Object.class);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static int number(Object o, int dflt) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? dflt : Integer.parseInt(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** The templates for common cases (join and derive, resample, statistics per group, a file or feed from an approved source). */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> templates() {
        try (InputStream in = PythonDrafts.class.getClassLoader().getResourceAsStream(TEMPLATES)) {
            if (in == null) {
                return List.of();
            }
            Map<String, Object> m = YAML.readValue(in, Map.class);
            return m.get("templates") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (IOException e) {
            throw new IllegalStateException(TEMPLATES + " cannot be read: " + e.getMessage(), e);
        }
    }

    /**
     * A notebook's cells as a build function: each {@code drishti.table("domain.name")} becomes the input {@code inputs["name"]}, the imports
     * move to the top, every other line goes into {@code build(inputs, params)}, and the last cell's last expression is what it returns.
     *
     * @return {@code {source, inputs: {alias: table}}}
     */
    public static Map<String, Object> fromNotebook(List<String> cells) {
        Set<String> imports = new LinkedHashSet<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        List<String> body = new ArrayList<>();
        int n = 0;
        for (String cell : cells) {
            n++;
            if (cell == null || cell.isBlank()) {
                continue;
            }
            body.add("# cell " + n);
            for (String raw : cell.split("\n", -1)) {
                String line = raw.stripTrailing();
                if (line.startsWith("import ") || line.startsWith("from ") && line.contains(" import ")) {
                    imports.add(line.strip());
                    continue;
                }
                Matcher t = READ_TABLE.matcher(line);
                StringBuilder b = new StringBuilder();
                while (t.find()) {
                    String table = t.group(1);
                    String alias = table.substring(table.indexOf('.') + 1);
                    inputs.putIfAbsent(alias, table);
                    t.appendReplacement(b, Matcher.quoteReplacement("inputs[\"" + alias + "\"]"));
                }
                t.appendTail(b);
                body.add(b.toString());
            }
        }
        while (!body.isEmpty() && body.get(body.size() - 1).isBlank()) {
            body.remove(body.size() - 1);
        }
        if (!body.isEmpty()) {
            String last = body.get(body.size() - 1);
            String s = last.strip();
            boolean expression = !s.isEmpty() && !s.startsWith("#") && !s.startsWith("return") && !s.endsWith(":") && !KW.matcher(s).lookingAt()
                    && !Character.isWhitespace(last.charAt(0)) && !s.startsWith("print(");
            if (expression) {
                body.set(body.size() - 1, "return " + s);
            } else {
                body.add("return result  # name the table the build function returns");
            }
        }
        StringBuilder src = new StringBuilder();
        imports.forEach(i -> src.append(i).append('\n'));
        if (!imports.isEmpty()) {
            src.append('\n');
        }
        src.append("def build(inputs, params):\n");
        if (body.isEmpty()) {
            src.append("    return None\n");
        }
        for (String line : body) {
            src.append(line.isBlank() ? "" : "    " + line).append('\n');
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", src.toString());
        out.put("inputs", inputs);
        return out;
    }

    /**
     * The output contract proposed from the code: every column it assigns ({@code out["X"] = ...}, {@code .assign(X=...)}) and, when its last
     * selection lists columns ({@code df[["A", "B"]]}), those in that order; a column kept from an input has the input's type, a new one
     * {@code decimal}. A proposal only: the author confirms it, and the sample run checks the rows against it.
     *
     * @param inputColumns each input column's name to its type
     */
    public static Map<String, Map<String, Object>> contract(String code, Map<String, String> inputColumns) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (code == null) {
            return out;
        }
        List<String> selected = new ArrayList<>();
        Matcher sel = SELECT.matcher(code);
        while (sel.find()) {
            selected.clear();
            Matcher q = QUOTED.matcher(sel.group(1));
            while (q.find()) {
                selected.add(q.group(1));
            }
        }
        Set<String> assigned = new LinkedHashSet<>();
        Matcher a = ASSIGN.matcher(code);
        while (a.find()) {
            assigned.add(a.group(1));
        }
        Matcher kw = ASSIGN_KW.matcher(code);
        while (kw.find()) {
            Matcher k = KW.matcher(kw.group(1));
            while (k.find()) {
                assigned.add(k.group(1));
            }
        }
        List<String> names = new ArrayList<>(selected.isEmpty() ? new ArrayList<>(assigned) : selected);
        for (String name : names) {
            Map<String, Object> c = new LinkedHashMap<>();
            String t = inputColumns.get(name);
            c.put("type", t != null && !t.isBlank() ? t : "decimal");
            out.put(name, c);
        }
        return out;
    }
}
