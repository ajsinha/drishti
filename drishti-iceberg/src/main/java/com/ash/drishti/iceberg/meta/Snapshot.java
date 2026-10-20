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
package com.ash.drishti.iceberg.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot: the table's state after one commit. Its files are listed by its manifest list (or, in some v1 tables, by
 * a list of manifests in the metadata itself). The sequence number orders commits (v2; 0 in v1), which decides which
 * delete files apply to which data files.
 *
 * @param operation the summary's {@code operation} (append, overwrite, replace, delete)
 * @param manifests v1 only: the manifests listed in the metadata when there is no manifest list, else empty
 */
public record Snapshot(long snapshotId, Long parentId, long sequenceNumber, long timestampMs, String manifestList, List<String> manifests,
        String operation, Map<String, String> summary, Integer schemaId) {

    public Snapshot {
        manifests = List.copyOf(manifests);
        summary = Map.copyOf(summary);
    }

    public static Snapshot read(JsonNode n) {
        Map<String, String> summary = new LinkedHashMap<>();
        n.path("summary").fields().forEachRemaining(e -> summary.put(e.getKey(), e.getValue().asText()));
        List<String> manifests = new ArrayList<>();
        n.path("manifests").forEach(m -> manifests.add(m.asText()));
        return new Snapshot(n.path("snapshot-id").asLong(), n.hasNonNull("parent-snapshot-id") ? n.get("parent-snapshot-id").asLong() : null,
                n.path("sequence-number").asLong(0), n.path("timestamp-ms").asLong(),
                n.hasNonNull("manifest-list") ? n.get("manifest-list").asText() : null, manifests, summary.getOrDefault("operation", ""), summary,
                n.hasNonNull("schema-id") ? n.get("schema-id").asInt() : null);
    }

    /** The JSON for table metadata ({@code sequence-number} written for v2 only). */
    public ObjectNode write(int formatVersion) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("snapshot-id", snapshotId);
        if (parentId != null) {
            o.put("parent-snapshot-id", parentId);
        }
        if (formatVersion >= 2) {
            o.put("sequence-number", sequenceNumber);
        }
        o.put("timestamp-ms", timestampMs);
        ObjectNode s = o.putObject("summary");
        s.put("operation", operation);
        summary.forEach((k, v) -> {
            if (!k.equals("operation")) {
                s.put(k, v);
            }
        });
        o.put("manifest-list", manifestList);
        if (schemaId != null) {
            o.put("schema-id", schemaId);
        }
        return o;
    }
}
