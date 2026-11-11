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
package com.ash.drishti.engine.history;

import java.util.List;
import java.util.Map;

/**
 * The answer of {@code GET /api/v1/history/{kind}/{id}}: exactly the JSON contract of DATA_ARCHITECTURE.md › History queries: an entity over time, section 8 (the testkit's
 * {@code history/trade-1000015-30.json} is the reference, and the server's tests compare against it field by field).
 */
public record HistoryAnswer(Ref ref, String mnemonic, String title, String shape, String basis, Window window, List<Column> columns,
        List<Part> parts, List<Chart> chart, List<Row> rows, Summary summary, int changedDays, boolean partial, List<Problem> problems,
        boolean truncated, String dataVersion, String cache, long tookMs) {

    public record Ref(String kind, String id) {}

    /**
     * {@code to} is the last day shown: for data days the entity's newest day with data, else the end of the span. {@code missing} are the
     * business days from {@code from} with no data for the entity, up to the newest date the kind's sources hold; {@code loadedTo}, set
     * only when a span ends after that date, is that date (the days after it are not loaded yet). {@code loadedFrom}, set only when a span
     * starts before the entity's first day with data, is that day: the days before it are not missing either.
     */
    public record Window(String asked, String from, String to, int days, String mode, String baseline, List<String> missing, String knownAt,
            String loadedTo, String loadedFrom) {}

    public record Column(String path, String label, String type, String format, boolean masked, boolean key) {}

    public record Part(String path, String label) {}

    public record Chart(String path, String label, String format) {}

    public record Change(String path, String label, String kind, Object before, Object after, Double delta) {}

    public record Row(String date, String dataDate, boolean carried, Map<String, Object> values, Map<String, String> display,
            Map<String, Integer> parts, List<String> changed, List<Change> changes, Integer versions, Boolean restated, Long generation,
            String source, boolean deleted) {}

    public record Summary(String field, Double last, Double first, Double change, Double changePct, Double high, String highDate, Double low,
            String lowDate, Double range, Double average) {
        public static final Summary NONE = new Summary(null, null, null, null, null, null, null, null, null, null, null);
    }

    public record Problem(String code, String source, List<String> days, String message) {}

    /** The same answer with another cache state and time (an answer served from the cache). */
    public HistoryAnswer with(String cacheState, long took) {
        return new HistoryAnswer(ref, mnemonic, title, shape, basis, window, columns, parts, chart, rows, summary, changedDays, partial, problems,
                truncated, dataVersion, cacheState, took);
    }
}
