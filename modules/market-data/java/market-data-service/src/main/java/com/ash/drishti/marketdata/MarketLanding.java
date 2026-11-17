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
package com.ash.drishti.marketdata;

import static com.ash.drishti.kernel.api.module.Answers.day;
import static com.ash.drishti.kernel.api.module.Answers.get;
import static com.ash.drishti.kernel.api.module.Answers.list;
import static com.ash.drishti.kernel.api.module.Answers.map;
import static com.ash.drishti.kernel.api.module.Answers.num;
import static com.ash.drishti.kernel.api.module.Answers.round;
import static com.ash.drishti.kernel.api.module.Answers.str;

import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.module.InlineFigures.Field;
import com.ash.drishti.kernel.api.module.InlineFigures.Figures;
import com.ash.drishti.kernel.api.module.Asker;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Kernel.Refused;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.module.PersonCache;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Drishti Market's Home figures (docs/guides/ADMIN_GUIDE.md › The design; THIN_CONSOLE_DESIGN.md step S8a), worked out here, in the
 * market-data module's server part, from the market-data pack's own answers (the server's search, raw documents, business date and one
 * field's history), read-only, as the person (identity, lens, date box). The console only draws them.
 *
 * <p>Nothing is invented: a figure the data does not hold is null, a day-over-day change is given only when both business days hold real
 * data (a history point whose data date is its date), and the biggest moves say how they were measured ({@code sigma},
 * {@code absolute}) or that the lake has no history ({@code none}). The CDS median, its move over the day and each move's sigma are the
 * pivot engine's ({@link InlineFigures}); with the engine refusing, the figure is null and the reason is in {@code problems}.
 *
 * <p>The checks are the ones the data supports: stale (older than the business date by more than {@code quality.stale-days} business
 * days), gaps (a curve missing a tenor every other curve of its type has), broken discount factors and negative forwards, and the vol
 * surfaces' sanity (no negative vol; total variance rising with expiry where the underlying is the same).
 */
@Service
public class MarketLanding {

    /** The market-data pack's kinds and their commands (packs/market-data/pack.yaml). */
    static final Map<String, String> COMMANDS = Collections.unmodifiableMap(ordered("ir-curve", "CRV", "repo-curve", "REPO", "fx-spot", "FX",
            "fx-forward-curve", "FXF",
            "fx-vol-surface", "FXV", "ir-vol-cube", "IRV", "cap-vol-surface", "CPV", "equity", "EQ", "equity-index", "EQX", "dividend-curve", "DIV",
            "equity-vol-surface", "EQV", "credit-curve", "CDS", "inflation-index", "INF", "inflation-curve", "INFC", "commodity", "CMD",
            "commodity-curve", "CMDC", "commodity-vol-surface", "CMDV", "rate-fixing", "FIX", "bond", "BND", "correlation-matrix", "CORR"));
    /** Kinds read whole for the checks; the others are checked by their latest load. */
    static final List<String> CURVES = List.of("ir-curve", "repo-curve", "credit-curve", "fx-forward-curve", "inflation-curve");
    static final Map<String, String> SURFACES = Collections.unmodifiableMap(ordered("fx-vol-surface", "ATM", "equity-vol-surface", "M_100",
            "commodity-vol-surface", null, "cap-vol-surface", null, "ir-vol-cube", null));
    /** The biggest moves: each kind's headline field, and its unit (bp of a rate held as a fraction, a spread in bp, or percent). */
    static final List<String[]> MOVERS = List.of(new String[] {"ir-curve", "TEN_Y", "bp"}, new String[] {"credit-curve", "SPREAD_5Y", "spread"},
            new String[] {"fx-spot", "MID", "pct"}, new String[] {"commodity", "FRONT", "pct"}, new String[] {"equity-index", "LEVEL", "pct"});
    static final int SIGMA_MIN_DAYS = 10;
    static final Pattern TENOR = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([DWMY])$", Pattern.CASE_INSENSITIVE);

    private final Kernel kernel;
    private final PersonCache<Map<String, Object>> cache;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public MarketLanding(Kernel kernel) {
        this.kernel = kernel;
        this.cache = kernel.cache(200, 120_000);
    }

    public Asker caller() {
        return new Asking(kernel, kernel.person());
    }

    /** One person's questions of the kernel (there is no risk engine to ask here). */
    private record Asking(Kernel kernel, Person person) implements Asker {
        @Override
        public com.ash.drishti.kernel.api.module.RiskBridge risk() {
            return null;
        }

        @Override
        public Asker as(Person p) {
            return new Asking(kernel, p);
        }
    }

    /** Pairs in their order (a value may be null), for a constant to wrap unmodifiable where it is declared (the C12 check reads it there). */
    static <V> Map<String, V> ordered(Object... kv) {
        Map<String, V> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            V v = (V) kv[i + 1];
            out.put((String) kv[i], v);
        }
        return out;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    /** The quality landing's {@code quality.stale-days} of the person's lens (default 2, 0 to 60). */
    public int staleDays(Asker c) {
        Double n = num(kernel.lenses().block(c.lens(), "quality").get("stale-days"));
        return n == null ? 2 : Math.max(0, Math.min(n.intValue(), 60));
    }

    // -- business days ----------------------------------------------------------------------------------------------------------------
    static boolean isBusinessDay(LocalDate d, Set<String> holidays) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d.toString());
    }

    static LocalDate previousBusinessDay(LocalDate d, Set<String> holidays) {
        LocalDate x = d.minusDays(1);
        while (!isBusinessDay(x, holidays)) {
            x = x.minusDays(1);
        }
        return x;
    }

    /** Business days after {@code earlier} up to and including {@code later} (0 when it is not before). */
    static int businessDaysBetween(LocalDate earlier, LocalDate later, Set<String> holidays) {
        int n = 0;
        LocalDate d = earlier;
        while (d.isBefore(later) && n < 400) {
            d = d.plusDays(1);
            if (isBusinessDay(d, holidays)) {
                n++;
            }
        }
        return n;
    }

    /** {@code 1M} → 1/12, {@code 2Y} → 2, {@code 1W} → 7/365; null for a label that is not a tenor. */
    static Double tenorYears(Object t) {
        Matcher mt = TENOR.matcher(str(t).strip());
        if (!mt.matches()) {
            return null;
        }
        double n = Double.parseDouble(mt.group(1));
        return n * switch (mt.group(2).toUpperCase(Locale.ROOT)) {
            case "D" -> 1.0 / 365;
            case "W" -> 7.0 / 365;
            case "M" -> 1.0 / 12;
            default -> 1.0;
        };
    }

    // -- reading -----------------------------------------------------------------------------------------------------------------------
    /** The pack's answers for one person, at most {@code limit} calls at once; an answer refused is null and noted. */
    final class Reader {
        final Asker c;
        final Semaphore gate;
        final List<String> problems = Collections.synchronizedList(new ArrayList<>());

        Reader(Asker c, int limit) {
            this.c = c;
            this.gate = new Semaphore(limit);
        }

        Map<String, Object> call(String what, Supplier<Map<String, Object>> s) {
            try {
                gate.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            try {
                return s.get();
            } catch (Refused e) {
                synchronized (problems) {
                    if (problems.size() < 20) {
                        problems.add(what + ": " + e.say());
                    }
                }
                return null;
            } finally {
                gate.release();
            }
        }

        CompletableFuture<Map<String, Object>> later(String what, Supplier<Map<String, Object>> s) {
            return CompletableFuture.supplyAsync(() -> call(what, s), pool);
        }

        CompletableFuture<Map<String, Object>> raw(String kind, String id) {
            return later(kind + "/" + id, () -> c.raw(kind, id));
        }

        CompletableFuture<Map<String, Object>> search(String q) {
            return later(q, () -> c.search(q));
        }

        CompletableFuture<Map<String, Object>> series(String kind, String id, String field, int days) {
            return later(kind + "/" + id + " " + field, () -> c.series(kind, id, field, days));
        }

        List<String> ids(String kind, int limit) {
            Map<String, Object> a = join(search(COMMANDS.get(kind) + " limit " + limit));
            List<String> out = new ArrayList<>();
            for (Object r : list(a, "rows")) {
                Object id = get(map(r, "ref"), "id");
                Object shown = id != null && !"".equals(id) ? id : get(r, "title");
                if (shown != null && !"".equals(shown)) {
                    out.add(str(shown));
                }
            }
            return out;
        }
    }

    static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }

    /** A field's history as (day, value), oldest first, only the days that hold that day's own data. */
    static List<Object[]> realPoints(Map<String, Object> series) {
        List<Object[]> out = new ArrayList<>();
        for (Object p : list(series, "points")) {
            LocalDate d = day(get(p, "date"));
            LocalDate dd = day(get(p, "dataDate"));
            if (d != null && d.equals(dd) && get(p, "value") != null) {
                out.add(new Object[] {d, get(p, "value")});
            }
        }
        return out;
    }

    /** (day, number) of the real points that hold a number. */
    static List<Object[]> numbers(Map<String, Object> series) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] p : realPoints(series)) {
            Double v = num(p[1]);
            if (v != null) {
                out.add(new Object[] {p[0], v});
            }
        }
        return out;
    }

    /** The latest real day's value against the business day before it, when both hold real data; else null. */
    static Map<String, Object> dayChange(Map<String, Object> series, Set<String> holidays) {
        List<Object[]> pts = numbers(series);
        if (pts.size() < 2) {
            return null;
        }
        Object[] a = pts.get(pts.size() - 2);
        Object[] b = pts.get(pts.size() - 1);
        LocalDate d0 = (LocalDate) a[0];
        LocalDate d1 = (LocalDate) b[0];
        if (!d0.equals(previousBusinessDay(d1, holidays))) {
            return null;
        }
        double v0 = (Double) a[1];
        double v1 = (Double) b[1];
        return m("date", d1.toString(), "since", d0.toString(), "from", v0, "to", v1, "delta", v1 - v0);
    }

    record Business(LocalDate day, Set<String> holidays) {
    }

    static Business business(Reader r) {
        try {
            Map<String, Object> bd = r.c.dates();
            Set<String> h = new HashSet<>();
            list(bd, "holidays").forEach(x -> h.add(str(x)));
            Object sel = get(bd, "selected");
            return new Business(day(sel != null && !"".equals(sel) ? sel : get(bd, "current")), h);
        } catch (Refused e) {
            r.problems.add("business date: " + e.getMessage());
            return new Business(null, Set.of());
        }
    }

    // -- A · Market desk ---------------------------------------------------------------------------------------------------------------
    static List<Map<String, Object>> curvePoints(List<Object> points) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object p : points) {
            Double y = tenorYears(get(p, "TENOR"));
            Double rate = num(get(p, "ZERO_RATE"));
            if (y != null && rate != null) {
                out.add(m("tenor", str(get(p, "TENOR")), "years", round(y, 4), "rate", rate));
            }
        }
        return out;
    }

    static List<Map<String, Object>> curvePoints(Map<String, Object> doc) {
        return curvePoints(list(map(doc, "data"), "POINTS"));
    }

    /** The curve five business days before its latest day, from its history, when that day holds real data. */
    static Map<String, Object> weekAgo(Map<String, Object> series, LocalDate latest, Set<String> holidays) {
        if (latest == null) {
            return null;
        }
        LocalDate want = latest;
        for (int i = 0; i < 5; i++) {
            want = previousBusinessDay(want, holidays);
        }
        for (Object[] p : realPoints(series)) {
            if (want.equals(p[0]) && p[1] instanceof List<?> l) {
                @SuppressWarnings("unchecked")
                List<Object> pts = (List<Object>) l;
                List<Map<String, Object>> shown = curvePoints(pts);
                if (!shown.isEmpty()) {
                    return m("date", want.toString(), "points", shown);
                }
            }
        }
        return null;
    }

    static Map<String, Object> tile(String key, String label, Object value, String unit, String cmd, String sub, Map<String, Object> change, double scale) {
        return m("id", key, "label", label, "value", value, "unit", unit, "cmd", cmd, "sub", sub,
                "change", change == null ? null : m("delta", round((Double) change.get("delta") * scale, 4), "since", change.get("since")));
    }

    /** A's tiles (USD OIS 10Y, USD 2s10s, EUR/USD 1M ATM vol, the CDS 5Y median) and its USD curves (OIS today, a week ago, projection). */
    public Map<String, Object> desk(Asker c) {
        return cache.once("desk\u0001" + c.key(), () -> makeDesk(c));
    }

    private Map<String, Object> makeDesk(Asker c) {
        Reader r = new Reader(c, 8);
        Business b = business(r);
        CompletableFuture<Map<String, Object>> oisT = r.raw("ir-curve", "CRV-USD-OIS");
        CompletableFuture<Map<String, Object>> projT = r.raw("ir-curve", "CRV-USD-PROJ");
        CompletableFuture<Map<String, Object>> fxvT = r.raw("fx-vol-surface", "FXV-EURUSD");
        CompletableFuture<Map<String, Object>> cdsT = r.search("CDS where SPREAD_5Y > 0 order by SPREAD_5Y limit 500");
        CompletableFuture<Map<String, Object>> crvT = r.search("CRV limit 1");
        CompletableFuture<Map<String, Object>> ois10T = r.series("ir-curve", "CRV-USD-OIS", "$.TEN_Y", 4);
        CompletableFuture<Map<String, Object>> slopeT = r.series("ir-curve", "CRV-USD-PROJ", "$.SLOPE_2S_10S", 4);
        CompletableFuture<Map<String, Object>> atmT = r.series("fx-vol-surface", "FXV-EURUSD", "$.ATM_1M", 4);
        CompletableFuture<Map<String, Object>> ptsT = r.series("ir-curve", "CRV-USD-OIS", "$.POINTS", 8);
        Map<String, Object> ois = join(oisT);
        Map<String, Object> proj = join(projT);
        Map<String, Object> fxv = join(fxvT);
        Map<String, Object> cds = join(cdsT);
        LocalDate dataDay = day(get(map(ois, "provenance"), "businessDate"));
        List<Map<String, Object>> tiles = new ArrayList<>();
        Map<String, Object> o = map(ois, "data");
        Double ten = num(o.get("TEN_Y"));
        String index = str(o.get("INDEX"));
        tiles.add(tile("ois10y", "USD OIS 10Y", ten == null ? null : round(ten * 100, 4), "%", "CRV CRV-USD-OIS",
                "CRV CRV-USD-OIS" + (index.isEmpty() ? "" : " · " + index), dayChange(join(ois10T), b.holidays()), 10000));
        tiles.add(tile("slope", "USD 2s10s", num(map(proj, "data").get("SLOPE_2S_10S")), "bp", "CRV CRV-USD-PROJ", "slope, CRV-USD-PROJ",
                dayChange(join(slopeT), b.holidays()), 1.0));
        tiles.add(tile("fxvol", "EUR/USD 1M ATM vol", num(map(fxv, "data").get("ATM_1M")), "%", "FXV FXV-EURUSD", "FXV FXV-EURUSD",
                dayChange(join(atmT), b.holidays()), 1.0));
        List<Object> rows = list(cds, "rows");
        List<Double> spreads = new ArrayList<>();
        for (Object x : rows) {
            Double s = num(get(map(x, "values"), "$.SPREAD_5Y"));
            if (s != null) {
                spreads.add(s);
            }
        }
        Double median = cdsMedian(r, spreads);
        Map<String, Object> change = null;
        if (!spreads.isEmpty()) {
            List<CompletableFuture<Map<String, Object>>> ts = new ArrayList<>();
            for (Object x : rows.subList(0, Math.min(60, rows.size()))) {
                ts.add(r.series("credit-curve", str(get(map(x, "ref"), "id")), "$.SPREAD_5Y", 4));
            }
            List<Map<String, Object>> moves = new ArrayList<>();
            for (CompletableFuture<Map<String, Object>> t : ts) {
                moves.add(dayChange(join(t), b.holidays()));
            }
            Set<Object> since = new HashSet<>();
            boolean all = !moves.isEmpty();
            for (Map<String, Object> mv : moves) {
                if (mv == null) {
                    all = false;
                } else {
                    since.add(mv.get("since"));
                }
            }
            if (all && since.size() == 1) {
                Double delta = medianMove(r, moves);
                change = delta == null ? null : m("delta", delta, "since", moves.get(0).get("since"));
            }
        }
        tiles.add(tile("cds", "CDS 5Y, median", median, "bp", "CDS where SPREAD_5Y > 0 order by SPREAD_5Y desc",
                spreads.isEmpty() ? "CDS" : "CDS · " + spreads.size() + " names", change, 1.0));
        List<Map<String, Object>> today = curvePoints(ois);
        Map<String, Object> pts = join(ptsT);
        Map<String, Object> crv = join(crvT);
        return m("businessDate", b.day() == null ? null : b.day().toString(), "dataDate", dataDay == null ? null : dataDay.toString(),
                "curves", crv == null ? null : crv.get("matched"), "kinds", COMMANDS.size(), "tiles", tiles,
                "curve", m("ois", today, "projection", curvePoints(proj), "weekAgo", today.isEmpty() ? null : weekAgo(pts, dataDay, b.holidays())),
                "problems", List.copyOf(r.problems));
    }

    /** The engine's figures of rows read; a refusal is null and noted with the other problems. */
    static Figures figures(Reader r, String what, List<Field> fields, List<List<Object>> rows, List<Map<String, Object>> values, String by) {
        try {
            return r.c.figures(fields, rows, values, by, null, null);
        } catch (Refused e) {
            if (r.problems.size() < 20) {
                r.problems.add(what + ": " + e.say());
            }
        } catch (IllegalArgumentException e) {
            if (r.problems.size() < 20) {
                r.problems.add(what + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** The CDS 5Y spreads' median, the engine's ({@code median(S)}). */
    static Double cdsMedian(Reader r, List<Double> spreads) {
        if (spreads.isEmpty()) {
            return null;
        }
        List<List<Object>> rows = spreads.stream().map(s -> List.<Object>of(s)).toList();
        Figures f = figures(r, "the CDS median", List.of(new Field("S", "number")), rows, List.of(InlineFigures.measure("median(S)", "median")), null);
        return f == null ? null : f.get("median");
    }

    /** How far the median moved over the day: the engine's {@code median(TO) - median(FROM)} over each name's two days. */
    static Double medianMove(Reader r, List<Map<String, Object>> moves) {
        List<List<Object>> rows = moves.stream().map(mv -> List.<Object>of(mv.get("from"), mv.get("to"))).toList();
        Figures f = figures(r, "the CDS median's move", List.of(new Field("FROM", "number"), new Field("TO", "number")), rows,
                List.of(InlineFigures.measure("median(TO) - median(FROM)", "delta")), null);
        return f == null ? null : f.get("delta");
    }

    /** Each item's move in standard deviations of its own daily changes, the engine's: {item: {sigma, sd, days}}. */
    static Map<String, Double[]> sigmas(Reader r, List<Object[]> items) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] it : items) {
            @SuppressWarnings("unchecked")
            List<Double> hist = (List<Double>) it[2];
            for (Double h : hist) {
                rows.add(java.util.Arrays.asList(it[0], h, null));
            }
        }
        for (Object[] it : items) {
            rows.add(java.util.Arrays.asList(it[0], null, it[1]));
        }
        Figures f = figures(r, "the moves' sigma", List.of(new Field("ITEM", "string"), new Field("H", "number"), new Field("LAST", "number")), rows,
                List.of(InlineFigures.measure("stddev_pop(H)", "sd"), InlineFigures.measure("count(H)", "days"),
                        InlineFigures.measure("abs(sum(LAST)) / stddev_pop(H)", "sigma")), "ITEM");
        Map<String, Double[]> out = new LinkedHashMap<>();
        if (f != null) {
            for (Object[] it : items) {
                out.put((String) it[0], new Double[] {f.get("sigma", it[0], null), f.get("sd", it[0], null), f.get("days", it[0], null)});
            }
        }
        return out;
    }

    /** A move in the units a desk reads it: bp for a rate held as a fraction, bp for a spread already in bp, percent of the level otherwise. */
    static Object[] moveValue(String unit, double d0, double d1) {
        if ("bp".equals(unit)) {
            return new Object[] {(d1 - d0) * 10000, "bp"};
        }
        if ("spread".equals(unit)) {
            return new Object[] {d1 - d0, "bp"};
        }
        return new Object[] {d0 != 0 ? (d1 - d0) / d0 * 100 : 0.0, "%"};
    }

    /**
     * The biggest moves of the latest day: each item's change against the business day before, sized in standard deviations of its own
     * daily changes when the lake holds {@link #SIGMA_MIN_DAYS} of them ({@code sigma}), else the largest moves ({@code absolute}), else
     * none ({@code none}).
     */
    public Map<String, Object> moves(Asker c) {
        return cache.once("moves\u0001" + c.key(), () -> makeMoves(c, 21, 6));
    }

    private Map<String, Object> makeMoves(Asker c, int days, int top) {
        Reader r = new Reader(c, 6);
        Business b = business(r);
        List<String[]> plan = new ArrayList<>();
        for (String[] mv : MOVERS) {
            List<String> ids = r.ids(mv[0], 200);
            for (String i : ids.subList(0, Math.min(60, ids.size()))) {
                plan.add(new String[] {mv[0], i, mv[1], mv[2]});
            }
        }
        List<CompletableFuture<Map<String, Object>>> ts = plan.stream().map(p -> r.series(p[0], p[1], "$." + p[2], days)).toList();
        List<Map<String, Object>> items = new ArrayList<>();
        List<Object[]> histories = new ArrayList<>();
        for (int n = 0; n < plan.size(); n++) {
            String[] p = plan.get(n);
            List<Object[]> pts = numbers(join(ts.get(n)));
            List<Object[]> diffs = new ArrayList<>();                       // (day, from, to)
            for (int k = 1; k < pts.size(); k++) {
                if (pts.get(k - 1)[0].equals(previousBusinessDay((LocalDate) pts.get(k)[0], b.holidays()))) {
                    diffs.add(new Object[] {pts.get(k)[0], pts.get(k - 1)[1], pts.get(k)[1]});
                }
            }
            if (diffs.isEmpty() || !diffs.get(diffs.size() - 1)[0].equals(pts.get(pts.size() - 1)[0])) {
                continue;
            }
            Object[] lastDiff = diffs.get(diffs.size() - 1);
            LocalDate d = (LocalDate) lastDiff[0];
            Object[] mv = moveValue(p[3], (Double) lastDiff[1], (Double) lastDiff[2]);
            double move = (Double) mv[0];
            List<Double> hist = new ArrayList<>();
            for (Object[] x : diffs.subList(0, diffs.size() - 1)) {
                hist.add((Double) moveValue(p[3], (Double) x[1], (Double) x[2])[0]);
            }
            if (hist.size() >= SIGMA_MIN_DAYS) {
                histories.add(new Object[] {p[0] + "/" + p[1], move, hist});
            }
            items.add(m("kind", p[0], "id", p[1], "cmd", COMMANDS.get(p[0]) + " " + p[1], "field", p[2], "date", d.toString(),
                    "since", previousBusinessDay(d, b.holidays()).toString(), "move", round(move, 4), "unit", mv[1], "sigma", null, "days", hist.size()));
        }
        Map<String, Double[]> measured = histories.isEmpty() ? Map.of() : sigmas(r, histories);
        for (Map<String, Object> x : items) {
            Double[] z = measured.get(x.get("kind") + "/" + x.get("id"));
            if (z != null && z[0] != null && z[1] != null && z[1] > 1e-12) {
                x.put("sigma", round(z[0], 2));
            }
        }
        List<Map<String, Object>> sized = items.stream().filter(x -> x.get("sigma") != null).toList();
        String mode;
        List<Map<String, Object>> ranked;
        if (sized.size() >= 3) {
            mode = "sigma";
            ranked = new ArrayList<>(sized);
            ranked.sort(Comparator.comparingDouble(x -> -(Double) x.get("sigma")));
        } else if (!items.isEmpty()) {
            mode = "absolute";
            ranked = new ArrayList<>(items);
            ranked.sort(Comparator.comparingDouble(x -> {
                double mvv = (Double) x.get("move");
                return -Math.abs("%".equals(x.get("unit")) ? mvv : mvv / 100);
            }));
        } else {
            mode = "none";
            ranked = List.of();
        }
        return m("mode", mode, "items", new ArrayList<>(ranked.subList(0, Math.min(top, ranked.size()))), "measured", items.size(),
                "asked", plan.size(), "problems", List.copyOf(r.problems));
    }

    // -- C · Quality -------------------------------------------------------------------------------------------------------------------
    static Map<String, Object> exc(String kind, String id, String check, String detail, String severity, String since) {
        return m("kind", kind, "id", id, "cmd", COMMANDS.getOrDefault(kind, kind) + " " + id, "check", check, "detail", detail, "severity", severity,
                "since", since);
    }

    /** Stale: the document's own AS_OF or, without one, the date its source holds it for, more than {@code staleDays} business days old. */
    static Map<String, Object> checkStale(String kind, String id, Map<String, Object> doc, LocalDate business, Set<String> holidays, int staleDays) {
        LocalDate seen = day(map(doc, "data").get("AS_OF"));
        if (seen == null) {
            seen = day(get(map(doc, "provenance"), "businessDate"));
        }
        if (seen == null) {
            return null;
        }
        int lag = businessDaysBetween(seen, business, holidays);
        if (lag <= staleDays) {
            return null;
        }
        return exc(kind, id, "stale", "as of " + seen + ", " + lag + " business days old", "warning", seen.toString());
    }

    /** Gaps: a curve missing a tenor that every other curve of its type has (a type of three curves or more). */
    static List<Map<String, Object>> checkGaps(List<Object[]> group) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (group.size() < 3) {
            return out;
        }
        Map<String, Set<String>> tenors = new LinkedHashMap<>();
        for (Object[] g : group) {
            Set<String> ts = new LinkedHashSet<>();
            for (Object p : list(map((Map<?, ?>) g[2], "data"), "POINTS")) {
                Object t = get(p, "TENOR");
                if (t != null && !"".equals(t)) {
                    ts.add(str(t));
                }
            }
            tenors.put((String) g[1], ts);
        }
        for (Object[] g : group) {
            String i = (String) g[1];
            List<Set<String>> others = tenors.entrySet().stream().filter(e -> !e.getKey().equals(i) && !e.getValue().isEmpty())
                    .map(Map.Entry::getValue).toList();
            if (others.isEmpty()) {
                continue;
            }
            Set<String> missing = new LinkedHashSet<>(others.get(0));
            others.forEach(missing::retainAll);
            missing.removeAll(tenors.get(i));
            if (!missing.isEmpty()) {
                List<String> order = new ArrayList<>(missing);
                order.sort(Comparator.comparingDouble(t -> tenorYears(t) == null ? 0 : tenorYears(t)));
                out.add(exc((String) g[0], i, "gap", String.join(", ", order) + " missing (the other curves of its type have "
                        + (order.size() == 1 ? "it" : "them") + ")", "warning", null));
            }
        }
        return out;
    }

    /** Broken discount factors (not falling with maturity, or outside (0, 1]) and negative forward rates. */
    static List<Map<String, Object>> checkForwards(String kind, String id, Map<String, Object> doc) {
        Map<String, Object> data = map(doc, "data");
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object[]> pts = new ArrayList<>();
        for (Object p : list(data, "POINTS")) {
            Double df = num(get(p, "DF"));
            if (df != null) {
                pts.add(new Object[] {str(get(p, "TENOR")), df});
            }
        }
        List<String> bad = new ArrayList<>();
        for (Object[] p : pts) {
            double df = (Double) p[1];
            if (df <= 0 || df > 1) {
                bad.add((String) p[0]);
            }
        }
        List<String> rises = new ArrayList<>();
        for (int n = 1; n < pts.size(); n++) {
            if ((Double) pts.get(n)[1] >= (Double) pts.get(n - 1)[1]) {
                rises.add((String) pts.get(n)[0]);
            }
        }
        if (!bad.isEmpty()) {
            out.add(exc(kind, id, "discount factor", "outside (0, 1] at " + String.join(", ", bad), "critical", null));
        }
        if (!rises.isEmpty()) {
            out.add(exc(kind, id, "discount factor", "does not fall at " + String.join(", ", rises), "critical", null));
        }
        List<String> neg = new ArrayList<>();
        for (Object f : list(data, "FORWARDS")) {
            Double v = num(get(f, "RATE"));
            if (v != null && v < 0) {
                neg.add(str(get(f, "TENOR")) + " " + String.format(Locale.ROOT, "%.2f", v) + "%");
            }
        }
        if (!neg.isEmpty()) {
            out.add(exc(kind, id, "negative forward", String.join(", ", neg), "warning", null));
        }
        return out;
    }

    /** Python's {@code :g} of a number, for the words of a check. */
    static String g(double v) {
        String s = String.format(Locale.ROOT, "%.6g", v);
        if (s.contains("e")) {
            return s;
        }
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    /**
     * A vol surface's sanity: no vol at or below zero (risk reversals and butterflies may be negative) and, where every expiry has the same
     * underlying (FX, equity), ATM total variance (vol² × T) rising with expiry.
     */
    static List<Map<String, Object>> checkSurface(String kind, String id, Map<String, Object> doc, String atm) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> grid = list(map(doc, "data"), "GRID");
        List<String> neg = new ArrayList<>();
        for (Object row : grid) {
            Map<String, Object> rm = map(row);
            Object label = rm.get("EXPIRY") != null && !"".equals(rm.get("EXPIRY")) ? rm.get("EXPIRY")
                    : rm.get("MATURITY") != null && !"".equals(rm.get("MATURITY")) ? rm.get("MATURITY") : rm.get("MONTH");
            for (Map.Entry<String, Object> e : rm.entrySet()) {
                Double n = num(e.getValue());
                if (n != null && n <= 0 && !e.getKey().startsWith("RR") && !e.getKey().startsWith("BF")) {
                    neg.add(str(label) + " " + e.getKey() + " " + g(n));
                }
            }
        }
        if (!neg.isEmpty()) {
            out.add(exc(kind, id, "negative vol", String.join(", ", neg.subList(0, Math.min(4, neg.size()))) + (neg.size() > 4 ? " …" : ""),
                    "critical", null));
        }
        if (atm != null) {
            List<Object[]> tv = new ArrayList<>();
            for (Object row : grid) {
                Double t = tenorYears(get(row, "EXPIRY"));
                Double v = num(get(row, atm));
                if (t != null && v != null && v > 0) {
                    tv.add(new Object[] {str(get(row, "EXPIRY")), t * v * v});
                }
            }
            List<String> falls = new ArrayList<>();
            for (int n = 1; n < tv.size(); n++) {
                if ((Double) tv.get(n)[1] < (Double) tv.get(n - 1)[1]) {
                    falls.add(tv.get(n - 1)[0] + " to " + tv.get(n)[0]);
                }
            }
            if (!falls.isEmpty()) {
                out.add(exc(kind, id, "calendar spread", "total variance falls " + String.join(", ", falls), "critical", null));
            }
        }
        return out;
    }

    /**
     * Every check over the documents read ({@code docs}: kind → id → raw answer) and each other kind's latest document ({@code latest}: kind
     * → {id, raw answer}). Pure.
     */
    static Map<String, Object> runChecks(Map<String, Map<String, Map<String, Object>>> docs, Map<String, Object[]> latest, LocalDate business,
            Set<String> holidays, int staleDays) {
        List<Map<String, Object>> found = new ArrayList<>();
        Map<String, Map<String, Object>> checks = new LinkedHashMap<>();
        for (String ch : List.of("stale", "gap", "forwards", "surface")) {
            checks.put(ch, m("id", ch, "items", 0, "flagged", 0));
        }
        Set<String> items = new LinkedHashSet<>();
        java.util.function.BiConsumer<String, Object[]> note = (check, nx) -> {
            int nItems = (Integer) nx[0];
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ex = (List<Map<String, Object>>) nx[1];
            Map<String, Object> c = checks.get(check);
            c.put("items", (Integer) c.get("items") + nItems);
            Set<String> flagged = new HashSet<>();
            ex.forEach(e -> flagged.add(e.get("kind") + "\u0001" + e.get("id")));
            c.put("flagged", (Integer) c.get("flagged") + flagged.size());
            found.addAll(ex);
        };
        docs.forEach((kind, byId) -> byId.forEach((i, d) -> {
            items.add(kind + "\u0001" + i);
            Map<String, Object> s = checkStale(kind, i, d, business, holidays, staleDays);
            note.accept("stale", new Object[] {1, s == null ? List.of() : List.of(s)});
        }));
        latest.forEach((kind, x) -> {
            String i = (String) x[0];
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) x[1];
            items.add(kind + "\u0001" + i);
            Map<String, Object> s = checkStale(kind, i, d, business, holidays, staleDays);
            if (s != null) {
                s.put("detail", s.get("detail") + " (the latest " + COMMANDS.getOrDefault(kind, kind) + " loaded)");
            }
            note.accept("stale", new Object[] {1, s == null ? List.of() : List.of(s)});
        });
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (String kind : CURVES) {
            docs.getOrDefault(kind, Map.of()).forEach((i, d) -> {
                String ctype = "ir-curve".equals(kind) ? str(map(d, "data").get("CURVE_TYPE")) : "";
                groups.computeIfAbsent(kind + "\u0001" + ctype, k -> new ArrayList<>()).add(new Object[] {kind, i, d});
            });
        }
        groups.values().forEach(g -> note.accept("gap", new Object[] {g.size() >= 3 ? g.size() : 0, checkGaps(g)}));
        docs.getOrDefault("ir-curve", Map.of()).forEach((i, d) -> note.accept("forwards", new Object[] {1, checkForwards("ir-curve", i, d)}));
        SURFACES.forEach((kind, atm) -> docs.getOrDefault(kind, Map.of())
                .forEach((i, d) -> note.accept("surface", new Object[] {1, checkSurface(kind, i, d, atm)})));
        Set<String> flagged = new HashSet<>();
        found.forEach(e -> flagged.add(e.get("kind") + "\u0001" + e.get("id")));
        Map<String, Integer> rank = Map.of("critical", 0, "warning", 1);
        found.sort(Comparator.<Map<String, Object>>comparingInt(e -> rank.getOrDefault(str(e.get("severity")), 2))
                .thenComparing(e -> str(e.get("check"))).thenComparing(e -> str(e.get("kind"))).thenComparing(e -> str(e.get("id"))));
        int n = items.size();
        long dirty = items.stream().filter(flagged::contains).count();
        return m("checked", n, "clean", n - dirty, "cleanPct", n == 0 ? null : round((n - dirty) * 100.0 / n, 1),
                "critical", found.stream().filter(e -> "critical".equals(e.get("severity"))).toList(),
                "warnings", found.stream().filter(e -> "warning".equals(e.get("severity"))).toList(),
                "checks", new ArrayList<>(checks.values()));
    }

    /** C's checks over the pack's own answers: the curves and surfaces read whole, every other kind by its latest load. */
    public Map<String, Object> quality(Asker c, int staleDays) {
        String key = "quality\u0001" + staleDays + "\u0001" + c.key();
        Map<String, Object> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Reader r = new Reader(c, 8);
        Business b = business(r);
        List<String> whole = new ArrayList<>(CURVES);
        whole.addAll(SURFACES.keySet());
        List<String> rest = COMMANDS.keySet().stream().filter(k -> !whole.contains(k)).toList();
        List<CompletableFuture<List<String>>> wholeIds = whole.stream().map(k -> CompletableFuture.supplyAsync(() -> r.ids(k, 500), pool)).toList();
        List<CompletableFuture<List<String>>> restIds = rest.stream().map(k -> CompletableFuture.supplyAsync(() -> r.ids(k, 1), pool)).toList();
        List<String[]> wanted = new ArrayList<>();
        for (int n = 0; n < whole.size(); n++) {
            List<String> ids = join(wholeIds.get(n));
            for (String i : ids.subList(0, Math.min(200, ids.size()))) {
                wanted.add(new String[] {whole.get(n), i});
            }
        }
        for (int n = 0; n < rest.size(); n++) {
            List<String> ids = join(restIds.get(n));
            if (!ids.isEmpty()) {
                wanted.add(new String[] {rest.get(n), ids.get(0)});
            }
        }
        List<CompletableFuture<Map<String, Object>>> answers = wanted.stream().map(w -> r.raw(w[0], w[1])).toList();
        Map<String, Map<String, Map<String, Object>>> docs = new LinkedHashMap<>();
        Map<String, Object[]> latest = new LinkedHashMap<>();
        for (int n = 0; n < wanted.size(); n++) {
            Map<String, Object> a = join(answers.get(n));
            if (a == null) {
                continue;
            }
            String k = wanted.get(n)[0];
            if (whole.contains(k)) {
                docs.computeIfAbsent(k, x -> new LinkedHashMap<>()).put(wanted.get(n)[1], a);
            } else {
                latest.put(k, new Object[] {wanted.get(n)[1], a});
            }
        }
        if (b.day() == null) {
            List<String> problems = r.problems.isEmpty() ? List.of("no business date") : List.copyOf(r.problems);
            return m("businessDate", null, "staleDays", staleDays, "checked", 0, "clean", 0, "cleanPct", null, "critical", List.of(),
                    "warnings", List.of(), "checks", List.of(), "problems", problems);
        }
        Map<String, Object> out = m("businessDate", b.day().toString(), "staleDays", staleDays);
        out.putAll(runChecks(docs, latest, b.day(), b.holidays(), staleDays));
        out.put("problems", List.copyOf(r.problems));
        return cache.put(key, out);
    }

    /** Drops the answers kept (tests). */
    public void forget() {
        cache.clear();
    }
}
