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

import com.ash.drishti.iceberg.types.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One commit's changes to a table's metadata and the requirements they rest on, in the REST catalog protocol's terms
 * ({@code add-schema}, {@code add-snapshot}, {@code set-snapshot-ref}, {@code remove-snapshots}, {@code set-properties},
 * …; {@code assert-table-uuid}, {@code assert-ref-snapshot-id}, …). A REST catalog is sent them as they are; a
 * path-based table has them applied here to a copy of its metadata JSON ({@link #apply}), which keeps every field this
 * engine does not know.
 */
public final class TableUpdate {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final List<ObjectNode> updates = new ArrayList<>();
    private final List<ObjectNode> requirements = new ArrayList<>();

    public List<ObjectNode> updates() {
        return updates;
    }

    public List<ObjectNode> requirements() {
        return requirements;
    }

    public boolean isEmpty() {
        return updates.isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ requirements

    public TableUpdate requireUuid(String uuid) {
        if (uuid != null) {
            requirements.add(F.objectNode().put("type", "assert-table-uuid").put("uuid", uuid));
        }
        return this;
    }

    /** The {@code main} branch still at {@code snapshotId} (null: the table has no snapshot yet). */
    public TableUpdate requireMainAt(Long snapshotId) {
        ObjectNode r = F.objectNode().put("type", "assert-ref-snapshot-id").put("ref", "main");
        if (snapshotId == null) {
            r.putNull("snapshot-id");
        } else {
            r.put("snapshot-id", snapshotId);
        }
        requirements.add(r);
        return this;
    }

    public TableUpdate requireCurrentSchema(int schemaId) {
        requirements.add(F.objectNode().put("type", "assert-current-schema-id").put("current-schema-id", schemaId));
        return this;
    }

    public TableUpdate requireLastColumnId(int id) {
        requirements.add(F.objectNode().put("type", "assert-last-assigned-field-id").put("last-assigned-field-id", id));
        return this;
    }

    /** Whether the requirements hold against {@code base} (a path-based commit re-checks them on the newest version). */
    public String unmet(TableMetadata base) {
        for (ObjectNode r : requirements) {
            switch (r.path("type").asText()) {
                case "assert-table-uuid" -> {
                    if (!r.path("uuid").asText().equals(base.uuid())) {
                        return "the table was replaced (uuid " + base.uuid() + ")";
                    }
                }
                case "assert-ref-snapshot-id" -> {
                    Long want = r.get("snapshot-id").isNull() ? null : r.get("snapshot-id").asLong();
                    Long has = base.currentSnapshot().map(Snapshot::snapshotId).orElse(null);
                    if (want == null ? has != null : !want.equals(has)) {
                        return "main moved from " + want + " to " + has;
                    }
                }
                case "assert-current-schema-id" -> {
                    if (r.path("current-schema-id").asInt() != base.currentSchemaId()) {
                        return "the schema changed (now " + base.currentSchemaId() + ")";
                    }
                }
                case "assert-last-assigned-field-id" -> {
                    if (r.path("last-assigned-field-id").asInt() != base.lastColumnId()) {
                        return "columns were added (last id now " + base.lastColumnId() + ")";
                    }
                }
                default -> {
                    // a requirement only a catalog checks
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ updates

    public TableUpdate addSchema(Schema s, int lastColumnId) {
        updates.add(F.objectNode().put("action", "add-schema").<ObjectNode>set("schema", SchemaJson.write(s)).put("last-column-id", lastColumnId));
        return this;
    }

    public TableUpdate setCurrentSchema(int schemaId) {
        updates.add(F.objectNode().put("action", "set-current-schema").put("schema-id", schemaId));
        return this;
    }

    public TableUpdate addSnapshot(Snapshot s, int formatVersion) {
        updates.add(F.objectNode().put("action", "add-snapshot").set("snapshot", s.write(formatVersion)));
        return this;
    }

    public TableUpdate setMain(long snapshotId) {
        updates.add(F.objectNode().put("action", "set-snapshot-ref").put("ref-name", "main").put("type", "branch").put("snapshot-id", snapshotId));
        return this;
    }

    public TableUpdate removeSnapshots(Set<Long> ids) {
        ObjectNode u = F.objectNode().put("action", "remove-snapshots");
        ArrayNode a = u.putArray("snapshot-ids");
        ids.forEach(a::add);
        updates.add(u);
        return this;
    }

    public TableUpdate setProperties(Map<String, String> props) {
        ObjectNode u = F.objectNode().put("action", "set-properties");
        ObjectNode m = u.putObject("updates");
        props.forEach(m::put);
        updates.add(u);
        return this;
    }

    public TableUpdate removeProperties(Set<String> keys) {
        ObjectNode u = F.objectNode().put("action", "remove-properties");
        ArrayNode a = u.putArray("removals");
        keys.forEach(a::add);
        updates.add(u);
        return this;
    }

    /** The REST {@code commitTable} request body. */
    public ObjectNode restRequest(List<String> namespace, String table) {
        ObjectNode body = F.objectNode();
        ObjectNode id = body.putObject("identifier");
        ArrayNode ns = id.putArray("namespace");
        namespace.forEach(ns::add);
        id.put("name", table);
        ArrayNode req = body.putArray("requirements");
        requirements.forEach(req::add);
        ArrayNode up = body.putArray("updates");
        updates.forEach(up::add);
        return body;
    }

    /**
     * The next metadata: {@code base}'s JSON with the updates applied, {@code last-updated-ms} set and, for a
     * path-based table, the previous metadata file appended to {@code metadata-log} (at most
     * {@code write.metadata.previous-versions-max}, 100, kept).
     *
     * @param previousFile the file {@code base} was read from (null: not logged)
     */
    public TableMetadata apply(TableMetadata base, String previousFile, long nowMs) {
        ObjectNode j = base.jsonCopy();
        int fv = base.formatVersion();
        for (ObjectNode u : updates) {
            switch (u.path("action").asText()) {
                case "add-schema" -> {
                    TableMetadata.array(j, "schemas").add(u.get("schema"));
                    j.put("last-column-id", Math.max(j.path("last-column-id").asInt(), u.path("last-column-id").asInt()));
                }
                case "set-current-schema" -> {
                    int id = u.path("schema-id").asInt();
                    if (id == -1) {
                        ArrayNode schemas = TableMetadata.array(j, "schemas");
                        id = schemas.get(schemas.size() - 1).path("schema-id").asInt();
                    }
                    j.put("current-schema-id", id);
                    if (fv == 1) {
                        for (JsonNode s : j.path("schemas")) {
                            if (s.path("schema-id").asInt() == id) {
                                j.set("schema", s.deepCopy());
                            }
                        }
                    }
                }
                case "add-snapshot" -> {
                    JsonNode s = u.get("snapshot");
                    TableMetadata.array(j, "snapshots").add(s);
                    if (fv >= 2) {
                        j.put("last-sequence-number", Math.max(j.path("last-sequence-number").asLong(), s.path("sequence-number").asLong()));
                    }
                }
                case "set-snapshot-ref" -> {
                    long id = u.path("snapshot-id").asLong();
                    ObjectNode refs = j.has("refs") && j.get("refs").isObject() ? (ObjectNode) j.get("refs") : j.putObject("refs");
                    refs.putObject(u.path("ref-name").asText()).put("snapshot-id", id).put("type", "branch");
                    if ("main".equals(u.path("ref-name").asText())) {
                        j.put("current-snapshot-id", id);
                        long ts = nowMs;
                        for (JsonNode s : j.path("snapshots")) {
                            if (s.path("snapshot-id").asLong() == id) {
                                ts = s.path("timestamp-ms").asLong();
                            }
                        }
                        TableMetadata.array(j, "snapshot-log").addObject().put("timestamp-ms", ts).put("snapshot-id", id);
                    }
                }
                case "remove-snapshots" -> {
                    Set<Long> gone = new HashSet<>();
                    u.path("snapshot-ids").forEach(x -> gone.add(x.asLong()));
                    ArrayNode keep = F.arrayNode();
                    j.path("snapshots").forEach(s -> {
                        if (!gone.contains(s.path("snapshot-id").asLong())) {
                            keep.add(s);
                        }
                    });
                    j.set("snapshots", keep);
                    // the log stays a contiguous history: entries up to the last removed snapshot go with it
                    ArrayNode log = F.arrayNode();
                    for (JsonNode e : j.path("snapshot-log")) {
                        if (gone.contains(e.path("snapshot-id").asLong())) {
                            log.removeAll();
                        } else {
                            log.add(e);
                        }
                    }
                    j.set("snapshot-log", log);
                }
                case "set-properties" -> {
                    ObjectNode props = j.has("properties") && j.get("properties").isObject() ? (ObjectNode) j.get("properties") : j.putObject("properties");
                    u.path("updates").fields().forEachRemaining(e -> props.set(e.getKey(), e.getValue()));
                }
                case "remove-properties" -> {
                    if (j.get("properties") instanceof ObjectNode props) {
                        u.path("removals").forEach(k -> props.remove(k.asText()));
                    }
                }
                default -> throw new IllegalArgumentException("unknown update " + u);
            }
        }
        j.put("last-updated-ms", nowMs);
        if (previousFile != null) {
            ArrayNode log = TableMetadata.array(j, "metadata-log");
            log.addObject().put("timestamp-ms", base.lastUpdatedMs()).put("metadata-file", previousFile);
            int max = Integer.parseInt(base.property("write.metadata.previous-versions-max", "100"));
            while (log.size() > max) {
                log.remove(0);
            }
        }
        return new TableMetadata(j);
    }
}
