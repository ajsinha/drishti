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
package com.ash.drishti.server.datamap;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.packs.Pack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The data map (DATAMAP, docs/guides/USER_GUIDE.md "The data map"), inferred and never drawn by hand: the kinds of the loaded packs and the
 * joins their data contracts declare, each join verified against the data. Nothing here is configured: a pack that is loaded appears with
 * its kinds and joins.
 *
 * <ul>
 *   <li>Kinds: each pack's {@code kinds:}, titled and described by its contract ({@code contracts/<kind>.contract.yaml}); a contract of
 *       {@code class: computed} marks a measure computed on demand (VaR, exposure), never stored, so it is neither counted nor read.</li>
 *   <li>Joins: a contract's {@code links:} ({@code field} → {@code kind}), its fields' {@code link:}, and its fields that a loaded pack's
 *       {@code graph.fields} names (a field scoped to its pack only there). A kind's own key naming itself is no join.</li>
 *   <li>Counts and coverage, from the {@link Lake}: how many entities the kind holds on the date, and for each join how many rows carry the
 *       field and how many of the ids they hold name an entity of the target kind that exists ({@code COUNTERPARTY → counterparty: 15,000
 *       of 15,000}). Ids are read as columns where the source keeps them so, else from the documents, at most {@code maxRows} rows.</li>
 * </ul>
 * Thread-safe once built; {@link Result} is filled while it is being built and read at any time.
 */
public final class DataMap {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** A kind on the map: its pack, mnemonic and label, contract title, description and grain, its contract class, whether it is computed. */
    public record Kind(String kind, String pack, String code, String label, String title, String description, String grain, String dataClass,
            boolean computed, List<String> key) {}

    /** A declared join: rows of {@code from} carry ids of {@code to} in {@code field} (a dotted path, {@code []} for each element of a list). */
    public record Join(String from, String field, String to, String declaredBy) {
        String id() {
            return from + "|" + field;
        }

        boolean nested() {
            return field.contains(".") || field.contains("[");
        }
    }

    /** The map's shape: the packs, kinds and joins, from the pack files alone. */
    public record Structure(List<Pack> packs, List<Kind> kinds, List<Join> joins) {}

    /** How many entities a kind holds: null when the lake could not say ({@code failed} says why); {@code more}: at least that many. */
    public record Count(Integer count, boolean more, String example, String failed) {}

    /**
     * A join checked against the data: {@code state} {@code checked}, {@code computed} (an end is computed on demand: nothing to read),
     * {@code unchecked} ({@code why}) or {@code pending}; {@code rows} read, {@code filled} rows that carry the field, {@code values} ids
     * they hold, {@code matched} ids naming an entity that exists; {@code partial} when only part of the source or target was read.
     */
    public record Coverage(String state, String why, int rows, int filled, int values, int matched, boolean partial) {
        static Coverage of(String state, String why) {
            return new Coverage(state, why, 0, 0, 0, 0, false);
        }
    }

    /** The ids of a kind on the date ({@code more}: the listing stopped at its limit), an example id, and why the listing failed if it did. */
    public record Ids(Set<String> ids, boolean more, String example, String failed) {}

    /** Where the data is read: the server's sources ({@link DataMapService}), or a stand-in in tests. */
    public interface Lake {
        /** The kind's ids, at most {@code limit} (and {@code more} when there are others). */
        Ids ids(String kind, int limit);

        /** The fields the kind's source keeps as columns. */
        Set<String> columnar(String kind);

        /** For each row, the value of each field ({@code fields} are all columnar); empty when the source could not answer so. */
        Optional<List<Object[]>> columns(String kind, List<String> fields, int limit);

        /** Every document of the kind, until {@code sink} answers false; false when the kind cannot be read in one pass. */
        boolean documents(String kind, Predicate<DataNode> sink);

        /** Which of these ids name an entity of the kind that exists (read one by one). */
        Set<String> exists(String kind, List<String> ids);

        /** The documents of these entities, one by one, until {@code sink} answers false; false when none could be read. */
        boolean fetch(String kind, List<String> ids, Predicate<DataNode> sink);
    }

    /** What is known so far: filled while the map is built ({@code state} counting → checking → ready). */
    public static final class Result {
        public final Structure structure;
        public final String date;
        public final Map<String, Count> counts = new java.util.concurrent.ConcurrentHashMap<>();
        public final Map<String, Coverage> coverage = new java.util.concurrent.ConcurrentHashMap<>();
        public volatile String state = "counting";
        public volatile java.time.Instant built;

        public Result(Structure structure, String date) {
            this.structure = structure;
            this.date = date;
            structure.joins().forEach(j -> coverage.put(j.id(), Coverage.of("pending", null)));
        }
    }

    private DataMap() {}

    // ---- the shape, from the packs' files ----------------------------------------------------------------------------------------------

    /** The kinds and declared joins of these packs, in pack order; joins to kinds no loaded pack owns are left out. */
    public static Structure read(List<Pack> packs) {
        Map<String, GraphField> graph = graphFields(packs);
        List<Kind> kinds = new ArrayList<>();
        Map<String, JsonNode> contracts = new HashMap<>();
        for (Pack p : packs) {
            for (String kind : p.kinds()) {
                JsonNode c = contract(p.dir(), kind);
                contracts.put(kind, c);
                List<String> key = new ArrayList<>();
                c.path("key").forEach(k -> key.add(k.asText()));
                String code = mnemonic(p, kind, c);
                kinds.add(new Kind(kind, p.name(), code, label(p, code, c, kind), c.path("title").asText(kind), oneLine(c.path("description").asText("")),
                        oneLine(c.path("grain").asText("")), c.path("class").asText(""), "computed".equals(c.path("class").asText()), List.copyOf(key)));
            }
        }
        Set<String> known = new HashSet<>();
        kinds.forEach(k -> known.add(k.kind()));
        List<Join> joins = new ArrayList<>();
        for (Kind k : kinds) {
            Map<String, Join> mine = new LinkedHashMap<>();
            JsonNode c = contracts.get(k.kind());
            for (JsonNode l : c.path("links")) {
                add(mine, k, l.path("field").asText(""), l.path("kind").asText(""), "contract links");
            }
            for (JsonNode f : c.path("fields")) {
                add(mine, k, f.path("name").asText(""), f.path("link").asText(""), "contract field");
            }
            c.path("nested").fields().forEachRemaining(e -> e.getValue().forEach(f ->
                    add(mine, k, e.getKey() + "." + f.path("name").asText(""), f.path("link").asText(""), "contract field")));
            for (JsonNode f : c.path("fields")) {
                GraphField g = graph.get(f.path("name").asText(""));
                if (g != null && (g.pack() == null || g.pack().equals(k.pack()))) {
                    add(mine, k, f.path("name").asText(""), g.kind(), "graph field of " + g.declaredBy());
                }
            }
            mine.values().stream().filter(j -> known.contains(j.to())).forEach(joins::add);
        }
        return new Structure(List.copyOf(packs), List.copyOf(kinds), List.copyOf(joins));
    }

    private static void add(Map<String, Join> joins, Kind k, String field, String to, String by) {
        if (field.isBlank() || to.isBlank() || (to.equals(k.kind()) && k.key().contains(field))) {
            return;                                              // no field, no target, or the kind's own key naming itself
        }
        joins.putIfAbsent(field, new Join(k.kind(), field, to, by));
    }

    private record GraphField(String kind, String pack, String declaredBy) {}

    /** {@code graph.fields} of every pack: field → kind; a field with {@code scope: pack} is a link only in that pack's kinds. */
    @SuppressWarnings("unchecked")
    private static Map<String, GraphField> graphFields(List<Pack> packs) {
        Map<String, GraphField> out = new HashMap<>();
        for (Pack p : packs) {
            if (p.manifest().get("graph") instanceof Map<?, ?> g && g.get("fields") instanceof Map<?, ?> fields) {
                fields.forEach((name, def) -> {
                    if (def instanceof Map<?, ?> d && d.get("kind") != null) {
                        String scope = d.get("scope") == null ? null : String.valueOf(d.get("scope"));
                        out.putIfAbsent(String.valueOf(name), new GraphField(String.valueOf(d.get("kind")), "pack".equals(scope) ? p.name() : null, p.name()));
                    }
                });
            }
        }
        return out;
    }

    private static String mnemonic(Pack p, String kind, JsonNode contract) {
        if (p.manifest().get("mnemonics") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> d && kind.equals(String.valueOf(d.get("kind")))) {
                    return String.valueOf(e.getKey()).toUpperCase(java.util.Locale.ROOT);
                }
            }
        }
        return contract.path("mnemonic").asText("");
    }

    private static String label(Pack p, String code, JsonNode contract, String kind) {
        if (p.manifest().get("mnemonics") instanceof Map<?, ?> m && m.get(code) instanceof Map<?, ?> d && d.get("label") != null) {
            return String.valueOf(d.get("label"));
        }
        return contract.path("title").asText(kind);
    }

    private static JsonNode contract(Path dir, String kind) {
        Path f = dir == null ? null : dir.resolve("contracts").resolve(kind + ".contract.yaml");
        if (f == null || !Files.isRegularFile(f)) {
            return YAML.createObjectNode();
        }
        try {
            JsonNode n = YAML.readTree(f.toFile());
            return n == null ? YAML.createObjectNode() : n;
        } catch (IOException e) {
            return YAML.createObjectNode();
        }
    }

    private static String oneLine(String text) {
        return String.join(" ", text.trim().split("\\s+"));
    }

    // ---- counts and coverage, from the data ------------------------------------------------------------------------------------------

    /**
     * Counts every stored kind, then checks every join, filling {@code out} as it goes. {@code run} runs the given tasks (in parallel
     * in the server, one after the other in tests) and returns when they are done.
     */
    public static void build(Lake lake, int maxRows, Result out, java.util.function.Consumer<List<Runnable>> run) {
        Structure s = out.structure;
        Map<String, Kind> byKind = new HashMap<>();
        s.kinds().forEach(k -> byKind.put(k.kind(), k));
        Set<String> targets = new HashSet<>();
        s.joins().forEach(j -> targets.add(j.to()));
        Map<String, Ids> ids = new java.util.concurrent.ConcurrentHashMap<>();
        List<Runnable> counting = new ArrayList<>();
        for (Kind k : s.kinds()) {
            if (k.computed()) {
                continue;
            }
            counting.add(() -> {
                Ids got;
                try {
                    got = lake.ids(k.kind(), maxRows);
                } catch (RuntimeException e) {
                    got = new Ids(Set.of(), false, null, String.valueOf(e.getMessage()));
                }
                boolean answered = got.failed() == null || !got.ids().isEmpty();
                out.counts.put(k.kind(), new Count(answered ? got.ids().size() : null, got.more(), got.example(), got.failed()));
                if (targets.contains(k.kind())) {
                    ids.put(k.kind(), got);
                }
            });
        }
        run.accept(counting);
        out.state = "checking";
        Map<String, List<Join>> bySource = new LinkedHashMap<>();
        for (Join j : s.joins()) {
            Kind from = byKind.get(j.from());
            Kind to = byKind.get(j.to());
            if (from.computed() || to.computed()) {
                out.coverage.put(j.id(), Coverage.of("computed", (from.computed() ? j.from() : j.to()) + " is computed on demand, never stored"));
            } else {
                bySource.computeIfAbsent(j.from(), x -> new ArrayList<>()).add(j);
            }
        }
        List<Runnable> checking = new ArrayList<>();
        bySource.forEach((kind, joins) -> checking.add(() -> {
            try {
                check(lake, kind, joins, ids, maxRows, out);
            } catch (RuntimeException e) {
                joins.forEach(j -> out.coverage.put(j.id(), Coverage.of("unchecked", "the data could not be read: " + e.getMessage())));
            }
        }));
        run.accept(checking);
        out.built = java.time.Instant.now();
        out.state = "ready";
    }

    /** One tally per join of a kind: rows read, rows carrying the field, ids, ids that name an existing entity. */
    private static final class Tally {
        int rows;
        int filled;
        int values;
        int matched;
        /** Ids the target's listing does not hold, with how often each occurs: asked of the target one by one before they count as missing. */
        final Map<String, Integer> missing = new LinkedHashMap<>();
        boolean overflow;
    }

    /** The most distinct ids of one join asked of the target one by one (a source that answers by id but does not list everything). */
    static final int PROBE = 200;

    private static void check(Lake lake, String kind, List<Join> joins, Map<String, Ids> ids, int maxRows, Result out) {
        Map<Join, Tally> tallies = new LinkedHashMap<>();
        joins.forEach(j -> tallies.put(j, new Tally()));
        Set<String> columnar = lake.columnar(kind);
        List<Join> asColumns = joins.stream().filter(j -> !j.nested() && columnar.contains(j.field())).toList();
        boolean whole = true;
        List<Join> rest = new ArrayList<>(joins);
        if (!asColumns.isEmpty()) {
            Optional<List<Object[]>> rows = lake.columns(kind, asColumns.stream().map(Join::field).toList(), maxRows);
            if (rows.isPresent()) {
                for (Object[] row : rows.get()) {
                    for (int i = 0; i < asColumns.size(); i++) {
                        List<String> got = new ArrayList<>();
                        value(row[i], got);
                        tally(tallies.get(asColumns.get(i)), got, ids.get(asColumns.get(i).to()));
                    }
                }
                whole = rows.get().size() < maxRows;
                rest.removeAll(asColumns);
            }
        }
        boolean[] cut = {false};
        if (!rest.isEmpty()) {
            int[] read = {0};
            Predicate<DataNode> sink = doc -> {
                if (read[0] >= maxRows) {
                    cut[0] = true;
                    return false;
                }
                read[0]++;
                for (Join j : rest) {
                    List<String> got = new ArrayList<>();
                    walk(doc, j.field().split("\\."), 0, got);
                    tally(tallies.get(j), got, ids.get(j.to()));
                }
                return true;
            };
            boolean readable = lake.documents(kind, sink);
            if (!readable) {                         // a source that cannot read the kind in one pass: its entities one by one, by id
                Ids own = ids.containsKey(kind) ? ids.get(kind) : lake.ids(kind, maxRows);
                readable = !own.ids().isEmpty() && lake.fetch(kind, List.copyOf(own.ids()), sink);
                cut[0] = cut[0] || own.more();
            }
            if (!readable) {
                rest.forEach(j -> out.coverage.put(j.id(), Coverage.of("unchecked", "the documents of " + kind + " could not be read")));
                rest.forEach(tallies::remove);
            }
        }
        boolean wholeSource = whole && !cut[0];
        tallies.forEach((j, t) -> {
            Ids target = ids.get(j.to());
            if (target == null || (target.ids().isEmpty() && target.failed() != null)) {
                out.coverage.put(j.id(), Coverage.of("unchecked", "the ids of " + j.to() + " could not be listed"
                        + (target == null || target.failed() == null ? "" : ": " + target.failed())));
                return;
            }
            if (!t.missing.isEmpty()) {              // ids the listing does not hold may still open: ask the target for each
                Set<String> found = lake.exists(j.to(), List.copyOf(t.missing.keySet()));
                found.forEach(id -> t.matched += t.missing.getOrDefault(id, 0));
            }
            out.coverage.put(j.id(), new Coverage("checked", null, t.rows, t.filled, t.values, t.matched, !wholeSource || t.overflow));
        });
    }

    private static void tally(Tally t, List<String> got, Ids target) {
        t.rows++;
        if (got.isEmpty()) {
            return;
        }
        t.filled++;
        for (String v : got) {
            t.values++;
            if (target != null && target.ids().contains(v)) {
                t.matched++;
            } else if (t.missing.containsKey(v) || t.missing.size() < PROBE) {
                t.missing.merge(v, 1, Integer::sum);
            } else {
                t.overflow = true;
            }
        }
    }

    /** The ids at a dotted path ({@code LEGS[].INDEX.GROUP}): a text, each text of a list, an object's {@code id}; masked values are none. */
    static void walk(DataNode node, String[] path, int at, List<String> out) {
        if (node == null || node.isNull() || node.isMasked()) {
            return;
        }
        if (at == path.length) {
            if (node instanceof DataNode.Arr a) {
                for (int i = 0; i < a.size(); i++) {
                    walk(a.get(i), path, at, out);
                }
            } else if (node instanceof DataNode.Obj) {
                walk(node.get("id"), path, at, out);
            } else {
                value(node.asText(), out);
            }
            return;
        }
        String seg = path[at];
        boolean each = seg.endsWith("[]");
        DataNode next = node.get(each ? seg.substring(0, seg.length() - 2) : seg);
        if (each && next instanceof DataNode.Arr a) {
            for (int i = 0; i < a.size(); i++) {
                walk(a.get(i), path, at + 1, out);
            }
        } else {
            walk(next, path, at + 1, out);
        }
    }

    private static void value(Object v, List<String> out) {
        if (v == null) {
            return;
        }
        String s = v instanceof Double d && d == Math.rint(d) && !d.isInfinite() ? String.valueOf(d.longValue()) : String.valueOf(v).strip();
        if (!s.isEmpty() && !DataNode.MASK.equals(s) && !"NaN".equals(s)) {
            out.add(s);
        }
    }
}
