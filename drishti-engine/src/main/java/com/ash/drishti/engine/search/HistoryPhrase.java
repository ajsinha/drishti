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
package com.ash.drishti.engine.search;

import com.ash.drishti.engine.command.HistoryRange;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Plain words for a history command (HISTORY_QUERIES.md section 15.5): after the kind word, one id and a time phrase, read
 * deterministically, no model. {@code trade 1000015 last month} is {@code TRD 1000015 FROM 2026-09-01 TO 2026-09-30};
 * {@code equity EQ-CSCA last 3 months} is {@code EQ EQ-CSCA 3M}; {@code what changed on trade 1000015 this week} ends in {@code DIFF}.
 * <p>Time phrases: {@code last|past N days} (N data days), {@code last|past N weeks|months|years}, {@code last week|month|year},
 * {@code this week|month|quarter|year}, {@code ytd|mtd|qtd}, {@code since <date>}, {@code from <date> to <date>},
 * {@code between <date> and <date>}.
 */
public final class HistoryPhrase {

    /**
     * @param id the entity's id, as typed (upper case)
     * @param range the range as a command writes it ({@code 30}, {@code 3M}, {@code FROM 2026-09-01 TO 2026-09-30})
     * @param diff whether the words asked what changed
     * @param idWords the words that named the id
     * @param timeWords the words of the time phrase
     * @param meaning the range in words ({@code the last 3 months})
     * @param ignored words left over that were not understood
     */
    public record Read(String id, String range, boolean diff, String idWords, String timeWords, String meaning, List<String> ignored) {}

    private static final Set<String> FILLER = Set.of("show", "me", "the", "of", "for", "on", "in", "over", "during", "history", "a", "an",
            "what", "how", "did", "has", "have", "is", "was", "please", "give", "get", "see", "its", "their", "to", "with");
    private static final Set<String> CHANGED = Set.of("changed", "changes", "change", "diff", "moved", "moves");
    private static final Pattern N = Pattern.compile("[1-9]\\d{0,3}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ID = Pattern.compile("(?=.*[0-9-])[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private HistoryPhrase() {}

    /**
     * Reads the words after the kind word. Empty unless there is a time phrase and a word that can be an id (it has a digit or a
     * dash, and is not a value of one of the kind's fields, such as a book): every other phrase stays a search.
     *
     * @param isValue whether a word is a value of one of the kind's fields ({@code BOOK-RATES-3}), so not an id
     */
    public static Optional<Read> read(List<String> words, LocalDate today, java.util.function.Predicate<String> isValue) {
        return read(words, today, isValue, w -> Optional.empty());
    }

    /**
     * As {@link #read(List, LocalDate, java.util.function.Predicate)}, and a word that names one entity of the kind ({@code idOf}: its id
     * in any case, or the end of its id after a dash, {@code audusd} for {@code FX-AUDUSD}) is the id even when it is also a value of a
     * field ({@code EQ-CSCA} is the equity's TICKER too; QA1-08).
     *
     * @param idOf the id of the one entity of the kind a word names, or empty
     */
    public static Optional<Read> read(List<String> words, LocalDate today, java.util.function.Predicate<String> isValue,
            java.util.function.Function<String, Optional<String>> idOf) {
        List<String> lw = words.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
        for (int i = 0; i < lw.size(); i++) {
            Optional<Time> t = time(lw, i, today);
            if (t.isEmpty()) {
                continue;
            }
            Time time = t.get();
            String id = null;
            String idWord = null;
            boolean diff = false;
            List<String> ignored = new ArrayList<>();
            for (int k = 0; k < lw.size(); k++) {
                if (k >= i && k < time.end) {
                    continue;
                }
                String w = lw.get(k);
                Optional<String> named = id != null || CHANGED.contains(w) || FILLER.contains(w) || DATE.matcher(w).matches() ? Optional.empty()
                        : idOf.apply(words.get(k));
                if (CHANGED.contains(w)) {
                    diff = true;
                } else if (named.isPresent()) {
                    id = named.get();
                    idWord = words.get(k);
                } else if (id == null && ID.matcher(words.get(k)).matches() && !DATE.matcher(w).matches() && !isValue.test(words.get(k))) {
                    id = words.get(k).toUpperCase(Locale.ROOT);
                    idWord = words.get(k);
                } else if (!FILLER.contains(w)) {
                    ignored.add(words.get(k));
                }
            }
            if (id == null || ignored.stream().anyMatch(isValue)) {
                return Optional.empty();                                  // a field's value too: a search with a date, not one entity
            }
            String meaning = HistoryRange.trailing("X Y " + time.range).map(s -> s.range().describe()).orElse(time.range);
            return Optional.of(new Read(id, time.range, diff, idWord, String.join(" ", words.subList(i, time.end)), meaning, ignored));
        }
        return Optional.empty();
    }

    private record Time(String range, int end) {}

    private static Optional<Time> time(List<String> w, int i, LocalDate today) {
        String a = w.get(i);
        String b = i + 1 < w.size() ? w.get(i + 1) : "";
        String c = i + 2 < w.size() ? w.get(i + 2) : "";
        String d = i + 3 < w.size() ? w.get(i + 3) : "";
        if ((a.equals("last") || a.equals("past")) && N.matcher(b).matches()) {
            String unit = c.replaceAll("s$", "");
            return switch (unit) {
                case "day", "business-day" -> Optional.of(new Time(b, i + 3));
                case "week" -> Optional.of(new Time(b + "W", i + 3));
                case "month" -> Optional.of(new Time(b + "M", i + 3));
                case "year" -> Optional.of(new Time(b + "Y", i + 3));
                default -> Optional.empty();
            };
        }
        if (a.equals("last") || a.equals("previous")) {
            switch (b) {
                case "week" -> {
                    LocalDate monday = today.with(DayOfWeek.MONDAY).minusWeeks(1);
                    return Optional.of(new Time(between(monday, monday.plusDays(4)), i + 2));
                }
                case "month" -> {
                    LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                    return Optional.of(new Time(between(first, first.plusMonths(1).minusDays(1)), i + 2));
                }
                case "year" -> {
                    LocalDate first = today.withDayOfYear(1).minusYears(1);
                    return Optional.of(new Time(between(first, first.plusYears(1).minusDays(1)), i + 2));
                }
                default -> { }
            }
        }
        if (a.equals("this")) {
            switch (b) {
                case "week" -> {
                    return Optional.of(new Time("FROM " + today.with(DayOfWeek.MONDAY), i + 2));
                }
                case "month" -> {
                    return Optional.of(new Time("MTD", i + 2));
                }
                case "quarter" -> {
                    return Optional.of(new Time("QTD", i + 2));
                }
                case "year" -> {
                    return Optional.of(new Time("YTD", i + 2));
                }
                default -> { }
            }
        }
        if (a.equals("ytd") || a.equals("mtd") || a.equals("qtd")) {
            return Optional.of(new Time(a.toUpperCase(Locale.ROOT), i + 1));
        }
        if (a.equals("since") && date(b) != null) {
            return Optional.of(new Time("FROM " + date(b), i + 2));
        }
        if ((a.equals("from") && c.equals("to") || a.equals("between") && c.equals("and")) && date(b) != null && date(d) != null) {
            LocalDate x = date(b);
            LocalDate y = date(d);
            return Optional.of(new Time(between(x.isAfter(y) ? y : x, x.isAfter(y) ? x : y), i + 4));
        }
        return Optional.empty();
    }

    private static String between(LocalDate from, LocalDate to) {
        return "FROM " + from + " TO " + to;
    }

    private static LocalDate date(String s) {
        if (!DATE.matcher(s).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
