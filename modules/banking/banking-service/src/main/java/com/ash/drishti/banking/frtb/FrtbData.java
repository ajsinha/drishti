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
package com.ash.drishti.banking.frtb;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.banking.compute.frtb.FactorDictionary;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A business date's sensitivity inputs as the connectors serve them (FRTB_SA_GUIDE.md section 3): the factor dictionary
 * ({@code risk-factor}), the position sensitivities ({@code position-sensitivity}) with the load checks of docs/guides/FRTB_SA_GUIDE.md
 * section 2.4, the optional DRC and RRAO inputs, and the FX of the date. Read once per date and kept a few minutes (a data load of one of
 * the kinds forgets the date).
 */
public final class FrtbData {

    /** How the kinds are read: the source router as the server, or a test's files. */
    @FunctionalInterface
    public interface Reader {
        /** Every document of {@code kind} on {@code asOf}; false when no connector serves the kind. */
        boolean documents(String kind, AsOf asOf, Consumer<DataNode> sink);
    }

    /** One delivered element, as delivered (the value in its own currency). */
    public record Element(String factor, String measure, double value, String currency, double shockSize, double impliedVol, String bucket) {}

    /** One record (a position or a trade) with its elements. */
    public record Record(String id, String grain, String positionId, String tradeId, List<String> tradeIds, Map<String, String> attributes,
            List<Element> elements) {

        /** The position the record is of: its POSITION_ID, else its trade id. */
        public String positionKey() {
            return positionId != null && !positionId.isBlank() ? positionId : tradeId;
        }
    }

    /** A date's inputs. */
    public record Day(LocalDate date, AsOf asOf, FactorDictionary dictionary, List<Record> records, Map<String, Integer> refusals, List<String> warnings,
            Set<String> batches, List<DataNode> defaultRisk, List<DataNode> residualRisk, Map<String, Double> usdPer, boolean sensitivitiesRead,
            boolean factorsRead, int proxied) {}

    /** The record attributes kept (placement and terms). */
    static final List<String> ATTRIBUTES = List.of("LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "LIMIT_LETTER", "FRTB_DESK", "PRODUCT_GROUP",
            "PRODUCT_TYPE", "TRADE_STATUS", "MATURITY_DATE", "CURRENCY", "SOURCE_SYSTEM", "BATCH_ID", "RUN_KIND", "STATUS", "COMPUTED_AT");

    private final Reader reader;
    private final FrtbProperties props;
    private final Map<String, Day> cache;

    public FrtbData(Reader reader, FrtbProperties props) {
        this.reader = reader;
        this.props = props;
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(512L << 20)   // weighed in bytes (D3)
                .<String, Day>weigher((k, d) -> (int) Math.min(Integer.MAX_VALUE, 4096L + 512L * size(d.records())
                        + 256L * (size(d.defaultRisk()) + size(d.residualRisk()))))
                .expireAfterWrite(Duration.ofMinutes(props.cacheMinutes())).<String, Day>build().asMap();
    }

    /** Forgets every date (a data load of one of the kinds). */
    public void clear() {
        cache.clear();
    }

    /** The kind of the factor dictionary (SIMM reads its SIMM columns). */
    public String factorKind() {
        return props.factorKind();
    }

    /** The kinds whose load changes a date's answers. */
    public List<String> kinds() {
        return List.of(props.sensitivityKind(), props.factorKind(), props.defaultRiskKind(), props.residualRiskKind(), "fx-spot");
    }

    /** The inputs of {@code asOf} (its business date and, for a recipe, the knowledge time it was run at). */
    public Day day(AsOf asOf) {
        String key = asOf.businessDate() + "|" + asOf.knownAt();
        Day hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Day d = read(asOf);
        cache.put(key, d);
        return d;
    }

    private static String text(DataNode n, String f) {
        DataNode v = n.get(f);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    private static double num(DataNode n, String f) {
        DataNode v = n.get(f);
        if (v == null || v.isMissing() || v.isNull()) {
            return Double.NaN;
        }
        try {
            return v.asDouble();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static List<String> list(DataNode n, String f) {
        DataNode v = n.get(f);
        List<String> out = new ArrayList<>();
        if (v != null && !v.isMissing() && !v.isNull()) {
            if (v.type() == com.ash.drishti.api.NodeType.ARRAY) {
                for (int i = 0; i < v.size(); i++) {
                    out.add(v.get(i).asText());
                }
            } else {
                out.add(v.asText());
            }
        }
        return out;
    }

    private Day read(AsOf asOf) {
        LocalDate date = asOf.businessDate();
        Map<String, Integer> refusals = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        // the dictionary: the newest row of each factor on or before the date
        List<FactorDictionary.Factor> factors = new ArrayList<>();
        boolean factorsRead = reader.documents(props.factorKind(), asOf, n -> {
            String name = text(n, "RISK_FACTOR");
            if (name != null) {
                factors.add(new FactorDictionary.Factor(name, text(n, "RISK_CLASS"), text(n, "FACTOR_TYPE"), text(n, "QUALIFIER"), text(n, "CURVE"),
                        text(n, "UNDERLYING"), text(n, "TENOR"), text(n, "UNDERLYING_TENOR"), text(n, "FRTB_BUCKET"), text(n, "SIMM_BUCKET"),
                        list(n, "SOURCE_NAMES")));
            }
        });
        FactorDictionary dict = new FactorDictionary(dedupe(factors), props.factorKind() + "@" + date);
        Map<String, Double> usdPer = new HashMap<>();
        usdPer.put("USD", 1.0);
        reader.documents("fx-spot", asOf, n -> {
            String pair = text(n, "PAIR");
            double mid = num(n, "MID");
            if (pair != null && mid > 0) {
                String p = pair.replace("FX-", "").replace("/", "");
                if (p.length() == 6 && p.endsWith("USD")) {
                    usdPer.put(p.substring(0, 3), mid);
                } else if (p.length() == 6 && p.startsWith("USD")) {
                    usdPer.put(p.substring(3), 1 / mid);
                }
            }
        });
        // the sensitivities: one full run per source system and date; a record's factor and measure once
        List<Record> records = new ArrayList<>();
        Map<String, String> runOf = new HashMap<>();
        Set<String> batches = new HashSet<>();
        int[] proxied = {0};
        boolean sensRead = reader.documents(props.sensitivityKind(), asOf, n -> {
            String day = text(n, "BUSINESS_DATE");
            if (day != null && date != null && !day.startsWith(date.toString())) {
                return;
            }
            String source = text(n, "SOURCE_SYSTEM");
            String batch = text(n, "BATCH_ID");
            String run = text(n, "RUN_KIND");
            if ("FULL".equalsIgnoreCase(run) && source != null && batch != null) {
                String was = runOf.putIfAbsent(source, batch);
                if (was != null && !was.equals(batch)) {
                    refusals.merge("DRS-5326 a second full run of the source on the date (refused: send a restatement)", 1, Integer::sum);
                    return;
                }
            }
            if ("proxied".equalsIgnoreCase(text(n, "STATUS"))) {
                proxied[0]++;
            }
            if (batch != null) {
                batches.add(batch);
            }
            Map<String, String> attrs = new LinkedHashMap<>();
            for (String a : ATTRIBUTES) {
                String v = text(n, a);
                if (v != null) {
                    attrs.put(a, v);
                }
            }
            List<Element> els = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean normal = "NORMAL".equalsIgnoreCase(text(n, "VOL_CONVENTION"));    // decision 3: normal vols converted on load
            DataNode arr = n.get("SENSITIVITIES");
            boolean dup = false;
            for (int i = 0; arr != null && !arr.isMissing() && i < arr.size(); i++) {
                DataNode e = arr.get(i);
                String fname = text(e, "RISK_FACTOR");
                String m = text(e, "MEASURE");
                FactorDictionary.Factor f = dict.resolve(fname);
                if (f == null) {
                    refusals.merge("DRS-5321 a risk factor not in the factor dictionary", 1, Integer::sum);
                    continue;
                }
                double v = num(e, "VALUE");
                if (!Double.isFinite(v)) {
                    refusals.merge("DRS-5323 a value that is not a finite number", 1, Integer::sum);
                    continue;
                }
                if (!seen.add(f.name() + "|" + m)) {
                    dup = true;
                    break;
                }
                String bucket = text(e, "FRTB_BUCKET");
                if (bucket != null && f.frtbBucket() != null && !bucket.equals(f.frtbBucket()) && warnings.size() < 50) {
                    warnings.add(f.name() + ": the delivered bucket " + bucket + " differs from the dictionary's " + f.frtbBucket() + " (the dictionary wins)");
                }
                double iv = num(e, "IMPLIED_VOL");
                if (normal && "VEGA".equalsIgnoreCase(m)) {
                    iv = iv / 100;                                    // VEGA per bp × σ in bp = VEGA × 100 × (σ_bp / 100)
                }
                els.add(new Element(f.name(), m == null ? "" : m.toUpperCase(Locale.ROOT), v, text(e, "CURRENCY"), num(e, "SHOCK_SIZE"), iv, bucket));
            }
            if (dup) {
                refusals.merge("DRS-5325 the same factor and measure twice in a record (the record refused)", 1, Integer::sum);
                return;
            }
            records.add(new Record(text(n, "SENSITIVITY_ID"), text(n, "GRAIN"), text(n, "POSITION_ID"), text(n, "TRADE_ID"), list(n, "TRADE_IDS"), attrs,
                    List.copyOf(els)));
        });
        List<DataNode> drc = new ArrayList<>();
        boolean drcRead = reader.documents(props.defaultRiskKind(), asOf, drc::add);
        List<DataNode> rrao = new ArrayList<>();
        boolean rraoRead = reader.documents(props.residualRiskKind(), asOf, rrao::add);
        return new Day(date, asOf, dict, List.copyOf(records), refusals, List.copyOf(warnings), Set.copyOf(batches), drcRead ? drc : null,
                rraoRead ? rrao : null, Map.copyOf(usdPer), sensRead, factorsRead, proxied[0]);
    }

    /** One row a factor (a date's re-delivered row replaces the earlier one). */
    private static List<FactorDictionary.Factor> dedupe(List<FactorDictionary.Factor> fs) {
        Map<String, FactorDictionary.Factor> m = new LinkedHashMap<>();
        fs.forEach(f -> m.put(f.name(), f));
        return new ArrayList<>(m.values());
    }

    /** A list's size for the cache's weigher; an input not read (no default-risk data) is null. */
    public static long size(java.util.Collection<?> c) {
        return c == null ? 0 : c.size();
    }
}
