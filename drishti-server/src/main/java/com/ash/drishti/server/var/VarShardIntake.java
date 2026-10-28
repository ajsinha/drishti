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
package com.ash.drishti.server.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the intake of a sharded date did (VAR_SHARDING.md section 5): each shard's own record (its hot file's {@code META}) added up, and the
 * date's checks made again across the shards, since each shard saw only its rows: one run (one batch id, the date's scenario set) and one
 * form and one grain per source system. A date that fails them is refused as one server refuses it.
 */
final class VarShardIntake {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final int LISTED = 20;

    /** One shard's record of its load, as its hot file keeps it: the intake, the versions, the FX used. */
    record Meta(VarIntake.Summary intake, Map<String, String> versions, Map<String, Object> fx, int withoutPv) {}

    private VarShardIntake() {}

    @SuppressWarnings("unchecked")
    static Meta read(String meta) {
        try {
            com.fasterxml.jackson.databind.JsonNode m = JSON.readTree(meta == null || meta.isBlank() ? "{}" : meta);
            VarIntake.Summary s = m.has("intake") ? JSON.treeToValue(m.get("intake"), VarIntake.Summary.class) : null;
            Map<String, String> v = new LinkedHashMap<>();
            m.path("versions").fields().forEachRemaining(e -> v.put(e.getKey(), e.getValue().asText()));
            Map<String, Object> fx = m.path("fx").isObject() ? JSON.convertValue(m.get("fx"), Map.class) : Map.of();
            return new Meta(s, v, fx, m.path("withoutPv").asInt());
        } catch (Exception e) {
            throw new IllegalStateException("a shard's record of its load cannot be read: " + e.getMessage(), e);
        }
    }

    /** The shards' intakes added up; refused when the date is not one run or a system sent two forms or grains across shards. */
    static VarIntake.Summary merge(LocalDate date, String setId, List<VarIntake.Summary> parts) {
        int rejected = 0;
        int excluded = 0;
        int scenarioPv = 0;
        int setLength = 0;
        Boolean oldIds = null;
        List<String> rejections = new ArrayList<>();
        Set<String> batches = new TreeSet<>();
        Set<String> sets = new TreeSet<>();
        Map<String, String> notLive = new TreeMap<>();
        Set<String> pvTrades = new TreeSet<>();
        Map<String, Map<String, Object>> inputs = new TreeMap<>();
        Map<String, List<String>> positionTrades = new TreeMap<>();
        String rules = "";
        String rulesDigest = "";
        for (VarIntake.Summary s : parts) {
            if (s == null) {
                continue;
            }
            rejected += s.rejected();
            excluded += s.excluded();
            scenarioPv += s.scenarioPv();
            setLength = Math.max(setLength, s.setLength());
            oldIds = Boolean.TRUE.equals(oldIds) || Boolean.TRUE.equals(s.oldPnlIds()) ? Boolean.TRUE : s.oldPnlIds() == null ? oldIds : s.oldPnlIds();
            for (String r : s.rejections()) {
                if (rejections.size() < LISTED) {
                    rejections.add(r);
                }
            }
            batches.addAll(s.batchIds());
            sets.addAll(s.scenarioSets());
            if (s.notLive() != null) {
                notLive.putAll(s.notLive());
            }
            if (s.pvTrades() != null) {
                pvTrades.addAll(s.pvTrades());
            }
            if (s.positionTrades() != null) {
                positionTrades.putAll(s.positionTrades());
            }
            rules = s.rules();
            rulesDigest = s.rulesDigest();
            if (s.inputs() != null) {
                s.inputs().forEach((sys, d) -> {
                    Map<String, Object> have = inputs.get(sys);
                    if (have == null) {
                        inputs.put(sys, new LinkedHashMap<>(d));
                        return;
                    }
                    if (!String.valueOf(have.get("form")).equals(String.valueOf(d.get("form")))
                            || !String.valueOf(have.get("grain")).equals(String.valueOf(d.get("grain")))) {
                        throw new DrishtiException(ErrorCode.VAR_INPUT_MIXED, "the inputs of " + date + " are not loaded: " + sys + " sent "
                                + have.get("form") + ", " + have.get("grain") + " and " + d.get("form") + ", " + d.get("grain")
                                + "; one source system sends one form and one grain on one date (VAR_GUIDE.md section 15.13)");
                    }
                    have.put("rows", number(have.get("rows")) + number(d.get("rows")));
                    have.put("tradesWithoutPv", number(have.get("tradesWithoutPv")) + number(d.get("tradesWithoutPv")));
                });
            }
        }
        if (batches.size() > 1) {
            throw new DrishtiException(ErrorCode.VAR_INCONSISTENT_DATE, "the VaR inputs of " + date + " are not loaded: the vectors come from "
                    + batches.size() + " runs (BATCH_ID " + String.join(", ", batches) + "): one run per date");
        }
        List<String> other = sets.stream().filter(x -> !x.equals(setId)).toList();
        if (!other.isEmpty()) {
            throw new DrishtiException(ErrorCode.VAR_INCONSISTENT_DATE, "the VaR inputs of " + date + " are not loaded: vectors of the scenario set "
                    + String.join(", ", other) + " beside the date's set " + setId);
        }
        return new VarIntake.Summary(rejected, List.copyOf(rejections), excluded, List.copyOf(batches), List.copyOf(sets), rules, rulesDigest,
                Map.copyOf(notLive), scenarioPv, Set.copyOf(pvTrades), inputs, Map.copyOf(positionTrades), setLength, oldIds);
    }

    private static int number(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
