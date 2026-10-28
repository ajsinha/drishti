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
package com.ash.drishti.server.official;

import com.ash.drishti.plugin.iceberg.IcebergAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The official results as Iceberg tables (by configuration; Delta is the default): {@code <root>/risk-results/<table>} written by
 * drishti-iceberg (no Hadoop; a local folder or {@code s3://}, a commit being an exclusive create of the next metadata file), in the
 * connector's layout (rows {@code id, doc, business_date} and the published columns beside them, an identity partition on
 * {@code business_date}). Append-only by convention: this sink only ever appends (no overwrite operation is offered), a restated date
 * adds rows with the next {@code RESULT_VERSION}. Table properties: snapshots kept {@code retention} days
 * ({@code history.expire.max-snapshot-age-ms}), old metadata files never removed after a commit, and {@code drishti.append-only}.
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

    /** The table properties every append sets (kept as they are when already so). */
    Map<String, String> properties() {
        return Map.of("history.expire.max-snapshot-age-ms", String.valueOf(retentionDays * 86_400_000L),
                "write.metadata.delete-after-commit.enabled", "false", "drishti.append-only", "true");
    }

    @Override
    public long append(String table, LocalDate date, Map<String, String> columns, List<Map<String, Object>> rows,
            Function<Map<String, Object>, String> id) throws IOException {
        Map<String, Boolean> promoted = new LinkedHashMap<>();
        columns.forEach((c, t) -> promoted.put(c, "double".equals(t) || "long".equals(t)));
        List<IcebergAppender.Row> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> cols = new LinkedHashMap<>();
            columns.keySet().forEach(c -> cols.put(c, r.get(c)));
            Map<String, Object> doc = new LinkedHashMap<>(cols);
            doc.put("BUSINESS_DATE", date.toString());
            out.add(new IcebergAppender.Row(id.apply(r), JSON.writeValueAsString(doc), cols));
        }
        Map<String, String> s = new HashMap<>(settings);
        s.put("root", OfficialSink.local(root) ? OfficialSink.folder(root).toString() : root);
        return IcebergAppender.append(s, FOLDER, table, date, out, promoted, Set.of(), properties());
    }
}
