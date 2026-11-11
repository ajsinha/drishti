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
package com.ash.drishti.server.bi.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A table of the table layer ({@code rachana: 3}, BI_GUIDE.md) as the {@link Dataset} BI's compiler plans over: the one-table dataset of the
 * table's implicit model ({@link com.ash.drishti.server.bi.layer.Standalone}). BI's own dataset files ({@code rachana: 1} and {@code 2}) are not
 * read: {@code drishti.py tables migrate} writes them as tables and models ({@code DRS-9196}). Never throws for a bad document; the problems come
 * back with their DRS code and the path of the offending key.
 */
public final class DatasetParser {

    /** One finding. {@code level} is {@code error} (the dataset cannot be used) or {@code warning}. */
    public record Problem(String level, String code, String path, String message) {
        public boolean error() {
            return "error".equals(level);
        }
    }

    /** The outcome: the dataset when the structure was readable (null otherwise), and every problem found. */
    public record Result(Dataset dataset, List<Problem> problems) {
        public boolean ok() {
            return dataset != null && problems.stream().noneMatch(Problem::error);
        }

        public String firstError() {
            return problems.stream().filter(Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("");
        }
    }

    static final String INVALID = "DRS-9010";
    static final String LIVE_TABLE = "DRS-9050";
    /** A dataset or model in BI's old form, which the server does not read (the migration's command named). */
    public static final String MIGRATE_FIRST = "DRS-9196";
    private static final Pattern SPAN = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    private DatasetParser() {}

    /** The checks of a dataset's expressions, relationships, hierarchies and rollups (what the model layer runs over a flattened model). */
    public static List<Problem> check(Dataset ds) {
        return DatasetValidator.validate(ds);
    }

    /** The dataset with the history tables of its live tables added (problems to {@code out}). */
    public static Dataset withHistories(Dataset ds, List<Problem> out) {
        return Histories.expand(ds, out);
    }

    /** A table of the layer as BI's one-table dataset; any other text is refused with the migration's command. */
    public static Result parse(String yaml) {
        if (com.ash.drishti.server.bi.layer.LayerTables.isTable(yaml)) {
            return com.ash.drishti.server.bi.layer.Standalone.asVersion1(yaml);
        }
        List<Problem> out = new ArrayList<>();
        out.add(new Problem("error", MIGRATE_FIRST, "", "not a table of the table layer (rachana: 3 with table:): a dataset in BI's old form is migrated by "
                + "drishti.py tables migrate (BI_GUIDE.md, the migration)"));
        return new Result(null, out);
    }

    /** {@code 90s}, {@code 15m}, {@code 2h}, {@code 1d} or an ISO-8601 duration; null when it is none of those. */
    public static java.time.Duration span(String text) {
        String t = text == null ? "" : text.trim();
        try {
            if (t.regionMatches(true, 0, "PT", 0, 2) || t.regionMatches(true, 0, "P", 0, 1)) {
                java.time.Duration d = java.time.Duration.parse(t.toUpperCase(java.util.Locale.ROOT));
                return d.isZero() || d.isNegative() ? null : d;
            }
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
        java.util.regex.Matcher m = SPAN.matcher(t);
        if (!m.matches()) {
            return null;
        }
        long v = Long.parseLong(m.group(1));
        java.time.Duration d = switch (m.group(2)) {
            case "ms" -> java.time.Duration.ofMillis(v);
            case "s" -> java.time.Duration.ofSeconds(v);
            case "m" -> java.time.Duration.ofMinutes(v);
            case "h" -> java.time.Duration.ofHours(v);
            default -> java.time.Duration.ofDays(v);
        };
        return d.isZero() ? null : d;
    }

    static Problem error(String path, String message) {
        return new Problem("error", INVALID, path, message);
    }
}
