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
package com.ash.drishti.engine;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.Fingerprint;
import com.ash.drishti.common.ShapeFingerprinter;
import com.ash.drishti.engine.bind.BindContext;
import com.ash.drishti.engine.bind.Binder;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.KeyView;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.LinkRef;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.EffectiveLayout;
import com.ash.drishti.inference.LayoutMerger;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Link;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * The seven-stage view pipeline (ARCHITECTURE §4): command → fetch → classify → fingerprint → layout →
 * link → bind. Layouts are cached per (Sutra version, kind, shape), so the cold cost of inference is
 * paid once per shape; linked entities are fetched in parallel within a budget; panels bind in parallel.
 * Thread-safe.
 */
public final class ViewPipeline {

    /** Cache key for effective layouts. */
    record LayoutKey(Object sutra, String kind, Fingerprint fingerprint) {}

    /**
     * A Sutra compared by identity: each (re)load parses a new object, so a layout computed from an older parse can
     * never be served for a newer one, even when a build that started before a same-version edit finishes after it.
     * Identity, not the record's deep equality, so the key stays cheap to hash.
     */
    private record SutraIdentity(Sutra sutra) {
        @Override
        @SuppressWarnings("ReferenceEquality")   // identity on purpose: the same compiled Sutra object, not an equal one
        public boolean equals(Object o) {
            return o instanceof SutraIdentity other && other.sutra == sutra;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(sutra);
        }
    }

    record GenKey(EntityRef ref, long generation, java.time.LocalDate businessDate) {}

    private final SourceRouter router;
    private final SutraMatcher matcher;
    private final LayoutMerger merger;
    private final ShapeFingerprinter fingerprinter;
    private final ReferenceCatalog catalog;
    private final GraphProperties graph;
    private final Binder binder;
    private final ElCompiler el;
    private final Formats formats;
    private final Mnemonics mnemonics;
    private final ExecutorService bindPool;
    private final Cache<LayoutKey, EffectiveLayout> layouts;
    private final Cache<GenKey, Fingerprint> fingerprints;
    private final BusinessDates dates;

    public ViewPipeline(SourceRouter router, SutraMatcher matcher, SutraRegistry registry, LayoutMerger merger,
            ShapeFingerprinter fingerprinter, ReferenceCatalog catalog, GraphProperties graph, Binder binder, ElCompiler el,
            Formats formats, Mnemonics mnemonics, ExecutorService bindPool, EngineProperties props, BusinessDates dates) {
        this.router = router;
        this.dates = dates;
        this.matcher = matcher;
        this.merger = merger;
        this.fingerprinter = fingerprinter;
        this.catalog = catalog;
        this.graph = graph;
        this.binder = binder;
        this.el = el;
        this.formats = formats;
        this.mnemonics = mnemonics;
        this.bindPool = bindPool;
        this.layouts = Caffeine.newBuilder().maximumSize(props.layoutCacheSize()).recordStats().build();
        this.fingerprints = Caffeine.newBuilder().maximumSize(props.fingerprintCacheSize()).build();
        registry.onChange(changed -> layouts.invalidateAll());
    }

    /** A view built with an unsaved Sutra (Sutra Studio). Bypasses the layout cache. */
    public ViewModel preview(Sutra sutra, EntityRef ref) {
        return preview(sutra, ref, AsOf.LATEST);
    }

    public ViewModel preview(Sutra sutra, EntityRef ref, AsOf asOf) {
        long t0 = System.nanoTime();
        EntityDocument doc = fetch(ref, asOf);
        return build(doc, t0, System.nanoTime(), Optional.of(sutra), false, asOf);
    }

    /** A view of a document supplied by the caller (Studio sample JSON), with a given or matched Sutra. */
    public ViewModel preview(Optional<Sutra> sutra, EntityDocument doc) {
        long t0 = System.nanoTime();
        return build(doc, t0, t0, sutra.isPresent() ? sutra : matcher.match(doc.ref().kind(), doc.data()), false, AsOf.LATEST);
    }

    /** The layout inference alone would give {@code ref}, in Sutra form (Studio "start from inference"). */
    public Sutra inferred(EntityRef ref) {
        return inferred(ref, AsOf.LATEST);
    }

    public Sutra inferred(EntityRef ref, AsOf asOf) {
        EntityDocument doc = fetch(ref, asOf);
        return merger.merge(Optional.empty(), doc.data(), ref.kind()).sutra();
    }

    /** The layout inference alone would give a supplied document. */
    public Sutra inferred(EntityDocument doc) {
        return merger.merge(Optional.empty(), doc.data(), doc.ref().kind()).sutra();
    }

    public ViewModel view(EntityRef ref) {
        return view(ref, AsOf.LATEST);
    }

    /** The view of {@code ref} as of a business date: the entity and every linked entity are read for that date. */
    public ViewModel view(EntityRef ref, AsOf asOf) {
        long t0 = System.nanoTime();
        EntityDocument doc = fetch(ref, asOf);
        return build(doc, t0, System.nanoTime(), matcher.match(doc.ref().kind(), doc.data()), true, asOf);
    }

    /** Builds a view from a document already in hand (live updates re-enter here; live is always current). */
    public ViewModel build(EntityDocument doc, long t0, long tFetched) {
        return build(doc, t0, tFetched, matcher.match(doc.ref().kind(), doc.data()), true, AsOf.LATEST);
    }

    private ViewModel build(EntityDocument doc, long t0, long tFetched, Optional<Sutra> sutra, boolean cached, AsOf asOf) {
        EntityRef ref = doc.ref();
        boolean current = dates.isCurrent(asOf);
        Fingerprint fp = fingerprints.get(new GenKey(ref, doc.provenance().generation(), doc.provenance().businessDate()),
                k -> fingerprinter.fingerprint(doc.data()));
        EffectiveLayout layout = cached
                ? layouts.get(new LayoutKey(sutra.<Object>map(SutraIdentity::new).orElse("-"), ref.kind(), fp), k -> merger.merge(sutra, doc.data(), ref.kind()))
                : merger.merge(sutra, doc.data(), ref.kind());
        long tLayout = System.nanoTime();

        EvalContext eval = EvalContext.of(doc.data(), formats);
        List<LinkRef> links = catalog.discover(doc.data(), ref);
        Set<EntityRef> wanted = new LinkedHashSet<>();
        links.forEach(l -> wanted.add(l.target()));
        for (Panel p : layout.sutra().panels()) {
            if (p.kind() == PanelKind.LINE) {
                binder.chartSource(p, eval).ifPresent(wanted::add);
            }
        }
        Map<EntityRef, EntityDocument> linked = wanted.isEmpty() ? Map.of() : router.fetchAll(wanted, graph.linkBudget(), asOf.businessDate() == null ? dates.resolve(asOf) : asOf);
        Set<EntityRef> pending = new HashSet<>(wanted);
        pending.removeAll(linked.keySet());
        long tLinks = System.nanoTime();

        BindContext ctx = new BindContext(doc, layout, fp, eval, links, linked, pending);
        List<CompletableFuture<PanelView>> futures = new ArrayList<>();
        for (Panel p : layout.sutra().panels()) {
            futures.add(CompletableFuture.supplyAsync(() -> binder.bind(p, ctx), bindPool));
        }
        List<PanelView> panels = futures.stream().map(CompletableFuture::join).toList();
        Sutra s = layout.sutra();
        List<Cell> strip = new ArrayList<>();
        for (StripItem i : s.strip()) {
            Object v;
            try {
                v = el.compile(i.bind()).eval(eval);
            } catch (RuntimeException e) {
                v = null;   // a field the document lacks, or of the wrong type, shows as a dash
            }
            strip.add(binder.cell(i.label(), v, i.fmt(), i.tone(), i.emphasis(), binder.pathOf(i.bind())));
        }
        ViewModel.TitleView title = title(s, eval, ref);
        long tBind = System.nanoTime();

        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("fetch", ms(tFetched - t0));
        timings.put("layout", ms(tLayout - tFetched));
        timings.put("links", ms(tLinks - tLayout));
        timings.put("bind", ms(tBind - tLinks));
        timings.put("total", ms(tBind - t0));
        var pv = doc.provenance();
        var fresh = router.freshness(ref.kind(), pv.source());
        return new ViewModel(new ViewModel.Ref(ref.kind(), ref.id()), mnemonics.codeFor(ref.kind()), title, strip, panels,
                keys(s, panels, eval), new ViewModel.Provenance(layout.label(), fp.shortForm(), pv.source(), pv.generation(),
                        pv.fetchedAt().toString(), (pv.live() || router.pushes(ref)) && current,   // a ticks-only stream makes a stored entity live
                        pv.businessDate() == null ? null : pv.businessDate().toString(),
                        fresh.lastUpdate() == null ? null : fresh.lastUpdate().toString(),
                        fresh.staleAfter() == null ? null : fresh.staleAfter().toString(), fresh.stale() && current), timings);
    }

    private EntityDocument fetch(EntityRef ref, AsOf asOf) {
        try {
            return router.fetch(ref, asOf.businessDate() == null ? dates.resolve(asOf) : asOf).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof DrishtiException d) {
                throw d;
            }
            throw new DrishtiException(ErrorCode.VIEW_FAILED, "fetching " + ref, e.getCause());
        }
    }

    private ViewModel.TitleView title(Sutra s, EvalContext eval, EntityRef ref) {
        var t = s.title();
        String id = soft(() -> Values.text(el.compile(t.id()).eval(eval)));
        Cell with = null;
        if (t.with() != null) {
            Object w = soft(() -> Values.simplify(el.compile(t.with()).eval(eval)));
            with = w == null ? null : binder.cell(null, w, null, null, false, null);
        }
        String pill = t.pill() == null ? null : soft(() -> el.template(t.pill()).render(eval));
        return new ViewModel.TitleView(pill, id == null || id.isBlank() ? ref.id() : id, with);
    }

    /** Title parts are best effort: an imperfect document gets a plainer title, never a failed view. */
    private static <T> T soft(java.util.function.Supplier<T> f) {
        try {
            return f.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<KeyView> keys(Sutra s, List<PanelView> panels, EvalContext eval) {
        List<KeyView> keys = new ArrayList<>();
        for (PanelView p : panels) {
            if (p.key() != null) {
                keys.add(new KeyView(p.key(), shortTitle(p.title()), "panel", p.id(), null));
            }
        }
        s.keys().forEach((key, action) -> {
            switch (action) {
                case "raw" -> keys.add(new KeyView(key, "Raw JSON", "raw", null, null));
                case "impact" -> keys.add(new KeyView(key, "Impact", "impact", null, null));
                default -> {
                    if (action.startsWith("link(")) {
                        Object v = Values.simplify(el.compile(action).eval(eval));
                        if (v instanceof Link l) {
                            var lv = binder.linkView(l);
                            if (lv != null) {
                                keys.add(new KeyView(key, humanize(lv.kind()), "link", null, lv));
                            }
                        }
                    } else {
                        keys.add(new KeyView(key, action, "panel", action, null));
                    }
                }
            }
        });
        keys.sort(Comparator.comparingInt(k -> Integer.parseInt(k.key().substring(1))));
        return keys;
    }

    private static String shortTitle(String t) {
        if (t == null) {
            return "";
        }
        String s = t;
        for (String cut : new String[] {" · ", " ("}) {
            int i = s.indexOf(cut);
            if (i > 0) {
                s = s.substring(0, i);
            }
        }
        String[] words = s.split(" ");
        return words.length > 2 ? words[0] + " " + words[1] : s;
    }

    private static String humanize(String kind) {
        String s = kind.replace('-', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    /** The engine's caches, for the admin's cache page. */
    public Map<String, Object> cacheStats() {
        var s = layouts.stats();
        return Map.of("layouts", layouts.estimatedSize(), "layoutHitRate", Math.round(s.hitRate() * 1000) / 1000.0,
                "fingerprints", fingerprints.estimatedSize());
    }

    /** Drops cached layouts and fingerprints; the next views recompute them. */
    public void purgeCaches() {
        layouts.invalidateAll();
        fingerprints.invalidateAll();
    }

    /** Layout cache hit ratio, for metrics. */
    public double layoutHitRate() {
        return layouts.stats().hitRate();
    }
}
