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
package com.ash.drishti.iceberg.manifest;

import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SchemaJson;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.SeekableByteArrayInput;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;

/**
 * Reads manifest lists and manifests, the Avro files of the spec's "Manifests" section, with Avro's own reader (no
 * Hadoop), versions 1 and 2. Fields are found by name in the writer's schema, so files of either version, and of any
 * writer (Java, Python, Rust, Spark, Snowflake), are read alike; inherited snapshot ids and sequence numbers are filled
 * in. Stateless.
 */
public final class ManifestReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ManifestReader() {}

    /** A snapshot's manifest list. */
    public static List<ManifestFile> readList(byte[] bytes, String where) throws IOException {
        List<ManifestFile> out = new ArrayList<>();
        for (GenericRecord r : records(bytes, where)) {
            List<ManifestFile.FieldSummary> parts = new ArrayList<>();
            Object ps = r.get("partitions");
            if (ps instanceof Collection<?> c) {
                for (Object o : c) {
                    GenericRecord p = (GenericRecord) o;
                    parts.add(new ManifestFile.FieldSummary(Boolean.TRUE.equals(p.get("contains_null")), (Boolean) field(p, "contains_nan"),
                            bytes(p.get("lower_bound")), bytes(p.get("upper_bound"))));
                }
            }
            int content = intOr(field(r, "content"), ManifestFile.DATA);
            long seq = longOr(field(r, "sequence_number"), 0);
            out.add(new ManifestFile(text(r.get("manifest_path")), longOr(r.get("manifest_length"), -1), intOr(r.get("partition_spec_id"), 0),
                    content, seq, longOr(field(r, "min_sequence_number"), seq), (Long) field(r, "added_snapshot_id"),
                    integer(first(r, "added_files_count", "added_data_files_count")), integer(first(r, "existing_files_count", "existing_data_files_count")),
                    integer(first(r, "deleted_files_count", "deleted_data_files_count")), (Long) field(r, "added_rows_count"),
                    (Long) field(r, "existing_rows_count"), (Long) field(r, "deleted_rows_count"), parts, bytes(field(r, "key_metadata"))));
        }
        return out;
    }

    /**
     * One manifest's entries, with inheritance applied: an entry with no snapshot id takes the manifest's
     * {@code added_snapshot_id}; an added entry with no sequence number takes the manifest's (v1 files have 0).
     *
     * @param table the table's metadata: the partition spec and the column types of the partition values
     */
    public static List<ManifestEntry> read(byte[] bytes, ManifestFile manifest, TableMetadata table) throws IOException {
        try (DataFileReader<GenericRecord> reader = open(bytes, manifest.path())) {
            Schema schema = table.schema();
            String schemaJson = reader.getMetaString("schema");
            if (schemaJson != null) {
                try {
                    schema = SchemaJson.read(JSON.readTree(schemaJson));        // the schema the manifest was written with
                } catch (RuntimeException e) {
                    // an unreadable or newer schema: the table's current one resolves the partition columns
                }
            }
            int specId = manifest.specId();
            if (specId < 0) {                                    // a v1 manifest named in the metadata itself: its header says
                String h = reader.getMetaString("partition-spec-id");
                specId = h == null ? table.defaultSpecId() : Integer.parseInt(h);
            }
            PartitionSpec spec = table.spec(specId);
            Type.Struct partType = spec.partitionType(schema);
            List<ManifestEntry> out = new ArrayList<>();
            for (GenericRecord e : reader) {
                int status = intOr(e.get("status"), ManifestEntry.EXISTING);
                Long snapshot = (Long) e.get("snapshot_id");
                Long seq = (Long) field(e, "sequence_number");
                Long fileSeq = (Long) field(e, "file_sequence_number");
                if (seq == null && (status == ManifestEntry.ADDED || table.formatVersion() == 1)) {
                    seq = manifest.sequenceNumber();
                }
                if (fileSeq == null && status == ManifestEntry.ADDED) {
                    fileSeq = manifest.sequenceNumber();
                }
                long sid = snapshot != null ? snapshot : manifest.addedSnapshotId() == null ? 0 : manifest.addedSnapshotId();
                out.add(new ManifestEntry(status, sid, seq == null ? 0 : seq, fileSeq, dataFile((GenericRecord) e.get("data_file"), manifest, specId, partType)));
            }
            return out;
        }
    }

    private static DataFile dataFile(GenericRecord d, ManifestFile manifest, int specId, Type.Struct partType) {
        GenericRecord p = (GenericRecord) d.get("partition");
        Object[] values = new Object[partType.fields().size()];
        for (int i = 0; i < values.length && p != null && i < p.getSchema().getFields().size(); i++) {
            NestedField f = partType.fields().get(i);
            values[i] = value(f.type() instanceof Type.Primitive t ? t : null, p.get(i), p.getSchema().getFields().get(i).schema());
        }
        int content = intOr(field(d, "content"), manifest.content() == ManifestFile.DELETES ? DataFile.POSITION_DELETES : DataFile.DATA);
        List<Long> splits = new ArrayList<>();
        if (field(d, "split_offsets") instanceof Collection<?> c) {
            c.forEach(o -> splits.add((Long) o));
        }
        List<Integer> eq = new ArrayList<>();
        if (field(d, "equality_ids") instanceof Collection<?> c) {
            c.forEach(o -> eq.add((Integer) o));
        }
        return new DataFile(content, text(d.get("file_path")), text(d.get("file_format")).toUpperCase(java.util.Locale.ROOT), specId, values,
                longOr(d.get("record_count"), 0), longOr(d.get("file_size_in_bytes"), -1), longs(field(d, "column_sizes")),
                longs(field(d, "value_counts")), longs(field(d, "null_value_counts")), longs(field(d, "nan_value_counts")),
                bounds(field(d, "lower_bounds")), bounds(field(d, "upper_bounds")), bytes(field(d, "key_metadata")), splits, eq,
                (Integer) field(d, "sort_order_id"), field(d, "referenced_data_file") == null ? null : text(field(d, "referenced_data_file")));
    }

    /** A partition value from Avro to its internal form: the table's type when known, else what the Avro type says. */
    static Object value(Type.Primitive type, Object v, org.apache.avro.Schema avro) {
        if (v == null) {
            return null;
        }
        org.apache.avro.Schema s = nonNull(avro);
        LogicalType logical = s.getLogicalType();
        if (logical instanceof LogicalTypes.Decimal dec) {
            byte[] b = v instanceof GenericFixed f ? f.bytes() : bytes(v);
            return new BigDecimal(new BigInteger(b), dec.getScale());
        }
        if (type != null && type.id() == Type.Id.UUID || logical != null && "uuid".equals(logical.getName())) {
            if (v instanceof GenericFixed f) {
                ByteBuffer b = ByteBuffer.wrap(f.bytes());
                return new UUID(b.getLong(), b.getLong());
            }
            return UUID.fromString(v.toString());
        }
        if (v instanceof CharSequence cs) {
            return cs.toString();
        }
        if (v instanceof GenericFixed f) {
            return f.bytes().clone();
        }
        if (v instanceof ByteBuffer) {
            return bytes(v);
        }
        if (type != null) {
            if (type.id() == Type.Id.LONG && v instanceof Integer i) {
                return i.longValue();
            }
            if (type.id() == Type.Id.DOUBLE && v instanceof Float f) {
                return f.doubleValue();
            }
        }
        return v;
    }

    private static org.apache.avro.Schema nonNull(org.apache.avro.Schema s) {
        if (s.getType() == org.apache.avro.Schema.Type.UNION) {
            for (org.apache.avro.Schema t : s.getTypes()) {
                if (t.getType() != org.apache.avro.Schema.Type.NULL) {
                    return t;
                }
            }
        }
        return s;
    }

    private static DataFileReader<GenericRecord> open(byte[] bytes, String where) throws IOException {
        try {
            return new DataFileReader<>(new SeekableByteArrayInput(bytes), new GenericDatumReader<>(null, null, GenericData.get()));
        } catch (IOException e) {
            throw new IOException("unreadable Avro file " + where + ": " + e.getMessage(), e);
        }
    }

    private static List<GenericRecord> records(byte[] bytes, String where) throws IOException {
        List<GenericRecord> out = new ArrayList<>();
        try (DataFileReader<GenericRecord> r = open(bytes, where)) {
            for (GenericRecord g : r) {
                out.add(g);
            }
        }
        return out;
    }

    /** A field of the record, or null when the writer's schema has no such field (another version or writer). */
    static Object field(GenericRecord r, String name) {
        return r.getSchema().getField(name) == null ? null : r.get(name);
    }

    private static Object first(GenericRecord r, String a, String b) {
        Object v = field(r, a);
        return v != null ? v : field(r, b);
    }

    static String text(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer integer(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static int intOr(Object o, int fallback) {
        return o == null ? fallback : ((Number) o).intValue();
    }

    private static long longOr(Object o, long fallback) {
        return o == null ? fallback : ((Number) o).longValue();
    }

    static byte[] bytes(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof GenericFixed f) {
            return f.bytes().clone();
        }
        ByteBuffer b = ((ByteBuffer) o).duplicate();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    /** An Iceberg map written as Avro (an array of key/value records, or an Avro map with string keys). */
    private static Map<Integer, Long> longs(Object o) {
        Map<Integer, Long> out = new HashMap<>();
        forEachPair(o, (k, v) -> out.put(k, ((Number) v).longValue()));
        return out;
    }

    private static Map<Integer, byte[]> bounds(Object o) {
        Map<Integer, byte[]> out = new HashMap<>();
        forEachPair(o, (k, v) -> out.put(k, bytes(v)));
        return out;
    }

    private static void forEachPair(Object o, java.util.function.BiConsumer<Integer, Object> sink) {
        if (o instanceof Collection<?> c) {
            for (Object x : c) {
                GenericRecord kv = (GenericRecord) x;
                Object v = kv.get("value");
                if (v != null) {
                    sink.accept(((Number) kv.get("key")).intValue(), v);
                }
            }
        } else if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (v != null) {
                    sink.accept(Integer.parseInt(k.toString()), v);
                }
            });
        }
    }
}
