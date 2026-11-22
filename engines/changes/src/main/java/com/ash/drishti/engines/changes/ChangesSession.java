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
package com.ash.drishti.engines.changes;

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.Missing;
import com.ash.engine.Request;
import com.ash.engine.Scan;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Steps;
import com.ash.engine.TableSchema;
import com.ash.engine.Tables;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One session of the changes engine: holds the inputs' handles only; every ask reads them as the caller (the host applies the caller's
 * row rules to {@code then}, {@code now}, {@code events} and {@code drivers}, so a person never sees a change of a subject they may not).
 */
final class ChangesSession implements Session, Explaining {

    /** A subject and measure: the key a value is compared by. */
    record Key(String kind, String subject, String measure) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int c = kind.compareTo(o.kind);
            if (c == 0) {
                c = subject.compareTo(o.subject);
            }
            return c == 0 ? measure.compareTo(o.measure) : c;
        }
    }

    /** One driver of a move: its name and the part of the move it accounts for. */
    record Driver(String name, double delta) {}

    /** One answered row, before it is written to the table. */
    record Row(Key key, String kind, Double then, Double now, double change, String why, long at) {}

    private final EngineContext context;
    private final TableSchema schema;
    private final Snapshot snapshot;
    private final Inputs inputs;

    ChangesSession(EngineContext context, TableSchema schema, Snapshot snapshot, Inputs inputs) {
        this.context = context;
        this.schema = schema;
        this.snapshot = snapshot;
        this.inputs = inputs;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        return 0;
    }

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        List<Missing> missing = missing(request);
        if (!missing.isEmpty()) {
            return Answers.notFound(context, this, request, missing);
        }
        List<Row> rows = compute(request, control);
        int limit = ((Number) request.param("limit")).intValue();
        Tables.Builder out = Tables.of(schema);
        int n = 0;
        for (Row r : rows) {
            if (n++ >= limit) {
                break;
            }
            out.row(r.key().kind(), r.key().subject(), r.key().measure(), r.kind(), r.then(), r.now(), r.change(), r.why(), "WHY " + r.key().subject());
        }
        return Answers.complete(context, this, request, List.of(out.build()));
    }

    private List<Missing> missing(Request request) {
        List<Missing> out = new ArrayList<>();
        for (String name : List.of("then", "now")) {
            InputTable t = inputs.table(name);
            List<String> lacking = !t.present() ? List.of() : List.of("SUBJECT_KIND", "SUBJECT", "MEASURE", "VALUE").stream().filter(c -> !t.columns().contains(c)).toList();
            if (!t.present() || !lacking.isEmpty()) {
                out.add(new Missing(name, "drishti/measure-value@1", snapshot, lacking, request.outputs()));
            }
        }
        return out;
    }

    private List<Row> compute(Request request, Control control) throws EngineException {
        double minAbs = ((Number) request.param("min-abs")).doubleValue();
        double minRel = ((Number) request.param("min-rel")).doubleValue();
        Map<String, double[]> thresholds = thresholds(control);
        Map<Key, Double> then = values("then", request, control);
        Map<Key, Double> now = values("now", request, control);
        Map<Key, List<Driver>> drivers = drivers(request, control);
        List<Row> rows = new ArrayList<>();
        Map<Key, Double> all = new TreeMap<>();
        then.keySet().forEach(k -> all.put(k, 0d));
        now.keySet().forEach(k -> all.put(k, 0d));
        for (Key k : all.keySet()) {
            control.check();
            double[] th = thresholds.getOrDefault(k.measure(), new double[] {minAbs, minRel});
            Double a = then.get(k);
            Double b = now.get(k);
            if (a != null && b != null) {
                double change = b - a;
                boolean big = Math.abs(change) > 0 && Math.abs(change) >= th[0] && (a == 0 || Math.abs(change) / Math.abs(a) >= th[1]);
                if (big) {
                    rows.add(new Row(k, "move", a, b, change, why(change, drivers.get(k)), 0));
                }
            } else if (b != null) {
                if (Math.abs(b) >= th[0]) {
                    rows.add(new Row(k, "new", null, b, b, why(b, drivers.get(k)), 0));
                }
            } else if (a != null && Math.abs(a) >= th[0]) {
                rows.add(new Row(k, "removed", a, null, -a, why(-a, drivers.get(k)), 0));
            }
        }
        rows.sort(Comparator.comparingDouble((Row r) -> -Math.abs(r.change())).thenComparing(Row::key));
        List<Row> events = events(request, control);
        rows.addAll(events);
        return rows;
    }

    /** The drivers of a move, up to three by size, with their shares of the move; the remainder is said, never hidden. */
    static String why(double change, List<Driver> drivers) {
        if (drivers == null || drivers.isEmpty() || change == 0) {
            return "No attribution supplied: the move is unexplained";
        }
        List<Driver> sorted = new ArrayList<>(drivers);
        sorted.sort(Comparator.comparingDouble((Driver d) -> -Math.abs(d.delta())).thenComparing(Driver::name));
        StringBuilder sb = new StringBuilder();
        double explained = 0;
        for (Driver d : drivers) {
            explained += d.delta();
        }
        for (int i = 0; i < sorted.size() && i < 3; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sorted.get(i).name()).append(' ').append(percent(sorted.get(i).delta() / change));
        }
        double rest = 1 - explained / change;
        if (Math.abs(rest) >= 0.05) {
            sb.append(", unexplained ").append(percent(rest));
        }
        return sb.toString();
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%d%%", Math.round(share * 100));
    }

    private Map<String, double[]> thresholds(Control control) throws EngineException {
        Map<String, double[]> out = new TreeMap<>();
        InputTable t = inputs.table("thresholds");
        if (!t.present() || !t.columns().containsAll(List.of("MEASURE", "MIN_ABS", "MIN_REL"))) {
            return out;
        }
        t.scan(new Scan(List.of("MEASURE", "MIN_ABS", "MIN_REL"), new Filter.All(), 1_024), b -> {
            control.check();
            String[] m = b.texts("MEASURE");
            double[] a = b.numbers("MIN_ABS");
            double[] r = b.numbers("MIN_REL");
            for (int i = 0; i < b.rows(); i++) {
                if (m[i] != null) {
                    out.put(m[i], new double[] {b.isNull("MIN_ABS", i) ? 0 : a[i], b.isNull("MIN_REL", i) ? 0 : r[i]});
                }
            }
        });
        return out;
    }

    private Map<Key, Double> values(String input, Request request, Control control) throws EngineException {
        Map<Key, Double> out = new TreeMap<>();
        InputTable t = inputs.table(input);
        t.scan(new Scan(List.of("SUBJECT_KIND", "SUBJECT", "MEASURE", "VALUE"), see(request, input), 4_096), b -> {
            control.check();
            String[] k = b.texts("SUBJECT_KIND");
            String[] s = b.texts("SUBJECT");
            String[] m = b.texts("MEASURE");
            double[] v = b.numbers("VALUE");
            for (int i = 0; i < b.rows(); i++) {
                if (k[i] != null && s[i] != null && m[i] != null && !b.isNull("VALUE", i)) {
                    out.merge(new Key(k[i], s[i], m[i]), v[i], Double::sum);
                }
            }
        });
        return out;
    }

    private Map<Key, List<Driver>> drivers(Request request, Control control) throws EngineException {
        Map<Key, List<Driver>> out = new TreeMap<>();
        InputTable t = inputs.table("drivers");
        List<String> cols = List.of("SUBJECT_KIND", "SUBJECT", "MEASURE", "DRIVER", "DELTA");
        if (!t.present() || !t.columns().containsAll(cols)) {
            return out;
        }
        t.scan(new Scan(cols, see(request, "drivers"), 4_096), b -> {
            control.check();
            String[] k = b.texts("SUBJECT_KIND");
            String[] s = b.texts("SUBJECT");
            String[] m = b.texts("MEASURE");
            String[] d = b.texts("DRIVER");
            double[] x = b.numbers("DELTA");
            for (int i = 0; i < b.rows(); i++) {
                if (k[i] != null && s[i] != null && m[i] != null && d[i] != null && !b.isNull("DELTA", i)) {
                    out.computeIfAbsent(new Key(k[i], s[i], m[i]), q -> new ArrayList<>()).add(new Driver(d[i], x[i]));
                }
            }
        });
        return out;
    }

    private List<Row> events(Request request, Control control) throws EngineException {
        List<Row> out = new ArrayList<>();
        InputTable t = inputs.table("events");
        List<String> cols = List.of("SUBJECT_KIND", "SUBJECT", "KIND", "AT", "TEXT");
        if (!t.present() || !t.columns().containsAll(cols)) {
            return out;
        }
        t.scan(new Scan(cols, see(request, "events"), 4_096), b -> {
            control.check();
            String[] k = b.texts("SUBJECT_KIND");
            String[] s = b.texts("SUBJECT");
            String[] kind = b.texts("KIND");
            long[] at = b.integers("AT");
            String[] text = b.texts("TEXT");
            for (int i = 0; i < b.rows(); i++) {
                if (k[i] != null && s[i] != null && kind[i] != null) {
                    out.add(new Row(new Key(k[i], s[i], "Event"), kind[i], null, null, 0, text[i] == null ? "" : text[i], b.isNull("AT", i) ? 0 : at[i]));
                }
            }
        });
        out.sort(Comparator.comparingLong(Row::at).thenComparing(Row::key).thenComparing(Row::kind));
        return out;
    }

    private static Filter see(Request request, String input) throws EngineException {
        Filter f = request.visibility() == null ? null : request.visibility().rows().get(input);
        if (f == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of " + input + " it may read");
        }
        return f;
    }

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        String subject = cell.key().get("SUBJECT");
        String kind = cell.key().get("SUBJECT_KIND");
        String measure = cell.key().get("MEASURE");
        if (!missing(request).isEmpty() || subject == null || kind == null || measure == null || !"changes".equals(cell.output()) || !"CHANGE".equals(cell.column())) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "only CHANGE of changes, for a subject and measure in the answer, is explained");
        }
        Key key = new Key(kind, subject, measure);
        Double a = values("then", request, control).get(key);
        Double b = values("now", request, control).get(key);
        List<Driver> ds = drivers(request, control).get(key);
        if (a == null && b == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the subject " + subject + " and measure " + measure + " are in neither input");
        }
        double change = (b == null ? 0 : b) - (a == null ? 0 : a);
        List<com.ash.engine.Step> steps = new ArrayList<>();
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("then", a);
        given.put("now", b);
        steps.add(Steps.of("move", "Now less then, where a value is missing the move is its whole size", "now - then", given, change));
        if (ds != null) {
            List<Driver> sorted = new ArrayList<>(ds);
            sorted.sort(Comparator.comparingDouble((Driver d) -> -Math.abs(d.delta())).thenComparing(Driver::name));
            double sum = 0;
            for (Driver d : sorted) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("delta", d.delta());
                g.put("move", change);
                steps.add(Steps.of("driver-" + d.name(), "Share of the move accounted for by " + d.name(), "delta / move", g, change == 0 ? 0d : d.delta() / change));
                sum += d.delta();
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("drivers", sum);
            g.put("move", change);
            steps.add(Steps.of("unexplained", "The part of the move no driver accounts for", "move - sum(drivers)", g, change - sum));
        }
        return new Explanation(cell, change, steps);
    }

    @Override
    public void close() {
        // nothing is held between asks
    }
}
