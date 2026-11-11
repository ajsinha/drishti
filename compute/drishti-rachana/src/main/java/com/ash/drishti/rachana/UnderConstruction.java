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

import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.ScreenPhone;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The under-construction registry ({@code config/under-construction.yaml}; docs/admin/UNDER_CONSTRUCTION.md): every figure, panel, menu item,
 * help page or contract that depends on work not built yet, and how it is treated: {@code hidden} (left out) or {@code under-construction}
 * (shown, its value replaced by the words "Under construction", tone {@code uc}, which the renderers draw as a chip). The data stays where it
 * is; only what is displayed changes. One mechanism: a Sutra is rewritten here, when it is matched to a document, so every page, panel,
 * pivot and renderer reads the same answer; the console reads the same file for its menus and help pages.
 */
public final class UnderConstruction {

    /** The words shown in place of a registered value. */
    public static final String TEXT = "Under construction";
    /** The tone the renderers draw as the "Under construction" chip. */
    public static final String TONE = "uc";
    /** The binding expression that shows the words. */
    static final String LITERAL = "'" + TEXT + "'";

    private static final UnderConstruction NONE = new UnderConstruction(List.of());

    /** Where an item shows on a kind's page: strip items and kv/table columns by their bind, panels by their id. */
    public record SutraPlace(String kind, List<String> binds, List<String> panels) {}

    /** One registered item. */
    public record Item(String id, String what, String about, String release, String treatment, List<SutraPlace> sutras, List<String> menus,
            List<String> help, List<String> contracts) {

        public boolean hidden() {
            return "hidden".equals(treatment);
        }
    }

    private final List<Item> items;
    private final Map<String, Map<String, Boolean>> binds = new HashMap<>();     // kind -> bind -> hidden?
    private final Map<String, Map<String, Boolean>> panels = new HashMap<>();    // kind -> panel id -> hidden?

    private UnderConstruction(List<Item> items) {
        this.items = List.copyOf(items);
        for (Item i : items) {
            for (SutraPlace p : i.sutras()) {
                p.binds().forEach(b -> binds.computeIfAbsent(p.kind(), k -> new HashMap<>()).put(b, i.hidden()));
                p.panels().forEach(id -> panels.computeIfAbsent(p.kind(), k -> new HashMap<>()).put(id, i.hidden()));
            }
        }
    }

    /** Nothing registered. */
    public static UnderConstruction none() {
        return NONE;
    }

    /** The registry at {@code file}; a file that does not exist registers nothing. */
    public static UnderConstruction load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException(file + ": cannot be read (" + e.getMessage() + ")", e);
        }
    }

    /** The registry from its YAML text (module doc). */
    public static UnderConstruction parse(String yaml) {
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        } catch (IOException e) {
            throw new IllegalStateException("the under-construction registry is not valid YAML: " + e.getMessage(), e);
        }
        List<Item> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : root == null ? List.<JsonNode>of() : iterable(root.path("items"))) {
            String id = n.path("id").asText("");
            String treatment = n.path("treatment").asText("");
            if (id.isBlank() || !ids.add(id)) {
                throw new IllegalStateException("the under-construction registry: an item without an id, or a repeated id '" + id + "'");
            }
            if (!treatment.equals("hidden") && !treatment.equals("under-construction")) {
                throw new IllegalStateException(id + ": treatment must be hidden or under-construction, not '" + treatment + "'");
            }
            List<SutraPlace> places = new ArrayList<>();
            for (JsonNode s : iterable(n.path("sutras"))) {
                places.add(new SutraPlace(s.path("kind").asText(""), texts(s.path("binds")), texts(s.path("panels"))));
            }
            out.add(new Item(id, n.path("what").asText(""), n.path("about").asText(""), n.path("release").asText(""), treatment, places,
                    texts(n.path("menus")), texts(n.path("help")), texts(n.path("contracts"))));
        }
        return new UnderConstruction(out);
    }

    public List<Item> items() {
        return items;
    }

    /** Whether nothing is registered for {@code kind}'s pages. */
    public boolean none(String kind) {
        return !binds.containsKey(kind) && !panels.containsKey(kind);
    }

    /** {@code s} as the registry shows it: registered values replaced by the words (tone {@code uc}), hidden ones left out. */
    public Sutra apply(Sutra s) {
        String kind = s.match() == null ? null : s.match().kind();
        if (kind == null || none(kind)) {
            return s;
        }
        Map<String, Boolean> b = binds.getOrDefault(kind, Map.of());
        Map<String, Boolean> p = panels.getOrDefault(kind, Map.of());
        List<StripItem> strip = new ArrayList<>();
        for (StripItem i : s.strip()) {
            Boolean hide = b.get(i.bind());
            if (hide == null) {
                strip.add(i);
            } else if (!hide) {
                strip.add(new StripItem(i.label(), LITERAL, null, TONE, i.emphasis(), i.location()));
            }
        }
        List<Panel> out = new ArrayList<>();
        for (Panel x : s.panels()) {
            Panel y = panel(x, b, p);
            if (y != null) {
                out.add(y);
            }
        }
        ScreenPhone phone = s.phone();
        if (phone != null && phone.summary() != null && b.keySet().stream().anyMatch(phone.summary()::contains)) {
            phone = new ScreenPhone(phone.order(), phone.defaultTier(), null);      // a summary line that reads a registered value: none
        }
        return new Sutra(s.name(), s.version(), s.domain(), s.match(), s.title(), strip, out, s.keys(), s.location(), s.description(),
                s.controls(), s.layout(), phone);
    }

    private static Panel panel(Panel x, Map<String, Boolean> binds, Map<String, Boolean> panels) {
        Boolean hide = x.id() == null ? null : panels.get(x.id());
        if (hide != null) {
            if (hide) {
                return null;
            }
            String label = x.title() == null || x.title().isBlank() ? "" : x.title();
            return new Panel(x.id(), PanelKind.KV, x.title(), x.key(), x.code(), x.area(), false,
                    List.of(new Column(label, LITERAL, null, TONE, false, false)), null, layoutOptions(x.options()), x.location());
        }
        List<Column> cols = new ArrayList<>();
        boolean changed = false;
        for (Column c : x.columns()) {
            Boolean h = binds.get(c.bind());
            if (h == null) {
                cols.add(c);
            } else {
                changed = true;
                if (!h) {
                    cols.add(new Column(c.label(), LITERAL, null, TONE, false, false));
                }
            }
        }
        List<Panel> parts = new ArrayList<>();
        for (Panel q : x.parts()) {
            Panel r = panel(q, binds, panels);
            changed |= r != q;
            if (r != null) {
                parts.add(r);
            }
        }
        return changed ? new Panel(x.id(), x.kind(), x.title(), x.key(), x.code(), x.area(), x.infer(), cols, x.body(), x.options(), x.location(),
                parts, x.wiring()) : x;
    }

    /** The options that place a panel on the screen (its span, height, area, order, section), kept when its content is replaced. */
    private static Map<String, Object> layoutOptions(Map<String, Object> options) {
        Map<String, Object> keep = new HashMap<>();
        for (String k : List.of(Panel.SPAN, Panel.HEIGHT, Panel.GRID_AREA, Panel.ORDER, Panel.SECTION, Panel.COLLAPSIBLE, Panel.WHEN)) {
            if (options.containsKey(k)) {
                keep.put(k, options.get(k));
            }
        }
        return keep;
    }

    private static Iterable<JsonNode> iterable(JsonNode n) {
        return n == null || !n.isArray() ? List.of() : n::elements;
    }

    private static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (JsonNode x : iterable(n)) {
            out.add(x.asText());
        }
        return out;
    }
}
