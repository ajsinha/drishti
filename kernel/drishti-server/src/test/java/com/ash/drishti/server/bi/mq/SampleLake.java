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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.SourceGateway;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A small generated lake for every kind the shipped sample datasets read: for each kind, a few entities per business date whose documents hold every path
 * the samples declare (arrays for the {@code each} tables), with references that point at real entities of the referenced kind ({@code desk}, {@code book},
 * {@code counterparty.id}, {@code nettingSet}), so joins match. Deterministic; tiny on purpose (the 10K-trade lake is the drill's).
 */
final class SampleLake implements SourceGateway {

    static final LocalDate DAY1 = LocalDate.of(2026, 10, 8);
    static final LocalDate DAY2 = LocalDate.of(2026, 10, 9);
    private static final int TRADES = 40;
    private static final int OTHERS = 8;

    private record Path(String path, String type) {}

    private final Map<String, Set<Path>> paths = new LinkedHashMap<>();
    private final Map<String, Map<String, Set<Path>>> arrays = new LinkedHashMap<>();
    private final Map<String, List<String>> ids = new LinkedHashMap<>();

    SampleLake(Collection<Dataset> datasets) {
        for (Dataset d : datasets) {
            for (Dataset.Table t : d.tables().values()) {
                if (t.kind() == null || t.kind().isBlank() || Dataset.FRAME.equals(t.kind()) || t.isHistory()) {
                    continue;
                }
                Set<Path> into = t.each() == null ? paths.computeIfAbsent(t.kind(), k -> new LinkedHashSet<>())
                        : arrays.computeIfAbsent(t.kind(), k -> new LinkedHashMap<>()).computeIfAbsent(t.each(), k -> new LinkedHashSet<>());
                paths.computeIfAbsent(t.kind(), k -> new LinkedHashSet<>());
                t.physical().forEach(c -> into.add(new Path(c.path(), c.type())));
            }
        }
        for (String kind : paths.keySet()) {
            int n = "trade".equals(kind) ? TRADES : OTHERS;
            List<String> list = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                list.add(kind.toUpperCase(Locale.ROOT) + "-" + i);
            }
            ids.put(kind, list);
        }
    }

    /** The id of the first entity of a kind ({@code DESK-0}): what a desk-head's profile names. */
    String first(String kind) {
        return ids.get(kind).get(0);
    }

    private String referenced(String path) {
        String p = path.endsWith(".id") ? path.substring(0, path.length() - 3) : path;
        String leaf = p.substring(p.lastIndexOf('.') + 1);
        String kebab = leaf.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        if (kebab.endsWith("-id")) {
            kebab = kebab.substring(0, kebab.length() - 3);
        }
        return ids.containsKey(kebab) ? kebab : null;
    }

    private Object value(Path p, int i, int shift) {
        String ref = referenced(p.path());
        if (ref != null && "string".equals(p.type())) {
            List<String> l = ids.get(ref);
            return l.get((i * 3 + 1) % l.size());
        }
        String leaf = p.path().substring(p.path().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (p.type()) {
            case "decimal" -> ((i * 37 + leaf.length() * 11) % 101 - 30) * 1000.5 + shift;
            case "integer" -> (long) ((i + leaf.length()) % 7);
            case "date" -> LocalDate.of(2026, 1 + i % 9, 1 + (i * 7) % 27).toString();
            case "timestamp" -> "2026-10-0" + (1 + i % 8) + "T10:00:00Z";
            case "boolean" -> i % 2 == 0;
            default -> leaf.contains("currency") || leaf.equals("ccy") ? List.of("USD", "EUR", "GBP").get(i % 3)
                    : leaf.equals("status") ? (i % 4 == 0 ? "Matured" : "Live") : leaf + "-" + (i % 4);
        };
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> doc, String path, Object v) {
        String[] parts = path.split("\\.");
        Map<String, Object> at = doc;
        for (int k = 0; k < parts.length - 1; k++) {
            at = (Map<String, Object>) at.computeIfAbsent(parts[k], x -> new LinkedHashMap<String, Object>());
        }
        at.put(parts[parts.length - 1], v);
    }

    private Map<String, Object> document(String kind, int i, LocalDate day) {
        int shift = day.equals(DAY2) ? 1 : 0;
        Map<String, Object> doc = new LinkedHashMap<>();
        for (Path p : paths.get(kind)) {
            put(doc, p.path(), value(p, i, shift));
        }
        for (Map.Entry<String, Set<Path>> a : arrays.getOrDefault(kind, Map.of()).entrySet()) {
            List<Map<String, Object>> elements = new ArrayList<>();
            for (int e = 0; e < 2; e++) {
                Map<String, Object> el = new LinkedHashMap<>();
                for (Path p : a.getValue()) {
                    put(el, p.path(), value(p, i + e, shift));
                }
                elements.add(el);
            }
            put(doc, a.getKey(), elements);
        }
        return doc;
    }

    private final SourcePlugin plugin = new SourcePlugin() {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("sample-lake", "1", Set.copyOf(paths.keySet()), new SourceCapabilities(false, false, false, true));
        }

        @Override
        public void start(SourceContext context) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }
    };

    @Override
    public Optional<SourcePlugin> plugin(String connector, String kind) {
        return paths.containsKey(kind) ? Optional.of(plugin) : Optional.empty();
    }

    @Override
    public List<EntityRef> ids(String kind, LocalDate date, int limit) {
        return ids.getOrDefault(kind, List.of()).stream().limit(limit).map(id -> EntityRef.of(kind, id)).toList();
    }

    @Override
    public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date) {
        LocalDate day = date == null ? DAY2 : date;
        Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
        for (EntityRef r : refs) {
            int i = ids.getOrDefault(r.kind(), List.of()).indexOf(r.id());
            if (i >= 0) {
                out.put(r, new EntityDocument(r, DataNode.of(document(r.kind(), i, day)), new Provenance("sample-lake", 1, java.time.Instant.now(), false)));
            }
        }
        return out;
    }

    @Override
    public LocalDate currentDate() {
        return DAY2;
    }
}
