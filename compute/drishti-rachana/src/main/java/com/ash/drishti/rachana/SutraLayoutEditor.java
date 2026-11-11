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
        return apply(source, placements, dropHidden, true);
    }

    /** As {@link #apply(String, List, boolean)}; {@code bump} false leaves the version line as it is (design operations). */
    public Edit apply(String source, List<Placement> placements, boolean dropHidden, boolean bump) {
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
        SutraText.Blocks blocks = SutraText.blocks(lines, root);
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
            List<String> block = new ArrayList<>(blocks.items().get(i));
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
                edit(block, blocks.dash(), set);
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
        List<String> out = new ArrayList<>(lines.subList(0, blocks.start()));
        for (String id : ids) {
            if (dropHidden && target.get(id).hidden()) {
                changes.add("'" + id + "' is removed (hidden in the layout)");
                continue;
            }
            out.addAll(edited.get(id));
        }
        out.addAll(lines.subList(blocks.end(), lines.size()));
        int fromVersion = sutra.version();
        int version = bump ? SutraText.bumpVersion(out, root) : fromVersion;
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

    /** Sets ({@code value}) or removes ({@code null}) top-level keys of one panel item. */
    private static void edit(List<String> block, int dash, Map<String, String> set) {
        int d = 0;
        while (d < block.size() && !(SutraText.indent(block.get(d)) == dash && block.get(d).length() > dash && block.get(d).charAt(dash) == '-')) {
            d++;
        }
        String after = block.get(d).substring(dash + 1).strip();
        if (after.startsWith("{")) {
            SutraText.flow(block, d, set);
        } else {
            mapping(block, d, dash, set);
        }
    }

    private static void mapping(List<String> block, int d, int dash, Map<String, String> set) {
        String first = block.get(d);
        int keyIndent = first.substring(dash + 1).isBlank() ? SutraText.indent(block.get(d + 1)) : dash + 1 + SutraText.indent(first.substring(dash + 1));
        int kindLine = -1;
        for (Map.Entry<String, String> e : set.entrySet()) {
            int at = -1;
            for (int l = d; l < block.size(); l++) {
                String t = block.get(l);
                String body = l == d ? " ".repeat(keyIndent) + t.substring(keyIndent) : t;
                if (SutraText.indent(body) == keyIndent && body.startsWith(e.getKey() + ":", keyIndent)) {
                    at = l;
                }
                if (SutraText.indent(body) == keyIndent && body.startsWith("kind:", keyIndent)) {
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
                    if (SutraText.indent(t) == keyIndent && SIZE_KEYS.stream().anyMatch(k -> t.startsWith(k + ":", keyIndent))) {
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
