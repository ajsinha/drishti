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
        return suggest(query, user, limit, com.ash.drishti.api.AsOf.LATEST);
    }

    /** Suggestions for the command line; entity hits come from the sources as of the business date. */
    public List<Suggestion> suggest(String query, String user, Integer limit, com.ash.drishti.api.AsOf asOf) {
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
            ranges(parts[0].toUpperCase(Locale.ROOT), kind, rest, q.endsWith(" "), user, asOf).forEach(s -> add(out, s));
            recents.of(user).stream().filter(h -> h.ref().kind().equals(kind) && matches(h, rest))
                    .forEach(h -> add(out, entity("recent", h)));
            router.search(kind, rest, max, budget, asOf).forEach(h -> add(out, entity("entity", h)));
            return cap(out, max);
        }
        String word = parts[0].toUpperCase(Locale.ROOT);
        mnemonics.all().forEach((code, def) -> {
            if (code.startsWith(word)) {
                add(out, new Suggestion("mnemonic", code, def.kind(), null, code, def.label(), code + " "));
            }
        });
        if (m.isPresent()) {                    // TRD alone: trades, not every id that happens to contain "trd"
            String kind = m.get().kind();
            recents.of(user).stream().filter(h -> h.ref().kind().equals(kind)).forEach(h -> add(out, entity("recent", h)));
            router.search(kind, "", max, budget, asOf).forEach(h -> add(out, entity("entity", h)));
            return cap(out, max);
        }
        recents.of(user).stream().filter(h -> matches(h, trimmed)).forEach(h -> add(out, entity("recent", h)));
        router.search(null, trimmed, max, budget, asOf).forEach(h -> add(out, entity("entity", h)));
        return cap(out, max);
    }

    /** The ranges offered after an entity ({@code TRD 1000015 } gives {@code 30}, {@code 3M}, {@code YTD}, {@code 30 DIFF}). */
    static final List<String> RANGES = List.of("30", "3M", "YTD", "30 DIFF");

    private static final java.util.regex.Pattern PARTIAL_RANGE = java.util.regex.Pattern.compile(
            "(?i)\\d{1,4}[DWMY]?|Y|YT|YTD|M|MT|MTD|Q|QT|QTD|30\\s+D|30\\s+DI|30\\s+DIF|30\\s+DIFF");

    /**
     * History ranges for {@code <MNEMONIC> <ID> } (a space typed after one entity's id) and for a range being typed
     * ({@code TRD 1000015 3}): the ranges that start with what was typed, first in the list (DATA_ARCHITECTURE.md › History queries: an entity over time, section 4.4).
     * Nothing when the id does not name one entity of the kind.
     */
    private List<Suggestion> ranges(String code, String kind, String rest, boolean spaced, String user, com.ash.drishti.api.AsOf asOf) {
        String r = rest.trim();
        if (r.isEmpty()) {
            return List.of();
        }
        String[] w = r.split("\\s+", 2);
        String typed;
        if (w.length == 1 && spaced) {
            typed = "";
        } else if (w.length == 2 && PARTIAL_RANGE.matcher(w[1]).matches()) {
            typed = w[1].replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        } else {
            return List.of();
        }
        String id = entityId(kind, w[0], user, asOf);
        if (id == null) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        for (String range : RANGES) {
            if (range.startsWith(typed) && !range.equals(typed)) {
                out.add(new Suggestion("range", code, kind, id, range, describe(range), code + " " + id + " " + range));
            }
        }
        if (out.isEmpty() && !typed.isEmpty()) {                    // TRD 1000015 90: the range typed itself, explained
            try {
                HistoryRange.trailing(code + " " + id + " " + typed).ifPresent(s -> out.add(new Suggestion("range", code, kind, id,
                        typed, describe(typed), code + " " + id + " " + typed)));
            } catch (RuntimeException notYet) {
                // a range still being typed
            }
        }
        return out;
    }

    private static String describe(String range) {
        return HistoryRange.trailing("X Y " + range).map(s -> s.range().describe() + (s.diff() ? ": only what changed, day by day" : ""))
                .orElse(range);
    }

    /** The id of the one entity of the kind that {@code word} names (any case), or null. */
    private String entityId(String kind, String word, String user, com.ash.drishti.api.AsOf asOf) {
        for (EntityHit h : recents.of(user)) {
            if (h.ref().kind().equals(kind) && h.ref().id().equalsIgnoreCase(word)) {
                return h.ref().id();
            }
        }
        for (EntityHit h : router.search(kind, word, 5, budget, asOf)) {
            if (h.ref().id().equalsIgnoreCase(word)) {
                return h.ref().id();
            }
        }
        return null;
    }

    /**
     * The text entity suggestions for {@code query} are matched against: what follows a mnemonic ({@code TRD MX-2} gives
     * {@code MX-2}), nothing for a mnemonic alone or an empty query, else the word typed.
     */
    public String matchText(String query) {
        String trimmed = (query == null ? "" : query.replaceAll("(?i)<\\s*GO\\s*>", "")).trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+", 2);
        return mnemonics.of(parts[0]).isPresent() ? (parts.length > 1 ? parts[1] : "") : trimmed;
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
        String key = s.id() == null ? "m:" + s.mnemonic() : s.kind() + "/" + s.id() + ("range".equals(s.type()) ? " " + s.title() : "");
        out.putIfAbsent(key, s);
    }

    private static List<Suggestion> cap(Map<String, Suggestion> out, int max) {
        return new ArrayList<>(out.values()).subList(0, Math.min(max, out.size()));
    }
}
