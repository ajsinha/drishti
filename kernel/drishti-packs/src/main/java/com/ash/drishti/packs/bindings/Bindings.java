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
package com.ash.drishti.packs.bindings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The site's data bindings, {@code config/bindings.yaml} (docs/architecture/KERNEL_AND_MODULES.md section 5.4): one binding per kind, shared
 * by every module that needs the kind. A binding names the site's connectors ({@code config/connectors/<name>.yaml}) in read order, whether
 * they are one store together ({@code union}), the connectors that push changes ({@code live}: one, or a list when several source systems
 * stream the kind, merged into the live result set by key and version), where a produced kind is written
 * ({@code write: {lake, table}}) or that the demo lake serves it ({@code demo: true}). It never holds a credential, a host or a location:
 * those stay in the connector files and the secret store. Immutable; {@link #parse} refuses what it does not understand, with words.
 *
 * <pre>
 *   format: 1
 *   bindings:
 *     trade:       { read: [trade-endur, trade-calypso], union: true, live: [trade-endur-kafka, trade-calypso-kafka] }
 *     scenario-pv: { read: [risk-store] }
 *     var-official: { write: { lake: risklake, table: banking.var-official } }
 *     pnl-vector:  { demo: true }
 * </pre>
 */
public record Bindings(Map<String, Binding> byKind) {

    /** No bindings: every kind resolves by default, convention or the demo lake. */
    public static final Bindings NONE = new Bindings(Map.of());

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_.\\-]{0,99}");
    private static final Set<String> TOP = Set.of("format", "bindings");
    private static final Set<String> KEYS = Set.of("read", "union", "live", "write", "demo", "note");
    /** Words that would carry a connection or a secret: never in a binding (section 5.10). */
    private static final Pattern SECRETISH = Pattern.compile("(?i)password|passwd|secret|token|api-?key|credential|jdbc:|://|bootstrap-servers|\\$\\{");

    public Bindings {
        byKind = java.util.Collections.unmodifiableMap(new TreeMap<>(byKind));
    }

    /**
     * One kind's binding.
     *
     * @param kind the kind
     * @param read the connectors that serve it, in read order
     * @param union true when the read connectors are one store together (one slice each, ids unique across them)
     * @param live the connectors that push changes of it, in order (empty: none)
     * @param writeLake the lake a produced kind is written to, or null
     * @param writeTable the table in that lake, or null
     * @param demo true when the demo lake's samples serve it explicitly
     * @param note the administrator's note, or empty
     */
    public record Binding(String kind, List<String> read, boolean union, List<String> live, String writeLake, String writeTable, boolean demo,
            String note) {

        public Binding {
            read = read == null ? List.of() : List.copyOf(read);
            live = live == null ? List.of() : List.copyOf(live);
            note = note == null ? "" : note;
        }

        /** A binding with one live connector (or none, for null). */
        public Binding(String kind, List<String> read, boolean union, String live, String writeLake, String writeTable, boolean demo, String note) {
            this(kind, read, union, live == null ? List.of() : List.of(live), writeLake, writeTable, demo, note);
        }

        /** Every connector the binding names: the read order, then the live ones. */
        public List<String> connectors() {
            List<String> out = new ArrayList<>(read);
            live.forEach(l -> {
                if (!out.contains(l)) {
                    out.add(l);
                }
            });
            return out;
        }

        /** True when a produced kind is written somewhere. */
        public boolean writes() {
            return writeLake != null;
        }

        /** The binding as YAML values, for the file and the API. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!read.isEmpty()) {
                m.put("read", read);
            }
            if (union) {
                m.put("union", true);
            }
            if (live.size() == 1) {
                m.put("live", live.get(0));                   // one connector is written as a name, several as a list
            } else if (!live.isEmpty()) {
                m.put("live", live);
            }
            if (writeLake != null) {
                m.put("write", Map.of("lake", writeLake, "table", writeTable));
            }
            if (demo) {
                m.put("demo", true);
            }
            if (!note.isBlank()) {
                m.put("note", note);
            }
            return m;
        }
    }

    /** A file or a proposal that cannot be used, with the words to say why. */
    public static final class Invalid extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public Invalid(String message) {
            super(message);
        }
    }

    /** The binding of {@code kind}, if the site set one. */
    public Optional<Binding> of(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    /** The kinds bound to read from (or go live through) {@code connector}. */
    public Set<String> kindsOf(String connector) {
        Set<String> out = new LinkedHashSet<>();
        byKind.values().forEach(b -> {
            if (b.connectors().contains(connector)) {
                out.add(b.kind());
            }
        });
        return out;
    }

    /** Every connector any binding names. */
    public Set<String> connectors() {
        Set<String> out = new LinkedHashSet<>();
        byKind.values().forEach(b -> out.addAll(b.connectors()));
        return out;
    }

    /**
     * Each bound kind's sources for the router: its {@code read} list, then its {@code live} connector (the subscriptions' source). The
     * router reads a bound kind from these and no other connector (section 5.5: first hit wins).
     */
    public Map<String, List<String>> readOrder() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        byKind.values().forEach(b -> {
            if (!b.connectors().isEmpty()) {
                out.put(b.kind(), b.connectors());
            }
        });
        return out;
    }

    /** A copy with {@code kind}'s binding replaced ({@code null} removes it). */
    public Bindings with(String kind, Binding b) {
        Map<String, Binding> m = new LinkedHashMap<>(byKind);
        if (b == null) {
            m.remove(kind);
        } else {
            m.put(kind, b);
        }
        return new Bindings(m);
    }

    /** The file's content as YAML values. */
    public Map<String, Object> toMap() {
        Map<String, Object> b = new LinkedHashMap<>();
        byKind.forEach((k, v) -> b.put(k, v.toMap()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", 1);
        out.put("bindings", b);
        return out;
    }

    /** Parses the file's YAML values; refuses an unknown key, a bad name, a binding with nothing in it, or anything like a credential. */
    public static Bindings parse(Map<String, Object> yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return NONE;
        }
        for (String k : yaml.keySet()) {
            if (!TOP.contains(k)) {
                throw new Invalid("unknown key '" + k + "' (allowed: format, bindings)");
            }
        }
        Object f = yaml.get("format");
        if (f != null && !"1".equals(String.valueOf(f))) {
            throw new Invalid("format " + f + " is not known here (this server reads format 1)");
        }
        Map<String, Binding> out = new LinkedHashMap<>();
        if (yaml.get("bindings") == null) {
            return NONE;
        }
        if (!(yaml.get("bindings") instanceof Map<?, ?> all)) {
            throw new Invalid("'bindings' is a mapping of kinds to their binding");
        }
        for (Map.Entry<?, ?> e : all.entrySet()) {
            String kind = String.valueOf(e.getKey()).trim();
            out.put(kind, binding(kind, e.getValue()));
        }
        return new Bindings(out);
    }

    /** One kind's binding from its YAML values (a proposal from Admin → Data bindings is checked the same way). */
    public static Binding binding(String kind, Object value) {
        if (!NAME.matcher(kind).matches()) {
            throw new Invalid("'" + kind + "' is not a kind's name");
        }
        if (!(value instanceof Map<?, ?> m)) {
            throw new Invalid(kind + ": a binding is a mapping (read, union, live, write, demo)");
        }
        for (Object k : m.keySet()) {
            String key = String.valueOf(k);
            if (!KEYS.contains(key)) {
                String why = "for".equals(key) ? " (a per-module binding is not built: one binding per kind)" : "";
                throw new Invalid(kind + ": unknown key '" + key + "'" + why + " (allowed: read, union, live, write, demo, note)");
            }
        }
        if (SECRETISH.matcher(String.valueOf(m)).find()) {
            throw new Invalid(kind + ": a binding names connectors and lakes only; a host, a URL, a placeholder or a credential belongs in the "
                    + "connector's file and the secret store");
        }
        List<String> read = new ArrayList<>();
        Object r = m.get("read");
        if (r instanceof List<?> l) {
            l.forEach(x -> read.add(String.valueOf(x).trim()));
        } else if (r instanceof String s && !s.isBlank()) {
            for (String x : s.split(",")) {
                if (!x.isBlank()) {
                    read.add(x.trim());
                }
            }
        } else if (r != null) {
            throw new Invalid(kind + ": 'read' is a list of connectors, in read order");
        }
        for (String c : read) {
            if (!NAME.matcher(c).matches()) {
                throw new Invalid(kind + ": '" + c + "' is not a connector's name");
            }
        }
        if (read.size() != new LinkedHashSet<>(read).size()) {
            throw new Invalid(kind + ": a connector is named twice in 'read'");
        }
        List<String> live = new ArrayList<>();
        Object lv = m.get("live");
        if (lv instanceof List<?> l) {
            l.forEach(x -> live.add(String.valueOf(x).trim()));
        } else if (lv != null) {
            for (String x : String.valueOf(lv).split(",")) {
                if (!x.isBlank()) {
                    live.add(x.trim());
                }
            }
        }
        for (String c : live) {
            if (!NAME.matcher(c).matches()) {
                throw new Invalid(kind + ": '" + c + "' is not a connector's name");
            }
        }
        if (live.size() != new LinkedHashSet<>(live).size()) {
            throw new Invalid(kind + ": a connector is named twice in 'live'");
        }
        String lake = null;
        String table = null;
        if (m.get("write") instanceof Map<?, ?> w) {
            lake = w.get("lake") == null ? null : String.valueOf(w.get("lake")).trim();
            table = w.get("table") == null ? null : String.valueOf(w.get("table")).trim();
            if (lake == null || table == null || !NAME.matcher(lake).matches() || !NAME.matcher(table.toLowerCase(Locale.ROOT)).matches()) {
                throw new Invalid(kind + ": 'write' names a lake and a table ({lake: risklake, table: banking.var-official})");
            }
        } else if (m.get("write") != null) {
            throw new Invalid(kind + ": 'write' is {lake, table}");
        }
        boolean union = Boolean.parseBoolean(String.valueOf(m.get("union")));
        boolean demo = Boolean.parseBoolean(String.valueOf(m.get("demo")));
        if (union && read.size() < 2) {
            throw new Invalid(kind + ": 'union' needs two or more connectors in 'read'");
        }
        if (demo && !read.isEmpty()) {
            throw new Invalid(kind + ": a kind is either demo or real, never both: drop 'demo' or 'read'");
        }
        if (read.isEmpty() && live.isEmpty() && lake == null && !demo) {
            throw new Invalid(kind + ": the binding names nothing (read, live, write or demo)");
        }
        String note = m.get("note") == null ? "" : String.valueOf(m.get("note"));
        return new Binding(kind, read, union, live, lake, table, demo, note);
    }
}
