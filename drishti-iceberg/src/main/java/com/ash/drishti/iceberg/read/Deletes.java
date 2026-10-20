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
package com.ash.drishti.iceberg.read;

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.scan.Planner;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import com.ash.drishti.lakeio.ParquetInput;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deletes of one data file, read from its delete files: the row positions of its position deletes (a sorted set)
 * and, per equality delete file, the key tuples it deletes. A row is live unless its position is deleted or its key
 * columns equal a deleted tuple (null equals null, as the spec says). A delete file is read once and kept
 * ({@code drishti.iceberg.delete-cache-rows}, 5,000,000 rows), since one usually applies to many data files.
 */
public final class Deletes {

    private static final Cache<String, Object> FILES = Caffeine.newBuilder()
            .maximumWeight(Long.getLong("drishti.iceberg.delete-cache-rows", 5_000_000))
            .weigher((String k, Object v) -> 1 + (v instanceof Map<?, ?> m ? m.values().stream().mapToInt(a -> ((long[]) a).length).sum()
                    : ((Set<?>) v).size())).build();

    /** No deletes. */
    public static final Deletes NONE = new Deletes(new long[0], List.of());

    private record Equality(int[] fieldIds, Set<List<Object>> keys) {}

    private final long[] positions;
    private final List<Equality> equality;

    private Deletes(long[] positions, List<Equality> equality) {
        this.positions = positions;
        this.equality = equality;
    }

    /** Forgets every delete file read. */
    public static void purge() {
        FILES.invalidateAll();
    }

    /** The field ids the equality deletes compare (a read must project them to apply the deletes). */
    public Set<Integer> equalityIds() {
        Set<Integer> out = new HashSet<>();
        equality.forEach(e -> Arrays.stream(e.fieldIds()).forEach(out::add));
        return out;
    }

    public boolean isEmpty() {
        return positions.length == 0 && equality.isEmpty();
    }

    public boolean hasPositions() {
        return positions.length > 0;
    }

    /**
     * Whether the row at {@code position}, whose values of {@code ids} are {@code values}, is deleted.
     *
     * @param valueOf the row's value of a field id
     */
    public boolean deleted(long position, java.util.function.IntFunction<Object> valueOf) {
        if (positions.length > 0 && Arrays.binarySearch(positions, position) >= 0) {
            return true;
        }
        for (Equality e : equality) {
            List<Object> key = new ArrayList<>(e.fieldIds().length);
            for (int id : e.fieldIds()) {
                key.add(normal(valueOf.apply(id)));
            }
            if (e.keys().contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** The deletes of {@code data} from its delete files, with key values read as the table's {@code schema} types them. */
    public static Deletes of(DataFile data, List<DataFile> deleteFiles, Schema schema, FileAccess io) {
        if (deleteFiles.isEmpty()) {
            return NONE;
        }
        List<long[]> pos = new ArrayList<>();
        List<Equality> eq = new ArrayList<>();
        for (DataFile d : deleteFiles) {
            if (d.content() == DataFile.POSITION_DELETES) {
                @SuppressWarnings("unchecked")
                Map<String, long[]> byFile = (Map<String, long[]>) FILES.get(d.path(), k -> positions(d, io));
                long[] p = byFile.get(data.path());
                if (p != null) {
                    pos.add(p);
                }
            } else {
                int[] ids = d.equalityIds().stream().mapToInt(Integer::intValue).toArray();
                @SuppressWarnings("unchecked")
                Set<List<Object>> keys = (Set<List<Object>>) FILES.get(d.path() + "#eq", k -> keys(d, ids, schema, io));
                eq.add(new Equality(ids, keys));
            }
        }
        long[] all = pos.stream().flatMapToLong(Arrays::stream).sorted().distinct().toArray();
        return new Deletes(all, eq);
    }

    /** A position delete file: per data file it names, the sorted positions. */
    private static Map<String, long[]> positions(DataFile d, FileAccess io) {
        List<NestedField> cols = List.of(NestedField.required(Planner.DELETE_FILE_PATH, "file_path", Type.Primitive.STRING),
                NestedField.required(Planner.DELETE_POS, "pos", Type.Primitive.LONG));
        Map<String, List<Long>> byFile = new HashMap<>();
        read(d, cols, io, r -> byFile.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add((Long) r[1]));
        Map<String, long[]> out = new HashMap<>();
        byFile.forEach((k, v) -> out.put(k, v.stream().mapToLong(Long::longValue).sorted().toArray()));
        return out;
    }

    private static Set<List<Object>> keys(DataFile d, int[] ids, Schema schema, FileAccess io) {
        List<NestedField> cols = new ArrayList<>();
        for (int id : ids) {
            NestedField f = schema.field(id);
            if (f == null) {
                throw new IllegalStateException("equality delete file " + d.path() + " compares field " + id + ", which the table no longer has");
            }
            cols.add(f);
        }
        Set<List<Object>> out = new HashSet<>();
        read(d, cols, io, r -> {
            List<Object> key = new ArrayList<>(r.length);
            for (Object v : r) {
                key.add(normal(v));
            }
            out.add(key);
        });
        return out;
    }

    private static void read(DataFile d, List<NestedField> cols, FileAccess io, java.util.function.Consumer<Object[]> sink) {
        String p = io.resolve(d.path());
        try (ParquetRowReader r = new ParquetRowReader(ParquetInput.of(io.storage(p), p, d.fileSizeInBytes()), d.path(), d.fileSizeInBytes(), cols,
                null, Map.of(), Expr.TRUE, io.codecs(), false)) {
            while (r.hasNext()) {
                sink.accept(r.next().values());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading delete file " + d.path(), e);
        }
    }

    /** A key value comparable by {@code equals}: byte arrays by content, decimals without trailing zeros, ints as longs. */
    static Object normal(Object v) {
        if (v instanceof byte[] b) {
            return ByteBuffer.wrap(b);
        }
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros();
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Float f) {
            return f.doubleValue();
        }
        return v;
    }

    /** Whether a value equals a deleted one (for tests). */
    static boolean same(Object a, Object b) {
        return Values.equal(a, b);
    }
}
