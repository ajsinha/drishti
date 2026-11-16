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
package com.ash.drishti.banking.compute.simm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One SIMM version's parameter file (docs/guides/SIMM_GUIDE.md section 4; the structure is
 * docs/architecture/capital-parameters/simm.yaml, shipped with every number empty because ISDA's numbers are licensed). Every number is
 * read into one flat table by a stable key ({@code InterestRate.delta-risk-weights.1.5y}, {@code CreditQ.across-buckets.3.7},
 * {@code risk-class-correlation.InterestRate.FX}); the engine reads its numbers by key and records the keys it used, so the playground's
 * Config tab holds exactly those cells and every formula points at them. A key the structure needs and the file leaves null or out of
 * range refuses the version ({@code DRS-5333}, every problem listed); {@link #lenient} reads the empty structure for the shipped template
 * (missing numbers NaN, nothing refused). Overrides ({@code key → value}) replace numbers by key and mark every figure as not the
 * regulatory one.
 */
public final class SimmParams {

    public static final List<String> PRODUCT_CLASSES = List.of("RatesFX", "Credit", "Equity", "Commodity");
    public static final List<String> RISK_CLASSES = List.of("InterestRate", "CreditQ", "CreditNonQ", "Equity", "Commodity", "FX");
    /** Φ⁻¹(99 %) and Φ⁻¹(99.5 %), the vega σ's and the curvature λ's normal quantiles (the same doubles in the workbook's Config). */
    public static final double PHI99 = 2.3263478740408408;
    public static final double PHI995 = 2.5758293035489004;
    /** The FX groups, by a currency's place in {@code FX.high-volatility-currencies}. */
    public static final String REGULAR = "regular";
    public static final String HIGH = "high";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public final String version;
    public final String effectiveFrom;
    public final String source;
    public final String calculationCurrency;
    public final String hash;
    public final Map<String, String> overrides;
    public final List<String> regimes;
    public final List<String> irTenors;
    public final List<String> irBuckets;
    public final List<String> creditQTenors;
    public final List<String> creditQBuckets;
    public final List<String> creditNonQBuckets;
    public final List<String> equityBuckets;
    public final List<String> commodityBuckets;
    public final Set<String> zeroCurvatureEquity;
    public final Set<String> fxHigh;
    /** Concentration-threshold groups by currency: {@code InterestRate} and {@code FX}, currency → group (any other: {@code other}). */
    private final Map<String, Map<String, String>> groups = new LinkedHashMap<>();
    private final Map<String, Double> values = new LinkedHashMap<>();
    private final Map<String, String> notes = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final boolean lenient;

    private SimmParams(JsonNode root, String hash, Map<String, String> overrides, boolean lenient) {
        this.lenient = lenient;
        this.hash = hash;
        version = text(root, "version");
        effectiveFrom = text(root, "effective-from");
        source = root.path("source").asText("");
        String calc = root.path("calculation-currency").asText("");
        calculationCurrency = calc.isBlank() ? "USD" : calc.strip().toUpperCase(Locale.ROOT);
        regimes = labels(root.path("regimes"));
        irTenors = labels(root.path("InterestRate").path("tenors"));
        irBuckets = labels(root.path("InterestRate").path("buckets"));
        creditQTenors = labels(root.path("CreditQ").path("tenors"));
        creditQBuckets = labels(root.path("CreditQ").path("buckets"));
        creditNonQBuckets = labels(root.path("CreditNonQ").path("buckets"));
        equityBuckets = labels(root.path("Equity").path("buckets"));
        commodityBuckets = labels(root.path("Commodity").path("buckets"));
        zeroCurvatureEquity = Set.copyOf(labels(root.path("Equity").path("zero-curvature-buckets")));
        fxHigh = Set.copyOf(upper(root.path("FX").path("high-volatility-currencies"), "FX.high-volatility-currencies"));
        for (String s : List.of("regimes", "InterestRate.tenors", "InterestRate.buckets", "CreditQ.tenors", "CreditQ.buckets", "CreditNonQ.buckets",
                "Equity.buckets", "Commodity.buckets")) {
            if (at(root, s).isMissingNode() || !at(root, s).isArray() || at(root, s).isEmpty()) {
                problems.add(s + " is missing (the structure)");
            }
        }
        matrix(root, "risk-class-correlation", RISK_CLASSES, -1, 1);
        // InterestRate
        JsonNode ir = root.path("InterestRate");
        for (String b : irBuckets) {
            JsonNode row = ir.path("delta-risk-weights").path(b);
            for (int i = 0; i < irTenors.size(); i++) {
                put("InterestRate.delta-risk-weights." + b + "." + irTenors.get(i), row.isArray() ? row.path(i) : null, 0, 1e6, "rates delta risk weight, bucket "
                        + b + ", " + irTenors.get(i) + " (per bp)");
            }
        }
        scalar(ir, "InterestRate", "inflation-risk-weight", 0, 1e6, "rates: inflation delta risk weight");
        scalar(ir, "InterestRate", "cross-currency-basis-risk-weight", 0, 1e6, "rates: cross-currency basis risk weight");
        matrix(ir, "InterestRate.tenor-correlation", irTenors, -1, 1);
        scalar(ir, "InterestRate", "sub-curve-correlation", -1, 1, "rates: φ between two sub-curves of one currency");
        scalar(ir, "InterestRate", "inflation-correlation", -1, 1, "rates: inflation to a curve");
        scalar(ir, "InterestRate", "cross-currency-basis-correlation", -1, 1, "rates: cross-currency basis to anything");
        scalar(ir, "InterestRate", "across-currencies", -1, 1, "rates: γ across currencies");
        scalar(ir, "InterestRate", "vega-risk-weight", 0, 1e6, "rates: vega risk weight");
        scalar(ir, "InterestRate", "historical-volatility-ratio", 1e-6, 10, "rates: HVR_IR (curvature × HVR_IR⁻²)");
        groups("InterestRate", ir);
        // Credit
        credit(root.path("CreditQ"), "CreditQ", creditQBuckets, true);
        credit(root.path("CreditNonQ"), "CreditNonQ", creditNonQBuckets, false);
        // Equity, Commodity
        named(root.path("Equity"), "Equity", equityBuckets, true);
        named(root.path("Commodity"), "Commodity", commodityBuckets, false);
        // FX
        JsonNode fx = root.path("FX");
        for (String k : List.of("regular-regular", "regular-high", "high-regular", "high-high")) {
            put("FX.delta-risk-weights." + k, fx.path("delta-risk-weights").path(k), 0, 1e6, "FX delta risk weight, currency group-calculation group " + k);
        }
        for (String g : List.of(REGULAR, HIGH)) {
            for (String k : List.of("regular-regular", "regular-high", "high-high")) {
                put("FX.correlation." + g + "." + k, fx.path("correlation").path(g).path(k), -1, 1, "FX ρ, calculation currency " + g + ", groups " + k);
            }
        }
        scalar(fx, "FX", "vega-correlation", -1, 1, "FX: vega ρ between currencies");
        scalar(fx, "FX", "vega-risk-weight", 0, 1e6, "FX: vega risk weight");
        scalar(fx, "FX", "historical-volatility-ratio", 1e-6, 10, "FX: HVR");
        groups("FX", fx);
        if (version.isBlank()) {
            problems.add("version is missing");
        }
        Map<String, String> applied = new TreeMap<>();
        for (Map.Entry<String, String> o : (overrides == null ? Map.<String, String>of() : overrides).entrySet()) {
            String key = o.getKey().strip();
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("override " + key + ": no such SIMM parameter (a key as the workbook's Config tab names it)");
            }
            double v;
            try {
                v = Double.parseDouble(o.getValue().strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("override " + key + ": " + o.getValue() + " is not a number");
            }
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("override " + key + ": not a finite number");
            }
            values.put(key, v);
            applied.put(key, o.getValue().strip());
        }
        this.overrides = Collections.unmodifiableMap(applied);
        if (!problems.isEmpty() && !lenient) {
            throw new IllegalArgumentException("DRS-5333 SIMM " + (version.isBlank() ? "(no version)" : version) + " is refused: "
                    + String.join("; ", problems.subList(0, Math.min(25, problems.size()))) + (problems.size() > 25 ? " (and " + (problems.size() - 25) + " more)" : ""));
        }
    }

    /** One version's parameters from the file's bytes; refused (IllegalArgumentException, {@code DRS-5333}) when a needed number is missing. */
    public static SimmParams parse(byte[] yaml, Map<String, String> overrides) {
        return read(yaml, overrides, false);
    }

    /** The structure with missing numbers as NaN, nothing refused (the shipped template's empty Config). */
    public static SimmParams lenient(byte[] yaml) {
        return read(yaml, Map.of(), true);
    }

    private static SimmParams read(byte[] yaml, Map<String, String> overrides, boolean lenient) {
        try {
            JsonNode root = YAML.readTree(yaml);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("DRS-5333 the SIMM parameter file is empty");
            }
            return new SimmParams(root, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml)), overrides, lenient);
        } catch (IOException e) {
            throw new IllegalArgumentException("DRS-5333 the SIMM parameter file is not YAML: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- reading ---------------------------------------------------------------------------------------------------------------------

    private static JsonNode at(JsonNode root, String path) {
        JsonNode n = root;
        for (String p : path.split("\\.")) {
            n = n.path(p);
        }
        return n;
    }

    private static String text(JsonNode root, String f) {
        JsonNode n = root.path(f);
        return n.isMissingNode() || n.isNull() ? "" : n.asText().strip();
    }

    private static List<String> labels(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText().strip()));
        }
        return List.copyOf(out);
    }

    private List<String> upper(JsonNode n, String path) {
        if (!n.isArray()) {
            if (!lenient) {
                problems.add(path + " is missing");
            }
            return List.of();
        }
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText().strip().toUpperCase(Locale.ROOT)));
        return out;
    }

    private void put(String key, JsonNode n, double lo, double hi, String note) {
        notes.put(key, note);
        if (n == null || n.isMissingNode() || n.isNull()) {
            values.put(key, Double.NaN);
            problems.add(key + " is missing");
            return;
        }
        double v = n.isNumber() ? n.asDouble() : parse(n.asText());
        if (!(v >= lo && v <= hi)) {
            problems.add(key + " is " + n.asText() + ", outside " + lo + " to " + hi);
        }
        values.put(key, v);
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void scalar(JsonNode node, String prefix, String field, double lo, double hi, String note) {
        put(prefix + "." + field, node.path(field), lo, hi, note);
    }

    private void matrix(JsonNode root, String path, List<String> axis, double lo, double hi) {
        JsonNode m = path.contains(".") ? root.path(path.substring(path.lastIndexOf('.') + 1)) : root.path(path);
        for (int i = 0; i < axis.size(); i++) {
            for (int j = 0; j < axis.size(); j++) {
                JsonNode row = m.isArray() ? m.path(i) : null;
                put(path + "." + axis.get(i) + "." + axis.get(j), row != null && row.isArray() ? row.path(j) : null, lo, hi,
                        path.substring(path.lastIndexOf('.') + 1) + " " + axis.get(i) + " × " + axis.get(j));
            }
        }
        for (int i = 0; i < axis.size() && m.isArray(); i++) {
            String d = path + "." + axis.get(i) + "." + axis.get(i);
            if (values.get(d) != 1.0) {
                problems.add(d + " is " + values.get(d) + " (the diagonal must be 1)");
            }
            for (int j = 0; j < i; j++) {
                if (!values.get(path + "." + axis.get(i) + "." + axis.get(j)).equals(values.get(path + "." + axis.get(j) + "." + axis.get(i)))) {
                    problems.add(path + " is not symmetric at " + axis.get(i) + " × " + axis.get(j));
                }
            }
        }
    }

    private void groups(String cls, JsonNode node) {
        Map<String, String> byCcy = new LinkedHashMap<>();
        JsonNode g = node.path("threshold-groups");
        Set<String> names = new LinkedHashSet<>();
        if (g.isObject()) {
            g.fields().forEachRemaining(e -> {
                names.add(e.getKey());
                e.getValue().forEach(c -> {
                    String ccy = c.asText().strip().toUpperCase(Locale.ROOT);
                    if (byCcy.put(ccy, e.getKey()) != null) {
                        problems.add(cls + ".threshold-groups: " + ccy + " is in two groups");
                    }
                });
            });
        } else if (!lenient) {
            problems.add(cls + ".threshold-groups is missing");
        }
        names.add("other");
        groups.put(cls, Map.copyOf(byCcy));
        for (String m : List.of("delta", "vega")) {
            for (String n : names) {
                put(cls + ".concentration-thresholds." + m + "." + n, node.path("concentration-thresholds").path(m).path(n), 1e-9, 1e18,
                        cls + " " + m + " concentration threshold, group " + n);
            }
        }
    }

    private void credit(JsonNode node, String cls, List<String> buckets, boolean qualifying) {
        List<String> all = new ArrayList<>(buckets);
        all.add("Residual");
        for (String b : all) {
            put(cls + ".delta-risk-weights." + b, node.path("delta-risk-weights").path(b), 0, 1e6, cls + " delta risk weight, bucket " + b + " (per bp)");
        }
        for (String k : List.of("same-issuer", "different-issuer", "residual-same-issuer", "residual-different-issuer")) {
            put(cls + ".correlation." + k, node.path("correlation").path(k), -1, 1, cls + " ρ, " + k);
        }
        if (qualifying) {
            matrix(node, cls + ".across-buckets", buckets, -1, 1);
            scalar(node, cls, "base-correlation-risk-weight", 0, 1e6, "CreditQ: base correlation risk weight");
            scalar(node, cls, "base-correlation-correlation", -1, 1, "CreditQ: ρ between index families' base correlations");
        } else {
            scalar(node, cls, "across-buckets", -1, 1, cls + ": γ between its buckets");
        }
        scalar(node, cls, "vega-risk-weight", 0, 1e6, cls + ": vega risk weight");
        for (String m : List.of("delta", "vega")) {
            for (String b : all) {
                put(cls + ".concentration-thresholds." + m + "." + b, node.path("concentration-thresholds").path(m).path(b), 1e-9, 1e18,
                        cls + " " + m + " concentration threshold, bucket " + b);
            }
        }
    }

    private void named(JsonNode node, String cls, List<String> buckets, boolean residual) {
        List<String> all = new ArrayList<>(buckets);
        if (residual) {
            all.add("Residual");
        }
        for (String b : all) {
            put(cls + ".delta-risk-weights." + b, node.path("delta-risk-weights").path(b), 0, 1e6, cls + " delta risk weight, bucket " + b + " (per 1 %)");
            put(cls + ".correlation." + b, node.path("correlation").path(b), -1, 1, cls + " ρ between names in bucket " + b);
        }
        matrix(node, cls + ".across-buckets", buckets, -1, 1);
        JsonNode vrw = node.path("vega-risk-weight");
        if (vrw.isObject()) {
            for (String b : all) {
                put(cls + ".vega-risk-weight." + b, vrw.path(b), 0, 1e6, cls + " vega risk weight, bucket " + b);
            }
        } else {
            scalar(node, cls, "vega-risk-weight", 0, 1e6, cls + ": vega risk weight");
        }
        scalar(node, cls, "historical-volatility-ratio", 1e-6, 10, cls + ": HVR");
        for (String m : List.of("delta", "vega")) {
            for (String b : all) {
                put(cls + ".concentration-thresholds." + m + "." + b, node.path("concentration-thresholds").path(m).path(b), 1e-9, 1e18,
                        cls + " " + m + " concentration threshold, bucket " + b);
            }
        }
    }

    // ---- the numbers by key ----------------------------------------------------------------------------------------------------------

    /** The number of a key (NaN in a lenient read when missing); IllegalArgumentException for a key the structure does not have. */
    public double v(String key) {
        if (key.equals(ONE)) {
            return 1;
        }
        Double d = values.get(key);
        if (d == null) {
            throw new IllegalArgumentException("no SIMM parameter " + key);
        }
        return d;
    }

    /** The key that is the number 1 (the same factor; a correlation not in the file). */
    public static final String ONE = "1";

    /** True when the structure has the key. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** What a key is, in words (the Config tab's Rule column). */
    public String note(String key) {
        return notes.getOrDefault(key, key);
    }

    /** Every key and its number, in the file's order. */
    public Map<String, Double> values() {
        return Collections.unmodifiableMap(values);
    }

    /** The problems a lenient read found (empty for a complete version). */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** True when every number the structure needs is present and in range. */
    public boolean complete() {
        return problems.isEmpty();
    }

    /** The concentration-threshold group of a currency ({@code InterestRate} or {@code FX}). */
    public String group(String cls, String ccy) {
        Map<String, String> g = groups.get(cls);
        String v = g == null || ccy == null ? null : g.get(ccy.toUpperCase(Locale.ROOT));
        return v == null ? "other" : v;
    }

    /** The members of each threshold group of {@code InterestRate} or {@code FX}, group → currencies. */
    public Map<String, List<String>> groupMembers(String cls) {
        Map<String, List<String>> out = new TreeMap<>();
        groups.getOrDefault(cls, Map.of()).forEach((ccy, g) -> out.computeIfAbsent(g, x -> new ArrayList<>()).add(ccy));
        out.values().forEach(Collections::sort);
        return out;
    }

    /** The FX group of a currency. */
    public String fxGroup(String ccy) {
        return ccy != null && fxHigh.contains(ccy.toUpperCase(Locale.ROOT)) ? HIGH : REGULAR;
    }

    /** The vega risk weight's key of a class and bucket (a table by bucket, or one number). */
    public String vegaRiskWeightKey(String cls, String bucket) {
        String byBucket = cls + ".vega-risk-weight." + bucket;
        return values.containsKey(byBucket) ? byBucket : cls + ".vega-risk-weight";
    }

    /** A text of the version for the record. */
    public String describe() {
        return "SIMM " + version + (effectiveFrom.isBlank() ? "" : " (effective " + effectiveFrom + ")") + (overrides.isEmpty() ? "" : " with "
                + overrides.size() + " override(s)");
    }
}
