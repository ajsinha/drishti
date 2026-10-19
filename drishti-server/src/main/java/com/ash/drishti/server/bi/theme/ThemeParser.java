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
package com.ash.drishti.server.bi.theme;

import com.ash.drishti.server.bi.report.FormatSchemas;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import com.ash.drishti.server.bi.report.VisualCatalogue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a theme item's YAML (docs/guides/REPORTS.md section 5.2) into a {@link Theme}. Never throws for a bad document: every problem comes back with
 * {@code DRS-9036} and the path of the key. The visual defaults are checked against the format schemas of their family ({@link FormatSchemas}), so a theme
 * cannot set what no visual would accept.
 *
 * <pre>
 * rachana: 1
 * theme: corporate
 * title: Corporate
 * palette: ["#1f4e79", "#2e75b6", "#9dc3e6", "#c55a11", "#70ad47", "#7f7f7f"]
 * background: "#ffffff"
 * foreground: "#1b1b1b"
 * font: { family: sans, size: 12 }
 * good: "#2e7d32"
 * bad: "#c62828"
 * visuals:
 *   chart: { labels: true, legend: bottom }
 *   matrix: { rowBanding: true, stepped: true }
 * </pre>
 */
public final class ThemeParser {

    /** The outcome: the theme when it was readable (null otherwise) and every problem found. */
    public record Result(Theme theme, List<Problem> problems) {
        public boolean ok() {
            return theme != null && problems.stream().noneMatch(Problem::error);
        }
    }

    static final String INVALID = "DRS-9036";
    /** The font families a theme may name: the console's own stacks (nothing is fetched from another site, the pages' policy forbids it). */
    public static final Set<String> FONTS = Set.of("sans", "serif", "mono", "system");
    private static final Set<String> TOP = Set.of("rachana", "theme", "version", "title", "description", "sample", "palette", "background", "foreground", "font", "good", "bad",
            "visuals", "owner", "tags", "endorsement", "endorsedBy", "audience");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private ThemeParser() {}

    public static Result parse(String yaml) {
        List<Problem> out = new ArrayList<>();
        JsonNode root;
        try {
            root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            out.add(error("", "the YAML is not valid: " + com.ash.drishti.server.bi.YamlErrors.describe(e)));
            return new Result(null, out);
        }
        if (root == null || !root.isObject()) {
            out.add(error("", "a theme is a YAML mapping with at least `theme:` (its name) and `palette:` (its colours)"));
            return new Result(null, out);
        }
        root.fieldNames().forEachRemaining(k -> {
            if (!TOP.contains(k)) {
                out.add(new Problem("warning", INVALID, k, "unknown key '" + k + "' is ignored"));
            }
        });
        String name = root.path("theme").asText("");
        if (!NAME.matcher(name).matches()) {
            out.add(error("theme", name.isBlank() ? "`theme:` names the theme (letters, digits, hyphen, underscore, dot)" : "'" + name + "' is not a usable name"));
        }
        if (com.ash.drishti.server.bi.report.Report.PALETTES.contains(name)) {
            out.add(error("theme", "'" + name + "' is a built-in palette; a theme takes another name"));
        }
        List<String> palette = new ArrayList<>();
        JsonNode p = root.path("palette");
        if (!p.isArray() || p.size() < 3 || p.size() > 12) {
            out.add(error("palette", "palette is a list of 3 to 12 colours (#rrggbb), the series colours in order"));
        } else {
            for (int i = 0; i < p.size(); i++) {
                if (!HEX.matcher(p.get(i).asText()).matches()) {
                    out.add(error("palette[" + i + "]", "'" + p.get(i).asText() + "' is not a colour (#rrggbb)"));
                } else {
                    palette.add(p.get(i).asText().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        String background = colour(root, "background", out);
        String foreground = colour(root, "foreground", out);
        String good = colour(root, "good", out);
        String bad = colour(root, "bad", out);
        Map<String, Object> font = new LinkedHashMap<>();
        JsonNode f = root.path("font");
        if (f.isObject()) {
            if (f.has("family")) {
                if (!FONTS.contains(f.path("family").asText())) {
                    out.add(error("font.family", "family is one of " + String.join(", ", new java.util.TreeSet<>(FONTS)) + " (the console's own; no font is fetched)"));
                } else {
                    font.put("family", f.path("family").asText());
                }
            }
            if (f.has("size")) {
                if (!f.path("size").canConvertToInt() || f.path("size").asInt() < 10 || f.path("size").asInt() > 18) {
                    out.add(error("font.size", "size is 10 to 18 (px)"));
                } else {
                    font.put("size", f.path("size").asInt());
                }
            }
        } else if (!f.isMissingNode() && !f.isNull()) {
            out.add(error("font", "font is { family: sans | serif | mono | system, size: 12 }"));
        }
        if (background != null && foreground != null && contrast(background, foreground) < 4.5) {
            out.add(new Problem("warning", "A11Y", "foreground", String.format(java.util.Locale.ROOT, "text %s on %s has a contrast ratio of %.1f; 4.5 is needed", foreground, background,
                    contrast(background, foreground))));
        }
        return new Result(new Theme(name, root.path("version").asInt(1), root.path("title").asText(name), root.path("description").asText(""), root.path("sample").asBoolean(false), palette,
                background, foreground, font, good, bad, visuals(root.path("visuals"), out)), out);
    }

    /** {@code visuals: { <family or type>: { format defaults } }}, each checked against the format schema of its family. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> visuals(JsonNode n, List<Problem> out) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return all;
        }
        if (!n.isObject()) {
            out.add(error("visuals", "visuals maps a visual family (chart, table, matrix, card, ...) or type (column, kpi, ...) to its format defaults"));
            return all;
        }
        FormatSchemas schemas = FormatSchemas.get();
        n.fields().forEachRemaining(e -> {
            String k = e.getKey();
            String type = VisualCatalogue.type(k) != null ? k : schemas.families().entrySet().stream().filter(x -> x.getValue().equals(k)).map(Map.Entry::getKey).findFirst().orElse(null);
            if (type == null) {
                out.add(error("visuals." + k, "'" + k + "' is not a visual family (" + String.join(", ", FormatSchemas.FAMILIES) + ") or type"));
                return;
            }
            if (!e.getValue().isObject()) {
                out.add(error("visuals." + k, "the defaults are a mapping of format keys"));
                return;
            }
            Map<String, Object> defaults = JSON.convertValue(e.getValue(), LinkedHashMap.class);
            for (Problem pr : schemas.check(type, defaults, "visuals." + k)) {
                String path = pr.path().replace("visuals." + k + ".format", "visuals." + k);
                boolean notUsed = pr.message().contains("does not use");                          // a family default some of its types ignore is fine
                if (!notUsed) {
                    out.add(new Problem(pr.level(), pr.error() ? INVALID : pr.code(), path, pr.message()));
                }
            }
            all.put(k, defaults);
        });
        return all;
    }

    private static String colour(JsonNode root, String key, List<Problem> out) {
        JsonNode v = root.path(key);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (!HEX.matcher(v.asText()).matches()) {
            out.add(error(key, "'" + v.asText() + "' is not a colour (#rrggbb)"));
            return null;
        }
        return v.asText().toLowerCase(java.util.Locale.ROOT);
    }

    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double[] c = {(rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    static Problem error(String path, String message) {
        return new Problem("error", INVALID, path, message);
    }
}
