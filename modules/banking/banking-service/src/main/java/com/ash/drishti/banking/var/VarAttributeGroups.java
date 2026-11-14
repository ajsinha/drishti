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
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.Columns;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * A scope's VaR by the value of a trade column (VAR_GUIDE.md section 11, {@code by=attribute:<COLUMN>}; first {@code TRADER_ID}, the
 * Trading landing's VaR by trader): every trade of the scope is put in the group of its value, the group's vector summed in Java over all
 * its trades (never only the largest), so the groups partition the scope and their component VaR adds up to the scope's VaR. The column is
 * the VaR book's own (a column of the P&amp;L vectors' rows, a hierarchy's level column) or else the trade valuations' ({@code
 * drishti.var.kinds.valuation}, joined by {@code TRADE_ID} on the business date). A trade without a value is in the group "no COLUMN".
 *
 * <p>The column's field mask is honoured as for every other answer: a role that sees it masked ({@code drishti.security.redact} without
 * {@code raw}) gets no groups and a note saying why ({@link #masked}).
 */
final class VarAttributeGroups {

    static final String PREFIX = "attribute:";
    static final String KIND = "attribute";
    private static final Pattern COLUMN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Duration READ = Duration.ofMinutes(2);
    /** The most groups one answer computes (each a vector of the scenarios: 10,000 of 509 take about 40 MB while it is made). */
    static final int MAX_GROUPS = 10_000;
    private final VarService svc;
    /** The valuations' column joined by trade id, per kind, column, date and data version; the least recently used leaves first. */
    private final Map<String, Map<String, String>> joins = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumWeight(64L << 20)                                   // weighed in bytes (D3): about 96 bytes a joined trade
            .<String, Map<String, String>>weigher((k, m) -> (int) Math.min(Integer.MAX_VALUE, 1024L + 96L * m.size()))
            .build().asMap();

    VarAttributeGroups(VarService svc) {
        this.svc = svc;
    }

    /** The column {@code by} names ({@code attribute:TRADER_ID}, any case), upper case; null when {@code by} is not an attribute. */
    static String column(String by) {
        if (by == null || !by.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return null;
        }
        String c = by.trim().substring(PREFIX.length()).trim().toUpperCase(Locale.ROOT);
        if (!COLUMN.matcher(c).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "by=attribute:<COLUMN> names a trade column such as TRADER_ID, not '" + c + "'");
        }
        return c;
    }

    /** True when the column is masked for the caller: no groups (the values would be shown as the groups' names). */
    boolean masked(Principal p, String column) {
        return svc.access().masked(p, column);
    }

    /** The answer for a caller who sees the column masked: the scope's figures, no groups, and why. */
    static Map<String, Object> maskedAnswer(VarService.Scoped s, String column) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical", s.res().canonical());
        out.put("currency", s.date().currency());
        out.put("by", KIND);
        out.put("attribute", column);
        out.put("masked", true);
        out.put("var", VarViews.round(s.measures().var()));
        out.put("es", VarViews.round(s.measures().es()));
        out.put("truncated", false);
        out.put("total", 0);
        out.put("shown", 0);
        out.put("rows", List.of());
        out.put("note", column + " is masked for your role: VaR by " + column + " needs a role that sees it");
        return out;
    }

    /**
     * The groups of the scope's trades by the column's value, each with its vector summed over every one of its trades, never cut to
     * {@code max-parts}; refused when there are more than {@link #MAX_GROUPS} (a column of nearly one value per trade: ask {@code by=trade}).
     */
    List<VarViews.Part> parts(VarService.Scoped s, String column) {
        VarBook b = s.date().book();
        IntFunction<String> value = values(s, column);
        boolean off = !tradeSourcesOff().isEmpty();
        Map<String, OrdinalSet.Builder> groups = new TreeMap<>();
        Map<String, int[]> counts = new HashMap<>();
        s.set().forEach(o -> {
            String v = value.apply(o);
            String k = v == null || v.isBlank() ? "" : v;
            groups.computeIfAbsent(k, x -> new OrdinalSet.Builder()).add(o);
            counts.computeIfAbsent(k, x -> new int[1])[0]++;
        });
        if (groups.size() > MAX_GROUPS) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, column + " has " + groups.size() + " values in " + s.res().canonical()
                    + ", more than " + MAX_GROUPS + ": VaR by a column of so many values is VaR by trade (by=trade)");
        }
        List<VarViews.Part> out = new ArrayList<>(groups.size());
        groups.forEach((k, g) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("attribute", column);
            a.put("trades", counts.get(k)[0]);
            String label = k.isEmpty() ? (off ? "Unassigned" : "no " + column) : k;
            out.add(new VarViews.Part(k, null, label, KIND, b.vector(g.build(), svc.sums()), Double.NaN, a));
        });
        return out;
    }

    /** Each trade ordinal's value of the column: the VaR book's own column, else the trade valuations' joined by trade id. */
    private IntFunction<String> values(VarService.Scoped s, String column) {
        VarBook b = s.date().book();
        Columns tc = b.trades();
        if (tc.has(column)) {
            return o -> tc.text(column, o);
        }
        String kind = svc.props().kinds().valuation();
        SourceRouter router = svc.loader().router();
        if (!tradeSourcesOff().isEmpty()) {
            return o -> null;                       // trade details from a switched-off source: the trades stay in their VaR, under "Unassigned"
        }
        if (!router.columnar(kind).contains(column)) {
            List<String> own = new ArrayList<>(tc.textColumns());
            throw new DrishtiException(ErrorCode.BAD_REQUEST, column + " is not a column of the VaR book's trades (" + String.join(", ", own)
                    + ") nor a column the trade valuations (" + kind + ") keep: VaR by attribute groups by one of them");
        }
        Map<String, String> byTrade = join(router, kind, column, s.date().date());
        return o -> byTrade.get(b.tradeId(o));
    }

    /** The trading systems switched off that feed the trade details (the trade kind and the valuations): VaR at trade grain says so. */
    java.util.Set<String> tradeSourcesOff() {
        return svc.tradeSourcesOff();
    }

    /** The valuations' column by trade id on the date, read once per data version (the date's row of each trade). */
    private Map<String, String> join(SourceRouter router, String kind, String column, LocalDate date) {
        AsOf asOf = AsOf.of(date);
        String key = kind + "|" + column + "|" + date + "|" + router.dataVersion(kind, asOf).orElse("");
        Map<String, String> hit = joins.get(key);
        if (hit != null) {
            return hit;
        }
        ColumnSet cs = router.columns(kind, List.of("TRADE_ID", column), asOf, READ).orElseThrow(() -> new DrishtiException(
                ErrorCode.BAD_REQUEST, "the trade valuations (" + kind + ") of " + date + " could not be read as columns for " + column));
        Map<String, String> out = new HashMap<>(cs.size() * 2);
        for (int i = 0; i < cs.size(); i++) {
            Object t = cs.value("TRADE_ID", i);
            Object v = cs.value(column, i);
            String trade = t == null ? cs.ids()[i] : String.valueOf(t);
            if (v != null && trade != null) {
                out.putIfAbsent(trade, v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : String.valueOf(v));
            }
        }
        Map<String, String> kept = Map.copyOf(out);
        joins.put(key, kept);
        return kept;
    }
}
