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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The FRTB-SA parameters (docs/guides/FRTB_SA_GUIDE.md section 4): the two files of the market-risk pack ({@code capital/frtb-sa-sbm.yaml},
 * MAR21; {@code capital/frtb-sa-drc-rrao.yaml}, MAR22 and MAR23), read and checked once. A value missing or out of its range refuses the
 * files with the list of what is wrong. Each answer names the regimes, versions and the SHA-256 of the two files' bytes; overrides (the
 * playground's {@code param.<path>=<value>}) are applied to the parsed tree and mark every figure they touch as not the regulatory one.
 */
public final class FrtbParams {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The scenario transformation of a correlation (MAR21.6): high {@code min(m·ρ, cap)}, low {@code max(a·ρ − b, f·ρ)}. */
    public record Scenarios(double highMultiplier, double highCap, double lowMultiplier, double lowMinus, double lowFloorMultiplier) {

        /** ρ in scenario {@code s} (0 low, 1 medium, 2 high). */
        public double apply(int s, double rho) {
            return switch (s) {
                case 0 -> Math.max(lowMultiplier * rho - lowMinus, lowFloorMultiplier * rho);
                case 2 -> Math.min(highMultiplier * rho, highCap);
                default -> rho;
            };
        }
    }

    public final String sbmRegime;
    public final String sbmVersion;
    public final String drcRegime;
    public final String drcVersion;
    public final String hash;
    public final Map<String, String> overrides;
    public final Scenarios scenarios;

    // GIRR
    public final double[] girrTenors;
    public final double[] girrRw;
    public final double girrInflationRw;
    public final double girrBasisRw;
    public final boolean girrUseDivisor;
    public final Set<String> girrSpecified;
    public final double girrDivisor;
    public final double girrTheta;
    public final double girrFloor;
    public final double[][] girrTable;
    public final double girrDifferentCurve;
    public final double girrInflationToCurve;
    public final double girrBasisToAnything;
    public final double girrGamma;
    public final double girrVegaRw;
    public final double girrVegaAlpha;
    public final double girrCurvatureShock;
    // CSR non-securitisation
    public final double[] csrTenors;
    public final Map<Integer, Double> csrRw;
    public final int csrOther;
    public final Set<Integer> csrIndex;
    public final double csrName;
    public final double csrNameIndex;
    public final double csrTenor;
    public final double csrBasis;
    public final double csrRating;
    public final List<String> csrSectors;
    public final double[][] csrSectorTable;
    public final double csrVegaRw;
    public final double csrVegaAlpha;
    // equity
    public final Map<Integer, Double> eqSpotRw;
    public final Map<Integer, Double> eqRepoRw;
    public final int eqOther;
    public final Map<Integer, Double> eqName;
    public final double eqSpotRepo;
    public final double eqGammaDefault;
    public final double eqGammaOther;
    public final double eqGammaIndex;
    public final double eqGammaIndexName;
    public final Map<Integer, Double> eqVegaRw;
    public final double eqVegaAlpha;
    // commodity
    public final Map<Integer, Double> cmRw;
    public final int cmOther;
    public final Map<Integer, Double> cmCommodity;
    public final double cmTenor;
    public final double cmLocation;
    public final double cmGamma;
    public final double cmGammaOther;
    public final double cmVegaRw;
    public final double cmVegaAlpha;
    // FX
    public final double fxRw;
    public final Set<String> fxSpecifiedPairs;
    public final double fxDivisor;
    public final double fxGamma;
    public final double fxVegaRw;
    public final double fxVegaAlpha;
    public final double fxCurvatureDivisor;
    // DRC
    public final Map<String, Double> drcLgd;
    public final List<String> drcSeniority;
    public final double drcMaturityFloor;
    public final double drcMaturityCap;
    public final double drcCashEquityYears;
    public final List<String> drcBuckets;
    public final Map<String, Double> drcRw;
    public final boolean drcSovereignZero;
    // RRAO
    public final double rraoExotic;
    public final double rraoOther;
    public final Map<String, String> rraoTypes;

    private final List<String> problems = new ArrayList<>();

    private FrtbParams(JsonNode sbm, JsonNode drc, String hash, Map<String, String> overrides) {
        this.hash = hash;
        this.overrides = Map.copyOf(new TreeMap<>(overrides));
        sbmRegime = text(sbm, "regime");
        sbmVersion = text(sbm, "version");
        drcRegime = text(drc, "regime");
        drcVersion = text(drc, "version");
        scenarios = new Scenarios(num(sbm, "scenarios.high.multiplier", 1, 2), num(sbm, "scenarios.high.cap", 0, 1),
                num(sbm, "scenarios.low.multiplier", 1, 3), num(sbm, "scenarios.low.minus", 0, 2), num(sbm, "scenarios.low.floor-multiplier", 0, 1));
        girrTenors = nums(sbm, "GIRR.delta.tenors", 0, 50);
        girrRw = nums(sbm, "GIRR.delta.risk-weights", 0, 1);
        if (girrRw.length != girrTenors.length) {
            problems.add("GIRR.delta.risk-weights has " + girrRw.length + " values for " + girrTenors.length + " tenors");
        }
        girrInflationRw = num(sbm, "GIRR.delta.inflation-risk-weight", 0, 1);
        girrBasisRw = num(sbm, "GIRR.delta.cross-currency-basis-risk-weight", 0, 1);
        girrUseDivisor = bool(sbm, "GIRR.delta.use-specified-divisor");
        girrSpecified = Set.copyOf(texts(sbm, "GIRR.delta.specified-currencies"));
        girrDivisor = num(sbm, "GIRR.delta.specified-divisor", 1, 2);
        girrTheta = num(sbm, "GIRR.delta.within-bucket.theta", 0, 1);
        girrFloor = num(sbm, "GIRR.delta.within-bucket.floor", 0, 1);
        girrTable = matrix(sbm, "GIRR.delta.within-bucket.published-table", girrTenors.length);
        girrDifferentCurve = num(sbm, "GIRR.delta.within-bucket.different-curve", 0, 1);
        girrInflationToCurve = num(sbm, "GIRR.delta.within-bucket.inflation-to-curve", 0, 1);
        girrBasisToAnything = num(sbm, "GIRR.delta.within-bucket.basis-to-anything", 0, 1);
        girrGamma = num(sbm, "GIRR.delta.across-buckets", 0, 1);
        girrVegaRw = num(sbm, "GIRR.vega.risk-weight", 0, 1);
        girrVegaAlpha = num(sbm, "GIRR.vega.alpha", 0, 1);
        girrCurvatureShock = num(sbm, "GIRR.curvature.shock", 0, 1);
        csrTenors = nums(sbm, "CSR_NS.delta.tenors", 0, 50);
        csrRw = byBucket(sbm, "CSR_NS.delta.risk-weights", 0, 1);
        csrOther = (int) num(sbm, "CSR_NS.delta.other-sector-bucket", 1, 99);
        csrIndex = Set.copyOf(nums(sbm, "CSR_NS.delta.index-buckets", 1, 99).length == 0 ? List.of() : intList(nums(sbm, "CSR_NS.delta.index-buckets", 1, 99)));
        csrName = num(sbm, "CSR_NS.delta.within-bucket.different-name", 0, 1);
        csrNameIndex = num(sbm, "CSR_NS.delta.within-bucket.different-name-index-buckets", 0, 1);
        csrTenor = num(sbm, "CSR_NS.delta.within-bucket.different-tenor", 0, 1);
        csrBasis = num(sbm, "CSR_NS.delta.within-bucket.different-basis", 0, 1);
        csrRating = num(sbm, "CSR_NS.delta.across-buckets.rating-different", 0, 1);
        csrSectors = texts(sbm, "CSR_NS.delta.across-buckets.sector.sectors");
        csrSectorTable = matrix(sbm, "CSR_NS.delta.across-buckets.sector.table", csrSectors.size());
        csrVegaRw = num(sbm, "CSR_NS.vega.risk-weight", 0, 1);
        csrVegaAlpha = num(sbm, "CSR_NS.vega.alpha", 0, 1);
        eqSpotRw = byBucket(sbm, "EQ.delta.spot-risk-weights", 0, 1);
        eqRepoRw = byBucket(sbm, "EQ.delta.repo-risk-weights", 0, 1);
        eqOther = (int) num(sbm, "EQ.delta.other-sector-bucket", 1, 99);
        eqName = byBucket(sbm, "EQ.delta.within-bucket.different-name", 0, 1);
        eqSpotRepo = num(sbm, "EQ.delta.within-bucket.spot-repo", 0, 1);
        eqGammaDefault = num(sbm, "EQ.delta.across-buckets.default", 0, 1);
        eqGammaOther = num(sbm, "EQ.delta.across-buckets.with-other-sector", 0, 1);
        eqGammaIndex = num(sbm, "EQ.delta.across-buckets.index-to-index", 0, 1);
        eqGammaIndexName = num(sbm, "EQ.delta.across-buckets.index-to-name", 0, 1);
        eqVegaRw = byBucket(sbm, "EQ.vega.risk-weights", 0, 1);
        eqVegaAlpha = num(sbm, "EQ.vega.alpha", 0, 1);
        cmRw = byBucket(sbm, "COMM.delta.risk-weights", 0, 1);
        cmOther = (int) num(sbm, "COMM.delta.other-sector-bucket", 1, 99);
        cmCommodity = byBucket(sbm, "COMM.delta.within-bucket.commodity", 0, 1);
        cmTenor = num(sbm, "COMM.delta.within-bucket.different-tenor", 0, 1);
        cmLocation = num(sbm, "COMM.delta.within-bucket.different-location", 0, 1);
        cmGamma = num(sbm, "COMM.delta.across-buckets.default", 0, 1);
        cmGammaOther = num(sbm, "COMM.delta.across-buckets.with-other-sector", 0, 1);
        cmVegaRw = num(sbm, "COMM.vega.risk-weight", 0, 1);
        cmVegaAlpha = num(sbm, "COMM.vega.alpha", 0, 1);
        fxRw = num(sbm, "FX.delta.risk-weight", 0, 1);
        fxSpecifiedPairs = Set.copyOf(texts(sbm, "FX.delta.specified-pairs"));
        fxDivisor = num(sbm, "FX.delta.specified-divisor", 1, 2);
        fxGamma = num(sbm, "FX.delta.across-buckets", 0, 1);
        fxVegaRw = num(sbm, "FX.vega.risk-weight", 0, 1);
        fxVegaAlpha = num(sbm, "FX.vega.alpha", 0, 1);
        fxCurvatureDivisor = num(sbm, "FX.curvature.divisor", 1, 10);
        drcLgd = byName(drc, "drc.lgd", 0, 1);
        drcSeniority = texts(drc, "drc.seniority-order");
        drcMaturityFloor = num(drc, "drc.maturity.floor-years", 0, 1);
        drcMaturityCap = num(drc, "drc.maturity.cap-years", 0, 5);
        drcCashEquityYears = num(drc, "drc.maturity.cash-equity-years", 0, 1);
        if (drcCashEquityYears != 0.25 && drcCashEquityYears != 1.0) {
            problems.add("drc.maturity.cash-equity-years is 0.25 or 1 (MAR22.16), not " + drcCashEquityYears);
        }
        drcBuckets = texts(drc, "drc.buckets");
        drcRw = byName(drc, "drc.risk-weights", 0, 1);
        drcSovereignZero = bool(drc, "drc.sovereign-zero-weight");
        rraoExotic = num(drc, "rrao.exotic-underlying-weight", 0, 1);
        rraoOther = num(drc, "rrao.other-residual-risk-weight", 0, 1);
        Map<String, String> types = new HashMap<>();
        for (String cls : List.of("EXOTIC", "OTHER")) {
            for (String t : texts(drc, "rrao.product-types." + cls)) {
                if (types.put(t.toUpperCase(java.util.Locale.ROOT), cls) != null) {
                    problems.add("rrao.product-types: " + t + " is listed twice");
                }
            }
        }
        rraoTypes = Map.copyOf(types);
        for (String s : drcSeniority) {
            if (!drcLgd.containsKey(s)) {
                problems.add("drc.lgd has no " + s);
            }
        }
        checkSymmetric("GIRR.delta.within-bucket.published-table", girrTable);
        checkSymmetric("CSR_NS.delta.across-buckets.sector.table", csrSectorTable);
        for (int b = 1; b <= 13; b++) {
            if (b != eqOther && !eqName.containsKey(b)) {
                problems.add("EQ.delta.within-bucket.different-name has no bucket " + b);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the FRTB-SA parameter files are refused: " + String.join("; ", problems));
        }
    }

    /** Reads the two files; {@code overrides} ({@code path → value}, may be empty) are set on the trees before they are checked. */
    public static FrtbParams read(Path sbm, Path drcRrao, Map<String, String> overrides) {
        try {
            return parse(Files.readAllBytes(sbm), Files.readAllBytes(drcRrao), overrides);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The same from the files' bytes. */
    public static FrtbParams parse(byte[] sbm, byte[] drcRrao, Map<String, String> overrides) {
        try {
            JsonNode s = YAML.readTree(sbm);
            JsonNode d = YAML.readTree(drcRrao);
            Map<String, String> applied = new LinkedHashMap<>();
            for (Map.Entry<String, String> o : (overrides == null ? Map.<String, String>of() : overrides).entrySet()) {
                String path = o.getKey();
                JsonNode root = path.startsWith("drc.") || path.startsWith("rrao.") ? d : s;
                set(root, path, o.getValue());
                applied.put(path, o.getValue());
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(sbm);
            md.update((byte) 0);
            md.update(drcRrao);
            return new FrtbParams(s, d, HexFormat.of().formatHex(md.digest()), applied);
        } catch (IOException e) {
            throw new IllegalArgumentException("the FRTB-SA parameter files are not YAML: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same parameters with {@code overrides} added (a playground run; the source trees re-read from the same bytes). */
    public static FrtbParams withOverrides(byte[] sbm, byte[] drcRrao, Map<String, String> overrides) {
        return parse(sbm, drcRrao, overrides);
    }

    private static void set(JsonNode root, String path, String value) {
        String[] parts = path.split("\\.");
        JsonNode n = root;
        for (int i = 0; i < parts.length - 1; i++) {
            n = n.get(parts[i]);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException("override " + path + ": no such parameter");
            }
        }
        String last = parts[parts.length - 1];
        if (!n.has(last) || n.get(last).isContainerNode()) {
            throw new IllegalArgumentException("override " + path + ": no such single value");
        }
        try {
            ((ObjectNode) n).put(last, Double.parseDouble(value.strip()));
        } catch (NumberFormatException e) {
            ((ObjectNode) n).put(last, value.strip());
        }
    }

    private JsonNode at(JsonNode root, String path) {
        JsonNode n = root;
        for (String p : path.split("\\.")) {
            n = n == null ? null : n.get(p);
        }
        if (n == null || n.isNull()) {
            problems.add(path + " is missing");
        }
        return n;
    }

    private String text(JsonNode root, String path) {
        JsonNode n = at(root, path);
        return n == null ? "" : n.asText();
    }

    private boolean bool(JsonNode root, String path) {
        JsonNode n = at(root, path);
        return n != null && n.asBoolean();
    }

    private double num(JsonNode root, String path, double lo, double hi) {
        JsonNode n = at(root, path);
        if (n == null) {
            return Double.NaN;
        }
        if (!n.isNumber() && !(n.isTextual() && isNumber(n.asText()))) {
            problems.add(path + " is not a number");
            return Double.NaN;
        }
        double v = n.asDouble();
        if (!(v >= lo && v <= hi)) {
            problems.add(path + " is " + v + ", outside " + lo + " to " + hi);
        }
        return v;
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private double[] nums(JsonNode root, String path, double lo, double hi) {
        JsonNode n = at(root, path);
        if (n == null || !n.isArray()) {
            if (n != null) {
                problems.add(path + " is not a list");
            }
            return new double[0];
        }
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = n.get(i).asDouble(Double.NaN);
            if (!(out[i] >= lo && out[i] <= hi)) {
                problems.add(path + "[" + i + "] is " + n.get(i).asText() + ", outside " + lo + " to " + hi);
            }
        }
        return out;
    }

    private List<String> texts(JsonNode root, String path) {
        JsonNode n = at(root, path);
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        } else if (n != null) {
            problems.add(path + " is not a list");
        }
        return List.copyOf(out);
    }

    private double[][] matrix(JsonNode root, String path, int size) {
        JsonNode n = at(root, path);
        double[][] m = new double[size][size];
        if (n == null || !n.isArray() || n.size() != size) {
            problems.add(path + " is not a " + size + " by " + size + " table");
            return m;
        }
        for (int i = 0; i < size; i++) {
            JsonNode row = n.get(i);
            if (row == null || !row.isArray() || row.size() != size) {
                problems.add(path + " row " + (i + 1) + " does not have " + size + " values");
                continue;
            }
            for (int j = 0; j < size; j++) {
                m[i][j] = row.get(j).asDouble(Double.NaN);
                if (!(m[i][j] >= 0 && m[i][j] <= 1)) {
                    problems.add(path + " [" + (i + 1) + "," + (j + 1) + "] is outside 0 to 1");
                }
            }
        }
        return m;
    }

    private void checkSymmetric(String path, double[][] m) {
        for (int i = 0; i < m.length; i++) {
            if (m[i][i] != 1.0) {
                problems.add(path + " has " + m[i][i] + " on its diagonal (must be 1)");
            }
            for (int j = 0; j < i; j++) {
                if (m[i][j] != m[j][i]) {
                    problems.add(path + " is not symmetric at [" + (i + 1) + "," + (j + 1) + "]");
                }
            }
        }
    }

    private Map<Integer, Double> byBucket(JsonNode root, String path, double lo, double hi) {
        Map<Integer, Double> out = new TreeMap<>();
        JsonNode n = at(root, path);
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> {
                double v = e.getValue().asDouble(Double.NaN);
                if (!(v >= lo && v <= hi)) {
                    problems.add(path + "." + e.getKey() + " is " + e.getValue().asText() + ", outside " + lo + " to " + hi);
                }
                try {
                    out.put(Integer.parseInt(e.getKey().strip()), v);
                } catch (NumberFormatException x) {
                    problems.add(path + ": bucket " + e.getKey() + " is not a number");
                }
            });
        } else if (n != null) {
            problems.add(path + " is not a table by bucket");
        }
        return Map.copyOf(out);
    }

    private Map<String, Double> byName(JsonNode root, String path, double lo, double hi) {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonNode n = at(root, path);
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> {
                double v = e.getValue().asDouble(Double.NaN);
                if (!(v >= lo && v <= hi)) {
                    problems.add(path + "." + e.getKey() + " is outside " + lo + " to " + hi);
                }
                out.put(e.getKey().toUpperCase(java.util.Locale.ROOT), v);
            });
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static List<Integer> intList(double[] v) {
        List<Integer> out = new ArrayList<>();
        for (double d : v) {
            out.add((int) d);
        }
        return out;
    }

    /** The bytes the hash was taken of, as text (for the record): the regimes and versions. */
    public String describe() {
        return sbmRegime + " " + sbmVersion + " + " + drcRegime + " " + drcVersion + (overrides.isEmpty() ? "" : " with " + overrides.size() + " override(s)");
    }

    /** The index of a GIRR vertex in years, or −1. */
    public int girrTenorIndex(double years) {
        return index(girrTenors, years);
    }

    /** The index of a CSR vertex in years, or −1. */
    public int csrTenorIndex(double years) {
        return index(csrTenors, years);
    }

    static int index(double[] tenors, double years) {
        for (int i = 0; i < tenors.length; i++) {
            if (Math.abs(tenors[i] - years) < 1e-9) {
                return i;
            }
        }
        return -1;
    }

    /** The sector index (0-based) of a CSR bucket in MAR21.57's table: 1 and 9 share a sector, ... 7 and 15; then 8, 16, 17, 18. */
    public int csrSector(int bucket) {
        String key = bucket >= 9 && bucket <= 15 ? String.valueOf(bucket - 8) : String.valueOf(bucket);
        return csrSectors.indexOf(key);
    }
}
