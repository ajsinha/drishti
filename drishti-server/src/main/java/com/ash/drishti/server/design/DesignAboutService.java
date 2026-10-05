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
package com.ash.drishti.server.design;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.explain.AboutPreview;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.identity.design.StoredDesign.SampleInfo;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.DesignAbout;
import com.ash.drishti.rachana.about.GlossaryEntry;
import com.ash.drishti.rachana.about.GlossaryResolver;
import com.ash.drishti.rachana.about.HelpLint;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * The workbench's About tab: the About card of a Design's preview (layers 1 and 2 of CONTEXT_HELP.md and the panels' text), made
 * with the drawer's own code over the Design's sample, with the Design's about text laid over what the packs say, and the lint
 * ({@code DRS-2040} to {@code DRS-2047}) for the text in the box. Reading only: nothing is saved here. The sample is read with the
 * caller's rights and field masks, as the preview is. Stateless; thread-safe.
 */
@Service
public class DesignAboutService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DesignService designs;
    private final SutraRegistry sutras;
    private final ViewPipeline pipeline;
    private final Entitlements entitlements;
    private final JsonCodec codec;
    private final AboutCatalog catalog;
    private final ElCompiler el;
    private final AboutPreview preview;

    public DesignAboutService(DesignService designs, SutraRegistry sutras, ViewPipeline pipeline, Entitlements entitlements, JsonCodec codec,
            AboutCatalog catalog, ElCompiler el, AboutPreview preview) {
        this.designs = designs;
        this.sutras = sutras;
        this.pipeline = pipeline;
        this.entitlements = entitlements;
        this.codec = codec;
        this.catalog = catalog;
        this.el = el;
        this.preview = preview;
    }

    /**
     * The About tab's answer for {@code text} (the editor's content; null means the Design's saved text) over {@code sample} (its name;
     * null means the first). The answer is {@code {rev, about (the text), problems, lint, coverage, card?, sample?, error?}}: problems are
     * what is wrong with the text itself, lint the warnings about the Design (F1, unknown panels, fields shown without an entry), the card
     * the drawer's layers 1 and 2 as the preview shows them.
     */
    public ObjectNode answer(Principal who, String id, String text, String sample) {
        StoredDesign d = designs.get(who.user(), id);
        String about = text == null ? d.about : text;
        ObjectNode out = JSON.createObjectNode();
        out.put("rev", d.rev).put("about", about == null ? "" : about);
        Sutra s = null;
        try {
            s = d.sutra == null || d.sutra.isBlank() ? null : sutras.check(d.sutra);
        } catch (DrishtiException e) {
            out.put("error", "the Sutra does not check yet: " + e.getMessage());
        }
        String kind = s != null && s.match() != null && s.match().kind() != null && !s.match().kind().isBlank() ? s.match().kind() : d.kind;
        out.put("kind", kind);
        DesignAbout source = DesignAbout.of(catalog, el, catalog.maxText(), about, kind);
        ArrayNode problems = out.putArray("problems");
        source.problems().forEach(p -> problems.add(problem(p, "error")));
        ArrayNode lint = out.putArray("lint");
        ObjectNode coverage = out.putObject("coverage");
        if (s == null) {
            return out;
        }
        SampleInfo info = sample == null || sample.isBlank() ? (d.samples.isEmpty() ? null : d.samples.get(0)) : d.sample(sample);
        if (info == null) {
            out.put("error", sample == null || sample.isBlank() ? "this design has no samples to explain a page of" : "no sample '" + sample + "' in this design");
            lintOnly(d, s, kind, source, null, lint, coverage);
            return out;
        }
        out.put("sample", info.name());
        try {
            Predicate<String> mayOpen = k -> entitlements.mayOpen(who, k);
            EntityDocument doc = document(who, d, info, mayOpen);
            ViewPipeline.Built built = pipeline.builtPreview(Optional.of(s), doc, entitlements.redactor(who), mayOpen);
            ViewModel view = entitlements.restrict(who, built.view());
            AboutPreview.Card card = preview.card(source, built, view, catalog.maxRendered());
            out.set("card", JSON.valueToTree(card));
            ArrayNode panels = out.putArray("panels");
            AboutPreview.panelTitles(view).forEach((pid, title) -> panels.addObject().put("id", pid).put("title", title));
            lintOnly(d, s, kind, source, JSON.readTree(codec.toJson(doc.data())), lint, coverage);
        } catch (DrishtiException e) {
            out.put("error", e.getMessage());
            lintOnly(d, s, kind, source, null, lint, coverage);
        } catch (IOException e) {
            out.put("error", "the sample could not be read");
        }
        return out;
    }

    private void lintOnly(StoredDesign d, Sutra s, String kind, DesignAbout source, JsonNode sampleDoc, ArrayNode lint, ObjectNode coverage) {
        GlossaryResolver resolver = new GlossaryResolver(source);
        HelpLint help = new HelpLint((k, field) -> resolver.resolve(k, field, key -> Optional.<GlossaryEntry>empty()).isPresent());
        SutraProblem f1 = help.f1(s);
        if (f1 != null) {
            lint.add(problem(f1, "warning"));
        }
        Set<String> ids = new LinkedHashSet<>();
        s.panels().forEach(p -> collect(p, ids));
        help.unknownPanels(kind, ids, source).forEach(p -> lint.add(problem(p, "warning")));
        Predicate<String> present = sampleDoc == null ? f -> true : f -> sampleDoc.findValue(f.substring(f.lastIndexOf('.') + 1)) != null;
        HelpLint.Coverage c = help.coverage(kind, s, present);
        help.uncovered(c, s).forEach(p -> lint.add(problem(p, "warning")));
        ArrayNode missing = coverage.putArray("missing");
        c.missing().forEach(missing::add);
        coverage.put("shown", c.shown().size()).put("covered", c.covered()).put("text", c.describe());
    }

    private static void collect(Panel p, Set<String> ids) {
        ids.add(p.id());
        if (p.body() != null) {
            collect(p.body(), ids);
        }
    }

    private EntityDocument document(Principal who, StoredDesign d, SampleInfo info, Predicate<String> mayOpen) {
        if (StoredDesign.REF.equals(info.type())) {
            if (!mayOpen.test(info.refKind())) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, Entitlements.DENIED + ": you may not open " + info.refKind() + " entities");
            }
            return pipeline.document(EntityRef.of(info.refKind(), info.refId()), AsOf.LATEST);
        }
        String json = designs.sample(who.user(), d.id, info.name());
        return new EntityDocument(EntityRef.of(d.kind, "SAMPLE"), codec.read(json),
                new com.ash.drishti.api.Provenance("design sample JSON", 0, java.time.Instant.now(), false));
    }

    private static ObjectNode problem(SutraProblem p, String severity) {
        ObjectNode o = JSON.createObjectNode();
        o.put("code", p.code()).put("message", p.message()).put("severity", severity);
        if (p.location() != null && p.location().line() > 0) {
            o.put("line", p.location().line()).put("column", p.location().column());
        }
        return o;
    }
}
