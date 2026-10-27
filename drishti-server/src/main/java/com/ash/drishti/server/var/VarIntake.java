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

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.PositionRules;
import com.ash.drishti.compute.var.ScenarioFx;
import com.ash.drishti.compute.var.VarBook;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * One date's trade rows on their way into the {@link VarBook} (VAR_GUIDE.md sections 15.6, 15.9 and 20.3), row by row as the connector
 * streams them, in two forms a bank may send (either, or both for different trades):
 *
 * <ul>
 *   <li><b>P&amp;L vectors</b> ({@code pnl-vector}, USD): a vector of the wrong length, with NaN or infinity, in another currency, or a
 *       failed row that carries a vector is rejected ({@code DRS-5201}: counted and listed, never padded);</li>
 *   <li><b>scenario PVs</b> ({@code scenario-pv}, 2.9.4): the trade's PV under each scenario and its base PV of the same run, in the
 *       trade's own currency ({@code CURRENCY}); converted to the USD P&amp;L at load, {@code P&L(s) = PV(s) × FX(s) − PV(base) × FX(base)}
 *       ({@link ScenarioFx#pnlFromPv}). A scenario that failed to price (a null or NaN PV) rejects the trade ({@code DRS-5206}, the
 *       scenarios listed; never zero-filled); a currency without scenario FX rejects it too ({@code DRS-5207}); a wrong length, no base PV,
 *       or valuation dates that differ from each other or from the business date reject it as {@code DRS-5201}.</li>
 * </ul>
 *
 * A trade that is not live on the date (its status, its maturity) is left out; every other row gets its position from the configured
 * rules and goes to the builder (a trade id seen twice, in either form, refuses the date). The batch ids and scenario-set ids are gathered
 * for the one-run check made when the stream ends.
 */
final class VarIntake {

    /** The trade columns read besides the engine's own when the table keeps them (VAR_GUIDE.md section 15.2). */
    static final List<String> OPTIONAL = List.of("TRADE_STATUS", "MATURITY_DATE", "BATCH_ID", "SCENARIO_SET_ID", "REPORTING_CCY", "STATUS",
            "RUN_KIND", "COMPUTED_AT");
    /** The scenario-PV form's own columns (VAR_GUIDE.md section 15.9), read when the table keeps them. */
    static final List<String> PV_COLUMNS = List.of("BASE_PV", "VALUATION_DATE", "BASE_VALUATION_DATE");
    /** The engine's columns the vector table must keep (the rest of the trade columns are read when kept). */
    static final List<String> REQUIRED = List.of("LEGAL_ENTITY", "DESK", "TT_BOOK");
    private static final int LISTED = 20;
    /** The trades left out that are remembered with their reason, for the explorer's notice (beyond it: counted only). */
    private static final int NOT_LIVE_KEPT = 200_000;

    /**
     * What the intake did, for the provenance of every answer of the date.
     *
     * @param scenarioPv the trades loaded from scenario PVs (converted to USD at load)
     * @param pvTrades their ids (the first 200,000: a trade page links its scenario PVs rather than a P&amp;L vector)
     */
    record Summary(int rejected, List<String> rejections, int excluded, List<String> batchIds, List<String> scenarioSets, String rules,
            String rulesDigest, Map<String, String> notLive, int scenarioPv, java.util.Set<String> pvTrades) {

        /** Why a trade the date's vectors carry was left out (its status, its maturity), or null: the explorer's notice. */
        String notLiveWhy(String trade) {
            if (notLive == null || trade == null) {
                return null;
            }
            String why = notLive.get(trade);
            if (why == null) {
                for (Map.Entry<String, String> e : notLive.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(trade)) {
                        return e.getValue();
                    }
                }
            }
            return why;
        }

        /** True when the trade's row came as scenario PVs. */
        boolean fromScenarioPv(String trade) {
            return pvTrades != null && trade != null && pvTrades.contains(trade);
        }
    }

    private final VarBook.Builder book;
    private final PositionRules rules;
    private final LocalDate date;
    private final int width;
    private final Map<String, Integer> at = new HashMap<>();
    private final String[] texts = new String[VarBook.TRADE_TEXTS.size()];
    private final double[] numbers = new double[VarBook.TRADE_NUMBERS.size()];
    private final List<String> rejections = new ArrayList<>();
    private final TreeSet<String> batches = new TreeSet<>();
    private final TreeSet<String> sets = new TreeSet<>();
    private final Map<String, String> notLive = new HashMap<>();
    private final TreeSet<String> pvTrades = new TreeSet<>();
    /** Per currency met in scenario PVs: how its rates were found, and today's rate (VarDate.fx). */
    private final Map<String, Object> fxUsed = new TreeMap<>();
    private int rejected;
    private int excluded;
    private int scenarioPv;
    /** Scenario-PV mode: the date's FX (asked on the first PV row) and scenario days; null while reading P&amp;L vectors. */
    private Supplier<ScenarioFx> fx;
    private List<LocalDate> scenarioDates;
    private double[] usd;

    /** {@code columns} are the texts the vector read returns after {@code TRADE_ID}, in order. */
    VarIntake(VarBook.Builder book, PositionRules rules, LocalDate date, List<String> columns) {
        this.book = book;
        this.rules = rules;
        this.date = date;
        this.width = book.width();
        map(columns);
    }

    private void map(List<String> columns) {
        at.clear();
        for (int i = 0; i < columns.size(); i++) {
            at.put(columns.get(i), i + 1);
        }
    }

    /**
     * From now on the rows are scenario PVs ({@code columns} the texts the PV read returns after {@code TRADE_ID}): converted to USD
     * with the date's FX, asked once, on the first row that needs it.
     */
    void scenarioPvs(List<String> columns, Supplier<ScenarioFx> dateFx, List<LocalDate> days) {
        map(columns);
        ScenarioFx[] memo = {null};
        this.fx = () -> {
            if (memo[0] == null) {
                memo[0] = dateFx.get();
            }
            return memo[0];
        };
        this.scenarioDates = days;
        this.usd = new double[width];
    }

    /** The columns to ask the vector table for: TRADE_ID, the engine's, the rules' keys and the checks', those it keeps. */
    static List<String> columns(java.util.Set<String> kept, PositionRules rules) {
        return columns(kept, rules, List.of());
    }

    /** The same, with a form's own columns ({@link #PV_COLUMNS}) asked when kept. */
    static List<String> columns(java.util.Set<String> kept, PositionRules rules, List<String> own) {
        List<String> want = new ArrayList<>(List.of("TRADE_ID"));
        LinkedHashMap<String, Boolean> all = new LinkedHashMap<>();
        VarBook.TRADE_TEXTS.stream().filter(c -> !c.equals("POSITION_ID") && !c.equals("POSITION_RULE") && !c.equals("PV_CURRENCY"))
                .forEach(c -> all.put(c, true));
        VarBook.TRADE_NUMBERS.forEach(c -> all.put(c, true));
        rules.keyFields().forEach(c -> all.put(c, true));
        OPTIONAL.forEach(c -> all.put(c, true));
        own.forEach(c -> all.put(c, true));
        for (String c : all.keySet()) {
            if (kept.isEmpty() ? REQUIRED.contains(c) || VarBook.TRADE_NUMBERS.contains(c) || isEngineText(c) || own.contains(c)
                    : kept.contains(c) || REQUIRED.contains(c)) {
                want.add(c);
            }
        }
        return want;
    }

    /** A connector that cannot say which columns it keeps is asked for the engine's own columns only (as before 2.8.0). */
    private static boolean isEngineText(String c) {
        return List.of("DIVISION", "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "INSTRUMENT_ID").contains(c);
    }

    private String text(String[] row, String column) {
        Integer i = at.get(column);
        String v = i == null || i >= row.length ? null : row[i];
        return v == null || v.isEmpty() ? null : v;
    }

    /** One row of the stream: {@code row[0]} the trade id (null: the lake key {@code id}), then the columns. */
    void row(String id, double[] values, int length, String[] row) {
        String trade = row[0] == null ? id : row[0];
        String batch = text(row, "BATCH_ID");
        String set = text(row, "SCENARIO_SET_ID");
        String at = text(row, "COMPUTED_AT");
        boolean intra = INTRADAY.equalsIgnoreCase(text(row, "RUN_KIND"));
        currentIntra = intra;
        if (intra) {
            String part = (fx != null ? "scenario-pv|" : "pnl-vector|") + batch;    // a batch may come in both kinds, as two loads
            if (batch != null) {
                intradayBatches.add(batch);
                intradayParts.add(part);
            }
            if (batch != null && applied.contains(part)) {
                return;                                                 // applied already (a redelivery, or a date opened again)
            }
            if (setId != null && set != null && !set.equals(setId)) {
                reject(new String[] {ErrorCode.VAR_INCONSISTENT_DATE.code(), "an intraday update of the scenario set " + set + " (batch " + batch
                        + "); the date's set is " + setId + ": not applied"}, trade);
                return;
            }
        } else {
            if (batch != null) {
                batches.add(batch);
            }
            if (set != null) {
                sets.add(set);
            }
            if (at != null && (morningAt == null || at.compareTo(morningAt) > 0)) {
                morningAt = at;
            }
            if (intradayOnly) {
                return;                                                 // reading only the intraday rows of a loaded date
            }
        }
        String status = text(row, "TRADE_STATUS");
        String maturity = text(row, "MATURITY_DATE");
        boolean live = rules.live(status) && !matured(maturity);
        if (!live && !intra) {
            excluded++;
            if (notLive.size() < NOT_LIVE_KEPT && trade != null) {
                notLive.put(trade, !rules.live(status) ? "its status is " + status : "it matured on " + maturity.substring(0, 10));
            }
            return;
        }
        double[] vector = values;
        double pvUsd = Double.NaN;
        String[] why = null;
        if (live) {
            if (fx == null) {
                String w = check(values, length, text(row, "STATUS"), text(row, "REPORTING_CCY"));
                why = w == null ? null : new String[] {ErrorCode.VAR_ROW_REJECTED.code(), w};
            } else {
                why = checkPv(values, length, row);
                if (why == null && length >= 0) {
                    ScenarioFx.Rates r = fx.get().rates(currency(row)).orElseThrow();
                    double base = VarLoader.number(text(row, "BASE_PV"));
                    ScenarioFx.pnlFromPv(base, values, length, r, usd);
                    vector = usd;
                    pvUsd = base * r.base();
                    fxUsed.putIfAbsent(r.currency(), Map.of("source", r.source(), "base", r.base()));
                }
            }
        }
        if (why != null) {
            reject(why, trade);
            return;
        }
        for (int c = 0; c < texts.length; c++) {
            texts[c] = text(row, VarBook.TRADE_TEXTS.get(c));
        }
        for (int c = 0; c < numbers.length; c++) {
            numbers[c] = VarLoader.number(text(row, VarBook.TRADE_NUMBERS.get(c)));
        }
        if (fx != null) {
            numbers[VarBook.TRADE_NUMBERS.indexOf("PV")] = pvUsd;           // the trade's USD value today, as a P&L vector's PV
            if (!intra) {
                scenarioPv++;
                if (pvTrades.size() < NOT_LIVE_KEPT && trade != null) {
                    pvTrades.add(trade);
                }
            }
        }
        PositionRules.Derived d = rules.derive(trade, text(row, "TT_BOOK"), text(row, "PRODUCT_GROUP"), text(row, "PRODUCT_TYPE"),
                k -> text(row, k));
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_ID")] = d.positionId();
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_RULE")] = d.rule();
        // the currency the trade's PVs came in (VAR_GUIDE.md section 15.11): a scenario-PV row's CURRENCY; none for a P&L vector (USD)
        texts[VarBook.TRADE_TEXTS.indexOf("PV_CURRENCY")] = fx != null ? currency(row) : null;
        texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT_ID")] = d.instrumentId();
        texts[VarBook.TRADE_TEXTS.indexOf("SECURITY_ID")] = d.securityId();
        if (texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT")] == null) {
            texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT")] = d.securityId() != null ? d.securityId()
                    : text(row, "PRODUCT_TYPE") == null ? trade : text(row, "PRODUCT_TYPE") + " " + trade;
        }
        if (intra) {                                                    // an intraday update: the latest per trade kept, applied on the book
            if (live && length < 0) {
                return;                                                 // no PVs and not cancelled: nothing to apply
            }
            double pvAt = numbers[VarBook.TRADE_NUMBERS.indexOf("PV")];
            double[] pnl = live ? java.util.Arrays.copyOf(vector, width) : null;
            intraday.offer(new VarIntradayRows.Row(trade, at, batch == null ? "" : batch, new com.ash.drishti.compute.var.VarIntraday.Update(trade,
                    pnl, pvAt, texts.clone(), numbers.clone(), pvAt, pnl == null ? null : pnl.clone())));
            return;
        }
        book.trade(trade, vector, length, texts, numbers);
    }

    private void reject(String[] why, String trade) {
        rejected++;
        if (rejections.size() < LISTED) {
            rejections.add(why[0] + " " + trade + ": " + why[1]);
        }
        if (currentIntra && intradayRejections.size() < LISTED) {
            intradayRejections.add(why[0] + " " + trade + ": " + why[1]);
        }
    }

    private boolean currentIntra;
    private final List<String> intradayRejections = new ArrayList<>();

    /** The intraday rows rejected, in words (the first 20). */
    List<String> intradayRejections() {
        return List.copyOf(intradayRejections);
    }

    // ---------------------------------------------------------------------------------------------------------- intraday

    /** The marker of an intraday update (VAR_GUIDE.md section 8.4): {@code RUN_KIND: INTRADAY}. A full run has none, or {@code FULL}. */
    static final String INTRADAY = "INTRADAY";
    private final VarIntradayRows intraday = new VarIntradayRows();
    private final TreeSet<String> intradayBatches = new TreeSet<>();
    private final TreeSet<String> intradayParts = new TreeSet<>();
    private java.util.Set<String> applied = java.util.Set.of();
    private boolean intradayOnly;
    private String setId;
    private String morningAt;

    /** The date's scenario set: an intraday row of another is rejected ({@code DRS-5203}), never applied. */
    VarIntake scenarioSet(String id) {
        this.setId = id;
        return this;
    }

    /** Reads only the intraday rows of a loaded date, skipping the batch parts ({@code <kind>|<BATCH_ID>}) {@code applied} already. */
    VarIntake intradayOnly(java.util.Set<String> applied) {
        this.intradayOnly = true;
        this.applied = applied == null ? java.util.Set.of() : applied;
        return this;
    }

    /** The intraday updates read (the latest per trade). */
    VarIntradayRows intraday() {
        return intraday;
    }

    /** The intraday batch parts met, {@code <kind>|<BATCH_ID>}: a batch sent in both kinds is two parts, each loaded on its own. */
    java.util.Set<String> intradayParts() {
        return java.util.Set.copyOf(intradayParts);
    }

    /** The intraday batches met (applied before or not). */
    java.util.Set<String> intradayBatches() {
        return java.util.Set.copyOf(intradayBatches);
    }

    /** The newest {@code COMPUTED_AT} of the morning run's rows (an intraday update before it is older than the run, and ignored). */
    String morningAt() {
        return morningAt;
    }

    private String currency(String[] row) {
        String c = text(row, "CURRENCY");
        return c == null ? ScenarioFx.USD : c.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private boolean matured(String maturity) {
        if (maturity == null || maturity.length() < 10) {
            return false;
        }
        try {
            return LocalDate.parse(maturity.substring(0, 10)).isBefore(date);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** Why a row is rejected, or null: the vector's length and values, its currency, a failed row's vector. */
    private String check(double[] values, int length, String status, String currency) {
        if (length < 0) {
            return null;                                                // no vector: a failed row, counted by the builder
        }
        if ("failed".equalsIgnoreCase(status)) {
            return "STATUS is failed but the row carries a vector";
        }
        if (length != width) {
            return "the vector has " + length + " values; the scenario set has " + width;
        }
        for (int j = 0; j < length; j++) {
            if (!Double.isFinite(values[j])) {
                return "the vector holds " + (Double.isNaN(values[j]) ? "a null or NaN" : "an infinite") + " value at scenario " + j;
            }
        }
        if (currency != null && !currency.equalsIgnoreCase("USD")) {
            return "the vector is in " + currency + "; vectors are in USD (REPORTING_CCY)";
        }
        return null;
    }

    /** Why a scenario-PV row is rejected ({code, reason}), or null: its PVs, their valuation dates, its currency's FX. */
    private String[] checkPv(double[] values, int length, String[] row) {
        String bad = ErrorCode.VAR_ROW_REJECTED.code();
        if (length < 0) {
            return null;                                                // no scenario PVs: a failed row, counted by the builder
        }
        if ("failed".equalsIgnoreCase(text(row, "STATUS"))) {
            return new String[] {bad, "STATUS is failed but the row carries scenario PVs"};
        }
        if (length != width) {
            return new String[] {bad, "the row has " + length + " scenario PVs; the scenario set has " + width};
        }
        String valued = text(row, "VALUATION_DATE");
        String baseValued = text(row, "BASE_VALUATION_DATE");
        if (valued != null && baseValued != null && !valued.regionMatches(0, baseValued, 0, 10)) {
            return new String[] {bad, "the scenario PVs are valued on " + head(valued) + " and the base PV on " + head(baseValued) + ": one run values both on one date"};
        }
        for (String v : new String[] {valued, baseValued}) {
            if (v != null && !head(v).equals(date.toString())) {
                return new String[] {bad, "the PVs are valued on " + head(v) + ", not on the business date " + date};
            }
        }
        double base = VarLoader.number(text(row, "BASE_PV"));
        if (!Double.isFinite(base)) {
            return new String[] {bad, "no BASE_PV: the P&L is the scenario PV less the base PV of the same run"};
        }
        List<Integer> failed = new ArrayList<>();
        for (int j = 0; j < length; j++) {
            if (!Double.isFinite(values[j])) {
                failed.add(j);
            }
        }
        if (!failed.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(5, failed.size()); i++) {
                int j = failed.get(i);
                b.append(i == 0 ? "" : ", ").append(j).append(scenarioDates == null || j >= scenarioDates.size() ? "" : " (" + scenarioDates.get(j) + ")");
            }
            return new String[] {ErrorCode.VAR_SCENARIO_FAILED.code(), failed.size() + " scenario" + (failed.size() == 1 ? "" : "s")
                    + " failed to price: " + b + (failed.size() > 5 ? ", ..." : "") + "; the trade is not loaded (never filled with zero)"};
        }
        String ccy = currency(row);
        if (fx.get().rates(ccy).isEmpty()) {
            String note = fx.get().notes().stream().filter(n -> n.startsWith(ccy + ":")).findFirst().orElse(ccy + ": no rates");
            return new String[] {ErrorCode.VAR_NO_FX.code(), "its PVs are in " + ccy + " and the date has no " + ccy + " scenario FX (" + note + ")"};
        }
        return null;
    }

    private static String head(String d) {
        return d.length() >= 10 ? d.substring(0, 10) : d;
    }

    /** Per currency the scenario PVs were in: the source of its rates and today's rate. */
    Map<String, Object> fx() {
        return Map.copyOf(fxUsed);
    }

    Summary summary() {
        return new Summary(rejected, List.copyOf(rejections), excluded, List.copyOf(batches), List.copyOf(sets), rules.toString(), rules.digest(),
                Map.copyOf(notLive), scenarioPv, java.util.Set.copyOf(pvTrades));
    }
}
