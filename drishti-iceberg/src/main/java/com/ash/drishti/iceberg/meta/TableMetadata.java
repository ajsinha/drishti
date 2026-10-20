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
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A table's metadata file ({@code vN.metadata.json}), format versions 1 and 2, read whole and kept as it was written:
 * a change ({@link TableUpdate}) edits a copy of the JSON, so every field this engine does not know (a newer writer's,
 * a catalog's own) is <em>kept</em> in the next version, not dropped. The parsed views (schemas, specs, sort orders,
 * snapshots, logs, properties) are built once. Immutable and thread-safe.
 *
 * <p>Refused by name: format version 3, and encrypted tables ({@code encryption-keys}).
 */
public final class TableMetadata {

    static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);

    /** One snapshot-log or metadata-log entry. */
    public record LogEntry(long timestampMs, long snapshotId, String metadataFile) {}

    private final ObjectNode json;
    private final int formatVersion;
    private final List<Schema> schemas = new ArrayList<>();
    private final List<PartitionSpec> specs = new ArrayList<>();
    private final List<SortOrder> sortOrders = new ArrayList<>();
    private final List<Snapshot> snapshots = new ArrayList<>();
    private final List<LogEntry> snapshotLog = new ArrayList<>();
    private final List<LogEntry> metadataLog = new ArrayList<>();
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Map<String, Long> refs = new LinkedHashMap<>();

    public TableMetadata(ObjectNode json) {
        this.json = json;
        this.formatVersion = json.path("format-version").asInt(1);
        if (formatVersion > 2) {
            throw new UnsupportedFeature("format version " + formatVersion + " tables");
        }
        if (json.path("encryption-keys").size() > 0) {
            throw new UnsupportedFeature("encrypted tables (encryption-keys)");
        }
        if (json.has("schemas")) {
            json.get("schemas").forEach(s -> schemas.add(SchemaJson.read(s)));
        } else if (json.has("schema")) {
            schemas.add(SchemaJson.read(json.get("schema")));                     // v1 written before schemas
        }
        if (json.has("partition-specs")) {
            json.get("partition-specs").forEach(s -> specs.add(PartitionSpec.read(s)));
        } else if (json.has("partition-spec")) {
            specs.add(PartitionSpec.read(json.get("partition-spec")));
        }
        if (specs.isEmpty()) {
            specs.add(PartitionSpec.unpartitioned(0));
        }
        json.path("sort-orders").forEach(s -> sortOrders.add(SortOrder.read(s)));
        json.path("snapshots").forEach(s -> snapshots.add(Snapshot.read(s)));
        json.path("snapshot-log").forEach(e -> snapshotLog.add(new LogEntry(e.path("timestamp-ms").asLong(), e.path("snapshot-id").asLong(), null)));
        json.path("metadata-log").forEach(e -> metadataLog.add(new LogEntry(e.path("timestamp-ms").asLong(), 0, e.path("metadata-file").asText())));
        json.path("properties").fields().forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
        json.path("refs").fields().forEachRemaining(e -> {
            if ("branch".equals(e.getValue().path("type").asText("branch")) || e.getKey().equals("main")) {
                refs.put(e.getKey(), e.getValue().path("snapshot-id").asLong());
            }
        });
    }

    /** Reads a metadata file's bytes (gzip-compressed ones, {@code .gz.metadata.json}, included). */
    public static TableMetadata read(byte[] bytes) {
        try {
            InputStream in = new ByteArrayInputStream(bytes);
            if (bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
                in = new GZIPInputStream(in);
            }
            JsonNode n = JSON.readTree(in);
            if (!(n instanceof ObjectNode o)) {
                throw new IOException("table metadata is not a JSON object");
            }
            return new TableMetadata(o);
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable table metadata", e);
        }
    }

    /** The metadata file's bytes (gzip-compressed when {@code gzip}, for {@code write.metadata.compression-codec: gzip}). */
    public byte[] toBytes(boolean gzip) {
        try {
            byte[] plain = JSON.writeValueAsBytes(json);
            if (!gzip) {
                return plain;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(out)) {
                z.write(plain);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A copy of the JSON (for an update to edit). */
    public ObjectNode jsonCopy() {
        return json.deepCopy();
    }

    /** The JSON as it is (read only). */
    public JsonNode json() {
        return json;
    }

    public int formatVersion() {
        return formatVersion;
    }

    public String uuid() {
        return json.path("table-uuid").asText(null);
    }

    public String location() {
        return json.path("location").asText();
    }

    public long lastSequenceNumber() {
        return json.path("last-sequence-number").asLong(0);
    }

    public long lastUpdatedMs() {
        return json.path("last-updated-ms").asLong();
    }

    public int lastColumnId() {
        return json.path("last-column-id").asInt();
    }

    public int lastPartitionId() {
        if (json.has("last-partition-id")) {
            return json.get("last-partition-id").asInt();
        }
        return specs.stream().mapToInt(PartitionSpec::lastFieldId).max().orElse(999);
    }

    public List<Schema> schemas() {
        return Collections.unmodifiableList(schemas);
    }

    public int currentSchemaId() {
        if (json.has("current-schema-id")) {
            return json.get("current-schema-id").asInt();
        }
        return schemas.isEmpty() ? 0 : schemas.get(schemas.size() - 1).schemaId();
    }

    public Schema schema() {
        return schema(currentSchemaId()).orElseThrow(() -> new IllegalStateException("no current schema " + currentSchemaId()));
    }

    public Optional<Schema> schema(int id) {
        return schemas.stream().filter(s -> s.schemaId() == id).findFirst();
    }

    public List<PartitionSpec> specs() {
        return Collections.unmodifiableList(specs);
    }

    public int defaultSpecId() {
        return json.has("default-spec-id") ? json.get("default-spec-id").asInt() : specs.get(specs.size() - 1).specId();
    }

    public PartitionSpec spec() {
        return spec(defaultSpecId());
    }

    public PartitionSpec spec(int id) {
        return specs.stream().filter(s -> s.specId() == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("table " + location() + " has no partition spec " + id));
    }

    public List<SortOrder> sortOrders() {
        return Collections.unmodifiableList(sortOrders);
    }

    public SortOrder sortOrder() {
        int id = json.path("default-sort-order-id").asInt(0);
        return sortOrders.stream().filter(s -> s.orderId() == id).findFirst().orElse(SortOrder.UNSORTED);
    }

    public Map<String, String> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public String property(String key, String fallback) {
        return properties.getOrDefault(key, fallback);
    }

    public List<Snapshot> snapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    /** The current snapshot (the {@code main} branch's), or empty for a table with no commit yet. */
    public Optional<Snapshot> currentSnapshot() {
        Long main = refs.get("main");
        long id = main != null ? main : json.hasNonNull("current-snapshot-id") ? json.get("current-snapshot-id").asLong() : -1;
        return id < 0 ? Optional.empty() : snapshot(id);
    }

    public Optional<Snapshot> snapshot(long id) {
        return snapshots.stream().filter(s -> s.snapshotId() == id).findFirst();
    }

    public List<LogEntry> snapshotLog() {
        return Collections.unmodifiableList(snapshotLog);
    }

    public List<LogEntry> metadataLog() {
        return Collections.unmodifiableList(metadataLog);
    }

    /** The branches and their snapshots ({@code main} first when present). */
    public Map<String, Long> refs() {
        return Collections.unmodifiableMap(refs);
    }

    /**
     * The snapshot that was current at {@code timestampMs}, from the snapshot log (time travel by <em>known at</em>):
     * the last entry at or before it whose snapshot still exists; empty before the first commit or when those
     * snapshots were expired.
     */
    public Optional<Snapshot> snapshotAsOf(long timestampMs) {
        Long found = null;
        for (LogEntry e : snapshotLog) {
            if (e.timestampMs() <= timestampMs) {
                found = e.snapshotId();
            }
        }
        if (found == null && snapshotLog.isEmpty()) {
            for (Snapshot s : snapshots) {                       // no log (a hand-made table): by commit time
                if (s.timestampMs() <= timestampMs && (found == null || snapshot(found).orElseThrow().timestampMs() <= s.timestampMs())) {
                    found = s.snapshotId();
                }
            }
        }
        return found == null ? Optional.empty() : snapshot(found);
    }

    /** The schema of a snapshot: the one it names, else (older writers) the current one. */
    public Schema schemaFor(Snapshot s) {
        return s.schemaId() == null ? schema() : schema(s.schemaId()).orElse(schema());
    }

    /** {@code schema.name-mapping.default}: names to field ids for data files written without ids. */
    public Optional<NameMapping> nameMapping() {
        String m = properties.get("schema.name-mapping.default");
        return m == null ? Optional.empty() : Optional.of(NameMapping.parse(m));
    }

    /**
     * The first metadata of a new table: no snapshot, the given schema, spec and sort order (their ids and field ids
     * already assigned), {@code last-column-id} and {@code last-partition-id} from them. A v1 table also carries the
     * single {@code schema} and {@code partition-spec} older readers look for.
     */
    public static TableMetadata create(String location, int formatVersion, Schema schema, PartitionSpec spec, SortOrder order,
            Map<String, String> properties, long nowMs) {
        ObjectNode j = JSON.createObjectNode();
        j.put("format-version", formatVersion);
        j.put("table-uuid", java.util.UUID.randomUUID().toString());
        j.put("location", location);
        if (formatVersion >= 2) {
            j.put("last-sequence-number", 0);
        }
        j.put("last-updated-ms", nowMs);
        j.put("last-column-id", schema.highestFieldId());
        j.put("current-schema-id", schema.schemaId());
        j.putArray("schemas").add(SchemaJson.write(schema));
        j.put("default-spec-id", spec.specId());
        j.putArray("partition-specs").add(spec.write());
        j.put("last-partition-id", spec.lastFieldId());
        j.put("default-sort-order-id", order.orderId());
        j.putArray("sort-orders").add(order.write());
        ObjectNode props = j.putObject("properties");
        properties.forEach(props::put);
        j.put("current-snapshot-id", -1);
        j.putObject("refs");
        j.putArray("snapshots");
        j.putArray("statistics");
        j.putArray("snapshot-log");
        j.putArray("metadata-log");
        if (formatVersion == 1) {
            j.set("schema", SchemaJson.write(schema));
            j.set("partition-spec", spec.write().get("fields"));
        }
        return new TableMetadata(j);
    }

    static ArrayNode array(ObjectNode o, String field) {
        JsonNode n = o.get(field);
        return n instanceof ArrayNode a ? a : o.putArray(field);
    }

    @Override
    public String toString() {
        return "table " + location() + " v" + formatVersion + " snapshot " + currentSnapshot().map(Snapshot::snapshotId).orElse(-1L);
    }
}
