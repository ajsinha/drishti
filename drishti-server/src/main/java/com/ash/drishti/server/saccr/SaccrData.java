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
package com.ash.drishti.server.saccr;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.server.frtb.FrtbData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A business date's SA-CCR inputs (docs/guides/SACCR_GUIDE.md section 3; docs/contracts/SACCR.md sections 3 and 4): the classification
 * rows ({@code saccr-trade}) by netting set, each netting set's live members with their MTM (the trade valuations of a live status naming
 * the set: the fundamental source, as the exposure engine reads it), the netting sets, their CSAs and collateral balances.
 */
public final class SaccrData {

    /** The trade statuses that count (COMPUTED_ATTRIBUTES.md section 3, Live; the exposure engine's rule). */
    static final Set<String> LIVE = Set.of("NEW", "VALIDATED", "AMENDED");

    /** A live member of a netting set: its id, MTM (NaN: none) and product (for the excluded count's reason). */
    public record Member(String tradeId, double mtm, String productType, String productGroup, double notional) {}

    /**
     * A netting set's master data and its CSA's SA-CCR terms.
     *
     * @param marginTermsMissing a VM CSA without TH, MTA or MPOR: computed unmargined and flagged MARGIN_TERMS_MISSING
     */
    public record SetInfo(String id, String counterparty, String legalEntity, String csa, boolean enforceable, boolean cleared, boolean margined,
            boolean marginTermsMissing, double th, double mta, int mporDays, int frequencyDays, int disputes, boolean illiquid, double c, double nica,
            String collateralNote) {}

    /** A date's inputs. */
    public record Day(LocalDate date, Map<String, SetInfo> sets, Map<String, List<Map<String, Object>>> rows, Map<String, List<Member>> members,
            boolean rowsRead, boolean setsRead, int rowsWithoutSet) {}

    private final FrtbData.Reader reader;
    private final SaccrProperties props;
    private final Map<String, Day> cache;

    public SaccrData(FrtbData.Reader reader, SaccrProperties props) {
        this.reader = reader;
        this.props = props;
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(8).expireAfterWrite(Duration.ofMinutes(props.cacheMinutes()))
                .<String, Day>build().asMap();
    }

    /** Forgets every date. */
    public void clear() {
        cache.clear();
    }

    /** The kinds whose load changes a date's answers. */
    public List<String> kinds() {
        return List.of(props.tradeKind(), props.nettingSetKind(), props.csaKind(), props.collateralKind(), props.valuationKind());
    }

    /** The inputs of {@code asOf}. */
    public Day day(AsOf asOf) {
        String key = asOf.businessDate() + "|" + asOf.knownAt();
        return cache.computeIfAbsent(key, k -> read(asOf));
    }

    static String text(DataNode n, String f) {
        DataNode v = n == null ? null : n.get(f);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    static double num(DataNode n, String f, double dflt) {
        DataNode v = n == null ? null : n.get(f);
        if (v == null || v.isMissing() || v.isNull()) {
            return dflt;
        }
        try {
            return v.asDouble();
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    static boolean bool(DataNode n, String f, boolean dflt) {
        String t = text(n, f);
        return t == null ? dflt : t.strip().equalsIgnoreCase("true");
    }

    /** A document as a plain map of its scalar fields (arrays and objects kept as text). */
    static Map<String, Object> plain(DataNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (n.unwrap() instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                DataNode v = n.get(k);
                if (v == null || v.isMissing() || v.isNull()) {
                    continue;
                }
                Object u = v.unwrap();
                out.put(k, v.type() == NodeType.OBJECT || v.type() == NodeType.ARRAY ? String.valueOf(u)
                        : u instanceof Number || u instanceof Boolean ? u : v.asText());
            }
        }
        return out;
    }

    private Day read(AsOf asked) {
        // the classification rows first: with no date asked, the newest business date they hold is the date of every other input
        Map<String, List<Map<String, Object>>> all = new LinkedHashMap<>();
        int[] orphan = new int[1];
        String[] newest = {null};
        boolean rowsRead = reader.documents(props.tradeKind(), asked, n -> {
            String ns = text(n, "NETTING_SET");
            if (ns == null) {
                orphan[0]++;
                return;
            }
            String d = text(n, "BUSINESS_DATE");
            if (d != null && d.length() >= 10 && (newest[0] == null || d.substring(0, 10).compareTo(newest[0]) > 0)) {
                newest[0] = d.substring(0, 10);
            }
            all.computeIfAbsent(ns, k -> new ArrayList<>()).add(plain(n));
        });
        LocalDate date = asked.businessDate() != null ? asked.businessDate() : newest[0] != null ? LocalDate.parse(newest[0]) : LocalDate.now();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        String day = date.toString();
        all.forEach((ns, l) -> {
            List<Map<String, Object>> keep = l.stream().filter(r -> r.get("BUSINESS_DATE") == null || String.valueOf(r.get("BUSINESS_DATE")).startsWith(day)).toList();
            if (!keep.isEmpty()) {
                rows.put(ns, new ArrayList<>(keep));
            }
        });
        AsOf asOf = new AsOf(date, asked.knownAt());
        Map<String, DataNode> csas = new LinkedHashMap<>();
        reader.documents(props.csaKind(), asOf, n -> {
            String id = text(n, "CSA_ID");
            if (id != null) {
                csas.put(id, n);
            }
        });
        Map<String, DataNode> balances = new LinkedHashMap<>();
        reader.documents(props.collateralKind(), asOf, n -> {
            String id = text(n, "BALANCE_ID");
            if (id != null) {
                balances.put(id, n);
            }
        });
        Map<String, SetInfo> sets = new TreeMap<>();
        boolean setsRead = reader.documents(props.nettingSetKind(), asOf, n -> {
            String id = text(n, "NETTING_SET_ID");
            if (id != null) {
                sets.put(id, info(id, n, csas, balances));
            }
        });
        Map<String, List<Member>> members = new LinkedHashMap<>();
        reader.documents(props.valuationKind(), asOf, n -> {
            String ns = text(n, "NETTING_SET");
            String tid = text(n, "TRADE_ID");
            String status = text(n, "TRADE_STATUS");
            String mat = text(n, "MATURITY_DATE");
            if (ns == null || ns.isBlank() || tid == null || status != null && !LIVE.contains(status.toUpperCase(Locale.ROOT))
                    || mat != null && mat.length() >= 10 && mat.substring(0, 10).compareTo(day) < 0) {
                return;
            }
            members.computeIfAbsent(ns, k -> new ArrayList<>()).add(new Member(tid, num(n, "MTM", Double.NaN), text(n, "PRODUCT_TYPE"), text(n, "PRODUCT_GROUP"),
                    num(n, "NOTIONAL", Double.NaN)));
        });
        return new Day(date, sets, rows, members, rowsRead, setsRead, orphan[0]);
    }

    private static SetInfo info(String id, DataNode n, Map<String, DataNode> csas, Map<String, DataNode> balances) {
        String csaId = text(n, "CSA");
        DataNode csa = csaId == null ? null : csas.get(csaId);
        DataNode bal = balances.get(String.valueOf(text(n, "COLLATERAL_BALANCE")));
        String type = csa == null ? "" : String.valueOf(text(csa, "MARGIN_TYPE"));
        boolean vm = type.toUpperCase(Locale.ROOT).contains("VM");
        double th = num(csa, "THRESHOLD_THEM", Double.NaN);
        double mta = num(csa, "MTA_THEM", num(csa, "MTA", Double.NaN));
        double mpor = num(csa, "MPOR_DAYS", Double.NaN);
        boolean missing = vm && (!Double.isFinite(th) || !Double.isFinite(mta) || !Double.isFinite(mpor));
        double nica = num(csa, "INDEPENDENT_AMOUNT", 0) + num(bal, "IM_HELD", 0) - num(bal, "IM_POSTED_UNSEGREGATED", 0);
        double c = num(bal, "NET", 0) + nica;
        String note = bal == null ? "no collateral balance: C = 0" : "C = the balance's NET after the CSA's haircuts, plus NICA (COLL's C_REG at supervisory "
                + "haircuts is read when delivered)";
        if (bal != null && bal.get("C_REG") != null && !bal.get("C_REG").isMissing() && !bal.get("C_REG").isNull()) {
            c = num(bal, "C_REG", c);
            nica = num(bal, "NICA_REG", nica);
            note = "C and NICA at supervisory haircuts (COLL's C_REG, NICA_REG)";
        }
        return new SetInfo(id, text(n, "COUNTERPARTY"), text(n, "LEGAL_ENTITY"), csaId, bool(n, "NETTING_ENFORCEABLE", true), bool(n, "CLEARED", false),
                vm && !missing, missing, Double.isFinite(th) ? th : 0, Double.isFinite(mta) ? mta : 0, Double.isFinite(mpor) ? (int) mpor : 0,
                (int) num(csa, "MARGIN_FREQUENCY_DAYS", 1), (int) num(csa, "DISPUTES_LAST_2Q", 0), bool(csa, "ILLIQUID_COLLATERAL", false), c, nica, note);
    }
}
