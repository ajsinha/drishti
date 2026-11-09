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
package com.ash.drishti.banking.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.source.SourceRouter;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What happened to matured items on a date (VAR_GUIDE.md, "Matured items"), kept with the load's record: the records the intake excluded
 * because they had matured, per source system and grain, and the trades past their maturity inside live supplied positions. The second
 * is a flag: a position's PVs are sent whole, so it never changes a figure.
 *
 * @param records source system to grain ({@code TRADE}, {@code POSITION}) to the records excluded
 * @param check {@link #CHECKED}, {@link #NOT_NEEDED}, or {@link #NOT_CHECKED} (see {@code why})
 * @param why why the trades inside positions could not be checked, in words, or null
 * @param positions live supplied positions holding at least one matured trade (the whole date)
 * @param trades the matured trades they name, each once (the whole date)
 * @param unnamed live supplied positions that do not name their trades ({@code TRADE_IDS}): not checked
 * @param unresolved named trades with no maturity in the trade feed: not checked
 * @param listed position id to matured trade id to maturity, sorted, up to {@link #LISTED_TRADES} trades
 * @param capped true when {@code listed} does not hold every flagged position
 */
record VarMatured(Map<String, Map<String, Integer>> records, String check, String why, int positions, int trades, int unnamed, int unresolved,
        Map<String, Map<String, String>> listed, boolean capped) {

    static final String CHECKED = "checked";
    static final String NOT_NEEDED = "not-needed";
    static final String NOT_CHECKED = "not-checked";
    /** The trade feed's kind, as the trade pages name it. */
    static final String TRADE_KIND = "trade";
    static final String MATURITY = "MATURITY_DATE";
    /** The flagged trades kept with the date: the hot file's record and the memory stay small whatever the feed holds. */
    static final int LISTED_TRADES = 20_000;
    /** A feed that keeps no columns is read document by document, up to this many named trades. */
    static final int FETCHED_TRADES = 50_000;
    private static final Duration BUDGET = Duration.ofSeconds(60);

    /** What the intake alone knows: the excluded records; the positions await {@link #checked}. */
    static VarMatured ofIntake(Map<String, Map<String, Integer>> records, int unnamed) {
        return new VarMatured(records, NOT_NEEDED, null, 0, 0, unnamed, 0, Map.of(), false);
    }

    int recordCount() {
        return records == null ? 0 : records.values().stream().flatMap(m -> m.values().stream()).mapToInt(Integer::intValue).sum();
    }

    /** True when there is something to say about the date. */
    boolean worthSaying() {
        return recordCount() > 0 || positions > 0 || NOT_CHECKED.equals(check) || unnamed > 0 || unresolved > 0;
    }

    private VarMatured notChecked(String reason) {
        return new VarMatured(records, NOT_CHECKED, reason, 0, 0, unnamed, 0, Map.of(), false);
    }

    /**
     * Compares each trade the live positions name with the date: a trade whose {@code MATURITY_DATE} is before it is past its maturity
     * (the rule the intake applies to a record). Read once, at the date's load; only the flagged trades are kept.
     *
     * @param named the live supplied positions that name their trades, to those trades
     */
    VarMatured checked(Map<String, List<String>> named, LocalDate date, Feed feed) {
        if (named.isEmpty()) {
            return unnamed > 0 ? notChecked("the source does not send TRADE_IDS with its positions") : this;
        }
        Set<String> wanted = new HashSet<>();
        named.values().forEach(wanted::addAll);
        Map<String, String> past = new LinkedHashMap<>();
        int found;
        try {
            found = feed.read(wanted, (trade, maturity) -> {
                if (isBefore(maturity, date)) {
                    past.put(trade, maturity.substring(0, 10));
                }
            });
        } catch (RuntimeException e) {
            return notChecked("the trade feed could not be read");
        }
        if (found < 0) {
            return notChecked("the positions name more than " + FETCHED_TRADES + " trades and the trade feed keeps no maturity column");
        }
        if (found == 0) {
            return notChecked("the trade feed has no maturity for the trades the positions name");
        }
        Map<String, Map<String, String>> list = new TreeMap<>();
        Set<String> flagged = new HashSet<>();
        int count = 0;
        int kept = 0;
        boolean cut = false;
        for (Map.Entry<String, List<String>> e : new TreeMap<>(named).entrySet()) {
            Map<String, String> here = new TreeMap<>();
            e.getValue().stream().filter(past::containsKey).forEach(t -> here.put(t, past.get(t)));
            if (here.isEmpty()) {
                continue;
            }
            count++;
            flagged.addAll(here.keySet());
            if (kept + here.size() <= LISTED_TRADES) {
                list.put(e.getKey(), here);
                kept += here.size();
            } else {
                cut = true;
            }
        }
        return new VarMatured(records, CHECKED, null, count, flagged.size(), unnamed, wanted.size() - found, list, cut);
    }

    private static boolean isBefore(String maturity, LocalDate date) {
        if (maturity == null || maturity.length() < 10) {
            return false;
        }
        try {
            return LocalDate.parse(maturity.substring(0, 10)).isBefore(date);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** One date's parts, shard by shard: counts add up, a part not checked makes the whole not checked. */
    static VarMatured merge(List<VarMatured> parts) {
        Map<String, Map<String, Integer>> records = new TreeMap<>();
        Map<String, Map<String, String>> list = new TreeMap<>();
        String check = NOT_NEEDED;
        String why = null;
        int positions = 0;
        int trades = 0;
        int unnamed = 0;
        int unresolved = 0;
        int kept = 0;
        boolean cut = false;
        for (VarMatured m : parts) {
            if (m == null) {
                check = NOT_CHECKED;
                why = why == null ? "this date's record does not hold the check" : why;
                continue;
            }
            if (m.records() != null) {
                m.records().forEach((s, g) -> g.forEach((k, v) -> records.computeIfAbsent(s, x -> new TreeMap<>()).merge(k, v, Integer::sum)));
            }
            positions += m.positions();
            trades += m.trades();
            unnamed += m.unnamed();
            unresolved += m.unresolved();
            cut |= m.capped();
            for (Map.Entry<String, Map<String, String>> e : m.listed().entrySet()) {
                if (kept + e.getValue().size() <= LISTED_TRADES) {
                    list.put(e.getKey(), e.getValue());
                    kept += e.getValue().size();
                } else {
                    cut = true;
                }
            }
            if (NOT_CHECKED.equals(m.check())) {
                check = NOT_CHECKED;
                why = why == null ? m.why() : why;
            } else if (CHECKED.equals(m.check()) && !NOT_CHECKED.equals(check)) {
                check = CHECKED;
            }
        }
        return new VarMatured(records, check, why, positions, trades, unnamed, unresolved, list, cut);
    }

    /** The trade feed's maturities of some trades. */
    interface Feed {
        /** Gives each wanted trade found with a maturity to {@code sink}; the number found, or -1 when too many to read. */
        int read(Set<String> wanted, java.util.function.BiConsumer<String, String> sink);
    }

    /** The feed through the router: the maturity column when the source keeps it, else the named trades' documents. */
    static Feed feed(SourceRouter router, AsOf asOf) {
        return (wanted, sink) -> {
            Optional<ColumnSet> columns = router.columns(TRADE_KIND, List.of(MATURITY), asOf, BUDGET);
            if (columns.isPresent()) {
                ColumnSet c = columns.get();
                int found = 0;
                for (int i = 0; i < c.size(); i++) {
                    if (wanted.contains(c.ids()[i]) && c.value(MATURITY, i) instanceof String m) {
                        sink.accept(c.ids()[i], m);
                        found++;
                    }
                }
                return found;
            }
            if (wanted.size() > FETCHED_TRADES) {
                return -1;
            }
            List<EntityRef> refs = new ArrayList<>();
            wanted.forEach(t -> refs.add(new EntityRef(TRADE_KIND, t)));
            int found = 0;
            for (Map.Entry<EntityRef, EntityDocument> e : router.fetchAll(refs, BUDGET, asOf).entrySet()) {
                if (e.getValue() != null && e.getValue().data() instanceof DataNode.Obj o && !o.get(MATURITY).isNull()) {
                    sink.accept(e.getKey().id(), o.get(MATURITY).asText());
                    found++;
                }
            }
            return found;
        };
    }
}
