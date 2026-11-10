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

import com.ash.drishti.banking.compute.MethodChoices;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The method choices of FRTB-SA as named switches (docs/guides/FRTB_SA_GUIDE.md section 12.1a, FRTB_SA_GUIDE.md section 8): each with its
 * decided default (section 16), its alternatives, its rule and why the default was chosen. A run is the choices plus the overrides
 * ({@code param.<path>} of the parameter files); anything but the defaults and no overrides is an analysis, and a run with an override is
 * never a regulatory figure.
 *
 * @param curvatureSource {@code revaluation} (the up and down PVs where delivered; gamma only where a factor has no revaluation) or
 *     {@code gamma} (the second-order estimate wherever a gamma is delivered)
 * @param girrCurvatureShock {@code undivided} (1.7%) or {@code divided} (1.7%/√2 for the specified currencies and the reporting currency)
 * @param fxCurvatureDivisor {@code non-reporting-options} (÷ 1.5 for FX options not referencing the reporting currency), {@code off},
 *     {@code all} (every FX instrument, with the supervisor's approval)
 * @param girrCorrelation {@code table} (MAR21.46's published table, 0.1%) or {@code exact} (the formula behind it)
 * @param scenario {@code binding} (the largest of the three), {@code low}, {@code medium} or {@code high}
 * @param overrides parameter overrides, path → value (empty: none)
 */
public record FrtbChoices(String curvatureSource, String girrCurvatureShock, String fxCurvatureDivisor, String girrCorrelation, String scenario,
        Map<String, String> overrides) implements MethodChoices<FrtbChoices> {

    public static final List<Switch> SWITCHES = List.of(
            new Switch("curvature-source", "revaluation", List.of("revaluation", "gamma"), "MAR21.5, MAR21.17",
                    "the rule's curvature is the two full revaluations; an estimate from gamma is an analysis figure until the supervisor accepts it"),
            new Switch("girr-curvature-shock", "undivided", List.of("undivided", "divided"), "MAR21.99",
                    "the rule shifts GIRR curves by the highest delta risk weight, 1.7%; dividing it by √2 waits for a regulator to say so"),
            new Switch("fx-curvature-divisor", "non-reporting-options", List.of("non-reporting-options", "off", "all"), "MAR21.98",
                    "the division by 1.5 applies by default only where the rule allows it; every FX instrument only with the supervisor's approval"),
            new Switch("girr-correlation", "table", List.of("table", "exact"), "MAR21.46",
                    "the published table (rounded to 0.1%) is what regulators expect the bank's figures to match"),
            new Switch("scenario", "binding", List.of("binding", "low", "medium", "high"), "MAR21.6, MAR21.7",
                    "the capital is the largest of the three scenario totals; a single scenario is shown for analysis"));

    public FrtbChoices {
        curvatureSource = pick("curvature-source", curvatureSource);
        girrCurvatureShock = pick("girr-curvature-shock", girrCurvatureShock);
        fxCurvatureDivisor = pick("fx-curvature-divisor", fxCurvatureDivisor);
        girrCorrelation = pick("girr-correlation", girrCorrelation);
        scenario = pick("scenario", scenario);
        overrides = overrides == null ? Map.of() : Map.copyOf(new TreeMap<>(overrides));
    }

    /** The decided defaults and no override. */
    public static FrtbChoices defaults() {
        return new FrtbChoices(null, null, null, null, null, Map.of());
    }

    private static String pick(String name, String value) {
        return MethodChoices.pick(SWITCHES, name, value);
    }

    /** The value of a switch by name. */
    public String get(String name) {
        return switch (name) {
            case "curvature-source" -> curvatureSource;
            case "girr-curvature-shock" -> girrCurvatureShock;
            case "fx-curvature-divisor" -> fxCurvatureDivisor;
            case "girr-correlation" -> girrCorrelation;
            case "scenario" -> scenario;
            default -> throw new IllegalArgumentException("no switch " + name);
        };
    }

    /** The same choices with one switch set. */
    public FrtbChoices with(String name, String value) {
        Map<String, String> m = asMap();
        m.put(name, value);
        return of(m, overrides);
    }

    /** The choices from switch name → value (missing: the default). */
    public static FrtbChoices of(Map<String, String> switches, Map<String, String> overrides) {
        Map<String, String> s = switches == null ? Map.of() : switches;
        for (String k : s.keySet()) {
            if (SWITCHES.stream().noneMatch(x -> x.name().equals(k))) {
                throw new IllegalArgumentException("no switch " + k + "; the switches are " + SWITCHES.stream().map(Switch::name).toList());
            }
        }
        return new FrtbChoices(s.get("curvature-source"), s.get("girr-curvature-shock"), s.get("fx-curvature-divisor"), s.get("girr-correlation"),
                s.get("scenario"), overrides);
    }

    @Override
    public List<Switch> switches() {
        return SWITCHES;
    }
}
