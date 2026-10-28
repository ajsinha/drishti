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
package com.ash.drishti.lakeload;

import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.write.ParquetDataWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a load's rows go: a Delta table ({@link DeltaTarget}) or an Iceberg table ({@link IcebergTarget}), in the layout the connectors
 * read. Files are written by many threads at once ({@link #newFile}); {@link #commit} makes one date's files that date's data in ONE
 * commit that also takes the date's earlier files out, so a reader sees the old date or the new one, never a mix, and a load that stops
 * before its commit leaves the table as it was.
 */
interface TableTarget extends AutoCloseable {

    /** The columns a load has: per promoted column whether every value was a number, and whether any value was seen. */
    record Columns(List<String> promoted, boolean[] numeric, boolean[] seen) {}

    /** Creates the table or checks it, and adds the columns it lacks (Iceberg) or reports them left out (Delta). */
    void open(Columns columns) throws IOException;

    /** Starts a business date (Delta: its transaction). */
    void begin(LocalDate date) throws IOException;

    /** A new data file for rows of {@code date} with the given partition values (Delta partitions; none for Iceberg). */
    FileOut newFile(LocalDate date, String[] partitionValues, String name) throws IOException;

    /** Commits the date: its files replace the date's earlier files in one commit; returns the table's version (or snapshot id). */
    long commit(LocalDate date, List<WrittenFile> files) throws IOException;

    /** The table's columns as written ({@code name: string|double|date|vector}). */
    Map<String, String> columnTypes();

    /** Columns of the load the existing table does not have (Delta keeps its schema: they are not written). */
    List<String> leftOut();

    @Override
    void close();

    /** A file being written by one thread. */
    interface FileOut {
        void write(RowView row) throws IOException;

        /** The bytes written so far and buffered (to cut files by size). */
        long length();

        /** Finishes the file (uploads its last part on S3). */
        WrittenFile finish() throws IOException;
    }

    /** A finished data file: where, its rows and bytes, the target's own description of it, its partition and its first and last keys. */
    record WrittenFile(String path, long rows, long bytes, Object handle, String[] partition, List<Object> first, List<Object> last) {}

    // ------------------------------------------------------------------------------------------------ the shared column rules

    /** Where a file column's value comes from: the id, the document, the date, a promoted value, a partition value or a vector. */
    record Source(char kind, int index, Type type) {}

    /**
     * The value of {@code src} for a row in the writer's internal form. A promoted value into a text column is lake build's text for it
     * ({@code icebergRules}: the Iceberg loader's, where a whole number reads {@code 42}); into a number column, a number (text that
     * reads as one, else null).
     */
    static Object value(RowView r, Source src, int epochDay, boolean icebergRules) {
        return switch (src.kind()) {
            case 'i' -> r.id();
            case 'd' -> r.doc();
            case 't' -> Integer.valueOf(epochDay);
            case 'p' -> {
                String v = r.partition(src.index());
                if (v == null) {
                    yield null;
                }
                yield v.getBytes(StandardCharsets.UTF_8);
            }
            case 'v' -> r.vector(src.index());
            case 'c' -> promoted(r, src.index(), src.type(), icebergRules);
            default -> null;
        };
    }

    private static Object promoted(RowView r, int i, Type type, boolean icebergRules) {
        byte tag = r.tag(i);
        if (tag == RowPlan.NULL) {
            return null;
        }
        if (type.id() == Type.Id.DOUBLE) {
            if (tag == RowPlan.NUM) {
                return r.num(i);
            }
            try {
                return Double.parseDouble(new String(r.text(i), StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (type.id() == Type.Id.LIST) {
            return null;                                                // a vector column the load keeps as a promoted field: none
        }
        if (tag == RowPlan.NUM) {
            if (icebergRules) {
                double d = r.num(i);
                return (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d))
                        .getBytes(StandardCharsets.UTF_8);
            }
            return r.numText(i);
        }
        return r.text(i);
    }

    /** The Parquet writer's options for a load: the codec, row groups, and the vector columns' encoding. */
    static Map<String, String> writerOptions(Map<String, String> base, LoadConfig.Layout layout, String defaultCodec, List<String> vectorColumns) {
        Map<String, String> p = new LinkedHashMap<>(base);
        String codec = layout.compression().equals("auto") ? defaultCodec : layout.compression();
        p.put("write.parquet.compression-codec", codec.toLowerCase(Locale.ROOT));
        if (layout.rowGroupBytes() > 0) {
            p.put("write.parquet.row-group-size-bytes", String.valueOf(layout.rowGroupBytes()));
        }
        List<String> paths = new ArrayList<>();
        for (String v : vectorColumns) {
            paths.add(v + ".list.element");
            if (!layout.vectorCompression().equals(codec)) {
                p.put("drishti.parquet.codec." + v + ".list.element", layout.vectorCompression());
            }
        }
        if (!paths.isEmpty()) {
            if (layout.byteStreamSplit()) {
                p.put("drishti.parquet.byte-stream-split", String.join(",", paths));
            }
            p.put("drishti.parquet.no-dictionary", String.join(",", paths));
            p.put("drishti.parquet.no-statistics", String.join(",", paths));
        }
        return p;
    }

    /** A {@link FileOut} over a {@link ParquetDataWriter} whose columns come from {@code sources}. */
    final class ParquetOut implements FileOut {
        private final ParquetDataWriter w;
        private final Source[] sources;
        private final int epochDay;
        private final boolean icebergRules;
        private final String path;
        private final String[] partition;
        private final Object[] row;
        private long rows;
        private byte[] firstKey;
        private byte[] lastKey;
        private final java.util.function.Function<ParquetDataWriter, Object> describe;

        ParquetOut(ParquetDataWriter w, Schema schema, Source[] sources, LocalDate date, boolean icebergRules, String path, String[] partition,
                java.util.function.Function<ParquetDataWriter, Object> describe) {
            this.w = w;
            this.sources = sources;
            this.epochDay = (int) date.toEpochDay();
            this.icebergRules = icebergRules;
            this.path = path;
            this.partition = partition;
            this.row = new Object[schema.columns().size()];
            this.describe = describe;
        }

        @Override
        public void write(RowView r) throws IOException {
            for (int i = 0; i < sources.length; i++) {
                row[i] = sources[i] == null ? null : value(r, sources[i], epochDay, icebergRules);
            }
            w.write(row);
            byte[] k = java.util.Arrays.copyOfRange(r.buf(), r.keyOff(), r.keyOff() + r.keyLen());
            if (firstKey == null) {
                firstKey = k;
            }
            lastKey = k;
            rows++;
        }

        @Override
        public long length() {
            return w.length();
        }

        @Override
        public WrittenFile finish() throws IOException {
            Object handle = describe.apply(w);
            long bytes = handle instanceof com.ash.drishti.iceberg.manifest.DataFile f ? f.fileSizeInBytes() : -1;
            return new WrittenFile(path, rows, bytes, handle, partition, RowPlan.components(firstKey, 0, firstKey.length),
                    RowPlan.components(lastKey, 0, lastKey.length));
        }
    }

    /** The file-column sources for {@code schema}'s columns by name. */
    static Source[] sources(Schema schema, RowPlan plan) {
        List<NestedField> cols = schema.columns();
        Source[] out = new Source[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            NestedField f = cols.get(i);
            out[i] = source(f.name(), f.type(), plan);
        }
        return out;
    }

    private static Source source(String name, Type type, RowPlan plan) {
        switch (name) {
            case "id":
                return new Source('i', 0, type);
            case "doc":
                return new Source('d', 0, type);
            case "business_date":
                return new Source('t', 0, type);
            default:
                break;
        }
        for (int k = 0; k < plan.vectors.size(); k++) {
            if (plan.vectors.get(k).column().equals(name) && type.id() == Type.Id.LIST) {
                return new Source('v', k, type);
            }
        }
        for (int j = 0; j < plan.partition.size(); j++) {
            if (RowPlan.column(plan.partition.get(j)).equals(name)) {
                return new Source('p', j, type);
            }
        }
        for (int c = 0; c < plan.promoted.size(); c++) {
            if (plan.promoted.get(c).column().equals(name)) {
                return new Source('c', c, type);
            }
        }
        return null;                                                    // a column of the table the load does not have: null
    }
}
