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

import com.ash.drishti.bicompute.rows.InlinePivot;
import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.module.KernelCalls;
import com.ash.drishti.kernel.api.module.PersonCache;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.module.runtime.InlinePivotFigures;
import com.ash.drishti.module.runtime.KernelPersonCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * The stand-in server of Drishti Market's landings (MarketLandingRecordTest): the server's API as {@link MarketLanding} reads it through
 * {@link KernelCalls}, over market data with known problems, one of each check. Its answers are shaped as the server's (search rows of
 * {@code $.FIELD} values, raw documents with provenance, one field's history with each point's data date, the business date); the pivot
 * engine's {@code /pivot/inline} is the engine's own ({@link InlinePivot}); a lens is the repository's file. The business date is
 * 2026-09-30.
 *
 * <ul>
 *   <li>stale: CRV-GBP-OIS's AS_OF is 2026-09-24 (four business days old); the latest INF loaded is of 2026-09-25 (three);</li>
 *   <li>gap: CRV-AUD-PROJ has no 15Y point, which the two other projection curves have;</li>
 *   <li>forwards: CRV-EUR-OIS's discount factor rises at 30Y (critical) and its 10Y forward is negative (warning);</li>
 *   <li>surfaces: FXV-USDTRY's ATM total variance falls from 3M to 6M (critical); EQV-ZERO has a vol of zero (critical).</li>
 * </ul>
 * History: every curve's TEN_Y has 21 real business days, CRV-EUR-OIS jumping on the last; the EUR/USD vol surface holds the business date
 * only (so its tile shows no change); the CDS spreads hold both days. With {@code history} false no day but the last holds its own data.
 */
final class MarketStandIn implements KernelCalls {

    static final LocalDate BUSINESS = LocalDate.of(2026, 9, 30);
    static final List<String> TENORS = List.of("1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "15Y", "20Y", "30Y");
    static final double[] YEARS = {1.0 / 12, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30};
    static final Map<String, Integer> COUNTS = ordered("CRV", 28, "REPO", 7, "IRV", 7, "CPV", 7, "FIX", 7, "INF", 4, "INFC", 4, "FX", 12, "FXF", 12,
            "FXV", 12, "CDS", 16, "BND", 32, "EQ", 10, "EQX", 5, "DIV", 15, "EQV", 15, "CMD", 8, "CMDC", 8, "CMDV", 8, "CORR", 3);
    static final Map<String, Double> CDS = ordered("CDS-A", 60.0, "CDS-B", 70.0, "CDS-C", 300.0);
    static final Map<String, Double> CDS_BEFORE = ordered("CDS-A", 58.0, "CDS-B", 69.0, "CDS-C", 296.0);

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path lenses;
    private final boolean history;
    private final Map<String, Map<String, Object>> curves = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> fxv = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> eqv = new LinkedHashMap<>();
    /** Every call answered, as {@code user path}: what a test reads back to see whose call it was. */
    final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Person> current = new ThreadLocal<>();

    /** One person of the stand-in: their user name, display name, roles and lens; an administrator holds {@code *} or {@code admin}. */
    record Person(String user, String display, List<String> roles, String lens) {
        boolean admin() {
            return roles.contains("*") || roles.contains("admin");
        }
    }

    MarketStandIn(Path repo, boolean history) {
        this.lenses = repo.resolve("config/lenses");
        this.history = history;
        for (Map<String, Object> c : List.of(curve("CRV-USD-OIS", "Discount (OIS)", 3.80, "2026-09-30", null, false, false),
                curve("CRV-EUR-OIS", "Discount (OIS)", 2.10, "2026-09-30", null, true, true),
                curve("CRV-GBP-OIS", "Discount (OIS)", 3.90, "2026-09-24", null, false, false),
                curve("CRV-USD-PROJ", "Projection", 3.95, "2026-09-30", null, false, false),
                curve("CRV-EUR-PROJ", "Projection", 2.20, "2026-09-30", null, false, false),
                curve("CRV-AUD-PROJ", "Projection", 3.60, "2026-09-30", "15Y", false, false))) {
            curves.put((String) c.get("CURVE_ID"), c);
        }
        fxv.put("FXV-EURUSD", m("SURFACE_ID", "FXV-EURUSD", "PAIR", "EURUSD", "ATM_1M", 7.8, "GRID", List.of(
                m("EXPIRY", "1M", "P_25", 8.1, "ATM", 7.8, "C_25", 7.9, "RR_25", -0.3), m("EXPIRY", "3M", "P_25", 8.2, "ATM", 7.9, "C_25", 8.0, "RR_25", -0.3),
                m("EXPIRY", "6M", "P_25", 8.4, "ATM", 8.1, "C_25", 8.2, "RR_25", -0.4))));
        fxv.put("FXV-USDTRY", m("SURFACE_ID", "FXV-USDTRY", "PAIR", "USDTRY", "ATM_1M", 20.0, "GRID", List.of(
                m("EXPIRY", "1M", "ATM", 20.0, "RR_25", 2.0), m("EXPIRY", "3M", "ATM", 19.0, "RR_25", 2.1), m("EXPIRY", "6M", "ATM", 12.0, "RR_25", 2.2))));
        eqv.put("EQV-ZERO", m("SURFACE_ID", "EQV-ZERO", "GRID", List.of(m("EXPIRY", "1M", "M_90", 30.0, "M_100", 28.0),
                m("EXPIRY", "3M", "M_90", 0.0, "M_100", 27.5))));
        eqv.put("EQV-FINE", m("SURFACE_ID", "EQV-FINE", "GRID", List.of(m("EXPIRY", "1M", "M_90", 30.0, "M_100", 28.0),
                m("EXPIRY", "1Y", "M_90", 29.0, "M_100", 27.0))));
    }

    /** Runs {@code work} as {@code person} (the request in hand), on this thread. */
    <T> T as(Person person, java.util.function.Supplier<T> work) {
        current.set(person);
        try {
            return work.get();
        } finally {
            current.remove();
        }
    }

    @Override
    public Caller caller() {
        Person p = current.get();
        if (p == null) {
            throw new IllegalStateException("a caller is the person of a request: there is no request on this thread");
        }
        return new Call(p, null);
    }

    @Override
    public <V> PersonCache<V> cache(int size, long ttlMillis) {
        return new KernelPersonCache<>(size, ttlMillis);                   // the kernel's own, as the server gives it
    }

    @Override
    public Map<String, Object> lens(String name) {
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        try (Stream<Path> s = Files.walk(lenses, 2)) {
            Path f = s.filter(p -> p.getFileName().toString().equals(name.strip() + ".yaml")).findFirst().orElse(null);
            if (f == null) {
                return Map.of();
            }
            Map<String, Object> spec = new Yaml().load(Files.readString(f));
            return spec == null ? Map.of() : spec;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // -- the data ---------------------------------------------------------------------------------------------------------------------
    static double round(double v, int places) {
        return new BigDecimal(v).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }

    @SuppressWarnings("unchecked")
    static <V> Map<String, V> ordered(Object... kv) {
        Map<String, V> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put((String) kv[i], (V) kv[i + 1]);
        }
        return out;
    }

    static Map<String, Object> m(Object... kv) {
        return ordered(kv);
    }

    static Map<String, Object> curve(String cid, String ctype, double base, String asOf, String drop, boolean dfRise, boolean negFwd) {
        List<Map<String, Object>> pts = new ArrayList<>();
        for (int i = 0; i < TENORS.size(); i++) {
            String t = TENORS.get(i);
            if (t.equals(drop)) {
                continue;
            }
            double r = base + 0.04 * Math.sqrt(YEARS[i]);
            double df = round(Math.pow(1 + r / 100, -YEARS[i]), 6);
            if (dfRise && t.equals("30Y")) {
                df = 0.70;                                   // higher than the 20Y's: a broken curve
            }
            pts.add(m("TENOR", t, "ZERO_RATE", round(r, 4), "DF", df));
        }
        List<Map<String, Object>> fwd = new ArrayList<>();
        List<String> ft = List.of("1Y", "2Y", "3Y", "5Y", "7Y", "10Y");
        for (int i = 0; i < ft.size(); i++) {
            fwd.add(m("TENOR", ft.get(i), "RATE", round(base + 0.1 * i, 4)));
        }
        if (negFwd) {
            fwd.get(5).put("RATE", -0.12);
        }
        return m("CURVE_ID", cid, "CURRENCY", cid.split("-")[1], "CURVE_TYPE", ctype, "TEN_Y", round((base + 0.04 * Math.sqrt(10)) / 100, 6),
                "SLOPE_2S_10S", 46.0, "AS_OF", asOf, "INDEX", "CRV-USD-OIS".equals(cid) ? "SOFR" : "", "POINTS", pts, "FORWARDS", fwd);
    }

    static List<LocalDate> businessDays(int n) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = BUSINESS;
        while (out.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(0, d);
            }
            d = d.minusDays(1);
        }
        return out;
    }

    /** 21 business days of a curve's 10Y rate: a small wobble, CRV-EUR-OIS jumping 30bp on the last day, CRV-USD-OIS 4bp down. */
    List<Double> tenY(String cid) {
        double base = (Double) curves.get(cid).get("TEN_Y");
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            vals.add(base + (i % 2 == 1 ? 0.0001 : -0.0001));
        }
        vals.set(20, base);
        if ("CRV-EUR-OIS".equals(cid)) {
            vals.set(20, vals.get(19) + 0.003);
        }
        if ("CRV-USD-OIS".equals(cid)) {
            vals.set(19, base + 0.0004);
        }
        return vals;
    }

    static Map<String, Object> rows(String kind, List<Object[]> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] it : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vals = (Map<String, Object>) it[1];
            Map<String, Object> v = new LinkedHashMap<>();
            vals.forEach((k, x) -> v.put("$." + k, x));
            out.add(m("ref", m("kind", kind, "id", it[0]), "title", it[0], "values", v));
        }
        return m("kind", kind, "matched", items.size(), "scanned", items.size(), "rows", out);
    }

    Map<String, Object> search(String q) {
        String head = q.strip().split("\\s+")[0];
        if (q.endsWith(" limit 1") && !q.contains(" where ") && COUNTS.containsKey(head) && !head.equals("CRV") && !head.equals("INF")) {
            Map<String, Object> out = rows("x", List.of());
            out.put("matched", COUNTS.get(head));
            return out;
        }
        List<Object[]> items = new ArrayList<>();
        switch (head) {
            case "CRV" -> {
                curves.forEach((id, c) -> items.add(new Object[] {id, m("CURRENCY", c.get("CURRENCY"))}));
                Map<String, Object> out = rows("ir-curve", items);
                out.put("matched", q.endsWith("limit 1") ? 28 : curves.size());
                return out;
            }
            case "FXV" -> fxv.keySet().forEach(id -> items.add(new Object[] {id, m()}));
            case "EQV" -> eqv.keySet().forEach(id -> items.add(new Object[] {id, m()}));
            case "CDS" -> CDS.entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .forEach(e -> items.add(new Object[] {e.getKey(), m("SPREAD_5Y", e.getValue())}));
            case "INF" -> items.add(new Object[] {"INF-UKRPI", m()});
            default -> {
                if (!COUNTS.containsKey(head)) {
                    throw new Refused(404, "DRS-4023", head + " is not part of the edition");
                }
            }
        }
        String kind = switch (head) {
            case "FXV" -> "fx-vol-surface";
            case "EQV" -> "equity-vol-surface";
            case "CDS" -> "credit-curve";
            case "INF" -> "inflation-index";
            default -> "x";
        };
        return rows(kind, items);
    }

    Map<String, Object> raw(String kind, String id) {
        Map<String, Object> prov = m("source", "market-store", "businessDate", "2026-09-30");
        Map<String, Object> data = switch (kind) {
            case "ir-curve" -> curves.get(id);
            case "fx-vol-surface" -> fxv.get(id);
            case "equity-vol-surface" -> eqv.get(id);
            case "credit-curve" -> CDS.containsKey(id) ? m("CURVE_ID", id, "SPREAD_5Y", CDS.get(id), "POINTS", List.of(m("TENOR", "5Y", "SPREAD", CDS.get(id))))
                    : null;
            case "inflation-index" -> "INF-UKRPI".equals(id) ? m("INDEX_ID", id) : null;
            default -> null;
        };
        if (data == null) {
            throw new Refused(404, "DRS-1001", "no source holds " + kind + "/" + id);
        }
        if ("inflation-index".equals(kind)) {
            prov.put("businessDate", "2026-09-25");
        }
        return m("ref", m("kind", kind, "id", id), "provenance", prov, "data", data);
    }

    Map<String, Object> series(String kind, String id, String path, int days) {
        List<LocalDate> dates = businessDays(days);
        int n = dates.size();
        List<Object> vals = new ArrayList<>(Collections.nCopies(n, null));
        List<Boolean> real = new ArrayList<>(Collections.nCopies(n, true));
        if ("ir-curve".equals(kind) && curves.containsKey(id) && "$.TEN_Y".equals(path)) {
            List<Double> v = tenY(id);
            for (int i = 0; i < n; i++) {
                int at = v.size() - n + i;
                vals.set(i, at >= 0 ? v.get(at) : null);
            }
        } else if ("ir-curve".equals(kind) && curves.containsKey(id) && "$.SLOPE_2S_10S".equals(path)) {
            for (int i = 0; i < n; i++) {
                vals.set(i, i == n - 1 ? 46.0 : 45.0);
            }
        } else if ("ir-curve".equals(kind) && curves.containsKey(id) && "$.POINTS".equals(path)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> today = (List<Map<String, Object>>) curves.get(id).get("POINTS");
            List<Map<String, Object>> ago = new ArrayList<>();
            for (Map<String, Object> p : today) {
                Map<String, Object> q = new LinkedHashMap<>(p);
                q.put("ZERO_RATE", round((Double) p.get("ZERO_RATE") + 0.05, 4));
                ago.add(q);
            }
            for (int i = 0; i < n; i++) {
                vals.set(i, i == n - 1 ? today : ago);
            }
        } else if ("fx-vol-surface".equals(kind) && "$.ATM_1M".equals(path)) {
            for (int i = 0; i < n; i++) {
                vals.set(i, 7.5);
                real.set(i, i == n - 1);                     // only the business date holds data: no change shown
            }
        } else if ("credit-curve".equals(kind) && CDS.containsKey(id)) {
            for (int i = 0; i < n; i++) {
                vals.set(i, i == n - 1 ? CDS.get(id) : CDS_BEFORE.get(id));
            }
        } else {
            throw new Refused(404, "DRS-1001", "no history of " + kind + "/" + id);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean ok = real.get(i) && (history || i == n - 1);
            LocalDate dataDay = ok ? dates.get(i) : LocalDate.of(2026, 7, 8);
            points.add(m("date", dates.get(i).toString(), "value", vals.get(i), "dataDate", dataDay.toString(), "source", "market-store"));
        }
        return m("ref", m("kind", kind, "id", id), "path", path, "label", path, "dated", true, "points", points);
    }

    static Map<String, Object> businessDate(LocalDate selected) {
        return m("current", BUSINESS.toString(), "selected", selected == null ? BUSINESS.toString() : selected.toString(), "live", selected == null,
                "previous", "2026-09-29", "earliest", "2021-09-30", "calendar", "USNY", "zone", "America/New_York",
                "holidays", List.of("2026-10-12", "2026-11-11", "2026-11-26"));
    }

    /** A value as it comes over HTTP: written as JSON and read back (numbers, nulls and maps as the client sees them). */
    static Map<String, Object> wire(Object v) {
        try {
            return JSON.readValue(JSON.writeValueAsBytes(v), new TypeReference<Map<String, Object>>() { });
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One person's calls on one date box (null: live). */
    private final class Call implements Caller {
        private final Person p;
        private final LocalDate day;

        Call(Person p, LocalDate day) {
            this.p = p;
            this.day = day;
        }

        @Override
        public String user() {
            return p.user();
        }

        @Override
        public String display() {
            return p.display();
        }

        @Override
        public boolean admin() {
            return p.admin();
        }

        @Override
        public String lens() {
            return p.lens();
        }

        @Override
        public String key() {
            return String.join("\u0001", p.user(), String.join(",", p.roles()), p.lens(), day == null ? "live" : day.toString());
        }

        @Override
        public Caller onDate(LocalDate d) {
            return new Call(p, d);
        }

        @Override
        public Map<String, Object> get(String path, Map<String, ?> params) {
            asked.add(p.user() + " GET " + path);
            String[] parts = path.split("/");
            Object answer;
            if ("/business-date".equals(path)) {
                answer = businessDate(day);
            } else if ("/search".equals(path)) {
                answer = search(String.valueOf(params.get("q")));
            } else if (path.startsWith("/entities/") && path.endsWith("/raw")) {
                answer = raw(parts[2], "~".equals(parts[3]) ? String.valueOf(params.get("id")) : decode(parts[3]));
            } else if (path.startsWith("/history/") && path.endsWith("/series")) {
                answer = series(parts[2], "~".equals(parts[3]) ? String.valueOf(params.get("id")) : decode(parts[3]), String.valueOf(params.get("path")),
                        Integer.parseInt(String.valueOf(params.get("days"))));
            } else {
                throw new Refused(404, "DRS-4040", "the stand-in has no GET " + path);
            }
            return wire(answer);
        }

        @Override
        public InlineFigures.Figures figures(List<InlineFigures.Field> fields, List<List<Object>> rows, List<Map<String, Object>> values,
                String by, String across, Map<String, Object> top) {
            return InlinePivotFigures.ask(this, fields, rows, values, by, across, top);   // the kernel's asking, over this stand-in's /pivot/inline
        }

        @Override
        public Map<String, Object> post(String path, Object body, Map<String, ?> params) {
            asked.add(p.user() + " POST " + path);
            if (!"/pivot/inline".equals(path)) {
                throw new Refused(404, "DRS-4040", "the stand-in has no POST " + path);
            }
            try {
                return wire(InlinePivot.of(wire(body)).query(Viewer.of(p.user())));
            } catch (DrishtiException e) {
                throw new Refused(e.errorCode().httpStatus(), e.errorCode().code(), e.getMessage());
            }
        }

        private static String decode(String s) {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
