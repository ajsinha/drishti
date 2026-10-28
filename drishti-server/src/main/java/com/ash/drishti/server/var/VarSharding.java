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

import com.ash.drishti.compute.var.ShardMap;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What VaR sharding does now (VAR_SHARDING.md section 9): the market-risk pack's {@code sharding:} block, each key overridden by the
 * server's {@code drishti.var.sharding} when set. Off unless switched on; a block that is not valid leaves sharding off and says why
 * (status, log) rather than half on.
 *
 * @param enabled sharding on
 * @param key the shard key's columns
 * @param kinds the kinds named as sharded
 * @param targetBytes a shard's target size (float32 rows)
 * @param replicas each shard's owners
 * @param origin where the settings came from ({@code pack market-risk}, {@code drishti.var.sharding}, {@code default})
 * @param problem why a block switched on is not used, or null
 */
record VarSharding(boolean enabled, List<String> key, List<String> kinds, long targetBytes, int replicas, String origin, String problem) {

    static final double DEFAULT_TARGET_MB = 2048;
    static final int DEFAULT_REPLICAS = 2;

    VarSharding {
        key = List.copyOf(key);
        kinds = List.copyOf(kinds);
    }

    /** Sharding off (no block, no setting). */
    static VarSharding off(VarProperties.Kinds k, String origin, String problem) {
        return new VarSharding(false, ShardMap.ORDER, List.of(k.scenarioPv(), k.vector()), (long) (DEFAULT_TARGET_MB * 1024 * 1024),
                DEFAULT_REPLICAS, origin, problem);
    }

    /**
     * The settings now: the server's keys over the first loaded pack's {@code sharding:} block. Server settings that are not valid refuse the
     * start (as other VaR settings do); a pack's block that is not valid is said and left off.
     */
    static Supplier<VarSharding> of(VarShardingProperties own, VarProperties var, Supplier<PackRegistry> packs) {
        if (own.enabled() != null || own.key() != null || own.kinds() != null || own.targetShardMb() != null || own.replicas() != null) {
            resolve(own, Map.of(), var.kinds(), "drishti.var.sharding", true);      // checked at start
        }
        return () -> {
            Map<?, ?> block = Map.of();
            String origin = "drishti.var.sharding";
            PackRegistry r = packs == null ? null : packs.get();
            if (r != null) {
                for (Pack p : r.packs()) {
                    if (p.manifest().get("sharding") instanceof Map<?, ?> m && !m.isEmpty()) {
                        block = m;
                        origin = "pack " + p.name() + (own.enabled() != null ? " and drishti.var.sharding" : "");
                        break;
                    }
                }
            }
            return resolve(own, block, var.kinds(), origin, false);
        };
    }

    private static VarSharding resolve(VarShardingProperties own, Map<?, ?> block, VarProperties.Kinds k, String origin, boolean strict) {
        Boolean on = own.enabled() != null ? own.enabled() : block.get("enabled") instanceof Boolean b ? b
                : block.get("enabled") != null ? Boolean.parseBoolean(String.valueOf(block.get("enabled"))) : null;
        if (on == null || !on) {
            return off(k, block.isEmpty() && own.enabled() == null ? "default" : origin, null);
        }
        try {
            List<String> key = ShardMap.checkKey(own.key() != null ? own.key() : strings(block.get("key")));
            List<String> kinds = own.kinds() != null ? own.kinds() : strings(block.get("kinds"));
            List<String> trades = List.of(k.vector(), k.scenarioPv());
            List<String> norm = new ArrayList<>();
            for (String x : kinds == null || kinds.isEmpty() ? trades : kinds) {
                String c = x.strip().toLowerCase(Locale.ROOT);
                if (!trades.contains(c)) {
                    throw new IllegalArgumentException("kinds names " + x + ": only the trades' scenario P&L (" + trades + ") is sharded; every "
                            + "other kind is read whole by each member");
                }
                norm.add(c);
            }
            double mb = own.targetShardMb() != null ? own.targetShardMb() : number(block.get("target-shard-mb"), DEFAULT_TARGET_MB);
            if (!(mb > 0)) {
                throw new IllegalArgumentException("target-shard-mb is " + mb + ": a size above 0 (2048 by default)");
            }
            int rep = own.replicas() != null ? own.replicas() : (int) number(block.get("replicas"), DEFAULT_REPLICAS);
            if (rep < 1) {
                throw new IllegalArgumentException("replicas is " + rep + ": at least 1 (2 by default)");
            }
            return new VarSharding(true, key, norm, (long) (mb * 1024 * 1024), rep, origin, null);
        } catch (IllegalArgumentException e) {
            if (strict) {
                throw new IllegalArgumentException("drishti.var.sharding: " + e.getMessage(), e);
            }
            return off(k, origin, "the sharding settings of " + origin + " are not valid, so sharding is off: " + e.getMessage());
        }
    }

    private static List<String> strings(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        if (o instanceof String s && !s.isBlank()) {
            return java.util.Arrays.stream(s.replace("[", "").replace("]", "").split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
        }
        return null;
    }

    private static double number(Object o, double dflt) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? dflt : Double.parseDouble(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + o);
        }
    }

    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("origin", origin);
        if (problem != null) {
            m.put("problem", problem);
        }
        m.put("key", key);
        m.put("kinds", kinds);
        m.put("targetShardMb", targetBytes / (1024.0 * 1024.0));
        m.put("replicas", replicas);
        return m;
    }
}
