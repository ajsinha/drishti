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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
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
import com.ash.drishti.sutra.SutraMatcher;
import com.ash.drishti.sutra.SutraRegistry;
import com.ash.drishti.sutra.el.ElCompiler;
import com.ash.drishti.sutra.el.EvalContext;
import com.ash.drishti.sutra.el.Link;
import com.ash.drishti.sutra.el.Values;
import com.ash.drishti.sutra.format.Formats;
import com.ash.drishti.sutra.model.Panel;
import com.ash.drishti.sutra.model.PanelKind;
import com.ash.drishti.sutra.model.StripItem;
import com.ash.drishti.sutra.model.Sutra;
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
    record LayoutKey(String sutra, String kind, Fingerprint fingerprint) {}

    record GenKey(EntityRef ref, long generation) {}

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

    public ViewPipeline(SourceRouter router, SutraMatcher matcher, SutraRegistry registry, LayoutMerger merger,
            ShapeFingerprinter fingerprinter, ReferenceCatalog catalog, GraphProperties graph, Binder binder, ElCompiler el,
            Formats formats, Mnemonics mnemonics, ExecutorService bindPool, EngineProperties props) {
        this.router = router;
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

    public ViewModel view(EntityRef ref) {
        long t0 = System.nanoTime();
        EntityDocument doc = fetch(ref);
        return build(doc, t0, System.nanoTime());
    }

    /** Builds a view from a document already in hand (live updates re-enter here). */
    public ViewModel build(EntityDocument doc, long t0, long tFetched) {
        EntityRef ref = doc.ref();
        Optional<Sutra> sutra = matcher.match(ref.kind(), doc.data());
        Fingerprint fp = fingerprints.get(new GenKey(ref, doc.provenance().generation()), k -> fingerprinter.fingerprint(doc.data()));
        EffectiveLayout layout = layouts.get(new LayoutKey(sutra.map(Sutra::id).orElse("-"), ref.kind(), fp),
                k -> merger.merge(sutra, doc.data(), ref.kind()));
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
        Map<EntityRef, EntityDocument> linked = wanted.isEmpty() ? Map.of() : router.fetchAll(wanted, graph.linkBudget());
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
            strip.add(binder.cell(i.label(), el.compile(i.bind()).eval(eval), i.fmt(), i.tone(), i.emphasis(), binder.pathOf(i.bind())));
        }
        ViewModel.TitleView title = title(s, eval);
        long tBind = System.nanoTime();

        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("fetch", ms(tFetched - t0));
        timings.put("layout", ms(tLayout - tFetched));
        timings.put("links", ms(tLinks - tLayout));
        timings.put("bind", ms(tBind - tLinks));
        timings.put("total", ms(tBind - t0));
        var pv = doc.provenance();
        return new ViewModel(new ViewModel.Ref(ref.kind(), ref.id()), mnemonics.codeFor(ref.kind()), title, strip, panels,
                keys(s, panels, eval), new ViewModel.Provenance(layout.label(), fp.shortForm(), pv.source(), pv.generation(),
                        pv.fetchedAt().toString(), pv.live()), timings);
    }

    private EntityDocument fetch(EntityRef ref) {
        try {
            return router.fetch(ref).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof DrishtiException d) {
                throw d;
            }
            throw new DrishtiException(ErrorCode.VIEW_FAILED, "fetching " + ref, e.getCause());
        }
    }

    private ViewModel.TitleView title(Sutra s, EvalContext eval) {
        var t = s.title();
        String id = Values.text(el.compile(t.id()).eval(eval));
        Cell with = null;
        if (t.with() != null) {
            Object w = Values.simplify(el.compile(t.with()).eval(eval));
            with = w == null ? null : binder.cell(null, w, null, null, false, null);
        }
        String pill = t.pill() == null ? null : el.template(t.pill()).render(eval);
        return new ViewModel.TitleView(pill, id, with);
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
        int dot = t.indexOf(" · ");
        return dot > 0 ? t.substring(0, dot) : t.length() > 18 ? t.substring(0, 18) : t;
    }

    private static String humanize(String kind) {
        String s = kind.replace('-', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    /** Layout cache hit ratio, for metrics. */
    public double layoutHitRate() {
        return layouts.stats().hitRate();
    }
}
