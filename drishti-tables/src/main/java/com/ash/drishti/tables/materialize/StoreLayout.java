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
package com.ash.drishti.tables.materialize;

import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.def.TableName;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Where an on-load table's files live (docs/architecture/BI_DESIGN.md section 3.4): {@code <store>/<domain>/<name>/v<major>/<business date>/part-*.parquet}
 * under the layer's store ({@code drishti.tables.store-dir}, planned; by default {@code <data>/tables}). The shape is BI's cached layer's,
 * keyed by the table's name and major instead of the dataset's item key, so a new major never reads an old major's files. The files are
 * a cache: rebuilt at will, never the truth, never written into a lake's own tables.
 */
public final class StoreLayout {

    private final Path root;

    public StoreLayout(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** The table's folder for its major. */
    public Path tableDir(TableDef t) {
        if (!TableName.isTable(t.name())) {
            throw new IllegalArgumentException("not a table name: " + t.name());
        }
        return root.resolve(t.domain()).resolve(t.name().substring(t.domain().length() + 1)).resolve("v" + t.major());
    }

    /** The folder of one business date's partition. */
    public Path partition(TableDef t, LocalDate date) {
        return tableDir(t).resolve(date.toString());
    }

    /** A new data file for a partition (its own name, so a query reading the old file goes on; {@link FileSwap#unique}). */
    public Path newFile(TableDef t, LocalDate date) {
        return FileSwap.unique(partition(t, date));
    }

    /**
     * The business dates to drop, oldest first, when a table keeps {@code retention} dates ({@code keep: 30}, a count of business dates) or a
     * period ({@code keep: 90d}, calendar days before {@code today}); none when it keeps everything ({@code retention} null).
     */
    public static List<LocalDate> expired(List<LocalDate> held, String retention, LocalDate today) {
        if (retention == null || retention.isBlank() || held.isEmpty()) {
            return List.of();
        }
        List<LocalDate> sorted = held.stream().sorted().toList();
        String r = retention.strip().toLowerCase(Locale.ROOT);
        if (r.matches("\\d+")) {
            int keep = Integer.parseInt(r);
            return keep >= sorted.size() ? List.of() : sorted.subList(0, sorted.size() - keep);
        }
        if (r.matches("\\d+d")) {
            LocalDate cut = today.minusDays(Long.parseLong(r.substring(0, r.length() - 1)));
            return sorted.stream().filter(d -> d.isBefore(cut)).toList();
        }
        throw new IllegalArgumentException("keep is a number of business dates (30) or of days (90d), not '" + retention + "'");
    }
}
