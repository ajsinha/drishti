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
package com.ash.drishti.banking.compute.var;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The golden record of a date's hierarchy trees (the test data of 2026-09-30): per node, its tree (parent, level, children), its members
 * (count and a digest of their trade ids), its aggregate (a digest of the float64 bits), its VaR, ES, worst scenarios and its children's
 * component VaR (exact doubles) and its limit. Recorded once from the trees as they were built before the hierarchies came from the
 * promoted columns only, and compared, field by field, with the trees built now.
 */
final class HierarchyGolden {

    private HierarchyGolden() {}

    /** Every node of the book, in id order, described as the golden file keeps it. */
    static Map<String, Map<String, Object>> describe(VarBook b) {
        VarCalc calc = VarCalc.defaults();
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (VarBook.Node x : b.nodes().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hierarchy", x.hierarchy());
            m.put("parent", x.parent());
            m.put("level", x.level());
            m.put("children", new ArrayList<>(new TreeSet<>(x.children())));
            List<String> trades = new ArrayList<>();
            x.members().forEach(o -> trades.add(b.tradeId(o)));
            trades.sort(null);
            m.put("members", trades.size());
            m.put("membersDigest", sha(String.join("\n", trades).getBytes(StandardCharsets.UTF_8)));
            double[] v = b.aggregate(x.id());
            java.nio.ByteBuffer bits = java.nio.ByteBuffer.allocate(8 * v.length);
            for (double d : v) {
                bits.putLong(Double.doubleToRawLongBits(d));
            }
            m.put("aggregateDigest", sha(bits.array()));
            VarCalc.Measures ms = calc.measures(v);
            m.put("var", ms.var());
            m.put("es", ms.es());
            List<Integer> worst = new ArrayList<>();
            for (int i = 0; i < Math.min(10, ms.order().length); i++) {
                worst.add(ms.order()[i]);
            }
            m.put("worst", worst);
            if (!x.children().isEmpty()) {
                List<VarCalc.Part> parts = new ArrayList<>();
                for (String c : new TreeSet<>(x.children())) {
                    parts.add(new VarCalc.Part(c, c, "node", b.aggregate(c), b.node(c).members().size()));
                }
                Map<String, Double> comp = new TreeMap<>();
                calc.contributions(v, ms, parts).forEach(c -> comp.put(c.key(), c.componentVar()));
                m.put("components", comp);
            }
            m.put("limit", Double.isNaN(x.limit()) ? null : x.limit());
            out.put(x.id(), m);
        }
        return out;
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
