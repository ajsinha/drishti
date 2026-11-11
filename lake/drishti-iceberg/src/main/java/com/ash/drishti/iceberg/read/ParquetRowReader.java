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
import com.ash.drishti.iceberg.meta.NameMapping;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.lakeio.NativeCodecs;
import com.ash.drishti.lakeio.PageDecodeException;
import com.ash.drishti.lakeio.ParquetFooters;
import com.ash.drishti.lakeio.UnsupportedCodec;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.filter2.predicate.Operators;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.io.SeekableInputStream;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.RecordMaterializer;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Types;

/**
 * One Parquet data file read as rows of the table's columns, matched <em>by field id</em> (the spec's column
 * projection): a renamed column is read under its new name, a column added after the file was written reads as null
 * (or, for an identity partition column, as the file's partition value), a widened type is widened. A file written
 * without ids is read through the table's name mapping. The footer comes from the shared footer cache; only the
 * requested columns are decoded; row groups whose statistics rule out the filter are skipped (one entity's read,
 * {@code id = X}, decodes one small row group of a sorted file). Each row carries its position in the file, which
 * position deletes refer to. Rows that pass the row-group test but not the filter itself are returned too: callers
 * check what they asked for. Used by one thread.
 */
public final class ParquetRowReader implements Closeable {

    /** A row: the projection's values and the row's position in the file. */
    public record Row(Object[] values, long position) {}

    private final String path;
    private final List<NestedField> projection;
    private final Map<Integer, Object> constants;
    private final ParquetFileReader reader;
    private final MessageColumnIO columnIO;
    private final Materializer materializer;
    private final boolean pageFiltered;
    private RecordReader<Object[]> records;
    private long leftInGroup;
    private long nextPosition;
    private boolean done;

    /**
     * @param in the file
     * @param length its length (from the manifest)
     * @param projection the table's columns to read (top-level fields of the snapshot's schema), in output order
     * @param mapping the table's name mapping, for files without field ids (or null)
     * @param constants values of columns the file does not hold (identity partition columns: the partition value)
     * @param filter comparisons of top-level columns, for row-group pruning
     * @param pageIndex also skip pages by the column index (only when positions are not needed)
     */
    public ParquetRowReader(InputFile in, String path, long length, List<NestedField> projection, NameMapping mapping, Map<Integer, Object> constants,
            Expr filter, NativeCodecs codecs, boolean pageIndex) throws IOException {
        this.path = path;
        this.projection = List.copyOf(projection);
        this.constants = constants;
        SeekableInputStream stream = in.newStream();
        ParquetFileReader opened = null;
        try {
            ParquetReadOptions base = ParquetFooters.options(codecs).build();
            ParquetMetadata footer = ParquetFooters.footer(path, length, in, stream, base);
            MessageType fileSchema = footer.getFileMetaData().getSchema();
            Converters.Ids ids = ids(fileSchema, mapping);
            MessageType requested = requested(fileSchema, ids);
            Optional<FilterPredicate> pushed = rowGroupFilter(fileSchema, ids, filter);
            this.pageFiltered = pageIndex && pushed.isPresent();
            ParquetReadOptions options = ParquetFooters.options(codecs).useColumnIndexFilter(pageFiltered)
                    .withRecordFilter(pushed.map(FilterCompat::get).orElse(FilterCompat.NOOP)).build();
            opened = ParquetFileReader.open(in, footer, options, stream);      // the reader owns the stream now
            opened.setRequestedSchema(requested);
            this.materializer = new Materializer(requested, ids);
            this.columnIO = new ColumnIOFactory(footer.getFileMetaData().getCreatedBy()).getColumnIO(requested, fileSchema, true);
            this.reader = opened;
        } catch (IOException | RuntimeException e) {
            if (opened == null) {
                stream.close();
            } else {
                opened.close();
            }
            throw e;
        }
    }

    /** Field ids of the file's columns: their own; for a file written without any, the name mapping's (else none). */
    private static Converters.Ids ids(MessageType file, NameMapping mapping) {
        boolean any = hasIds(file);
        if (any || mapping == null) {
            return (f, p) -> f.getId() == null ? null : f.getId().intValue();
        }
        return (f, p) -> mapping.id(logicalPath(p));
    }

    private static boolean hasIds(GroupType g) {
        for (org.apache.parquet.schema.Type t : g.getFields()) {
            if (t.getId() != null || !t.isPrimitive() && hasIds(t.asGroupType())) {
                return true;
            }
        }
        return false;
    }

    /** A converter path without the list and map wrapper levels, as a name mapping names them ({@code legs.element}). */
    private static String logicalPath(String p) {
        return p.replace(".list.element", ".element").replace(".key_value.key", ".key").replace(".key_value.value", ".value")
                .replace(".array.element", ".element");
    }

    /** The file's columns the projection asks for, in file order; nested structs pruned to the table's fields. */
    private MessageType requested(MessageType file, Converters.Ids ids) {
        List<org.apache.parquet.schema.Type> keep = new ArrayList<>();
        for (org.apache.parquet.schema.Type f : file.getFields()) {
            Integer id = ids.of(f, f.getName());
            NestedField want = id == null ? null : field(id);
            if (want != null) {
                keep.add(prune(f, want.type(), f.getName(), ids));
            }
        }
        return new MessageType(file.getName(), keep);
    }

    private NestedField field(int id) {
        for (NestedField f : projection) {
            if (f.id() == id) {
                return f;
            }
        }
        return null;
    }

    private static org.apache.parquet.schema.Type prune(org.apache.parquet.schema.Type f, Type want, String path, Converters.Ids ids) {
        if (f.isPrimitive() || !(want instanceof Type.Struct s)) {
            return f;
        }
        GroupType g = f.asGroupType();
        List<org.apache.parquet.schema.Type> keep = new ArrayList<>();
        for (org.apache.parquet.schema.Type c : g.getFields()) {
            String p = path + "." + c.getName();
            Integer id = ids.of(c, p);
            if (id != null && s.field(id) != null) {
                keep.add(prune(c, s.field(id).type(), p, ids));
            }
        }
        if (keep.isEmpty()) {
            keep.add(g.getFields().get(0));                   // a struct none of whose fields remain: read one to know null from present
        }
        return g.withNewFields(keep);
    }

    /** The filter as parquet-java's row-group predicate, over the top-level primitive columns the file holds. */
    private Optional<FilterPredicate> rowGroupFilter(MessageType file, Converters.Ids ids, Expr filter) {
        FilterPredicate all = null;
        for (Expr.Pred p : filter.preds()) {
            org.apache.parquet.schema.Type col = null;
            for (org.apache.parquet.schema.Type f : file.getFields()) {
                Integer id = ids.of(f, f.getName());
                if (id != null && id == p.fieldId() && f.isPrimitive() && !f.getName().contains(".")) {
                    col = f;
                }
            }
            NestedField want = field(p.fieldId());
            if (col == null || want == null || !(want.type() instanceof Type.Primitive t)) {
                continue;
            }
            FilterPredicate one = predicate(col.asPrimitiveType(), t, p);
            if (one != null) {
                all = all == null ? one : FilterApi.and(all, one);
            }
        }
        return Optional.ofNullable(all);
    }

    private static FilterPredicate predicate(PrimitiveType col, Type.Primitive t, Expr.Pred p) {
        String name = col.getName();
        LogicalTypeAnnotation a = col.getLogicalTypeAnnotation();
        switch (col.getPrimitiveTypeName()) {
            case INT32 -> {
                if (a != null && !(a instanceof LogicalTypeAnnotation.DateLogicalTypeAnnotation) && !(a instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation)) {
                    return null;
                }
                List<Integer> vs = new ArrayList<>();
                for (Object v : p.values()) {
                    if (!(v instanceof Number n) || n.longValue() != n.intValue() || v instanceof Double || v instanceof Float) {
                        return null;                           // a literal the column cannot hold: no pruning, to be safe
                    }
                    vs.add(n.intValue());
                }
                return compare(FilterApi.intColumn(name), p.op(), vs);
            }
            case INT64 -> {
                if (a != null && !(a instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation)
                        && !(a instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation ts && ts.getUnit() == LogicalTypeAnnotation.TimeUnit.MICROS)) {
                    return null;
                }
                List<Long> vs = new ArrayList<>();
                for (Object v : p.values()) {
                    if (!(v instanceof Long || v instanceof Integer)) {
                        return null;
                    }
                    vs.add(((Number) v).longValue());
                }
                return compare(FilterApi.longColumn(name), p.op(), vs);
            }
            case DOUBLE -> {
                List<Double> vs = new ArrayList<>();
                for (Object v : p.values()) {
                    if (!(v instanceof Double || v instanceof Float)) {
                        return null;
                    }
                    vs.add(((Number) v).doubleValue());
                }
                return compare(FilterApi.doubleColumn(name), p.op(), vs);
            }
            case BINARY -> {
                if (!(a instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation) || t.id() != Type.Id.STRING) {
                    return null;
                }
                List<Binary> vs = new ArrayList<>();
                for (Object v : p.values()) {
                    if (!(v instanceof String s)) {
                        return null;
                    }
                    vs.add(Binary.fromString(s));
                }
                return compare(FilterApi.binaryColumn(name), p.op(), vs);
            }
            default -> {
                return null;
            }
        }
    }

    private static <T extends Comparable<T>, C extends Operators.Column<T> & Operators.SupportsLtGt> FilterPredicate compare(C c, Expr.Op op,
            List<T> vs) {
        return switch (op) {
            case EQ -> FilterApi.eq(c, vs.get(0));
            case IN -> FilterApi.in(c, new java.util.HashSet<>(vs));
            case LT -> FilterApi.lt(c, vs.get(0));
            case LE -> FilterApi.ltEq(c, vs.get(0));
            case GT -> FilterApi.gt(c, vs.get(0));
            case GE -> FilterApi.gtEq(c, vs.get(0));
            default -> null;
        };
    }

    /** Moves to the next row group with rows; false at the end of the file. */
    private boolean advance() throws IOException {
        while (leftInGroup == 0) {
            PageReadStore pages = pageFiltered ? reader.readNextFilteredRowGroup() : reader.readNextRowGroup();
            if (pages == null) {
                return false;
            }
            leftInGroup = pages.getRowCount();
            nextPosition = pages.getRowIndexOffset().orElseThrow(() -> new IOException("no row index offset in " + path));
            records = columnIO.getRecordReader(pages, materializer, FilterCompat.NOOP);
        }
        return true;
    }

    /** Whether there is another row. */
    public boolean hasNext() {
        if (done) {
            return false;
        }
        try {
            if (leftInGroup > 0 || advance()) {
                return true;
            }
            done = true;
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("reading Parquet file " + path, e);
        } catch (UnsupportedCodec e) {
            throw e.inFile(path);
        } catch (PageDecodeException e) {
            throw e.inFile(path);
        }
    }

    /** The next row (its array is the reader's own: copy it to keep it). */
    public Row next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        try {
            Object[] fileRow = records.read();
            long pos = nextPosition++;
            leftInGroup--;
            Object[] out = new Object[projection.size()];
            for (int i = 0; i < out.length; i++) {
                int slot = materializer.slotOf(projection.get(i).id());
                out[i] = slot >= 0 ? fileRow[slot] : constants.get(projection.get(i).id());
                if (out[i] != null && projection.get(i).type() instanceof Type.Primitive pt) {
                    out[i] = com.ash.drishti.iceberg.types.Values.promote(pt, out[i]);
                }
            }
            return new Row(out, pos);
        } catch (UnsupportedCodec e) {
            throw e.inFile(path);
        } catch (PageDecodeException e) {
            throw e.inFile(path);
        }
    }

    @Override
    public void close() throws IOException {
        done = true;
        reader.close();
    }

    /** Assembles one file row: a slot per requested top-level column. */
    private final class Materializer extends RecordMaterializer<Object[]> {
        private final Root root;
        private final int[] ids;

        Materializer(MessageType requested, Converters.Ids idOf) {
            ids = new int[requested.getFieldCount()];
            for (int i = 0; i < ids.length; i++) {
                org.apache.parquet.schema.Type f = requested.getType(i);
                ids[i] = idOf.of(f, f.getName());
            }
            root = new Root(requested, idOf);
        }

        int slotOf(int id) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Object[] getCurrentRecord() {
            return root.current;
        }

        @Override
        public GroupConverter getRootConverter() {
            return root;
        }
    }

    private final class Root extends GroupConverter {
        private final Converter[] children;
        Object[] current;

        Root(MessageType requested, Converters.Ids idOf) {
            children = new Converter[requested.getFieldCount()];
            for (int i = 0; i < children.length; i++) {
                org.apache.parquet.schema.Type f = requested.getType(i);
                int slot = i;
                children[i] = Converters.of(f, field(idOf.of(f, f.getName())), f.getName(), idOf, v -> current[slot] = v);
            }
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            return children[fieldIndex];
        }

        @Override
        public void start() {
            current = new Object[children.length];
        }

        @Override
        public void end() {
        }
    }

    /** A message type of the given fields (for tests and the writer's schema checks). */
    static MessageType message(String name, List<org.apache.parquet.schema.Type> fields) {
        return Types.buildMessage().addFields(fields.toArray(new org.apache.parquet.schema.Type[0])).named(name);
    }
}
