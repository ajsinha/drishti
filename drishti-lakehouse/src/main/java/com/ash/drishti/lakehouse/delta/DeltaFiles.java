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

import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One Delta data file ({@code add} action of a Delta log, or a {@code file} line of a Delta Sharing answer) as the Iceberg file task the
 * external-table reader plans with: its partition values (identity fields, typed), its row count and its column bounds and null counts
 * from the file's statistics ({@code minValues}, {@code maxValues}, {@code nullCount}), so the reader prunes files by business date and
 * by id as it does for an Iceberg table.
 *
 * <p>Bounds are kept only where they are exact enough to prune safely: a string bound of 32 characters or more may have been cut by the
 * writer and is dropped (a lower bound is kept: a cut prefix is still at or below every value); timestamps are kept to the millisecond
 * the statistics hold, the upper bound widened by the rest of that millisecond; booleans and binary are not used.
 */
public final class DeltaFiles {

    /** Delta's own text forms of a partition timestamp: {@code 2026-09-30 12:00:00} (and fractions), or ISO-8601. */
    private static final DateTimeFormatter PARTITION_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS][.S]");

    private DeltaFiles() {}

    /**
     * The file task.
     *
     * @param path the file's absolute path (or the stable name a share's file is read by)
     * @param partition the partition values by physical or logical column name (as written: text, null for a null)
     * @param stats the statistics JSON, or null when the writer wrote none (the file is then never pruned)
     * @param deletes the file's deletion vector as a position-delete file, or empty
     */
    public static FileTask task(DeltaSchemas.Converted c, PartitionSpec spec, String path, long size, Map<String, String> partition, String stats,
            long sequence, List<DataFile> deletes) {
        Object[] values = new Object[spec.fields().size()];
        for (int i = 0; i < values.length; i++) {
            PartitionSpec.Field f = spec.fields().get(i);
            NestedField col = c.schema().field(f.sourceId());
            String raw = partition.containsKey(c.physical().get(col.id())) ? partition.get(c.physical().get(col.id())) : partition.get(col.name());
            values[i] = partitionValue((Type.Primitive) col.type(), raw, col.name());
        }
        Map<Integer, byte[]> lower = new HashMap<>();
        Map<Integer, byte[]> upper = new HashMap<>();
        Map<Integer, Long> nulls = new HashMap<>();
        Map<Integer, Long> counts = new HashMap<>();
        long rows = 0;
        if (stats != null && !stats.isBlank()) {
            try {
                JsonNode s = DeltaSchemas.JSON.readTree(stats);
                rows = s.path("numRecords").asLong(0);
                for (Map.Entry<Integer, String> e : c.physical().entrySet()) {
                    NestedField col = c.schema().field(e.getKey());
                    if (!(col.type() instanceof Type.Primitive t)) {
                        continue;
                    }
                    bound(t, s.path("minValues").get(e.getValue()), false).ifPresent(b -> lower.put(col.id(), b));
                    bound(t, s.path("maxValues").get(e.getValue()), true).ifPresent(b -> upper.put(col.id(), b));
                    JsonNode n = s.path("nullCount").get(e.getValue());
                    if (n != null && n.isNumber() && rows > 0) {
                        nulls.put(col.id(), n.asLong());
                        counts.put(col.id(), rows);
                    }
                }
            } catch (IOException | RuntimeException e) {
                lower.clear();                                    // unreadable statistics: the file is read, never pruned
                upper.clear();
                nulls.clear();
                counts.clear();
            }
        }
        DataFile data = new DataFile(DataFile.DATA, path, "PARQUET", spec.specId(), values, rows, size, Map.of(), counts, nulls, Map.of(), lower,
                upper, null, List.of(), List.of(), null, null);
        return new FileTask(data, sequence, spec, deletes);
    }

    /** A partition value in Iceberg's internal form ({@code null} or an empty text is a null). */
    static Object partitionValue(Type.Primitive t, String v, String column) {
        if (v == null || v.isEmpty() && t.id() != Type.Id.STRING) {
            return null;
        }
        try {
            return switch (t.id()) {
                case STRING -> v;
                case INT -> Integer.parseInt(v);
                case LONG -> Long.parseLong(v);
                case FLOAT -> Float.parseFloat(v);
                case DOUBLE -> Double.parseDouble(v);
                case BOOLEAN -> Boolean.parseBoolean(v);
                case DATE -> (int) LocalDate.parse(v).toEpochDay();
                case DECIMAL -> new BigDecimal(v).setScale(t.scale(), RoundingMode.UNNECESSARY);
                case TIMESTAMP, TIMESTAMPTZ -> micros(v, t.id() == Type.Id.TIMESTAMPTZ);
                case BINARY -> v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("not a partition type: " + t);
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the partition value '" + v + "' of " + column + " is not a " + t, e);
        }
    }

    private static long micros(String v, boolean utc) {
        Instant i;
        try {
            i = OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            LocalDateTime l;
            try {
                l = LocalDateTime.parse(v);
            } catch (DateTimeParseException e2) {
                l = LocalDateTime.parse(v, PARTITION_TS);
            }
            i = l.toInstant(ZoneOffset.UTC);
        }
        return ChronoUnit.MICROS.between(Instant.EPOCH, i);
    }

    /** A bound in Iceberg's single-value form, or empty when it is not stated or not safe to prune with. */
    private static java.util.Optional<byte[]> bound(Type.Primitive t, JsonNode n, boolean upper) {
        if (n == null || n.isNull() || n.isContainerNode()) {
            return java.util.Optional.empty();
        }
        Object v = switch (t.id()) {
            case INT -> n.canConvertToInt() ? (Object) n.asInt() : null;
            case LONG -> n.isIntegralNumber() ? (Object) n.asLong() : null;
            case DOUBLE -> n.isNumber() ? (Object) n.asDouble() : null;
            case FLOAT -> n.isNumber() ? (Object) (float) n.asDouble() : null;
            case DECIMAL -> n.isNumber() || n.isTextual() ? new BigDecimal(n.asText()).setScale(t.scale(), upper ? RoundingMode.CEILING : RoundingMode.FLOOR) : null;
            case DATE -> n.isTextual() ? (Object) (int) LocalDate.parse(n.asText().substring(0, Math.min(10, n.asText().length()))).toEpochDay() : null;
            case TIMESTAMP, TIMESTAMPTZ -> n.isTextual() ? (Object) (micros(n.asText(), true) + (upper ? 999 : 0)) : null;
            case STRING -> n.isTextual() && (!upper || n.asText().codePointCount(0, n.asText().length()) < 32) ? n.asText() : null;
            default -> null;
        };
        return v == null ? java.util.Optional.empty() : java.util.Optional.of(Values.toBytes(t, v));
    }
}
