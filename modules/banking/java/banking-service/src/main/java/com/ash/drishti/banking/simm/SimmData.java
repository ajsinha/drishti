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
package com.ash.drishti.banking.simm;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.banking.compute.simm.SimmInput;
import com.ash.drishti.banking.frtb.FrtbData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A business date's SIMM inputs (docs/guides/SIMM_GUIDE.md section 3; docs/contracts/SIMM.md sections 3 and 4): the position
 * sensitivities, the dictionary and the FX of {@link FrtbData} (one table for FRTB-SA and SIMM), the dictionary's SIMM mapping (the CRIF
 * terms of each factor), the netting sets and their trades, the CSAs' SIMM terms, the notional add-on lines, the counterparty's calls and
 * the stored figures. Each record of the sensitivities is placed in the one netting set its trades lie in; a record whose trades span
 * netting sets is left out and counted ({@code DRS-5335}).
 */
public final class SimmData {

    /** A netting set and its trades. */
    public record NettingSet(String id, String counterparty, String csa, String legalEntity, List<String> trades) {}

    /**
     * A CSA's SIMM terms (SIMM.md section 4; not in the {@code csa} contract yet, read when present).
     *
     * @param version {@code SIMM_VERSION}: the version the CSA names (blank: the server's default)
     * @param collect {@code IM_COLLECT_REGIMES}
     * @param post {@code IM_POST_REGIMES}
     * @param calculationCurrency {@code IM_CALCULATION_CURRENCY}, else the CSA's currency, else USD
     * @param model {@code IM_MODEL}: {@code SIMM} (default) or {@code SCHEDULE} (schedule IM: not computed here)
     * @param fixed {@code IM_FIXED_ADDON}
     * @param multipliers {@code IM_PRODUCT_MULTIPLIERS}: product class → MS ≥ 1
     * @param factors {@code IM_ADDON_FACTORS}: product → notional factor
     * @param tolerance {@code IM_DISPUTE_TOLERANCE}: the difference with the counterparty's call accepted without dispute
     */
    public record Csa(String id, String version, List<String> collect, List<String> post, String calculationCurrency, String model, double fixed,
            Map<String, Double> multipliers, Map<String, Double> factors, double tolerance) {}

    /** The counterparty's call of one netting set and direction ({@code crif-external} HEADER rows). */
    public record Call(String nettingSet, String direction, String source, double total, Map<String, Double> byRiskClass) {}

    /** A date's SIMM inputs. */
    public record Day(LocalDate date, FrtbData.Day sens, Map<String, SimmInput.Factor> factors, Map<String, NettingSet> nettingSets, Map<String, Csa> csas,
            Map<String, List<FrtbData.Record>> recordsBySet, Map<String, Set<String>> tradesWithRecords, int spanning, List<String> spanningIds,
            Map<String, List<String>> tradeInTwo, Map<String, List<DataNode>> addOnLines, Map<String, List<Call>> calls, Map<String, DataNode> stored,
            boolean nettingSetsRead, boolean callsRead) {}

    private final FrtbData.Reader reader;
    private final FrtbData sensitivities;
    private final SimmProperties props;
    private final Map<String, Day> cache;

    public SimmData(FrtbData.Reader reader, FrtbData sensitivities, SimmProperties props) {
        this.reader = reader;
        this.sensitivities = sensitivities;
        this.props = props;
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(256L << 20)   // weighed in bytes (D3); the
                .<String, Day>weigher((k, d) -> (int) Math.min(Integer.MAX_VALUE, 4096L      // sensitivities themselves are FrtbData's
                        + 64L * (d.recordsBySet() == null ? 0 : d.recordsBySet().values().stream().mapToLong(FrtbData::size).sum())
                        + 256L * ((d.factors() == null ? 0 : d.factors().size()) + (d.nettingSets() == null ? 0 : d.nettingSets().size()))))
                .expireAfterWrite(Duration.ofMinutes(props.cacheMinutes())).<String, Day>build().asMap();
    }

    /** Forgets every date. */
    public void clear() {
        cache.clear();
    }

    /** The kinds whose load changes a date's answers (the sensitivities' kinds are FrtbData's). */
    public List<String> kinds() {
        List<String> k = new ArrayList<>(sensitivities.kinds());
        k.addAll(List.of(props.nettingSetKind(), props.csaKind(), props.addOnKind(), props.externalKind(), props.storedKind()));
        return k;
    }

    public FrtbData sensitivities() {
        return sensitivities;
    }

    /** The inputs of {@code asOf}. */
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

    static String text(DataNode n, String f) {
        DataNode v = n == null ? null : n.get(f);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    static double num(DataNode n, String f, double dflt) {
        DataNode v = n.get(f);
        if (v == null || v.isMissing() || v.isNull()) {
            return dflt;
        }
        try {
            return v.asDouble();
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    static List<String> list(DataNode n, String f) {
        DataNode v = n.get(f);
        List<String> out = new ArrayList<>();
        if (v != null && !v.isMissing() && !v.isNull()) {
            if (v.type() == NodeType.ARRAY) {
                for (int i = 0; i < v.size(); i++) {
                    out.add(v.get(i).asText());
                }
            } else {
                for (String s : v.asText().split("[,;]")) {
                    if (!s.isBlank()) {
                        out.add(s.strip());
                    }
                }
            }
        }
        return out;
    }

    static Map<String, Double> map(DataNode n, String f) {
        DataNode v = n.get(f);
        Map<String, Double> out = new LinkedHashMap<>();
        if (v != null && v.type() == NodeType.OBJECT && v.unwrap() instanceof Map<?, ?> m) {
            m.forEach((k, x) -> {
                if (x instanceof Number num) {
                    out.put(String.valueOf(k), num.doubleValue());
                } else if (x != null) {
                    try {
                        out.put(String.valueOf(k), Double.parseDouble(String.valueOf(x)));
                    } catch (NumberFormatException e) {
                        // not a number: left out
                    }
                }
            });
        }
        return out;
    }

    private Day read(AsOf asOf) {
        FrtbData.Day sens = sensitivities.day(asOf);
        LocalDate date = sens.date() != null ? sens.date() : asOf.businessDate();
        AsOf at = new AsOf(date, asOf.knownAt());
        // the dictionary's SIMM mapping (the newest row of each factor)
        Map<String, SimmInput.Factor> factors = new LinkedHashMap<>();
        reader.documents(sensitivities.factorKind(), at, n -> {
            String name = text(n, "RISK_FACTOR");
            if (name != null) {
                factors.put(name, new SimmInput.Factor(name, text(n, "SIMM_RISK_CLASS"), text(n, "FACTOR_TYPE"), text(n, "SIMM_BUCKET"), text(n, "QUALIFIER"),
                        text(n, "SIMM_LABEL1"), text(n, "SIMM_LABEL2"), text(n, "SENIORITY")));
            }
        });
        Map<String, NettingSet> sets = new TreeMap<>();
        boolean setsRead = reader.documents(props.nettingSetKind(), at, n -> {
            String id = text(n, "NETTING_SET_ID");
            if (id == null) {
                id = text(n, "NETTING_SET");
            }
            if (id != null) {
                List<String> trades = list(n, "TRADE_IDS");
                if (trades.isEmpty()) {
                    DataNode t = n.get("TRADES");
                    for (int i = 0; t != null && !t.isMissing() && t.type() == NodeType.ARRAY && i < t.size(); i++) {
                        String tid = text(t.get(i), "TRADE_ID");
                        if (tid != null) {
                            trades.add(tid);
                        }
                    }
                }
                sets.put(id, new NettingSet(id, text(n, "COUNTERPARTY"), text(n, "CSA"), text(n, "LEGAL_ENTITY"), List.copyOf(trades)));
            }
        });
        Map<String, Csa> csas = new LinkedHashMap<>();
        reader.documents(props.csaKind(), at, n -> {
            String id = text(n, "CSA_ID");
            if (id != null) {
                String calc = text(n, "IM_CALCULATION_CURRENCY");
                if (calc == null) {
                    calc = text(n, "CSA_CURRENCY");
                }
                String model = text(n, "IM_MODEL");
                csas.put(id, new Csa(id, text(n, "SIMM_VERSION"), list(n, "IM_COLLECT_REGIMES"), list(n, "IM_POST_REGIMES"), calc == null ? "USD"
                        : calc.toUpperCase(Locale.ROOT), model == null ? "SIMM" : model.toUpperCase(Locale.ROOT), num(n, "IM_FIXED_ADDON", 0),
                        map(n, "IM_PRODUCT_MULTIPLIERS"), map(n, "IM_ADDON_FACTORS"), num(n, "IM_DISPUTE_TOLERANCE", Double.NaN)));
            }
        });
        // trades → netting sets (a trade in two is kept in both, flagged)
        Map<String, List<String>> setsOf = new HashMap<>();
        sets.values().forEach(s -> s.trades().forEach(t -> setsOf.computeIfAbsent(t, x -> new ArrayList<>(1)).add(s.id())));
        Map<String, List<String>> inTwo = new TreeMap<>();
        setsOf.forEach((t, l) -> {
            if (l.size() > 1) {
                inTwo.put(t, List.copyOf(l));
            }
        });
        Map<String, List<FrtbData.Record>> bySet = new LinkedHashMap<>();
        Map<String, Set<String>> covered = new HashMap<>();
        int spanning = 0;
        List<String> spanningIds = new ArrayList<>();
        for (FrtbData.Record r : sens.records()) {
            Set<String> trades = new LinkedHashSet<>(r.tradeIds());
            if (r.tradeId() != null) {
                trades.add(r.tradeId());
            }
            Set<String> in = new LinkedHashSet<>();
            for (String t : trades) {
                List<String> l = setsOf.get(t);
                if (l != null && l.size() == 1) {
                    in.add(l.get(0));
                } else if (l != null) {
                    in.addAll(l);
                }
            }
            if (in.size() > 1 && trades.size() > 1) {
                spanning++;
                if (spanningIds.size() < 20) {
                    spanningIds.add(r.id());
                }
                continue;
            }
            for (String s : in) {
                bySet.computeIfAbsent(s, x -> new ArrayList<>()).add(r);
                Set<String> c = covered.computeIfAbsent(s, x -> new LinkedHashSet<>());
                c.addAll(trades);
            }
        }
        Map<String, List<DataNode>> addOns = new HashMap<>();
        reader.documents(props.addOnKind(), at, n -> {
            String t = text(n, "TRADE_ID");
            List<String> l = t == null ? null : setsOf.get(t);
            if (l != null) {
                l.forEach(s -> addOns.computeIfAbsent(s, x -> new ArrayList<>()).add(n));
            }
        });
        Map<String, List<Call>> calls = new HashMap<>();
        boolean callsRead = reader.documents(props.externalKind(), at, n -> {
            String ns = text(n, "NETTING_SET");
            if (ns != null && "HEADER".equalsIgnoreCase(text(n, "ROW_TYPE"))) {
                Map<String, Double> byRc = new LinkedHashMap<>();
                for (String rc : com.ash.drishti.banking.compute.simm.SimmParams.RISK_CLASSES) {
                    double v = num(n, "IM_" + rc.toUpperCase(Locale.ROOT), Double.NaN);
                    if (Double.isFinite(v)) {
                        byRc.put(rc, v);
                    }
                }
                String dir = text(n, "DIRECTION");
                calls.computeIfAbsent(ns, x -> new ArrayList<>()).add(new Call(ns, dir == null ? "COLLECT" : dir.toUpperCase(Locale.ROOT), text(n, "SOURCE"),
                        num(n, "IM_TOTAL", Double.NaN), byRc));
            }
        });
        Map<String, DataNode> stored = new HashMap<>();
        reader.documents(props.storedKind(), at, n -> {
            String ns = text(n, "NETTING_SET");
            if (ns != null) {
                stored.put(ns, n);
            }
        });
        return new Day(date, sens, Map.copyOf(factors), sets, csas, bySet, covered, spanning, List.copyOf(spanningIds), inTwo, addOns, calls, stored, setsRead,
                callsRead);
    }
}
