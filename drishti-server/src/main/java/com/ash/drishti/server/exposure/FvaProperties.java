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
package com.ash.drishti.server.exposure;

import com.ash.drishti.compute.ccr.FvaMath;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FVA, funding cost and benefit (docs/guides/FVA_GUIDE.md section 9, docs/architecture/COUNTERPARTY_ENGINES.md section 5),
 * {@code drishti.ccr.fva}. Its switch is the engine's own, {@code drishti.engines.fva.enabled}; it needs the exposure engine.
 *
 * @param fundingSpread the borrowing spread: a curve id (read from {@code curve-kind}, else from {@code curves} here) or a flat spread in
 *     decimals ({@code 0.0050})
 * @param lendingSpread the lending spread FBA uses, as {@code funding-spread}; blank: the same as the borrowing spread (symmetric funding)
 * @param fallbackSpread the flat spread used, flagged {@code FUNDING_FALLBACK}, when the named curve is found nowhere
 * @param discountCurve the discount curve's id ({@code CRV-USD-OIS}; points {@code TENOR}, {@code DF}, log-linear in DF)
 * @param discountRate the flat continuously compounded rate used, flagged {@code FLAT_DISCOUNT}, when the discount curve is not found
 * @param curveKind the kind the curves are read from ({@code ir-curve}: {@code CURVE_ID}, {@code POINTS[].TENOR}, {@code DF} or {@code SPREAD})
 * @param curves funding curves named here (the demo's synthetic, labelled {@code CRV-USD-FUNDING}): used when the kind holds no such id
 * @param eePoint {@code end} (the period's end) or {@code mid} (the average of both ends)
 * @param fundingSurvival weight each term by S_C(t) · S_B(t) (off by default, decision 11)
 * @param counterpartyHazard the counterparty's flat hazard rate when survival weighting is on (per-counterparty credit curves: planned)
 * @param ownHazard the bank's own flat hazard rate when survival weighting is on
 * @param fba report the funding benefit (symmetric funding); false: FCA only
 * @param requireRiskNeutral refuse FVA on a run whose calibration is not {@code RISK_NEUTRAL} (DRS-5447); false: compute and flag
 *     {@code REAL_WORLD_RUN} on every figure (the demo)
 * @param playgroundMaxSets the most netting sets one playground and workbook shows side by side
 * @param playgroundTemplate a bank's workbook template folder (blank: the built-in {@code playground/fva/template/})
 */
@ConfigurationProperties("drishti.ccr.fva")
public record FvaProperties(String fundingSpread, String lendingSpread, Double fallbackSpread, String discountCurve, Double discountRate,
        String curveKind, List<Curve> curves, String eePoint, Boolean fundingSurvival, Double counterpartyHazard, Double ownHazard, Boolean fba,
        Boolean requireRiskNeutral, Integer playgroundMaxSets, String playgroundTemplate) {

    /** A funding curve named in the configuration: its id, what it is, its points. */
    public record Curve(String id, String label, List<Point> points) {
        public Curve {
            id = id == null ? "" : id.strip();
            label = label == null ? "" : label.strip();
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    /** One point of a configured curve: a tenor ({@code 6M}, {@code 1Y}) and the spread in decimals. */
    public record Point(String tenor, Double spread) {}

    public FvaProperties {
        fundingSpread = blank(fundingSpread) ? "CRV-USD-FUNDING" : fundingSpread.strip();
        lendingSpread = lendingSpread == null ? "" : lendingSpread.strip();
        fallbackSpread = fallbackSpread == null ? 0.0050 : fallbackSpread;
        discountCurve = blank(discountCurve) ? "CRV-USD-OIS" : discountCurve.strip();
        discountRate = discountRate == null ? 0.0 : discountRate;
        curveKind = blank(curveKind) ? "ir-curve" : curveKind.strip();
        curves = curves == null ? List.of() : List.copyOf(curves);
        eePoint = blank(eePoint) ? "end" : eePoint.strip().toLowerCase(java.util.Locale.ROOT);
        FvaMath.Point.of(eePoint);
        fundingSurvival = Boolean.TRUE.equals(fundingSurvival);
        counterpartyHazard = counterpartyHazard == null ? 0.0 : counterpartyHazard;
        ownHazard = ownHazard == null ? 0.0 : ownHazard;
        fba = fba == null || fba;
        requireRiskNeutral = requireRiskNeutral == null || requireRiskNeutral;
        playgroundMaxSets = playgroundMaxSets == null || playgroundMaxSets < 1 ? 20 : playgroundMaxSets;
        playgroundTemplate = playgroundTemplate == null ? "" : playgroundTemplate.strip();
    }

    /** The defaults (tests, and a server without the block). */
    public static FvaProperties defaults() {
        return new FvaProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public FvaMath.Point point() {
        return FvaMath.Point.of(eePoint);
    }

    /** The flat spread when {@code s} is a number, else null (a curve id). */
    static Double flat(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
