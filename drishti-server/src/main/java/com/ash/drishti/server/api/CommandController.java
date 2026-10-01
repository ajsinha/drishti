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
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.command.CommandParser;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.command.Suggestion;
import com.ash.drishti.engine.command.SuggestionService;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The command line: parsing {@code <MNEMONIC> <ID> <GO>} and the type-ahead dropdown. */
@RestController
@RequestMapping("/api/v1/command")
public class CommandController {

    private final CommandParser parser;
    private final SuggestionService suggestions;
    private final Mnemonics mnemonics;
    private final Entitlements entitlements;
    private final StructuredSearch search;
    private final SourceRouter router;
    private final SearchProperties searchProps;

    public CommandController(CommandParser parser, SuggestionService suggestions, Mnemonics mnemonics, Entitlements entitlements,
            StructuredSearch search, SourceRouter router, SearchProperties searchProps) {
        this.parser = parser;
        this.suggestions = suggestions;
        this.mnemonics = mnemonics;
        this.entitlements = entitlements;
        this.search = search;
        this.router = router;
        this.searchProps = searchProps;
    }

    /**
     * Resolves a command the way a Bloomberg terminal does: one entity opens it; several give a pick list.
     * <ul>
     *   <li>{@code TRD T-10001}: that trade, when it exists;</li>
     *   <li>{@code TRD T-100}: no such trade, so every trade whose id starts with T-100 (or whose title contains it);
     *       exactly one opens at once;</li>
     *   <li>{@code TRD productType=Revolver}, {@code TRD T-1* desk=rates}, {@code TRD}: always a pick list, unless
     *       exactly one entity matches.</li>
     * </ul>
     * Case never matters.
     */
    @PostMapping
    public ApiDtos.CommandResponse command(@RequestBody ApiDtos.CommandRequest req, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        String text = req.text() == null ? "" : req.text().replaceAll("(?i)<\\s*GO\\s*>", " ").trim();
        boolean pick = SearchQuery.looksLikePick(text);
        Optional<EntityRef> named = pick ? Optional.empty() : parser.parse(text);
        String head = text.split("\\s+", 2)[0];
        boolean listable = mnemonics.of(head.toUpperCase(Locale.ROOT)).isPresent();
        if (named.isPresent()) {
            EntityRef ref = named.get();
            entitlements.requireOpen(principal, ref.kind());
            if (!listable || exists(ref, asOf)) {
                return open(ref);
            }
        } else if (!listable) {
            parser.require(text);                                   // says what it cannot read
        }
        SearchQuery q = SearchQuery.pick(text);
        String kind = search.kindOf(q);
        entitlements.requireOpen(principal, kind);
        SearchQuery probe = new SearchQuery(q.head(), q.condition(), q.orderBy(), q.descending(), 2, q.fields(), q.idPattern());
        StructuredSearch.Result r = search.run(probe, asOf, data -> entitlements.redact(principal, data));
        if (r.matched() == 1 && !r.rows().isEmpty()) {
            return open(r.rows().get(0).ref());
        }
        return new ApiDtos.CommandResponse(null, mnemonics.codeFor(kind), text, r.matched());
    }

    private ApiDtos.CommandResponse open(EntityRef ref) {
        return new ApiDtos.CommandResponse(new ViewModel.Ref(ref.kind(), ref.id()), mnemonics.codeFor(ref.kind()));
    }

    private boolean exists(EntityRef ref, AsOf asOf) {
        return router.fetchAll(List.of(ref), searchProps.budget(), asOf).containsKey(ref);
    }

    @GetMapping("/suggest")
    public List<Suggestion> suggest(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) Integer limit, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return entitlements.filter(principal, suggestions.suggest(q, principal.user(), limit, asOf));
    }
}
