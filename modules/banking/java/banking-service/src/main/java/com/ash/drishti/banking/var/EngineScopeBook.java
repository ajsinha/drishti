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
package com.ash.drishti.banking.var;

import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.compute.vector.OrdinalSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * R2 (ENGINE_INTEGRATION_DESIGN 18.1, EN-68): the market-risk engine's index of one date (its outputs {@code scope-index},
 * {@code scope-positions}, {@code scope-position-values}, {@code scope-nodes}, {@code scope-words}, {@code scope-limits}) as a book the
 * host's resolver reads. The scope grammar, the saved and system baskets and the resolution are Drishti's; the rows, their order and the
 * positions the rules derive are the engine's. The ordinals the resolver gives are the row set the engine path asks with.
 */
final class EngineScopeBook implements ScopeResolver.Book {

    private final String[] trades;
    private final int[] positionOf;
    private final boolean[] supplied;
    private final boolean[] cancelled;
    private final Map<String, String[]> tradeTexts = new HashMap<>();
    private final Map<String, Integer> tradeUpper = new HashMap<>();
    private final Map<String, Integer> tradeExact = new HashMap<>();
    private final String[] positionIds;
    private final int[][] positionRuns;
    private final Map<String, Integer> positionUpper = new HashMap<>();
    private final Map<String, Integer> positionExact = new HashMap<>();
    private final List<Map<String, Object>> positionValues;
    private final Map<String, OrdinalSet> nodes = new LinkedHashMap<>();
    private final Map<String, Double> nodeLimits = new HashMap<>();
    private final Map<String, String> roots = new LinkedHashMap<>();
    final Map<String, String> words = new LinkedHashMap<>();
    final Map<String, Double> basketLimits = new LinkedHashMap<>();

    EngineScopeBook(Map<String, Object> answer) {
        List<Map<String, Object>> idx = VarEnginePath.rows(answer, "scope-index");
        int n = idx.size();
        trades = new String[n];
        positionOf = new int[n];
        supplied = new boolean[n];
        cancelled = new boolean[n];
        String[] cpty = new String[n];
        String[] le = new String[n];
        for (Map<String, Object> r : idx) {
            int o = ((Number) r.get("ORDINAL")).intValue();
            trades[o] = String.valueOf(r.get("TRADE_ID"));
            positionOf[o] = ((Number) r.get("POSITION")).intValue();
            supplied[o] = Boolean.TRUE.equals(r.get("SUPPLIED"));
            cancelled[o] = Boolean.TRUE.equals(r.get("CANCELLED"));
            cpty[o] = (String) r.get("COUNTERPARTY");
            le[o] = (String) r.get("LEGAL_ENTITY");
        }
        tradeTexts.put("COUNTERPARTY", cpty);
        tradeTexts.put("LEGAL_ENTITY", le);
        for (int o = 0; o < n; o++) {
            tradeExact.putIfAbsent(trades[o], o);
            tradeUpper.putIfAbsent(trades[o].toUpperCase(Locale.ROOT), o);
        }
        List<Map<String, Object>> pos = VarEnginePath.rows(answer, "scope-positions");
        positionIds = new String[pos.size()];
        positionRuns = new int[pos.size()][];
        for (Map<String, Object> r : pos) {
            int p = ((Number) r.get("POSITION")).intValue();
            positionIds[p] = String.valueOf(r.get("POSITION_ID"));
            positionRuns[p] = ints(r.get("RUNS"));
            positionExact.putIfAbsent(positionIds[p], p);
            positionUpper.putIfAbsent(positionIds[p].toUpperCase(Locale.ROOT), p);
        }
        positionValues = new ArrayList<>();
        for (int p = 0; p < positionIds.length; p++) {
            positionValues.add(new HashMap<>());
        }
        for (Map<String, Object> r : VarEnginePath.rows(answer, "scope-position-values")) {
            int p = ((Number) r.get("POSITION")).intValue();
            String c = String.valueOf(r.get("COLUMN"));
            Object v = r.get("TEXT") != null ? r.get("TEXT") : r.get("NUMBER") == null ? null : ((Number) r.get("NUMBER")).doubleValue();
            if (v instanceof String t && c.equals("TAGS")) {              // a list column, written a|b|c (Columns.value)
                v = t.isEmpty() ? List.of() : List.of(t.split("\\|"));
            }
            positionValues.get(p).put(c, v);
        }
        for (Map<String, Object> r : VarEnginePath.rows(answer, "scope-nodes")) {
            String id = String.valueOf(r.get("NODE_ID"));
            int[] runs = ints(r.get("RUNS"));
            OrdinalSet.Builder b = new OrdinalSet.Builder();
            for (int i = 0; i + 1 < runs.length; i += 2) {
                b.addRange(runs[i], runs[i + 1]);
            }
            nodes.put(id, b.build());
            if (r.get("LIMIT") instanceof Number l) {
                nodeLimits.put(id, l.doubleValue());
            }
            if (r.get("PARENT") == null) {
                roots.putIfAbsent(String.valueOf(r.get("HIERARCHY")), id);
            }
        }
        for (Map<String, Object> r : VarEnginePath.rows(answer, "scope-words")) {
            words.put(String.valueOf(r.get("WORD")), String.valueOf(r.get("HIERARCHY")));
        }
        for (Map<String, Object> r : VarEnginePath.rows(answer, "scope-limits")) {
            basketLimits.put(String.valueOf(r.get("BASKET_ID")), ((Number) r.get("LIMIT")).doubleValue());
        }
    }

    private static int[] ints(Object list) {
        if (list instanceof double[] d) {
            int[] out = new int[d.length];
            for (int i = 0; i < d.length; i++) {
                out[i] = (int) d[i];
            }
            return out;
        }
        Collection<?> c = list instanceof Collection<?> l ? l : List.of();
        int[] out = new int[c.size()];
        int i = 0;
        for (Object x : c) {
            out[i++] = ((Number) x).intValue();
        }
        return out;
    }

    int rows() {
        return trades.length;
    }

    /** A node's limit (USD), NaN when it has none. */
    double nodeLimit(String node) {
        return nodeLimits.getOrDefault(node, Double.NaN);
    }

    @Override
    public OrdinalSet members(String node) {
        return nodes.get(node);
    }

    @Override
    public List<String> hierarchies() {
        return List.copyOf(roots.keySet());
    }

    @Override
    public String rootId(String hierarchy) {
        return roots.get(hierarchy);
    }

    @Override
    public int positionIndexAnyCase(String id) {
        Integer p = positionExact.get(id);
        if (p == null) {
            p = positionUpper.get(id.toUpperCase(Locale.ROOT));
        }
        return p == null ? -1 : p;
    }

    @Override
    public void forEachPositionRun(int index, OrdinalSet.RunConsumer f) {
        int[] r = positionRuns[index];
        for (int i = 0; i + 1 < r.length; i += 2) {
            f.run(r[i], r[i + 1]);
        }
    }

    @Override
    public int tradeOrdinalAnyCase(String id) {
        Integer o = tradeExact.get(id);
        if (o == null) {
            o = tradeUpper.get(id.toUpperCase(Locale.ROOT));
        }
        return o == null ? -1 : o;
    }

    @Override
    public boolean supplied(int ordinal) {
        return ordinal >= 0 && supplied[ordinal];
    }

    @Override
    public OrdinalSet tradesWhere(String column, Collection<String> values) {
        String[] t = tradeTexts.get(column);
        OrdinalSet.Builder b = new OrdinalSet.Builder();
        if (t == null) {
            return b.build();
        }
        for (int o = 0; o < t.length; o++) {
            if (t[o] != null && !cancelled[o]) {
                for (String v : values) {
                    if (t[o].equalsIgnoreCase(v)) {
                        b.add(o);
                        break;
                    }
                }
            }
        }
        return b.build();
    }

    @Override
    public int positionCount() {
        return positionIds.length;
    }

    @Override
    public Object positionValue(String column, int position) {
        return positionValues.get(position).get(column);
    }
}
