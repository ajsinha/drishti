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
package com.ash.drishti.server.flight;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightRefusal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * A snapshot source over a reader that pushes rows (the Data view's walk, which never holds a day whole): the reader runs on a virtual
 * thread and hands over a table every {@code batchRows} rows, two at most waiting, so memory stays at two batches whatever the day's size.
 * Each row's values are put in the column's type from the plan (a value that does not fit is null). Closing stops the reader.
 */
final class PushedBatches implements FlightBackend.BatchSource {

    /** The reader: sends each row to the sink until the sink answers false. */
    interface Reader {
        void read(RowSink sink) throws Exception;
    }

    interface RowSink {
        boolean row(Map<String, Object> row);
    }

    private static final ArrowTable END = ArrowTable.empty();

    private final List<String> names;
    private final List<ArrowTable.Type> types;
    private final Schema schema;
    private final int batchRows;
    private final BlockingQueue<ArrowTable> queue = new ArrayBlockingQueue<>(2);
    private final Thread thread;
    private volatile boolean closed;
    private volatile Throwable failed;

    PushedBatches(List<String> names, List<ArrowTable.Type> types, int batchRows, Reader reader) {
        this.names = List.copyOf(names);
        this.types = List.copyOf(types);
        this.batchRows = Math.max(1, batchRows);
        this.schema = ArrowIpc.schema(empty());
        this.thread = Thread.ofVirtual().name("flight-read").start(() -> run(reader));
    }

    private void run(Reader reader) {
        List<Map<String, Object>> rows = new ArrayList<>(batchRows);
        try {
            reader.read(row -> {
                if (closed) {
                    return false;
                }
                rows.add(row);
                if (rows.size() >= batchRows) {
                    put(table(rows));
                    rows.clear();
                }
                return !closed;
            });
            if (!rows.isEmpty()) {
                put(table(rows));
            }
        } catch (Throwable t) {
            failed = t;
        } finally {
            try {
                while (!closed && !queue.offer(END, 100, TimeUnit.MILLISECONDS)) {
                    // the client is slow: wait until it takes a batch or goes away
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void put(ArrowTable t) {
        try {
            while (!closed && !queue.offer(t, 100, TimeUnit.MILLISECONDS)) {
                // back-pressure: the producer has not sent the previous batches yet
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public boolean next(VectorSchemaRoot root) {
        ArrowTable t;
        try {
            t = queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (t == END) {
            Throwable f = failed;
            if (f instanceof FlightRefusal r) {
                throw r;
            }
            if (f instanceof DrishtiException d) {
                throw new FlightRefusal(d.errorCode() == ErrorCode.FORBIDDEN ? ErrorCode.FLIGHT_SCOPE : ErrorCode.FLIGHT_BAD_TICKET, d.getMessage(), d);
            }
            if (f != null) {
                throw new IllegalStateException(f.getMessage(), f);
            }
            return false;
        }
        ArrowIpc.fill(root, t, 0, t.rows());
        return true;
    }

    @Override
    public void close() {
        closed = true;
        queue.clear();
        thread.interrupt();
    }

    private ArrowTable empty() {
        return table(List.of());
    }

    /** Rows as a table in the plan's types. */
    ArrowTable table(List<Map<String, Object>> rows) {
        return table(names, types, rows);
    }

    /** Rows as a table in these columns' types (a value that does not fit is null). */
    static ArrowTable table(List<String> names, List<ArrowTable.Type> types, List<Map<String, Object>> rows) {
        int n = rows.size();
        ArrowTable.Builder b = ArrowTable.builder();
        for (int c = 0; c < names.size(); c++) {
            String name = names.get(c);
            BitSet nulls = new BitSet(n);
            switch (types.get(c)) {
                case INT64 -> {
                    long[] d = new long[n];
                    for (int i = 0; i < n; i++) {
                        Long v = asLong(rows.get(i).get(name));
                        if (v == null) {
                            nulls.set(i);
                        } else {
                            d[i] = v;
                        }
                    }
                    b.column(new ArrowTable.Column(name, ArrowTable.Type.INT64, 1, d, nulls));
                }
                case FLOAT64 -> {
                    double[] d = new double[n];
                    for (int i = 0; i < n; i++) {
                        Double v = asDouble(rows.get(i).get(name));
                        if (v == null) {
                            nulls.set(i);
                        } else {
                            d[i] = v;
                        }
                    }
                    b.column(new ArrowTable.Column(name, ArrowTable.Type.FLOAT64, 1, d, nulls));
                }
                case BOOL -> {
                    boolean[] d = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        Object v = rows.get(i).get(name);
                        if (v == null) {
                            nulls.set(i);
                        } else {
                            d[i] = v instanceof Boolean x ? x : "true".equalsIgnoreCase(String.valueOf(v));
                        }
                    }
                    b.column(new ArrowTable.Column(name, ArrowTable.Type.BOOL, 1, d, nulls));
                }
                default -> {
                    String[] d = new String[n];
                    for (int i = 0; i < n; i++) {
                        Object v = rows.get(i).get(name);
                        if (v == null) {
                            nulls.set(i);
                        } else {
                            d[i] = v instanceof Map<?, ?> || v instanceof List<?> ? json(v) : String.valueOf(v);
                        }
                    }
                    b.column(new ArrowTable.Column(name, ArrowTable.Type.UTF8, 1, d, nulls));
                }
            }
        }
        return b.build();
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String json(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(v);
        }
    }
}
