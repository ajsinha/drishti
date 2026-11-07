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
package com.ash.drishti.lakehouse.delta;

import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;

/**
 * A Delta table (or a shared Delta table) seen as an Iceberg table: metadata built in memory from the Delta schema, with one Iceberg
 * snapshot per Delta version kept ({@code snapshot-id} and sequence number = version + 1, its commit time as the snapshot's time), so the
 * external-table reader's time travel (<em>known at</em>) finds the Delta version current at a moment. Every version is read with the
 * current schema: a column added later is null in older versions, a dropped one is not shown. No manifests: the files of a version are
 * listed by the Delta log ({@link DeltaTableOps}) or the sharing server.
 *
 * @param converted the schema, name mapping and partition spec
 * @param metadata the Iceberg metadata
 * @param spec the partition spec, parsed
 */
public record DeltaView(DeltaSchemas.Converted converted, TableMetadata metadata, PartitionSpec spec) {

    /** One Delta version: its number and commit time. */
    public record Version(long version, long timestampMs) {}

    /** The snapshot id of a Delta version. */
    public static long snapshotId(long version) {
        return version + 1;
    }

    /** The Delta version of a snapshot id. */
    public static long version(long snapshotId) {
        return snapshotId - 1;
    }

    /**
     * The view of a table at {@code location} whose versions are {@code versions} (oldest first, the current one last).
     *
     * @param id a stable id of the table (the Delta table id, or the share's table id)
     */
    public static DeltaView of(String location, String id, DeltaSchemas.Converted c, List<Version> versions) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode md = f.objectNode();
        Version current = versions.get(versions.size() - 1);
        md.put("format-version", 2);
        md.put("table-uuid", uuid(id).toString());
        md.put("location", location);
        md.put("last-sequence-number", snapshotId(current.version()));
        md.put("last-updated-ms", current.timestampMs());
        md.put("last-column-id", c.schema().highestFieldId());
        md.put("current-schema-id", 0);
        md.putArray("schemas").add(DeltaSchemas.schemaJson(c));
        md.put("default-spec-id", 0);
        md.putArray("partition-specs").add(c.spec());
        md.put("last-partition-id", 999 + c.spec().path("fields").size());
        md.put("default-sort-order-id", 0);
        md.putArray("sort-orders").addObject().put("order-id", 0).putArray("fields");
        md.putObject("properties").put("schema.name-mapping.default", c.nameMapping()).put("drishti.source-format", "delta");
        md.put("current-snapshot-id", snapshotId(current.version()));
        ArrayNode snaps = md.putArray("snapshots");
        ArrayNode log = md.putArray("snapshot-log");
        Long parent = null;
        for (Version v : versions) {
            ObjectNode s = snaps.addObject();
            s.put("snapshot-id", snapshotId(v.version()));
            if (parent != null) {
                s.put("parent-snapshot-id", parent);
            }
            s.put("sequence-number", snapshotId(v.version()));
            s.put("timestamp-ms", v.timestampMs());
            s.putObject("summary").put("operation", "append").put("delta-version", String.valueOf(v.version()));
            s.put("manifest-list", "delta-version:" + v.version());
            s.put("schema-id", 0);
            log.addObject().put("timestamp-ms", v.timestampMs()).put("snapshot-id", snapshotId(v.version()));
            parent = snapshotId(v.version());
        }
        md.putArray("metadata-log");
        md.putObject("refs").putObject("main").put("snapshot-id", snapshotId(current.version())).put("type", "branch");
        TableMetadata m = new TableMetadata(md);
        return new DeltaView(c, m, m.spec());
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (RuntimeException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
