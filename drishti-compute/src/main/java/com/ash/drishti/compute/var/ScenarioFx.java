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
package com.ash.drishti.compute.var;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The FX of one business date's scenario set (VAR_GUIDE.md section 15.9): for every currency, today's rate and its rate under each
 * scenario, always as <b>USD per one unit</b> of the currency. Every cross rate goes through USD (product owner, 2026-10-27): units of
 * {@code B} per unit of {@code A} are {@code usdPer(A) / usdPer(B)}, never a direct pair.
 *
 * <p>The P&amp;L of a trade priced as scenario PVs in its own currency {@code c}, in USD:
 * {@code P&L(s) = PV(s) × FX_c(s) − PV(base) × FX_c(base)} ({@link #pnlFromPv}). A USD P&amp;L vector with the trade's USD value today
 * {@code p} in another reporting currency {@code r}: {@code P&L_r(s) = (p + P&L(s)) / FX_r(s) − p / FX_r(base)} ({@link #convert}):
 * the trade's USD value under the scenario, at the scenario's rate, less today's value at today's rate. The two are the same
 * triangulation through USD, so a scenario-PV trade and a P&amp;L-vector trade convert alike, and the currency move is part of the risk:
 * the same trades give a different VaR in each currency.
 *
 * <p>Scenario rates are supplied by the batch (the {@code fx-scenario} kind) or derived from the FX history ({@link #derive}): today's
 * rate moved by the scenario day's 1-day relative move ({@code HORIZON_DAYS} 1), {@code FX(base) × H(d) / H(d - 1)}, the day before
 * being the history's previous date.
 */
public final class ScenarioFx {

    public static final String USD = "USD";

    /**
     * One currency against USD.
     *
     * @param currency the ISO code, upper case
     * @param base today's rate (USD per unit)
     * @param scenarios the rate under each scenario, in the scenario set's order (USD per unit)
     * @param source {@code supplied} (the batch's scenario FX) or {@code derived} (from the FX history), {@code base} for USD
     */
    public record Rates(String currency, double base, double[] scenarios, String source) {

        public Rates {
            currency = currency.toUpperCase(Locale.ROOT);
        }

        /** The rate under scenario {@code j}; {@code -1} is today's. */
        public double at(int j) {
            return j < 0 ? base : scenarios[j];
        }
    }

    private final int width;
    private final Map<String, Rates> rates = new TreeMap<>();
    private final List<String> notes;

    /**
     * The rates of one date; every currency's scenarios must have {@code width} values, finite and positive, like its base (else it is
     * left out and said in {@link #notes()}). USD is always there (1 everywhere).
     */
    public ScenarioFx(int width, Collection<Rates> given) {
        this.width = width;
        List<String> n = new ArrayList<>();
        double[] ones = new double[width];
        java.util.Arrays.fill(ones, 1.0);
        rates.put(USD, new Rates(USD, 1.0, ones, "base"));
        for (Rates r : given) {
            String why = invalid(r, width);
            if (why != null) {
                n.add(r.currency() + ": " + why);
            } else if (!r.currency().equals(USD)) {
                rates.put(r.currency(), r);
            }
        }
        this.notes = List.copyOf(n);
    }

    private static String invalid(Rates r, int width) {
        if (!(r.base() > 0) || !Double.isFinite(r.base())) {
            return "no rate today";
        }
        if (r.scenarios() == null || r.scenarios().length != width) {
            return (r.scenarios() == null ? 0 : r.scenarios().length) + " scenario rates; the scenario set has " + width;
        }
        List<Integer> bad = new ArrayList<>();
        for (int j = 0; j < width; j++) {
            if (!(r.scenarios()[j] > 0) || !Double.isFinite(r.scenarios()[j])) {
                bad.add(j);
            }
        }
        return bad.isEmpty() ? null : "no rate under " + bad.size() + " scenario" + (bad.size() == 1 ? "" : "s") + " (first: scenario " + bad.get(0) + ")";
    }

    public int width() {
        return width;
    }

    /** The currencies with rates (USD included). */
    public java.util.Set<String> currencies() {
        return java.util.Collections.unmodifiableSet(rates.keySet());
    }

    /** A currency's rates against USD, or empty when the date has none for it. */
    public Optional<Rates> rates(String currency) {
        return currency == null ? Optional.empty() : Optional.ofNullable(rates.get(currency.toUpperCase(Locale.ROOT)));
    }

    /** What was left out and why (a currency with a missing or bad rate). */
    public List<String> notes() {
        return notes;
    }

    /** Units of {@code to} per one unit of {@code from} under scenario {@code j} ({@code -1}: today), through USD. NaN when either is missing. */
    public double cross(String from, String to, int j) {
        Rates a = rates(from).orElse(null);
        Rates b = rates(to).orElse(null);
        return a == null || b == null ? Double.NaN : a.at(j) / b.at(j);
    }

    /**
     * The USD P&amp;L of a trade priced in its own currency: {@code out[s] = pv[s] × fx(s) − basePv × fx(base)} for the first
     * {@code length} scenarios.
     */
    public static void pnlFromPv(double basePv, double[] pv, int length, Rates fx, double[] out) {
        double today = basePv * fx.base();
        for (int s = 0; s < length; s++) {
            out[s] = pv[s] * fx.scenarios()[s] - today;
        }
    }

    /**
     * A USD P&amp;L vector in the reporting currency {@code rep}: {@code out[s] = (pvUsd + usd[s]) / rep(s) − pvUsd / rep(base)}. With no
     * value today ({@code pvUsd} 0) only the P&amp;L itself is converted at each scenario's rate.
     */
    public static void convert(double pvUsd, double[] usd, int length, Rates rep, double[] out) {
        double today = pvUsd / rep.base();
        for (int s = 0; s < length; s++) {
            out[s] = (pvUsd + usd[s]) / rep.scenarios()[s] - today;
        }
    }

    /**
     * Scenario rates derived from an FX history (USD per unit by date): today's rate moved by each scenario day's 1-day relative move,
     * {@code base × H(d) / H(previous date of the history)}. NaN where the history lacks the day or a day before it.
     */
    public static double[] derive(double base, List<LocalDate> scenarioDates, NavigableMap<LocalDate, Double> history) {
        double[] out = new double[scenarioDates.size()];
        for (int j = 0; j < out.length; j++) {
            LocalDate d = scenarioDates.get(j);
            Double now = history.get(d);
            Map.Entry<LocalDate, Double> before = history.lowerEntry(d);
            out[j] = now == null || before == null || !(before.getValue() > 0) ? Double.NaN : base * now / before.getValue();
        }
        return out;
    }

    /**
     * A date's FX from what it has (product owner, 2026-10-27): per currency, the batch's <b>supplied</b> scenario rates when it sent
     * them, else rates <b>derived</b> from the FX history ({@code history}, USD per unit by date) around today's rate ({@code today});
     * a currency neither covers is left out, with the reason in {@link #notes()} (its trades cannot be converted, its reporting
     * currency cannot be asked).
     */
    public static ScenarioFx assemble(List<LocalDate> scenarioDates, Collection<Rates> supplied, Map<String, Double> today,
            Map<String, ? extends NavigableMap<LocalDate, Double>> history) {
        Map<String, Rates> chosen = new TreeMap<>();
        for (Rates r : supplied) {
            chosen.put(r.currency(), r);
        }
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Double> e : today.entrySet()) {
            String c = e.getKey().toUpperCase(Locale.ROOT);
            if (chosen.containsKey(c) || c.equals(USD)) {
                continue;
            }
            NavigableMap<LocalDate, Double> h = history.get(e.getKey());
            if (h == null || h.isEmpty()) {
                notes.add(c + ": no scenario rates supplied and no FX history to derive them from");
                continue;
            }
            double[] d = derive(e.getValue(), scenarioDates, h);
            List<LocalDate> gaps = uncovered(scenarioDates, d);
            if (gaps.isEmpty()) {
                chosen.put(c, new Rates(c, e.getValue(), d, "derived"));
            } else {
                notes.add(c + ": no scenario rates supplied, and the FX history (" + h.firstKey() + " .. " + h.lastKey() + ") does not cover "
                        + gaps.size() + " of the " + scenarioDates.size() + " scenario days (first: " + gaps.get(0) + ")");
            }
        }
        ScenarioFx fx = new ScenarioFx(scenarioDates.size(), chosen.values());
        if (notes.isEmpty()) {
            return fx;
        }
        List<String> all = new ArrayList<>(fx.notes);
        all.addAll(notes);
        return new ScenarioFx(fx.width, fx.rates, all);
    }

    private ScenarioFx(int width, Map<String, Rates> rates, List<String> notes) {
        this.width = width;
        this.rates.putAll(rates);
        this.notes = List.copyOf(notes);
    }

    /** The scenario days a derivation could not cover (no rate on the day, or none before it), oldest first. */
    public static List<LocalDate> uncovered(List<LocalDate> scenarioDates, double[] derived) {
        List<LocalDate> out = new ArrayList<>();
        for (int j = 0; j < derived.length; j++) {
            if (Double.isNaN(derived[j])) {
                out.add(scenarioDates.get(j));
            }
        }
        return out;
    }
}
