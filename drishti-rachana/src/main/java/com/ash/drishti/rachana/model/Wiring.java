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
package com.ash.drishti.rachana.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a panel of a Sutra Next screen is wired (docs/guides/RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, sections 6 to 8): the record it reads
 * ({@code source:}), the events it emits ({@code emits:}), the words it shows while an event it reads has not fired
 * ({@code waits:}) and its child panels ({@code children:}). {@link #NONE} for a panel without any.
 *
 * @param source the record the panel reads as {@code $src}, or null
 * @param emits the events a click on the panel fires
 * @param waits what the panel says while it waits for an event, or null for the default words
 * @param children the groups of child panels, filled when the panel fires
 */
public record Wiring(Source source, List<Emit> emits, String waits, List<Children> children) {

    public static final Wiring NONE = new Wiring(null, List.of(), null, List.of());

    /** The triggers built (RACHANA_REFERENCE.md 7): a table's or ladder's row, an hbar's bar. */
    public static final Set<String> TRIGGERS = Set.of("row-click", "bar-click");
    /** Where child panels are placed relative to their parent. */
    public static final List<String> PLACES = List.of("expand-row", "detail-right", "detail-below", "tabs");
    /** When child panels are shown: when the parent fires (the default), with the parent, or when the reader opens them. */
    public static final List<String> SHOWS = List.of("on-select", "always", "on-expand");
    /** The types a payload field may declare; {@code any} when it declares none. */
    public static final List<String> TYPES = List.of("id", "text", "number", "date", "bool", "any");
    /** Screen panel, child, grandchild: a great-grandchild is refused ({@code DRS-2056}). */
    public static final int MAX_DEPTH = 3;

    public Wiring {
        emits = emits == null ? List.of() : List.copyOf(emits);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public boolean isNone() {
        return source == null && emits.isEmpty() && waits == null && children.isEmpty();
    }

    /** The event {@code name} this panel emits, if it declares it. */
    public java.util.Optional<Emit> emit(String name) {
        return emits.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    /**
     * Where a panel reads its data (RACHANA_REFERENCE.md 6): one entity ({@code kind} and {@code id}), the entities of a kind a
     * condition keeps ({@code where}, {@code @} each entity), or a text search of a kind ({@code search}); {@code pack} names the
     * pack when two serve the kind. {@code id}, {@code where} and {@code search} are Rachana-EL.
     *
     * @param pack the pack serving the kind, or null
     * @param kind the entity kind
     * @param id an expression for the entity's id, or null
     * @param where a condition over each entity of the kind, or null
     * @param search an expression for the text searched, or null
     * @param limit the most entities a where or search source reads
     * @param asOf an expression for the business date read, or null for the screen's
     * @param params typed parameters of a computed kind (DATA_ARCHITECTURE.md › Reactive connectors, section 6.1): name to expression, sorted by name; empty
     *        for a kind that is read, not computed
     */
    public record Source(String pack, String kind, String id, String where, String search, int limit, String asOf, java.util.Map<String, String> params) {
        public static final int DEFAULT_LIMIT = 20;
        public static final int MAX_LIMIT = 200;
        public static final int MAX_PARAMS = 32;

        public Source {
            params = params == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(params));
        }

        /** A source without parameters. */
        public Source(String pack, String kind, String id, String where, String search, int limit, String asOf) {
            this(pack, kind, id, where, search, limit, asOf, java.util.Map.of());
        }

        /** Whether the source names one entity ({@code $src} a record) rather than a list ({@code $src} a list of records). */
        public boolean single() {
            return id != null;
        }

        /** The expressions of the source, in order (id, where, search, as-of, then each parameter's), without the absent ones. */
        public List<String> expressions() {
            List<String> out = new ArrayList<>();
            for (String e : new String[] {id, where, search, asOf}) {
                if (e != null) {
                    out.add(e);
                }
            }
            out.addAll(params.values());
            return out;
        }
    }

    /**
     * An event the panel fires: {@code name}, on what ({@code on}: row-click, bar-click) and its payload, each field an
     * expression over the clicked row ({@code @}) with an optional type.
     */
    public record Emit(String name, String on, Map<String, Field> payload) {
        public Emit {
            payload = payload == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    /** One field of a payload: the expression and the declared type ({@code any} when none). */
    public record Field(String bind, String type) {}

    /**
     * A group of child panels: placed {@code place} relative to the parent, shown {@code show}, reading the group's
     * {@code source} (each panel may name its own instead), waiting with {@code waits}.
     */
    public record Children(String place, String show, Source source, String waits, List<Panel> panels) {
        public Children {
            panels = panels == null ? List.of() : List.copyOf(panels);
        }
    }

    private static final Pattern REF = Pattern.compile("@([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*)((?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");
    private static final Pattern QUOTED = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");

    /** One reference to an event in an expression: {@code @panel.event.field} (event and field null when not written). */
    public record Ref(String panel, String event, String field) {}

    /** The event references in an expression or a template ({@code @trades.open.trade}, {@code @parent.select.ID}); strings are skipped. */
    public static List<Ref> refs(String text) {
        if (text == null || text.indexOf('@') < 0) {
            return List.of();
        }
        String bare = QUOTED.matcher(text).replaceAll("''");
        List<Ref> out = new ArrayList<>();
        Matcher m = REF.matcher(bare);
        while (m.find()) {
            if (m.start() > 0 && (Character.isLetterOrDigit(bare.charAt(m.start() - 1)) || bare.charAt(m.start() - 1) == '_')) {
                continue;                                 // an @ inside a word (an e-mail address in a template's text)
            }
            String[] parts = m.group(2).isEmpty() ? new String[0] : m.group(2).substring(1).split("\\.");
            out.add(new Ref(m.group(1), parts.length > 0 ? parts[0] : null, parts.length > 1 ? parts[1] : null));
        }
        return out;
    }
}
