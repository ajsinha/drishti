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
package com.ash.drishti.engine.impact;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * F8 Impact: what depends on an entity. Level 1 is every entity that references it (reverse lookups across
 * all known kinds, run in parallel). Level 2 follows the configured fields upward from those dependents
 * ({@code nettingSet}, {@code creditLimit}) to what they roll into. Each group carries a count and the sum
 * of the kind's measure; entities the caller may not open are counted as hidden, never shown.
 *
 * <p>Books of any size: a kind whose source keeps the measure as a column is summed over every dependent from columns
 * (and rolled up from the follow columns it keeps), without reading a document; otherwise at most {@link #MAX_DOCS}
 * documents per kind are read. At most {@link #LISTED} items are listed per group; {@code more} counts the rest.
 */
public final class ImpactService {

    /**
     * @param ref the entity
     * @param title its display text
     * @param via the field that ties it in (level 2) or null
     * @param measure its measure, formatted, or null
     */
    public record Item(EntityRef ref, String via, String measure) {}

    /**
     * @param level 1 for direct dependents, 2 for what they roll into
     * @param kind entity kind
     * @param mnemonic its mnemonic
     * @param items visible entities
     * @param hidden entities the caller may not open
     * @param total formatted sum of the measure over visible entities (all of them, listed or not), or null
     * @param more visible entities not listed (a group lists at most {@link #LISTED})
     */
    public record Group(int level, String kind, String mnemonic, List<Item> items, int hidden, String total, int more) {}

    static final int LISTED = 200;
    static final int MAX_DOCS = 2000;
    private static final Duration COLUMNS_BUDGET = Duration.ofSeconds(20);

    /**
     * @param ref the analysed entity
     * @param groups groups, level 1 first
     * @param elapsedMs how long the analysis took
     */
    public record Impact(EntityRef ref, List<Group> groups, double elapsedMs) {}

    private final SourceRouter router;
    private final Mnemonics mnemonics;
    private final ReferenceCatalog catalog;
    private final GraphProperties.Impact config;
    private final ElCompiler el;
    private final Formats formats;
    private final ExecutorService executor;

    public ImpactService(SourceRouter router, Mnemonics mnemonics, ReferenceCatalog catalog, GraphProperties graph, ElCompiler el,
            Formats formats, ExecutorService executor) {
        this.router = router;
        this.mnemonics = mnemonics;
        this.catalog = catalog;
        this.config = graph.impact();
        this.el = el;
        this.formats = formats;
        this.executor = executor;
    }

    public Impact analyse(EntityRef target, Predicate<String> mayOpen) {
        return analyse(target, mayOpen, com.ash.drishti.api.AsOf.LATEST);
    }

    /** What depends on {@code target} as of a business date. */
    public Impact analyse(EntityRef target, Predicate<String> mayOpen, com.ash.drishti.api.AsOf asOf) {
        long t0 = System.nanoTime();
        Set<String> kinds = new LinkedHashSet<>();
        mnemonics.all().values().forEach(m -> kinds.add(m.kind()));
        List<CompletableFuture<List<EntityRef>>> parts = new ArrayList<>();
        for (String kind : kinds) {
            parts.add(CompletableFuture.supplyAsync(() -> router.reverse(target, kind, asOf), executor));
        }
        Set<EntityRef> direct = new LinkedHashSet<>();
        parts.forEach(f -> direct.addAll(f.join()));
        direct.remove(target);

        Map<String, List<EntityRef>> byKind = new LinkedHashMap<>();
        direct.forEach(r -> byKind.computeIfAbsent(r.kind(), k -> new ArrayList<>()).add(r));
        List<Group> groups = new ArrayList<>();
        Map<EntityRef, String> rolled = new LinkedHashMap<>();
        byKind.forEach((kind, refs) -> {
            refs.sort((a, b) -> a.id().compareTo(b.id()));
            Optional<Group> fromColumns = mayOpen.test(kind) ? columns(1, kind, refs, rolled, asOf) : Optional.empty();
            if (fromColumns.isPresent()) {
                groups.add(fromColumns.get());
                return;
            }
            List<EntityRef> read = refs.subList(0, Math.min(refs.size(), MAX_DOCS));
            Map<EntityRef, EntityDocument> docs = mayOpen.test(kind) ? router.fetchAll(read, Duration.ofSeconds(2), asOf) : Map.of();
            for (EntityDocument d : docs.values()) {
                for (String field : config.follow()) {
                    if (!d.data().get(field).asText().isEmpty()) {
                        catalog.discover(d.data(), d.ref()).stream().filter(l -> l.path().equals("$." + field))
                                .forEach(l -> rolled.putIfAbsent(l.target(), field));
                    }
                }
            }
            groups.add(group(1, kind, refs, docs, Map.of(), mayOpen));
        });
        rolled.keySet().removeAll(direct);
        rolled.remove(target);
        Map<String, List<EntityRef>> rolledByKind = new LinkedHashMap<>();
        rolled.keySet().forEach(r -> rolledByKind.computeIfAbsent(r.kind(), k -> new ArrayList<>()).add(r));
        rolledByKind.forEach((kind, refs) -> {
            refs.sort((a, b) -> a.id().compareTo(b.id()));
            List<EntityRef> read = refs.subList(0, Math.min(refs.size(), MAX_DOCS));
            Map<EntityRef, EntityDocument> docs = mayOpen.test(kind) ? router.fetchAll(read, Duration.ofSeconds(2), asOf) : Map.of();
            groups.add(group(2, kind, refs, docs, rolled, mayOpen));
        });
        return new Impact(target, groups, Math.round((System.nanoTime() - t0) / 10_000.0) / 100.0);
    }

    /**
     * A group from columns: the measure summed over every dependent and the follow fields rolled up, when the kind's
     * source keeps them as columns; empty otherwise.
     */
    private Optional<Group> columns(int level, String kind, List<EntityRef> refs, Map<EntityRef, String> rolled,
            com.ash.drishti.api.AsOf asOf) {
        String measure = config.measures().get(kind);
        String path = measure != null && measure.matches("^\\$\\.[A-Za-z_][A-Za-z0-9_.]*$") ? measure.substring(2) : null;
        java.util.Set<String> have = router.columnar(kind);
        if (path == null || !have.contains(path) || refs.size() <= LISTED) {
            return Optional.empty();                            // a few dependents: their documents are cheap
        }
        List<String> follow = config.follow().stream().filter(have::contains).toList();
        List<String> wanted = new ArrayList<>(List.of(path));
        wanted.addAll(follow);
        Optional<com.ash.drishti.api.ColumnSet> got = router.columns(kind, wanted, asOf, COLUMNS_BUDGET);
        if (got.isEmpty()) {
            return Optional.empty();
        }
        com.ash.drishti.api.ColumnSet c = got.get();
        Map<String, Integer> row = new java.util.HashMap<>(c.size() * 2);
        for (int i = 0; i < c.size(); i++) {
            row.put(c.ids()[i], i);
        }
        double[] values = c.numbers().get(path);
        String fmt = config.formats().get(kind);
        double sum = 0;
        boolean any = false;
        java.util.Set<String> followed = new java.util.HashSet<>();
        List<Item> items = new ArrayList<>();
        for (EntityRef r : refs) {
            Integer i = row.get(r.id());
            double x = i == null || values == null ? Double.NaN : values[i];
            if (!Double.isNaN(x)) {
                sum += x;
                any = true;
            }
            if (items.size() < LISTED) {
                items.add(new Item(r, null, Double.isNaN(x) ? null : formats.format(fmt, Values.normalise(x))));
            }
            if (i != null) {
                for (String f : follow) {
                    Object v = c.value(f, i);
                    if (v != null && followed.add(f + "\u001f" + v)) {         // each linked id once, not once per trade
                        catalog.discover(DataNode.of(Map.of(f, v)), r).stream().filter(l -> l.path().equals("$." + f))
                                .forEach(l -> rolled.putIfAbsent(l.target(), f));
                    }
                }
            }
        }
        return Optional.of(new Group(level, kind, mnemonics.codeFor(kind), items, 0, any ? formats.format(fmt, Values.normalise(sum)) : null,
                refs.size() - items.size()));
    }

    private Group group(int level, String kind, List<EntityRef> refs, Map<EntityRef, EntityDocument> docs, Map<EntityRef, String> via,
            Predicate<String> mayOpen) {
        if (!mayOpen.test(kind)) {
            return new Group(level, kind, mnemonics.codeFor(kind), List.of(), refs.size(), null, 0);
        }
        String measure = config.measures().get(kind);
        String fmt = config.formats().get(kind);
        double sum = 0;
        boolean any = false;
        List<Item> items = new ArrayList<>();
        for (EntityRef r : refs) {
            String text = null;
            EntityDocument d = docs.get(r);
            if (measure != null && d != null) {
                Object v = el.compile(measure).eval(EvalContext.of(d.data(), formats));
                double x = Values.number(v);
                if (!Double.isNaN(x)) {
                    sum += x;
                    any = true;
                    text = formats.format(fmt, Values.normalise(x));
                }
            }
            if (items.size() < LISTED) {
                items.add(new Item(r, via.get(r), text));
            }
        }
        return new Group(level, kind, mnemonics.codeFor(kind), items, 0, any ? formats.format(fmt, Values.normalise(sum)) : null,
                refs.size() - items.size());
    }
}
