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

import com.ash.drishti.deltalake.read.DeltaRowsCli;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a Delta table holds on one business date, counted by a few columns: read before a file is pushed, to say which rows a replace would
 * remove. Only those columns are read, through the same Kernel reader as every Delta read.
 */
public final class TableDateRows {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The date's rows, by the values of the {@code columns} the table has.
     *
     * @param rows the rows of the date (0: none, or no such table)
     * @param columns the asked columns the table has
     * @param groups the values of {@code columns} (in that order) to their row count
     */
    public record Counts(long rows, List<String> columns, Map<List<String>, Long> groups) {

        /** Nothing on the date. */
        public static final Counts NONE = new Counts(0, List.of(), Map.of());
    }

    private TableDateRows() {}

    /** Counts the rows of {@code date} in the table at {@code location}. */
    public static Counts count(String location, String date, List<String> columns) throws IOException {
        Engine engine = LakeWriter.engineFor(location, Map.of());
        Snapshot s;
        try {
            s = Table.forPath(engine, location).getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            return Counts.NONE;
        }
        List<String> have = new ArrayList<>();
        for (String c : columns) {
            if (s.getSchema().indexOf(c) >= 0) {
                have.add(c);
            }
        }
        List<String> read = have.isEmpty() ? List.of(s.getSchema().fieldNames().get(0)) : have;
        Map<List<String>, Long> groups = new TreeMap<>(java.util.Comparator.comparing(List::toString));
        long[] rows = {0};
        try (Lines out = new Lines(line -> {
            JsonNode a = JSON.readTree(line);
            if (!a.isArray()) {
                return;                                                  // the schema line
            }
            rows[0]++;
            if (have.isEmpty()) {
                return;
            }
            List<String> key = new ArrayList<>(have.size());
            for (int i = 0; i < have.size(); i++) {
                key.add(a.get(i) == null || a.get(i).isNull() ? "" : a.get(i).asText());
            }
            groups.merge(key, 1L, Long::sum);
        })) {
            DeltaRowsCli.write(engine, location, null, date, read, out);
        }
        return new Counts(rows[0], have, new LinkedHashMap<>(groups));
    }

    private interface LineSink {
        void line(byte[] line) throws IOException;
    }

    /** Splits what is written into lines, keeping only the current one. */
    private static final class Lines extends OutputStream {
        private final LineSink sink;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);

        Lines(LineSink sink) {
            this.sink = sink;
        }

        @Override
        public void write(int b) throws IOException {
            if (b == '\n') {
                sink.line(line.toByteArray());
                line.reset();
            } else {
                line.write(b);
            }
        }
    }
}
