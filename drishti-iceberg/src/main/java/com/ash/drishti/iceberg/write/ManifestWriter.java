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
package com.ash.drishti.iceberg.write;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.manifest.ManifestEntry;
import com.ash.drishti.iceberg.manifest.ManifestFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SchemaJson;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;

/**
 * Writes manifests and manifest lists (Avro, deflate-compressed as Iceberg's {@code write.avro.compression-codec}
 * default), versions 1 and 2, with the header metadata readers look for ({@code schema}, {@code partition-spec},
 * {@code format-version}, {@code content}; a list's {@code snapshot-id}, {@code parent-snapshot-id},
 * {@code sequence-number}). An entry whose sequence numbers are to be inherited from its manifest (a file added by this
 * commit) carries a negative sequence number and is written without one. Stateless.
 */
public final class ManifestWriter {

    private ManifestWriter() {}

    /**
     * Writes a manifest of {@code entries} (all of one spec and one content) to {@code path} and describes it as the
     * manifest list will.
     */
    public static ManifestFile write(FileAccess io, String path, TableMetadata table, Schema schema, PartitionSpec spec, int content,
            long snapshotId, long sequenceNumber, List<ManifestEntry> entries) throws IOException {
        int fv = table.formatVersion();
        Type.Struct partType = spec.partitionType(schema);
        org.apache.avro.Schema entrySchema = AvroSchemas.manifestEntry(partType, fv);
        org.apache.avro.Schema fileSchema = entrySchema.getField("data_file").schema();
        org.apache.avro.Schema partSchema = fileSchema.getField("partition").schema();
        Summary[] summaries = new Summary[partType.fields().size()];
        for (int i = 0; i < summaries.length; i++) {
            summaries[i] = new Summary((Type.Primitive) partType.fields().get(i).type());
        }
        int added = 0;
        int existing = 0;
        int deleted = 0;
        long addedRows = 0;
        long existingRows = 0;
        long deletedRows = 0;
        long minSeq = Long.MAX_VALUE;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataFileWriter<GenericRecord> w = new DataFileWriter<>(new GenericDatumWriter<GenericRecord>(entrySchema))) {
            w.setCodec(CodecFactory.deflateCodec(6));
            w.setMeta("schema", SchemaJson.write(schema).toString());
            w.setMeta("schema-id", String.valueOf(schema.schemaId()));
            w.setMeta("partition-spec", spec.write().get("fields").toString());
            w.setMeta("partition-spec-id", String.valueOf(spec.specId()));
            w.setMeta("format-version", String.valueOf(fv));
            if (fv >= 2) {
                w.setMeta("content", content == ManifestFile.DELETES ? "deletes" : "data");
            }
            w.create(entrySchema, bytes);
            for (ManifestEntry e : entries) {
                DataFile f = e.file();
                GenericRecord r = new GenericData.Record(entrySchema);
                r.put("status", e.status());
                r.put("snapshot_id", e.snapshotId());
                if (fv >= 2) {
                    r.put("sequence_number", e.sequenceNumber() < 0 ? null : e.sequenceNumber());
                    r.put("file_sequence_number", e.fileSequenceNumber() == null || e.fileSequenceNumber() < 0 ? null : e.fileSequenceNumber());
                }
                GenericRecord d = new GenericData.Record(fileSchema);
                if (fv >= 2) {
                    d.put("content", f.content());
                }
                d.put("file_path", f.path());
                d.put("file_format", f.format());
                GenericRecord p = new GenericData.Record(partSchema);
                for (int i = 0; i < partType.fields().size(); i++) {
                    Object v = i < f.partition().length ? f.partition()[i] : null;
                    summaries[i].add(v);
                    p.put(i, avro((Type.Primitive) partType.fields().get(i).type(), v, partSchema.getFields().get(i).schema()));
                }
                d.put("partition", p);
                d.put("record_count", f.recordCount());
                d.put("file_size_in_bytes", f.fileSizeInBytes());
                if (fv == 1) {
                    d.put("block_size_in_bytes", 64L * 1024 * 1024);
                }
                d.put("column_sizes", pairs(fileSchema, "column_sizes", f.columnSizes()));
                d.put("value_counts", pairs(fileSchema, "value_counts", f.valueCounts()));
                d.put("null_value_counts", pairs(fileSchema, "null_value_counts", f.nullValueCounts()));
                d.put("nan_value_counts", pairs(fileSchema, "nan_value_counts", f.nanValueCounts()));
                d.put("lower_bounds", pairs(fileSchema, "lower_bounds", f.lowerBounds()));
                d.put("upper_bounds", pairs(fileSchema, "upper_bounds", f.upperBounds()));
                d.put("key_metadata", f.keyMetadata() == null ? null : ByteBuffer.wrap(f.keyMetadata()));
                d.put("split_offsets", f.splitOffsets() == null || f.splitOffsets().isEmpty() ? null : new ArrayList<>(f.splitOffsets()));
                if (fv >= 2) {
                    d.put("equality_ids", f.equalityIds() == null || f.equalityIds().isEmpty() ? null : new ArrayList<>(f.equalityIds()));
                }
                d.put("sort_order_id", f.sortOrderId());
                r.put("data_file", d);
                w.append(r);
                switch (e.status()) {
                    case ManifestEntry.ADDED -> {
                        added++;
                        addedRows += f.recordCount();
                    }
                    case ManifestEntry.EXISTING -> {
                        existing++;
                        existingRows += f.recordCount();
                    }
                    default -> {
                        deleted++;
                        deletedRows += f.recordCount();
                    }
                }
                if (e.status() != ManifestEntry.DELETED) {
                    minSeq = Math.min(minSeq, e.sequenceNumber() < 0 ? sequenceNumber : e.sequenceNumber());
                }
            }
        }
        byte[] out = bytes.toByteArray();
        String resolved = io.resolve(path);
        io.storage(resolved).put(resolved, out);
        List<ManifestFile.FieldSummary> parts = new ArrayList<>();
        for (Summary s : summaries) {
            parts.add(s.summary());
        }
        return new ManifestFile(path, out.length, spec.specId(), content, sequenceNumber, minSeq == Long.MAX_VALUE ? sequenceNumber : minSeq, snapshotId,
                added, existing, deleted, addedRows, existingRows, deletedRows, parts, null);
    }

    /** Writes a snapshot's manifest list. */
    public static long writeList(FileAccess io, String path, int formatVersion, long snapshotId, Long parentId, long sequenceNumber,
            List<ManifestFile> manifests) throws IOException {
        org.apache.avro.Schema s = AvroSchemas.manifestFile(formatVersion);
        org.apache.avro.Schema summarySchema = s.getField("partitions").schema().getTypes().get(1).getElementType();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataFileWriter<GenericRecord> w = new DataFileWriter<>(new GenericDatumWriter<GenericRecord>(s))) {
            w.setCodec(CodecFactory.deflateCodec(6));
            w.setMeta("snapshot-id", String.valueOf(snapshotId));
            w.setMeta("parent-snapshot-id", parentId == null ? "null" : String.valueOf(parentId));
            w.setMeta("sequence-number", String.valueOf(sequenceNumber));
            w.setMeta("format-version", String.valueOf(formatVersion));
            w.create(s, bytes);
            boolean v2 = formatVersion >= 2;
            for (ManifestFile m : manifests) {
                GenericRecord r = new GenericData.Record(s);
                r.put("manifest_path", m.path());
                r.put("manifest_length", m.length());
                r.put("partition_spec_id", m.specId());
                if (v2) {
                    r.put("content", m.content());
                    r.put("sequence_number", m.sequenceNumber());
                    r.put("min_sequence_number", m.minSequenceNumber());
                }
                r.put("added_snapshot_id", m.addedSnapshotId() == null ? (v2 ? snapshotId : null) : m.addedSnapshotId());
                r.put(v2 ? "added_files_count" : "added_data_files_count", or(m.addedFilesCount(), v2));
                r.put(v2 ? "existing_files_count" : "existing_data_files_count", or(m.existingFilesCount(), v2));
                r.put(v2 ? "deleted_files_count" : "deleted_data_files_count", or(m.deletedFilesCount(), v2));
                r.put("added_rows_count", orLong(m.addedRowsCount(), v2));
                r.put("existing_rows_count", orLong(m.existingRowsCount(), v2));
                r.put("deleted_rows_count", orLong(m.deletedRowsCount(), v2));
                List<GenericRecord> parts = new ArrayList<>();
                for (ManifestFile.FieldSummary f : m.partitions()) {
                    GenericRecord p = new GenericData.Record(summarySchema);
                    p.put("contains_null", f.containsNull());
                    p.put("contains_nan", f.containsNan());
                    p.put("lower_bound", f.lowerBound() == null ? null : ByteBuffer.wrap(f.lowerBound()));
                    p.put("upper_bound", f.upperBound() == null ? null : ByteBuffer.wrap(f.upperBound()));
                    parts.add(p);
                }
                r.put("partitions", parts);
                r.put("key_metadata", null);
                w.append(r);
            }
        }
        byte[] out = bytes.toByteArray();
        String resolved = io.resolve(path);
        io.storage(resolved).put(resolved, out);
        return out.length;
    }

    private static Integer or(Integer v, boolean v2) {
        return v == null ? (v2 ? 0 : null) : v;
    }

    private static Long orLong(Long v, boolean v2) {
        return v == null ? (v2 ? 0L : null) : v;
    }

    /** An Iceberg map as Avro's array of {@code k<id>_v<id>} records; null when empty. */
    private static <V> List<GenericRecord> pairs(org.apache.avro.Schema fileSchema, String field, Map<Integer, V> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        org.apache.avro.Schema rec = fileSchema.getField(field).schema().getTypes().get(1).getElementType();
        List<GenericRecord> out = new ArrayList<>();
        new java.util.TreeMap<>(m).forEach((k, v) -> {
            GenericRecord r = new GenericData.Record(rec);
            r.put("key", k);
            r.put("value", v instanceof byte[] b ? ByteBuffer.wrap(b) : v);
            out.add(r);
        });
        return out;
    }

    /** A partition value in its Avro form. */
    static Object avro(Type.Primitive t, Object v, org.apache.avro.Schema union) {
        if (v == null) {
            return null;
        }
        org.apache.avro.Schema s = union.getTypes().get(1);
        return switch (t.id()) {
            case DECIMAL -> new GenericData.Fixed(s, fixed(((BigDecimal) v).setScale(t.scale(), RoundingMode.UNNECESSARY).unscaledValue().toByteArray(),
                    s.getFixedSize()));
            case UUID -> {
                UUID u = (UUID) v;
                yield new GenericData.Fixed(s, ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
            }
            case FIXED -> new GenericData.Fixed(s, (byte[]) v);
            case BINARY -> ByteBuffer.wrap((byte[]) v);
            case LONG, TIME, TIMESTAMP, TIMESTAMPTZ -> ((Number) v).longValue();
            case INT, DATE -> ((Number) v).intValue();
            default -> v;
        };
    }

    private static byte[] fixed(byte[] b, int n) {
        if (b.length == n) {
            return b;
        }
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, 0, n - b.length, (byte) (b.length > 0 && b[0] < 0 ? 0xff : 0));
        System.arraycopy(b, 0, out, n - b.length, b.length);
        return out;
    }

    /** One partition field's values across a manifest. */
    private static final class Summary {
        private final Type.Primitive type;
        private boolean nulls;
        private boolean nans;
        private Object lo;
        private Object hi;

        Summary(Type.Primitive type) {
            this.type = type;
        }

        void add(Object v) {
            if (v == null) {
                nulls = true;
                return;
            }
            if (v instanceof Double d && d.isNaN() || v instanceof Float f && f.isNaN()) {
                nans = true;
                return;
            }
            lo = lo == null || Values.compare(type, v, lo) < 0 ? v : lo;
            hi = hi == null || Values.compare(type, v, hi) > 0 ? v : hi;
        }

        ManifestFile.FieldSummary summary() {
            boolean floating = type.id() == Type.Id.FLOAT || type.id() == Type.Id.DOUBLE;
            return new ManifestFile.FieldSummary(nulls, floating ? nans : null, lo == null ? null : Values.toBytes(type, lo),
                    hi == null ? null : Values.toBytes(type, hi));
        }
    }

    /** The schema's field of an id, for messages. */
    static String name(Schema s, int id) {
        NestedField f = s.field(id);
        return f == null ? String.valueOf(id) : f.name();
    }
}
