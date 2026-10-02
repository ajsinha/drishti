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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.command.CommandsProperties;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.command.Suggestion;
import com.ash.drishti.engine.command.SuggestionService;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * The type-ahead as a caller with field masks may see it. A source describes its entities with text of its own (the demo
 * source's {@code "Trade · Interest rate swap · Meridian Reinsurance Ltd · AUD 242m"}) and matches what is typed against
 * it, and nothing tells which words of that text come from a masked field. So for such a caller every entity suggestion
 * is described again from its document as the caller sees it (the title of its view: the pill and the {@code with} part,
 * masked fields reading {@code •••}), and kept only when what was typed is in its id or in that description: a masked
 * value is neither shown nor matched. An entity whose document cannot be read within {@code drishti.commands.suggest-budget}
 * is described by its kind alone. Callers without masks get the sources' suggestions unchanged. Stateless; thread-safe.
 */
@Component
public class SuggestionMasks {

    private final Entitlements entitlements;
    private final ViewPipeline pipeline;
    private final SourceRouter router;
    private final SuggestionService suggestions;
    private final Mnemonics mnemonics;
    private final Duration budget;

    public SuggestionMasks(Entitlements entitlements, ViewPipeline pipeline, SourceRouter router, SuggestionService suggestions,
            Mnemonics mnemonics, CommandsProperties props) {
        this.entitlements = entitlements;
        this.pipeline = pipeline;
        this.router = router;
        this.suggestions = suggestions;
        this.mnemonics = mnemonics;
        this.budget = props.suggestBudget();
    }

    /** {@code in} (the suggestions for {@code query}) as {@code p} may see them. */
    public List<Suggestion> apply(Principal p, String query, List<Suggestion> in, AsOf asOf) {
        if (!entitlements.masks(p)) {
            return in;
        }
        UnaryOperator<DataNode> redact = entitlements.redactor(p);
        String text = suggestions.matchText(query).toLowerCase(Locale.ROOT);
        Set<EntityRef> refs = new LinkedHashSet<>();
        in.stream().filter(s -> s.id() != null && s.kind() != null).forEach(s -> refs.add(EntityRef.of(s.kind(), s.id())));
        Map<EntityRef, EntityDocument> docs = refs.isEmpty() ? Map.of() : router.fetchAll(refs, budget, asOf);
        List<Suggestion> out = new ArrayList<>(in.size());
        for (Suggestion s : in) {
            if (s.id() == null || s.kind() == null) {
                out.add(s);                                      // a mnemonic: nothing of a document in it
                continue;
            }
            String[] described = describe(docs.get(EntityRef.of(s.kind(), s.id())), s, redact);
            boolean matches = text.isEmpty() || s.id().toLowerCase(Locale.ROOT).contains(text)
                    || (described[0] + " " + described[1]).toLowerCase(Locale.ROOT).contains(text);
            if (matches) {
                out.add(new Suggestion(s.type(), s.mnemonic(), s.kind(), s.id(), described[0], described[1], s.complete()));
            }
        }
        return out;
    }

    /** {title, subtitle} of a suggested entity from its document as the caller sees it; its kind when it was not read. */
    private String[] describe(EntityDocument doc, Suggestion s, UnaryOperator<DataNode> redact) {
        String kind = mnemonics.of(s.mnemonic() == null ? "" : s.mnemonic()).map(m -> m.label()).filter(l -> l != null && !l.isBlank())
                .orElse(s.kind());
        if (doc == null) {
            return new String[] {s.id(), kind};
        }
        try {
            ViewModel.TitleView t = pipeline.title(doc, redact);
            String with = t.with() == null || t.with().text() == null || t.with().text().isBlank() ? "" : " · " + t.with().text();
            return new String[] {t.id() == null ? s.id() : t.id(), (t.pill() == null || t.pill().isBlank() ? kind : t.pill()) + with};
        } catch (RuntimeException e) {
            return new String[] {s.id(), kind};                 // a document no layout can describe: its kind
        }
    }
}
