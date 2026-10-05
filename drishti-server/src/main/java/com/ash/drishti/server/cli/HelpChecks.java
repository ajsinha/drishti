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
package com.ash.drishti.server.cli;

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.AboutText;
import com.ash.drishti.rachana.about.CatalogGlossaryLookup;
import com.ash.drishti.rachana.about.GlossaryLookup;
import com.ash.drishti.rachana.about.HelpLint;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The help half of {@code sutra lint} and {@code sutra test} (docs/architecture/CONTEXT_HELP.md, step 6): the {@code DRS-2045} to
 * {@code DRS-2047} warnings, coverage over the test samples and the {@code expect.yaml help:} assertions. Built over the engine's
 * about catalogue, or over any {@link GlossaryLookup} (step 4's glossary resolver plugs in there). Stateless; thread-safe.
 */
public final class HelpChecks {

    private final AboutCatalog catalog;
    private final Formats formats;
    private final JsonCodec codec;
    private final HelpLint lint;

    public HelpChecks(AboutCatalog catalog, GlossaryLookup lookup, Formats formats, JsonCodec codec) {
        this.catalog = catalog;
        this.formats = formats;
        this.codec = codec;
        this.lint = new HelpLint(lookup != null ? lookup : catalog == null ? GlossaryLookup.NONE : new CatalogGlossaryLookup(catalog));
    }

    private static String kindOf(Sutra s) {
        return s.match() == null ? null : s.match().kind();
    }

    /** DRS-2045 and DRS-2046 for one Sutra; {@code siblings} are the other Sutras of the run, for the panel ids of the kind. */
    List<SutraProblem> warnings(Sutra s, List<Sutra> siblings, List<Sutra> registered) {
        List<SutraProblem> out = new ArrayList<>();
        SutraProblem f1 = lint.f1(s);
        if (f1 != null) {
            out.add(f1);
        }
        String kind = kindOf(s);
        if (catalog != null && kind != null) {
            Set<String> ids = new LinkedHashSet<>();
            for (List<Sutra> group : List.of(siblings, registered)) {
                group.stream().filter(x -> kind.equals(kindOf(x))).forEach(x -> x.panels().forEach(p -> collect(p, ids)));
            }
            s.panels().forEach(p -> collect(p, ids));
            out.addAll(lint.unknownPanels(kind, ids, catalog));
        }
        return out;
    }

    private static void collect(Panel p, Set<String> ids) {
        ids.add(p.id());
        if (p.body() != null) {
            collect(p.body(), ids);
        }
    }

    /** Coverage of the fields {@code s} shows that some sample document holds (all of them when there are no samples). */
    HelpLint.Coverage coverage(Sutra s, List<JsonNode> samples) {
        Predicate<String> present = samples.isEmpty() ? f -> true : f -> samples.stream().anyMatch(d -> d.findValue(last(f)) != null);
        return lint.coverage(kindOf(s), s, present);
    }

    List<SutraProblem> uncovered(HelpLint.Coverage c, Sutra s) {
        return lint.uncovered(c, s);
    }

    private static String last(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    /** Null when the kind's page text renders over {@code doc} without a failing expression; else what went wrong. */
    String aboutError(Sutra s, JsonNode doc) {
        String kind = kindOf(s);
        AboutText t = catalog == null || kind == null ? null : catalog.forKind(kind).orElse(null);
        if (t == null) {
            return "no about text for kind '" + kind + "'";
        }
        List<String> ids = s.panels().stream().map(Panel::id).toList();
        AboutText.Rendered r = t.render(EvalContext.of(codec.read(doc.toString()), formats), ids, catalog.maxRendered());
        return r.errors() == 0 ? null : r.errors() + " about expression(s) failed to render (shown as " + AboutText.FAILED + ")";
    }
}
