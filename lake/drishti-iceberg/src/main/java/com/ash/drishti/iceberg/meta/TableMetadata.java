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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.mapping.NameMappingParser;

/** A table version as Drishti's readers and writers see it, read from Apache Iceberg's own {@link org.apache.iceberg.TableMetadata}. Immutable. */
public final class TableMetadata {

    /** One entry of the snapshot log or the metadata log. */
    public record LogEntry(long timestampMs, long snapshotId, String metadataFile) {}

    private final org.apache.iceberg.TableMetadata md;
    private final List<Schema> schemas;
    private final Schema schema;
    private final List<PartitionSpec> specs;
    private final List<Snapshot> snapshots;

    public TableMetadata(org.apache.iceberg.TableMetadata md) {
        this.md = md;
        this.schemas = md.schemas().stream().map(Bridge::schema).toList();
        this.schema = Bridge.schema(md.schema());
        this.specs = md.specs().stream().map(Bridge::spec).toList();
        this.snapshots = md.snapshots().stream().map(Bridge::snapshot).toList();
    }

    public org.apache.iceberg.TableMetadata iceberg() {
        return md;
    }

    public int formatVersion() {
        return md.formatVersion();
    }

    public String uuid() {
        return md.uuid();
    }

    public String location() {
        return md.location();
    }

    public long lastSequenceNumber() {
        return md.lastSequenceNumber();
    }

    public long lastUpdatedMs() {
        return md.lastUpdatedMillis();
    }

    public int lastColumnId() {
        return md.lastColumnId();
    }

    public List<Schema> schemas() {
        return schemas;
    }

    public int currentSchemaId() {
        return md.currentSchemaId();
    }

    public Schema schema() {
        return schema;
    }

    public Optional<Schema> schema(int id) {
        return schemas.stream().filter(s -> s.schemaId() == id).findFirst();
    }

    public List<PartitionSpec> specs() {
        return specs;
    }

    public int defaultSpecId() {
        return md.defaultSpecId();
    }

    public PartitionSpec spec() {
        return spec(md.defaultSpecId());
    }

    public PartitionSpec spec(int id) {
        return specs.stream().filter(s -> s.specId() == id).findFirst().orElseThrow(() -> new IllegalArgumentException("no partition spec " + id));
    }

    public SortOrder sortOrder() {
        return Bridge.order(md.sortOrder());
    }

    public Map<String, String> properties() {
        return md.properties();
    }

    public String property(String key, String fallback) {
        return md.properties().getOrDefault(key, fallback);
    }

    public List<Snapshot> snapshots() {
        return snapshots;
    }

    public Optional<Snapshot> currentSnapshot() {
        return Optional.ofNullable(md.currentSnapshot()).map(Bridge::snapshot);
    }

    public Optional<Snapshot> snapshot(long id) {
        return Optional.ofNullable(md.snapshot(id)).map(Bridge::snapshot);
    }

    public List<LogEntry> snapshotLog() {
        List<LogEntry> out = new ArrayList<>();
        md.snapshotLog().forEach(e -> out.add(new LogEntry(e.timestampMillis(), e.snapshotId(), null)));
        return out;
    }

    public List<LogEntry> metadataLog() {
        List<LogEntry> out = new ArrayList<>();
        md.previousFiles().forEach(e -> out.add(new LogEntry(e.timestampMillis(), 0, e.file())));
        return out;
    }

    /** The main branch and every other reference, by name. */
    public Map<String, Long> allRefs() {
        Map<String, Long> out = new java.util.HashMap<>();
        md.refs().forEach((k, v) -> out.put(k, v.snapshotId()));
        return out;
    }

    /** The files of table and partition statistics. */
    public List<String> statisticsFiles() {
        List<String> out = new ArrayList<>(md.statisticsFiles().stream().map(StatisticsFile::path).toList());
        md.partitionStatisticsFiles().forEach(f -> out.add(f.path()));
        return out;
    }

    /** The snapshot that was current at {@code timestampMs} (time travel by <em>known at</em>); empty before the first commit or when it was expired. */
    public Optional<Snapshot> snapshotAsOf(long timestampMs) {
        Long found = null;
        for (org.apache.iceberg.HistoryEntry e : md.snapshotLog()) {
            if (e.timestampMillis() <= timestampMs) {
                found = e.snapshotId();
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
        String m = md.properties().get("schema.name-mapping.default");
        return m == null ? Optional.empty() : Optional.of(new NameMapping(NameMappingParser.fromJson(m)));
    }

    @Override
    public String toString() {
        return "table at " + md.location();
    }
}
