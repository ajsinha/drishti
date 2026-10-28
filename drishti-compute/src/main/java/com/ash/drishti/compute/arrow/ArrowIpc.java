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

import com.google.flatbuffers.FlatBufferBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.apache.arrow.flatbuf.Bool;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.Endianness;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.FixedSizeList;
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
 * Reads and writes the Apache Arrow IPC <em>stream</em> format (a schema, record batches, end of stream) for the column types of
 * {@link ArrowTable}, from the format's message definitions alone ({@code arrow-format} with flatbuffers; Arrow's memory libraries, which
 * need JVM {@code --add-opens} flags and bring Netty, are not used). The Python side reads the same bytes with the SDK's pure-Python codec
 * or with pyarrow. A large table is written as several record batches so no batch body passes {@link #BATCH_BYTES}; reading joins them.
 * Files are memory-mapped when read. Compressed bodies and dictionary batches are refused with the reason.
 */
public final class ArrowIpc {

    /** The largest body one record batch gets when writing; a bigger table is split into batches. */
    public static final long BATCH_BYTES = 64L << 20;

    private static final int CONTINUATION = 0xFFFFFFFF;

    private ArrowIpc() {}

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
        message(out, schema(t), List.of());
        int rowBytes = 1;
        for (ArrowTable.Column c : t.columns()) {
            rowBytes += switch (c.type()) {
                case UTF8 -> 32;
                case BOOL -> 1;
                default -> 8 * c.width();
            };
        }
        int step = (int) Math.max(1, Math.min(Integer.MAX_VALUE, BATCH_BYTES / rowBytes));
        if (t.rows() == 0) {
            batch(out, t);
        }
        for (int from = 0; from < t.rows(); from += step) {
            int to = Math.min(t.rows(), from + step);
            batch(out, from == 0 && to == t.rows() ? t : t.slice(from, to));
        }
        out.write(new byte[] {-1, -1, -1, -1, 0, 0, 0, 0});
    }

    private static byte[] schema(ArrowTable t) {
        FlatBufferBuilder fb = new FlatBufferBuilder(512);
        int[] fields = new int[t.columns().size()];
        for (int i = 0; i < fields.length; i++) {
            ArrowTable.Column c = t.columns().get(i);
            if (c.type() == ArrowTable.Type.FLOAT64_VECTOR) {
                int item = field(fb, "item", Type.FloatingPoint, FloatingPoint.createFloatingPoint(fb, Precision.DOUBLE), new int[0]);
                fields[i] = field(fb, c.name(), Type.FixedSizeList, FixedSizeList.createFixedSizeList(fb, c.width()), new int[] {item});
                continue;
            }
            int type;
            byte typeType;
            switch (c.type()) {
                case INT64 -> {
                    type = Int.createInt(fb, 64, true);
                    typeType = Type.Int;
                }
                case FLOAT64 -> {
                    type = FloatingPoint.createFloatingPoint(fb, Precision.DOUBLE);
                    typeType = Type.FloatingPoint;
                }
                case BOOL -> {
                    Bool.startBool(fb);
                    type = Bool.endBool(fb);
                    typeType = Type.Bool;
                }
                default -> {
                    Utf8.startUtf8(fb);
                    type = Utf8.endUtf8(fb);
                    typeType = Type.Utf8;
                }
            }
            fields[i] = field(fb, c.name(), typeType, type, new int[0]);
        }
        int vector = Schema.createFieldsVector(fb, fields);
        Schema.startSchema(fb);
        Schema.addEndianness(fb, Endianness.Little);
        Schema.addFields(fb, vector);
        return finish(fb, MessageHeader.Schema, Schema.endSchema(fb), 0);
    }

    private static int field(FlatBufferBuilder fb, String name, byte typeType, int type, int[] children) {
        int n = fb.createString(name);
        int ch = Field.createChildrenVector(fb, children);
        Field.startField(fb);
        Field.addName(fb, n);
        Field.addNullable(fb, true);
        Field.addTypeType(fb, typeType);
        Field.addType(fb, type);
        Field.addChildren(fb, ch);
        return Field.endField(fb);
    }

    private static void batch(OutputStream out, ArrowTable t) throws IOException {
        int rows = t.rows();
        List<ByteBuffer> buffers = new ArrayList<>();
        List<long[]> nodes = new ArrayList<>();
        for (ArrowTable.Column c : t.columns()) {
            nodes.add(new long[] {rows, c.nullCount()});
            buffers.add(validity(c.nulls(), rows));
            switch (c.type()) {
                case INT64 -> {
                    ByteBuffer b = le(rows * 8L);
                    b.asLongBuffer().put((long[]) c.data());
                    buffers.add(b);
                }
                case FLOAT64 -> {
                    ByteBuffer b = le(rows * 8L);
                    b.asDoubleBuffer().put((double[]) c.data());
                    buffers.add(b);
                }
                case BOOL -> {
                    boolean[] v = (boolean[]) c.data();
                    ByteBuffer b = le((rows + 7) / 8);
                    for (int i = 0; i < rows; i++) {
                        if (v[i]) {
                            b.put(i >> 3, (byte) (b.get(i >> 3) | (1 << (i & 7))));
                        }
                    }
                    buffers.add(b);
                }
                case UTF8 -> {
                    String[] v = (String[]) c.data();
                    ByteBuffer offsets = le((rows + 1) * 4L);
                    byte[][] bytes = new byte[rows][];
                    long size = 0;
                    for (int i = 0; i < rows; i++) {
                        bytes[i] = v[i] == null ? new byte[0] : v[i].getBytes(StandardCharsets.UTF_8);
                        size += bytes[i].length;
                    }
                    if (size > Integer.MAX_VALUE) {
                        throw new IOException("column " + c.name() + " holds more than 2 GB of text in one batch");
                    }
                    ByteBuffer data = le(size);
                    offsets.putInt(0, 0);
                    int at = 0;
                    for (int i = 0; i < rows; i++) {
                        data.put(at, bytes[i]);
                        at += bytes[i].length;
                        offsets.putInt((i + 1) * 4, at);
                    }
                    buffers.add(offsets);
                    buffers.add(data);
                }
                case FLOAT64_VECTOR -> {
                    double[] v = (double[]) c.data();
                    nodes.add(new long[] {(long) rows * c.width(), 0});
                    buffers.add(le(0));                                     // the child's validity: no nulls inside a vector
                    ByteBuffer b = le(v.length * 8L);
                    b.asDoubleBuffer().put(v);
                    buffers.add(b);
                }
            }
        }
        long[] offsets = new long[buffers.size()];
        long[] lengths = new long[buffers.size()];
        long body = 0;
        for (int i = 0; i < buffers.size(); i++) {
            offsets[i] = body;
            lengths[i] = buffers.get(i).capacity();
            body += lengths[i] + pad(lengths[i]);
        }
        FlatBufferBuilder fb = new FlatBufferBuilder(512);
        RecordBatch.startNodesVector(fb, nodes.size());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            FieldNode.createFieldNode(fb, nodes.get(i)[0], nodes.get(i)[1]);
        }
        int nodesAt = fb.endVector();
        RecordBatch.startBuffersVector(fb, offsets.length);
        for (int i = offsets.length - 1; i >= 0; i--) {
            Buffer.createBuffer(fb, offsets[i], lengths[i]);
        }
        int buffersAt = fb.endVector();
        RecordBatch.startRecordBatch(fb);
        RecordBatch.addLength(fb, rows);
        RecordBatch.addNodes(fb, nodesAt);
        RecordBatch.addBuffers(fb, buffersAt);
        int rb = RecordBatch.endRecordBatch(fb);
        message(out, finish(fb, MessageHeader.RecordBatch, rb, body), buffers);
    }

    private static ByteBuffer validity(BitSet nulls, int rows) {
        if (nulls == null || nulls.isEmpty()) {
            return le(0);
        }
        ByteBuffer b = le((rows + 7) / 8);
        for (int i = 0; i < rows; i++) {
            if (!nulls.get(i)) {
                b.put(i >> 3, (byte) (b.get(i >> 3) | (1 << (i & 7))));
            }
        }
        return b;
    }

    private static ByteBuffer le(long size) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a buffer of " + size + " bytes is over 2 GB");
        }
        return ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] finish(FlatBufferBuilder fb, byte header, int offset, long bodyLength) {
        Message.startMessage(fb);
        Message.addVersion(fb, MetadataVersion.V5);
        Message.addHeaderType(fb, header);
        Message.addHeader(fb, offset);
        Message.addBodyLength(fb, bodyLength);
        fb.finish(Message.endMessage(fb));
        return fb.sizedByteArray();
    }

    /** Continuation marker, the metadata length (padded so the body starts on 8 bytes), the metadata, then the body's buffers, each padded. */
    private static void message(OutputStream out, byte[] metadata, List<ByteBuffer> body) throws IOException {
        int padded = metadata.length + (int) pad(metadata.length + 8L);
        ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(CONTINUATION).putInt(padded);
        out.write(head.array());
        out.write(metadata);
        out.write(new byte[padded - metadata.length]);
        for (ByteBuffer b : body) {
            out.write(b.array(), 0, b.capacity());
            out.write(new byte[(int) pad(b.capacity())]);
        }
    }

    private static long pad(long length) {
        return (8 - (length & 7)) & 7;
    }

    // ------------------------------------------------------------------------------------------------------------ reading

    /**
     * Reads a stream file. It is read into the heap, not memory-mapped: on Windows a mapped file cannot be deleted until the mapping is
     * collected, and the request's scratch folder must be gone when the request ends.
     */
    public static ArrowTable read(Path file) throws IOException {
        long size = Files.size(file);
        if (size > Integer.MAX_VALUE - 64) {
            throw new IOException(file.getFileName() + " is over 2 GB: split the result into several tables");
        }
        ByteBuffer b = ByteBuffer.allocate((int) size);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            while (b.hasRemaining() && com.ash.drishti.api.ChannelIo.read(ch, b) >= 0) {
                // read it whole
            }
        }
        b.flip();
        return read(b);
    }

    public static ArrowTable read(byte[] bytes) throws IOException {
        return read(ByteBuffer.wrap(bytes));
    }

    private record FieldInfo(String name, byte type, int bitWidth, boolean signed, short precision, int listSize, byte childType,
            short childPrecision) {}

    private static ArrowTable read(ByteBuffer raw) throws IOException {
        ByteBuffer in = raw.order(ByteOrder.LITTLE_ENDIAN);
        List<FieldInfo> fields = null;
        List<ArrowTable> batches = new ArrayList<>();
        while (in.remaining() >= 4) {
            int size = in.getInt();
            if (size == CONTINUATION) {
                if (in.remaining() < 4) {
                    break;
                }
                size = in.getInt();
            }
            if (size == 0) {
                break;                                                      // end of stream
            }
            if (size < 0 || size > in.remaining()) {
                throw new IOException("not an Arrow IPC stream: a message of " + size + " bytes at " + in.position());
            }
            ByteBuffer meta = in.slice().order(ByteOrder.LITTLE_ENDIAN).limit(size);
            in.position(in.position() + size);
            Message m = Message.getRootAsMessage(meta);
            long bodyLength = m.bodyLength();
            if (bodyLength > in.remaining()) {
                throw new IOException("the Arrow stream is cut short: a body of " + bodyLength + " bytes, " + in.remaining() + " left");
            }
            ByteBuffer body = in.slice().order(ByteOrder.LITTLE_ENDIAN).limit((int) bodyLength);
            in.position(in.position() + (int) bodyLength);
            switch (m.headerType()) {
                case MessageHeader.Schema -> fields = fields((Schema) m.header(new Schema()));
                case MessageHeader.RecordBatch -> {
                    if (fields == null) {
                        throw new IOException("a record batch before the schema");
                    }
                    batches.add(batch((RecordBatch) m.header(new RecordBatch()), body, fields));
                }
                case MessageHeader.DictionaryBatch -> throw new IOException("dictionary-encoded columns are not supported: cast them to text");
                default -> throw new IOException("unexpected Arrow message type " + m.headerType());
            }
        }
        if (fields == null) {
            throw new IOException("not an Arrow IPC stream: no schema");
        }
        if (batches.isEmpty()) {
            List<ArrowTable.Column> cols = new ArrayList<>();
            for (FieldInfo f : fields) {
                cols.add(empty(f));
            }
            return new ArrowTable(cols);
        }
        return batches.size() == 1 ? batches.get(0) : ArrowTable.concat(batches);
    }

    private static List<FieldInfo> fields(Schema s) throws IOException {
        List<FieldInfo> out = new ArrayList<>();
        for (int i = 0; i < s.fieldsLength(); i++) {
            Field f = s.fields(i);
            if (f.dictionary() != null) {
                throw new IOException("column " + f.name() + " is dictionary-encoded: cast it to text");
            }
            byte t = f.typeType();
            switch (t) {
                case Type.Int -> {
                    Int it = (Int) f.type(new Int());
                    out.add(new FieldInfo(f.name(), t, it.bitWidth(), it.isSigned(), (short) 0, 0, (byte) 0, (short) 0));
                }
                case Type.FloatingPoint -> {
                    FloatingPoint fp = (FloatingPoint) f.type(new FloatingPoint());
                    if (fp.precision() == Precision.HALF) {
                        throw new IOException("column " + f.name() + " is a half-precision float: cast it to float64");
                    }
                    out.add(new FieldInfo(f.name(), t, 0, true, fp.precision(), 0, (byte) 0, (short) 0));
                }
                case Type.Bool, Type.Utf8, Type.LargeUtf8 -> out.add(new FieldInfo(f.name(), t, 0, true, (short) 0, 0, (byte) 0, (short) 0));
                case Type.FixedSizeList -> {
                    FixedSizeList fl = (FixedSizeList) f.type(new FixedSizeList());
                    Field child = f.children(0);
                    if (child == null || child.typeType() != Type.FloatingPoint) {
                        throw new IOException("column " + f.name() + " is a fixed-size list of something other than floats");
                    }
                    FloatingPoint fp = (FloatingPoint) child.type(new FloatingPoint());
                    out.add(new FieldInfo(f.name(), t, 0, true, (short) 0, fl.listSize(), child.typeType(), fp.precision()));
                }
                default -> throw new IOException("column " + f.name() + " has Arrow type " + Type.name(t)
                        + ": the compute transport carries int, float, bool, utf8 and fixed-size lists of floats");
            }
        }
        return out;
    }

    private static ArrowTable.Column empty(FieldInfo f) {
        return switch (f.type()) {
            case Type.Int -> new ArrowTable.Column(f.name(), ArrowTable.Type.INT64, 1, new long[0], null);
            case Type.FloatingPoint -> new ArrowTable.Column(f.name(), ArrowTable.Type.FLOAT64, 1, new double[0], null);
            case Type.Bool -> new ArrowTable.Column(f.name(), ArrowTable.Type.BOOL, 1, new boolean[0], null);
            case Type.FixedSizeList -> new ArrowTable.Column(f.name(), ArrowTable.Type.FLOAT64_VECTOR, f.listSize(), new double[0], null);
            default -> new ArrowTable.Column(f.name(), ArrowTable.Type.UTF8, 1, new String[0], null);
        };
    }

    private static ArrowTable batch(RecordBatch rb, ByteBuffer body, List<FieldInfo> fields) throws IOException {
        if (rb.compression() != null) {
            throw new IOException("compressed Arrow bodies are not supported: write without compression");
        }
        int rows = Math.toIntExact(rb.length());
        int[] node = {0};
        int[] buf = {0};
        List<ArrowTable.Column> cols = new ArrayList<>();
        for (FieldInfo f : fields) {
            FieldNode n = rb.nodes(node[0]++);
            BitSet nulls = n.nullCount() == 0 ? null : nulls(body, rb.buffers(buf[0]), rows);
            buf[0]++;
            switch (f.type()) {
                case Type.Int -> {
                    ByteBuffer d = slice(body, rb.buffers(buf[0]++));
                    long[] v = new long[rows];
                    for (int i = 0; i < rows; i++) {
                        v[i] = switch (f.bitWidth()) {
                            case 8 -> f.signed() ? d.get(i) : d.get(i) & 0xFFL;
                            case 16 -> f.signed() ? d.getShort(i * 2) : d.getShort(i * 2) & 0xFFFFL;
                            case 32 -> f.signed() ? d.getInt(i * 4) : d.getInt(i * 4) & 0xFFFFFFFFL;
                            default -> d.getLong(i * 8);
                        };
                    }
                    cols.add(new ArrowTable.Column(f.name(), ArrowTable.Type.INT64, 1, v, nulls));
                }
                case Type.FloatingPoint -> {
                    ByteBuffer d = slice(body, rb.buffers(buf[0]++));
                    cols.add(new ArrowTable.Column(f.name(), ArrowTable.Type.FLOAT64, 1, floats(d, rows, f.precision()), nulls));
                }
                case Type.Bool -> {
                    ByteBuffer d = slice(body, rb.buffers(buf[0]++));
                    boolean[] v = new boolean[rows];
                    for (int i = 0; i < rows; i++) {
                        v[i] = (d.get(i >> 3) >> (i & 7) & 1) == 1;
                    }
                    cols.add(new ArrowTable.Column(f.name(), ArrowTable.Type.BOOL, 1, v, nulls));
                }
                case Type.Utf8, Type.LargeUtf8 -> {
                    ByteBuffer off = slice(body, rb.buffers(buf[0]++));
                    ByteBuffer data = slice(body, rb.buffers(buf[0]++));
                    boolean large = f.type() == Type.LargeUtf8;
                    String[] v = new String[rows];
                    for (int i = 0; i < rows; i++) {
                        if (nulls != null && nulls.get(i)) {
                            continue;
                        }
                        int from = large ? (int) off.getLong(i * 8) : off.getInt(i * 4);
                        int to = large ? (int) off.getLong(i * 8 + 8) : off.getInt(i * 4 + 4);
                        byte[] b = new byte[to - from];
                        data.get(from, b);
                        v[i] = new String(b, StandardCharsets.UTF_8);
                    }
                    cols.add(new ArrowTable.Column(f.name(), ArrowTable.Type.UTF8, 1, v, nulls));
                }
                case Type.FixedSizeList -> {
                    FieldNode child = rb.nodes(node[0]++);
                    if (child.nullCount() != 0) {
                        throw new IOException("column " + f.name() + " has nulls inside its vectors");
                    }
                    buf[0]++;                                               // the child's validity
                    ByteBuffer d = slice(body, rb.buffers(buf[0]++));
                    cols.add(new ArrowTable.Column(f.name(), ArrowTable.Type.FLOAT64_VECTOR, f.listSize(),
                            floats(d, rows * f.listSize(), f.childPrecision()), nulls));
                }
                default -> throw new IOException("column " + f.name() + ": unsupported type");
            }
        }
        return new ArrowTable(cols);
    }

    private static double[] floats(ByteBuffer d, int count, short precision) {
        double[] v = new double[count];
        if (precision == Precision.DOUBLE) {
            d.asDoubleBuffer().get(v, 0, count);
        } else {
            for (int i = 0; i < count; i++) {
                v[i] = d.getFloat(i * 4);
            }
        }
        return v;
    }

    private static ByteBuffer slice(ByteBuffer body, Buffer b) throws IOException {
        long end = b.offset() + b.length();
        if (b.offset() < 0 || end > body.limit()) {
            throw new IOException("an Arrow buffer points outside its body");
        }
        return body.slice((int) b.offset(), (int) b.length()).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static BitSet nulls(ByteBuffer body, Buffer validity, int rows) throws IOException {
        ByteBuffer v = slice(body, validity);
        BitSet nulls = new BitSet(rows);
        for (int i = 0; i < rows; i++) {
            if ((v.get(i >> 3) >> (i & 7) & 1) == 0) {
                nulls.set(i);
            }
        }
        return nulls;
    }
}
