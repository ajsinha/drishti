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

import com.ash.drishti.api.KeyRange;
import com.ash.drishti.compute.var.ShardMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One shard of a date's shard map as a load reads it (VAR_SHARDING.md section 5): its index, the key range of its rows (from its first key,
 * shard 0 from the start of the key space, up to the next shard's first key, the last shard to the end), and the {@code RANG} section its
 * hot file carries.
 *
 * @param map the date's shard map
 * @param index the shard's index in it
 */
record VarShardScope(ShardMap map, int index) {

    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The data a shard's book (any currency) was built from: its {@code RANG} section's {@code data}, else its own digest. */
    static String data(String range, String own) {
        if (range == null || range.isBlank()) {
            return own;
        }
        try {
            String d = JSON.readTree(range).path("data").asText("");
            return d.isEmpty() ? own : d;
        } catch (Exception e) {
            return own;
        }
    }

    ShardMap.Shard shard() {
        return map.shard(index);
    }

    /** The rows this shard reads: the key range of the map's columns. */
    KeyRange range() {
        List<String> from = index == 0 ? List.of() : shard().first();
        List<String> to = index == map.size() - 1 ? List.of() : map.shard(index + 1).first();
        return new KeyRange(map.key(), from, to);
    }

    /** What the shard's hot file is built from beyond the date's inputs: its map and its place in it. */
    String key() {
        return map.digest().substring(0, 16) + ":" + index + "/" + map.size();
    }

    /**
     * The {@code RANG} section: the map, the shard, its range and its data ({@code data}: the digest of the inputs its USD book is built
     * from, which its books in other currencies carry too), as JSON.
     */
    String json(String data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shard", shard().id());
        m.put("index", index);
        m.put("shards", map.size());
        m.put("map", map.digest());
        m.put("key", map.key());
        m.put("range", shard().range());
        m.put("from", range().from());
        m.put("to", range().to());
        m.put("data", data);
        try {
            return JSON.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
