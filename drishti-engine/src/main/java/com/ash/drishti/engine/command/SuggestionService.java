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
package com.ash.drishti.engine.command;

import com.ash.drishti.api.EntityHit;
import com.ash.drishti.engine.source.SourceRouter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Type-ahead for the command line (ARCHITECTURE §7a). What it offers depends on what has been typed:
 * <ul>
 *   <li>nothing: the user's recent entities;</li>
 *   <li>one partial word: matching mnemonics, recent entities and entities of any kind;</li>
 *   <li>a mnemonic and more: entities of that mnemonic's kind matching the rest.</li>
 * </ul>
 * Sources are searched in parallel under a time budget; slow ones are left out rather than waited for.
 */
public final class SuggestionService {

    private final Mnemonics mnemonics;
    private final SourceRouter router;
    private final RecentEntities recents;
    private final Duration budget;
    private final int defaultLimit;

    public SuggestionService(Mnemonics mnemonics, SourceRouter router, RecentEntities recents, CommandsProperties props) {
        this.mnemonics = mnemonics;
        this.router = router;
        this.recents = recents;
        this.budget = props.suggestBudget();
        this.defaultLimit = props.suggestLimit();
    }

    public List<Suggestion> suggest(String query, String user, Integer limit) {
        int max = limit == null || limit <= 0 ? defaultLimit : Math.min(limit, 50);
        String q = query == null ? "" : query.replaceAll("(?i)<\\s*GO\\s*>", "");
        String trimmed = q.trim();
        Map<String, Suggestion> out = new LinkedHashMap<>();
        if (trimmed.isEmpty()) {
            recents.of(user).forEach(h -> add(out, entity("recent", h)));
            return cap(out, max);
        }
        String[] parts = trimmed.split("\\s+", 2);
        var m = mnemonics.of(parts[0]);
        if (m.isPresent() && (parts.length > 1 || q.endsWith(" "))) {
            String rest = parts.length > 1 ? parts[1] : "";
            String kind = m.get().kind();
            recents.of(user).stream().filter(h -> h.ref().kind().equals(kind) && matches(h, rest))
                    .forEach(h -> add(out, entity("recent", h)));
            router.search(kind, rest, max, budget).forEach(h -> add(out, entity("entity", h)));
            return cap(out, max);
        }
        String word = parts[0].toUpperCase(Locale.ROOT);
        mnemonics.all().forEach((code, def) -> {
            if (code.startsWith(word)) {
                add(out, new Suggestion("mnemonic", code, def.kind(), null, code, def.label(), code + " "));
            }
        });
        recents.of(user).stream().filter(h -> matches(h, trimmed)).forEach(h -> add(out, entity("recent", h)));
        router.search(null, trimmed, max, budget).forEach(h -> add(out, entity("entity", h)));
        return cap(out, max);
    }

    private static boolean matches(EntityHit h, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.isEmpty() || h.ref().id().toLowerCase(Locale.ROOT).contains(t) || h.subtitle().toLowerCase(Locale.ROOT).contains(t);
    }

    private Suggestion entity(String type, EntityHit h) {
        String code = mnemonics.codeFor(h.ref().kind());
        return new Suggestion(type, code, h.ref().kind(), h.ref().id(), h.title(), h.subtitle(),
                (code == null ? "" : code + " ") + h.ref().id());
    }

    private static void add(Map<String, Suggestion> out, Suggestion s) {
        String key = s.id() == null ? "m:" + s.mnemonic() : s.kind() + "/" + s.id();
        out.putIfAbsent(key, s);
    }

    private static List<Suggestion> cap(Map<String, Suggestion> out, int max) {
        return new ArrayList<>(out.values()).subList(0, Math.min(max, out.size()));
    }
}
