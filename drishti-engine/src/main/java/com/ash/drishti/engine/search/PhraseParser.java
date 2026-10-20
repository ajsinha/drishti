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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a phrase into a structured search, deterministically: no model, no guessing beyond the vocabulary the server
 * has (its kinds, and each kind's fields with their types and the values seen in its documents). Every part of the
 * phrase it used is explained, and every word it did not understand is returned, so the person sees the query before
 * it runs and can correct it.
 *
 * <pre>
 * live trades over 5m in BOOK-RATES-3, biggest first      TRD where status = 'Live' and mtm > 5000000 and book = 'BOOK-RATES-3' order by mtm desc
 * top 10 counterparties by exposure                         CPTY order by exposure desc limit 10
 * trades maturing before 2027 with negative mtm             TRD where maturityDate < '2027-01-01' and mtm < 0
 * usd trades with notional between 10m and 50m              TRD where currency = 'USD' and notional >= 10000000 and notional <= 50000000
 * </pre>
 */
public final class PhraseParser {

    /** What a kind is called. */
    public record KindWord(String kind, String mnemonic, String label) {}

    public enum Type {
        NUMBER, TEXT, DATE, BOOLEAN
    }

    /**
     * A field of a kind.
     *
     * @param path its path in the document ({@code mtm}, {@code counterparty.name})
     * @param label its words, lower case ({@code maturity date})
     * @param values text values seen in the documents, lower case to as written
     */
    public record Field(String path, String label, Type type, Map<String, String> values) {}

    /** Everything the parser knows. */
    public interface Vocabulary {
        /** Every word or phrase that names a kind, lower case and singular or plural, to the kind. */
        Map<String, KindWord> kinds();

        List<Field> fields(String kind);

        /**
         * The id of the one entity of {@code kind} that {@code word} names: its id in any case, or the one id that ends with
         * {@code -word} ({@code audusd} for {@code FX-AUDUSD}); empty when none or several (QA1-08).
         */
        default Optional<String> entityId(String kind, String word) {
            return Optional.empty();
        }

        /** The kind of the one entity whose id {@code word} is, among the kinds this vocabulary names; empty when none or several. */
        default Optional<KindWord> kindOfEntity(String word) {
            return Optional.empty();
        }
    }

    /** One part of the phrase and what it became. */
    public record Step(String words, String meaning) {}

    /**
     * @param query the structured search, or null when the phrase names no kind
     * @param steps how each part was read
     * @param ignored words that were not understood (not in the query)
     * @param problem why there is no query, or null
     * @param command a history command to open instead of a search ({@code TRD 1000015 FROM 2026-09-01 TO 2026-09-30}), or null
     */
    public record Parsed(String query, String kind, List<Step> steps, List<String> ignored, String problem, String command) {

        public Parsed(String query, String kind, List<Step> steps, List<String> ignored, String problem) {
            this(query, kind, steps, ignored, problem, null);
        }
    }

    private static final Set<String> FILLER = Set.of("show", "me", "all", "the", "list", "find", "get", "give", "of", "which", "that", "are",
            "is", "with", "where", "and", "please", "a", "an", "whose", "have", "has", "for", "in", "on", "at", "by", "from", "first", "to");
    private static final Set<String> MORE = Set.of("over", "above", "more", "greater", "exceeding", "exceeds", ">", "bigger", "larger");
    private static final Set<String> LESS = Set.of("under", "below", "less", "lower", "<", "smaller");
    private static final Set<String> BIG = Set.of("biggest", "largest", "highest", "top", "most", "greatest", "newest", "latest");
    private static final Set<String> SMALL = Set.of("smallest", "lowest", "least", "bottom", "oldest", "earliest");
    private static final Set<String> MEASURES = Set.of("mtm", "notional", "amount", "value", "balance", "exposure", "marketvalue", "pnl");
    private static final Pattern NUMBER = Pattern.compile("^([-+−]?\\d[\\d,]*(?:\\.\\d+)?)(k|m|mm|mn|bn|b|million|billion|thousand)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("^(19|20)\\d\\d$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final Vocabulary vocab;
    private final java.util.function.Supplier<java.time.LocalDate> today;
    private int maxDays = 366;

    public PhraseParser(Vocabulary vocab) {
        this(vocab, java.time.LocalDate::now);
    }

    /** @param today the date "last month" and "this week" count from (the business date) */
    public PhraseParser(Vocabulary vocab, java.util.function.Supplier<java.time.LocalDate> today) {
        this.vocab = vocab;
        this.today = today;
    }

    /** The most business days a history may span here, for the message of a number of days past it ({@code DRS-4021}). */
    public PhraseParser maxDays(int days) {
        this.maxDays = days;
        return this;
    }

    private static final Set<String> TIME_WORDS = Set.of("since", "from", "until", "till", "between");

    /**
     * A bare id followed by what reads as a range but is none ({@code 1000015 -5}, {@code 1000015 since yesterday}): {@code DRS-4022} (or
     * {@code DRS-4021}) saying what to type, as {@code TRD 1000015 -5} on the command line (QA1-203), never "say what to look for".
     */
    private void refuseBadRange(KindWord kind, String id, String typed, List<String> rest) {
        String command = kind.mnemonic() + " " + id;
        for (com.ash.drishti.engine.command.HistoryRange.Bad b
                : com.ash.drishti.engine.command.HistoryRange.malformed(command + " " + String.join(" ", rest), maxDays)) {
            if (b.command().equalsIgnoreCase(command)) {
                throw new com.ash.drishti.common.DrishtiException(b.code(), b.message());
            }
        }
        String first = rest.get(0).toLowerCase(Locale.ROOT);
        if (rest.size() >= 2 && TIME_WORDS.contains(first) && !isDate(rest.get(1))) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.HISTORY_BAD_RANGE, "'" + rest.get(1)
                    + "' is not a date: write it as yyyy-mm-dd, for example " + typed + " " + first + " 2026-09-01");
        }
    }

    /**
     * A bare id first: when it names one entity (of one kind) and what follows is a range as a command writes it ({@code 30}, {@code 3M},
     * {@code FROM 2026-10-01}) or a time phrase, that entity's history; empty otherwise.
     */
    private Optional<Parsed> bareHistory(List<String> words, List<Step> steps) {
        if (words.size() < 2 || DATE.matcher(words.get(0)).matches() || !words.get(0).matches(".*[0-9-].*")) {
            return Optional.empty();
        }
        Optional<KindWord> k = vocab.kindOfEntity(words.get(0));
        if (k.isEmpty()) {
            return Optional.empty();
        }
        KindWord kind = k.get();
        String id = vocab.entityId(kind.kind(), words.get(0)).orElse(words.get(0));
        String rest = String.join(" ", words.subList(1, words.size()));
        List<Step> out = new ArrayList<>(steps);
        Optional<com.ash.drishti.engine.command.HistoryRange.Split> range;
        try {
            range = com.ash.drishti.engine.command.HistoryRange.trailing(kind.mnemonic() + " " + id + " " + rest);
        } catch (RuntimeException e) {
            range = Optional.empty();
        }
        if (range.isPresent() && range.get().command().equalsIgnoreCase(kind.mnemonic() + " " + id)) {
            String command = kind.mnemonic() + " " + id + " " + range.get().range().render() + (range.get().diff() ? " DIFF" : "");
            out.add(new Step(words.get(0), "the " + kind.label().toLowerCase(Locale.ROOT) + " " + id));
            out.add(new Step(rest, range.get().range().describe() + (range.get().diff() ? ", only what changed" : "")));
            return Optional.of(new Parsed(command, kind.kind(), out, List.of(), null, command));
        }
        String kindName = kind.kind();
        List<Field> kindFields = vocab.fields(kindName);
        Optional<HistoryPhrase.Read> h = HistoryPhrase.read(words, today.get(), w -> valueOf(w, kindFields).isPresent(),
                w -> w.equalsIgnoreCase(words.get(0)) ? Optional.of(id) : Optional.empty());
        if (h.isEmpty() || !h.get().idWords().equalsIgnoreCase(words.get(0))) {
            refuseBadRange(kind, id, words.get(0), words.subList(1, words.size()));
            return Optional.empty();
        }
        String command = kind.mnemonic() + " " + id + " " + h.get().range() + (h.get().diff() ? " DIFF" : "");
        out.add(new Step(words.get(0), "the " + kind.label().toLowerCase(Locale.ROOT) + " " + id));
        out.add(new Step(h.get().timeWords(), h.get().meaning() + (h.get().diff() ? ", only what changed" : "")));
        return Optional.of(new Parsed(command, kind.kind(), out, h.get().ignored(), null, command));
    }

    public Parsed parse(String phrase) {
        List<String> words = tokens(phrase);
        List<Step> steps = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        // 1. the kind: the first word or two that name one
        KindWord kind = null;
        for (int i = 0; i < words.size() && kind == null; i++) {
            for (int n = Math.min(3, words.size() - i); n >= 1 && kind == null; n--) {
                String w = String.join(" ", words.subList(i, i + n)).toLowerCase(Locale.ROOT);
                KindWord k = vocab.kinds().get(w);
                if (k != null) {
                    kind = k;
                    steps.add(new Step(String.join(" ", words.subList(i, i + n)), k.label() + " (" + k.mnemonic() + ")"));
                    words = new ArrayList<>(words);
                    for (int r = 0; r < n; r++) {
                        words.remove(i);
                    }
                }
            }
        }
        if (kind == null && !words.isEmpty()) {
            // QA1-08: a bare id that names one entity, then a range or a time phrase ("1000015 30", "BOOK-RATES-3 last week")
            Optional<Parsed> bare = bareHistory(words, steps);
            if (bare.isPresent()) {
                return bare.get();
            }
        }
        if (kind == null) {
            // named as the vocabulary names them, with their mnemonics: no guessed plurals, no list brackets
            return new Parsed(null, null, steps, List.of(), "say what to look for: a kind such as "
                    + String.join(", ", vocab.kinds().values().stream().map(k -> k.label().toLowerCase(Locale.ROOT) + " (" + k.mnemonic() + ")")
                            .distinct().sorted().limit(4).toList()));
        }
        // 2. kind word, id, time phrase: the entity over time (HISTORY_QUERIES.md section 15.5), not a search
        List<Field> kindFields = vocab.fields(kind.kind());
        String kindName = kind.kind();
        Optional<HistoryPhrase.Read> history = HistoryPhrase.read(words, today.get(), w -> valueOf(w, kindFields).isPresent(),
                w -> vocab.entityId(kindName, w));
        if (history.isPresent()) {
            HistoryPhrase.Read h = history.get();
            String command = kind.mnemonic() + " " + h.id() + " " + h.range() + (h.diff() ? " DIFF" : "");
            steps.add(new Step(h.idWords(), "the " + kind.label().toLowerCase(Locale.ROOT) + " " + h.id()));
            steps.add(new Step(h.timeWords(), h.meaning() + (h.diff() ? ", only what changed" : "")));
            return new Parsed(command, kind.kind(), steps, h.ignored(), null, command);
        }
        // 3. kind word and one id, no range: that entity (fx audusd is FX FX-AUDUSD; QA1-207)
        List<String> content = words.stream().filter(w -> !FILLER.contains(w.toLowerCase(Locale.ROOT))).toList();
        if (content.size() == 1 && content.get(0).length() > 1) {
            Optional<String> one = vocab.entityId(kindName, content.get(0));
            if (one.isPresent()) {
                String command = kind.mnemonic() + " " + one.get();
                steps.add(new Step(content.get(0), "the " + kind.label().toLowerCase(Locale.ROOT) + " " + one.get()));
                return new Parsed(command, kind.kind(), steps, List.of(), null, command);
            }
        }
        List<Field> fields = vocab.fields(kind.kind());
        String timeWords = null;
        String valueSeen = null;
        List<String> conditions = new ArrayList<>();
        String orderBy = null;
        boolean descending = true;
        Integer limit = null;
        int i = 0;
        while (i < words.size()) {
            String w = words.get(i);
            String lw = w.toLowerCase(Locale.ROOT);
            // a time phrase a search cannot use (it reads one business date): named, never dropped; "since <date>" is a date condition
            int timeEnd = lw.equals("since") ? -1 : HistoryPhrase.timeEnd(words.stream().map(x -> x.toLowerCase(Locale.ROOT)).toList(), i,
                    today.get());
            if (timeEnd > i) {
                timeWords = String.join(" ", words.subList(i, timeEnd));
                i = timeEnd;
                continue;
            }
            // a field named here, possibly followed by a comparison
            Optional<Match> f = field(words, i, fields);
            int j = f.map(m -> m.end).orElse(i);
            Field named = f.map(m -> m.field).orElse(null);
            // signs: negative mtm, positive pnl
            if (lw.equals("negative") || lw.equals("positive") || lw.equals("losing") || lw.equals("profitable")) {
                Optional<Match> g = field(words, i + 1, fields);
                Field target = g.map(m -> m.field).orElse(measure(fields));
                if (target != null) {
                    String op = lw.equals("negative") || lw.equals("losing") ? "<" : ">";
                    conditions.add(target.path() + " " + op + " 0");
                    int end = g.map(m -> m.end).orElse(i + 1);
                    steps.add(new Step(String.join(" ", words.subList(i, end)), target.label() + " " + op + " 0"));
                    i = end;
                    continue;
                }
            }
            // between A and B
            int cmpAt = named != null ? j : i;
            String cw = cmpAt < words.size() ? words.get(cmpAt).toLowerCase(Locale.ROOT) : "";
            if (cw.equals("between") && cmpAt + 3 < words.size()) {
                Field target = named != null ? named : measure(fields);
                String a = value(words, cmpAt + 1, target);
                String b = words.get(cmpAt + 2).equalsIgnoreCase("and") ? value(words, cmpAt + 3, target) : null;
                if (target != null && a != null && b != null) {
                    conditions.add(target.path() + " >= " + a + " and " + target.path() + " <= " + b);
                    steps.add(new Step(String.join(" ", words.subList(i, cmpAt + 4)), target.label() + " from " + a + " to " + b));
                    i = cmpAt + 4;
                    continue;
                }
            }
            // over / under / before / after / maturing before …
            int k = cmpAt;
            if (k < words.size() && Set.of("maturing", "due", "expiring", "dated", "traded").contains(words.get(k).toLowerCase(Locale.ROOT))) {
                Field d = dateField(fields, words.get(k).toLowerCase(Locale.ROOT));
                if (d != null && named == null) {
                    named = d;
                }
                k++;
            }
            String op = null;
            int skip = 0;
            if (k < words.size()) {
                String c = words.get(k).toLowerCase(Locale.ROOT);
                String c2 = k + 1 < words.size() ? words.get(k + 1).toLowerCase(Locale.ROOT) : "";
                if (MORE.contains(c)) {
                    op = ">";
                    skip = c2.equals("than") ? 2 : 1;
                } else if (LESS.contains(c)) {
                    op = "<";
                    skip = c2.equals("than") ? 2 : 1;
                } else if (c.equals("at") && (c2.equals("least") || c2.equals("most"))) {
                    op = c2.equals("least") ? ">=" : "<=";
                    skip = 2;
                } else if (c.equals("before") || c.equals("after") || c.equals("since") || c.equals("until")) {
                    op = c.equals("before") || c.equals("until") ? "<" : ">=";
                    skip = 1;
                    if (named == null) {
                        named = dateField(fields, "");
                    }
                } else if ((c.equals("in") || c.equals("during")) && named != null && named.type() == Type.DATE && k + 1 < words.size()
                        && YEAR.matcher(words.get(k + 1)).matches()) {
                    String y = words.get(k + 1);
                    conditions.add(named.path() + " >= '" + y + "-01-01' and " + named.path() + " < '" + (Integer.parseInt(y) + 1) + "-01-01'");
                    steps.add(new Step(String.join(" ", words.subList(i, k + 2)), named.label() + " in " + y));
                    i = k + 2;
                    continue;
                }
            }
            if (op != null && k + skip < words.size()) {
                Field target = named != null ? named : measure(fields);
                String v = value(words, k + skip, target);
                if (target != null && v != null) {
                    if (target.type() == Type.DATE && op.equals(">=") && YEAR.matcher(words.get(k + skip)).matches()) {
                        v = "'" + (Integer.parseInt(words.get(k + skip)) + 1) + "-01-01'";        // after 2027: from 2028
                    }
                    conditions.add(target.path() + " " + op + " " + v);
                    steps.add(new Step(String.join(" ", words.subList(i, k + skip + 1)), target.label() + " " + op + " " + v));
                    i = k + skip + 1;
                    continue;
                }
            }
            // ordering: biggest/top N [by field], sorted by field [desc]
            if (BIG.contains(lw) || SMALL.contains(lw) || lw.equals("sorted") || lw.equals("ordered")) {
                int end = i + 1;
                if (end < words.size() && NUMBER.matcher(words.get(end)).matches() && !words.get(end).matches(".*[a-zA-Z].*")) {
                    limit = Integer.parseInt(words.get(end).replace(",", ""));
                    end++;
                }
                if (end < words.size() && words.get(end).equalsIgnoreCase("by")) {
                    end++;
                }
                Optional<Match> by = field(words, end, fields);
                Field target = by.map(m -> m.field).orElse(lw.equals("newest") || lw.equals("oldest") || lw.equals("latest") || lw.equals("earliest")
                        ? dateField(fields, "") : measure(fields));
                if (by.isPresent()) {
                    end = by.get().end;
                }
                if (end < words.size() && (words.get(end).equalsIgnoreCase("desc") || words.get(end).equalsIgnoreCase("asc"))) {
                    descending = words.get(end).equalsIgnoreCase("desc");
                    end++;
                } else {
                    descending = !SMALL.contains(lw);
                }
                if (end < words.size() && words.get(end).equalsIgnoreCase("first")) {
                    end++;
                }
                if (target != null) {
                    orderBy = target.path();
                    steps.add(new Step(String.join(" ", words.subList(i, end)), "sorted by " + target.label() + (descending ? ", largest first" : ", smallest first")
                            + (limit != null ? ", the first " + limit : "")));
                    i = end;
                    continue;
                }
            }
            // a value of a field: BOOK-RATES-3, live, usd (a field before it narrows which)
            Optional<Map.Entry<Field, String>> val = valueOf(named != null && j < words.size() ? words.get(j) : w, named == null ? fields : List.of(named));
            if (val.isPresent()) {
                Field target = val.get().getKey();
                conditions.add(target.path() + " = '" + val.get().getValue().replace("'", "\\'") + "'");
                valueSeen = val.get().getValue();
                int end = named != null ? j + 1 : i + 1;
                steps.add(new Step(String.join(" ", words.subList(i, end)), target.label() + " is " + val.get().getValue()));
                i = end;
                continue;
            }
            if (!FILLER.contains(lw) && !lw.equals("than")) {
                ignored.add(w);
            }
            i++;
        }
        if (timeWords != null) {
            steps.add(new Step(timeWords, "left out: a search reads one business date (the date box); for one entity over time name it with the "
                    + "time phrase alone (" + (valueSeen != null ? valueSeen : kind.label().toLowerCase(Locale.ROOT) + " <id>") + " " + timeWords + ")"));
            ignored.add(timeWords);
        }
        StringBuilder q = new StringBuilder(kind.mnemonic());
        if (!conditions.isEmpty()) {
            q.append(" where ").append(String.join(" and ", conditions));
        }
        if (orderBy != null) {
            q.append(" order by ").append(orderBy).append(descending ? " desc" : " asc");
        }
        if (limit != null) {
            q.append(" limit ").append(Math.max(1, Math.min(limit, SearchQuery.MAX_LIMIT)));
        }
        return new Parsed(q.toString(), kind.kind(), steps, ignored, null);
    }

    private record Match(Field field, int end) {}

    /** The longest run of words from {@code i} that names a field (its label, its name, or a unique prefix of it). */
    private static Optional<Match> field(List<String> words, int i, List<Field> fields) {
        for (int n = Math.min(4, words.size() - i); n >= 1; n--) {
            String w = String.join(" ", words.subList(i, i + n)).toLowerCase(Locale.ROOT);
            String compact = w.replace(" ", "");
            for (Field f : fields) {
                String name = f.path().toLowerCase(Locale.ROOT);
                if (f.label().equals(w) || name.equals(compact) || name.endsWith("." + compact)) {
                    return Optional.of(new Match(f, i + n));
                }
            }
        }
        if (i < words.size() && words.get(i).length() >= 4) {
            String w = words.get(i).toLowerCase(Locale.ROOT);
            List<Field> starts = fields.stream().filter(f -> f.path().toLowerCase(Locale.ROOT).startsWith(w) || f.label().startsWith(w)).toList();
            if (starts.size() == 1) {
                return Optional.of(new Match(starts.get(0), i + 1));
            }
        }
        return Optional.empty();
    }

    /** A number with its magnitude (5m, 2.5bn, 10 thousand), a year or a date, written for the field. */
    private static String value(List<String> words, int i, Field target) {
        if (i >= words.size()) {
            return null;
        }
        String w = words.get(i).replace("−", "-");
        if (target != null && target.type() == Type.DATE) {
            if (YEAR.matcher(w).matches()) {
                return "'" + w + "-01-01'";
            }
            return DATE.matcher(w).matches() ? "'" + w + "'" : null;
        }
        Matcher m = NUMBER.matcher(w);
        if (!m.matches()) {
            return null;
        }
        String unit = m.group(2);
        if (unit == null && i + 1 < words.size() && words.get(i + 1).matches("(?i)k|m|mm|mn|bn|million|billion|thousand")) {
            unit = words.get(i + 1);
            words.remove(i + 1);
        }
        BigDecimal n = new BigDecimal(m.group(1).replace(",", "").replace("+", ""));
        if (unit != null) {
            n = n.multiply(switch (unit.toLowerCase(Locale.ROOT)) {
                case "k", "thousand" -> BigDecimal.valueOf(1_000);
                case "m", "mm", "mn", "million" -> BigDecimal.valueOf(1_000_000);
                default -> BigDecimal.valueOf(1_000_000_000);
            });
        }
        return n.stripTrailingZeros().toPlainString();
    }

    /** A text field one of whose values this word is (a field given first narrows it). */
    private static Optional<Map.Entry<Field, String>> valueOf(String word, List<Field> fields) {
        String w = word.toLowerCase(Locale.ROOT);
        for (Field f : fields) {
            if (f.type() == Type.TEXT && f.values().containsKey(w)) {
                return Optional.of(Map.entry(f, f.values().get(w)));
            }
        }
        return Optional.empty();
    }

    /** The kind's main number: mtm, notional, amount … in that order, else its first number. */
    private static Field measure(List<Field> fields) {
        for (String m : List.of("mtm", "notional", "amount", "value", "balance", "exposure", "marketvalue", "pnl")) {
            for (Field f : fields) {
                if (f.type() == Type.NUMBER && f.path().toLowerCase(Locale.ROOT).replace(".", "").equals(m)) {
                    return f;
                }
            }
        }
        return fields.stream().filter(f -> f.type() == Type.NUMBER && MEASURES.stream().anyMatch(m -> f.path().toLowerCase(Locale.ROOT).contains(m)))
                .findFirst().orElse(fields.stream().filter(f -> f.type() == Type.NUMBER).findFirst().orElse(null));
    }

    /** The kind's date field for a verb (maturing: a maturity date), else its first date. */
    private static Field dateField(List<Field> fields, String verb) {
        String hint = switch (verb) {
            case "maturing", "due", "expiring" -> "matur";
            case "traded" -> "trade";
            default -> "";
        };
        return fields.stream().filter(f -> f.type() == Type.DATE && f.path().toLowerCase(Locale.ROOT).contains(hint)).findFirst()
                .orElse(fields.stream().filter(f -> f.type() == Type.DATE).findFirst().orElse(null));
    }

    private static boolean isDate(String w) {
        try {
            return DATE.matcher(w).matches() && java.time.LocalDate.parse(w) != null;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static List<String> tokens(String phrase) {
        List<String> out = new ArrayList<>();
        for (String t : (phrase == null ? "" : phrase).replaceAll(",(?!\\d)", " ").replace("?", " ").split("\\s+")) {
            if (!t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }

    /** Kind words for a kind: its mnemonic, kind name and label, singular and plural. */
    public static Map<String, KindWord> kindWords(List<KindWord> kinds) {
        Map<String, KindWord> out = new LinkedHashMap<>();
        for (KindWord k : kinds) {
            String label = k.label().replaceAll("\\s*\\(.*\\)", "").toLowerCase(Locale.ROOT).trim();
            for (String s : List.of(k.mnemonic().toLowerCase(Locale.ROOT), k.kind().replace('-', ' '), label)) {
                out.putIfAbsent(s, k);
                out.putIfAbsent(plural(s), k);
            }
        }
        return out;
    }

    static String plural(String s) {
        if (s.endsWith("y") && !s.endsWith("ay") && !s.endsWith("ey")) {
            return s.substring(0, s.length() - 1) + "ies";
        }
        return s.endsWith("s") || s.endsWith("x") || s.endsWith("ch") ? s + "es" : s + "s";
    }
}
