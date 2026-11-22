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
package com.ash.drishti.lakehost;

import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.ScanRequest;
import com.ash.lake.read.Bounds;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.DayFiles;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.Expr;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * A connector's {@code where} pushed down to a lake table (docs/guides/DATA_LAKE_GUIDE.md, "Several source systems: shared or separate
 * tables"): the comparisons of a promoted column with text constants ({@code SOURCE_SYSTEM = 'ENDUR'}, {@code DESK in ('D1', 'D2')}) leave
 * out the date's files whose partition value or text statistics show none of the values, and the rows of the files read that do not
 * match, through Drishti Lake's read API ({@link TableSnapshot#day} for the files, {@link TableSnapshot#scanFile} for the rows). The
 * connector still tests every row it is given with the whole predicate, so what is pushed down only ever leaves out rows the predicate
 * would drop: the answer is the same as filtering after a whole read.
 *
 * <p>Nothing is pushed down (a {@code null} plan: the whole date is read as before, from its caches) when no comparison is usable, when the
 * date has deleted rows (deletion vectors, delete files) or when every file may hold a matching row. A statistic of 32 characters or more
 * may be a truncated prefix and is not trusted. Thread-safe; the counters are for Health and the tests.
 */
public final class WherePushdown {

    /** Text statistics at least this long may be cut (Spark's and delta-rs' prefix length): not trusted to leave a file out. */
    static final int TRUSTED_BOUND = 32;

    /** One date's read under a where: the files to read (indexes into the date's files) and the row test for them. */
    public record Plan(DayFiles day, int[] files, Expr rows) {
        /** The date's files left out. */
        public int skipped() {
            return day.files().size() - files.length;
        }
    }

    private final AtomicLong filesRead = new AtomicLong();
    private final AtomicLong filesSkipped = new AtomicLong();
    private final AtomicLong rowsReturned = new AtomicLong();

    /**
     * The plan of a read of {@code day} under {@code where} (paths as the documents name them), or null when nothing can be left out.
     *
     * @param promoted document path to the table's column, for the paths the table keeps as columns
     */
    public static Plan plan(TableSnapshot l, DayFiles day, List<ScanRequest.Filter> where, Map<String, String> promoted) {
        if (where == null || where.isEmpty() || day == null || day.deletes() || day.files().isEmpty()) {
            return null;
        }
        Map<String, Set<String>> wanted = com.ash.drishti.api.TextEqualities.of(where, promoted::get);   // column -> the text values a row may hold
        if (wanted.isEmpty()) {
            return null;
        }
        List<Integer> keep = new ArrayList<>();
        List<DataFile> files = day.files();
        for (int i = 0; i < files.size(); i++) {
            if (mayHold(files.get(i), wanted)) {
                keep.add(i);
            }
        }
        if (keep.size() == files.size()) {
            return null;
        }
        Map<String, ColumnType> types = l.columns().types();
        List<Expr> tests = new ArrayList<>();
        wanted.forEach((column, values) -> {
            boolean partition = files.stream().anyMatch(f -> f.partition().containsKey(column));
            if (types.get(column) == ColumnType.STRING && !partition) {     // a partition column was decided by the file's value already
                tests.add(values.size() == 1 ? Expr.eq(column, values.iterator().next()) : Expr.in(column, values));
            }
        });
        Expr rows = tests.isEmpty() ? Expr.TRUE : tests.size() == 1 ? tests.get(0) : Expr.and(tests.toArray(Expr[]::new));
        return new Plan(day, keep.stream().mapToInt(Integer::intValue).toArray(), rows);
    }

    /** Whether the file may hold a row whose every wanted column holds one of its values (by its partition value, else its statistics). */
    static boolean mayHold(DataFile f, Map<String, Set<String>> wanted) {
        for (Map.Entry<String, Set<String>> w : wanted.entrySet()) {
            String part = f.partition().get(w.getKey());
            if (part != null) {
                if (!w.getValue().contains(part)) {
                    return false;
                }
                continue;
            }
            Bounds b = f.bounds().get(w.getKey());
            if (b == null || b.min() == null || b.max() == null || b.min().length() >= TRUSTED_BOUND || b.max().length() >= TRUSTED_BOUND) {
                continue;                                              // nothing known of the column in this file: it may hold any value
            }
            boolean any = false;
            for (String v : w.getValue()) {
                any |= b.mayHold(v, v, false);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /**
     * The asked columns of the plan's files, as {@link TableSnapshot#columns} gives them for the whole date (ids, numbers, texts; the
     * same rows in the same order, less the rows the plan leaves out), or null when a column's type is not one read the same way here
     * (then the whole date is read).
     *
     * @param asks path to column, and whether it is read as numbers
     */
    public ColumnSet columns(TableSnapshot l, Plan p, Map<String, String> asks, Set<String> numeric) throws IOException {
        Map<String, ColumnType> types = l.columns().types();
        for (Map.Entry<String, String> a : asks.entrySet()) {
            ColumnType t = types.get(a.getValue());
            boolean number = numeric.contains(a.getKey());
            if (number ? !(t == ColumnType.DOUBLE || t == ColumnType.LONG || t == ColumnType.INT) : t != ColumnType.STRING) {
                return null;
            }
        }
        List<String> wanted = new ArrayList<>();
        wanted.add(l.idColumn());
        wanted.addAll(asks.values());
        List<String> ids = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int file : p.files()) {
            l.scanFile(p.day().date(), file, wanted, p.rows(), v -> {
                ids.add(v[0] == null ? "" : v[0].toString());
                rows.add(v.clone());                                  // the reader reuses its array
            });
        }
        count(p, rows.size());
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        int c = 1;
        for (String path : asks.keySet()) {
            int at = c++;
            if (numeric.contains(path)) {
                double[] out = new double[rows.size()];
                for (int r = 0; r < out.length; r++) {
                    out[r] = rows.get(r)[at] instanceof Number n ? n.doubleValue() : Double.NaN;
                }
                nums.put(path, out);
            } else {
                String[] out = new String[rows.size()];
                for (int r = 0; r < out.length; r++) {
                    out[r] = rows.get(r)[at] == null ? null : rows.get(r)[at].toString();
                }
                texts.put(path, out);
            }
        }
        LocalDate d = p.day().date();
        return new ColumnSet(ids.toArray(String[]::new), nums, texts, LocalDate.MIN.equals(d) ? null : d);
    }

    /**
     * The documents of the plan's files as stored ({@link TableSnapshot#documents} for the files read), or false when the table does not
     * keep its documents whole in {@code docColumn} (then the whole date is read).
     */
    public boolean documents(TableSnapshot l, Plan p, String docColumn, BiConsumer<String, String> sink) throws IOException {
        if (l.columns().indexOf(docColumn) < 0 || !l.documentFields().isEmpty()) {
            return false;
        }
        List<String> wanted = List.of(l.idColumn(), docColumn);
        long[] n = {0};
        for (int file : p.files()) {
            l.scanFile(p.day().date(), file, wanted, p.rows(), v -> {
                if (v[0] != null && v[1] != null) {
                    n[0]++;
                    sink.accept(v[0].toString(), v[1].toString());
                }
            });
        }
        count(p, n[0]);
        return true;
    }

    private void count(Plan p, long rows) {
        filesRead.addAndGet(p.files().length);
        filesSkipped.addAndGet(p.skipped());
        rowsReturned.addAndGet(rows);
    }

    /** What the pushed-down reads read and left out since start: {@code filesRead}, {@code filesSkipped}, {@code rowsReturned}. */
    public Map<String, Long> stats() {
        return Map.of("filesRead", filesRead.get(), "filesSkipped", filesSkipped.get(), "rowsReturned", rowsReturned.get());
    }
}
