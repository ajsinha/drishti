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
package com.ash.drishti.compute.arrow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseIntVector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.LargeVarCharVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryEncoder;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Reads and writes the Apache Arrow IPC <em>stream</em> format (a schema, record batches, end of stream) for the column types of
 * {@link ArrowTable}, with the official Apache Arrow Java library ({@code arrow-vector}): the Python workers read the same bytes with
 * pyarrow or the SDK's codec. A large table is written as several record batches so no batch passes {@link #BATCH_BYTES}; reading joins
 * them. Dictionary-encoded text is decoded to text when read; compressed bodies are refused with the reason. Every buffer comes from a
 * short-lived child of {@link ArrowMemory}, so busy protection sees it.
 */
public final class ArrowIpc {

    /** The largest body one record batch gets when writing; a bigger table is split into batches. */
    public static final long BATCH_BYTES = 64L << 20;

    private static final FieldType DOUBLE = FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));

    private ArrowIpc() {}

    // ------------------------------------------------------------------------------------------------------------ the schema

    /** The Arrow schema of a table, the same one the stream carries. */
    public static Schema schema(ArrowTable t) {
        return schema(t, Map.of());
    }

    /** As above with the schema's custom metadata. */
    public static Schema schema(ArrowTable t, Map<String, String> metadata) {
        List<Field> fields = new ArrayList<>(t.columns().size());
        for (ArrowTable.Column c : t.columns()) {
            fields.add(field(c));
        }
        return new Schema(fields, metadata.isEmpty() ? null : metadata);
    }

    private static Field field(ArrowTable.Column c) {
        return switch (c.type()) {
            case INT64 -> new Field(c.name(), FieldType.nullable(new ArrowType.Int(64, true)), null);
            case FLOAT64 -> new Field(c.name(), DOUBLE, null);
            case BOOL -> new Field(c.name(), FieldType.nullable(ArrowType.Bool.INSTANCE), null);
            case UTF8 -> new Field(c.name(), FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
            case FLOAT64_VECTOR -> new Field(c.name(), FieldType.nullable(new ArrowType.FixedSizeList(c.width())),
                    List.of(new Field("item", DOUBLE, null)));
        };
    }

    // ------------------------------------------------------------------------------------------------------------ writing

    /** The table as stream bytes. */
    public static byte[] toBytes(ArrowTable t) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1024 + t.rows() * 16);
        try {
            write(t, out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Writes the table to a file (created or replaced). */
    public static void write(ArrowTable t, Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                OutputStream out = new java.io.BufferedOutputStream(com.ash.drishti.api.ChannelIo.outputStream(ch), 1 << 20)) {
            write(t, out);
        }
    }

    public static void write(ArrowTable t, OutputStream out) throws IOException {
        write(t, Map.of(), out);
    }

    /** Writes the table with the schema's custom metadata, in batches of at most {@link #BATCH_BYTES}. */
    public static void write(ArrowTable t, Map<String, String> metadata, OutputStream out) throws IOException {
        try (BufferAllocator alloc = ArrowMemory.child("ipc-write", 0);
                VectorSchemaRoot root = VectorSchemaRoot.create(schema(t, metadata), alloc);
                ArrowStreamWriter writer = new ArrowStreamWriter(root, null, Channels.newChannel(out))) {
            writer.start();
            int step = batchRows(t);
            if (t.rows() == 0) {
                fill(root, t, 0, 0);
                writer.writeBatch();
            }
            for (int from = 0; from < t.rows(); from += step) {
                int to = Math.min(t.rows(), from + step);
                fill(root, t, from, to);
                writer.writeBatch();
            }
            writer.end();
        }
    }

    /** How many rows one batch holds so its body stays under {@link #BATCH_BYTES}. */
    public static int batchRows(ArrowTable t) {
        long rowBytes = 1;
        for (ArrowTable.Column c : t.columns()) {
            rowBytes += switch (c.type()) {
                case UTF8 -> 32;
                case BOOL -> 1;
                default -> 8L * c.width();
            };
        }
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, BATCH_BYTES / rowBytes));
    }

    /**
     * Fills the root's vectors with the rows {@code [from, to)} of the table (the root's schema is {@link #schema} of the table). Arrow
     * Flight streams a table this way, one batch at a time.
     */
    public static void fill(VectorSchemaRoot root, ArrowTable t, int from, int to) {
        int rows = to - from;
        for (int c = 0; c < t.columns().size(); c++) {
            ArrowTable.Column col = t.columns().get(c);
            FieldVector v = root.getVector(c);
            v.reset();
            switch (col.type()) {
                case INT64 -> {
                    BigIntVector bv = (BigIntVector) v;
                    long[] d = (long[]) col.data();
                    bv.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        if (col.isNull(from + i)) {
                            bv.setNull(i);
                        } else {
                            bv.set(i, d[from + i]);
                        }
                    }
                }
                case FLOAT64 -> {
                    Float8Vector fv = (Float8Vector) v;
                    double[] d = (double[]) col.data();
                    fv.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        if (col.isNull(from + i)) {
                            fv.setNull(i);
                        } else {
                            fv.set(i, d[from + i]);
                        }
                    }
                }
                case BOOL -> {
                    BitVector bv = (BitVector) v;
                    boolean[] d = (boolean[]) col.data();
                    bv.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        if (col.isNull(from + i)) {
                            bv.setNull(i);
                        } else {
                            bv.set(i, d[from + i] ? 1 : 0);
                        }
                    }
                }
                case UTF8 -> {
                    VarCharVector sv = (VarCharVector) v;
                    String[] d = (String[]) col.data();
                    sv.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        if (col.isNull(from + i)) {
                            sv.setNull(i);
                        } else {
                            String s = d[from + i];
                            sv.setSafe(i, s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                case FLOAT64_VECTOR -> {
                    FixedSizeListVector lv = (FixedSizeListVector) v;
                    int w = col.width();
                    double[] d = (double[]) col.data();
                    lv.setInitialCapacity(Math.max(1, rows));
                    lv.allocateNew();
                    Float8Vector items = (Float8Vector) lv.getDataVector();
                    for (int i = 0; i < rows; i++) {
                        if (col.isNull(from + i)) {
                            lv.setNull(i);
                        } else {
                            lv.setNotNull(i);
                        }
                    }
                    for (int k = 0; k < rows * w; k++) {
                        items.set(k, d[from * w + k]);
                    }
                    items.setValueCount(rows * w);
                }
            }
            v.setValueCount(rows);
        }
        root.setRowCount(rows);
    }

    // ------------------------------------------------------------------------------------------------------------ reading

    /**
     * Reads a stream file. It is read into the heap, not memory-mapped: on Windows a mapped file cannot be deleted until the mapping is
     * collected, and the request's scratch folder must be gone when the request ends.
     */
    public static ArrowTable read(Path file) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file), 1 << 20)) {
            return read(in);
        }
    }

    public static ArrowTable read(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes));
    }

    /** Reads a whole stream into one table (its batches joined). */
    public static ArrowTable read(InputStream in) throws IOException {
        try (BufferAllocator alloc = ArrowMemory.child("ipc-read", 0);
                ArrowStreamReader reader = new ArrowStreamReader(in, alloc)) {
            VectorSchemaRoot root;
            try {
                root = reader.getVectorSchemaRoot();
            } catch (IOException | RuntimeException e) {
                throw new IOException("not an Arrow IPC stream: " + e.getMessage(), e);
            }
            List<ArrowTable> batches = new ArrayList<>();
            while (reader.loadNextBatch()) {
                batches.add(table(root, reader));
            }
            if (batches.isEmpty()) {
                return table(root, reader);
            }
            return batches.size() == 1 ? batches.get(0) : ArrowTable.concat(batches);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new IOException("the Arrow stream cannot be read: " + e.getMessage(), e);
        }
    }

    /** The rows a root holds now as a table (dictionary columns decoded to text). */
    public static ArrowTable table(VectorSchemaRoot root, DictionaryProvider dictionaries) throws IOException {
        int rows = root.getRowCount();
        List<ArrowTable.Column> cols = new ArrayList<>();
        for (FieldVector v : root.getFieldVectors()) {
            String name = v.getField().getName();
            ValueVector vector = v;
            if (v.getField().getDictionary() != null) {
                Dictionary d = dictionaries == null ? null : dictionaries.lookup(v.getField().getDictionary().getId());
                if (d == null) {
                    throw new IOException("column " + name + " is dictionary-encoded and its dictionary is missing");
                }
                vector = DictionaryEncoder.decode(v, d);
            }
            try {
                cols.add(column(name, vector, rows));
            } finally {
                if (vector != v) {
                    vector.close();
                }
            }
        }
        return new ArrowTable(cols);
    }

    private static ArrowTable.Column column(String name, ValueVector v, int rows) throws IOException {
        BitSet nulls = null;
        for (int i = 0; i < rows; i++) {
            if (v.isNull(i)) {
                if (nulls == null) {
                    nulls = new BitSet(rows);
                }
                nulls.set(i);
            }
        }
        if (v instanceof BaseIntVector iv) {
            long[] d = new long[rows];
            for (int i = 0; i < rows; i++) {
                d[i] = v.isNull(i) ? 0 : iv.getValueAsLong(i);
            }
            return new ArrowTable.Column(name, ArrowTable.Type.INT64, 1, d, nulls);
        }
        if (v instanceof Float8Vector || v instanceof Float4Vector) {
            return new ArrowTable.Column(name, ArrowTable.Type.FLOAT64, 1, floats(v, 0, rows), nulls);
        }
        if (v instanceof BitVector bv) {
            boolean[] d = new boolean[rows];
            for (int i = 0; i < rows; i++) {
                d[i] = !bv.isNull(i) && bv.get(i) == 1;
            }
            return new ArrowTable.Column(name, ArrowTable.Type.BOOL, 1, d, nulls);
        }
        if (v instanceof VarCharVector || v instanceof LargeVarCharVector) {
            String[] d = new String[rows];
            for (int i = 0; i < rows; i++) {
                if (!v.isNull(i)) {
                    byte[] b = v instanceof VarCharVector s ? s.get(i) : ((LargeVarCharVector) v).get(i);
                    d[i] = new String(b, StandardCharsets.UTF_8);
                }
            }
            return new ArrowTable.Column(name, ArrowTable.Type.UTF8, 1, d, nulls);
        }
        if (v instanceof FixedSizeListVector lv) {
            ValueVector items = lv.getDataVector();
            if (!(items instanceof Float8Vector || items instanceof Float4Vector)) {
                throw new IOException("column " + name + " is a fixed-size list of something other than floats");
            }
            int w = lv.getListSize();
            for (int k = 0; k < rows * w; k++) {
                if (items.isNull(k)) {
                    throw new IOException("column " + name + " has nulls inside its vectors");
                }
            }
            return new ArrowTable.Column(name, ArrowTable.Type.FLOAT64_VECTOR, w, floats(items, 0, rows * w), nulls);
        }
        throw new IOException("column " + name + " has Arrow type " + v.getField().getType()
                + ": the compute transport carries int, float, bool, utf8 and fixed-size lists of floats");
    }

    private static double[] floats(ValueVector v, int from, int count) {
        double[] d = new double[count];
        if (v instanceof Float8Vector f) {
            for (int i = 0; i < count; i++) {
                d[i] = f.isNull(from + i) ? 0.0 : f.get(from + i);
            }
        } else {
            Float4Vector f = (Float4Vector) v;
            for (int i = 0; i < count; i++) {
                d[i] = f.isNull(from + i) ? 0.0 : f.get(from + i);
            }
        }
        return d;
    }
}
