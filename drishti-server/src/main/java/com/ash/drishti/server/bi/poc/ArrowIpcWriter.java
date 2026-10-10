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
package com.ash.drishti.server.bi.poc;

import com.google.flatbuffers.FlatBufferBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.Endianness;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.FloatingPoint;
import org.apache.arrow.flatbuf.Int;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.MetadataVersion;
import org.apache.arrow.flatbuf.Precision;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.flatbuf.Type;
import org.apache.arrow.flatbuf.Utf8;

/**
 * RUPAKA PHASE 0 PROOF OF CONCEPT. Writes an Apache Arrow IPC <em>stream</em> (schema, one record batch, end-of-stream) for
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
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024 + rows * columns.size() * 12);
        message(out, schema(columns), new byte[0]);
        List<byte[]> buffers = new ArrayList<>();
        long[] nulls = new long[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            nulls[c] = encode(columns.get(c), rows, buffers);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[buffers.size()];
        int[] lengths = new int[buffers.size()];
        for (int i = 0; i < buffers.size(); i++) {
            offsets[i] = body.size();
            lengths[i] = buffers.get(i).length;
            body.writeBytes(buffers.get(i));
            body.writeBytes(new byte[pad(buffers.get(i).length)]);
        }
        message(out, batch(rows, nulls, offsets, lengths, body.size()), body.toByteArray());
        out.writeBytes(new byte[] {-1, -1, -1, -1, 0, 0, 0, 0});          // end of stream
        return out.toByteArray();
    }

    private static byte[] schema(List<Column> columns) {
        FlatBufferBuilder fb = new FlatBufferBuilder(512);
        int[] fields = new int[columns.size()];
        for (int i = 0; i < fields.length; i++) {
            Column c = columns.get(i);
            int name = fb.createString(c.name());
            int type;
            byte typeType;
            switch (c.kind()) {
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
            Field.startField(fb);
            Field.addName(fb, name);
            Field.addNullable(fb, true);
            Field.addTypeType(fb, typeType);
            Field.addType(fb, type);
            Field.addChildren(fb, children);
            fields[i] = Field.endField(fb);
        }
        int vector = Schema.createFieldsVector(fb, fields);
        Schema.startSchema(fb);
        Schema.addEndianness(fb, Endianness.Little);
        Schema.addFields(fb, vector);
        int schema = Schema.endSchema(fb);
        return finish(fb, MessageHeader.Schema, schema, 0);
    }

    private static byte[] batch(int rows, long[] nulls, int[] offsets, int[] lengths, int bodyLength) {
        FlatBufferBuilder fb = new FlatBufferBuilder(512);
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
        int batch = RecordBatch.endRecordBatch(fb);
        return finish(fb, MessageHeader.RecordBatch, batch, bodyLength);
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

    /** Appends the column's buffers (validity, [offsets,] data); returns its null count. */
    private static long encode(Column c, int rows, List<byte[]> buffers) {
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
