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
import com.ash.drishti.engine.command.HistoryRange;
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
import java.util.Map;
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
    private final com.ash.drishti.server.security.PackAccess packs;
    private final CommandMemory memory;
    private final SuggestionMasks masks;

    private com.ash.drishti.server.edition.EditionCommands edition = com.ash.drishti.server.edition.EditionCommands.NONE;

    /** The edition's command allow-list (EDITIONS.md › The design as built, section 5.3); none: every command shown. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEdition(com.ash.drishti.server.edition.EditionCommands edition) {
        this.edition = edition;
    }

    public CommandController(CommandParser parser, SuggestionService suggestions, Mnemonics mnemonics, Entitlements entitlements,
            StructuredSearch search, SourceRouter router, SearchProperties searchProps, com.ash.drishti.server.security.PackAccess packs,
            CommandMemory memory, SuggestionMasks masks) {
        this.masks = masks;
        this.packs = packs;
        this.memory = memory;
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
     *   <li>{@code TRD MX-20000001}: that trade, when it exists;</li>
     *   <li>{@code TRD MX-200000}: no such trade, so every trade whose id starts with MX-200000 (or whose title contains it);
     *       exactly one opens at once;</li>
     *   <li>{@code TRD productType=Revolver}, {@code TRD MX-2* desk=rates}, {@code TRD}: always a pick list, unless
     *       exactly one entity matches.</li>
     * </ul>
     * Case never matters.
     */
    @PostMapping
    public ApiDtos.CommandResponse command(@RequestBody ApiDtos.CommandRequest req, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        String typed = HistoryRange.asciiDigits(req.text() == null ? "" : req.text().replaceAll("(?i)<\\s*GO\\s*>", " ").trim());   // ３0 is 30
        String text = memory.expand(principal.user(), typed).trim();         // MYBOOK -> BOOK BOOK-RATES-1
        ApiDtos.CommandResponse answer = resolve(text, asOf, principal);
        if (principal.embedApp() == null) {
            memory.remember(principal.user(), typed);                        // only commands that could be read; an embedded host never writes the user's history
        }
        return answer;
    }

    private ApiDtos.CommandResponse resolve(String text, AsOf asOf, Principal principal) {
        refuseBadRange(text, asOf, principal);
        Optional<HistoryRange.Split> range = historyOf(text, asOf, principal);
        if (range.isPresent()) {                                    // TRD 1000015 30: the entity (or the pick list) over time
            ApiDtos.CommandResponse plain = resolvePlain(range.get().command(), asOf, principal);
            return plain.pack() != null ? plain : plain.withHistory(ApiDtos.HistoryAsk.of(range.get()));
        }
        return resolvePlain(text, asOf, principal);
    }

    /**
     * The range a command ends with, when it is one (DATA_ARCHITECTURE.md › History queries: an entity over time, section 4.2): never the first word after the mnemonic,
     * never on a search ({@code where}, an operator, a {@code *}), never when the whole text after the mnemonic is itself an
     * entity ({@code BOND UST 2030}); a bare id with a range only when the id's pattern names its kind.
     */
    private Optional<HistoryRange.Split> historyOf(String text, AsOf asOf, Principal principal) {
        if (SearchQuery.looksLikeSearch(text)) {
            return Optional.empty();
        }
        Optional<HistoryRange.Split> split = HistoryRange.trailing(text);
        if (split.isEmpty()) {
            return split;
        }
        String[] words = split.get().command().split("\\s+", 2);
        if (mnemonics.of(words[0].toUpperCase(Locale.ROOT)).isEmpty()) {
            return words.length == 1 && parser.parse(words[0]).isPresent() ? split : Optional.empty();   // MX-20000001 30
        }
        if (words.length < 2 || SEARCHY.matcher(" " + words[1] + " ").find()) {
            return Optional.empty();                                // TRD 30 opens trade 30; TRD desk=rates 30 stays a search
        }
        Optional<EntityRef> whole = parser.parse(text);             // BOND UST 2030: the whole rest is tried as an id first
        if (whole.isPresent() && entitlements.mayOpen(principal, whole.get().kind()) && exists(whole.get(), asOf)) {
            return Optional.empty();
        }
        return split;
    }

    /**
     * A command that ends in what reads as a range but is none ({@code TRD 1000001 -5}, {@code 1.5M}, {@code FROM yesterday}, {@code DIFF
     * DIFF}, {@code 30 30}) after one entity the caller may open fails with {@code DRS-4022} (or {@code DRS-4021} for a number of days past
     * the limit) and says what to type, as the history endpoint does for the same range (QA1-03): never today's view as if the range was
     * not typed, never a search that drops the id. Text that is itself an entity ({@code BOND UST 0}) or a search is left alone.
     */
    private void refuseBadRange(String text, AsOf asOf, Principal principal) {
        if (SearchQuery.looksLikeSearch(text)) {
            return;
        }
        int maxDays = historyProps == null ? 366 : historyProps.limits().maxDays();
        for (HistoryRange.Bad bad : HistoryRange.malformed(text, maxDays)) {
            String[] words = bad.command().split("\\s+", 2);
            boolean mnemonic = mnemonics.of(words[0].toUpperCase(Locale.ROOT)).isPresent();
            if (mnemonic ? words.length < 2 || SEARCHY.matcher(" " + words[1] + " ").find() : words.length != 1) {
                continue;                                           // TRD 0 is trade 0; TRD desk=rates 0 is a search
            }
            Optional<EntityRef> ref = parser.parse(bad.command());
            if (ref.isEmpty() || !entitlements.mayOpen(principal, ref.get().kind()) || !exists(ref.get(), asOf)) {
                continue;
            }
            Optional<EntityRef> whole = parser.parse(text);         // an id that itself ends so is opened as before
            if (whole.isPresent() && entitlements.mayOpen(principal, whole.get().kind()) && exists(whole.get(), asOf)) {
                return;
            }
            throw new com.ash.drishti.common.DrishtiException(bad.code(), bad.message());
        }
    }

    private com.ash.drishti.engine.history.HistoryProperties historyProps;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void historyProps(com.ash.drishti.engine.history.HistoryProperties p) {
        this.historyProps = p;
    }

    /** What makes the rest of a command a search rather than one entity: a wildcard or a field operator. */
    private static final java.util.regex.Pattern SEARCHY = java.util.regex.Pattern.compile(
            "(?i)(\\*|!=|<>|<=|>=|==|=|<|>|\\scontains\\s|\\sstartswith\\s|\\smatches\\s|\\smatchescase\\s|^\\s*not\\s|^\\s*\\()");

    private ApiDtos.CommandResponse resolvePlain(String text, AsOf asOf, Principal principal) {
        boolean pick = SearchQuery.looksLikePick(text);
        Optional<EntityRef> named = pick ? Optional.empty() : parser.parse(text);
        String head = text.split("\\s+", 2)[0];
        boolean listable = mnemonics.of(head.toUpperCase(Locale.ROOT)).isPresent();
        mnemonics.of(head.toUpperCase(Locale.ROOT)).ifPresent(m -> entitlements.requireActive(m.kind()));   // a command turned off: DRS-1016
        if (listable) {
            edition.requireCode(head);                                // a command the edition hides: DRS-4023
        }
        if (!listable && !text.contains(" ")) {                       // MKT, market-data: the pack's overview
            Optional<String> pack = packs.byCodeOrName(text);
            if (pack.isPresent()) {
                return new ApiDtos.CommandResponse(null, null, null, null, pack.get());
            }
        }
        if (named.isPresent()) {
            EntityRef ref = named.get();
            entitlements.requireOpen(principal, ref.kind());
            edition.requireKind(ref.kind());
            if (!listable || exists(ref, asOf)) {
                return open(ref);
            }
        } else if (!listable) {
            parser.require(text);                                   // says what it cannot read
        }
        SearchQuery q = SearchQuery.pick(text);
        String kind = search.kindOf(q);
        entitlements.requireOpen(principal, kind);
        edition.requireKind(kind);
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

    /** The type-ahead: kinds the caller may not open left out, and for a caller with field masks no masked value shown or matched. */
    @GetMapping("/suggest")
    public List<Suggestion> suggest(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) Integer limit, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        List<Suggestion> local = masks.apply(principal, q, entitlements.filter(principal, suggestions.suggest(q, principal.user(), limit, asOf)), asOf)
                .stream().filter(s -> edition.kindShown(s.kind())).toList();      // the edition's hidden commands are never suggested
        // Drishti*net: the peers' suggestions too, within the short type-ahead budget; a slow peer is simply left out
        com.ash.drishti.server.net.SearchScatter sc = scatter == null ? null : scatter.getIfAvailable();
        if (sc == null || q.isBlank()) {
            return local;
        }
        List<String> peers = sc.suggestTargets(principal);
        if (peers.isEmpty()) {
            return local;
        }
        String path = "/api/v1/command/suggest?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8) + (limit == null ? "" : "&limit=" + limit);
        var shares = sc.ask(peers, path, asOf, principal, sc.suggestBudget(), "suggest", null);
        List<Suggestion> merged = sc.mergeSuggestions(local, shares, limit == null ? Math.max(local.size(), 10) : limit, q);
        // this node's masks and kinds again on what the peers suggested, for the kinds whose packs are here (masks twice)
        // a command turned off is off on every node (the switches are the cluster's): its kind is never suggested, whoever holds it
        return masks.apply(principal, q, merged.stream().filter(s -> s.kind() == null || packAccess == null
                || !packAccess.commandOff(s.kind()) && edition.kindShown(s.kind())
                && (packAccess.ownerOf(s.kind()) == null || entitlements.mayOpen(principal, s.kind()))).toList(), asOf);
    }

    private org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.net.SearchScatter> scatter;
    private com.ash.drishti.server.security.PackAccess packAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void scatter(org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.net.SearchScatter> s, com.ash.drishti.server.security.PackAccess p) {
        this.scatter = s;
        this.packAccess = p;
    }

    /** The caller's recent commands, newest first (↑ on the command line). */
    @GetMapping("/history")
    public List<String> history(@RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return memory.history(principal.user());
    }

    /** The caller's aliases: short words for longer commands. */
    @GetMapping("/aliases")
    public Map<String, String> aliases(@RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return memory.aliases(principal.user());
    }

    /** Replaces the caller's aliases ({@code {"MYBOOK": "BOOK BOOK-RATES-1"}}); a mnemonic or pack code is not free. */
    @org.springframework.web.bind.annotation.PutMapping("/aliases")
    public Map<String, String> setAliases(@RequestBody Map<String, String> aliases, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return memory.setAliases(principal.user(), aliases, name -> mnemonics.of(name).isPresent() || packs.byCodeOrName(name).isPresent());
    }
}
