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
package com.ash.drishti.banking.official;

import com.ash.drishti.lakehost.LakeRowsLoad;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The official results as Iceberg tables (by configuration; Delta is the default): {@code <root>/risk-results/<table>} written by
 * Drishti Lake's loader (no Hadoop; a local folder or {@code s3://}, a commit being an exclusive create of the next metadata file), in the
 * connector's layout (rows {@code id, doc, business_date} and the published columns beside them, an identity partition on
 * {@code business_date}). Append-only by convention: this sink only ever appends (no overwrite operation is offered), a restated date
 * adds rows with the next {@code RESULT_VERSION}. Table properties: the same two as the Delta tables
 * ({@link OfficialSink#properties}): append only and the retention, which Drishti Lake sets as Iceberg's own properties.
 * Text columns are strings and every number a double ({@code RESULT_VERSION} 2 reads 2.0): the layout's two column types.
 */
final class IcebergSink implements OfficialSink {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String root;
    private final Map<String, String> settings;
    private final int retentionDays;

    IcebergSink(String root, Map<String, String> settings, int retentionDays) {
        this.root = root;
        this.settings = settings;
        this.retentionDays = retentionDays;
    }

    @Override
    public String format() {
        return "iceberg";
    }

    @Override
    public String root() {
        return root;
    }

    @Override
    public Map<String, String> settings() {
        return settings;
    }

    @Override
    public long append(String table, LocalDate date, Map<String, String> columns, List<Map<String, Object>> rows,
            Function<Map<String, Object>, String> id) throws IOException {
        sameFormat(table);
        List<LakeRowsLoad.Row> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> cols = new LinkedHashMap<>();
            columns.keySet().forEach(c -> cols.put(c, r.get(c)));
            Map<String, Object> doc = new LinkedHashMap<>(cols);
            doc.put("BUSINESS_DATE", date.toString());
            out.add(new LakeRowsLoad.Row(id.apply(r), JSON.writeValueAsString(doc), cols));
        }
        String where = where(table);
        String path = OfficialSink.local(where) ? OfficialSink.folder(where).toString() : where;
        return LakeRowsLoad.load("iceberg", path, FOLDER, table, date, "append", out, List.copyOf(columns.keySet()), List.of(), OfficialSink.properties(retentionDays), settings);
    }
}
