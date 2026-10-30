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

import com.google.flatbuffers.FlatBufferBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.flatbuf.Bool;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.DictionaryBatch;
import org.apache.arrow.flatbuf.DictionaryEncoding;
import org.apache.arrow.flatbuf.Endianness;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.FloatingPoint;
import org.apache.arrow.flatbuf.Int;
import org.apache.arrow.flatbuf.KeyValue;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.MetadataVersion;
import org.apache.arrow.flatbuf.Precision;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.flatbuf.Type;
import org.apache.arrow.flatbuf.Utf8;

/**
 * Writes (built in the phase 0 proof of concept, now the engine's result format) an Apache Arrow IPC <em>stream</em> (schema, one record batch, end-of-stream) for
 * columns of text, 64-bit integers and doubles, with nulls. It uses only the message definitions of the Arrow format
 * ({@code arrow-format}, Apache-2.0, with flatbuffers); Arrow's memory and vector libraries (which need JVM
 * {@code --add-opens} flags and bring Netty) are not used. Perspective and DuckDB-Wasm read the bytes in the browser.
 */
public final class ArrowIpcWriter {

    /** The three column types the POC needs. */
    public enum Kind { TEXT, INT64, FLOAT64 }

    /** One column: values are {@code String}, {@code Long} or {@code Double} (by kind), or null. */
    public record Column(String name, Kind kind, Object[] values) {}

    private static final int CONTINUATION = 0xFFFFFFFF;

    private ArrowIpcWriter() {}

    public static byte[] write(List<Column> columns, int rows) {
        return write(columns, rows, java.util.Map.of());
    }

    /**
     * As above with the schema's custom metadata (key and value pairs, in order): an answer sent as Arrow carries the rest of its JSON there
     * ({@code drishti.answer}, see {@link ArrowAnswers}), so the table and the answer around it travel as one stream.
     */
    public static byte[] write(List<Column> columns, int rows, java.util.Map<String, String> metadata) {
        return write(columns, rows, metadata, java.util.Set.of());
    }

    /**
     * As above, with the text columns at the positions in {@code dictionaries} written as dictionaries (2.9.7): each distinct value once, in a
     * dictionary batch of its own (id = the column's position), and the cells as 32-bit indices into it. {@link ArrowAnswers} chooses it for a
     * column that repeats its values (desks, books, currencies, dates); readers (pyarrow, Arrow JS) give the values back as text.
     */
    public static byte[] write(List<Column> columns, int rows, java.util.Map<String, String> metadata, java.util.Set<Integer> dictionaries) {
        return write(columns, rows, metadata, dictionaries, java.util.Set.of());
    }

    /**
     * As above, with the columns at the positions in {@code booleans} written as Arrow booleans (one bit a cell): their values are
     * {@code Boolean} or null, whatever the column's kind says (a contribution table's {@code partial}, 2.9.7).
     */
    public static byte[] write(List<Column> columns, int rows, java.util.Map<String, String> metadata, java.util.Set<Integer> dictionaries,
            java.util.Set<Integer> booleans) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024 + rows * columns.size() * 12);
        message(out, schema(columns, metadata, dictionaries, booleans), new byte[0]);
        List<Column> encoded = new ArrayList<>(columns.size());
        for (int c = 0; c < columns.size(); c++) {
            Column col = columns.get(c);
            if (col.kind() != Kind.TEXT || !dictionaries.contains(c)) {
                encoded.add(col);
                continue;
            }
            // the dictionary first (its own batch, id = the column's position), then the column as indices into it
            java.util.Map<Object, Integer> index = new java.util.LinkedHashMap<>();
            Object[] keys = new Object[rows];
            for (int r = 0; r < rows; r++) {
                Object v = col.values()[r];
                keys[r] = v == null ? null : (long) index.computeIfAbsent(v, k -> index.size());
            }
            Column dict = new Column(col.name(), Kind.TEXT, index.keySet().toArray());
            List<byte[]> dictBuffers = new ArrayList<>();
            long dictNulls = encode(dict, index.size(), dictBuffers, false);
            ByteArrayOutputStream dictBody = new ByteArrayOutputStream();
            int[][] at = layout(dictBuffers, dictBody);
            message(out, dictionaryBatch(c, index.size(), dictNulls, at[0], at[1], dictBody.size()), dictBody.toByteArray());
            encoded.add(new Column(col.name(), Kind.INT64, keys));
        }
        List<byte[]> buffers = new ArrayList<>();
        long[] nulls = new long[encoded.size()];
        for (int c = 0; c < encoded.size(); c++) {
            nulls[c] = booleans.contains(c) ? bits(encoded.get(c), rows, buffers)
                    : encode(encoded.get(c), rows, buffers, columns.get(c).kind() == Kind.TEXT && dictionaries.contains(c));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[][] at = layout(buffers, body);
        message(out, batch(rows, nulls, at[0], at[1], body.size()), body.toByteArray());
        out.writeBytes(new byte[] {-1, -1, -1, -1, 0, 0, 0, 0});          // end of stream
        return out.toByteArray();
    }

    /** Writes the buffers one after another into {@code body}, each padded to 8 bytes; answers their offsets and lengths. */
    private static int[][] layout(List<byte[]> buffers, ByteArrayOutputStream body) {
        int[] offsets = new int[buffers.size()];
        int[] lengths = new int[buffers.size()];
        for (int i = 0; i < buffers.size(); i++) {
            offsets[i] = body.size();
            lengths[i] = buffers.get(i).length;
            body.writeBytes(buffers.get(i));
            body.writeBytes(new byte[pad(buffers.get(i).length)]);
        }
        return new int[][] {offsets, lengths};
    }

    private static byte[] schema(List<Column> columns, java.util.Map<String, String> metadata, java.util.Set<Integer> dictionaries,
            java.util.Set<Integer> booleans) {
        FlatBufferBuilder fb = new FlatBufferBuilder(512);
        int[] fields = new int[columns.size()];
        for (int i = 0; i < fields.length; i++) {
            Column c = columns.get(i);
            int name = fb.createString(c.name());
            int type;
            byte typeType;
            switch (booleans.contains(i) ? null : c.kind()) {
                case null -> {
                    Bool.startBool(fb);
                    type = Bool.endBool(fb);
                    typeType = Type.Bool;
                }
                case TEXT -> {
                    Utf8.startUtf8(fb);
                    type = Utf8.endUtf8(fb);
                    typeType = Type.Utf8;
                }
                case INT64 -> {
                    type = Int.createInt(fb, 64, true);
                    typeType = Type.Int;
                }
                default -> {
                    type = FloatingPoint.createFloatingPoint(fb, Precision.DOUBLE);
                    typeType = Type.FloatingPoint;
                }
            }
            int children = Field.createChildrenVector(fb, new int[0]);
            int dictionary = 0;
            if (c.kind() == Kind.TEXT && dictionaries.contains(i)) {
                int indexType = Int.createInt(fb, 32, true);
                DictionaryEncoding.startDictionaryEncoding(fb);
                DictionaryEncoding.addId(fb, i);
                DictionaryEncoding.addIndexType(fb, indexType);
                DictionaryEncoding.addIsOrdered(fb, false);
                dictionary = DictionaryEncoding.endDictionaryEncoding(fb);
            }
            Field.startField(fb);
            Field.addName(fb, name);
            Field.addNullable(fb, true);
            Field.addTypeType(fb, typeType);
            Field.addType(fb, type);
            Field.addChildren(fb, children);
            if (dictionary != 0) {
                Field.addDictionary(fb, dictionary);
            }
            fields[i] = Field.endField(fb);
        }
        int vector = Schema.createFieldsVector(fb, fields);
        int custom = 0;
        if (!metadata.isEmpty()) {
            int[] pairs = new int[metadata.size()];
            int i = 0;
            for (java.util.Map.Entry<String, String> e : metadata.entrySet()) {
                int k = fb.createString(e.getKey());
                int v = fb.createString(e.getValue());
                pairs[i++] = KeyValue.createKeyValue(fb, k, v);
            }
            custom = Schema.createCustomMetadataVector(fb, pairs);
        }
        Schema.startSchema(fb);
        Schema.addEndianness(fb, Endianness.Little);
        Schema.addFields(fb, vector);
        if (custom != 0) {
            Schema.addCustomMetadata(fb, custom);
        }
        int schema = Schema.endSchema(fb);
        return finish(fb, MessageHeader.Schema, schema, 0);
    }

    private static byte[] batch(int rows, long[] nulls, int[] offsets, int[] lengths, int bodyLength) {
        FlatBufferBuilder fb = new FlatBufferBuilder(512);
        return finish(fb, MessageHeader.RecordBatch, recordBatch(fb, rows, nulls, offsets, lengths), bodyLength);
    }

    /** A dictionary's values as a batch of their own (one text column), for the column with this id. */
    private static byte[] dictionaryBatch(long id, int size, long nulls, int[] offsets, int[] lengths, int bodyLength) {
        FlatBufferBuilder fb = new FlatBufferBuilder(256);
        int data = recordBatch(fb, size, new long[] {nulls}, offsets, lengths);
        DictionaryBatch.startDictionaryBatch(fb);
        DictionaryBatch.addId(fb, id);
        DictionaryBatch.addData(fb, data);
        DictionaryBatch.addIsDelta(fb, false);
        return finish(fb, MessageHeader.DictionaryBatch, DictionaryBatch.endDictionaryBatch(fb), bodyLength);
    }

    private static int recordBatch(FlatBufferBuilder fb, int rows, long[] nulls, int[] offsets, int[] lengths) {
        RecordBatch.startNodesVector(fb, nulls.length);
        for (int i = nulls.length - 1; i >= 0; i--) {
            FieldNode.createFieldNode(fb, rows, nulls[i]);
        }
        int nodes = fb.endVector();
        RecordBatch.startBuffersVector(fb, offsets.length);
        for (int i = offsets.length - 1; i >= 0; i--) {
            Buffer.createBuffer(fb, offsets[i], lengths[i]);
        }
        int buffers = fb.endVector();
        RecordBatch.startRecordBatch(fb);
        RecordBatch.addLength(fb, rows);
        RecordBatch.addNodes(fb, nodes);
        RecordBatch.addBuffers(fb, buffers);
        return RecordBatch.endRecordBatch(fb);
    }

    private static byte[] finish(FlatBufferBuilder fb, byte header, int offset, int bodyLength) {
        Message.startMessage(fb);
        Message.addVersion(fb, MetadataVersion.V5);
        Message.addHeaderType(fb, header);
        Message.addHeader(fb, offset);
        Message.addBodyLength(fb, bodyLength);
        fb.finish(Message.endMessage(fb));
        return fb.sizedByteArray();
    }

    /** Continuation marker, the metadata length (padded so the body starts on 8 bytes), the metadata, then the body. */
    private static void message(ByteArrayOutputStream out, byte[] metadata, byte[] body) {
        int padded = metadata.length + pad(metadata.length + 8);
        ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(CONTINUATION).putInt(padded);
        out.writeBytes(head.array());
        out.writeBytes(metadata);
        out.writeBytes(new byte[padded - metadata.length]);
        out.writeBytes(body);
    }

    private static int pad(int length) {
        return (8 - (length & 7)) & 7;
    }

    /** Appends a boolean column's buffers (validity, then one bit a cell); returns its null count. */
    private static long bits(Column c, int rows, List<byte[]> buffers) {
        Object[] v = c.values();
        long nulls = 0;
        byte[] validity = new byte[(rows + 7) / 8];
        byte[] values = new byte[(rows + 7) / 8];
        for (int i = 0; i < rows; i++) {
            if (v[i] == null) {
                nulls++;
                continue;
            }
            validity[i >> 3] |= (byte) (1 << (i & 7));
            if (Boolean.TRUE.equals(v[i])) {
                values[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        buffers.add(nulls == 0 ? new byte[0] : validity);
        buffers.add(values);
        return nulls;
    }

    /** Appends the column's buffers (validity, [offsets,] data); returns its null count. */
    private static long encode(Column c, int rows, List<byte[]> buffers, boolean indices) {
        Object[] v = c.values();
        long nulls = 0;
        byte[] validity = new byte[(rows + 7) / 8];
        for (int i = 0; i < rows; i++) {
            if (v[i] == null) {
                nulls++;
            } else {
                validity[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        buffers.add(nulls == 0 ? new byte[0] : validity);
        switch (c.kind()) {
            case TEXT -> {
                ByteBuffer offsets = ByteBuffer.allocate((rows + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                offsets.putInt(0);
                for (int i = 0; i < rows; i++) {
                    if (v[i] != null) {
                        data.writeBytes(((String) v[i]).getBytes(StandardCharsets.UTF_8));
                    }
                    offsets.putInt(data.size());
                }
                buffers.add(offsets.array());
                buffers.add(data.toByteArray());
            }
            case INT64 -> {
                if (indices) {                                           // a dictionary column's cells: 32-bit indices into its values
                    ByteBuffer b = ByteBuffer.allocate(rows * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < rows; i++) {
                        b.putInt(v[i] == null ? 0 : ((Number) v[i]).intValue());
                    }
                    buffers.add(b.array());
                    break;
                }
                ByteBuffer b = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < rows; i++) {
                    b.putLong(v[i] == null ? 0L : ((Number) v[i]).longValue());
                }
                buffers.add(b.array());
            }
            default -> {
                ByteBuffer b = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < rows; i++) {
                    b.putDouble(v[i] == null ? 0.0 : ((Number) v[i]).doubleValue());
                }
                buffers.add(b.array());
            }
        }
        return nulls;
    }
}
