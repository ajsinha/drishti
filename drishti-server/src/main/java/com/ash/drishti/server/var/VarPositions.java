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
package com.ash.drishti.server.var;

import com.ash.drishti.compute.var.Columns;
import com.ash.drishti.compute.var.VarBook;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The positions Drishti derives (VAR_GUIDE.md section 15.6) as documents of the {@code position} kind, served by the {@code var-positions}
 * connector ({@link VarPositionsPlugin}): a position's instrument and key, its direction and signed quantity, its trades (each with its
 * own quantity, direction, PV, product and counterparty), where it is held, its market value and counts. No VaR figure is stored on it:
 * its page shows the engine's figures computed when asked ({@link VarFigures}). Positions live in memory with their date; this builds
 * the document of one when it is asked for.
 *
 * <p>The plugin is made by the server's plugin loader with no arguments, so it finds the engine through {@link #install}: the server
 * installs it when the VaR engine starts.
 */
final class VarPositions {

    private static final AtomicReference<VarPositions> INSTALLED = new AtomicReference<>();
    private final VarService svc;

    VarPositions(VarService svc) {
        this.svc = svc;
    }

    /** Makes this the instance the {@code var-positions} connector reads through. */
    static void install(VarPositions p) {
        INSTALLED.set(p);
    }

    static Optional<VarPositions> installed() {
        return Optional.ofNullable(INSTALLED.get());
    }

    VarService service() {
        return svc;
    }

    /** The newest date with vectors (the positions' date), or null. */
    LocalDate latest() {
        return svc.loader().latest();
    }

    /** One position's document on a date (the newest date with vectors on or before it). */
    Optional<Map<String, Object>> position(String id, LocalDate date) {
        VarDate d = svc.date(date);
        VarBook b = d.book();
        int p = b.positionIndexAnyCase(id);
        return p < 0 ? Optional.empty() : Optional.of(document(d, p));
    }

    /** Every position of a date, in hierarchy order. */
    void positions(LocalDate date, Consumer<Map<String, Object>> sink) {
        VarDate d = svc.date(date);
        for (int p = 0; p < d.book().positionCount(); p++) {
            sink.accept(document(d, p));
        }
    }

    /** The date's data version (the positions change when their trades or the rules do). */
    String version(LocalDate date) {
        VarDate d = svc.date(date);
        return d.version() + ":" + d.intake().rulesDigest();
    }

    static Map<String, Object> document(VarDate d, int p) {
        VarBook b = d.book();
        Columns pc = b.positions();
        Columns tc = b.trades();
        Map<String, Object> m = new LinkedHashMap<>();
        String id = b.positionId(p);
        m.put("POSITION_ID", id);
        m.put("BUSINESS_DATE", d.date().toString());
        for (String c : List.of("POSITION_RULE", "SECURITY_ID", "INSTRUMENT_ID", "INSTRUMENT", "POSITION_TYPE", "DIRECTION")) {
            m.put(c, pc.text(c, p));
        }
        m.put("QUANTITY", pc.number("QUANTITY", p));
        m.put("GROSS_QUANTITY", pc.number("NOTIONAL", p));
        m.put("TRADE_COUNT", (long) pc.number("TRADE_COUNT", p));
        m.put("LONG_TRADES", (long) pc.number("LONG_TRADES", p));
        m.put("SHORT_TRADES", (long) pc.number("SHORT_TRADES", p));
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> trades = new ArrayList<>();
        java.util.TreeSet<String> cps = new java.util.TreeSet<>();
        for (int o = b.positionFrom(p); o < b.positionTo(p); o++) {
            String t = b.tradeId(o);
            ids.add(t);
            double q = tc.number("QUANTITY", o);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("TRADE_ID", t);
            r.put("QUANTITY", Double.isNaN(q) ? null : q);
            r.put("DIRECTION", q > 0 ? "LONG" : q < 0 ? "SHORT" : "FLAT");
            double pv = tc.number("PV", o);
            r.put("PV", Double.isNaN(pv) ? null : pv);
            r.put("PRODUCT_TYPE", tc.text("PRODUCT_TYPE", o));
            r.put("COUNTERPARTY", tc.text("COUNTERPARTY", o));
            if (tc.text("COUNTERPARTY", o) != null) {
                cps.add(tc.text("COUNTERPARTY", o));
            }
            trades.add(r);
        }
        m.put("TRADE_IDS", ids);
        m.put("TRADES", trades);
        for (String c : List.of("LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO", "STRATEGY", "COUNTERPARTY", "PRODUCT_GROUP",
                "PRODUCT_TYPE", "CURRENCY")) {
            m.put(c, pc.text(c, p));
        }
        m.put("COUNTERPARTIES", List.copyOf(cps));
        double mv = pc.number("MARKET_VALUE", p);
        m.put("MARKET_VALUE", Double.isNaN(mv) ? null : Math.round(mv));
        m.put("NOTIONAL", Math.round(pc.number("NOTIONAL", p)));
        String tags = pc.text("TAGS", p);
        m.put("TAGS", tags == null || tags.isEmpty() ? List.of() : List.of(tags.split("\\|")));
        m.put("STATUS", pc.text("STATUS", p));
        m.put("SCENARIO_SET_ID", b.scenarioSet());
        m.put("VAR_SCOPE", "POS:" + id);
        m.put("DERIVED_BY", "the server's position rules " + d.intake().rulesDigest() + " (VAR_GUIDE.md section 15.6)");
        return m;
    }
}
