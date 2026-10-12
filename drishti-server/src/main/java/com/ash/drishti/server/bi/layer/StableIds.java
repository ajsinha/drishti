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
package com.ash.drishti.server.bi.layer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the stable ids of a dataset or a model into its YAML on save (docs/architecture/BI_MODEL_SPEC.md section 2.8): the item's own id after
 * {@code version:}, and an id on every column ({@code c_...}), measure ({@code m_...}) and relationship ({@code r_...}) that has none. Ids already written
 * are never changed, so a rename keeps its id and consumers that store ids keep working. The text is edited line by line, so comments and layout stay; an
 * entry written in a form this does not recognise is left without an id (Check still passes; the designer writes it).
 */
public final class StableIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ENTRY = Pattern.compile("^(\\s+)(\"[^\"]+\"|'[^']+'|[^\\s#:][^:#]*?):(\\s*)(.*)$");
    private static final Pattern HAS_ID = Pattern.compile("[{,]\\s*id\\s*:");

    private StableIds() {}

    /** The text with every missing id written; the same text when nothing was missing. */
    public static String write(String yaml, boolean model) {
        List<String> lines = new ArrayList<>(List.of(yaml.split("\n", -1)));
        if (lines.stream().noneMatch(l -> l.startsWith("id:"))) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("version:")) {
                    lines.add(i + 1, "id: " + (model ? "md_" : "ds_") + HexFormat.of().formatHex(bytes()));
                    break;
                }
            }
        }
        if (model) {
            section(lines, "measures", "m_");
            relationships(lines);
        } else {
            section(lines, "columns", "c_");
        }
        return String.join("\n", lines);
    }

    private static byte[] bytes() {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        return b;
    }

    private static void section(List<String> lines, String key, String prefix) {
        int start = lines.indexOf(key + ":");
        if (start < 0) {
            return;
        }
        Set<String> used = new LinkedHashSet<>();
        Matcher idm = Pattern.compile("\\bid\\s*:\\s*(" + prefix + "[A-Za-z0-9_]+)").matcher(String.join("\n", lines));
        while (idm.find()) {
            used.add(idm.group(1));
        }
        String indent = null;
        for (int i = start + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank() || l.trim().startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(l.charAt(0))) {
                break;                                                    // the next top-level key
            }
            String lead = l.substring(0, l.length() - l.stripLeading().length());
            if (indent == null) {
                indent = lead;
            }
            if (!lead.equals(indent)) {
                continue;
            }
            Matcher m = ENTRY.matcher(l);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(2).replaceAll("^[\"']|[\"']$", "");
            String rest = m.group(4);
            String id = fresh(prefix, name, used);
            if (rest.isEmpty() || rest.startsWith("#")) {
                if (!blockHasId(lines, i + 1, lead.length())) {
                    lines.add(i + 1, lead + "  id: " + id);
                } else {
                    used.remove(id);
                }
            } else if (rest.startsWith("{")) {
                String body = flow(rest);
                if (body == null || HAS_ID.matcher(body).find()) {
                    used.remove(id);
                    continue;
                }
                String inner = body.substring(1, body.length() - 1).trim();
                String withId = "{ " + (inner.isEmpty() ? "" : inner + ", ") + "id: " + id + " }";
                lines.set(i, l.substring(0, l.length() - rest.length()) + withId + rest.substring(body.length()));
            } else {
                String scalar = rest.contains(" #") ? rest.substring(0, rest.indexOf(" #")).trim() : rest.trim();
                String comment = rest.substring(scalar.length());
                String k = "m_".equals(prefix) ? "expr" : "type";
                lines.set(i, l.substring(0, l.length() - rest.length()) + "{ " + k + ": " + scalar + ", id: " + id + " }" + comment);
            }
        }
    }

    private static boolean blockHasId(List<String> lines, int from, int indent) {
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank()) {
                continue;
            }
            int lead = l.length() - l.stripLeading().length();
            if (lead <= indent) {
                return false;
            }
            if (l.trim().startsWith("id:")) {
                return true;
            }
        }
        return false;
    }

    /** The flow mapping at the start of {@code rest}, braces balanced and quotes respected; null when it does not close on this line. */
    private static String flow(String rest) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return rest.substring(0, i + 1);
            }
        }
        return null;
    }

    private static String fresh(String prefix, String name, Set<String> used) {
        String slug = name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        String base = prefix + (slug.isEmpty() ? "x" : "m_".equals(prefix) ? slug.toLowerCase(Locale.ROOT) : slug);
        String id = base;
        for (int n = 2; used.contains(id); n++) {
            id = base + "_" + n;
        }
        used.add(id);
        return id;
    }

    private static void relationships(List<String> lines) {
        int start = lines.indexOf("relationships:");
        if (start < 0) {
            return;
        }
        int max = 0;
        Matcher m = Pattern.compile("\\bid\\s*:\\s*r_(\\d+)").matcher(String.join("\n", lines));
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        for (int i = start + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!l.isBlank() && !Character.isWhitespace(l.charAt(0)) && !l.startsWith("-")) {
                break;
            }
            String t = l.stripLeading();
            if (t.startsWith("- {") && !HAS_ID.matcher(t).find()) {
                int at = l.indexOf("- {") + 3;
                lines.set(i, l.substring(0, at) + " id: r_" + (++max) + "," + l.substring(at));
            }
        }
    }
}
