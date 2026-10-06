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
import com.ash.drishti.api.DataNode;
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
import com.ash.drishti.engine.source.SourceFailures;
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
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The seven-stage view pipeline (ARCHITECTURE §4): command → fetch → classify → fingerprint → layout →
 * link → bind. Layouts are cached per (Sutra version, kind, shape), so the cold cost of inference is
 * paid once per shape; linked entities are fetched in parallel within a budget; panels bind in parallel.
 *
 * <p>Field masks: every method that serves a caller takes the caller's redaction ({@code redact}, from the server's
 * entitlements). The Sutra and the layout are chosen from the document as stored (its shape, not its values); every
 * value is bound from the document and the linked documents as the caller may see them, so a masked field reads
 * {@link DataNode#MASK} wherever the view shows it or a value derived from it (strip, title, tables and their totals,
 * keys, links, panel records, live patches). The overloads without {@code redact} mask nothing.
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

    /** Callers that are not users (tests, internal jobs) may open everything; every user-facing path passes its own right. */
    private static final Predicate<String> ANY = k -> true;

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

    /** The zone business dates and "known at" times are read in (what the console's top bar shows times in). */
    public java.time.ZoneId businessZone() {
        return dates.zone();
    }

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
        return preview(sutra, ref, asOf, UnaryOperator.identity());
    }

    /** A view of a stored entity with an unsaved Sutra, as the caller may see it. */
    public ViewModel preview(Sutra sutra, EntityRef ref, AsOf asOf, UnaryOperator<DataNode> redact) {
        return preview(sutra, ref, asOf, redact, ANY);
    }

    /** As above, and a panel whose {@code source} is of a kind {@code mayOpen} refuses shows "no access" instead of that entity's data. */
    public ViewModel preview(Sutra sutra, EntityRef ref, AsOf asOf, UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        long t0 = System.nanoTime();
        EntityDocument doc = fetch(ref, asOf);
        return assemble(doc, t0, System.nanoTime(), Optional.of(sutra), false, asOf, redact, mayOpen).view();
    }

    /** A view of a document supplied by the caller (Studio sample JSON), with a given or matched Sutra. */
    public ViewModel preview(Optional<Sutra> sutra, EntityDocument doc) {
        return preview(sutra, doc, UnaryOperator.identity());
    }

    /** A view of a supplied document; linked entities read from the sources are masked for the caller. */
    public ViewModel preview(Optional<Sutra> sutra, EntityDocument doc, UnaryOperator<DataNode> redact) {
        return preview(sutra, doc, redact, ANY);
    }

    /** A supplied document, as the caller may see it; sourced panels of kinds {@code mayOpen} refuses show "no access". */
    public ViewModel preview(Optional<Sutra> sutra, EntityDocument doc, UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        long t0 = System.nanoTime();
        return assemble(doc, t0, t0, sutra.isPresent() ? sutra : matcher.match(doc.ref().kind(), doc.data()), false, AsOf.LATEST, redact,
                mayOpen).view();
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
        return view(ref, asOf, UnaryOperator.identity());
    }

    /** The view of {@code ref} as the caller may see it: {@code redact} masks what the caller's role may not see. */
    public ViewModel view(EntityRef ref, AsOf asOf, UnaryOperator<DataNode> redact) {
        return view(ref, asOf, redact, ANY);
    }

    /** The view as the caller may see it: linked and sourced entities of kinds {@code mayOpen} refuses are never fetched or bound. */
    public ViewModel view(EntityRef ref, AsOf asOf, UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        long t0 = System.nanoTime();
        EntityDocument doc = fetch(ref, asOf);
        return assemble(doc, t0, System.nanoTime(), matcher.match(doc.ref().kind(), doc.data()), true, asOf, redact, mayOpen).view();
    }

    /**
     * What {@code view} built, with what the explanation of it needs: the document as stored, the Sutra chosen and the linked
     * entities' counts. Built by one pass of the same code as the view, so the two never disagree.
     *
     * @param view the view as the caller may see it
     * @param doc the document as stored (never sent out; the explanation derives verdicts from it, never values)
     * @param seen the document as the caller may see it
     * @param sutra the Sutra chosen for the document, empty when the layout is inferred
     * @param layout the layout the view was built from (panels with their options, and why inference added what it did)
     * @param current whether the business date is the current one
     * @param linked how many linked entities were fetched, are still pending, or are refused to the caller
     */
    public record Built(ViewModel view, EntityDocument doc, EntityDocument seen, Optional<Sutra> sutra, EffectiveLayout layout, boolean current,
            LinkCounts linked) {}

    /** Linked entities of a view: fetched in time, pending past the budget, denied to the caller; and the budget in milliseconds. */
    public record LinkCounts(int fetched, int pending, int denied, long budgetMs) {}

    /**
     * The view of a document supplied by the caller (a Design's sample) with a given Sutra, with what explaining it needs; as
     * {@link #preview(Optional, EntityDocument, UnaryOperator, Predicate)}: nothing is cached, because the Sutra is a draft.
     */
    public Built builtPreview(Optional<Sutra> sutra, EntityDocument doc, UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        long t0 = System.nanoTime();
        return assemble(doc, t0, t0, sutra.isPresent() ? sutra : matcher.match(doc.ref().kind(), doc.data()), false, AsOf.LATEST, redact, mayOpen);
    }

    /** The stored document of {@code ref} as of {@code asOf} (the source's cache serves repeats). */
    public EntityDocument document(EntityRef ref, AsOf asOf) {
        return fetch(ref, asOf);
    }

    /** The view of an already fetched document, with what explaining it needs; as {@link #view(EntityRef, AsOf, UnaryOperator, Predicate)}. */
    public Built built(EntityDocument doc, AsOf asOf, UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        long t0 = System.nanoTime();
        return assemble(doc, t0, t0, matcher.match(doc.ref().kind(), doc.data()), true, asOf, redact, mayOpen);
    }


    /**
     * The rows of one table or ladder of {@code ref}'s view, as raw values of its pivot's fields (the Pivot tab): the same
     * document, Sutra and business date as the view, every row up to {@code drishti.pivot.max-records}.
     *
     * @throws DrishtiException {@code DRS-1001} when the view has no such panel or the panel offers no pivot
     */
    public com.ash.drishti.engine.bind.PivotBinder.Records records(EntityRef ref, AsOf asOf, String panelId) {
        return records(ref, asOf, panelId, UnaryOperator.identity());
    }

    /** A panel's rows as the caller may see them: masked fields read {@link DataNode#MASK}. */
    public com.ash.drishti.engine.bind.PivotBinder.Records records(EntityRef ref, AsOf asOf, String panelId, UnaryOperator<DataNode> redact) {
        return records(ref, asOf, panelId, redact, ANY);
    }

    /**
     * A panel's rows for a caller who may open only the kinds {@code mayOpen} accepts.
     *
     * @throws DrishtiException {@code DRS-5002} when the panel's source is of a kind the caller may not open
     */
    public com.ash.drishti.engine.bind.PivotBinder.Records records(EntityRef ref, AsOf asOf, String panelId, UnaryOperator<DataNode> redact,
            Predicate<String> mayOpen) {
        EntityDocument doc = fetch(ref, asOf);
        Optional<Sutra> sutra = matcher.match(ref.kind(), doc.data());
        Fingerprint fp = fingerprints.get(new GenKey(ref, doc.provenance().generation(), doc.provenance().businessDate()),
                k -> fingerprinter.fingerprint(doc.data()));
        EffectiveLayout layout = layouts.get(new LayoutKey(sutra.<Object>map(SutraIdentity::new).orElse("-"), ref.kind(), fp),
                k -> merger.merge(sutra, doc.data(), ref.kind()));
        Panel panel = layout.sutra().panels().stream().filter(p -> p.id().equals(panelId) && p.pivot().isPresent()).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the view of " + ref.id() + " has no panel '" + panelId
                        + "' that offers a pivot"));
        EntityDocument seen = seen(doc, redact);
        EvalContext eval = EvalContext.of(seen.data(), formats);
        Map<EntityRef, EntityDocument> linked = new LinkedHashMap<>();
        binder.chartSource(panel, eval).ifPresent(src -> {
            if (!mayOpen.test(src.kind())) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "no access to " + src.kind());
            }
            router.fetchAll(Set.of(src), graph.linkBudget(), asOf.businessDate() == null ? dates.resolve(asOf) : asOf)
                    .forEach((r, d) -> linked.put(r, seen(d, redact)));
        });
        BindContext ctx = new BindContext(seen, layout, fp, eval, List.of(), linked, Set.of());
        return binder.records(panel, ctx);
    }

    /** Builds a view from a document already in hand (live updates re-enter here; live is always current). */
    public ViewModel build(EntityDocument doc, long t0, long tFetched) {
        return build(doc, t0, tFetched, UnaryOperator.identity());
    }

    /** Builds a view from a document already in hand, as the caller may see it. */
    public ViewModel build(EntityDocument doc, long t0, long tFetched, UnaryOperator<DataNode> redact) {
        return build(doc, t0, tFetched, redact, ANY);
    }

    /** Builds a view from a document already in hand (live rebuilds), for a caller who may open only what {@code mayOpen} accepts. */
    public ViewModel build(EntityDocument doc, long t0, long tFetched, UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        return assemble(doc, t0, tFetched, matcher.match(doc.ref().kind(), doc.data()), true, AsOf.LATEST, redact, mayOpen).view();
    }

    /**
     * The title of {@code doc}'s view as the caller may see it: the matched Sutra's (or the inferred layout's) title bound
     * to the document with the caller's field masks. The type-ahead describes entities with it for callers with masks.
     */
    public ViewModel.TitleView title(EntityDocument doc, UnaryOperator<DataNode> redact) {
        EntityRef ref = doc.ref();
        Optional<Sutra> sutra = matcher.match(ref.kind(), doc.data());
        Fingerprint fp = fingerprints.get(new GenKey(ref, doc.provenance().generation(), doc.provenance().businessDate()),
                k -> fingerprinter.fingerprint(doc.data()));
        EffectiveLayout layout = layouts.get(new LayoutKey(sutra.<Object>map(SutraIdentity::new).orElse("-"), ref.kind(), fp),
                k -> merger.merge(sutra, doc.data(), ref.kind()));
        return title(layout.sutra(), EvalContext.of(redact.apply(doc.data()), formats), ref);
    }

    /** The document as the caller may see it (the same object when nothing is masked). */
    private static EntityDocument seen(EntityDocument doc, UnaryOperator<DataNode> redact) {
        DataNode data = redact.apply(doc.data());
        return data == doc.data() ? doc : new EntityDocument(doc.ref(), data, doc.provenance(), doc.deleted());
    }

    private Built assemble(EntityDocument doc, long t0, long tFetched, Optional<Sutra> sutra, boolean cached, AsOf asOf,
            UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        EntityRef ref = doc.ref();
        boolean current = dates.isCurrent(asOf);
        Fingerprint fp = fingerprints.get(new GenKey(ref, doc.provenance().generation(), doc.provenance().businessDate()),
                k -> fingerprinter.fingerprint(doc.data()));
        EffectiveLayout layout = cached
                ? layouts.get(new LayoutKey(sutra.<Object>map(SutraIdentity::new).orElse("-"), ref.kind(), fp), k -> merger.merge(sutra, doc.data(), ref.kind()))
                : merger.merge(sutra, doc.data(), ref.kind());
        long tLayout = System.nanoTime();

        EntityDocument seen = seen(doc, redact);                      // values: as the caller may see them
        EvalContext eval = EvalContext.of(seen.data(), formats);
        List<LinkRef> links = catalog.discover(seen.data(), ref);
        Set<EntityRef> wanted = new LinkedHashSet<>();
        Set<EntityRef> denied = new HashSet<>();
        links.forEach(l -> {
            if (mayOpen.test(l.target().kind())) {
                wanted.add(l.target());
            }
        });
        for (Panel p : layout.sutra().panels()) {
            if (p.kind().readsData()) {
                binder.chartSource(p, eval).ifPresent(src -> {
                    if (mayOpen.test(src.kind())) {
                        wanted.add(src);
                    } else {
                        denied.add(src);          // never fetched: the panel says "no access" and holds none of its values
                    }
                });
            }
        }
        denied.removeAll(wanted);
        Set<EntityRef> missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Map<EntityRef, EntityDocument> linked = wanted.isEmpty() ? Map.of()
                : router.fetchAll(wanted, graph.linkBudget(), asOf.businessDate() == null ? dates.resolve(asOf) : asOf, new SourceFailures(), missing);
        if (!linked.isEmpty()) {
            Map<EntityRef, EntityDocument> masked = new LinkedHashMap<>();
            linked.forEach((r, d) -> masked.put(r, seen(d, redact)));
            linked = masked;
        }
        Set<EntityRef> pending = new HashSet<>(wanted);
        pending.removeAll(linked.keySet());
        pending.removeAll(missing);                      // held by no source: not waiting, simply not found
        long tLinks = System.nanoTime();

        BindContext ctx = new BindContext(seen, layout, fp, eval, links, linked, pending, denied);
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
            } catch (RuntimeException | StackOverflowError e) {
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
        // what the caller's masks hid: counted on the built view, only when a mask changed the document at all
        com.ash.drishti.engine.view.MaskCount.Result hidden = seen == doc ? new com.ash.drishti.engine.view.MaskCount.Result(0, List.of())
                : com.ash.drishti.engine.view.MaskCount.of(title, strip, panels);
        ViewModel view = new ViewModel(new ViewModel.Ref(ref.kind(), ref.id()), mnemonics.codeFor(ref.kind()), title, strip, panels,
                keys(s, panels, eval), new ViewModel.Provenance(layout.label(), fp.shortForm(), pv.source(), pv.generation(),
                        pv.fetchedAt().toString(), (pv.live() || router.pushes(ref)) && current,   // a ticks-only stream makes a stored entity live
                        pv.businessDate() == null ? null : pv.businessDate().toString(),
                        fresh.lastUpdate() == null ? null : fresh.lastUpdate().toString(),
                        fresh.staleAfter() == null ? null : fresh.staleAfter().toString(), fresh.stale() && current,
                        s.version() > 0 ? s.name() : null, hidden.count(), hidden.panels()), timings);
        return new Built(view, doc, seen, sutra, layout, current,
                new LinkCounts(linked.size(), pending.size(), denied.size(), graph.linkBudget().toMillis()));
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
        } catch (RuntimeException | StackOverflowError e) {
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
