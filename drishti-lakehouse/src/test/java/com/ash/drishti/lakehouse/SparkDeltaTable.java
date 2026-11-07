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
package com.ash.drishti.lakehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.NanoTime;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

/**
 * A Delta table as Spark (or Databricks) writes it, made in a test without Spark: plain columns (no {@code id}/{@code doc} shape),
 * Parquet without field ids, timestamps as INT96 (Spark's default), a struct and an array, partitioned by {@code business_date}, a Delta
 * log of two commits with per-file statistics, and optionally column mapping by name (physical names {@code col-…}, as Databricks writes
 * with {@code delta.columnMapping.mode = name}) and a deletion vector (a row deleted in place, Databricks' default).
 *
 * <p>Version 0: 2026-09-29, trades T-1, T-2, T-3. Version 1: 2026-09-30, trades T-1, T-2 (a new notional), T-4.
 */
public final class SparkDeltaTable {

    public static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    public static final LocalDate D30 = LocalDate.of(2026, 9, 30);
    public static final ObjectMapper JSON = new ObjectMapper();

    /** One trade row. */
    public record Trade(String id, String desk, double notional, long qty, Instant ts, String cptyId, String cptyName, List<String> tags) {}

    /** A data file as the log lists it. */
    public record AddedFile(String path, Map<String, String> partitionValues, long size, String stats) {}

    /** What was written: the schema, the files of the current version, the table id and version. */
    public record Written(Path dir, String id, String schemaString, List<String> partitionColumns, List<AddedFile> files, long version) {}

    private static final String[] NAMES = {"TRADE_ID", "DESK", "NOTIONAL", "QTY", "TRADE_TS", "CPTY", "TAGS", "business_date"};

    private SparkDeltaTable() {}

    public static List<Trade> day(LocalDate d) {
        Instant t = d.atTime(10, 0).toInstant(ZoneOffset.UTC);
        if (d.equals(D29)) {
            return List.of(new Trade("T-1", "FX", 1_000_000, 10, t, "CP-1", "Acme Bank", List.of("hedge", "fx")),
                    new Trade("T-2", "RATES", 2_500_000, 25, t.plusSeconds(60), "CP-2", "Bravo Capital", List.of()),
                    new Trade("T-3", "FX", 750_000.5, 7, t.plusSeconds(120), "CP-1", "Acme Bank", List.of("spot")));
        }
        return List.of(new Trade("T-1", "FX", 1_000_000, 10, t, "CP-1", "Acme Bank", List.of("hedge", "fx")),
                new Trade("T-2", "RATES", 3_100_000, 31, t.plusSeconds(60), "CP-2", "Bravo Capital", List.of("amended")),
                new Trade("T-4", "CREDIT", 420_000, 4, t.plusSeconds(180), "CP-3", "Charlie Fund", List.of()));
    }

    /** The physical name of a column: its own, or {@code col-<n>} with column mapping. */
    private static String physical(String name, boolean mapping) {
        if (!mapping) {
            return name;
        }
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return "col-" + (i + 1) + "-" + name.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            }
        }
        return "col-" + name.toLowerCase(java.util.Locale.ROOT);
    }

    /** Writes the table at {@code dir}: two commits (two business days). */
    public static Written write(Path dir, boolean columnMapping) throws IOException {
        Files.createDirectories(dir.resolve("_delta_log"));
        String id = UUID.nameUUIDFromBytes(dir.toString().getBytes(StandardCharsets.UTF_8)).toString();
        String schema = schemaString(columnMapping);
        List<AddedFile> all = new ArrayList<>();
        long now = System.currentTimeMillis() - 60_000;
        for (int v = 0; v < 2; v++) {
            LocalDate d = v == 0 ? D29 : D30;
            AddedFile f = parquet(dir, d, day(d), columnMapping);
            all.add(f);
            StringBuilder log = new StringBuilder();
            log.append("{\"commitInfo\":{\"timestamp\":").append(now + v * 1000).append(",\"operation\":\"WRITE\"}}\n");
            if (v == 0) {
                ObjectNode protocol = JSON.createObjectNode();
                protocol.putObject("protocol").put("minReaderVersion", columnMapping ? 2 : 1).put("minWriterVersion", columnMapping ? 5 : 2);
                log.append(protocol).append('\n');
                ObjectNode md = JSON.createObjectNode();
                ObjectNode m = md.putObject("metaData");
                m.put("id", id);
                m.putObject("format").put("provider", "parquet").putObject("options");
                m.put("schemaString", schema);
                m.putArray("partitionColumns").add("business_date");
                ObjectNode conf = m.putObject("configuration");
                if (columnMapping) {
                    conf.put("delta.columnMapping.mode", "name").put("delta.columnMapping.maxColumnId", "12");
                }
                m.put("createdTime", now);
                log.append(md).append('\n');
            }
            ObjectNode add = JSON.createObjectNode();
            ObjectNode a = add.putObject("add");
            a.put("path", f.path());
            ObjectNode pv = a.putObject("partitionValues");
            f.partitionValues().forEach(pv::put);
            a.put("size", f.size()).put("modificationTime", now + v * 1000).put("dataChange", true).put("stats", f.stats());
            log.append(add).append('\n');
            Path commit = dir.resolve("_delta_log").resolve("%020d.json".formatted(v));
            Files.writeString(commit, log.toString(), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(commit, java.nio.file.attribute.FileTime.fromMillis(now + v * 1000));
        }
        return new Written(dir, id, schema, List.of("business_date"), List.copyOf(all), 1);
    }

    private static String schemaString(boolean mapping) {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "struct");
        ArrayNode fs = s.putArray("fields");
        int[] next = {1};
        field(fs, "TRADE_ID", JSON.getNodeFactory().textNode("string"), mapping, next);
        field(fs, "DESK", JSON.getNodeFactory().textNode("string"), mapping, next);
        field(fs, "NOTIONAL", JSON.getNodeFactory().textNode("double"), mapping, next);
        field(fs, "QTY", JSON.getNodeFactory().textNode("long"), mapping, next);
        field(fs, "TRADE_TS", JSON.getNodeFactory().textNode("timestamp"), mapping, next);
        ObjectNode cpty = JSON.createObjectNode().put("type", "struct");
        ArrayNode cf = cpty.putArray("fields");
        field(cpty, "CPTY", mapping, next, fs);
        field(cf, "ID", JSON.getNodeFactory().textNode("string"), mapping, next);
        field(cf, "NAME", JSON.getNodeFactory().textNode("string"), mapping, next);
        ObjectNode tags = JSON.createObjectNode().put("type", "array").put("elementType", "string").put("containsNull", true);
        field(fs, "TAGS", tags, mapping, next);
        field(fs, "business_date", JSON.getNodeFactory().textNode("date"), mapping, next);
        return s.toString();
    }

    private static void field(ObjectNode type, String name, boolean mapping, int[] next, ArrayNode into) {
        field(into, name, type, mapping, next);
    }

    private static void field(ArrayNode fs, String name, com.fasterxml.jackson.databind.JsonNode type, boolean mapping, int[] next) {
        ObjectNode f = fs.addObject();
        f.put("name", name);
        f.set("type", type);
        f.put("nullable", true);
        ObjectNode md = f.putObject("metadata");
        if (mapping) {
            md.put("delta.columnMapping.id", next[0]++).put("delta.columnMapping.physicalName", physical(name, true));
        }
    }

    private static AddedFile parquet(Path dir, LocalDate d, List<Trade> rows, boolean m) throws IOException {
        String part = physical("business_date", m) + "=" + d;
        String rel = part + "/part-00000-" + UUID.randomUUID() + ".c000.snappy.parquet";
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        MessageType type = MessageTypeParser.parseMessageType("message spark_schema {\n"
                + "  optional binary " + physical("TRADE_ID", m) + " (STRING);\n"
                + "  optional binary " + physical("DESK", m) + " (STRING);\n"
                + "  optional double " + physical("NOTIONAL", m) + ";\n"
                + "  optional int64 " + physical("QTY", m) + ";\n"
                + "  optional int96 " + physical("TRADE_TS", m) + ";\n"
                + "  optional group " + physical("CPTY", m) + " {\n"
                + "    optional binary " + physical("ID", m) + " (STRING);\n"
                + "    optional binary " + physical("NAME", m) + " (STRING);\n"
                + "  }\n"
                + "  optional group " + physical("TAGS", m) + " (LIST) {\n"
                + "    repeated group list {\n"
                + "      optional binary element (STRING);\n"
                + "    }\n"
                + "  }\n"
                + "}");
        SimpleGroupFactory g = new SimpleGroupFactory(type);
        try (ParquetWriter<Group> w = ExampleParquetWriter.builder(new LocalOutputFile(file)).withType(type)
                .withCompressionCodec(CompressionCodecName.SNAPPY).build()) {
            for (Trade t : rows) {
                Group r = g.newGroup();
                r.add(physical("TRADE_ID", m), t.id());
                r.add(physical("DESK", m), t.desk());
                r.add(physical("NOTIONAL", m), t.notional());
                r.add(physical("QTY", m), t.qty());
                long day = t.ts().atZone(ZoneOffset.UTC).toLocalDate().getLong(JulianFields.JULIAN_DAY);
                long nanos = t.ts().atZone(ZoneOffset.UTC).toLocalTime().toNanoOfDay();
                r.add(physical("TRADE_TS", m), new NanoTime((int) day, nanos));
                Group c = r.addGroup(physical("CPTY", m));
                c.add(physical("ID", m), t.cptyId());
                c.add(physical("NAME", m), t.cptyName());
                Group tags = r.addGroup(physical("TAGS", m));
                for (String tag : t.tags()) {
                    tags.addGroup("list").add("element", Binary.fromString(tag));
                }
                w.write(r);
            }
        }
        ObjectNode stats = JSON.createObjectNode();
        stats.put("numRecords", rows.size());
        ObjectNode min = stats.putObject("minValues");
        ObjectNode max = stats.putObject("maxValues");
        ObjectNode nulls = stats.putObject("nullCount");
        min.put(physical("TRADE_ID", m), rows.stream().map(Trade::id).min(String::compareTo).orElseThrow());
        max.put(physical("TRADE_ID", m), rows.stream().map(Trade::id).max(String::compareTo).orElseThrow());
        min.put(physical("NOTIONAL", m), rows.stream().mapToDouble(Trade::notional).min().orElseThrow());
        max.put(physical("NOTIONAL", m), rows.stream().mapToDouble(Trade::notional).max().orElseThrow());
        min.put(physical("QTY", m), rows.stream().mapToLong(Trade::qty).min().orElseThrow());
        max.put(physical("QTY", m), rows.stream().mapToLong(Trade::qty).max().orElseThrow());
        min.put(physical("TRADE_TS", m), rows.stream().map(Trade::ts).min(Instant::compareTo).orElseThrow().toString());
        max.put(physical("TRADE_TS", m), rows.stream().map(Trade::ts).max(Instant::compareTo).orElseThrow().toString());
        nulls.put(physical("TRADE_ID", m), 0).put(physical("NOTIONAL", m), 0).put(physical("QTY", m), 0);
        Map<String, String> pv = new LinkedHashMap<>();
        pv.put(physical("business_date", m), d.toString());
        return new AddedFile(rel, pv, Files.size(file), stats.toString());
    }

    /**
     * Deletes the row of {@code tradeId} on {@code d} in place, as Databricks does: a deletion vector file beside the table and a commit
     * that re-adds the data file with it. Returns the new version.
     */
    public static long deleteInPlace(Written t, LocalDate d, String tradeId) throws IOException {
        AddedFile f = t.files().stream().filter(x -> x.partitionValues().containsValue(d.toString())).findFirst().orElseThrow();
        int row = 0;
        for (Trade tr : day(d)) {
            if (tr.id().equals(tradeId)) {
                break;
            }
            row++;
        }
        // the portable RoaringBitmapArray: magic, one bitmap, its key, then the 32-bit bitmap
        org.roaringbitmap.RoaringBitmap deleted = org.roaringbitmap.RoaringBitmap.bitmapOf(row);
        ByteBuffer data = ByteBuffer.allocate(4 + 8 + 4 + deleted.serializedSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(1681511377).putLong(1).putInt(0);
        deleted.serialize(data);
        byte[] bytes = data.array();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        UUID uuid = UUID.randomUUID();
        ByteArrayOutputStream dv = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(dv)) {
            out.writeByte(1);
            out.writeInt(bytes.length);
            out.write(bytes);
            out.writeInt((int) crc.getValue());
        }
        Files.write(t.dir().resolve("deletion_vector_" + uuid + ".bin"), dv.toByteArray());
        long version = t.version() + 1;
        long now = System.currentTimeMillis();
        String pv = JSON.writeValueAsString(f.partitionValues());
        String z85 = io.delta.kernel.internal.deletionvectors.Base85Codec.encodeUUID(uuid);
        String commit = String.join("\n",
                "{\"commitInfo\":{\"timestamp\":" + now + ",\"operation\":\"DELETE\"}}",
                "{\"protocol\":{\"minReaderVersion\":3,\"minWriterVersion\":7,\"readerFeatures\":[\"deletionVectors\""
                        + (t.schemaString().contains("physicalName") ? ",\"columnMapping\"" : "") + "],\"writerFeatures\":[\"deletionVectors\""
                        + (t.schemaString().contains("physicalName") ? ",\"columnMapping\"" : "") + "]}}",
                "{\"remove\":{\"path\":\"" + f.path() + "\",\"deletionTimestamp\":" + now + ",\"dataChange\":true,\"extendedFileMetadata\":true,"
                        + "\"partitionValues\":" + pv + ",\"size\":" + f.size() + "}}",
                "{\"add\":{\"path\":\"" + f.path() + "\",\"partitionValues\":" + pv + ",\"size\":" + f.size() + ",\"modificationTime\":" + now
                        + ",\"dataChange\":true,\"stats\":" + JSON.writeValueAsString(f.stats()) + ",\"deletionVector\":{\"storageType\":\"u\","
                        + "\"pathOrInlineDv\":\"" + z85 + "\",\"offset\":1,\"sizeInBytes\":" + bytes.length + ",\"cardinality\":1}}}") + "\n";
        Files.writeString(t.dir().resolve("_delta_log").resolve("%020d.json".formatted(version)), commit, StandardCharsets.UTF_8);
        return version;
    }
}
