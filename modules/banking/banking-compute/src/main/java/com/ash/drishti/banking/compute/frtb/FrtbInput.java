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
package com.ash.drishti.banking.compute.frtb;

import java.util.List;
import java.util.Map;

/**
 * What one FRTB-SA run reads (FRTB_SA_GUIDE.md section 3): the scope's positions and their sensitivity elements (already converted to the
 * reporting currency), the default-risk positions for the DRC and the residual-risk instruments for the RRAO. A null DRC or RRAO list
 * means the input was not delivered: the answer says so and never shows a zero.
 *
 * @param positions the scope's positions (or trades), each element names one by its index
 * @param elements the sensitivity elements
 * @param reporting the reporting currency
 * @param drc the default-risk positions; null when no {@code default-risk} input exists
 * @param rrao the residual-risk instruments; null when none were delivered or derived
 */
public record FrtbInput(List<Position> positions, List<Element> elements, String reporting, List<DrcPosition> drc, List<RraoItem> rrao) {

    public FrtbInput {
        positions = List.copyOf(positions);
        elements = List.copyOf(elements);
        reporting = reporting == null || reporting.isBlank() ? "USD" : reporting.strip();
        drc = drc == null ? null : List.copyOf(drc);
        rrao = rrao == null ? null : List.copyOf(rrao);
    }

    /**
     * A position (or trade) of the scope.
     *
     * @param id its id ({@code POS-...} or the trade id)
     * @param productType the record's {@code PRODUCT_TYPE}
     * @param attributes its placement and terms ({@code DESK}, {@code TT_BOOK}, {@code LEGAL_ENTITY}, ...) for contributions by attribute
     */
    public record Position(String id, String productType, Map<String, String> attributes) {
        public Position {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /**
     * One sensitivity element, in the reporting currency.
     *
     * @param position the position's index in {@link #positions}
     * @param factor the dictionary's factor
     * @param measure {@code DV01}, {@code IE01}, {@code CS01}, {@code DELTA}, {@code VEGA}, {@code CURVATURE_UP}, {@code CURVATURE_DOWN},
     *     {@code GAMMA}
     * @param value the delivered value converted to the reporting currency
     * @param shockSize the curvature shock the source applied ({@code SHOCK_SIZE}, NaN when none)
     * @param impliedVol the implied volatility of a vega ({@code IMPLIED_VOL}, NaN when none)
     */
    public record Element(int position, FactorDictionary.Factor factor, String measure, double value, double shockSize, double impliedVol) {}

    /**
     * A default-risk position (FRTB.md section 3.3; MAR22).
     *
     * @param id the row's id
     * @param position the position's index in {@link #positions}, or −1 when it is not one of the scope's sensitivity positions
     * @param obligor the issuer or reference entity
     * @param bucket {@code CORPORATE}, {@code SOVEREIGN} or {@code LOCAL_GOVERNMENT_MUNICIPALITY}
     * @param seniority {@code COVERED_BOND}, {@code SENIOR}, {@code NON_SENIOR}, {@code EQUITY}
     * @param creditQuality {@code AAA} ... {@code CCC}, {@code UNRATED}, {@code DEFAULTED}
     * @param notional the bond-equivalent notional, positive long (loses on default), negative short
     * @param marketValue the market value of the exposure
     * @param maturityYears the instrument's residual maturity in years; for cash equity the bank's 0.25 or 1, NaN for the parameter file's
     * @param lgd an LGD override (a recovery-independent instrument: 1), NaN for the parameter file's by seniority
     * @param cashEquity true for a cash equity position (maturity scaling of MAR22.16)
     */
    public record DrcPosition(String id, int position, String obligor, String bucket, String seniority, String creditQuality, double notional,
            double marketValue, double maturityYears, double lgd, boolean cashEquity) {}

    /**
     * A residual-risk instrument (FRTB.md section 3.4; MAR23).
     *
     * @param id the trade or position id
     * @param position the position's index, or −1
     * @param productType the product type (mapped to EXOTIC or OTHER by the parameter file when {@code rraoClass} is null)
     * @param rraoClass {@code EXOTIC}, {@code OTHER}, or null to map from the product type
     * @param grossNotional the gross notional, absolute
     * @param backToBack MAR23.7: excluded entirely
     * @param listedOrCleared MAR23.7: excluded from the other-residual part only
     */
    public record RraoItem(String id, int position, String productType, String rraoClass, double grossNotional, boolean backToBack,
            boolean listedOrCleared) {}
}
