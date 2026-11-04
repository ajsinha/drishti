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
package com.ash.drishti.compute.saccr;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one SA-CCR run reads (docs/guides/SACCR_GUIDE.md section 3; docs/contracts/SACCR.md sections 3.3 and 4): the netting set's header
 * (its value, collateral and CSA terms) and its classification rows ({@code saccr-trade}), each with its times in years already.
 *
 * @param set the netting set's header
 * @param trades its classification rows (one per trade and risk driver)
 */
public record SaccrInput(NettingSet set, List<Trade> trades) {

    public SaccrInput {
        trades = List.copyOf(trades);
    }

    /**
     * The netting set's header (COUNTERPARTY_ENGINES.md section 3.1).
     *
     * @param id the netting set
     * @param counterparty its counterparty
     * @param legalEntity the bank's legal entity facing it
     * @param v Σ MTM of the in-scope members; NaN: the sum of the rows' {@code mtm} (driver 0 rows only)
     * @param c the haircut value of net collateral held (supervisory haircuts, decision 1), NICA included
     * @param nica the net independent collateral amount
     * @param th {@code THRESHOLD_THEM}, USD
     * @param mta {@code MTA_THEM}, USD
     * @param margined a CSA with variation margin and its terms present
     * @param cleared the set faces a qualifying CCP
     * @param mporDays the CSA's {@code MPOR_DAYS} (excluding the remargining period, decision 2)
     * @param marginFrequencyDays the CSA's {@code MARGIN_FREQUENCY_DAYS}
     * @param disputes {@code DISPUTES_LAST_2Q}
     * @param illiquidCollateral {@code ILLIQUID_COLLATERAL}
     * @param members the set's member count (the 5,000-trade MPOR floor); 0: the rows' count
     */
    public record NettingSet(String id, String counterparty, String legalEntity, double v, double c, double nica, double th, double mta, boolean margined,
            boolean cleared, int mporDays, int marginFrequencyDays, int disputes, boolean illiquidCollateral, int members) {

        /** An unmargined set with a value and collateral (the hand-built cases). */
        public static NettingSet unmargined(String id, double v, double c) {
            return new NettingSet(id, null, null, v, c, 0, 0, 0, false, false, 0, 1, 0, false, 0);
        }

        /** A margined bilateral set (the hand-built cases). */
        public static NettingSet margined(String id, double v, double c, double nica, double th, double mta, int mporDays, int frequency, int disputes) {
            return new NettingSet(id, null, null, v, c, nica, th, mta, true, false, mporDays, frequency, disputes, false, 0);
        }

        /** The same header with another value V. */
        public NettingSet withV(double value) {
            return new NettingSet(id, counterparty, legalEntity, value, c, nica, th, mta, margined, cleared, mporDays, marginFrequencyDays, disputes,
                    illiquidCollateral, members);
        }

        /** The same header, unmargined (missing CSA terms: flagged MARGIN_TERMS_MISSING by the caller). */
        public NettingSet asUnmargined() {
            return new NettingSet(id, counterparty, legalEntity, v, c, nica, th, mta, false, cleared, mporDays, marginFrequencyDays, disputes,
                    illiquidCollateral, members);
        }
    }

    /**
     * One {@code saccr-trade} row (SACCR.md section 3.3) with its dates turned into years ACT/365 from the business date.
     *
     * @param tradeId the trade
     * @param driver 0 the primary risk driver; 1, 2 … further drivers
     * @param assetClass {@code IR}, {@code FX}, {@code CREDIT}, {@code EQUITY}, {@code COMMODITY}
     * @param hedgingSet IR the currency, FX the pair, credit {@code CREDIT}, equity {@code EQUITY}, commodity {@code ENERGY},
     *     {@code METALS}, {@code AGRICULTURAL}, {@code OTHER}; a basis or volatility set its own name
     * @param transactionType {@code LINEAR}, {@code OPTION}, {@code CDO_TRANCHE}, {@code BASIS}, {@code VOLATILITY}
     * @param reference credit and equity: the entity or index; commodity: the commodity type ({@code ELECTRICITY} has its own factor)
     * @param index an index (credit, equity)
     * @param creditQuality {@code AAA} … {@code CCC} for a single name, {@code IG} or {@code SG} for an index
     * @param direction {@code LONG} or {@code SHORT} in the primary risk factor
     * @param notional the adjusted-notional basis: {@code NOTIONAL_USD}, or {@code QUANTITY} × {@code UNDERLYING_PRICE}
     * @param s S, years (IR and credit; NaN: 0)
     * @param e E, years (IR and credit; NaN: M)
     * @param m M, years
     * @param t T, years (options)
     * @param optionType {@code CALL} or {@code PUT}
     * @param boughtSold {@code BOUGHT} or {@code SOLD} (options; tranches: protection)
     * @param strike K
     * @param forward P
     * @param shift λ (NaN: 0)
     * @param attachment A (tranches)
     * @param detachment D (tranches)
     * @param mtm the trade's MTM today (NaN: none; counted on driver 0 only)
     * @param attributes every delivered field, as delivered (the playground's Data tab)
     */
    public record Trade(String tradeId, int driver, String assetClass, String hedgingSet, String transactionType, String reference, boolean index,
            String creditQuality, String direction, double notional, double s, double e, double m, double t, String optionType, String boughtSold,
            double strike, double forward, double shift, double attachment, double detachment, double mtm, Map<String, Object> attributes) {

        public Trade {
            assetClass = up(assetClass);
            hedgingSet = up(hedgingSet);
            transactionType = transactionType == null || transactionType.isBlank() ? "LINEAR" : up(transactionType);
            reference = reference == null ? "" : reference.strip();
            creditQuality = up(creditQuality);
            direction = up(direction);
            optionType = up(optionType);
            boughtSold = up(boughtSold);
            attributes = attributes == null ? Map.of() : attributes;
        }

        /** A linear row (the hand-built cases). */
        public static Trade linear(String id, String assetClass, String hedgingSet, String direction, double notional, double s, double e, double m) {
            return new Trade(id, 0, assetClass, hedgingSet, "LINEAR", null, false, null, direction, notional, s, e, m, Double.NaN, null, null, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null);
        }

        /** The same row with a reference, index flag and credit quality. */
        public Trade named(String ref, boolean isIndex, String quality) {
            return new Trade(tradeId, driver, assetClass, hedgingSet, transactionType, ref, isIndex, quality, direction, notional, s, e, m, t, optionType,
                    boughtSold, strike, forward, shift, attachment, detachment, mtm, attributes);
        }

        /** The same row as an option. */
        public Trade option(String type, String bs, double expiry, double k, double p, double lambda) {
            return new Trade(tradeId, driver, assetClass, hedgingSet, "OPTION", reference, index, creditQuality, direction, notional, s, e, m, expiry, type,
                    bs, k, p, lambda, attachment, detachment, mtm, attributes);
        }

        /** The same row with another MTM. */
        public Trade withMtm(double value) {
            return new Trade(tradeId, driver, assetClass, hedgingSet, transactionType, reference, index, creditQuality, direction, notional, s, e, m, t,
                    optionType, boughtSold, strike, forward, shift, attachment, detachment, value, attributes);
        }

        private static String up(String x) {
            return x == null ? null : x.strip().toUpperCase(Locale.ROOT);
        }
    }

    /** Years ACT/365 from {@code from} to {@code to} (NaN when {@code to} is null). */
    public static double years(LocalDate from, LocalDate to) {
        return to == null ? Double.NaN : ChronoUnit.DAYS.between(from, to) / 365.0;
    }
}
