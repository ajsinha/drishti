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

import com.ash.drishti.banking.compute.var.VarBook;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One loaded business date: the engine's {@link VarBook}, the system baskets' VaR limits (var-limit, HIERARCHY PORTFOLIO, in the
 * date's currency) and what the date was read from. The system baskets themselves are read from the basket store as they are asked
 * ({@link VarSystemBaskets}), never with the date, so an edit is seen at once.
 *
 * @param version the vector table's data version (the pin a result is stamped with)
 * @param loadMs the whole load, {@code vectorMs} the vectors' part
 * @param intake the rows rejected and left out, the batch ids, the position rules the date was built with
 * @param versions every input kind's data version on the date (vectors, scenario set, limits)
 * @param warnings what the answers of this date say about it (the date asked could not be loaded, this one is answered instead)
 * @param currency the reporting currency of the book's vectors (USD, built at load; another, built from the USD book when first asked)
 * @param fx how the vectors met FX: per currency used, the source of its scenario rates and today's rate (empty: no FX was needed)
 * @param intraday the date's intraday updates applied on its book (VAR_GUIDE.md section 8.4): the latest per trade, the batches met, the
 *     morning run's {@code COMPUTED_AT}; null when the date has none
 */
record VarDate(LocalDate date, VarBook book, Map<String, Double> portfolioLimits, String version, Instant loadedAt, long loadMs, long vectorMs,
        VarIntake.Summary intake, Map<String, String> versions, List<String> warnings, String currency, Map<String, Object> fx,
        VarIntradayRows intraday) {

    static final String USD = com.ash.drishti.banking.compute.var.ScenarioFx.USD;

    VarDate {
        portfolioLimits = portfolioLimits == null ? Map.of() : Map.copyOf(portfolioLimits);
        versions = versions == null ? Map.of() : Map.copyOf(versions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        currency = currency == null || currency.isBlank() ? USD : currency;
        fx = fx == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fx));
    }

    VarDate(LocalDate date, VarBook book, Map<String, Double> portfolioLimits, String version, Instant loadedAt, long loadMs, long vectorMs,
            VarIntake.Summary intake, Map<String, String> versions, List<String> warnings, String currency, Map<String, Object> fx) {
        this(date, book, portfolioLimits, version, loadedAt, loadMs, vectorMs, intake, versions, warnings, currency, fx, null);
    }

    /** The same date over another book (an intraday batch applied), with its intraday updates. */
    VarDate withBook(VarBook b, VarIntradayRows rows) {
        return new VarDate(date, b, portfolioLimits, version, loadedAt, loadMs, vectorMs, intake, versions, warnings, currency, fx, rows);
    }

    /** The date without its intraday layer: the morning run alone (the official captures, VAR_GUIDE.md section 8.4). */
    VarDate morning() {
        return book.intraday() == null ? this : new VarDate(date, book.morning(), portfolioLimits, version, loadedAt, loadMs, vectorMs, intake, versions,
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
        return new VarDate(date, book, portfolioLimits, version, loadedAt, loadMs, vectorMs, intake, versions, w, currency, fx, intraday);
    }

    /** The same date without the warnings that begin with {@code prefix} (a condition that has passed). */
    VarDate unwarned(String prefix) {
        if (warnings.stream().noneMatch(w -> w.startsWith(prefix))) {
            return this;
        }
        return new VarDate(date, book, portfolioLimits, version, loadedAt, loadMs, vectorMs, intake, versions,
                warnings.stream().filter(w -> !w.startsWith(prefix)).toList(), currency, fx, intraday);
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
     * A system basket as the date resolves {@code PORT:<id>} by (VAR_GUIDE.md section 5.7): its definition as scope text and its limit.
     *
     * @param type HIERARCHY, RULE or BASKET
     * @param scope its definition in the scope grammar: {@code NODE:x}, {@code WHERE:rule}, {@code POS:... + TRD:...}
     * @param limit its VaR limit in the date's currency (var-limit, HIERARCHY PORTFOLIO), NaN when it has none
     */
    record Portfolio(String id, String name, String type, String owner, String scope, double limit) {}
}
