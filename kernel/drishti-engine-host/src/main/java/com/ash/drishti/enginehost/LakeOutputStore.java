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
package com.ash.drishti.enginehost;

import com.ash.drishti.lakehost.LakeRowsLoad;
import com.ash.engine.Answer;
import com.ash.engine.ColumnSchema;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.engine.host.EngineFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A stored output (engine specification 10.4 rule 4): the table's rows written into a lake table through Drishti Lake's loader
 * ({@link LakeRowsLoad}, the one way a host writes a lake), one business date per snapshot: {@code by-snapshot} replaces that date's rows,
 * {@code append} keeps history. Each row carries the answer's stamp in {@code _ENGINE_STAMP} (the provenance column), so the stored table is a
 * kind like any loaded one, with the engine version that made it.
 */
public final class LakeOutputStore implements Outputs.OutputStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<String, String> resolve;
    private final String format;

    /**
     * @param resolve resolves placeholders and anchors a relative path to a named root ({@code ${lake.root}/risk}); the server's environment
     * @param format  {@code delta} or {@code iceberg}
     */
    public LakeOutputStore(Function<String, String> resolve, String format) {
        this.resolve = resolve;
        this.format = format;
    }

    @Override
    public long store(String engine, String output, EngineFile.Store store, Snapshot snapshot, Table table, Answer answer) throws Exception {
        Path lake = Path.of(resolve.apply(store.lake()));
        if (!lake.isAbsolute()) {
            throw new IllegalArgumentException("outputs." + output + ".store.lake must be absolute or anchored to a named root (${lake.root}/...), not " + store.lake());
        }
        String date = snapshot.dimensions().get("date");
        LocalDate day = date == null ? LocalDate.now(java.time.ZoneOffset.UTC) : LocalDate.parse(date);
        String stamp = answer.stamp() == null ? "" : answer.stamp().engine() + " " + answer.stamp().version() + " " + answer.stamp().request();
        List<String> key = table.schema().key();
        List<String> promote = new ArrayList<>();
        List<String> vectors = new ArrayList<>();
        for (ColumnSchema c : table.schema().columns()) {
            (c.carrier() == ColumnSchema.Carrier.LIST ? vectors : promote).add(c.name());
        }
        promote.add("_ENGINE_STAMP");
        List<LakeRowsLoad.Row> rows = new ArrayList<>();
        int i = 0;
        for (Object[] r : Tables.rows(table)) {
            Map<String, Object> doc = new LinkedHashMap<>();
            for (int c = 0; c < r.length; c++) {
                Object v = r[c];
                doc.put(table.schema().columns().get(c).name(), v instanceof double[] d ? Arrays.stream(d).boxed().toList() : v);
            }
            doc.put("_ENGINE_STAMP", stamp);
            String id = key.isEmpty() ? Integer.toString(i) : String.join("|", key.stream().map(k -> String.valueOf(doc.get(k))).toList());
            rows.add(new LakeRowsLoad.Row(id, JSON.writeValueAsString(doc), doc));
            i++;
        }
        String mode = "append".equals(store.mode()) ? "append" : "replace-date";
        return LakeRowsLoad.load(format, lake.resolve(store.table()).toString(), lake.getFileName().toString(), store.table(), day, mode, rows, promote,
                vectors, Map.of("drishti.engine", engine, "drishti.engine.output", output), Map.of());
    }
}
