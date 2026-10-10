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
package com.ash.drishti.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Builds an {@link ArrowScan} from a source's columns ({@link SourcePlugin#columns}): the day-by-day, column-oriented read the sources that
 * promote columns (Delta Lake, DuckDB, PostgreSQL, JSON-lines files) already do for searches, cut into batches with the request's filters
 * applied. It never parses a document. Empty when the source cannot answer every requested path as a column, so the caller reads records
 * instead and says so. A request without dates reads the source's latest day.
 */
public final class ColumnScans {

    /** The most calendar days one scan walks (a guard against a mistyped range). */
    public static final int MAX_DAYS = 800;

    private ColumnScans() {}

    public static Optional<ArrowScan> over(SourcePlugin source, ScanRequest r) {
        if (!source.columnar(r.kind()).containsAll(r.paths())) {
            return Optional.empty();
        }
        boolean dated = source.manifest().capabilities().dated();
        List<LocalDate> days = new ArrayList<>();
        if (!dated || r.from() == null) {
            days.add(null);                                       // undated, or the source's latest day
        } else {
            LocalDate to = r.to() == null ? r.from() : r.to();
            for (LocalDate d = r.from(); !d.isAfter(to) && days.size() < MAX_DAYS; d = d.plusDays(1)) {
                days.add(d);
            }
        }
        return Optional.of(new Walk(source, r, days));
    }

    private static final class Walk implements ArrowScan {
        private final SourcePlugin source;
        private final ScanRequest r;
        private final List<LocalDate> days;
        private final List<String> warnings = new ArrayList<>();
        private int day;
        private ColumnBatch pending;
        private int offset;

        Walk(SourcePlugin source, ScanRequest r, List<LocalDate> days) {
            this.source = source;
            this.r = r;
            this.days = days;
        }

        @Override
        public ColumnBatch next() throws Exception {
            while (true) {
                if (pending != null && offset < pending.rows()) {
                    int end = Math.min(pending.rows(), offset + r.batchRows());
                    ColumnBatch out = slice(pending, offset, end);
                    offset = end;
                    return out;
                }
                if (day >= days.size()) {
                    return null;
                }
                LocalDate d = days.get(day++);
                Optional<ColumnSet> set = source.columns(r.kind(), r.paths(), d == null ? AsOf.LATEST : AsOf.of(d));
                if (set.isEmpty()) {
                    continue;
                }
                if (set.get().incomplete() != null) {
                    warnings.add(set.get().incomplete());
                }
                pending = filtered(set.get());
                offset = 0;
            }
        }

        @Override
        public List<String> warnings() {
            return warnings;
        }

        private ColumnBatch filtered(ColumnSet s) {
            int n = s.size();
            boolean[] keep = new boolean[n];
            Arrays.fill(keep, true);
            int kept = n;
            for (ScanRequest.Filter f : r.filters()) {
                for (int i = 0; i < n; i++) {
                    if (keep[i] && !matches(f, s.value(f.path(), i))) {
                        keep[i] = false;
                        kept--;
                    }
                }
            }
            String[] ids = new String[kept];
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    ids[k++] = s.ids()[i];
                }
            }
            int cols = r.paths().size();
            ColumnBatch.Type[] types = new ColumnBatch.Type[cols];
            Object[] data = new Object[cols];
            for (int c = 0; c < cols; c++) {
                String p = r.paths().get(c);
                if (s.numbers().containsKey(p)) {
                    double[] src = s.numbers().get(p);
                    double[] out = new double[kept];
                    k = 0;
                    for (int i = 0; i < n; i++) {
                        if (keep[i]) {
                            out[k++] = src[i];
                        }
                    }
                    types[c] = ColumnBatch.Type.NUMBER;
                    data[c] = out;
                } else {
                    String[] out = new String[kept];
                    k = 0;
                    for (int i = 0; i < n; i++) {
                        if (keep[i]) {
                            Object v = s.value(p, i);
                            out[k++] = v == null ? null : v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : v.toString();
                        }
                    }
                    types[c] = ColumnBatch.Type.TEXT;
                    data[c] = out;
                }
            }
            return new ColumnBatch(s.businessDate(), ids, r.paths().toArray(new String[0]), types, data);
        }

        private static ColumnBatch slice(ColumnBatch b, int from, int to) {
            if (from == 0 && to == b.rows()) {
                return b;
            }
            Object[] data = new Object[b.data().length];
            for (int c = 0; c < data.length; c++) {
                data[c] = switch (b.types()[c]) {
                    case NUMBER -> Arrays.copyOfRange((double[]) b.data()[c], from, to);
                    case BOOLEAN -> Arrays.copyOfRange((boolean[]) b.data()[c], from, to);
                    case TEXT -> Arrays.copyOfRange((String[]) b.data()[c], from, to);
                };
            }
            return new ColumnBatch(b.businessDate(), Arrays.copyOfRange(b.ids(), from, to), b.names(), b.types(), data);
        }
    }

    static boolean matches(ScanRequest.Filter f, Object value) {
        if (value == null) {
            return false;
        }
        if (f.op() == ScanRequest.Op.IN) {
            return f.values().stream().anyMatch(v -> compare(value, v) == 0);
        }
        int c = compare(value, f.values().get(0));
        return switch (f.op()) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            case IN -> false;
        };
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Number x) {
            try {
                return Double.compare(x.doubleValue(), Double.parseDouble(String.valueOf(b)));
            } catch (NumberFormatException e) {
                return String.valueOf(a).compareTo(String.valueOf(b));
            }
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
