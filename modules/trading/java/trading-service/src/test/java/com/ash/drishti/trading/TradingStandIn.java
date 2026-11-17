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
package com.ash.drishti.trading;

import com.ash.drishti.bicompute.rows.InlinePivot;
import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Kernel.Refused;
import com.ash.drishti.kernel.api.module.PersonCache;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.module.runtime.InlinePivotFigures;
import com.ash.drishti.module.runtime.KernelPersonCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * The stand-in server of Drishti Trading's landings (TradingLandingRecordTest): the server's API as {@link TradingLanding} reads it through
 * {@link Kernel}, shaped as the server answers (search rows of {@code $.FIELD} values, masked for a role without raw as the server masks
 * TRADER_ID; raw records; the live result set of a search; the VaR engine's scope, contributions, official records, history and what-if;
 * the exposure engine's scope and pre-deal check; the person's settings; the business date), with the pivot engine's own
 * {@code /pivot/inline} ({@link InlinePivot}) and the repository's lens files. The business date is 2026-09-30, the day before 2026-09-29.
 *
 * <p>The desk DESK-RATES (head T. Brennan) holds BOOK-RATES-1, -2 and -3; TRDR-ASHAH trades the first two, TRDR-KMOREAU the third. Twelve
 * trades MX-20000001 to MX-20000012, trade i (from 0) in BOOK-RATES-(1 + i mod 3), its MtM up 10,000 × (i + 1) since the day before (A.
 * Shah's P&amp;L today: 480,000; the desk's: 780,000); MX-20000012 is cancelled. Today: MX-20000001 new, MX-20000002 amended, MX-20000004
 * cancelled (A. Shah's books), MX-20000003 amended (K. Moreau's). VaR: BOOK-RATES-1 6.12m of 7.4m (83%), -2 3.0m of 5m (60%), -3 9.0m of 8m
 * (113%, a breach); the desk 15m of 20m; the official record of 2026-09-25 is missing. CP-ALDERSHOT's peak PFE 401.5m of a 420m limit.
 */
final class TradingStandIn implements Kernel {

    static final List<String> BOOKS = List.of("BOOK-RATES-1", "BOOK-RATES-2", "BOOK-RATES-3");
    static final List<String> CPS = List.of("CP-ALDERSHOT", "CP-NORTHPLAINS", "CP-MERIDIAN");
    static final List<String> PRODUCTS = List.of("IR_SWAP", "SWAPTION", "IR_SWAP");
    static final Map<String, Map<String, Object>> TRADERS = Map.of(
            "TRDR-ASHAH", m("NAME", "A. Shah", "DESK", "DESK-RATES", "DESK_NAME", "Rates · Swaps and options", "TRADE_COUNT", 8,
                    "BOOKS", List.of(m("ID", "BOOK-RATES-1"), m("ID", "BOOK-RATES-2"))),
            "TRDR-KMOREAU", m("NAME", "K. Moreau", "DESK", "DESK-RATES", "DESK_NAME", "Rates · Swaps and options", "TRADE_COUNT", 4,
                    "BOOKS", List.of(m("ID", "BOOK-RATES-3"))));
    static final List<String> TRADER_ORDER = List.of("TRDR-ASHAH", "TRDR-KMOREAU");
    static final Map<String, Map<String, Object>> DESKS = Map.of(
            "DESK-RATES", m("NAME", "Rates · Swaps and options", "HEAD", "T. Brennan", "BOOKS", BOOKS.stream().map(b -> m("ID", b)).toList()),
            "DESK-COMM", m("NAME", "Commodities", "HEAD", "R. Okafor", "BOOKS", List.of()));
    static final List<String> DESK_ORDER = List.of("DESK-RATES", "DESK-COMM");
    static final Map<String, double[]> BOOK_VAR = Map.of("BOOK-RATES-1", new double[] {6_120_000, 7_400_000},
            "BOOK-RATES-2", new double[] {3_000_000, 5_000_000}, "BOOK-RATES-3", new double[] {9_000_000, 8_000_000});
    static final List<String> OFFICIAL_DAYS = List.of("2026-09-24", "2026-09-28", "2026-09-29", "2026-09-30");   // 2026-09-25 has none
    /** The server's drishti.risk.limit-near in this stand-in. */
    static final double NEAR = 0.8;
    /** Today's trade events: (trade index, action, status, version, time). */
    static final Object[][] EVENTS = {{0, "NEW", "VALIDATED", "1", "13:52"}, {1, "AMEND", "AMENDED", "3", "12:10"}, {3, "AMEND", "CANCELLED", "2", "11:41"},
        {2, "AMEND", "AMENDED", "2", "10:05"}};

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path lenses;
    /** Every call answered, as {@code user METHOD path}: what a test reads back to see whose call it was. */
    final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Person> current = new ThreadLocal<>();

    /**
     * One person of the stand-in: user name, display name, roles, lens and settings (their {@code tradingView}); an administrator holds
     * {@code *} or {@code admin}; a role that sees raw values ({@code *}, {@code admin}, {@code author}) sees TRADER_ID.
     */
    record Person(String user, String display, List<String> roles, String lens, Map<String, Object> settings) {
        boolean admin() {
            return roles.contains("*") || roles.contains("admin");
        }

        boolean raw() {
            return admin() || roles.contains("author");
        }
    }

    TradingStandIn(Path repo) {
        this.lenses = repo.resolve("config/lenses");
    }

    /** Runs {@code work} as {@code person} (the request in hand), on this thread. */
    <T> T as(Person person, Supplier<T> work) {
        current.set(person);
        try {
            return work.get();
        } finally {
            current.remove();
        }
    }

    @Override
    public com.ash.drishti.kernel.api.services.Person person() {
        Person p = current.get();
        if (p == null) {
            throw new IllegalStateException("a person is the person of a request: there is no request on this thread");
        }
        return new Held(p, null);
    }

    @Override
    public <V> PersonCache<V> cache(int size, long ttlMillis) {
        return new KernelPersonCache<>(size, ttlMillis);                   // the kernel's own, as the server gives it
    }

    /** One person of the stand-in on one date box (null: live), as the kernel's typed {@code Person}. */
    private record Held(Person p, LocalDate day) implements com.ash.drishti.kernel.api.services.Person {
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
        public List<String> roles() {
            return p.roles();
        }

        @Override
        public String lens() {
            return p.lens();
        }

        @Override
        public com.ash.drishti.api.AsOf asOf() {
            return day == null ? com.ash.drishti.api.AsOf.LATEST : com.ash.drishti.api.AsOf.of(day);
        }

        @Override
        public String key() {
            return String.join("\u0001", p.user(), String.join(",", p.roles()), p.lens(), day == null ? "live" : day.toString());
        }

        @Override
        public com.ash.drishti.kernel.api.services.Person onDate(LocalDate d) {
            return new Held(p, d);
        }

        String dayText() {
            return day == null ? "2026-09-30" : day.toString();
        }
    }

    private Held held(com.ash.drishti.kernel.api.services.Person p, String what) {
        Held h = (Held) p;
        asked.add(h.user() + " " + what + " " + h.dayText());
        return h;
    }

    @Override
    public com.ash.drishti.kernel.api.services.Records records() {
        return new com.ash.drishti.kernel.api.services.Records() {
            @Override
            public com.ash.drishti.kernel.api.services.SearchAnswer search(com.ash.drishti.kernel.api.services.Person p, String query) {
                Held h = held(p, "GET /search");
                return Typed.search(wire(TradingStandIn.this.search(h.p(), h.dayText(), query)));
            }

            @Override
            public java.util.Optional<com.ash.drishti.kernel.api.services.RawRecord> raw(com.ash.drishti.kernel.api.services.Person p,
                    com.ash.drishti.api.EntityRef ref) {
                Held h = held(p, "GET /entities/" + ref.kind() + "/raw");
                try {
                    return java.util.Optional.of(Typed.raw(wire(TradingStandIn.this.raw(ref.kind(), ref.id(), h.dayText()))));
                } catch (Refused e) {
                    if ("DRS-1001".equals(e.code())) {
                        return java.util.Optional.empty();
                    }
                    throw e;
                }
            }
        };
    }

    @Override
    public com.ash.drishti.kernel.api.services.BusinessDates dates() {
        return new com.ash.drishti.kernel.api.services.BusinessDates() {
            @Override
            public LocalDate current() {
                return LocalDate.of(2026, 9, 30);
            }

            @Override
            public java.util.Set<LocalDate> holidays() {
                return java.util.Set.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 26));
            }

            @Override
            public LocalDate selected(com.ash.drishti.kernel.api.services.Person p) {
                Held h = held(p, "GET /business-date");
                return h.day() == null ? current() : h.day();
            }

            @Override
            public boolean live(com.ash.drishti.kernel.api.services.Person p) {
                return ((Held) p).day() == null;
            }
        };
    }

    @Override
    public com.ash.drishti.kernel.api.services.PersonSettings settings() {
        return p -> wire(held(p, "GET /me/settings").p().settings());
    }

    @Override
    public com.ash.drishti.kernel.api.services.LiveQueries live() {
        return (p, query, limit) -> {
            Held h = held(p, "GET /live/query");
            return Typed.live(wire(liveQuery(h.p(), h.dayText(), query)));
        };
    }

    @Override
    public com.ash.drishti.kernel.api.services.Pivots pivots() {
        return new com.ash.drishti.kernel.api.services.Pivots() {
            @Override
            public com.ash.drishti.kernel.api.services.CellSet inline(com.ash.drishti.kernel.api.services.Person p,
                    com.ash.drishti.kernel.api.services.InlineRequest request) {
                throw new UnsupportedOperationException("the stand-in answers figures only");
            }

            @Override
            public InlineFigures.Figures figures(com.ash.drishti.kernel.api.services.Person p, List<InlineFigures.Field> fields, List<List<Object>> rows,
                    List<Map<String, Object>> values, String by, String across, Map<String, Object> top) {
                Held h = held(p, "POST /pivot/inline");
                return InlinePivotFigures.ask(b -> {          // the kernel's asking, over this stand-in's pivot engine
                    try {
                        return wire(InlinePivot.of(wire(b)).query(Viewer.of(h.user())));
                    } catch (DrishtiException e) {
                        throw new Refused(e.errorCode().httpStatus(), e.errorCode().code(), e.getMessage());
                    }
                }, fields, rows, values, by, across, top);
            }
        };
    }

    @Override
    public com.ash.drishti.kernel.api.services.Lenses lenses() {
        return (lens, block) -> lensFile(lens).get(block) instanceof Map<?, ?> m ? wire(m) : Map.of();
    }

    @Override
    public com.ash.drishti.kernel.api.services.History history() {
        throw new UnsupportedOperationException("the trading landings read no history");
    }

    @Override
    public com.ash.drishti.kernel.api.services.Access access() {
        throw new UnsupportedOperationException("the stand-in holds no access doors");
    }

    @Override
    public com.ash.drishti.kernel.api.services.Operations operations() {
        return new com.ash.drishti.kernel.api.services.Operations() {
            @Override
            public boolean available(String module) {
                return "banking".equals(module);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T of(String module, Class<T> view) {
                return (T) risk;
            }
        };
    }

    /** The banking module's operations as this stand-in answers them (the engines' figures, shaped as the REST paths answer). */
    private final RiskFigures risk = new RiskFigures() {
        @Override
        public Figures varScope(com.ash.drishti.kernel.api.services.Person p, Scope r) {
            Held h = held(p, "GET /risk/var/scope");
            return new Figures(wire(var(h.p(), "GET", "/scope", Map.of("scope", r.scope()), null)));
        }

        @Override
        public Figures varContributions(com.ash.drishti.kernel.api.services.Person p, Contributions r) {
            Held h = held(p, "GET /risk/var/scope/contributions");
            return new Figures(wire(var(h.p(), "GET", "/scope/contributions", Map.of("scope", r.scope(), "by", r.by()), null)));
        }

        @Override
        public Figures varOfficial(com.ash.drishti.kernel.api.services.Person p, Official r) {
            Held h = held(p, "GET /risk/var/official");
            return new Figures(wire(var(h.p(), "GET", "/official", Map.of("asOf", h.dayText()), null)));
        }

        @Override
        public Figures varHistory(com.ash.drishti.kernel.api.services.Person p, Scope r) {
            Held h = held(p, "GET /risk/var/scope/history");
            return new Figures(wire(var(h.p(), "GET", "/scope/history", Map.of("scope", r.scope()), null)));
        }

        @Override
        public Figures varWhatIf(com.ash.drishti.kernel.api.services.Person p, Body r) {
            Held h = held(p, "POST /risk/var/what-if");
            return new Figures(wire(var(h.p(), "POST", "/what-if", Map.of(), wire(r.body()))));
        }

        @Override
        public Figures exposurePreDeal(com.ash.drishti.kernel.api.services.Person p, Body r) {
            held(p, "POST /risk/exposure/predeal");
            return new Figures(wire(exposure("POST", "/predeal", Map.of(), wire(r.body()))));
        }
    };

    Map<String, Object> lensFile(String name) {
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        try (Stream<Path> s = Files.walk(lenses, 2)) {
            Path f = s.filter(p -> p.getFileName().toString().equals(name.strip() + ".yaml")).findFirst().orElse(null);
            Map<String, Object> spec = f == null ? null : new Yaml().load(Files.readString(f));
            return spec == null ? Map.of() : spec;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // -- the data ---------------------------------------------------------------------------------------------------------------------
    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    /** A limit use's status as the server says it (RiskLimits, limit-near 0.8): within, near, breach; null without a use. */
    static String status(Double use) {
        return use == null ? null : use > 1 ? "breach" : use >= NEAR ? "near" : "within";
    }

    static Map<String, Object> trade(int i, String day) {
        String book = BOOKS.get(i % 3);
        long mtm = 1_000_000L * (i + 1) * (i % 2 == 0 ? 1 : -1) + ("2026-09-30".equals(day) ? 0 : -10_000L * (i + 1));
        return m("TRADE_ID", "MX-" + (20000001 + i), "PORTFOLIO", book, "DESK", "DESK-RATES", "COUNTERPARTY", CPS.get(i % 3),
                "PRODUCT_TYPE", PRODUCTS.get(i % 3), "PRODUCT_GROUP", "Rates", "TRADE_STATUS", i == 11 ? "CANCELLED" : i % 2 == 1 ? "AMENDED" : "VALIDATED",
                "TRADER_ID", "BOOK-RATES-3".equals(book) ? "TRDR-KMOREAU" : "TRDR-ASHAH", "BUY_SELL_CODE", i % 2 == 0 ? "B" : "S",
                "NOTIONAL", 50_000_000.0 * (i + 1), "DV01", -1000L * (i + 1), "CURRENCY", "USD", "MTM", mtm, "MATURITY_DATE", "2036-09-30");
    }

    static Map<String, Object> rows(String kind, List<Object[]> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] it : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vals = (Map<String, Object>) it[2];
            Map<String, Object> v = new LinkedHashMap<>();
            vals.forEach((k, x) -> v.put("$." + k, x));
            out.add(m("ref", m("kind", kind, "id", it[0]), "title", it[1], "values", v));
        }
        return m("kind", kind, "matched", items.size(), "scanned", items.size(), "partial", false, "rows", out);
    }

    static List<String> all(Pattern p, String q) {
        List<String> out = new ArrayList<>();
        Matcher mt = p.matcher(q);
        while (mt.find()) {
            out.add(mt.group(1));
        }
        return out;
    }

    static final Pattern DESK_IS = Pattern.compile("DESK = '([^']+)'");
    static final Pattern BOOK_IS = Pattern.compile("PORTFOLIO = '([^']+)'");
    static final Pattern FLOOR = Pattern.compile("MTM <= (-?[0-9.]+)");
    static final Pattern LIMIT = Pattern.compile("limit (\\d+)");

    Map<String, Object> search(Person who, String day, String q) {
        String head = q.strip().split("\\s+")[0].toUpperCase(java.util.Locale.ROOT);
        List<Object[]> items = new ArrayList<>();
        switch (head) {
            case "TRDR" -> {
                List<String> desk = all(DESK_IS, q);
                for (String t : TRADER_ORDER) {
                    Map<String, Object> d = TRADERS.get(t);
                    if (desk.isEmpty() || d.get("DESK").equals(desk.get(0))) {
                        items.add(new Object[] {t, d.get("NAME"), m("NAME", d.get("NAME"), "DESK", d.get("DESK"), "TRADE_COUNT", d.get("TRADE_COUNT"))});
                    }
                }
                return rows("trader", items);
            }
            case "DESK" -> {
                DESK_ORDER.forEach(d -> items.add(new Object[] {d, DESKS.get(d).get("NAME"), m("NAME", DESKS.get(d).get("NAME"))}));
                return rows("desk", items);
            }
            case "TVAL" -> {
                List<String> books = all(BOOK_IS, q);
                List<String> desk = all(DESK_IS, q);
                List<String> floor = all(FLOOR, q);
                int limit = Integer.parseInt(all(LIMIT, q).get(0));
                List<Map<String, Object>> ts = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    Map<String, Object> t = trade(i, day);
                    if ((books.isEmpty() || books.contains(t.get("PORTFOLIO"))) && (desk.isEmpty() || t.get("DESK").equals(desk.get(0)))) {
                        ts.add(t);
                    }
                }
                int matched = ts.size();
                if (!floor.isEmpty()) {
                    double f = Double.parseDouble(floor.get(0));
                    ts.removeIf(t -> (Long) t.get("MTM") > f);
                }
                ts.sort(Comparator.comparing(t -> -(Long) t.get("MTM")));
                for (Map<String, Object> t : ts.subList(0, Math.min(limit, ts.size()))) {
                    Map<String, Object> v = new LinkedHashMap<>(t);
                    v.remove("MATURITY_DATE");
                    v.put("TRADER_ID", who.raw() ? t.get("TRADER_ID") : "•••");
                    items.add(new Object[] {"TVAL-" + t.get("TRADE_ID"), "TVAL-" + t.get("TRADE_ID"), v});
                }
                Map<String, Object> out = rows("trade-valuation", items);
                out.put("matched", matched);
                return out;
            }
            case "TRD" -> {
                List<String> books = all(BOOK_IS, q);
                for (Object[] e : EVENTS) {
                    Map<String, Object> t = trade((Integer) e[0], "2026-09-30");
                    if (books.isEmpty() || books.contains(t.get("PORTFOLIO"))) {
                        items.add(new Object[] {t.get("TRADE_ID"), t.get("TRADE_ID"), m("PORTFOLIO", t.get("PORTFOLIO"), "COUNTERPARTY", t.get("COUNTERPARTY"),
                                "PRODUCT_TYPE", t.get("PRODUCT_TYPE"), "TRADE_ACTION", e[1], "TRADE_STATUS", e[2], "TRADE_VERSION", e[3],
                                "TRADE_DATE", "2026-08-27", "SOURCE_UPDATE_TIME", "2026-09-30T" + e[4] + ":00Z")});
                    }
                }
                return rows("trade", items);
            }
            default -> throw new Refused(404, "DRS-4023", head + " is not part of the edition");
        }
    }

    Map<String, Object> raw(String kind, String id, String day) {
        if ("trader".equals(kind) && TRADERS.containsKey(id)) {
            Map<String, Object> d = m("TRADER_ID", id);
            d.putAll(TRADERS.get(id));
            return m("ref", m("kind", kind, "id", id), "data", d);
        }
        if ("desk".equals(kind) && DESKS.containsKey(id)) {
            Map<String, Object> d = m("DESK_ID", id);
            d.putAll(DESKS.get(id));
            return m("ref", m("kind", kind, "id", id), "data", d);
        }
        if ("trade-valuation".equals(kind) && id.startsWith("TVAL-MX-")) {
            int i = Integer.parseInt(id.substring(8)) - 20000001;
            return m("ref", m("kind", kind, "id", id), "provenance", m("businessDate", day), "data", trade(i, day));
        }
        throw new Refused(404, "DRS-1001", "no source holds " + kind + "/" + id);
    }

    /** A search as the live engine's result set (the page the real server gives): each row's document holds its values by field name. */
    Map<String, Object> liveQuery(Person who, String day, String q) {
        Map<String, Object> found = search(who, day, q);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> cols = new ArrayList<>();
        for (Object r : (List<?>) found.get("rows")) {
            Map<?, ?> row = (Map<?, ?>) r;
            Map<String, Object> doc = new LinkedHashMap<>();
            ((Map<?, ?>) row.get("values")).forEach((k, v) -> doc.put(((String) k).substring(2), v));
            if (cols.isEmpty()) {
                ((Map<?, ?>) row.get("values")).keySet().forEach(k -> cols.add((String) k));
            }
            rows.add(m("id", ((Map<?, ?>) row.get("ref")).get("id"), "version", 1, "seq", 1, "doc", doc));
        }
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("id", null);
        set.put("kind", found.get("kind"));
        return m("set", set, "live", false, "total", rows.size(), "seq", 1, "offset", 0, "rows", rows,
                "search", m("kind", found.get("kind"), "columns", cols, "matched", found.get("matched"), "partial", found.get("partial")));
    }

    Map<String, Object> var(Person who, String method, String path, Map<String, ?> params, Map<String, Object> body) {
        String scope = String.valueOf(params.containsKey("scope") ? params.get("scope") : body == null ? "" : body.getOrDefault("scope", ""));
        if ("GET".equals(method) && "/scope".equals(path)) {
            if (scope.startsWith("BOOK:") && !scope.contains(" ")) {
                double[] vl = BOOK_VAR.get(scope.substring(5));
                return m("scope", scope, "businessDate", "2026-09-30", "var", (long) vl[0], "es", (long) (vl[0] * 1.1), "limit", (long) vl[1],
                        "limitUse", vl[0] / vl[1], "limitStatus", status(vl[0] / vl[1]), "limitNear", NEAR, "confidence", 0.99, "trades", 4);
            }
            if (scope.startsWith("DESK:")) {
                return m("scope", scope, "businessDate", "2026-09-30", "var", 15_000_000, "limit", 20_000_000, "limitUse", 0.75, "limitStatus", "within",
                        "limitNear", NEAR, "confidence", 0.99, "trades", 11);
            }
            return m("scope", scope, "businessDate", "2026-09-30", "var", 8_000_000, "limit", null, "limitUse", null, "limitStatus", null,
                    "limitNear", NEAR, "confidence", 0.99, "trades", 8, "intraday", m("updates", 2, "trades", 3, "lastAt", "2026-09-30T14:05:00Z"));
        }
        if ("GET".equals(method) && "/scope/contributions".equals(path)) {
            if ("attribute:TRADER_ID".equals(params.get("by"))) {        // the server sums each trader's trades (masked without raw)
                if (!who.raw()) {
                    return m("by", "attribute", "attribute", "TRADER_ID", "masked", true, "rows", List.of(), "var", 15_000_000,
                            "note", "TRADER_ID is masked for your role: VaR by TRADER_ID needs a role that sees it");
                }
                Map<String, Long> groups = new LinkedHashMap<>();
                for (int i = 0; i < 11; i++) {
                    groups.merge((String) trade(i, "2026-09-30").get("TRADER_ID"), 1_000_000L + 100_000L * i, Long::sum);
                }
                List<Map<String, Object>> rs = new ArrayList<>();
                groups.forEach((t, v) -> rs.add(m("key", t, "label", t, "kind", "attribute", "componentVar", v)));
                return m("by", "attribute", "attribute", "TRADER_ID", "masked", false, "truncated", false, "total", groups.size(), "rows", rs);
            }
            return m("rows", List.of(m("label", "USD SOFR swaps 10Y", "scope", "POS:POS-BOOK-RATES-1~MX-20000001", "componentVar", 2_310_000,
                    "marketValue", -42_100_000, "book", "BOOK-RATES-1"), m("label", "USD swaptions 1Yx10Y", "scope", "POS:POS-BOOK-RATES-2~MX-20000002",
                    "componentVar", 710_000, "marketValue", 9_200_000, "book", "BOOK-RATES-2")));
        }
        if ("GET".equals(method) && "/official".equals(path)) {
            String day = String.valueOf(params.get("asOf"));
            int k = OFFICIAL_DAYS.indexOf(day);
            if (k < 0) {
                throw new Refused(404, "DRS-1001", "no official VaR record of " + day);
            }
            Map<String, Object> nodes = new LinkedHashMap<>();
            for (String b : BOOKS) {
                double v = BOOK_VAR.get(b)[0] * (0.9 + 0.025 * k);
                double lim = BOOK_VAR.get(b)[1];
                nodes.put("MGMT~" + b, m("var", v, "limit", (long) lim, "limitUse", v / lim, "limitStatus", status(v / lim)));
            }
            return m("businessDate", day, "nodes", nodes, "limitNear", NEAR);
        }
        if ("GET".equals(method) && "/scope/history".equals(path)) {
            List<Map<String, Object>> rs = new ArrayList<>();
            for (int d = 0; d < 64; d++) {
                rs.add(m("date", String.format("2026-%02d-%02d", 7 + d / 22, 1 + d % 22), "var", 15_000_000, "pnl", (d % 2 == 0 ? 1 : -1) * 4_000_000 * (1 + d % 3),
                        "exception", false));
            }
            rs.get(40).put("pnl", -16_500_000);
            rs.get(40).put("exception", true);
            return m("mode", "official", "rows", rs, "backtest", m("days", 64, "exceptions", 1, "light", "green"));
        }
        if ("POST".equals(method) && "/what-if".equals(path)) {
            double[] vl = BOOK_VAR.get(scope.substring(5));
            double f = ((Number) ((Map<?, ?>) ((List<?>) body.get("items")).get(0)).get("factor")).doubleValue();
            long after = (long) (vl[0] + 360_000 * f);
            return m("scope", scope, "before", m("var", (long) vl[0]), "after", m("var", after), "change", m("var", after - (long) vl[0]),
                    "confidence", 0.99, "currency", "USD", "limit", m("limit", (long) vl[1], "useBefore", vl[0] / vl[1], "useAfter", after / vl[1],
                            "statusBefore", status(vl[0] / vl[1]), "status", status(after / vl[1]), "near", NEAR),
                    "engine", "Java VaR engine (the server)", "note", "copies");
        }
        throw new Refused(404, "DRS-1001", "no VaR endpoint " + path);
    }

    Map<String, Object> exposure(String method, String path, Map<String, ?> params, Map<String, Object> body) {
        if ("GET".equals(method) && "/scope".equals(path)) {
            String scope = String.valueOf(params.get("scope"));
            if ("CPTY:CP-ALDERSHOT".equals(scope)) {
                return m("scope", scope, "peakPfe", 401_500_000, "limit", m("limit", 420_000_000, "confidence", 0.95, "used", 401_500_000),
                        "byNettingSet", List.of(m("nettingSet", "NS-ALDERSHOT-LDN", "peakPfe", 90_000_000), m("nettingSet", "NS-ALDERSHOT-NY", "peakPfe", 300_000_000)));
            }
            if ("CPTY:CP-NORTHPLAINS".equals(scope)) {
                return m("scope", scope, "peakPfe", 50_000_000, "limit", null, "byNettingSet", List.of());
            }
            throw new Refused(404, "DRS-5401", "no exposure of " + scope);
        }
        if ("POST".equals(method) && "/predeal".equals(path)) {
            String cp = String.valueOf(body.get("counterparty"));
            boolean aldershot = "CP-ALDERSHOT".equals(cp);
            double base = aldershot ? 401_500_000 : 50_000_000;
            boolean exact = body.get("like") != null && !"".equals(body.get("like"));
            Map<String, Object> lim = aldershot ? m("id", "LIM-ALDERSHOT", "limit", 420_000_000, "confidence", 0.95, "before", base, "after", base + 7_700_000,
                    "useBefore", base / 420_000_000, "useAfter", (base + 7_700_000) / 420_000_000, "near", 0.8, "status", "near") : null;
            List<String> reasons = new ArrayList<>();
            if (!exact) {
                reasons.add("a proxy answer only refers, never approves");
            }
            if (lim == null) {
                reasons.add("no credit limit on record for " + cp);
            }
            String decision = reasons.isEmpty() ? "approve" : "refer";
            if (reasons.isEmpty()) {
                reasons.add("exact: within the limit");
            }
            Map<String, Object> ns = new LinkedHashMap<>();
            ns.put("id", exact ? "NS-ALDERSHOT-NY" : null);
            return m("checkId", "PDC-TEST", "command", "PREDEAL " + cp + " " + (exact ? "LIKE " + body.get("like") : "RATES"), "scope", "CPTY:" + cp,
                    "decision", decision, "reason", reasons.get(0), "reasons", reasons, "confidence", 0.95,
                    "method", m("id", exact ? "paths" : "addon", "exact", exact, "text", "copies"), "nettingSet", ns, "limit", lim,
                    "exposure", m("base", m("peakPfe", base), "with", m("peakPfe", base + 7_700_000)), "flags", List.of(exact ? "exact" : "proxy"));
        }
        if ("POST".equals(method) && "/what-if".equals(path)) {
            double base = "CPTY:CP-ALDERSHOT".equals(body.get("scope")) ? 401_500_000 : 50_000_000;
            return m("base", m("peakPfe", base), "with", m("peakPfe", base + 7_700_000), "incremental", m("peakPfe", 7_700_000), "method", body.get("method"),
                    "flags", "addon".equals(body.get("method")) ? List.of("proxy") : List.of());
        }
        throw new Refused(404, "DRS-1001", "no exposure endpoint " + path);
    }

    static Map<String, Object> businessDate(LocalDate selected) {
        return m("current", "2026-09-30", "selected", selected == null ? "2026-09-30" : selected.toString(), "live", selected == null,
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

    /** The stand-in's JSON-shaped answers as the kernel's typed records (what the landings read back through {@code Asker}). */
    static final class Typed {
        private Typed() {
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> map(Object o) {
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        static List<Map<String, Object>> list(Object o) {
            return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        }

        static com.ash.drishti.api.EntityRef ref(Object r) {
            Map<String, Object> m = map(r);
            return com.ash.drishti.api.EntityRef.of((String) m.get("kind"), (String) m.get("id"));
        }

        static long num(Object o) {
            return o instanceof Number n ? n.longValue() : 0;
        }

        static com.ash.drishti.kernel.api.services.SearchAnswer search(Map<String, Object> a) {
            List<com.ash.drishti.kernel.api.services.SearchAnswer.Row> rows = new ArrayList<>();
            for (Map<String, Object> r : list(a.get("rows"))) {
                rows.add(new com.ash.drishti.kernel.api.services.SearchAnswer.Row(ref(r.get("ref")), (String) r.get("title"), map(r.get("values"))));
            }
            return new com.ash.drishti.kernel.api.services.SearchAnswer("", String.valueOf(a.get("kind")), "", "", "", false, 0, List.of(), Map.of(),
                    num(a.get("scanned")), num(a.get("matched")), Boolean.TRUE.equals(a.get("partial")), List.of(), 0,
                    null, a.get("noData") instanceof Map<?, ?> ? map(a.get("noData")) : null, rows, null);
        }

        static com.ash.drishti.kernel.api.services.RawRecord raw(Map<String, Object> a) {
            Object day = map(a.get("provenance")).get("businessDate");
            com.ash.drishti.api.Provenance prov = new com.ash.drishti.api.Provenance("stand-in", 0, java.time.Instant.EPOCH, false,
                    day == null ? null : LocalDate.parse(String.valueOf(day)));
            return new com.ash.drishti.kernel.api.services.RawRecord(ref(a.get("ref")), prov, com.ash.drishti.api.DataNode.of(a.get("data")));
        }

        static com.ash.drishti.kernel.api.services.LivePage live(Map<String, Object> a) {
            return new com.ash.drishti.kernel.api.services.LivePage(map(a.get("set")), Boolean.TRUE.equals(a.get("live")), (int) num(a.get("total")),
                    num(a.get("seq")), (int) num(a.get("offset")), list(a.get("rows")), map(a.get("search")), null);
        }

        static com.ash.drishti.kernel.api.services.Series series(String kind, String id, Map<String, Object> a) {
            List<com.ash.drishti.kernel.api.services.Series.Point> pts = new ArrayList<>();
            for (Map<String, Object> p : list(a.get("points"))) {
                pts.add(new com.ash.drishti.kernel.api.services.Series.Point(LocalDate.parse(String.valueOf(p.get("date"))), p.get("value"),
                        p.get("dataDate") == null ? null : LocalDate.parse(String.valueOf(p.get("dataDate"))), (String) p.get("source"),
                        p.get("generation") == null ? null : num(p.get("generation"))));
            }
            return new com.ash.drishti.kernel.api.services.Series(com.ash.drishti.api.EntityRef.of(kind, id), String.valueOf(a.get("path")),
                    (String) a.get("label"), pts, !Boolean.FALSE.equals(a.get("dated")));
        }
    }
}
