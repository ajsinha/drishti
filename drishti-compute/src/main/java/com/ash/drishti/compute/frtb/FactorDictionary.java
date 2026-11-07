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
package com.ash.drishti.compute.frtb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The factor dictionary (the kind {@code risk-factor}, FRTB_SA_GUIDE.md section 3): every risk factor by its standard name with
 * its class, type, parts and FRTB bucket, and the names the source systems give it. A source name maps to exactly one factor: a name in
 * two rows refuses the dictionary. A sensitivity whose factor is in no row (by its name or a source name) is refused and counted, never
 * guessed from its name.
 */
public final class FactorDictionary {

    /**
     * One factor.
     *
     * @param name the standard name ({@code IR/CURVE/USD/SOFR/10Y})
     * @param riskClass {@code GIRR}, {@code CSR_NS}, {@code EQ}, {@code COMM} or {@code FX}
     * @param type {@code CURVE}, {@code INFLATION}, {@code BASIS}, {@code SPREAD}, {@code SPOT}, {@code REPO}, {@code PRICE} or {@code VOL}
     * @param qualifier the currency (GIRR, FX), the issuer (CSR), the underlying (equity, commodity)
     * @param curve the curve (GIRR, inflation index), the bond or CDS curve (CSR), the location (commodity)
     * @param underlying the underlying (equity, commodity, the volatility's underlying)
     * @param tenor the tenor or option expiry ({@code 10Y}, {@code 3M}); null for the whole curve or a spot
     * @param underlyingTenor the swap tenor of a GIRR volatility; else null
     * @param frtbBucket the FRTB bucket (a currency for GIRR and FX, a number for the others)
     * @param simmBucket the SIMM bucket
     * @param sourceNames the source systems' names
     */
    public record Factor(String name, String riskClass, String type, String qualifier, String curve, String underlying, String tenor,
            String underlyingTenor, String frtbBucket, String simmBucket, List<String> sourceNames) {

        public Factor {
            sourceNames = sourceNames == null ? List.of() : List.copyOf(sourceNames);
        }

        /** True for a factor without a tenor that curvature uses (a curve, an issuer, a commodity curve, a spot). */
        public boolean whole() {
            return tenor == null || tenor.isBlank();
        }
    }

    private final Map<String, Factor> byName = new LinkedHashMap<>();
    private final Map<String, Factor> bySource = new LinkedHashMap<>();
    private final String version;

    /**
     * The dictionary of {@code factors}; refused (IllegalArgumentException with every conflict) when a name or a source name is in two rows.
     *
     * @param version the dictionary's version (the table's data version or its business date), for the provenance
     */
    public FactorDictionary(Collection<Factor> factors, String version) {
        this.version = version == null ? "" : version;
        List<String> problems = new ArrayList<>();
        for (Factor f : factors) {
            if (byName.put(f.name(), f) != null) {
                problems.add("the factor " + f.name() + " is in two rows");
            }
        }
        for (Factor f : byName.values()) {
            for (String s : f.sourceNames()) {
                String key = s.strip();
                Factor was = bySource.put(key, f);
                if (was != null && was != f) {
                    problems.add("the source name " + key + " maps to " + was.name() + " and " + f.name());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("DRS-5322 the factor dictionary is refused: " + String.join("; ", problems.subList(0, Math.min(20, problems.size())))
                    + (problems.size() > 20 ? " (and " + (problems.size() - 20) + " more)" : ""));
        }
    }

    /** The factor of a name: the standard name, else a source name; null when neither is known. */
    public Factor resolve(String name) {
        if (name == null) {
            return null;
        }
        Factor f = byName.get(name.strip());
        return f != null ? f : bySource.get(name.strip());
    }

    /** Every factor, in the dictionary's order. */
    public Collection<Factor> factors() {
        return byName.values();
    }

    public int size() {
        return byName.size();
    }

    public String version() {
        return version;
    }

    /** The factors whose name, source name, class or bucket contains {@code q} (case-insensitive), at most {@code limit}. */
    public List<Factor> search(String q, int limit) {
        String s = q == null ? "" : q.strip().toUpperCase(Locale.ROOT);
        List<Factor> out = new ArrayList<>();
        for (Factor f : byName.values()) {
            if (out.size() >= limit) {
                break;
            }
            if (s.isEmpty() || f.name().toUpperCase(Locale.ROOT).contains(s) || f.riskClass().equals(s) || s.equals(f.riskClass() + " " + f.frtbBucket())
                    || f.sourceNames().stream().anyMatch(n -> n.toUpperCase(Locale.ROOT).contains(s))) {
                out.add(f);
            }
        }
        return out;
    }

    /** A tenor in years ({@code 3M} 0.25, {@code 10Y} 10, {@code 2W} 14/365, {@code 0} or {@code SPOT} 0); NaN when it is not a tenor. */
    public static double years(String tenor) {
        if (tenor == null || tenor.isBlank()) {
            return Double.NaN;
        }
        String t = tenor.strip().toUpperCase(Locale.ROOT);
        if (t.equals("SPOT") || t.equals("0") || t.equals("0D")) {
            return 0;
        }
        try {
            char u = t.charAt(t.length() - 1);
            double n = Double.parseDouble(t.substring(0, t.length() - 1));
            return switch (u) {
                case 'Y' -> n;
                case 'M' -> n / 12.0;
                case 'W' -> n * 7 / 365.0;
                case 'D' -> n / 365.0;
                default -> Double.parseDouble(t);
            };
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
