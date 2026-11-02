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
package com.ash.drishti.compute.simm;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one SIMM run reads (docs/guides/SIMM_GUIDE.md section 3): the netting set's trades (or a scope's records) with their product
 * class, their sensitivity elements in the calculation currency with the factor's SIMM mapping (the CRIF terms), and the CSA's add-on
 * terms. The direction is not here: post is the same input with every sensitivity negated ({@link Simm#run}).
 *
 * @param trades the trades (or position records), each element names one by its index
 * @param elements the sensitivity elements, converted to the calculation currency
 * @param calculationCurrency the CSA's calculation currency
 * @param addOns the CSA's add-ons (none: {@link AddOns#NONE})
 */
public record SimmInput(List<Trade> trades, List<Element> elements, String calculationCurrency, AddOns addOns) {

    public SimmInput {
        trades = List.copyOf(trades);
        elements = List.copyOf(elements);
        calculationCurrency = calculationCurrency == null || calculationCurrency.isBlank() ? "USD" : calculationCurrency.strip().toUpperCase(Locale.ROOT);
        addOns = addOns == null ? AddOns.NONE : addOns;
    }

    /**
     * A trade (or a position record) of the netting set.
     *
     * @param id the trade id (or the record's position key)
     * @param productClass {@code RatesFX}, {@code Credit}, {@code Equity} or {@code Commodity} (SIMM paragraph 6: every risk of the trade
     *     stays in it)
     * @param attributes its placement and terms, as delivered
     */
    public record Trade(String id, String productClass, Map<String, String> attributes) {
        public Trade {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /**
     * The SIMM mapping of one dictionary factor (the CRIF terms, docs/contracts/SIMM.md section 3.2).
     *
     * @param name the standard name ({@code IR/CURVE/USD/SOFR/10Y})
     * @param riskClass the dictionary's {@code SIMM_RISK_CLASS}: {@code InterestRate}, {@code CreditQ}, {@code CreditNonQ}, {@code Equity},
     *     {@code Commodity} or {@code FX}
     * @param type the dictionary's {@code FACTOR_TYPE}: {@code CURVE}, {@code INFLATION}, {@code BASIS}, {@code SPREAD}, {@code SPOT},
     *     {@code REPO}, {@code PRICE} or {@code VOL}
     * @param bucket the dictionary's {@code SIMM_BUCKET} (for rates the currency's volatility group; {@code Residual} as text)
     * @param qualifier the currency (rates, FX), the issuer (credit), the name (equity), the commodity
     * @param label1 the SIMM tenor or the volatility's expiry ({@code 5y}, {@code 2w}; {@code Inf} for inflation)
     * @param label2 the rates sub-curve ({@code OIS}); for credit the instrument curve
     * @param seniority the credit seniority (the concentration's grain is the issuer and seniority)
     */
    public record Factor(String name, String riskClass, String type, String bucket, String qualifier, String label1, String label2, String seniority) {

        /** The issuer key of a credit factor: the qualifier and its seniority. */
        public String issuer() {
            return seniority == null || seniority.isBlank() ? qualifier : qualifier + "/" + seniority.strip().toUpperCase(Locale.ROOT);
        }

        /** {@code label1} in years ({@code 2w} 14/365, {@code 6m} 0.5, {@code 10y} 10); NaN when it is not a tenor. */
        public double years() {
            return Factor.years(label1);
        }

        /** A SIMM tenor label in years; NaN when it is not one. */
        public static double years(String label) {
            if (label == null || label.isBlank()) {
                return Double.NaN;
            }
            String t = label.strip().toLowerCase(Locale.ROOT);
            try {
                double n = Double.parseDouble(t.substring(0, t.length() - 1));
                return switch (t.charAt(t.length() - 1)) {
                    case 'y' -> n;
                    case 'm' -> n / 12.0;
                    case 'w' -> n * 7 / 365.0;
                    case 'd' -> n / 365.0;
                    default -> Double.NaN;
                };
            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                return Double.NaN;
            }
        }
    }

    /**
     * One sensitivity element, in the calculation currency.
     *
     * @param trade the trade's index in {@link #trades}
     * @param factor the factor's SIMM mapping (null: not in the dictionary)
     * @param measure {@code DV01}, {@code IE01}, {@code CS01}, {@code DELTA}, {@code VEGA}, {@code BASE_CORR} (curvature revaluations and
     *     gammas are not SIMM inputs and are left out, counted)
     * @param value the delivered value in the calculation currency (per bp, per 1 %, per volatility point)
     * @param impliedVol the implied volatility of a vega, a fraction (NaN when none)
     */
    public record Element(int trade, Factor factor, String measure, double value, double impliedVol) {}

    /**
     * The CSA's add-ons (SIMM section L): {@code AddOnIM = fixed + Σ factor_product · notional + Σ_p (MS_p − 1) · SIMM_p}.
     *
     * @param fixed the fixed amount in the calculation currency
     * @param multipliers product class → multiplier MS_p (each at least 1)
     * @param factors product → notional factor (a fraction of the notional)
     * @param lines the trades with a notional add-on, their product and notional (in the calculation currency)
     */
    public record AddOns(double fixed, Map<String, Double> multipliers, Map<String, Double> factors, List<Line> lines) {

        public static final AddOns NONE = new AddOns(0, Map.of(), Map.of(), List.of());

        public AddOns {
            multipliers = multipliers == null ? Map.of() : Map.copyOf(multipliers);
            factors = factors == null ? Map.of() : Map.copyOf(factors);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** True when the CSA adds nothing. */
        public boolean none() {
            return fixed == 0 && multipliers.isEmpty() && lines.isEmpty();
        }
    }

    /** A notional add-on line: a trade, the product its factor is agreed for, the notional in the calculation currency. */
    public record Line(String trade, String product, double notional) {}
}
