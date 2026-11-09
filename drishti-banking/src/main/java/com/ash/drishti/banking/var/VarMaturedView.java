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

import com.ash.drishti.compute.vector.OrdinalSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The date's {@link VarMatured} as one reader's answer (VAR_GUIDE.md, "Matured items"): the flagged positions of the scope and the sentences
 * that say so. Built per answer from the date's record, nothing kept: a position the reader cannot see one by one is counted, never named.
 */
final class VarMaturedView {

    /** The rows carried by the provenance; the rest come page by page ({@code /scope/matured}). */
    static final int FIRST_PAGE = 25;
    static final int MAX_PAGE = 200;

    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final VarMatured m;
    private final String date;
    private final boolean wholeDate;
    private int positions;
    private int trades;
    private int hidden;

    private VarMaturedView(VarService.Scoped s, VarMatured m, boolean maskedTrades) {
        this.m = m;
        this.date = s.date().date().toString();
        this.wholeDate = m.capped() || s.shards() != null;              // not every flagged position can be placed in the scope
        VarAccess.View view = s.view();
        boolean mayName = !maskedTrades;
        if (wholeDate) {
            positions = m.positions();
            trades = m.trades();
            hidden = view.full() && mayName ? 0 : m.positions();
            if (view.full() && mayName) {
                m.listed().forEach((position, named) -> rows.add(row(position, named)));
            }
            return;
        }
        OrdinalSet scope = s.res().set();
        Set<String> seen = new HashSet<>();
        m.listed().forEach((position, named) -> {
            int o = s.date().book().tradeOrdinal(position);
            if (o < 0 || !scope.contains(o)) {
                return;
            }
            positions++;
            seen.addAll(named.keySet());
            if (mayName && (view.full() || view.positions().contains(o))) {
                rows.add(row(position, named));
            } else {
                hidden++;
            }
        });
        trades = seen.size();
    }

    private static Map<String, Object> row(String position, Map<String, String> named) {
        List<Map<String, String>> list = new ArrayList<>();
        named.forEach((t, d) -> list.add(Map.of("trade", t, "maturity", d)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("position", position);
        r.put("trades", list);
        return r;
    }

    /** The date's record; on a position-grain date whose record does not hold it, one that says it could not be checked; otherwise null. */
    private static VarMatured record(VarService.Scoped s) {
        VarIntake.Summary intake = s.date().intake();
        if (intake.matured() != null) {
            return intake.matured();
        }
        return intake.positionTrades() != null && !intake.positionTrades().isEmpty()
                ? new VarMatured(Map.of(), VarMatured.NOT_CHECKED, "this date's record does not hold the check", 0, 0, 0, 0, Map.of(), false) : null;
    }

    /** The provenance's {@code matured}, or null when there is nothing to say. */
    static Map<String, Object> of(VarService.Scoped s, boolean maskedTrades) {
        VarMatured m = record(s);
        if (m == null || !m.worthSaying()) {
            return null;
        }
        VarMaturedView v = new VarMaturedView(s, m, maskedTrades);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", v.date);
        if (m.recordCount() > 0) {
            out.put("records", v.records());
        }
        Map<String, Object> inside = v.inside();
        if (inside != null) {
            out.put("inside", inside);
        }
        return out.size() == 1 ? null : out;
    }

    private Map<String, Object> records() {
        List<Map<String, Object>> by = new ArrayList<>();
        m.records().forEach((system, grains) -> grains.forEach((grain, n) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("system", system);
            r.put("grain", grain);
            r.put("count", n);
            by.add(r);
        }));
        int n = m.recordCount();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n);
        out.put("bySystem", by);
        out.put("sentence", count(n, "record") + (n == 1 ? " was excluded because it" : " were excluded because they") + " had matured by " + date + ".");
        return out;
    }

    private Map<String, Object> inside() {
        List<String> sentences = new ArrayList<>();
        boolean notChecked = VarMatured.NOT_CHECKED.equals(m.check());
        if (notChecked) {
            sentences.add("Trades inside positions could not be checked for maturity: " + m.why() + ".");
        }
        if (positions > 0) {
            sentences.add(count(positions, "position") + (positions == 1 ? " contains " : " contain ") + count(trades, "trade") + " past "
                    + (trades == 1 ? "its" : "their") + " maturity; " + (positions == 1 ? "its" : "their")
                    + " PVs are included, because the source sends each position whole" + (wholeDate ? " (counted for the whole date)" : "") + ".");
            if (hidden > 0) {
                sentences.add(String.format(Locale.ROOT, "%,d of these %s outside what you may see one by one: counted, not named.", hidden,
                        hidden == 1 ? "is" : "are"));
            }
        }
        if (m.unnamed() > 0 && !notChecked) {
            sentences.add(count(m.unnamed(), "position") + " could not be checked: " + (m.unnamed() == 1 ? "it does not name its" : "they do not name their")
                    + " trades (TRADE_IDS).");
        }
        if (m.unresolved() > 0) {
            sentences.add(count(m.unresolved(), "named trade") + " could not be checked: the trade feed gives no maturity for "
                    + (m.unresolved() == 1 ? "it" : "them") + ".");
        }
        if (sentences.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", notChecked ? "could not be checked" : "checked");
        if (notChecked) {
            out.put("why", m.why());
        }
        out.put("positions", notChecked ? null : positions);
        out.put("trades", notChecked ? null : trades);
        out.put("notNamed", hidden);
        out.put("positionsNotChecked", m.unnamed());
        out.put("tradesNotChecked", m.unresolved());
        out.put("wholeDate", wholeDate);
        out.put("sentences", sentences);
        out.put("rows", new ArrayList<>(rows.subList(0, Math.min(FIRST_PAGE, rows.size()))));
        out.put("more", rows.size() > FIRST_PAGE);
        return out;
    }

    /** One page of the positions this reader may see named. */
    static Map<String, Object> page(VarService.Scoped s, boolean maskedTrades, int page, int size) {
        VarMatured m = record(s);
        List<Map<String, Object>> all = m == null ? List.of() : new VarMaturedView(s, m, maskedTrades).rows;
        int n = Math.max(1, Math.min(MAX_PAGE, size <= 0 ? FIRST_PAGE : size));
        int from = (int) Math.min(all.size(), Math.max(0L, page) * n);
        int to = Math.min(all.size(), from + n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", s.date().date().toString());
        out.put("page", Math.max(0, page));
        out.put("size", n);
        out.put("total", all.size());
        out.put("rows", new ArrayList<>(all.subList(from, to)));
        out.put("more", to < all.size());
        return out;
    }

    /** Admin, Health: the date's counts in words, or null. */
    static String health(VarMatured m, java.time.LocalDate date) {
        if (m == null || !m.worthSaying()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        boolean notChecked = VarMatured.NOT_CHECKED.equals(m.check());
        if (m.recordCount() > 0) {
            parts.add(count(m.recordCount(), "record") + " excluded as matured");
        }
        if (notChecked) {
            parts.add("trades inside positions could not be checked (" + m.why() + ")");
        } else if (m.positions() > 0) {
            parts.add(count(m.positions(), "position") + " containing " + count(m.trades(), "trade") + " past maturity (PVs included)");
        }
        if (m.unnamed() > 0 && !notChecked) {
            parts.add(count(m.unnamed(), "position") + " not naming their trades");
        }
        if (m.unresolved() > 0) {
            parts.add(count(m.unresolved(), "named trade") + " with no maturity in the trade feed");
        }
        return parts.isEmpty() ? null : date + ": " + String.join("; ", parts);
    }

    /** Per open date, what happened to its matured items, in words; a date still loading is not opened for it. */
    static Map<String, String> warnings(Map<java.time.LocalDate, java.util.concurrent.CompletableFuture<VarDate>> dates) {
        Map<String, String> out = new java.util.TreeMap<>();
        dates.forEach((d, f) -> {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                String w = health(f.join().intake().matured(), d);
                if (w != null) {
                    out.put(d.toString(), w);
                }
            }
        });
        return out;
    }

    private static String count(int n, String noun) {
        return String.format(Locale.ROOT, "%,d %s%s", n, noun, n == 1 ? "" : "s");
    }
}
