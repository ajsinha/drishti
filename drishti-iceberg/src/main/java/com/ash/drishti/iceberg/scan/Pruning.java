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
package com.ash.drishti.iceberg.scan;

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.manifest.ManifestFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a manifest, a partition or a file <em>may</em> hold rows matching a filter: false only when it certainly
 * cannot (so pruning never loses a row). Manifests are judged by their partition summaries, partitions by their
 * values, files by their column bounds and null counts, all through the inclusive projection of the filter onto each
 * partition spec ({@link com.ash.drishti.iceberg.meta.Transform#project}). Stateless.
 */
public final class Pruning {

    private Pruning() {}

    /** The filter projected onto a spec's partition values: comparisons whose field id is the partition field's index. */
    public static List<Expr.Pred> project(Expr filter, PartitionSpec spec, Schema schema) {
        List<Expr.Pred> out = new ArrayList<>();
        for (Expr.Pred p : filter.preds()) {
            NestedField src = schema.field(p.fieldId());
            if (src == null || !(src.type() instanceof Type.Primitive t)) {
                continue;
            }
            for (int i = 0; i < spec.fields().size(); i++) {
                PartitionSpec.Field f = spec.fields().get(i);
                if (f.sourceId() == p.fieldId()) {
                    Optional<Expr.Pred> projected;
                    try {
                        projected = f.transform().project(t, p, i);
                    } catch (RuntimeException e) {
                        projected = Optional.empty();                // a literal the transform cannot take: no pruning
                    }
                    projected.ifPresent(out::add);
                }
            }
        }
        return out;
    }

    /** Whether the manifest may hold matching files, by its partition summaries. */
    public static boolean manifest(ManifestFile m, List<Expr.Pred> projected, Type.Struct partitionType) {
        for (Expr.Pred p : projected) {
            if (p.fieldId() >= m.partitions().size() || !(partitionType.fields().get(p.fieldId()).type() instanceof Type.Primitive t)) {
                continue;
            }
            ManifestFile.FieldSummary s = m.partitions().get(p.fieldId());
            Object lo = decode(t, s.lowerBound());
            Object hi = decode(t, s.upperBound());
            boolean allNull = lo == null && hi == null && s.containsNull();
            if (!range(t, p, lo, hi, s.containsNull(), allNull)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a file of this partition may hold matching rows. */
    public static boolean partition(Object[] values, List<Expr.Pred> projected, Type.Struct partitionType) {
        for (Expr.Pred p : projected) {
            if (p.fieldId() >= values.length || !(partitionType.fields().get(p.fieldId()).type() instanceof Type.Primitive t)) {
                continue;
            }
            Object v = values[p.fieldId()];
            if (!range(t, p, v, v, v == null, v == null)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the data file may hold matching rows, by its column bounds and counts. */
    public static boolean metrics(DataFile f, Expr filter, Schema schema) {
        for (Expr.Pred p : filter.preds()) {
            NestedField c = schema.field(p.fieldId());
            if (c == null || !(c.type() instanceof Type.Primitive t)) {
                continue;
            }
            if ((t.id() == Type.Id.FLOAT || t.id() == Type.Id.DOUBLE) && p.values().stream().anyMatch(v -> v instanceof Number n && Double.isNaN(n.doubleValue()))) {
                continue;
            }
            Long nulls = f.nullValueCounts().get(p.fieldId());
            Long values = f.valueCounts().get(p.fieldId());
            boolean allNull = nulls != null && values != null && nulls.longValue() == values.longValue() && values > 0;
            boolean mayHaveNull = nulls == null || nulls > 0;
            Object lo = decode(t, f.lowerBounds().get(p.fieldId()));
            Object hi = decode(t, f.upperBounds().get(p.fieldId()));
            if (!range(t, p, lo, hi, mayHaveNull, allNull)) {
                return false;
            }
        }
        return true;
    }

    private static Object decode(Type.Primitive t, byte[] b) {
        try {
            return b == null ? null : Values.fromBytes(t, b);
        } catch (RuntimeException e) {
            return null;                                       // a bound this engine cannot read: no pruning by it
        }
    }

    /** Whether values within [lo, hi] (either may be unknown, null) may satisfy {@code p}. */
    private static boolean range(Type.Primitive t, Expr.Pred p, Object lo, Object hi, boolean mayHaveNull, boolean allNull) {
        try {
            return switch (p.op()) {
                case IS_NULL -> mayHaveNull;
                case NOT_NULL -> !allNull;
                case EQ, IN -> {
                    if (allNull) {
                        yield false;
                    }
                    for (Object v : p.values()) {
                        if (v == null) {
                            continue;
                        }
                        if ((lo == null || Values.compare(t, v, lo) >= 0) && (hi == null || Values.compare(t, v, hi) <= 0)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case LT -> !allNull && (lo == null || Values.compare(t, lo, p.value()) < 0);
                case LE -> !allNull && (lo == null || Values.compare(t, lo, p.value()) <= 0);
                case GT -> !allNull && (hi == null || Values.compare(t, hi, p.value()) > 0);
                case GE -> !allNull && (hi == null || Values.compare(t, hi, p.value()) >= 0);
            };
        } catch (RuntimeException e) {
            return true;                                       // a literal of another type: read rather than guess
        }
    }
}
