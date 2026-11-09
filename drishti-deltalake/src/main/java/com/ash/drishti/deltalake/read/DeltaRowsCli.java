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
package com.ash.drishti.deltalake.read;

import com.ash.drishti.deltalake.NativeEngine;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.delta.kernel.Scan;
import io.delta.kernel.ScanBuilder;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ArrayValue;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Literal;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.ByteType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DecimalType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.ShortType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampNTZType;
import io.delta.kernel.types.TimestampType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Delta table's rows as Delta Kernel reads them, for the Python tools (tools/lake/deltaread.py): the one reader that applies every
 * reader feature Kernel supports (deletion vectors, column mapping by name or id, v2 checkpoints, timestamps without time zone), so
 * Python reads exactly what the server reads. Output, JSON Lines on standard output: first {@code {"schema":[{"name","type"}]}}, then one
 * JSON array a row in the schema's order (a date as {@code yyyy-MM-dd}, a timestamp as microseconds since the epoch, a decimal as text,
 * an array of numbers as a JSON array).
 *
 * <pre>java -Dloader.main=com.ash.drishti.deltalake.read.DeltaRowsCli -jar drishti-server-exec.jar TABLE [--version N]
 *     [--date yyyy-MM-dd] [--columns a,b]</pre>
 */
public final class DeltaRowsCli {

    private DeltaRowsCli() {}

    /** Reads the table the arguments name and writes its rows to standard output; exit 1 with the reason on standard error. */
    public static void main(String[] args) throws Exception {
        String table = null;
        Long version = null;
        String date = null;
        List<String> columns = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version" -> version = Long.parseLong(args[++i]);
                case "--date" -> date = LocalDate.parse(args[++i]).toString();
                case "--columns" -> columns = List.of(args[++i].split(","));
                default -> table = args[i];
            }
        }
        if (table == null) {
            System.err.println("usage: DeltaRowsCli TABLE [--version N] [--date yyyy-MM-dd] [--columns a,b]");
            System.exit(2);
        }
        try (OutputStream out = new BufferedOutputStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), 1 << 16)) {
            write(NativeEngine.create(), table, version, date, columns, out);
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.err.println("deltaread: " + e.getMessage() + (root != e ? " (" + root + ")" : ""));
            System.exit(1);
        }
    }

    /** Writes the table's rows (as of {@code version} when given; one business date when {@code date} is given) to {@code out}. */
    public static void write(Engine engine, String table, Long version, String date, List<String> columns, OutputStream out) throws IOException {
        Table t = Table.forPath(engine, table);
        Snapshot s = version == null ? t.getLatestSnapshot(engine) : t.getSnapshotAsOfVersion(engine, version);
        StructType schema = s.getSchema();
        if (columns != null) {
            StructType only = new StructType();
            for (String c : columns) {
                only = only.add(schema.get(c));
            }
            schema = only;
        }
        ScanBuilder b = s.getScanBuilder().withReadSchema(schema);
        if (date != null && s.getPartitionColumnNames().contains("business_date")) {
            boolean text = s.getSchema().get("business_date").getDataType() instanceof io.delta.kernel.types.StringType;   // delta-rs may write a string column
            b = b.withFilter(new Predicate("=", new Column("business_date"),
                    text ? Literal.ofString(date) : Literal.ofDate((int) LocalDate.parse(date).toEpochDay())));
        }
        Scan scan = b.build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        JsonGenerator g = new JsonFactory().createGenerator(out);
        g.setRootValueSeparator(new com.fasterxml.jackson.core.io.SerializedString("\n"));
        g.writeStartObject();
        g.writeArrayFieldStart("schema");
        for (StructField f : schema.fields()) {
            g.writeStartObject();
            g.writeStringField("name", f.getName());
            g.writeStringField("type", typeName(f.getDataType()));
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeEndObject();
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fr = files.next().getRows()) {
                    while (fr.hasNext()) {
                        Row file = fr.next();                      // a partition filter prunes files exactly (Kernel)
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
                        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                                .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.empty());
                             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, state, file,
                                     raw.map(FileReadResult::getData))) {
                            while (data.hasNext()) {
                                FilteredColumnarBatch batch = data.next();
                                rows(g, batch, schema);
                            }
                        }
                    }
                }
            }
        }
        g.flush();
        out.write('\n');
        out.flush();
    }

    private static void rows(JsonGenerator g, FilteredColumnarBatch batch, StructType schema) throws IOException {
        var data = batch.getData();
        Optional<ColumnVector> sel = batch.getSelectionVector();
        List<ColumnVector> cols = new ArrayList<>();
        for (StructField f : schema.fields()) {
            cols.add(data.getColumnVector(data.getSchema().indexOf(f.getName())));
        }
        for (int r = 0; r < data.getSize(); r++) {
            if (sel.isPresent() && (sel.get().isNullAt(r) || !sel.get().getBoolean(r))) {
                continue;                                               // deleted by a deletion vector
            }
            g.writeStartArray();
            for (ColumnVector v : cols) {
                value(g, v, r);
            }
            g.writeEndArray();
        }
    }

    private static void value(JsonGenerator g, ColumnVector v, int r) throws IOException {
        if (v.isNullAt(r)) {
            g.writeNull();
            return;
        }
        DataType t = v.getDataType();
        if (t instanceof StringType) {
            g.writeString(v.getString(r));
        } else if (t instanceof DoubleType) {
            double d = v.getDouble(r);
            if (Double.isFinite(d)) {
                g.writeNumber(d);
            } else {
                g.writeString(Double.toString(d));
            }
        } else if (t instanceof FloatType) {
            g.writeNumber(v.getFloat(r));
        } else if (t instanceof LongType || t instanceof TimestampType || t instanceof TimestampNTZType) {
            g.writeNumber(v.getLong(r));
        } else if (t instanceof IntegerType) {
            g.writeNumber(v.getInt(r));
        } else if (t instanceof ShortType) {
            g.writeNumber(v.getShort(r));
        } else if (t instanceof ByteType) {
            g.writeNumber(v.getByte(r));
        } else if (t instanceof BooleanType) {
            g.writeBoolean(v.getBoolean(r));
        } else if (t instanceof DateType) {
            g.writeString(LocalDate.ofEpochDay(v.getInt(r)).toString());
        } else if (t instanceof DecimalType) {
            g.writeString(v.getDecimal(r).toPlainString());
        } else if (t instanceof ArrayType) {
            ArrayValue a = v.getArray(r);
            ColumnVector el = a.getElements();
            g.writeStartArray();
            for (int i = 0; i < a.getSize(); i++) {
                value(g, el, i);
            }
            g.writeEndArray();
        } else {
            throw new IllegalArgumentException("deltaread: a column of type " + t + " is not read by the tools yet");
        }
    }

    private static String typeName(DataType t) {
        if (t instanceof ArrayType a) {
            return "array<" + typeName(a.getElementType()) + ">";
        }
        if (t instanceof TimestampNTZType) {
            return "timestamp_ntz";
        }
        if (t instanceof DecimalType d) {
            return "decimal(" + d.getPrecision() + "," + d.getScale() + ")";
        }
        return t.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** For tests: writes to a print stream. */
    static void write(Engine engine, String table, PrintStream out) throws IOException {
        write(engine, table, null, null, null, out);
    }
}
