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
package com.ash.drishti.bicompute.scale;

import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.functions.Kll;
import com.ash.drishti.bicompute.functions.Sketches;
import com.ash.drishti.bicompute.spi.Kind;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Progressive answers (ARCHITECTURE.md section 23.9.8, decision 7): a view estimated over {@code after-ms} (2 s) answers twice, first ≈ from a
 * sample of the rows, then exact. The first answer is honest or silent:
 *
 * <ul>
 *   <li>a sum or a count is the sample's figure scaled by 1/f with its 95% interval (Horvitz–Thompson for a Bernoulli sample: the variance of
 *       Σx/f is (1 − f)/f² · Σx² over the sample, Σx² from the sample's own count, mean and variance, computed beside it);</li>
 *   <li>an average is the sample's, with ±1.96·s/√n;</li>
 *   <li>a sketched percentile is the sample's, its rank interval the sketch's error plus the sample's (Dvoretzky–Kiefer–Wolfowitz at 95%);</li>
 *   <li>everything else (min, max, a variance, a distinct count, any holistic measure) says "computing" until the exact answer: never a
 *       figure guessed or split by proportions.</li>
 * </ul>
 *
 * Never for VaR or any view with a holistic measure: it waits for exact (decision 7). The sampled answer is never cached.
 */
public final class Progressive {

    /**
     * {@code drishti.pivot.progressive}.
     *
     * @param enabled on by default
     * @param afterMs a view estimated over this answers ≈ first
     * @param sampleFraction the share of rows the first answer reads
     */
    public record Settings(boolean enabled, long afterMs, double sampleFraction) {
        public Settings {
            afterMs = afterMs <= 0 ? 2_000 : afterMs;
            sampleFraction = sampleFraction <= 0 || sampleFraction >= 1 ? 0.02 : sampleFraction;
        }

        public static Settings defaults() {
            return new Settings(true, 2_000, 0.02);
        }
    }

    private static final String COMPANION = "≈";

    private final PivotCore core;
    private volatile Settings settings;

    public Progressive(PivotCore core, Settings settings) {
        this.core = core;
        this.settings = settings == null ? Settings.defaults() : settings;
    }

    public Settings settings() {
        return settings;
    }

    public void settings(Settings s) {
        this.settings = s == null ? Settings.defaults() : s;
    }

    /**
     * Why this view gets no ≈ first answer, or null when it does: off, not asked, under {@code after-ms}, a VaR or holistic measure.
     */
    public String refusal(Viewer v, PivotView view, ScaleOptions o) {
        if (!settings.enabled()) {
            return "progressive answers are off (drishti.pivot.progressive.enabled)";
        }
        if (o != null && !o.progressive()) {
            return "not asked";
        }
        PivotProvider p = core.provider(view.source());
        if ("var".equals(p.id())) {
            return "VaR is never sampled: it shows when exact";
        }
        boolean exact = o != null && o.exactness() == ScaleOptions.Exactness.EXACT;
        PivotView prepared = p.prepare(v, view);
        for (PivotView.Value val : prepared.values()) {
            FunctionCall f = parsed(val, p);
            if (f != null && f.fn().kind() == Kind.HOLISTIC && (exact || !Sketches.hasSketch(f.fn()))) {
                return f.label() + " is holistic: it is never sampled, the answer shows when exact";
            }
        }
        Map<String, Object> e = core.estimate(v, view);
        double secs = e.get("seconds") instanceof Number n ? n.doubleValue() : 0;
        if (secs * 1000 <= settings.afterMs()) {
            return String.format(Locale.ROOT, "estimated at %.1f s, under %d ms (drishti.pivot.progressive.after-ms)", secs, settings.afterMs());
        }
        return null;
    }

    /**
     * Both answers, the ≈ one first ({@code sink} gets it, then the exact one), or only the exact one when the view gets no ≈ answer.
     * The exact answer is returned too.
     */
    public Map<String, Object> run(Viewer v, PivotView view, ScaleOptions o, Consumer<Map<String, Object>> sink) {
        String no = refusal(v, view, o);
        if (no == null) {
            Map<String, Object> first = approximate(v, view, o);
            sink.accept(first);
        }
        Map<String, Object> exact = core.query(v, view, o == null ? ScaleOptions.AUTO : o);
        exact.put("progressive", Map.of("phase", "exact", "words", no == null ? "exact: it replaces the ≈ answer" : "exact (" + no + ")"));
        sink.accept(exact);
        return exact;
    }

    /** The ≈ answer from a sample of {@code sample-fraction} of the rows, every figure with its interval or "computing". */
    @SuppressWarnings("unchecked")
    public Map<String, Object> approximate(Viewer v, PivotView view, ScaleOptions o) {
        PivotProvider p = core.provider(view.source());
        List<PivotView.Value> vals = new ArrayList<>(view.values());
        Map<String, String[]> companions = new LinkedHashMap<>();       // label -> {function, field, count label, var label}
        List<PivotView.Value> prepared = p.prepare(v, view).values();   // a document source's paths and once-per-parent forms, one per value
        int i = 0;
        for (PivotView.Value val : view.values()) {
            FunctionCall f = i < prepared.size() ? parsed(prepared.get(i), p) : null;
            String fn = f == null ? "" : f.fn().name();
            Matcher one = ONE_ARG.matcher(val.measure());
            if (("sum".equals(fn) || "avg".equals(fn)) && f.inputs().size() == 1 && one.matches()) {
                String x = one.group(2).strip();                       // the argument as the view wrote it (a field or a path)
                String nl = COMPANION + "n" + i;
                String vl = COMPANION + "v" + i;
                vals.add(new PivotView.Value("count(" + x + ")", nl, null));
                vals.add(new PivotView.Value("var_pop(" + x + ")", vl, null));
                companions.put(val.label(), new String[] {fn, x, nl, vl});
            } else {
                companions.put(val.label(), new String[] {fn, null, null, null});
            }
            i++;
        }
        Map<String, Object> am = new LinkedHashMap<>(view.toMap());
        List<Map<String, Object>> vm = new ArrayList<>();
        for (PivotView.Value val : vals) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("measure", val.measure());
            if (val.as() != null) {
                x.put("as", val.as());
            }
            if (val.showAs() != null) {
                x.put("showAs", val.showAs());
            }
            vm.add(x);
        }
        am.put("values", vm);
        PivotView augmented = PivotView.parse(am);
        ScaleOptions so = new ScaleOptions(ScaleOptions.Exactness.APPROXIMATE, settings.sampleFraction(), true);   // a sample's quantiles by sketch
        Map<String, Object> a = core.query(v, augmented, so);
        double f = fraction(a, v, view);
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
        for (Map<String, Object> cell : cells.values()) {
            Map<String, Object> values = (Map<String, Object>) cell.get("values");
            Map<String, Object> raw = (Map<String, Object>) cell.get("raw");
            Map<String, Object> intervals = new LinkedHashMap<>();
            Map<String, Object> notes = (Map<String, Object>) cell.computeIfAbsent("notes", k -> new LinkedHashMap<>());
            for (Map.Entry<String, String[]> c : companions.entrySet()) {
                String label = c.getKey();
                String[] how = c.getValue();
                boolean shownAs = raw != null && raw.containsKey(label);
                Double x = num(shownAs ? raw.get(label) : values.get(label));
                Double est = null;
                double[] iv = null;
                String fn = how[0];
                if (x != null) {
                    switch (fn) {
                        case "sum" -> {
                            Double n = num(values.get(how[2]));
                            Double var = num(values.get(how[3]));
                            est = x / f;
                            if (n != null && var != null && n > 0) {
                                double mean = x / n;
                                double sq = n * (var + mean * mean);
                                double hw = 1.96 * Math.sqrt(Math.max(0, (1 - f) * sq)) / f;
                                iv = new double[] {est - hw, est + hw};
                            }
                        }
                        case "count" -> {
                            est = x / f;
                            double hw = 1.96 * Math.sqrt(Math.max(0, (1 - f) * x)) / f;
                            iv = new double[] {est - hw, est + hw};
                        }
                        case "avg" -> {
                            Double n = num(values.get(how[2]));
                            Double var = num(values.get(how[3]));
                            est = x;
                            if (n != null && var != null && n > 1) {
                                double hw = 1.96 * Math.sqrt(var / n);
                                iv = new double[] {est - hw, est + hw};
                            }
                        }
                        case "median", "percentile" -> {
                            est = x;
                            long members = cell.get("members") instanceof Number m ? m.longValue() : 0;
                            double dkw = members > 0 ? Math.sqrt(Math.log(2 / 0.05) / (2.0 * members)) : 1;
                            intervals.put(label, String.format(Locale.ROOT, "rank ±%.1f%%", 100 * (Kll.rankError(200) + dkw)));
                        }
                        default -> est = null;
                    }
                }
                if (est == null && !"median".equals(fn) && !"percentile".equals(fn)) {
                    put(values, raw, label, shownAs, null);
                    notes.put(label, "computing: no honest figure from a sample; the exact answer follows");
                    continue;
                }
                put(values, raw, label, shownAs, est);
                if (iv != null) {
                    intervals.put(label, List.of(iv[0], iv[1]));
                }
            }
            for (String[] how : companions.values()) {
                if (how[2] != null) {
                    values.remove(how[2]);
                    values.remove(how[3]);
                    if (raw != null) {
                        raw.remove(how[2]);
                        raw.remove(how[3]);
                    }
                }
            }
            if (cell.get("members") instanceof Number m) {
                cell.put("members", Math.round(m.doubleValue() / f));
            }
            cell.put("approximate", true);
            if (!intervals.isEmpty()) {
                cell.put("intervals", intervals);
            }
        }
        List<Map<String, Object>> measures = (List<Map<String, Object>>) a.get("measures");
        measures.removeIf(m -> String.valueOf(m.get("label")).startsWith(COMPANION));
        measures.forEach(m -> m.put("exact", false));
        a.put("view", view.toMap());
        a.put("exactness", "approximate");
        a.remove("provenance");
        a.remove("provenanceShared");
        a.put("progressive", Map.of("phase", "approximate", "fraction", f, "words", String.format(Locale.ROOT,
                "≈ from a %.1f%% sample of the rows, each figure with its 95%% interval; the exact answer follows", 100 * f)));
        return a;
    }

    /** The sample's share: measured (sampled rows over the source's rows) where both are known, else the share asked. */
    @SuppressWarnings("unchecked")
    private double fraction(Map<String, Object> a, Viewer v, PivotView view) {
        double f = settings.sampleFraction();
        Object comp = a.get("computed");
        if (comp instanceof Map<?, ?> c && c.get("sampled") instanceof Number s && s.doubleValue() > 0) {
            f = s.doubleValue();
        }
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
        Object grand = null;
        for (Map<String, Object> cell : cells.values()) {
            if (cell.get("coords") instanceof Map<?, ?> m && m.isEmpty()) {
                grand = cell.get("members");
            }
        }
        Object rows = core.estimate(v, view).get("rows");
        if (grand instanceof Number g && rows instanceof Number r && r.longValue() > 0 && view.filters().isEmpty() && g.longValue() > 0) {
            double measured = g.doubleValue() / r.doubleValue();
            if (measured > 0 && measured < 1) {
                f = measured;
            }
        }
        return f;
    }

    private static void put(Map<String, Object> values, Map<String, Object> raw, String label, boolean shownAs, Double x) {
        if (shownAs) {
            raw.put(label, x);
        } else {
            values.put(label, x);
        }
    }

    /** {@code sum(x)} or {@code avg(x)} of one argument, as written. */
    private static final java.util.regex.Pattern ONE_ARG = java.util.regex.Pattern.compile("\\s*(sum|avg)\\s*\\(([^,()]+)\\)\\s*",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** A prepared value as a function call, or null when it is not one of the built-ins (a custom function: "computing" until exact). */
    private static FunctionCall parsed(PivotView.Value val, PivotProvider p) {
        try {
            return Functions.parse(val.measure(), val.label(), p);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
