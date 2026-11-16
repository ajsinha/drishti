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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.compute.arrow.ArrowMemory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Writes the engine's result format, an Apache Arrow IPC <em>stream</em> (schema, dictionaries, one record batch, end of stream), for
 * columns of text, 64-bit integers, doubles and booleans with nulls, with the official Apache Arrow Java library. Perspective,
 * DuckDB-Wasm, Arrow JS in the browser and pyarrow in the console read the bytes. Buffers come from a short-lived child of
 * {@link ArrowMemory}.
 */
public final class ArrowIpcWriter {

    /** The column kinds of an engine result. */
    public enum Kind { TEXT, INT64, FLOAT64 }

    /** One column: values are {@code String}, {@code Long} or {@code Double} (by kind), or null. */
    public record Column(String name, Kind kind, Object[] values) {}

    private ArrowIpcWriter() {}

    public static byte[] write(List<Column> columns, int rows) {
        return write(columns, rows, Map.of());
    }

    /**
     * As above with the schema's custom metadata (key and value pairs, in order): an answer sent as Arrow carries the rest of its JSON there
     * ({@code drishti.answer}, see {@link ArrowAnswers}), so the table and the answer around it travel as one stream.
     */
    public static byte[] write(List<Column> columns, int rows, Map<String, String> metadata) {
        return write(columns, rows, metadata, Set.of());
    }

    /**
     * As above, with the text columns at the positions in {@code dictionaries} written as dictionaries: each distinct value once, in a
     * dictionary batch of its own (id = the column's position), and the cells as 32-bit indices into it. {@link ArrowAnswers} chooses it for a
     * column that repeats its values (desks, books, currencies, dates); readers (pyarrow, Arrow JS) give the values back as text.
     */
    public static byte[] write(List<Column> columns, int rows, Map<String, String> metadata, Set<Integer> dictionaries) {
        return write(columns, rows, metadata, dictionaries, Set.of());
    }

    /**
     * As above, with the columns at the positions in {@code booleans} written as Arrow booleans (one bit a cell): their values are
     * {@code Boolean} or null, whatever the column's kind says (a contribution table's {@code partial}).
     */
    public static byte[] write(List<Column> columns, int rows, Map<String, String> metadata, Set<Integer> dictionaries, Set<Integer> booleans) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024 + rows * columns.size() * 12);
        List<FieldVector> dictVectors = new ArrayList<>();
        try (BufferAllocator alloc = ArrowMemory.child("bi-answer", 0)) {
            try {
                DictionaryProvider.MapDictionaryProvider provider = new DictionaryProvider.MapDictionaryProvider();
                List<Field> fields = new ArrayList<>(columns.size());
                List<Map<Object, Integer>> indexes = new ArrayList<>(columns.size());
                for (int c = 0; c < columns.size(); c++) {
                    Column col = columns.get(c);
                    if (booleans.contains(c)) {
                        fields.add(new Field(col.name(), FieldType.nullable(ArrowType.Bool.INSTANCE), null));
                        indexes.add(null);
                    } else if (col.kind() == Kind.TEXT && dictionaries.contains(c)) {
                        Map<Object, Integer> index = new LinkedHashMap<>();
                        for (int r = 0; r < rows; r++) {
                            Object v = col.values()[r];
                            if (v != null) {
                                index.computeIfAbsent(v, k -> index.size());
                            }
                        }
                        DictionaryEncoding enc = new DictionaryEncoding(c, false, new ArrowType.Int(32, true));
                        VarCharVector values = new VarCharVector(new Field(col.name(), FieldType.nullable(ArrowType.Utf8.INSTANCE), null), alloc);
                        values.allocateNew(index.size());
                        int i = 0;
                        for (Object v : index.keySet()) {
                            values.setSafe(i++, ((String) v).getBytes(StandardCharsets.UTF_8));
                        }
                        values.setValueCount(index.size());
                        dictVectors.add(values);
                        provider.put(new Dictionary(values, enc));
                        // the root holds the indices (Arrow Java's in-memory form); the writer puts the values' type in the message
                        fields.add(new Field(col.name(), new FieldType(true, enc.getIndexType(), enc), null));
                        indexes.add(index);
                    } else {
                        fields.add(new Field(col.name(), FieldType.nullable(type(col.kind())), null));
                        indexes.add(null);
                    }
                }
                try (VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(fields, metadata.isEmpty() ? null : metadata), alloc);
                        ArrowStreamWriter writer = new ArrowStreamWriter(root, provider, Channels.newChannel(out))) {
                    for (int c = 0; c < columns.size(); c++) {
                        fill(root.getVector(c), columns.get(c), rows, booleans.contains(c), indexes.get(c));
                    }
                    root.setRowCount(rows);
                    writer.start();
                    writer.writeBatch();
                    writer.end();
                }
            } finally {
                dictVectors.forEach(FieldVector::close);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static ArrowType type(Kind kind) {
        return switch (kind) {
            case TEXT -> ArrowType.Utf8.INSTANCE;
            case INT64 -> new ArrowType.Int(64, true);
            case FLOAT64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        };
    }

    /** One column's cells into its vector: booleans as bits, a dictionary column as 32-bit indices, else by kind. */
    private static void fill(FieldVector vector, Column col, int rows, boolean bool, Map<Object, Integer> index) {
        Object[] v = col.values();
        if (bool) {
            BitVector b = (BitVector) vector;
            b.allocateNew(rows);
            for (int i = 0; i < rows; i++) {
                if (v[i] == null) {
                    b.setNull(i);
                } else {
                    b.set(i, Boolean.TRUE.equals(v[i]) ? 1 : 0);
                }
            }
        } else if (index != null) {
            IntVector ix = (IntVector) vector;
            ix.allocateNew(rows);
            for (int i = 0; i < rows; i++) {
                if (v[i] == null) {
                    ix.setNull(i);
                } else {
                    ix.set(i, index.get(v[i]));
                }
            }
        } else {
            switch (col.kind()) {
                case TEXT -> {
                    VarCharVector s = (VarCharVector) vector;
                    s.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        if (v[i] == null) {
                            s.setNull(i);
                        } else {
                            s.setSafe(i, ((String) v[i]).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                case INT64 -> {
                    BigIntVector n = (BigIntVector) vector;
                    n.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        if (v[i] == null) {
                            n.setNull(i);
                        } else {
                            n.set(i, ((Number) v[i]).longValue());
                        }
                    }
                }
                case FLOAT64 -> {
                    Float8Vector d = (Float8Vector) vector;
                    d.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        if (v[i] == null) {
                            d.setNull(i);
                        } else {
                            d.set(i, ((Number) v[i]).doubleValue());
                        }
                    }
                }
            }
        }
        vector.setValueCount(rows);
    }
}
