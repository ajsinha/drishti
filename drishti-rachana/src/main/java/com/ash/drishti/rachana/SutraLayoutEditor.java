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

import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.PNode;
import com.ash.drishti.rachana.parse.PositionalYamlReader;
import com.ash.drishti.rachana.parse.SutraParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a personal layout (the order, column, width and height of each panel) into the text of the next version of a
 * Sutra, editing the Sutra's own text so its comments, quoting and formatting survive: panel blocks are moved, and only
 * the {@code area}, {@code span} and {@code height} keys are added, changed or removed. Panels written as block mappings
 * ({@code - id: legs} with keys below) and as flow mappings ({@code - { id: refs, kind: links }}, on one line or
 * several) are both edited; a {@code panels:} written as one flow list is refused. The result is parsed again and must
 * have exactly the requested arrangement. Stateless and thread-safe.
 */
public final class SutraLayoutEditor {

    /** Where one panel goes. {@code span} null (or 12) and {@code height} null mean the defaults. */
    public record Placement(String id, Area area, Integer span, Integer height, boolean hidden) {}

    /**
     * @param text the next version's text
     * @param fromVersion the version it was made from
     * @param version the new version
     * @param changes what changed, one plain sentence each
     */
    public record Edit(String text, int fromVersion, int version, List<String> changes) {}

    private static final Pattern VERSION = Pattern.compile("^(version:\\s*)(\\d+)(.*)$");
    private static final List<String> SIZE_KEYS = List.of("area", Panel.SPAN, Panel.HEIGHT);

    private final SutraParser parser = new SutraParser();
    private final PositionalYamlReader reader = new PositionalYamlReader();

    /**
     * @param source the Sutra's text (valid)
     * @param placements the panels in their new order, each with its column and sizes; panels not listed keep theirs
     *     and their place
     * @param dropHidden remove the panels marked hidden from the Sutra (otherwise they stay, as they are)
     * @throws IllegalArgumentException when the text cannot be edited this way (a flow list of panels)
     */
    public Edit apply(String source, List<Placement> placements, boolean dropHidden) {
        Sutra sutra = parser.parse(source, SutraParser.STUDIO, "studio");
        PNode root;
        try {
            root = reader.read(source);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the Sutra: " + e.getMessage(), e);
        }
        Map<String, Placement> wanted = new LinkedHashMap<>();
        for (Placement p : placements) {
            if (sutra.panels().stream().anyMatch(x -> x.id().equals(p.id()))) {
                wanted.putIfAbsent(p.id(), p);
            }
        }
        List<String> lines = new ArrayList<>(List.of(source.split("\n", -1)));
        Blocks blocks = blocks(lines, root);
        List<String> changes = new ArrayList<>();

        // the new value of each panel: placement, or as written
        Map<String, Placement> target = new LinkedHashMap<>();
        for (Panel p : sutra.panels()) {
            Placement w = wanted.get(p.id());
            target.put(p.id(), w == null ? new Placement(p.id(), p.area(), p.span().orElse(null), p.height().orElse(null), false)
                    : new Placement(p.id(), w.area() == null ? p.area() : w.area(), normal(w.span()), w.height(), w.hidden()));
        }
        // edit each block's keys
        Map<String, List<String>> edited = new HashMap<>();
        for (int i = 0; i < sutra.panels().size(); i++) {
            Panel p = sutra.panels().get(i);
            Placement t = target.get(p.id());
            List<String> block = new ArrayList<>(blocks.items.get(i));
            Map<String, String> set = new LinkedHashMap<>();
            if (t.area() != p.area()) {
                set.put("area", t.area() == Area.RIGHT ? "right" : null);
                changes.add("'" + p.id() + "' moves to the " + (t.area() == Area.RIGHT ? "side" : "main") + " column");
            }
            if (!Objects.equals(t.span(), normal(p.span().orElse(null)))) {
                set.put(Panel.SPAN, t.span() == null ? null : String.valueOf(t.span()));
                changes.add("'" + p.id() + "' is " + (t.span() == null ? "as wide as its column" : t.span() + " of 12 columns wide"));
            }
            if (!Objects.equals(t.height(), p.height().orElse(null))) {
                set.put(Panel.HEIGHT, t.height() == null ? null : String.valueOf(t.height()));
                changes.add("'" + p.id() + "' is " + (t.height() == null ? "as tall as its content" : t.height() + " rows tall"));
            }
            if (!set.isEmpty()) {
                edit(block, blocks.dash, set);
            }
            edited.put(p.id(), block);
        }
        // order: each slot keeps the column its panel now has, and takes the next panel of that column in the new order
        List<String> mainOrder = order(target, wanted, sutra, Area.MAIN);
        List<String> rightOrder = order(target, wanted, sutra, Area.RIGHT);
        Iterator<String> mains = mainOrder.iterator();
        Iterator<String> rights = rightOrder.iterator();
        List<String> ids = new ArrayList<>();
        for (Panel p : sutra.panels()) {
            ids.add(target.get(p.id()).area() == Area.RIGHT ? rights.next() : mains.next());
        }
        noteOrder(sutra, target, mainOrder, Area.MAIN, changes);
        noteOrder(sutra, target, rightOrder, Area.RIGHT, changes);
        List<String> out = new ArrayList<>(lines.subList(0, blocks.start));
        for (String id : ids) {
            if (dropHidden && target.get(id).hidden()) {
                changes.add("'" + id + "' is removed (hidden in the layout)");
                continue;
            }
            out.addAll(edited.get(id));
        }
        out.addAll(lines.subList(blocks.end, lines.size()));
        int fromVersion = sutra.version();
        int version = bumpVersion(out, root);
        String text = String.join("\n", out);
        verify(text, ids, target, dropHidden);
        return new Edit(text, fromVersion, version, changes);
    }

    private static Integer normal(Integer span) {
        return span == null || span >= Panel.MAX_SPAN ? null : span;
    }

    /** The panels of one column in their new order: the placements' order, then panels the placements do not list. */
    private static List<String> order(Map<String, Placement> target, Map<String, Placement> wanted, Sutra s, Area area) {
        List<String> out = new ArrayList<>();
        for (String id : wanted.keySet()) {
            if (target.get(id).area() == area) {
                out.add(id);
            }
        }
        for (Panel p : s.panels()) {
            if (!wanted.containsKey(p.id()) && target.get(p.id()).area() == area) {
                out.add(p.id());
            }
        }
        return out;
    }

    private static void noteOrder(Sutra s, Map<String, Placement> target, List<String> order, Area area, List<String> changes) {
        List<String> before = s.panels().stream().map(Panel::id).filter(id -> target.get(id).area() == area).toList();
        if (!before.equals(order)) {
            changes.add("the " + (area == Area.RIGHT ? "side" : "main") + " column reads " + String.join(", ", order));
        }
    }

    // ---- the panels' text -------------------------------------------------------------------------------------

    /** The lines of the panel list: where it starts and ends, each item's lines, and the column of its dashes. */
    private record Blocks(int start, int end, int dash, List<List<String>> items) {}

    private static Blocks blocks(List<String> lines, PNode root) {
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

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** Sets ({@code value}) or removes ({@code null}) top-level keys of one panel item. */
    private static void edit(List<String> block, int dash, Map<String, String> set) {
        int d = 0;
        while (d < block.size() && !(indent(block.get(d)) == dash && block.get(d).length() > dash && block.get(d).charAt(dash) == '-')) {
            d++;
        }
        String after = block.get(d).substring(dash + 1).strip();
        if (after.startsWith("{")) {
            flow(block, d, set);
        } else {
            mapping(block, d, dash, set);
        }
    }

    private static void mapping(List<String> block, int d, int dash, Map<String, String> set) {
        String first = block.get(d);
        int keyIndent = first.substring(dash + 1).isBlank() ? indent(block.get(d + 1)) : dash + 1 + indent(first.substring(dash + 1));
        int kindLine = -1;
        for (Map.Entry<String, String> e : set.entrySet()) {
            int at = -1;
            for (int l = d; l < block.size(); l++) {
                String t = block.get(l);
                String body = l == d ? " ".repeat(keyIndent) + t.substring(keyIndent) : t;
                if (indent(body) == keyIndent && body.startsWith(e.getKey() + ":", keyIndent)) {
                    at = l;
                }
                if (indent(body) == keyIndent && body.startsWith("kind:", keyIndent)) {
                    kindLine = l;
                }
            }
            if (at == d) {
                throw new IllegalArgumentException("'" + e.getKey() + "' written on a panel's first line cannot be edited: move it below");
            }
            if (e.getValue() == null) {
                if (at >= 0) {
                    block.remove(at);
                }
            } else if (at >= 0) {
                String t = block.get(at);
                int hash = t.indexOf(" #");
                block.set(at, " ".repeat(keyIndent) + e.getKey() + ": " + e.getValue() + (hash > 0 ? t.substring(hash) : ""));
            } else {
                int after = kindLine >= 0 ? kindLine : d;
                for (int l = d + 1; l < block.size(); l++) {         // next to the size keys it already has, if any
                    String t = block.get(l);
                    if (indent(t) == keyIndent && SIZE_KEYS.stream().anyMatch(k -> t.startsWith(k + ":", keyIndent))) {
                        after = l;
                    }
                }
                block.add(lastSizeKey(block, after, keyIndent) + 1, " ".repeat(keyIndent) + e.getKey() + ": " + e.getValue());
            }
        }
    }

    /** The last line of the run of area/span/height keys that follows {@code after} (or {@code after} itself). */
    private static int lastSizeKey(List<String> block, int after, int keyIndent) {
        String pad = " ".repeat(keyIndent);
        int l = after;
        while (l + 1 < block.size()) {
            String next = block.get(l + 1);
            if (SIZE_KEYS.stream().noneMatch(k -> next.startsWith(pad + k + ":"))) {
                break;
            }
            l++;
        }
        return l;
    }

    /** A flow mapping item ({@code - { id: x, kind: kv, ... }}), possibly over several lines. */
    private static void flow(List<String> block, int d, Map<String, String> set) {
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
    static String flowSet(String text, String key, String value) {
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

    private static int bumpVersion(List<String> out, PNode root) {
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

    private void verify(String text, List<String> ids, Map<String, Placement> target, boolean dropHidden) {
        Sutra s = parser.parse(text, SutraParser.STUDIO, "studio");
        List<String> expected = ids.stream().filter(id -> !(dropHidden && target.get(id).hidden())).toList();
        List<String> got = s.panels().stream().map(Panel::id).toList();
        if (!got.equals(expected)) {
            throw new IllegalStateException("the edited Sutra lists its panels as " + got + ", not " + expected);
        }
        for (Panel p : s.panels()) {
            Placement t = target.get(p.id());
            if (p.area() != t.area() || !Objects.equals(normal(p.span().orElse(null)), t.span())
                    || !Objects.equals(p.height().orElse(null), t.height())) {
                throw new IllegalStateException("the edited Sutra places '" + p.id() + "' as " + p.area().name().toLowerCase(Locale.ROOT)
                        + " " + p.span() + " " + p.height() + ", not as asked");
            }
        }
    }
}
