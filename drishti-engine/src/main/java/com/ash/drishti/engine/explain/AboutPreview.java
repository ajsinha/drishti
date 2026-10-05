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

import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.rachana.about.AboutSource;
import com.ash.drishti.rachana.about.AboutText;
import com.ash.drishti.rachana.about.GlossaryEntry;
import com.ash.drishti.rachana.about.GlossaryResolver;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The About card (layers 1 and 2 of docs/architecture/CONTEXT_HELP.md) of a Design's preview, for the workbench's About tab.
 * It is the drawer's own code path over a view of a sample: the page text is rendered over the document as the caller may see it,
 * the glossary is built from the view the caller got, and the text comes from an {@link AboutSource}, which is the packs'
 * catalogue with the Design's own about text laid over it. Stateless; thread-safe.
 */
public final class AboutPreview {

    /**
     * @param about layer 1: the page text (rendered), the Sutra's description and the panels' text; null when there is nothing
     * @param glossary layer 2: one entry per field the preview shows that has one
     * @param errors expressions that failed (they read as the dash)
     */
    public record Card(PageContext.About about, List<PageContext.Term> glossary, int errors) {}

    private final SourceRouter router;
    private final Formats formats;

    public AboutPreview(SourceRouter router, Formats formats) {
        this.router = router;
        this.formats = formats;
    }

    /**
     * @param source the text to explain with
     * @param built the sample's view as {@link ViewPipeline#builtPreview} made it
     * @param view that view after the caller's restrictions (a panel the caller may not open gets no text)
     * @param maxRendered the longest a rendered text may be ({@code drishti.about.max-rendered})
     */
    public Card card(AboutSource source, ViewPipeline.Built built, ViewModel view, int maxRendered) {
        String kind = view.ref().kind();
        Sutra sutra = built.layout().sutra();
        String description = built.sutra().map(Sutra::description).orElse(null);
        AboutText t = source.forKind(kind).orElse(null);
        PageContext.About about = description == null ? null : new PageContext.About(null, null, null, description, null);
        int errors = 0;
        if (t != null) {
            Map<String, String> titles = new LinkedHashMap<>();
            view.panels().stream().filter(p -> p.denied() == null).forEach(p -> titles.put(p.id(), p.title()));
            AboutText.Rendered r = t.render(EvalContext.of(built.seen().data(), formats), titles.keySet(), maxRendered);
            errors = r.errors();
            List<PageContext.PanelAbout> panels = new ArrayList<>();
            r.panels().forEach((id, text) -> panels.add(new PageContext.PanelAbout(id, titles.get(id), text)));
            about = new PageContext.About(new PageContext.Pack(t.pack(), t.packTitle()), t.title(), r.text(), description, panels);
        }
        Map<String, Panel> defined = new LinkedHashMap<>();
        sutra.panels().forEach(p -> defined.put(p.id(), p));
        String origin = view.provenance().source();
        Function<String, Optional<GlossaryEntry>> derived = key -> key.indexOf('.') >= 0 ? Optional.empty()
                : router.connectorOf(kind, origin).flatMap(d -> d.describeField(kind, key))
                        .map(n -> GlossaryEntry.derived(key, n.means(), n.formula(), n.origin()));
        List<PageContext.Term> glossary = new GlossaryBuilder(new GlossaryResolver(source), kind, defined, derived).build(view);
        return new Card(about, glossary, errors);
    }

    /** The panel ids and titles of a view, for the editor's hints. */
    public static Map<String, String> panelTitles(ViewModel view) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PanelView p : view.panels()) {
            out.put(p.id(), p.title());
        }
        return out;
    }
}
