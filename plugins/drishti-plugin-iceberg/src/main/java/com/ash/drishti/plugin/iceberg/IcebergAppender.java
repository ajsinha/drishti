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
package com.ash.drishti.plugin.iceberg;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appends rows to an Iceberg table in {@link IcebergLayout}, for writers outside the plugin (the server's official results, 2.9.5;
 * intraday micro-batches): one business date's rows written sorted by id into files and committed as one <b>append</b> (nothing is
 * replaced: an append-only table keeps every earlier row), with drishti-iceberg (no Hadoop; local folders and {@code s3://}). The table
 * is created on the first append with the properties given. Thread-safe per call; commits retry on conflicts.
 */
public final class IcebergAppender {

    /**
     * One row: its id, its document as JSON text, and its promoted columns (path to a Number, a String, a list of numbers for a vector
     * column, or null).
     */
    public record Row(String id, String doc, Map<String, Object> columns) {}

    private IcebergAppender() {
    }

    /**
     * Appends {@code rows} of {@code date} to the table {@code table} of {@code domain} under the settings' {@code root} (or REST catalog),
     * creating it with {@code properties} when it does not exist (they are set on an existing table too). Returns the snapshot's sequence
     * number after the commit (it grows with every commit).
     *
     * @param settings the connector settings that name the lake ({@code root}, or {@code catalog: rest} and {@code uri}; {@code s3.*})
     * @param promoted the promoted paths and whether each is a number
     * @param vectors the paths written as {@code list<double>}
     */
    public static long append(Map<String, String> settings, String domain, String table, LocalDate date, List<Row> rows,
            Map<String, Boolean> promoted, Set<String> vectors, Map<String, String> properties) throws IOException {
        Map<String, String> s = new HashMap<>(settings);
        s.put("domain", domain);
        s.putIfAbsent("engine", "native");
        try (LayoutLake lake = LayoutLake.of(s)) {
            LayoutLake.LayoutTable t = lake.open(table, new LinkedHashMap<>(promoted), vectors, IcebergLayout.DEFAULT_ROW_GROUP_BYTES,
                    IcebergLayout.DEFAULT_FILE_ROWS);
            if (!properties.isEmpty()) {
                t.properties(properties);
            }
            List<IcebergLayout.Row> sorted = new ArrayList<>();
            for (Row r : rows) {
                sorted.add(new IcebergLayout.Row(r.id(), r.doc(), r.columns()));
            }
            sorted.sort(Comparator.comparing(IcebergLayout.Row::id));
            List<?> files = t.writeDay(date, sorted.iterator(), IcebergLayout.DEFAULT_FILE_ROWS);
            t.append(files, Map.of("drishti.business-dates", date.toString(), "drishti.operation", "append"));
            return t.sequenceNumber();
        } catch (RuntimeException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }
}
