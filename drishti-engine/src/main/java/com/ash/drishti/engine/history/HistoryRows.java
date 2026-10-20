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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.HistoryDay;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Turns the days a history read gave into the answer's columns, rows, changes and summary (HISTORY_QUERIES.md sections 5, 7.3, 8). Each
 * day's document is redacted for the caller <b>before</b> any column is projected, any change computed or any summary taken, so a masked
 * field reads as the mask on every row, never shows as changed and cannot be probed by the headline numbers.
 */
public final class HistoryRows {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final int DIFF_LIMIT = 2000;

    /** The columns, rows and summary built. {@code rows} are oldest first and exclude the baseline. */
    public record Built(List<HistoryAnswer.Column> columns, List<HistoryAnswer.Part> parts, List<HistoryAnswer.Chart> chart,
            List<HistoryAnswer.Row> rows, HistoryAnswer.Summary summary, int changedDays, String baseline, String title) {}

    private final HistorySpec spec;
    private final List<String> keyColumns;
    private final Formats formats;
    private final ElCompiler el;
    private final UnaryOperator<DataNode> redact;
    private final Predicate<String> masked;
    private final int maxColumns;

    /**
     * @param keyColumns the kind's key fields ({@code drishti.search.columns}), shown first
     * @param redact the caller's masks applied to a document
     * @param masked true when a top-level field is masked for the caller
     */
    public HistoryRows(HistorySpec spec, List<String> keyColumns, Formats formats, ElCompiler el, UnaryOperator<DataNode> redact,
            Predicate<String> masked, int maxColumns) {
        this.spec = spec;
        this.keyColumns = keyColumns.stream().map(c -> c.startsWith("$.") ? c.substring(2) : c).toList();
        this.formats = formats;
        this.el = el;
        this.redact = redact;
        this.masked = masked;
        this.maxColumns = maxColumns;
    }

    /**
     * @param days the days read, oldest first
     * @param baselineFirst true when the oldest day is the baseline (compared with, not shown)
     * @param wanted the fields asked for ({@code fields=}), or empty for every column
     * @param diff true to add each row's {@link DocumentDiff} changes
     */
    public Built build(List<HistoryDay> days, boolean baselineFirst, Set<String> wanted, boolean diff) {
        List<DataNode> docs = new ArrayList<>(days.size());
        for (HistoryDay d : days) {
            docs.add(d.document().deleted() ? DataNode.missing() : redact.apply(d.document().data()));
        }
        List<Col> cols = columns(docs, wanted);
        List<String> partPaths = parts(docs);
        Set<String> ignore = spec.ignoreChanges();
        DocumentDiff differ = diff ? new DocumentDiff(DIFF_LIMIT) : null;

        List<HistoryAnswer.Row> rows = new ArrayList<>();
        Map<String, Object> prevValues = null;
        Map<String, DataNode> prevParts = null;
        DataNode prevDoc = null;
        boolean prevSeries = false;
        int changedDays = 0;
        for (int i = 0; i < days.size(); i++) {
            HistoryDay day = days.get(i);
            DataNode doc = docs.get(i);
            Map<String, Object> values = new LinkedHashMap<>();
            Map<String, String> display = new LinkedHashMap<>();
            for (Col c : cols) {
                Object v = day.document().deleted() ? null : c.value(doc);
                values.put(c.key, v);
                display.put(c.key, display(v, c.format));
            }
            boolean series = HistoryService.SERIES.equals(day.document().provenance().source());
            Map<String, DataNode> partNodes = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String p : partPaths) {
                DataNode n = doc.get(p);
                partNodes.put(p, n);
                // a row from an embedded series holds the series' fields only: its parts were not read that day, which is not zero (QA1-15)
                counts.put(p, n.isMasked() || series && n.isMissing() ? null : Integer.valueOf(n.isNull() || n.isMissing() ? 0 : n.size()));
            }
            List<String> changed = new ArrayList<>();
            if (prevValues != null) {
                // beside a row from an embedded series only the series' own fields compare: the rest is not known on that day
                boolean either = series || prevSeries;
                DataNode seriesDoc = series ? doc : prevDoc;
                for (Col c : cols) {
                    if (either && seriesDoc.get(c.key).isMissing()) {
                        continue;
                    }
                    if (!ignore.contains(c.key) && !c.masked && !Values.equal(prevValues.get(c.key), values.get(c.key))) {
                        changed.add(c.key);
                    }
                }
                for (String p : partPaths) {
                    if (!either && !ignore.contains(p) && !Objects.equals(prevParts.get(p), partNodes.get(p))) {
                        changed.add("parts." + p);
                    }
                }
            }
            List<HistoryAnswer.Change> changes = null;
            if (differ != null && prevDoc != null) {
                DataNode only = series ? doc : prevSeries ? prevDoc : null;          // beside a series row: its fields only
                changes = differ.diff(prevDoc, doc).changes().stream().filter(c -> !ignored(c.path(), ignore))
                        .filter(c -> only == null || !only.get(top(c.path())).isMissing())
                        .map(c -> new HistoryAnswer.Change(c.path(), label(c.path()), c.kind().name().toLowerCase(Locale.ROOT), c.before(), c.after(), c.delta()))
                        .toList();
            } else if (differ != null) {
                changes = List.of();
            }
            boolean shown = !(baselineFirst && i == 0);
            if (shown) {
                if (!changed.isEmpty()) {
                    changedDays++;
                }
                var pv = day.document().provenance();
                rows.add(new HistoryAnswer.Row(day.date().toString(), pv.businessDate() == null ? day.date().toString() : pv.businessDate().toString(),
                        false, values, display, counts, List.copyOf(changed), changes, day.versions(),
                        day.versions() == null ? null : day.versions() > 1, pv.generation(), pv.source(), day.document().deleted()));
            }
            prevValues = values;
            prevParts = partNodes;
            prevDoc = doc;
            prevSeries = series;
        }
        List<HistoryAnswer.Column> columns = cols.stream()
                .map(c -> new HistoryAnswer.Column(c.key, c.label, c.type(rows), c.format, c.masked, c.isKey)).toList();
        List<HistoryAnswer.Part> parts = partPaths.stream().map(p -> new HistoryAnswer.Part(p, Semantics.humanize(p))).toList();
        List<HistoryAnswer.Chart> chart = spec.chart().stream()
                .map(c -> new HistoryAnswer.Chart(c.key(), c.label() != null ? c.label() : Semantics.humanize(c.key()), c.format())).toList();
        HistoryAnswer.Summary summary = spec.market() ? summary(rows, cols) : HistoryAnswer.Summary.NONE;
        String baseline = baselineFirst && !days.isEmpty() ? days.get(0).date().toString() : null;
        return new Built(columns, parts, chart, rows, summary, changedDays, baseline, title(rows, cols));
    }

    private static boolean ignored(String path, Set<String> ignore) {
        for (String i : ignore) {
            if (path.equals(i) || path.startsWith(i + ".") || path.startsWith(i + "[")) {
                return true;
            }
        }
        return false;
    }

    // ---- columns ----

    private final class Col {
        final String key;
        final String label;
        final String format;
        final boolean masked;
        final boolean isKey;
        final Expr expr;

        Col(String key, String label, String format, boolean masked, boolean isKey, Expr expr) {
            this.key = key;
            this.label = label;
            this.format = format;
            this.masked = masked;
            this.isKey = isKey;
            this.expr = expr;
        }

        Object value(DataNode doc) {
            if (expr != null) {
                try {
                    Object v = Values.simplify(expr.eval(EvalContext.of(doc, formats)));
                    return v instanceof DataNode n ? plain(n) : v;
                } catch (RuntimeException e) {
                    return null;
                }
            }
            return plain(doc.at(key));
        }

        String type(List<HistoryAnswer.Row> rows) {
            String t = null;
            for (HistoryAnswer.Row r : rows) {
                Object v = r.values().get(key);
                if (v == null || DataNode.MASK.equals(v)) {
                    continue;
                }
                String one = v instanceof Number ? "number" : v instanceof Boolean ? "boolean"
                        : ISO_DATE.matcher(String.valueOf(v)).find() ? "date" : "text";
                if (t != null && !t.equals(one)) {
                    return "text";
                }
                t = one;
            }
            return t == null ? "text" : t;
        }
    }

    private static Object plain(DataNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isMasked()) {
            return DataNode.MASK;
        }
        Object v = n.unwrap();
        if (v instanceof BigDecimal b) {
            return b.doubleValue();
        }
        return v instanceof Map || v instanceof List ? null : v;
    }

    private List<Col> columns(List<DataNode> docs, Set<String> wanted) {
        Map<String, Col> out = new LinkedHashMap<>();
        Set<String> exclude = spec.exclude();
        Set<String> partSet = new LinkedHashSet<>(parts(docs));
        if (!spec.columns().isEmpty()) {
            for (HistorySpec.Column c : spec.columns()) {
                add(out, c, keyColumns.contains(c.key()));
            }
        } else {
            for (String k : keyColumns) {
                if (!exclude.contains(top(k)) && !partSet.contains(k)) {
                    add(out, new HistorySpec.Column(k, null, null, null), true);
                }
            }
            for (int i = docs.size() - 1; i >= 0; i--) {                  // the newest document's field order first
                if (docs.get(i) instanceof DataNode.Obj obj) {
                    obj.fields().forEach((k, v) -> {
                        if (!exclude.contains(k) && !partSet.contains(k) && scalar(v)) {
                            add(out, new HistorySpec.Column(k, null, null, null), false);
                        }
                    });
                }
            }
        }
        for (HistorySpec.Column c : spec.chart()) {                        // a chart field is always a column
            add(out, c, keyColumns.contains(c.key()));
        }
        if (!wanted.isEmpty()) {
            // QA1-18: a field asked for that is no column but is in the documents is shown; one in none of them is named, not dropped
            List<String> unknown = new ArrayList<>();
            for (String f : wanted) {
                if (out.containsKey(f)) {
                    continue;
                }
                String t = top(f);
                boolean seen = docs.stream().anyMatch(d -> d instanceof DataNode.Obj o && o.fields().containsKey(t));
                if (seen && t.equals(f) && docs.stream().anyMatch(d -> d instanceof DataNode.Obj o && o.fields().containsKey(f)
                        && scalar(o.fields().get(f)))) {
                    add(out, new HistorySpec.Column(f, null, null, null), keyColumns.contains(f));
                } else if (!seen) {
                    unknown.add(f);
                }
            }
            if (!unknown.isEmpty()) {
                throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.BAD_SEARCH, "no " + spec.kind()
                        + " has " + (unknown.size() == 1 ? "a field " : "the fields ") + String.join(", ", unknown) + " on these days");
            }
        }
        List<Col> list = new ArrayList<>(out.values());
        if (!wanted.isEmpty()) {
            list.removeIf(c -> !wanted.contains(c.key));
        }
        return list.size() > maxColumns ? list.subList(0, maxColumns) : list;
    }

    private void add(Map<String, Col> out, HistorySpec.Column c, boolean isKey) {
        if (out.containsKey(c.key())) {
            return;
        }
        Expr expr = null;
        if (c.expr() != null) {
            try {
                expr = el.compile(c.expr().startsWith("$") || c.expr().contains("(") ? c.expr() : "$." + c.expr());
            } catch (RuntimeException e) {
                return;                                                     // reported by the pack check (DRS-2061)
            }
        }
        String label = c.label() != null ? c.label() : Semantics.humanize(leaf(c.key()));
        // a column with no format of its own reads as its chart field does (price2: 21.66, not 21.657685; QA1-15)
        String format = c.format() != null ? c.format() : spec.chart().stream().filter(x -> x.key().equals(c.key()) && x.format() != null)
                .map(HistorySpec.Column::format).findFirst().orElse(null);
        out.put(c.key(), new Col(c.key(), label, format, c.path() != null && masked.test(top(c.path())), isKey, expr));
    }

    private static boolean scalar(DataNode v) {
        NodeType t = v.type();
        return t != NodeType.OBJECT && t != NodeType.ARRAY;
    }

    private List<String> parts(List<DataNode> docs) {
        if (spec.parts() != null) {
            return spec.parts();
        }
        Set<String> out = new LinkedHashSet<>();
        for (int i = docs.size() - 1; i >= 0; i--) {
            if (docs.get(i) instanceof DataNode.Obj obj) {
                obj.fields().forEach((k, v) -> {
                    if (!spec.exclude().contains(k) && !scalar(v)) {
                        out.add(k);
                    }
                });
            }
        }
        return List.copyOf(out);
    }

    private static String top(String path) {
        int cut = path.length();
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '.' || ch == '[') {
                cut = i;
                break;
            }
        }
        return path.substring(0, cut);
    }

    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    static String label(String path) {
        String last = leaf(path);
        int bracket = last.indexOf('[');
        String field = bracket >= 0 ? last.substring(0, bracket) : last;
        String label = Semantics.humanize(field.isEmpty() ? path : field);
        return bracket >= 0 && last.endsWith("]") ? label + " " + last.substring(bracket) : label;
    }

    // ---- display, summary, title ----

    private String display(Object v, String format) {
        if (v == null) {
            return "";
        }
        if (DataNode.MASK.equals(v)) {
            return DataNode.MASK;
        }
        if (format != null && formats.has(format)) {
            try {
                return formats.format(format, v);
            } catch (RuntimeException e) {
                return Values.text(v);
            }
        }
        if (v instanceof Number n) {
            DecimalFormat f = new DecimalFormat("#,##0.##########", DecimalFormatSymbols.getInstance(Locale.ROOT));
            return f.format(n.doubleValue());
        }
        return Values.text(v);
    }

    private HistoryAnswer.Summary summary(List<HistoryAnswer.Row> rows, List<Col> cols) {
        String field = spec.headlineKey();
        if (field == null || cols.stream().anyMatch(c -> c.key.equals(field) && c.masked)) {
            return HistoryAnswer.Summary.NONE;
        }
        Double first = null;
        Double last = null;
        Double high = null;
        Double low = null;
        String highDate = null;
        String lowDate = null;
        double sum = 0;
        int n = 0;
        for (HistoryAnswer.Row r : rows) {
            if (!(r.values().get(field) instanceof Number num)) {
                continue;
            }
            double v = num.doubleValue();
            if (first == null) {
                first = v;
            }
            last = v;
            if (high == null || v > high) {
                high = v;
                highDate = r.date();
            }
            if (low == null || v < low) {
                low = v;
                lowDate = r.date();
            }
            sum += v;
            n++;
        }
        if (n == 0) {
            return new HistoryAnswer.Summary(field, null, null, null, null, null, null, null, null, null, null);
        }
        double change = last - first;
        Double pct = first == 0 ? null : round(change / Math.abs(first) * 100);
        return new HistoryAnswer.Summary(field, last, first, round(change), pct, high, highDate, low, lowDate, round(high - low), round(sum / n));
    }

    private static double round(double v) {
        return Math.round(v * 1e8) / 1e8;
    }

    private static String title(List<HistoryAnswer.Row> rows, List<Col> cols) {
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, String> last = rows.get(rows.size() - 1).display();
        List<String> parts = new ArrayList<>();
        for (Col c : cols) {
            String s = last.get(c.key);
            if (c.isKey && !c.masked && s != null && !s.isBlank() && parts.size() < 2) {
                parts.add(s);
            }
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }
}
