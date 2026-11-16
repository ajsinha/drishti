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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.parse.PNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-level helpers shared by the Sutra text editors ({@link SutraLayoutEditor}, {@link SutraPivotEditor} and the
 * design operations): where the panel list lives, the lines of each panel, and editing a flow mapping in place so
 * comments and formatting survive. Stateless.
 */
public final class SutraText {

    private static final Pattern VERSION = Pattern.compile("^(version:\\s*)(\\d+)(.*)$");

    private SutraText() {}

    /** The lines of the panel list: where it starts and ends, each item's lines, and the column of its dashes. */
    public record Blocks(int start, int end, int dash, List<List<String>> items) {}

    public static Blocks blocks(List<String> lines, PNode root) {
        PNode panels = root.map().get("panels");
        if (panels == null || panels.list().isEmpty()) {
            throw new IllegalArgumentException("the Sutra has no panels");
        }
        int keyLine = keyLine(lines, "panels");
        if (keyLine < 0 || lines.get(keyLine).substring("panels:".length()).strip().startsWith("[")) {
            throw new IllegalArgumentException("the panels are written as one flow list ([...]): write them one per line to promote a layout");
        }
        List<Integer> starts = new ArrayList<>();
        int dash = -1;
        for (PNode item : panels.list()) {
            int at = item.line() - 1;
            int found = -1;
            for (int l = at; l > keyLine && found < 0; l--) {
                String t = lines.get(l);
                int c = indent(t);
                if (c < t.length() && t.charAt(c) == '-' && (dash < 0 || c == dash)) {
                    found = l;
                    dash = c;
                }
            }
            if (found < 0) {
                throw new IllegalArgumentException("cannot find the start of a panel near line " + item.line());
            }
            starts.add(found);
        }
        int end = starts.get(starts.size() - 1) + 1;
        while (end < lines.size() && !topLevel(lines.get(end))) {
            end++;
        }
        while (end > starts.get(starts.size() - 1) + 1 && (lines.get(end - 1).isBlank() || lines.get(end - 1).startsWith("#"))) {
            end--;                                    // blank lines and comments before the next key stay where they are
        }
        // a comment just above a panel belongs to it
        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i);
            int floor = i == 0 ? keyLine : starts.get(i - 1);
            while (s - 1 > floor && isComment(lines.get(s - 1)) && indent(lines.get(s - 1)) >= dash) {
                s--;
            }
            starts.set(i, s);
        }
        List<List<String>> items = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int to = i + 1 < starts.size() ? starts.get(i + 1) : end;
            items.add(new ArrayList<>(lines.subList(starts.get(i), to)));
        }
        return new Blocks(starts.get(0), end, dash, items);
    }

    private static int keyLine(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + ":")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean topLevel(String line) {
        return !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.charAt(0) != '#' && line.charAt(0) != '-';
    }

    private static boolean isComment(String line) {
        return line.strip().startsWith("#");
    }

    public static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** A flow mapping item ({@code - { id: x, kind: kv, ... }}), possibly over several lines. */
    public static void flow(List<String> block, int d, Map<String, String> set) {
        StringBuilder sb = new StringBuilder();
        int closeLine = -1;
        int depth = 0;
        char quote = 0;
        for (int l = d; l < block.size() && closeLine < 0; l++) {
            String t = block.get(l);
            for (int c = 0; c < t.length(); c++) {
                char ch = t.charAt(c);
                if (quote != 0) {
                    if (ch == quote) {
                        quote = 0;
                    }
                } else if (ch == '"' || ch == '\'') {
                    quote = ch;
                } else if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        closeLine = l;
                        break;
                    }
                }
            }
        }
        if (closeLine < 0) {
            throw new IllegalArgumentException("a panel's flow mapping is not closed");
        }
        for (int l = d; l <= closeLine; l++) {
            sb.append(l > d ? "\n" : "").append(block.get(l));
        }
        String text = sb.toString();
        for (Map.Entry<String, String> e : set.entrySet()) {
            text = flowSet(text, e.getKey(), e.getValue());
        }
        List<String> replaced = List.of(text.split("\n", -1));
        for (int l = closeLine; l >= d; l--) {
            block.remove(l);
        }
        block.addAll(d, replaced);
    }

    /** Sets or removes one depth-1 key of the flow mapping in {@code text}. */
    public static String flowSet(String text, String key, String value) {
        int open = text.indexOf('{');
        List<int[]> entries = new ArrayList<>();                 // [start, end) of each depth-1 entry
        int depth = 0;
        int start = open + 1;
        int close = -1;
        char quote = 0;
        for (int c = open; c < text.length() && close < 0; c++) {
            char ch = text.charAt(c);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                if (depth == 0) {
                    entries.add(new int[] {start, c});
                    close = c;
                }
            } else if (ch == ',' && depth == 1) {
                entries.add(new int[] {start, c});
                start = c + 1;
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            int[] en = entries.get(i);
            String entry = text.substring(en[0], en[1]);
            int colon = entry.indexOf(':');
            if (colon < 0 || !entry.substring(0, colon).strip().equals(key)) {
                continue;
            }
            if (value == null) {
                int from = i == 0 ? en[0] : entries.get(i - 1)[1];      // from the comma before it
                int to = i == 0 && entries.size() > 1 ? entries.get(1)[0] : en[0] + entry.stripTrailing().length();
                return text.substring(0, from) + text.substring(to);
            }
            int v = en[0] + colon + 1;
            String rest = text.substring(v, en[1]);
            int lead = rest.length() - rest.stripLeading().length();
            int trail = rest.length() - rest.stripTrailing().length();
            return text.substring(0, v + lead) + value + text.substring(en[1] - trail);
        }
        if (value == null) {
            return text;
        }
        int[] last = entries.get(entries.size() - 1);
        String lastText = text.substring(last[0], last[1]);
        int endOfLast = last[0] + lastText.stripTrailing().length();
        return text.substring(0, endOfLast) + ", " + key + ": " + value + text.substring(endOfLast);
    }

    public static int bumpVersion(List<String> out, PNode root) {
        PNode v = root.map().get("version");
        int now = v != null && v.value() instanceof Long l ? l.intValue() : 0;
        for (int i = 0; i < out.size(); i++) {
            Matcher m = VERSION.matcher(out.get(i));
            if (m.matches()) {
                out.set(i, m.group(1) + (now + 1) + m.group(3));
                return now + 1;
            }
        }
        throw new IllegalArgumentException("cannot find the Sutra's version line");
    }
}
