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
package com.ash.drishti.engine.explain;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceHealth;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.rachana.MatchTrace;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.AboutText;
import com.ash.drishti.rachana.about.GlossaryEntry;
import com.ash.drishti.rachana.about.GlossaryResolver;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Explains a page (docs/architecture/CONTEXT_HELP.md, layers 3 and 4): where its data came from and how fresh it is, and
 * why it looks as it does. Nothing is computed while a view is built; the answer is derived on request by building the view
 * again for the same caller (same field masks, same right to open kinds, same restrictions on links), so the explanation is
 * of exactly what the caller is allowed to see and never of a ViewModel a browser sent. The view is cheap to rebuild: the
 * layout is cached per shape and the document is served by the source's cache.
 *
 * <p>Answers are cached per user, page, business date and generation for {@code drishti.explain.cache-ttl}, because masks and
 * rights are per user. Thread-safe.
 */
public final class ExplainService {

    private static final String NO_ACCESS = "no access to ";

    private record Key(String user, EntityRef ref, String businessDate, long generation, String locale, long aboutRevision) {}

    /** What is cached: the answer and the panel ids of the page (for validating {@code ?panel=}). */
    private record Entry(PageContext context, Set<String> panels, double viewMs) {}

    private final ViewPipeline pipeline;
    private final SutraMatcher matcher;
    private final SourceRouter router;
    private final ElCompiler el;
    private final SutraRegistry registry;
    private final AboutCatalog about;
    private final Formats formats;
    private final GlossaryResolver glossary;
    private final Counter templateErrors;
    private final Cache<Key, Entry> cache;
    private final Timer timer;

    public ExplainService(ViewPipeline pipeline, SutraMatcher matcher, SourceRouter router, ElCompiler el, SutraRegistry registry, AboutCatalog about,
            Formats formats, ExplainProperties props, MeterRegistry meters) {
        this.pipeline = pipeline;
        this.matcher = matcher;
        this.router = router;
        this.el = el;
        this.registry = registry;
        this.about = about;
        this.formats = formats;
        this.glossary = new GlossaryResolver(about);
        this.templateErrors = Counter.builder("drishti.explain.template-errors")
                .description("About templates whose expressions failed").register(meters);
        this.cache = Caffeine.newBuilder().maximumSize(props.cacheSize()).expireAfterWrite(props.cacheTtl()).build();
        this.timer = Timer.builder("drishti.explain").description("Time to explain a page").publishPercentiles(0.5, 0.99).register(meters);
    }

    /** The caller: who they are (the cache is per user) and what they may see. */
    public record Caller(String user, UnaryOperator<DataNode> redact, Predicate<String> mayOpen, UnaryOperator<ViewModel> restrict) {}

    /**
     * Explains the page of {@code ref}.
     *
     * @param panel narrows the answer to one panel of the page when not null
     * @param generation the generation the caller's page shows, or null; a newer one on the server is said in the answer
     * @param locales the caller's languages, best first ({@code ?locale=}, then the user's preference, then Accept-Language); the
     *     first with an about overlay is used, else English; the answer says which
     * @throws DrishtiException {@code DRS-4006} when {@code panel} names no panel of the page; the source's own errors as for the view
     */
    public PageContext explain(EntityRef ref, AsOf asOf, Caller caller, String panel, Long generation, List<String> locales) {
        long t0 = System.nanoTime();
        EntityDocument doc = pipeline.document(ref, asOf);
        long gen = doc.provenance().generation();
        String date = doc.provenance().businessDate() == null ? "" : doc.provenance().businessDate().toString();
        String locale = about.localeFor(locales);
        Entry e = cache.get(new Key(caller.user(), ref, date, gen, locale, about.revision()), k -> derive(doc, asOf, caller, locale));
        if (panel != null && !e.panels().contains(panel)) {
            throw new DrishtiException(ErrorCode.EXPLAIN_NO_PANEL, "the view of " + ref.id() + " has no panel '" + panel + "'");
        }
        PageContext c = narrow(e.context(), panel);
        double explainMs = Math.round((System.nanoTime() - t0) / 1e4) / 100.0;
        timer.record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("view", e.viewMs());
        timings.put("explain", explainMs);
        return new PageContext(c.ref(), c.mnemonic(), c.locale(), c.generation(), generation != null && gen > generation ? Boolean.TRUE : null,
                c.about(), c.glossary(), c.data(), c.layout(), c.next(), timings);
    }

    /**
     * {@code ?panel=}: keeps of the glossary only the entries the panel shows, and of the authored panel text only the panel's
     * own, to cut bytes. Everything else is as for the page.
     */
    private static PageContext narrow(PageContext c, String panel) {
        if (panel == null) {
            return c;
        }
        List<PageContext.Term> terms = c.glossary() == null ? null : c.glossary().stream().filter(t -> t.shownIn().contains(panel)).toList();
        PageContext.About a = c.about();
        if (a != null && a.panels() != null) {
            a = new PageContext.About(a.pack(), a.kindTitle(), a.text(), a.sutraDescription(), a.panels().stream().filter(x -> x.id().equals(panel)).toList());
        }
        return new PageContext(c.ref(), c.mnemonic(), c.locale(), c.generation(), c.newer(), a, terms, c.data(), c.layout(), c.next(), c.timings());
    }

    /** Forgets every answer (the admin's cache purge). */
    public void purge() {
        cache.invalidateAll();
    }

    public long size() {
        return cache.estimatedSize();
    }

    private Entry derive(EntityDocument doc, AsOf asOf, Caller caller, String locale) {
        ViewPipeline.Built built = pipeline.built(doc, asOf, caller.redact(), caller.mayOpen());
        ViewModel view = caller.restrict().apply(built.view());
        ViewModel.Provenance pv = view.provenance();
        Set<String> ids = new LinkedHashSet<>();
        view.panels().forEach(p -> ids.add(p.id()));
        PageContext ctx = new PageContext(view.ref(), view.mnemonic(), locale, pv.generation(), null, about(view, built, locale), glossary(view, built, locale), data(pv, built), layout(view, built),
                next(view), null);
        return new Entry(ctx, ids, view.timings().getOrDefault("total", 0.0));
    }

    /**
     * Layer 1. The page's text is rendered over {@code built.seen()}, the document after the caller's field masks, and only
     * that: a template cannot read what the view does not show this caller. Only panels the caller may open get text.
     */
    private PageContext.About about(ViewModel view, ViewPipeline.Built built, String locale) {
        String sutraDescription = built.sutra().map(Sutra::description).orElse(null);
        AboutText t = about.forKind(view.ref().kind(), locale).orElse(null);
        if (t == null) {
            return sutraDescription == null ? null : new PageContext.About(null, null, null, sutraDescription, null);
        }
        Map<String, String> titles = new LinkedHashMap<>();
        view.panels().stream().filter(p -> p.denied() == null).forEach(p -> titles.put(p.id(), p.title()));
        AboutText.Rendered r = t.render(EvalContext.of(built.seen().data(), formats), titles.keySet(), about.maxRendered());
        if (r.errors() > 0) {
            templateErrors.increment(r.errors());
        }
        List<PageContext.PanelAbout> panels = new ArrayList<>();
        r.panels().forEach((id, text) -> panels.add(new PageContext.PanelAbout(id, titles.get(id), text)));
        return new PageContext.About(new PageContext.Pack(t.pack(), t.packTitle()), t.title(), r.text(), sutraDescription, panels);
    }

    /**
     * Layer 2: what each field the page shows means. Taken from the view the caller got, so a field the page does not show has
     * no entry, a panel the caller may not open adds none, and a hidden field's definition is given without the meaning of its values.
     */
    private List<PageContext.Term> glossary(ViewModel view, ViewPipeline.Built built, String locale) {
        String kind = view.ref().kind();
        Map<String, Panel> defined = new LinkedHashMap<>();
        built.layout().sutra().panels().forEach(p -> defined.put(p.id(), p));
        String source = view.provenance().source();
        Function<String, Optional<GlossaryEntry>> derived = key -> key.indexOf('.') >= 0 ? Optional.empty()
                : router.connectorOf(kind, source).flatMap(d -> d.describeField(kind, key))
                        .map(n -> GlossaryEntry.derived(key, n.means(), n.formula(), n.origin()));
        return new GlossaryBuilder(glossary.forLocale(locale), kind, defined, derived).build(view);
    }

    private PageContext.Data data(ViewModel.Provenance pv, ViewPipeline.Built built) {
        String health = router.connectorOf(built.doc().ref().kind(), pv.source()).map(p -> SourceHealth.of(p).word()).orElse(null);
        var l = built.linked();
        return new PageContext.Data(pv.source(), pv.generation(), pv.fetchedAt(), pv.businessDate(), built.current(), pv.live(),
                pv.updatedAt(), pv.staleAfter(), pv.stale(), health, new PageContext.Linked(l.fetched(), l.pending(), l.denied(), l.budgetMs()));
    }

    private PageContext.Layout layout(ViewModel view, ViewPipeline.Built built) {
        ViewModel.Provenance pv = view.provenance();
        String kind = view.ref().kind();
        MatchTrace trace = matcher.explain(kind, built.doc().data(), built.seen().data());
        PageContext.Chosen chosen = built.sutra().map(s -> new PageContext.Chosen(s.name(), s.version(), registry.fileOf(s.id()).flatMap(f -> about.packOfSutra(f.toString())).map(p -> p.name()).orElse(null), s.match().priority(), s.match().where(),
                s.description())).orElse(null);
        List<PageContext.Candidate> candidates = new ArrayList<>();
        for (MatchTrace.Candidate c : trace.candidates()) {
            Sutra s = c.sutra();
            if (built.sutra().isPresent() && built.sutra().get() == s) {
                continue;
            }
            candidates.add(new PageContext.Candidate(s.name(), s.version(), s.match().priority(), c.where(), c.result().text()));
        }
        Map<String, Panel> defined = new LinkedHashMap<>();
        built.layout().sutra().panels().forEach(p -> defined.put(p.id(), p));

        List<PageContext.InferredPanel> inferred = new ArrayList<>();
        List<PageContext.NoData> noData = new ArrayList<>();
        List<PageContext.PanelError> errors = new ArrayList<>();
        List<PageContext.NoAccess> noAccess = new ArrayList<>();
        for (PanelView p : view.panels()) {
            if (p.denied() != null) {
                noAccess.add(new PageContext.NoAccess(p.title(), p.denied().startsWith(NO_ACCESS) ? p.denied().substring(NO_ACCESS.length()) : null));
                continue;
            }
            if (p.inferred()) {
                inferred.add(new PageContext.InferredPanel(p.id(), p.title(), p.explanation() == null ? "completed by inference" : p.explanation()));
            }
            if (p.error() != null) {
                errors.add(new PageContext.PanelError(p.id(), p.title(), p.error()));
            } else if (p.empty()) {
                EmptinessReason.Reason r = EmptinessReason.of(defined.get(p.id()), built.seen().data(), el);
                noData.add(new PageContext.NoData(p.id(), p.title(), r.why(), r.path()));
            }
        }
        return new PageContext.Layout(pv.layout(), pv.fingerprint(), chosen, built.layout().inferred(), candidates, inferred, noData, errors,
                masked(view), noAccess);
    }

    private PageContext.Next next(ViewModel view) {
        Set<String> kinds = new LinkedHashSet<>();
        view.panels().stream().filter(p -> p.denied() == null).forEach(p -> kinds.add(p.kind()));
        return new PageContext.Next(view.keys(), List.copyOf(kinds));
    }

    /** Fields the page shows hidden for the caller: cells that read the mask, grouped by field, with the panels they are in. */
    private static List<PageContext.MaskedField> masked(ViewModel view) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Set<String>> panels = new LinkedHashMap<>();
        for (Cell c : view.strip()) {
            note(labels, panels, c, c.label(), "strip");
        }
        if (view.title().with() != null) {
            note(labels, panels, view.title().with(), "Title", "title");
        }
        for (PanelView p : view.panels()) {
            if (p.denied() != null || p.data() == null) {
                continue;
            }
            switch (p.data()) {
                case PanelData.Fields f -> f.fields().forEach(c -> note(labels, panels, c, c.label(), p.id()));
                case PanelData.Tabs t -> t.tabs().forEach(tab -> tab.fields().forEach(c -> note(labels, panels, c, c.label(), p.id())));
                case PanelData.Table t -> {
                    t.rows().forEach(r -> rowCells(labels, panels, t.columns(), r, p.id()));
                    if (t.total() != null) {
                        rowCells(labels, panels, t.columns(), t.total(), p.id());
                    }
                }
                case PanelData.Metric m -> {
                    note(labels, panels, m.value(), m.value() == null ? null : m.value().label(), p.id());
                    note(labels, panels, m.delta(), m.delta() == null ? null : m.delta().label(), p.id());
                }
                default -> { }
            }
        }
        List<PageContext.MaskedField> out = new ArrayList<>();
        labels.forEach((key, label) -> out.add(new PageContext.MaskedField(key, label, List.copyOf(panels.get(key)))));
        return out;
    }

    private static void rowCells(Map<String, String> labels, Map<String, Set<String>> panels, List<String> columns,
            PanelData.Row row, String panel) {
        for (int i = 0; i < row.cells().size(); i++) {
            note(labels, panels, row.cells().get(i), i < columns.size() ? columns.get(i) : null, panel);
        }
        if (row.children() != null) {
            row.children().forEach(c -> rowCells(labels, panels, columns, c, panel));
        }
    }

    private static void note(Map<String, String> labels, Map<String, Set<String>> panels, Cell c, String label, String panel) {
        if (c == null || !DataNode.MASK.equals(c.text())) {
            return;
        }
        String shown = label == null || label.isBlank() ? (c.label() == null ? "(unlabelled)" : c.label()) : label;
        String key = c.path() != null ? c.path() : shown;
        labels.putIfAbsent(key, shown);
        panels.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(panel);
    }
}
