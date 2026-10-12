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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes the dataset tables behind live-table history (docs/guides/LIVE_BI.md, "History"). A live table with {@code live.history} gets a second table in the
 * model, named by {@code history.table} ({@code history} by default): a {@code ts} column (when the sample was taken), one column for each column it is grouped
 * by (the live table's own type) and one {@code decimal} column for each sampled measure. For each sampled measure it adds a measure {@code <name> history} that
 * adds up that column, so a line of the measure over time is {@code axis: history.ts, values: <name> history}. Nothing here reads data: the engine fills the table.
 */
public final class Histories {

    /** Samples are never closer than this, whatever the dataset asks. */
    public static final Duration MIN_EVERY = Duration.ofSeconds(1);

    private Histories() {}

    /** The name of the generated measure for a sampled measure. */
    public static String measureName(String measure) {
        return measure + " history";
    }

    /** The column a sampled measure has in the history table. */
    public static String columnName(String measure, java.util.Collection<String> taken) {
        return Composition.identifier(measure, taken);
    }

    /** The column each sampled measure has in the history table, in the order of {@code history.measures}. */
    public static Map<String, String> measureColumns(Dataset.History h) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> taken = new ArrayList<>(List.of(Dataset.TS));
        taken.addAll(h.by());
        for (String m : h.measures()) {
            String col = columnName(m, taken);
            taken.add(col);
            out.put(m, col);
        }
        return out;
    }

    /** Adds the history tables and their measures to {@code ds}; problems (an unknown column or measure, a clash of names) are added to {@code out}. */
    static Dataset expand(Dataset ds, List<DatasetParser.Problem> out) {
        Map<String, Dataset.Table> tables = new LinkedHashMap<>(ds.tables());
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>(ds.measures());
        boolean any = false;
        for (Dataset.Table t : ds.tables().values()) {
            Dataset.History h = t.live() == null ? null : t.live().history();
            if (h == null) {
                continue;
            }
            String path = "tables." + t.name() + ".live.history";
            if (tables.containsKey(h.table())) {
                out.add(new DatasetParser.Problem("error", DatasetParser.LIVE_TABLE, path + ".table", "'" + h.table() + "' is already a table of the dataset; name the history table differently (history: { table: ... })"));
                continue;
            }
            Map<String, Dataset.Column> cols = new LinkedHashMap<>();
            cols.put(Dataset.TS, new Dataset.Column(Dataset.TS, Dataset.TS, "timestamp", null, false, null, "when the sample was taken"));
            boolean ok = true;
            for (String by : h.by()) {
                Dataset.Column c = t.columns().get(by);
                if (c == null || c.expr() != null) {
                    out.add(new DatasetParser.Problem("error", DatasetParser.LIVE_TABLE, path + ".by", "'" + by + "' is not a stored column of " + t.name() + " (columns: " + String.join(", ", t.physical().stream().map(Dataset.Column::name).toList()) + ")"));
                    ok = false;
                } else if (Dataset.TS.equals(by)) {
                    out.add(new DatasetParser.Problem("error", DatasetParser.LIVE_TABLE, path + ".by", "'ts' is the history's own time column; group by another column"));
                    ok = false;
                } else {
                    cols.put(by, new Dataset.Column(by, by, c.type(), c.format(), false, null, c.description()));
                }
            }
            List<String> sampled = new ArrayList<>();
            Map<String, String> columnOf = new LinkedHashMap<>();
            for (String m : h.measures()) {
                Dataset.Measure meas = ds.measures().get(m);
                if (meas == null || meas.expr() == null) {
                    out.add(new DatasetParser.Problem("error", DatasetParser.LIVE_TABLE, path + ".measures", "'" + m + "' is not a measure of this dataset (or is a Python measure); history samples measures the engine computes (measures: " + String.join(", ", ds.measures().keySet()) + ")"));
                    ok = false;
                    continue;
                }
                String col = columnName(m, cols.keySet());
                cols.put(col, new Dataset.Column(col, col, "decimal", meas.format(), false, null, "the value of " + m + " at the sample"));
                columnOf.put(m, col);
                sampled.add(m);
            }
            if (h.measures().isEmpty()) {
                out.add(new DatasetParser.Problem("error", DatasetParser.LIVE_TABLE, path + ".measures", "name the measures to sample: measures: [Volume, Average price]"));
                ok = false;
            }
            if (!ok) {
                continue;
            }
            tables.put(h.table(), new Dataset.Table(h.table(), t.connector(), t.kind(), null, Dataset.HISTORY, false, "",
                    "samples of " + String.join(", ", sampled) + " of the live table " + t.name() + ", every " + h.every().toSeconds() + " s, the newest " + h.keep() + " kept", cols, null, t.name()));
            for (String m : sampled) {
                String name = measureName(m);
                if (!measures.containsKey(name)) {
                    measures.put(name, new Dataset.Measure(name, "sum(" + h.table() + "." + columnOf.get(m) + ")", ds.measures().get(m).format(),
                            "the sampled values of " + m + " added up at each sample time; chart it by " + h.table() + "." + Dataset.TS + (h.by().isEmpty() ? "" : " (add " + h.table() + "." + h.by().get(0) + " to see one line per group)"), null));
                }
            }
            any = true;
        }
        if (!any) {
            return ds;
        }
        return new Dataset(ds.name(), ds.version(), ds.title(), ds.description(), ds.sample(), ds.engine(), tables, ds.relationships(), measures, ds.hierarchies(), ds.security(),
                ds.rollups(), ds.refresh(), ds.compose(), ds.derived(), ds.tests());
    }
}
