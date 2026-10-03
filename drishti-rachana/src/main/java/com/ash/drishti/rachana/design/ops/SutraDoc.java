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
package com.ash.drishti.rachana.design.ops;

import com.ash.drishti.rachana.SutraText;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.PNode;
import com.ash.drishti.rachana.parse.PositionalYamlReader;
import com.ash.drishti.rachana.parse.SutraParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text of one Sutra, edited in place by line so comments, quoting and key order survive: set or remove a key of a
 * panel or of the Sutra, insert or remove a whole panel. Every edit re-reads the text first, so it always works on
 * what is there. Not thread-safe (one per operation list); the line helpers it shares with the layout editor live in
 * {@link SutraText}.
 */
final class SutraDoc {

    /** Top-level keys in their conventional order: a key that is absent goes before the first later key that is present. */
    private static final List<String> TOP_ORDER = List.of("rachana", "sutra", "version", "domain", "description", "match", "title", "strip",
            "panels", "keys", "notes");

    private final SutraParser parser = new SutraParser();
    private final PositionalYamlReader reader = new PositionalYamlReader();
    private List<String> lines;

    SutraDoc(String text) {
        this.lines = new ArrayList<>(List.of(text.split("\n", -1)));
    }

    String text() {
        return String.join("\n", lines);
    }

    /** The parsed Sutra (throws {@link com.ash.drishti.rachana.SutraException} when the text is not valid). */
    Sutra sutra() {
        return parser.parse(text(), SutraParser.STUDIO, "studio");
    }

    Panel panel(String id) {
        return sutra().panels().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new OpException(OpException.NO_PANEL, "no panel '" + id + "' in this Sutra"));
    }

    List<String> panelIds() {
        return sutra().panels().stream().map(Panel::id).toList();
    }

    // ---- panels --------------------------------------------------------------------------------------------------

    private PNode root() {
        try {
            return reader.read(text());
        } catch (IOException e) {
            throw new OpException(OpException.UNEDITABLE, "cannot read the Sutra: " + e.getMessage());
        }
    }

    private SutraText.Blocks blocks() {
        try {
            return SutraText.blocks(lines, root());
        } catch (IllegalArgumentException e) {
            throw new OpException(OpException.UNEDITABLE, e.getMessage());
        }
    }

    private int index(String id) {
        List<String> ids = panelIds();
        int i = ids.indexOf(id);
        if (i < 0) {
            throw new OpException(OpException.NO_PANEL, "no panel '" + id + "' in this Sutra");
        }
        return i;
    }

    private void write(SutraText.Blocks b, List<List<String>> items) {
        List<String> out = new ArrayList<>(lines.subList(0, b.start()));
        items.forEach(out::addAll);
        out.addAll(lines.subList(b.end(), lines.size()));
        lines = out;
    }

    /** Sets ({@code value}) or removes ({@code null}) one key of the panel {@code id}. */
    void setPanelKey(String id, String key, Object value) {
        int i = index(id);
        SutraText.Blocks b = blocks();
        List<List<String>> items = new ArrayList<>();
        b.items().forEach(it -> items.add(new ArrayList<>(it)));
        try {
            PanelLines.set(items.get(i), b.dash(), key, value == null ? null : YamlText.flow(value));
        } catch (IllegalArgumentException e) {
            throw new OpException(OpException.UNEDITABLE, e.getMessage());
        }
        write(b, items);
    }

    /** Inserts a new panel so that it becomes the {@code at}-th (0-based) panel in the file. */
    void insertPanel(int at, Map<String, Object> keys) {
        SutraText.Blocks b = blocks();
        List<List<String>> items = new ArrayList<>();
        b.items().forEach(it -> items.add(new ArrayList<>(it)));
        boolean flow = PanelLines.isFlow(items.get(Math.min(Math.max(at - 1, 0), items.size() - 1)), b.dash());
        items.add(Math.min(at, items.size()), PanelLines.render(keys, b.dash(), flow));
        write(b, items);
    }

    void removePanel(String id) {
        int i = index(id);
        SutraText.Blocks b = blocks();
        List<List<String>> items = new ArrayList<>();
        b.items().forEach(it -> items.add(new ArrayList<>(it)));
        items.remove(i);
        write(b, items);
    }

    // ---- the Sutra's own keys ------------------------------------------------------------------------------------

    /** Sets or (null, or an empty list or mapping) removes a top-level key, written where the key conventionally goes. */
    void setTop(String key, Object value) {
        List<String> next = value == null || value instanceof List<?> l && l.isEmpty() || value instanceof Map<?, ?> m && m.isEmpty()
                ? null : render(key, value);
        int at = find(key);
        if (at >= 0) {
            int end = extent(at);
            List<String> comments = new ArrayList<>();       // comment lines inside the block belong to the person who wrote them: they stay under the key
            for (int k = at + 1; k < end; k++) {
                if (lines.get(k).stripLeading().startsWith("#")) {
                    comments.add(lines.get(k));
                }
            }
            for (int k = at; k < end; k++) {
                lines.remove(at);
            }
            if (next != null) {
                List<String> block = new ArrayList<>(next);
                block.addAll(Math.min(1, block.size()), comments);
                lines.addAll(at, block);
            }
            return;
        }
        if (next == null) {
            return;
        }
        int where = lines.size();
        for (int k = TOP_ORDER.indexOf(key) + 1; k > 0 && k < TOP_ORDER.size() && where == lines.size(); k++) {
            int later = find(TOP_ORDER.get(k));
            if (later >= 0) {
                where = later;
                while (where > 0 && lines.get(where - 1).startsWith("#") && !lines.get(where - 1).isBlank()) {
                    where--;                                     // a comment just above the later key belongs to it
                }
            }
        }
        if (where == lines.size()) {
            while (where > 0 && lines.get(where - 1).isBlank()) {
                where--;
            }
        }
        lines.addAll(where, next);
    }

    private static List<String> render(String key, Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> l && "strip".equals(key)) {
            out.add(key + ":");
            l.forEach(o -> out.add("  - " + YamlText.flow(o)));
        } else if (value instanceof Map<?, ?> m && "keys".equals(key)) {
            out.add(key + ":");
            m.forEach((k, v) -> out.add("  " + YamlText.text(String.valueOf(k)) + ": " + YamlText.flow(v)));
        } else {
            out.add(key + ": " + YamlText.flow(value));
        }
        return out;
    }

    private int find(String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + ":")) {
                return i;
            }
        }
        return -1;
    }

    /** One past the last line of the top-level key at {@code at}: its nested lines and a list written level with the key. */
    private int extent(int at) {
        return PanelLines.extent(lines, at, 0);
    }

    /** The Sutra's whole text replaced. */
    void replaceAll(String text) {
        lines = new ArrayList<>(List.of(text.split("\n", -1)));
    }
}
