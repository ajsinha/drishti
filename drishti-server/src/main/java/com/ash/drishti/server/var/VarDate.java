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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.compute.var.VarBook;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One loaded business date: the engine's {@link VarBook}, the stored portfolios' definitions, and what the date was read from.
 *
 * @param version the vector table's data version (the pin a result is stamped with)
 * @param loadMs the whole load, {@code vectorMs} the vectors' part
 * @param intake the rows rejected and left out, the batch ids, the position rules the date was built with
 * @param versions every input kind's data version on the date (vectors, scenario set, hierarchy, portfolios)
 * @param warnings what the answers of this date say about it (the date asked could not be loaded, this one is answered instead)
 * @param currency the reporting currency of the book's vectors (USD, built at load; another, built from the USD book when first asked)
 * @param fx how the vectors met FX: per currency used, the source of its scenario rates and today's rate (empty: no FX was needed)
 * @param intraday the date's intraday updates applied on its book (VAR_GUIDE.md section 8.4): the latest per trade, the batches met, the
 *     morning run's {@code COMPUTED_AT}; null when the date has none
 */
record VarDate(LocalDate date, VarBook book, Map<String, Portfolio> portfolios, String version, Instant loadedAt, long loadMs, long vectorMs,
        VarIntake.Summary intake, Map<String, String> versions, List<String> warnings, String currency, Map<String, Object> fx,
        VarIntradayRows intraday) {

    static final String USD = com.ash.drishti.compute.var.ScenarioFx.USD;

    VarDate {
        versions = versions == null ? Map.of() : Map.copyOf(versions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        currency = currency == null || currency.isBlank() ? USD : currency;
        fx = fx == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fx));
    }

    VarDate(LocalDate date, VarBook book, Map<String, Portfolio> portfolios, String version, Instant loadedAt, long loadMs, long vectorMs,
            VarIntake.Summary intake, Map<String, String> versions, List<String> warnings, String currency, Map<String, Object> fx) {
        this(date, book, portfolios, version, loadedAt, loadMs, vectorMs, intake, versions, warnings, currency, fx, null);
    }

    /** The same date over another book (an intraday batch applied), with its intraday updates. */
    VarDate withBook(VarBook b, VarIntradayRows rows) {
        return new VarDate(date, b, portfolios, version, loadedAt, loadMs, vectorMs, intake, versions, warnings, currency, fx, rows);
    }

    /** The date without its intraday layer: the morning run alone (the official captures, VAR_GUIDE.md section 8.4). */
    VarDate morning() {
        return book.intraday() == null ? this : new VarDate(date, book.morning(), portfolios, version, loadedAt, loadMs, vectorMs, intake, versions,
                warnings, currency, fx, null);
    }

    /** A stamp of the intraday layer (0: none), for the answers' cache. */
    long intradayStamp() {
        return book.intraday() == null ? 0 : book.intraday().info().updates();
    }

    /** The same date with a warning more (answered in place of a date that could not be loaded). */
    VarDate warned(String warning) {
        List<String> w = new ArrayList<>(warnings);
        w.add(warning);
        return new VarDate(date, book, portfolios, version, loadedAt, loadMs, vectorMs, intake, versions, w, currency, fx, intraday);
    }

    /** The input tables' versions and batch ids, as an official capture and the provenance record them (and the currency, not USD). */
    Map<String, Object> inputs() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(versions);
        m.put("batchIds", intake == null ? List.of() : intake.batchIds());
        m.put("positionRules", intake == null ? "" : intake.rulesDigest());
        if (!USD.equals(currency)) {
            m.put("currency", currency);
        }
        return m;
    }

    /**
     * A stored portfolio (the {@code portfolio} kind): its definition as scope text.
     *
     * @param type HIERARCHY, RULE or BASKET
     * @param scope its definition in the scope grammar: {@code NODE:x}, {@code WHERE:rule}, {@code POS:... + TRD:...}
     */
    record Portfolio(String id, String name, String type, String owner, String scope, double limit) {

        static Portfolio of(String id, DataNode d) {
            String type = VarLoader.text(d, "TYPE");
            DataNode def = d.get("DEFINITION");
            String scope;
            if ("HIERARCHY".equals(type)) {
                scope = "NODE:" + VarLoader.text(def, "NODE");
            } else if ("RULE".equals(type)) {
                scope = "WHERE:" + VarLoader.text(def, "RULE");
            } else {
                List<String> pos = new ArrayList<>();
                List<String> trd = new ArrayList<>();
                DataNode m = def.get("MEMBERS");
                for (int i = 0; i < m.size(); i++) {
                    String p = VarLoader.text(m.get(i), "POSITION_ID");
                    String t = VarLoader.text(m.get(i), "TRADE_ID");
                    if (p != null) {
                        pos.add(p);
                    } else if (t != null) {
                        trd.add(t);
                    }
                }
                List<String> parts = new ArrayList<>();
                if (!pos.isEmpty()) {
                    parts.add("POS:" + String.join(",", pos));
                }
                if (!trd.isEmpty()) {
                    parts.add("TRD:" + String.join(",", trd));
                }
                scope = parts.isEmpty() ? "TRD:NONE" : String.join(" + ", parts);
            }
            return new Portfolio(id, VarLoader.text(d, "NAME"), type, VarLoader.text(d, "OWNER"), scope, VarLoader.number(VarLoader.text(d, "LIMIT_VAR")));
        }
    }
}
