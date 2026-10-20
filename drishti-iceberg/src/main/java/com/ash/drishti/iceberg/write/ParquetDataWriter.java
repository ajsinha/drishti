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
package com.ash.drishti.iceberg.write;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.SchemaJson;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import com.ash.drishti.lakeio.ParquetFooters;
import com.ash.drishti.lakeio.Storage;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.conf.ParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;

/**
 * Writes one Parquet data file of an Iceberg table, with no Hadoop: parquet-java's writer configured with
 * {@link ParquetFooters#PLAIN} (no Hadoop {@code Configuration}) and the shared page codecs (no Hadoop codec loading),
 * over the lake's own output file. The Parquet schema carries every column's field id ({@link ParquetSchemas}); the
 * file's Iceberg metrics (value and null counts, column sizes, lower and upper bounds, cut as the table's metrics modes
 * say, and the row groups' offsets) are taken from the finished footer. The table properties honoured:
 * {@code write.parquet.compression-codec} (zstd), {@code write.parquet.compression-level},
 * {@code write.parquet.row-group-size-bytes}, {@code write.parquet.page-size-bytes},
 * {@code write.parquet.page-row-limit}, {@code write.parquet.dict-size-bytes},
 * {@code write.parquet.row-group-check-min-record-count} and {@code -max-record-count}, and the metrics modes. Used by
 * one thread.
 */
public final class ParquetDataWriter implements Closeable {

    private final String path;
    private final Schema schema;
    private final Map<String, String> props;
    private final int specId;
    private final Object[] partition;
    private final Integer sortOrderId;
    private final ParquetWriter<Object[]> writer;
    private final long[] written = new long[1];
    private final MessageType message;
    private long rows;
    private DataFile result;

    /**
     * @param path the file's path as the manifest will name it (relocation applies when it is opened)
     * @param partition the partition values of the spec {@code specId}, in its field order
     */
    public ParquetDataWriter(FileAccess io, String path, Schema schema, Map<String, String> props, int specId, Object[] partition,
            Integer sortOrderId) throws IOException {
        this.path = path;
        this.schema = schema;
        this.props = props;
        this.specId = specId;
        this.partition = partition.clone();
        this.sortOrderId = sortOrderId;
        this.message = ParquetSchemas.of(schema, "table");
        String resolved = io.resolve(path);
        Storage storage = io.storage(resolved);
        CompressionCodecName codec = CompressionCodecName.valueOf(props.getOrDefault("write.parquet.compression-codec", "zstd").toUpperCase(Locale.ROOT));
        int level = Integer.parseInt(props.getOrDefault("write.parquet.compression-level", codec == CompressionCodecName.ZSTD ? "3" : "-1"));
        var codecs = new org.apache.parquet.compression.CompressionCodecFactory() {
            @Override
            public BytesInputCompressor getCompressor(CompressionCodecName name) {
                return io.codecs().compressor(name, level);
            }

            @Override
            public BytesInputDecompressor getDecompressor(CompressionCodecName name) {
                return io.codecs().getDecompressor(name);
            }

            @Override
            public void release() {
            }
        };
        this.writer = new Builder(output(storage, resolved, written), new RowWriteSupport(message, schema)).withConf(ParquetFooters.PLAIN)
                .withCodecFactory(codecs).withCompressionCodec(codec).withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withRowGroupSize(Long.parseLong(props.getOrDefault("write.parquet.row-group-size-bytes", String.valueOf(128L << 20))))
                .withPageSize(Integer.parseInt(props.getOrDefault("write.parquet.page-size-bytes", String.valueOf(1 << 20))))
                .withPageRowCountLimit(Integer.parseInt(props.getOrDefault("write.parquet.page-row-limit", "20000")))
                .withDictionaryPageSize(Integer.parseInt(props.getOrDefault("write.parquet.dict-size-bytes", String.valueOf(2 << 20))))
                .withMinRowCountForPageSizeCheck(Integer.parseInt(props.getOrDefault("write.parquet.row-group-check-min-record-count", "100")))
                .withMaxRowCountForPageSizeCheck(Integer.parseInt(props.getOrDefault("write.parquet.row-group-check-max-record-count", "10000")))
                .withDictionaryEncoding(true).build();
    }

    /** One row: the schema's top-level columns in order, values in their internal form. */
    public void write(Object[] row) throws IOException {
        writer.write(row);
        rows++;
    }

    public long rows() {
        return rows;
    }

    /** The bytes written so far plus what is buffered (to cut files by size). */
    public long length() {
        return writer.getDataSize();
    }

    /** Finishes the file and describes it as its manifest entry will. */
    public DataFile finish() throws IOException {
        if (result == null) {
            writer.close();
            result = describe(writer.getFooter());
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        finish();
    }

    private DataFile describe(ParquetMetadata footer) {
        MetricsConfig metrics = new MetricsConfig(props);
        Map<String, Integer> idOfPath = new HashMap<>();
        Map<Integer, Type.Primitive> typeOf = new HashMap<>();
        for (ColumnDescriptor c : message.getColumns()) {
            if (c.getMaxRepetitionLevel() > 0) {
                continue;                                    // inside a list or map: no metrics, as Iceberg's own writer
            }
            org.apache.parquet.schema.Type t = message.getType(c.getPath());
            if (t.getId() != null) {
                int id = t.getId().intValue();
                idOfPath.put(String.join(".", c.getPath()), id);
                if (schema.field(id) != null && schema.field(id).type() instanceof Type.Primitive p) {
                    typeOf.put(id, p);
                }
            }
        }
        Map<Integer, Long> sizes = new HashMap<>();
        Map<Integer, Long> values = new HashMap<>();
        Map<Integer, Long> nulls = new HashMap<>();
        Map<Integer, Object> lo = new HashMap<>();
        Map<Integer, Object> hi = new HashMap<>();
        java.util.Set<Integer> unbounded = new java.util.HashSet<>();
        List<Long> splits = new ArrayList<>();
        for (BlockMetaData b : footer.getBlocks()) {
            splits.add(b.getStartingPos());
            for (ColumnChunkMetaData ch : b.getColumns()) {
                Integer id = idOfPath.get(ch.getPath().toDotString());
                if (id == null) {
                    continue;
                }
                sizes.merge(id, ch.getTotalSize(), Long::sum);
                values.merge(id, ch.getValueCount(), Long::sum);
                Statistics<?> st = ch.getStatistics();
                if (st == null || st.isEmpty() && ch.getValueCount() > 0 || !st.isNumNullsSet()) {
                    unbounded.add(id);
                    continue;
                }
                nulls.merge(id, st.getNumNulls(), Long::sum);
                if (st.hasNonNullValue() && typeOf.containsKey(id)) {
                    Type.Primitive t = typeOf.get(id);
                    Object min = internal(t, st.genericGetMin(), ch.getPrimitiveType());
                    Object max = internal(t, st.genericGetMax(), ch.getPrimitiveType());
                    lo.merge(id, min, (a, x) -> Values.compare(t, a, x) <= 0 ? a : x);
                    hi.merge(id, max, (a, x) -> Values.compare(t, a, x) >= 0 ? a : x);
                }
            }
        }
        Map<Integer, Long> outValues = new HashMap<>();
        Map<Integer, Long> outNulls = new HashMap<>();
        Map<Integer, byte[]> lower = new HashMap<>();
        Map<Integer, byte[]> upper = new HashMap<>();
        for (Map.Entry<String, Integer> e : idOfPath.entrySet()) {
            int id = e.getValue();
            MetricsConfig.Mode mode = metrics.mode(schema.nameOf(id));
            if (!mode.counts()) {
                sizes.remove(id);
                continue;
            }
            if (values.containsKey(id)) {
                outValues.put(id, values.get(id));
            }
            if (nulls.containsKey(id) && !unbounded.contains(id)) {
                outNulls.put(id, nulls.get(id));
            }
            Type.Primitive t = typeOf.get(id);
            if (!mode.bounds() || t == null || unbounded.contains(id) || !lo.containsKey(id)) {
                continue;
            }
            Object min = lo.get(id);
            Object max = hi.get(id);
            if (t.id() == Type.Id.STRING || t.id() == Type.Id.BINARY) {
                min = truncateLower(min, mode.truncate());
                max = truncateUpper(max, mode.truncate());
            }
            lower.put(id, Values.toBytes(t, min));
            if (max != null) {
                upper.put(id, Values.toBytes(t, max));
            }
        }
        return new DataFile(DataFile.DATA, path, "PARQUET", specId, partition, rows, written[0], sizes, outValues, outNulls, Map.of(), lower, upper, null,
                splits, List.of(), sortOrderId, null);
    }

    /** A statistic's value in Iceberg's internal form. */
    static Object internal(Type.Primitive t, Object v, PrimitiveType file) {
        return switch (t.id()) {
            case STRING -> ((Binary) v).toStringUsingUTF8();
            case BINARY, FIXED -> ((Binary) v).getBytes();
            case UUID -> {
                ByteBuffer b = ByteBuffer.wrap(((Binary) v).getBytes());
                yield new UUID(b.getLong(), b.getLong());
            }
            case DECIMAL -> v instanceof Binary b ? new BigDecimal(new BigInteger(b.getBytes()), t.scale())
                    : new BigDecimal(BigInteger.valueOf(((Number) v).longValue()), t.scale());
            default -> v;
        };
    }

    static Object truncateLower(Object v, int n) {
        if (v instanceof String s) {
            return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
        }
        byte[] b = (byte[]) v;
        return b.length <= n ? b : Arrays.copyOf(b, n);
    }

    /** The smallest value of at most {@code n} characters (bytes) at or above {@code v}; null when there is none. */
    static Object truncateUpper(Object v, int n) {
        if (v instanceof String s) {
            if (s.codePointCount(0, s.length()) <= n) {
                return s;
            }
            int[] cps = s.codePoints().limit(n).toArray();
            for (int i = cps.length - 1; i >= 0; i--) {
                int next = cps[i] + 1;
                if (next >= 0xD800 && next <= 0xDFFF) {
                    next = 0xE000;                             // skip the surrogate range
                }
                if (next <= Character.MAX_CODE_POINT) {
                    cps[i] = next;
                    return new String(cps, 0, i + 1);
                }
            }
            return null;
        }
        byte[] b = (byte[]) v;
        if (b.length <= n) {
            return b;
        }
        byte[] out = Arrays.copyOf(b, n);
        for (int i = n - 1; i >= 0; i--) {
            if ((out[i] & 0xff) != 0xff) {
                out[i]++;
                return Arrays.copyOf(out, i + 1);
            }
        }
        return null;
    }

    private static OutputFile output(Storage storage, String path, long[] written) {
        return new OutputFile() {
            @Override
            public PositionOutputStream create(long blockSizeHint) throws IOException {
                return createOrOverwrite(blockSizeHint);
            }

            @Override
            public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
                OutputStream out = storage.create(path);
                return new PositionOutputStream() {
                    @Override
                    public long getPos() {
                        return written[0];
                    }

                    @Override
                    public void write(int b) throws IOException {
                        out.write(b);
                        written[0]++;
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                        written[0] += len;
                    }

                    @Override
                    public void flush() throws IOException {
                        out.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        out.close();
                    }
                };
            }

            @Override
            public boolean supportsBlockSize() {
                return false;
            }

            @Override
            public long defaultBlockSize() {
                return 0;
            }

            @Override
            public String getPath() {
                return path;
            }
        };
    }

    private static final class Builder extends ParquetWriter.Builder<Object[], Builder> {
        private final RowWriteSupport support;

        Builder(OutputFile file, RowWriteSupport support) {
            super(file);
            this.support = support;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        protected WriteSupport<Object[]> getWriteSupport(org.apache.hadoop.conf.Configuration conf) {
            throw new UnsupportedOperationException("drishti-iceberg writes without a Hadoop Configuration");
        }

        @Override
        protected WriteSupport<Object[]> getWriteSupport(ParquetConfiguration conf) {
            return support;
        }
    }

    /** Rows of internal values to Parquet records, by the table's types. */
    static final class RowWriteSupport extends WriteSupport<Object[]> {
        private final MessageType message;
        private final Schema schema;
        private RecordConsumer out;

        RowWriteSupport(MessageType message, Schema schema) {
            this.message = message;
            this.schema = schema;
        }

        @Override
        public WriteContext init(org.apache.hadoop.conf.Configuration configuration) {
            throw new UnsupportedOperationException("drishti-iceberg writes without a Hadoop Configuration");
        }

        @Override
        public WriteContext init(ParquetConfiguration configuration) {
            return new WriteContext(message, Map.of("iceberg.schema", SchemaJson.write(schema).toString()));
        }

        @Override
        public String getName() {
            return "drishti-iceberg";
        }

        @Override
        public void prepareForWrite(RecordConsumer recordConsumer) {
            this.out = recordConsumer;
        }

        @Override
        public void write(Object[] row) {
            out.startMessage();
            List<NestedField> cols = schema.columns();
            for (int i = 0; i < cols.size(); i++) {
                field(cols.get(i), i, row[i]);
            }
            out.endMessage();
        }

        private void field(NestedField f, int index, Object v) {
            if (v == null) {
                if (f.required()) {
                    throw new IllegalArgumentException("column " + f.name() + " is required: a row has no value for it");
                }
                return;
            }
            out.startField(f.name(), index);
            value(f.type(), v);
            out.endField(f.name(), index);
        }

        private void value(Type t, Object v) {
            switch (t) {
                case Type.Primitive p -> primitive(p, v);
                case Type.Struct s -> {
                    Object[] a = (Object[]) v;
                    out.startGroup();
                    for (int i = 0; i < s.fields().size(); i++) {
                        field(s.fields().get(i), i, i < a.length ? a[i] : null);
                    }
                    out.endGroup();
                }
                case Type.ListType l -> {
                    List<?> items = (List<?>) v;
                    out.startGroup();
                    if (!items.isEmpty()) {
                        out.startField("list", 0);
                        for (Object e : items) {
                            out.startGroup();
                            if (e != null) {
                                out.startField("element", 0);
                                value(l.element(), e);
                                out.endField("element", 0);
                            } else if (l.elementRequired()) {
                                throw new IllegalArgumentException("a list of required elements holds a null");
                            }
                            out.endGroup();
                        }
                        out.endField("list", 0);
                    }
                    out.endGroup();
                }
                case Type.MapType m -> {
                    Map<?, ?> map = (Map<?, ?>) v;
                    out.startGroup();
                    if (!map.isEmpty()) {
                        out.startField("key_value", 0);
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            out.startGroup();
                            out.startField("key", 0);
                            value(m.key(), e.getKey());
                            out.endField("key", 0);
                            if (e.getValue() != null) {
                                out.startField("value", 1);
                                value(m.value(), e.getValue());
                                out.endField("value", 1);
                            }
                            out.endGroup();
                        }
                        out.endField("key_value", 0);
                    }
                    out.endGroup();
                }
            }
        }

        private void primitive(Type.Primitive p, Object v) {
            switch (p.id()) {
                case BOOLEAN -> out.addBoolean((Boolean) v);
                case INT, DATE -> out.addInteger(((Number) v).intValue());
                case LONG, TIME, TIMESTAMP, TIMESTAMPTZ -> out.addLong(((Number) v).longValue());
                case FLOAT -> out.addFloat(((Number) v).floatValue());
                case DOUBLE -> out.addDouble(((Number) v).doubleValue());
                case STRING -> out.addBinary(Binary.fromString(v.toString()));
                case UUID -> {
                    UUID u = (UUID) v;
                    out.addBinary(Binary.fromConstantByteArray(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
                            .putLong(u.getLeastSignificantBits()).array()));
                }
                case FIXED, BINARY -> out.addBinary(Binary.fromConstantByteArray((byte[]) v));
                case DECIMAL -> {
                    BigInteger u = ((BigDecimal) v).setScale(p.scale(), RoundingMode.UNNECESSARY).unscaledValue();
                    if (p.precision() <= 9) {
                        out.addInteger(u.intValueExact());
                    } else if (p.precision() <= 18) {
                        out.addLong(u.longValueExact());
                    } else {
                        out.addBinary(Binary.fromConstantByteArray(fixed(u, ParquetSchemas.decimalBytes(p.precision()))));
                    }
                }
                default -> throw new IllegalArgumentException("not a primitive: " + p);
            }
        }

        /** Two's complement, big-endian, sign-extended to {@code n} bytes. */
        private static byte[] fixed(BigInteger u, int n) {
            byte[] b = u.toByteArray();
            if (b.length == n) {
                return b;
            }
            byte[] out = new byte[n];
            Arrays.fill(out, 0, n - b.length, (byte) (u.signum() < 0 ? 0xff : 0));
            System.arraycopy(b, 0, out, n - b.length, b.length);
            return out;
        }
    }
}
