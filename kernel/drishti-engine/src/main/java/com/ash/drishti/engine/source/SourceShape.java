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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How a read connector shapes what it reads (docs/guides/DATA_LAKE_GUIDE.md, "Several source systems: shared or separate tables"): the same for
 * every plugin (Delta, Iceberg, files, Kafka), applied where the connector meets the router ({@link ConnectorInstance}).
 *
 * <pre>
 *   settings:
 *     lake: main
 *     lake-tables: trading.trades           # a shared table, or trading.trade_endur
 *     where: SOURCE_SYSTEM = 'ENDUR'        # the rows that are this connector's ({@link RowPredicate}); pushed down to the store
 *     set: { SOURCE_SYSTEM: ENDUR }         # constants for rows that lack the field
 *     map: mappings/endur-trade.yaml        # native field -> canonical field (renames, simple casts)
 * </pre>
 *
 * <p>Order: {@code where} tests the stored row (its native fields), then {@code map} renames and casts, then {@code set} fills what the
 * canonical row lacks. The record a store holds is unchanged: Raw JSON shows it before {@code map} ({@link ConnectorInstance#fetchNative}).
 * The map file ({@code format: 1}; {@code fields:} native name to a canonical name or {@code {to, cast}}, casts {@code text}, {@code number},
 * {@code integer}, {@code boolean}, {@code date}; {@code keep: false} drops native fields the map does not name) is read relative to the
 * connector's module ({@code module-dir}), else the connectors' folder. A predicate or a map that cannot be used refuses the connector at
 * load with {@code DRS-5034}, shown on Admin → Connectors. Immutable.
 */
public final class SourceShape {

    /** The field that says which trading system a canonical record came from. */
    public static final String SOURCE_SYSTEM = "SOURCE_SYSTEM";
    /** The error code a shape that cannot be used is refused with. */
    public static final String CODE = "DRS-5034";

    /** A connector that reads its rows as they are. */
    public static final SourceShape NONE = new SourceShape(RowPredicate.ALL, Map.of(), Map.of(), true, null, "", Map.of());

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * A native field's canonical name, cast ({@code null}: as read) and value translation ({@code values}: native value to canonical value;
     * a value it does not name stays as read).
     */
    public record Target(String to, String cast, Map<String, Object> values) {
        public Target {
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        public Target(String to, String cast) {
            this(to, cast, Map.of());
        }
    }

    private final RowPredicate where;
    private final Map<String, Object> set;
    private final Map<String, Target> map;
    private final Map<String, String> reverse;
    private final boolean keep;
    private final String system;
    private final String mapFile;
    /** Canonical fields the map gives one value always ({@code CANON: {value: X}}). */
    private final Map<String, Object> fixed;

    private SourceShape(RowPredicate where, Map<String, Object> set, Map<String, Target> map, boolean keep, String system, String mapFile,
            Map<String, Object> fixed) {
        this.fixed = fixed;
        this.where = where;
        this.set = set;
        this.map = map;
        this.keep = keep;
        this.system = system;
        this.mapFile = mapFile;
        Map<String, String> r = new LinkedHashMap<>();
        map.forEach((nat, t) -> r.put(t.to(), nat));
        this.reverse = Map.copyOf(r);
    }

    /**
     * The connector's shape from its settings ({@code where}, {@code set.<field>}, {@code map}).
     *
     * @param bases where a relative map file is looked for, in order (the connector's module folder first)
     * @throws IllegalStateException {@code DRS-5034} with words, when the predicate or the map cannot be used
     */
    public static SourceShape of(String connector, Map<String, String> settings, List<Path> bases) {
        if (settings == null) {
            return NONE;
        }
        String w = settings.get("where");
        Map<String, Object> set = new LinkedHashMap<>();
        settings.forEach((k, v) -> {
            if (k.startsWith("set.") && k.length() > 4) {
                set.put(k.substring(4), constant(v));
            }
        });
        String m = settings.get("map");
        if ((w == null || w.isBlank()) && set.isEmpty() && (m == null || m.isBlank())) {
            return NONE;
        }
        RowPredicate where;
        try {
            where = RowPredicate.parse(w);
        } catch (IllegalArgumentException e) {
            throw refused(connector, "where: " + e.getMessage());
        }
        Map<String, Target> map = Map.of();
        Map<String, Object> fixed = new LinkedHashMap<>();
        boolean keep = true;
        String file = "";
        if (m != null && !m.isBlank()) {
            Path p = locate(m.strip(), settings, bases);
            if (p == null) {
                throw refused(connector, "map: " + m.strip() + " is not found (looked in the connector's module folder and " + bases + ")");
            }
            file = p.toString();
            JsonNode doc;
            try {
                doc = YAML.readTree(p.toFile());
            } catch (IOException e) {
                throw refused(connector, "map: " + p + " is not valid YAML: " + e.getMessage().lines().findFirst().orElse(""));
            }
            if (doc == null || !doc.path("fields").isObject()) {
                throw refused(connector, "map: " + p + " has no 'fields' (native field: canonical field, or {to, cast})");
            }
            Map<String, Target> out = new LinkedHashMap<>();
            for (var it = doc.path("fields").fields(); it.hasNext();) {
                var e = it.next();
                JsonNode v = e.getValue();
                // two forms: native: canonical (or native: {to, cast}); and the source designer's canonical: {from: native, cast, values}
                // or canonical: {value: constant}
                boolean byCanonical = v.isObject() && (v.has("from") || v.has("value"));
                if (byCanonical && v.has("value") && !v.has("from")) {
                    if (!e.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                        throw refused(connector, "map: " + p + ": '" + e.getKey() + "' is not a field name");
                    }
                    fixed.put(e.getKey(), plain(v.get("value")));
                    continue;
                }
                String nat = byCanonical ? v.path("from").asText() : e.getKey();
                String to = byCanonical ? e.getKey() : v.isTextual() ? v.asText() : v.path("to").asText(e.getKey());
                String cast = v.isObject() && v.hasNonNull("cast") ? v.path("cast").asText().toLowerCase(Locale.ROOT) : null;
                if (cast != null && !List.of("text", "number", "integer", "boolean", "date").contains(cast)) {
                    throw refused(connector, "map: " + p + ": " + e.getKey() + " casts to '" + cast + "' (text, number, integer, boolean or date)");
                }
                if (to.isBlank() || !to.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || nat.isBlank()) {
                    throw refused(connector, "map: " + p + ": " + e.getKey() + " maps " + nat + " to '" + to + "', which is not a field name");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                if (v.path("values").isObject()) {
                    v.path("values").fields().forEachRemaining(x -> values.put(x.getKey(), plain(x.getValue())));
                }
                if (out.containsKey(nat)) {
                    throw refused(connector, "map: " + p + ": the native field " + nat + " is mapped twice");
                }
                out.put(nat, new Target(to, cast, values));
            }
            if (doc.path("set").isObject()) {                  // the map file's own constants; the connector's set wins
                doc.path("set").fields().forEachRemaining(x -> set.putIfAbsent(x.getKey(), plain(x.getValue())));
            }
            if (out.values().stream().map(Target::to).distinct().count() != out.size()) {
                throw refused(connector, "map: " + p + ": two native fields map to one canonical field");
            }
            map = java.util.Collections.unmodifiableMap(out);
            keep = !doc.has("keep") || doc.path("keep").asBoolean(true);
        }
        Object system = set.get(SOURCE_SYSTEM);
        if (system == null) {
            system = fixed.get(SOURCE_SYSTEM);
        }
        if (system == null) {
            system = where.fixes(SOURCE_SYSTEM).orElse(null);
        }
        return new SourceShape(where, java.util.Collections.unmodifiableMap(set), map, keep, system == null ? null : String.valueOf(system), file,
                java.util.Collections.unmodifiableMap(fixed));
    }

    private static Object plain(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        return n.asText();
    }

    private static IllegalStateException refused(String connector, String why) {
        return new IllegalStateException(CODE + " connector " + connector + ": " + why);
    }

    private static Path locate(String m, Map<String, String> settings, List<Path> bases) {
        Path given = Path.of(m);
        if (given.isAbsolute()) {
            return Files.isRegularFile(given) ? given : null;
        }
        List<Path> where = new java.util.ArrayList<>();
        String dir = settings.get("module-dir");
        if (dir != null && !dir.isBlank()) {
            where.add(Path.of(dir));
        }
        where.addAll(bases);
        for (Path b : where) {
            Path p = b.resolve(m).normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static Object constant(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip();
        if (s.equals("true") || s.equals("false")) {
            return Boolean.valueOf(s);
        }
        if (s.matches("-?\\d{1,15}")) {
            return Long.valueOf(s);
        }
        return s;
    }

    /** True when the connector reads its rows as they are. */
    public boolean none() {
        return this == NONE;
    }

    /** The connector's {@code where}. */
    public RowPredicate where() {
        return where;
    }

    /** The constants {@code set} gives. */
    public Map<String, Object> set() {
        return set;
    }

    /** The canonical fields the map gives one value always ({@code CANON: {value: X}}). */
    public Map<String, Object> fixed() {
        return fixed;
    }

    /** The map: native field to canonical field and cast. */
    public Map<String, Target> map() {
        return map;
    }

    /** The map file read, or empty. */
    public String mapFile() {
        return mapFile;
    }

    /** The trading system this connector feeds: its {@code set.SOURCE_SYSTEM}, else the value its {@code where} fixes; empty for neither. */
    public Optional<String> system() {
        return Optional.ofNullable(system);
    }

    /** Whether the stored row is this connector's. */
    public boolean admits(DataNode stored) {
        return where.test(stored);
    }

    /** The stored field a canonical field is read from ({@code map} reversed; a field the map does not name is its own). */
    public String nativeName(String canonical) {
        int dot = canonical.indexOf('.');
        String top = dot < 0 ? canonical : canonical.substring(0, dot);
        String n = reverse.get(top);
        if (n != null) {
            return dot < 0 ? n : n + canonical.substring(dot);
        }
        return canonical;
    }

    /** The canonical name of a stored field (the map's; else its own, or null when {@code keep: false} drops it). */
    public String canonicalName(String stored) {
        int dot = stored.indexOf('.');
        String top = dot < 0 ? stored : stored.substring(0, dot);
        Target t = map.get(top);
        if (t != null) {
            return dot < 0 ? t.to() : t.to() + stored.substring(dot);
        }
        return keep || reverse.containsKey(top) ? stored : null;
    }

    /** The canonical row: renamed and cast by {@code map}, then the constants {@code set} gives where the row lacks them. */
    public DataNode canonical(DataNode stored) {
        if (map.isEmpty() && set.isEmpty() && fixed.isEmpty() || !(stored.unwrap() instanceof Map<?, ?> raw)) {
            return stored;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = String.valueOf(e.getKey());
            Target t = map.get(k);
            if (t != null) {
                Object v = e.getValue();
                if (!t.values().isEmpty() && v != null && t.values().containsKey(String.valueOf(v))) {
                    v = t.values().get(String.valueOf(v));
                }
                out.put(t.to(), cast(v, t.cast()));
            } else if (keep && !out.containsKey(k)) {
                out.put(k, e.getValue());
            }
        }
        out.putAll(fixed);
        set.forEach((k, v) -> {
            Object had = out.get(k);
            if (had == null || had instanceof String s && s.isBlank()) {
                out.put(k, v);
            }
        });
        return DataNode.of(out);
    }

    /** The document as this connector serves it, or null when its {@code where} leaves it out (a deletion passes as it is). */
    public EntityDocument apply(EntityDocument d) {
        if (d == null || none()) {
            return d;
        }
        if (d.deleted() || d.data() == null) {
            return d;
        }
        if (!admits(d.data())) {
            return null;
        }
        DataNode c = canonical(d.data());
        return c == d.data() ? d : new EntityDocument(d.ref(), c, d.provenance(), d.deleted());
    }

    static Object cast(Object v, String cast) {
        if (cast == null || v == null) {
            return v;
        }
        String s = String.valueOf(v).strip();
        try {
            return switch (cast) {
                case "text" -> s;
                case "number" -> v instanceof Number n ? n.doubleValue() : s.isEmpty() ? null : Double.parseDouble(s.replace(",", ""));
                case "integer" -> v instanceof Number n ? n.longValue() : s.isEmpty() ? null : Long.parseLong(s.replace(",", "").replaceAll("\\.0+$", ""));
                case "boolean" -> v instanceof Boolean b ? b : List.of("true", "y", "yes", "1").contains(s.toLowerCase(Locale.ROOT));
                case "date" -> date(s);
                default -> v;
            };
        } catch (RuntimeException e) {
            return v;                                          // a value the cast cannot read stays as stored: never a lost row
        }
    }

    private static String date(String s) {
        if (s.matches("\\d{8}")) {
            return LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString();
        }
        if (s.length() >= 10 && s.charAt(4) == '-') {
            return LocalDate.parse(s.substring(0, 10)).toString();
        }
        return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)).toString();
    }

    /** What Admin shows of the shape. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!where.all()) {
            m.put("where", where.text());
            m.put("pushdown", where.pushdown().stream().map(f -> f.path() + " " + f.op() + " " + f.values()).toList());
        }
        if (!set.isEmpty()) {
            m.put("set", set);
        }
        if (!map.isEmpty()) {
            m.put("map", mapFile);
            m.put("fields", map.size());
        }
        system().ifPresent(s -> m.put("sourceSystem", s));
        return m;
    }
}
