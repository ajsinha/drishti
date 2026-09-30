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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * F8 Impact: what depends on an entity. Level 1 is every entity that references it (reverse lookups across
 * all known kinds, run in parallel). Level 2 follows the configured fields upward from those dependents
 * ({@code nettingSet}, {@code creditLimit}) to what they roll into. Each group carries a count and the sum
 * of the kind's measure; entities the caller may not open are counted as hidden, never shown.
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
     * @param total formatted sum of the measure over visible items, or null
     */
    public record Group(int level, String kind, String mnemonic, List<Item> items, int hidden, String total) {}

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
        Map<EntityRef, EntityDocument> docs = router.fetchAll(direct, Duration.ofSeconds(2), asOf);

        Map<EntityRef, String> rolled = new LinkedHashMap<>();
        for (EntityDocument d : docs.values()) {
            for (String field : config.follow()) {
                String id = d.data().get(field).asText();
                if (!id.isEmpty()) {
                    catalog.discover(d.data(), d.ref()).stream().filter(l -> l.path().equals("$." + field))
                            .forEach(l -> rolled.putIfAbsent(l.target(), field));
                }
            }
        }
        rolled.keySet().removeAll(direct);
        rolled.remove(target);
        Map<EntityRef, EntityDocument> rolledDocs = router.fetchAll(rolled.keySet(), Duration.ofSeconds(2), asOf);

        List<Group> groups = new ArrayList<>();
        groups.addAll(group(1, direct, docs, Map.of(), mayOpen));
        groups.addAll(group(2, rolled.keySet(), rolledDocs, rolled, mayOpen));
        return new Impact(target, groups, Math.round((System.nanoTime() - t0) / 10_000.0) / 100.0);
    }

    private List<Group> group(int level, Set<EntityRef> refs, Map<EntityRef, EntityDocument> docs, Map<EntityRef, String> via,
            Predicate<String> mayOpen) {
        Map<String, List<EntityRef>> byKind = new LinkedHashMap<>();
        refs.forEach(r -> byKind.computeIfAbsent(r.kind(), k -> new ArrayList<>()).add(r));
        List<Group> out = new ArrayList<>();
        byKind.forEach((kind, list) -> {
            if (!mayOpen.test(kind)) {
                out.add(new Group(level, kind, mnemonics.codeFor(kind), List.of(), list.size(), null));
                return;
            }
            String measure = config.measures().get(kind);
            String fmt = config.formats().get(kind);
            double sum = 0;
            boolean any = false;
            List<Item> items = new ArrayList<>();
            for (EntityRef r : list.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList()) {
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
                items.add(new Item(r, via.get(r), text));
            }
            out.add(new Group(level, kind, mnemonics.codeFor(kind), items, 0, any ? formats.format(fmt, Values.normalise(sum)) : null));
        });
        return out;
    }
}
