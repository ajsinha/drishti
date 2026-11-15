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
package com.ash.drishti.server.live;

import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A page's live mark for a kind (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 9.2, option 1): the state as a pill and, beside it,
 * in the one fixed list of words, what the rows are: the lake's snapshot they stand on, how many changes since, how recent. The words:
 * {@code LIVE · base 13 Nov close + 412 changes}, {@code LIVE · catching up}, {@code PAUSED · waiting for the broker},
 * {@code PAUSED · by an administrator}, {@code STALE · nothing since 10:42}; a kind with no feed switched on has no mark. "2 s ago" is the
 * page's, from {@code lastMessage}. Rows a feed gave carry the row dot (the set's {@code live} flag per row).
 */
@Component
public final class LiveMarks {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final LiveFeeds feeds;
    private final SourceRouter router;
    private final BusinessDates dates;

    public LiveMarks(LiveFeeds feeds, SourceRouter router, BusinessDates dates) {
        this.feeds = feeds;
        this.router = router;
        this.dates = dates;
    }

    /** The kind's mark; empty when no feed of it is on. */
    public Optional<Map<String, Object>> of(String kind) {
        List<Map<String, Object>> mine = feeds.ofKind(kind).stream()
                .filter(f -> !List.of("OFF", "NOT CONFIGURED").contains(String.valueOf(f.get("state")))).toList();
        if (mine.isEmpty()) {
            return Optional.empty();
        }
        LocalDate base = dates.calendar().previous(dates.current());
        long changes = router.liveSources(kind).values().stream().filter(l -> l.feed() != null)
                .mapToLong(l -> l.feed().state().entries(kind).count()).sum();
        String last = mine.stream().map(f -> (String) f.get("lastMessage")).filter(x -> x != null).max(String::compareTo).orElse(null);
        String state = worst(mine);
        String upTo = last == null ? "" : " up to " + TIME.withZone(dates.zone()).format(java.time.Instant.parse(last));
        String rows = "base " + DAY.format(base) + " close + " + String.format(Locale.ROOT, "%,d", changes) + (changes == 1 ? " change" : " changes");
        String words = switch (state) {
            case "WAITING", "DOWN" -> "PAUSED · waiting for the broker · showing " + rows + upTo;
            case "PAUSED" -> "PAUSED · by an administrator · showing " + rows + upTo;
            case "STALE" -> "STALE · nothing since " + (last == null ? "the start" : TIME.withZone(dates.zone()).format(java.time.Instant.parse(last)));
            case "CATCHING UP" -> "LIVE · catching up";
            default -> "LIVE · " + rows;
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", switch (state) {
            case "WAITING", "DOWN", "PAUSED" -> "PAUSED";
            default -> state.equals("CATCHING UP") ? "LIVE" : state;
        });
        m.put("words", words);
        m.put("base", base.toString());
        m.put("changes", changes);
        m.put("lastMessage", last);
        m.put("perMinute", mine.stream().mapToLong(f -> f.get("perMinute") instanceof Number n ? n.longValue() : 0).sum());
        m.put("feeds", mine.stream().map(f -> f.get("connector")).toList());
        return Optional.of(m);
    }

    /** The state that needs the most attention among the kind's feeds that are on. */
    private static String worst(List<Map<String, Object>> feeds) {
        List<String> order = List.of("DOWN", "WAITING", "PAUSED", "DEGRADED", "STALE", "CATCHING UP", "STANDBY", "LIVE");
        return feeds.stream().map(f -> String.valueOf(f.get("state"))).min(java.util.Comparator.comparingInt(s -> order.indexOf(s) < 0 ? order.size() : order.indexOf(s)))
                .map(s -> s.equals("DEGRADED") || s.equals("STANDBY") ? "LIVE" : s).orElse("LIVE");
    }
}
