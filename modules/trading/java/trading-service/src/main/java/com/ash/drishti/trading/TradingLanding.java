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

import static com.ash.drishti.kernel.api.module.Answers.day;
import static com.ash.drishti.kernel.api.module.Answers.get;
import static com.ash.drishti.kernel.api.module.Answers.list;
import static com.ash.drishti.kernel.api.module.Answers.map;
import static com.ash.drishti.kernel.api.module.Answers.masked;
import static com.ash.drishti.kernel.api.module.Answers.num;
import static com.ash.drishti.kernel.api.module.Answers.round;
import static com.ash.drishti.kernel.api.module.Answers.str;

import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.module.InlineFigures.Field;
import com.ash.drishti.kernel.api.module.InlineFigures.Figures;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Kernel.Refused;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.module.PersonCache;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Drishti Trading's Home (docs/guides/ADMIN_GUIDE.md › The design; THIN_CONSOLE_DESIGN.md step S8a): who "my" is, and the figures of the
 * Trader desk and Desk head landings, worked out here, in the trading module's server part, from the server's own answers read as the
 * person (their roles, masks, lens and date box), read-only. The console only draws them.
 *
 * <p><b>"My" scope.</b> A person maps to a trader (their books) or a desk: first {@code drishti.trading.people} (the server's settings),
 * then the lens's {@code trading.people}, then a trader record whose {@code TRADER_ID} is {@code TRDR-<USER>} or whose {@code NAME} is
 * theirs ({@code trading.match-records}), then their own "View as" choice, kept in their settings ({@code tradingView}: {@code trader:<id>}
 * or {@code desk:<id>}). An administrator may always pick, and their choice wins. The scope only chooses what the landing shows: every read
 * is still the person's own.
 *
 * <p><b>The figures</b>, each from an existing answer, never invented (a figure the data cannot give is null, or the panel says why).
 * Every total (MtM, P&amp;L, DV01, by book, by trader, the largest counterparty's share) is the pivot engine's ({@link InlineFigures}); this
 * class adds up nothing. P&amp;L today: the MtM change of every trade valued on both the business date and the business day before
 * ({@code TVAL}); VaR and limit use: the VaR engine's own answers; today's trades: the trades whose {@code SOURCE_UPDATE_TIME} is on the
 * business date; the limit use by book: each date's official VaR record; P&amp;L against VaR: the desk's official history; the new-trade
 * check: one VaR what-if and one pre-deal check, each the engine's own, on a copy of a booked trade of the product scaled to the notional.
 * Drishti never prices: nothing here values a trade.
 */
@Service
public class TradingLanding {

    static final List<String> LIVE = List.of("NEW", "VALIDATED", "AMENDED");
    static final Pattern VIEW = Pattern.compile("^(trader|desk):([A-Za-z0-9][A-Za-z0-9._~-]{0,79})$");
    static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,79}$");
    /** The server's most rows per search ({@code SearchQuery.MAX_LIMIT}). */
    static final int PAGE = 1000;
    /** The valuation fields read per trade (columns of the trade-valuation store: the search reads no document). */
    static final List<String> FIELDS = List.of("TRADE_ID", "PORTFOLIO", "DESK", "COUNTERPARTY", "PRODUCT_TYPE", "PRODUCT_GROUP", "TRADE_STATUS",
            "TRADER_ID", "BUY_SELL_CODE", "NOTIONAL", "DV01", "CURRENCY");
    /** A limit's status as the server says it, in the landing's words: nothing is decided here. */
    static final Map<String, String> STATES = Map.of("within", "ok", "near", "near", "breach", "breach");
    private static final long TTL_MILLIS = 120_000;

    private final Kernel kernel;
    private final RiskFigures risk;
    private final Environment env;
    /** What was read, per person (the caller's key: user, roles, lens, date box), two minutes each, the 64 most recent. */
    private final PersonCache<Object> cache;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public TradingLanding(Kernel kernel, Environment env) {
        this.kernel = kernel;
        this.risk = kernel.operations().of("banking", RiskFigures.class);
        this.env = env;
        this.cache = kernel.cache(64, TTL_MILLIS);
    }

    /** The person of the request in hand. */
    public TradingAsker caller() {
        return new Asking(kernel, risk, kernel.person());
    }

    /** One person's questions of the kernel. */
    private record Asking(Kernel kernel, RiskFigures risk, Person person) implements TradingAsker {
        @Override
        public TradingAsker as(Person p) {
            return new Asking(kernel, risk, p);
        }
    }

    // -- small helpers ------------------------------------------------------------------------------------------------------------------
    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    static String q(String s) {
        return "'" + String.valueOf(s).replace("'", "''") + "'";
    }

    private <T> CompletableFuture<T> async(Supplier<T> s) {
        return CompletableFuture.supplyAsync(s, pool);
    }

    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }

    /** An answer, or why not: {@code (answer, why, code)}. */
    record Asked(Map<String, Object> answer, String why, String code) {
        static Asked of(Supplier<Map<String, Object>> call) {
            try {
                return new Asked(call.get(), null, null);
            } catch (Refused e) {
                return new Asked(null, e.say(), e.code());
            }
        }
    }

    static Map<String, Object> search(TradingAsker c, String query) {
        return c.search(query);
    }

    /** A document as the server holds it ({@code GET /api/v1/entities/{kind}/{id}/raw}): an id a path cannot carry goes in the query. */
    static Map<String, Object> raw(TradingAsker c, String kind, String id) {
        return c.raw(kind, id);
    }

    static int count(Map<String, Object> cfg, String key, int dflt, int lo, int hi) {
        Double v = num(cfg.get(key));
        return v == null ? dflt : Math.max(lo, Math.min(v.intValue(), hi));
    }

    static List<String> live(Map<String, Object> cfg) {
        List<Object> l = list(cfg, "live-statuses");
        return l.isEmpty() ? LIVE : l.stream().map(x -> str(x).toUpperCase(Locale.ROOT)).toList();
    }

    static String stateOf(Object status) {
        return STATES.getOrDefault(str(status), "none");
    }

    // -- configuration ------------------------------------------------------------------------------------------------------------------
    /** The lens's {@code trading:} block with the server's {@code drishti.trading.people} over its people (a name in both: the server's). */
    public Map<String, Object> config(TradingAsker c) {
        Map<String, Object> block = new LinkedHashMap<>(kernel.lenses().block(c.lens(), "trading"));
        Map<String, Object> people = new LinkedHashMap<>();
        map(block, "people").forEach((k, v) -> {
            if (v instanceof Map<?, ?>) {
                people.put(k.toLowerCase(Locale.ROOT), v);
            }
        });
        Bindable<Map<String, Map<String, String>>> type = Bindable.of(ResolvableType.forClassWithGenerics(Map.class,
                ResolvableType.forClass(String.class), ResolvableType.forClassWithGenerics(Map.class, String.class, String.class)));
        Binder.get(env).bind("drishti.trading.people", type).orElse(Map.of())
                .forEach((k, v) -> people.put(k.toLowerCase(Locale.ROOT), new LinkedHashMap<String, Object>(v)));
        block.put("people", people);
        return block;
    }

    // -- "my" scope ---------------------------------------------------------------------------------------------------------------------
    /** Who "my" is. Mutable while it is made; a copy is kept. */
    static final class Scope {
        String kind = "";
        String id = "";
        String role = "";
        String source = "none";
        String name = "";
        String desk = "";
        String deskName = "";
        String head = "";
        List<String> books = new ArrayList<>();
        boolean canPick;
        List<String> problems = new ArrayList<>();

        Scope copy() {
            Scope s = new Scope();
            s.kind = kind;
            s.id = id;
            s.role = role;
            s.source = source;
            s.name = name;
            s.desk = desk;
            s.deskName = deskName;
            s.head = head;
            s.books = new ArrayList<>(books);
            s.canPick = canPick;
            s.problems = new ArrayList<>(problems);
            return s;
        }

        Map<String, Object> asMap() {
            return m("kind", kind, "id", id, "role", role, "source", source, "name", name, "desk", desk, "deskName", deskName, "head", head,
                    "books", List.copyOf(books), "canPick", canPick, "problems", List.copyOf(problems));
        }

        String booksKey() {
            return String.join(",", books);
        }
    }

    /** {@code trader:<id>} or {@code desk:<id>} as {kind, id}; null for anything else. */
    static String[] parseView(Object value) {
        Matcher mt = VIEW.matcher(str(value).strip());
        return mt.matches() ? new String[] {mt.group(1), mt.group(2)} : null;
    }

    /** A {@code people} entry as {kind, id, role}: {@code {trader: TRDR-ASHAH}} or {@code {desk: DESK-COMM, role: desk-head}}. */
    static String[] mapping(Object entry) {
        if (!(entry instanceof Map<?, ?> e)) {
            return null;
        }
        String role = str(e.get("role")).strip().toLowerCase(Locale.ROOT);
        for (String kind : List.of("trader", "desk")) {
            String v = str(e.get(kind)).strip();
            if (!v.isEmpty() && SAFE_ID.matcher(v).matches()) {
                return new String[] {kind, v, role.isEmpty() ? ("desk".equals(kind) ? "desk-head" : "trader") : role};
            }
        }
        return null;
    }

    private Map<String, Object> settings(TradingAsker c) {
        try {
            return c.settings();
        } catch (Refused e) {
            return Map.of();
        }
    }

    /** A trader record that is this person's: {@code TRDR-<USER>} (case and dots ignored), or the same name as their display name. */
    private String recordMatch(TradingAsker c) {
        String user = c.user().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String display = c.display().strip().toLowerCase(Locale.ROOT);
        Map<String, Object> found;
        try {
            found = search(c, "TRDR limit 500");
        } catch (Refused e) {
            return null;
        }
        for (Object r : list(found, "rows")) {
            String rid = str(map(r, "ref"), "id");
            String flat = rid.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (!rid.isEmpty() && (flat.equals("TRDR" + user) || flat.equals(user))) {
                return rid;
            }
        }
        for (Object r : list(found, "rows")) {
            String rid = str(map(r, "ref"), "id");
            Object title = get(r, "title");
            String name = str(title != null && !"".equals(title) ? title : get(map(r, "values"), "$.NAME")).strip().toLowerCase(Locale.ROOT);
            if (!rid.isEmpty() && !display.isEmpty() && name.equals(display)) {
                return rid;
            }
        }
        return null;
    }

    /** Who "my" is for this person, with the trader's or desk's books read from its record as the person. */
    public Scope resolve(TradingAsker c, Map<String, Object> cfg) {
        String[] mapped = mapping(map(cfg, "people").get(c.user().toLowerCase(Locale.ROOT)));
        String[] view = parseView(settings(c).get("tradingView"));
        String key = "scope\u0001" + c.key() + "\u0001" + (mapped == null ? "" : String.join(":", mapped)) + "\u0001"
                + (view == null ? "" : String.join(":", view));
        Object hit = cache.get(key);
        if (hit instanceof Scope s) {
            return s.copy();
        }
        String[] chosen = null;                                   // kind, id, role, source
        if (view != null && c.admin()) {
            chosen = new String[] {view[0], view[1], "desk".equals(view[0]) ? "desk-head" : "trader", "choice"};
        } else if (mapped != null) {
            chosen = new String[] {mapped[0], mapped[1], mapped[2], "config"};
        } else if (!Boolean.FALSE.equals(cfg.get("match-records")) && recordMatch(c) instanceof String rid) {
            chosen = new String[] {"trader", rid, "trader", "record"};
        } else if (view != null) {
            chosen = new String[] {view[0], view[1], "desk".equals(view[0]) ? "desk-head" : "trader", "choice"};
        }
        Scope s = new Scope();
        s.canPick = c.admin();
        if (chosen == null) {
            s.canPick = true;
            cache.put(key, s.copy());
            return s;
        }
        s.kind = chosen[0];
        s.id = chosen[1];
        s.role = chosen[2];
        s.source = chosen[3];
        s.canPick = c.admin() || "choice".equals(s.source);
        Map<String, Object> doc;
        try {
            doc = map(raw(c, "trader".equals(s.kind) ? "trader" : "desk", s.id), "data");
        } catch (Refused e) {
            s.problems.add(s.kind + " " + s.id + ": " + e.say());
            doc = Map.of();
        }
        s.name = masked(doc.get("NAME")) ? "" : str(doc.get("NAME"));
        for (Object b : list(doc, "BOOKS")) {
            String id = b instanceof Map<?, ?> bm ? str(bm.get("ID")) : str(b);
            if (!id.isEmpty() && SAFE_ID.matcher(id).matches()) {
                s.books.add(id);
            }
        }
        if ("trader".equals(s.kind)) {
            s.desk = str(doc.get("DESK"));
            s.deskName = str(doc.get("DESK_NAME"));
        } else {
            s.desk = s.id;
            s.deskName = s.name;
            s.head = str(doc.get("HEAD"));
        }
        cache.put(key, s.copy());
        return s;
    }

    /** The Desk head landing of a trader: their desk (its record's books, read as the person); a desk stays itself. */
    public Scope deskOf(TradingAsker c, Scope scope) {
        if (!"trader".equals(scope.kind) || !SAFE_ID.matcher(scope.desk.isEmpty() ? "-" : scope.desk).matches()) {
            return scope;
        }
        Scope out = scope.copy();
        out.kind = "desk";
        out.id = scope.desk;
        out.books = new ArrayList<>();
        Map<String, Object> doc;
        try {
            doc = map(raw(c, "desk", scope.desk), "data");
        } catch (Refused e) {
            out.problems.add("desk " + scope.desk + ": " + e.say());
            doc = Map.of();
        }
        for (Object b : list(doc, "BOOKS")) {
            String id = b instanceof Map<?, ?> bm ? str(bm.get("ID")) : "";
            if (SAFE_ID.matcher(id.isEmpty() ? "-" : id).matches()) {
                out.books.add(id);
            }
        }
        String name = str(doc.get("NAME"));
        out.deskName = name.isEmpty() ? scope.deskName : name;
        out.head = str(doc.get("HEAD"));
        return out;
    }

    /** What the View as picker offers: the traders and desks this person may read (their search, as them). */
    public Map<String, Object> options(TradingAsker c) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String[] wk : new String[][] {{"TRDR", "traders"}, {"DESK", "desks"}}) {
            List<Map<String, Object>> items = new ArrayList<>();
            try {
                for (Object r : list(search(c, wk[0] + " where NAME != null limit 200"), "rows")) {
                    String rid = str(map(r, "ref"), "id");
                    if (SAFE_ID.matcher(rid).matches()) {
                        Object name = get(map(r, "values"), "$.NAME");
                        Object title = get(r, "title");
                        String shown = name != null && !"".equals(name) && !masked(name) ? str(name)
                                : title != null && !"".equals(title) ? str(title) : rid;
                        items.add(m("id", rid, "name", shown));
                    }
                }
            } catch (Refused e) {
                // not readable by this person: nothing offered of it
            }
            out.put(wk[1], items);
        }
        return out;
    }

    /** The person's trading role without reading the data (which landing opens first): their mapping's, else what their View as names. */
    public String tradingRole(TradingAsker c, Map<String, Object> cfg) {
        String[] mapped = mapping(map(cfg, "people").get(c.user().toLowerCase(Locale.ROOT)));
        String[] view = parseView(settings(c).get("tradingView"));
        if (view != null && (mapped == null || c.admin())) {
            return "desk".equals(view[0]) ? "desk-head" : "trader";
        }
        return mapped == null ? "" : mapped[2];
    }

    // -- dates --------------------------------------------------------------------------------------------------------------------------
    static boolean businessDay(LocalDate d, Set<String> holidays) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d.toString());
    }

    static LocalDate previousBusinessDay(LocalDate d, Set<String> holidays) {
        LocalDate x = d.minusDays(1);
        while (!businessDay(x, holidays)) {
            x = x.minusDays(1);
        }
        return x;
    }

    /** The last {@code n} business days up to {@code end} (itself first when it is one), oldest first. */
    static List<LocalDate> businessDays(LocalDate end, int n, Set<String> holidays) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = end;
        while (out.size() < n) {
            if (businessDay(d, holidays)) {
                out.add(0, d);
            }
            d = d.minusDays(1);
        }
        return out;
    }

    /** The business date the person looks at (their date box, else the current) and the server's holidays; null date when none. */
    record Dates(LocalDate business, Set<String> holidays) {
    }

    static Dates dates(TradingAsker c) {
        try {
            Map<String, Object> bd = c.dates();
            Object sel = get(bd, "selected");
            Set<String> h = new HashSet<>();
            list(bd, "holidays").forEach(x -> h.add(str(x)));
            return new Dates(day(sel != null && !"".equals(sel) ? sel : get(bd, "current")), h);
        } catch (Refused e) {
            return new Dates(null, Set.of());
        }
    }

    // -- the trade valuations of a scope on one date ----------------------------------------------------------------------------------
    /** The search condition of a scope: its desk ({@code DESK}), or a trader's books ({@code PORTFOLIO}); null without books. */
    static String scopeCondition(Scope scope) {
        if ("desk".equals(scope.kind)) {
            return "DESK = " + q(scope.id);
        }
        if (!scope.books.isEmpty()) {
            return "(" + String.join(" or ", scope.books.stream().map(b -> "PORTFOLIO = " + q(b)).toList()) + ")";
        }
        return null;
    }

    /** One date's valuations: trade id → fields (with MTM and VALUATION_ID), how many matched, whether partial, the server's noData. */
    record Read(Map<String, Map<String, Object>> rows, int matched, boolean partial, Object noData) {
    }

    /**
     * Every trade valuation matching {@code condition} on the caller's business date, a page of the server's most at a time (by MTM,
     * largest first; each next page from the last MTM down, ids already read skipped).
     */
    static Read readValuations(TradingAsker c, String condition, int maxRows) {
        String names = String.join(" or ", FIELDS.stream().map(f -> f + " != null").toList());
        String base = "TVAL where " + condition + " and (" + names + ")";
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        Integer matched = null;
        boolean partial = false;
        Double floor = null;
        Object noData = null;
        while (true) {
            String query = base + (floor != null ? " and MTM <= " + pyRepr(floor) : "") + " order by MTM desc limit " + PAGE;
            Map<String, Object> a = search(c, query);
            if (matched == null) {
                Double mm = num(a.get("matched"));
                matched = mm == null ? 0 : mm.intValue();
                noData = a.get("noData");
            }
            partial = partial || Boolean.TRUE.equals(a.get("partial"));
            int fresh = 0;
            Double last = null;
            List<Object> got = list(a, "rows");
            for (Object r : got) {
                Map<String, Object> vals = map(r, "values");
                Object t = vals.get("$.TRADE_ID");
                String tid = t != null && !"".equals(t) ? str(t) : str(map(r, "ref"), "id");
                if (tid.isEmpty() || rows.containsKey(tid)) {
                    continue;
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                for (String f : FIELDS) {
                    rec.put(f, vals.get("$." + f));
                }
                rec.put("MTM", vals.get("$.MTM"));
                rec.put("VALUATION_ID", str(map(r, "ref"), "id"));
                rows.put(tid, rec);
                fresh++;
                if (num(rec.get("MTM")) != null) {
                    last = num(rec.get("MTM"));
                }
            }
            if (rows.size() >= matched || got.size() < PAGE || fresh == 0 || last == null) {
                break;
            }
            if (rows.size() >= maxRows) {
                partial = true;
                break;
            }
            floor = last;
        }
        return new Read(rows, matched == null ? 0 : matched, partial || rows.size() < (matched == null ? 0 : matched), noData);
    }

    /** A number as the search grammar reads it back exactly (Python's repr of a float: the shortest text that reads back the same). */
    static String pyRepr(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e16) {
            return String.valueOf((long) v) + ".0";
        }
        return Double.toString(v);
    }

    /** The day a read's valuations are of: one of them read whole, its provenance's business date (null when there is none to read). */
    static LocalDate dataDate(TradingAsker c, Read read) {
        if (read == null || read.rows().isEmpty()) {
            return null;
        }
        Map<String, Object> first = read.rows().values().iterator().next();
        String vid = str(first.get("VALUATION_ID"));
        if (vid.isEmpty()) {
            return null;
        }
        try {
            return day(get(map(raw(c, "trade-valuation", vid), "provenance"), "businessDate"));
        } catch (Refused e) {
            return null;
        }
    }

    /** The valuations' answer with what the other panels use (never sent: the rows, the day's changes). */
    record Valuations(Map<String, Object> answer, Map<String, Map<String, Object>> rows, Map<String, Object> changes) {
        boolean available() {
            return Boolean.TRUE.equals(answer.get("available"));
        }
    }

    /**
     * The scope's trade valuations on the business date and the business day before, and what they give (the P&amp;L tiles, by book and
     * trader, overnight movers, DV01, the largest counterparty share), kept two minutes per person, date and scope.
     */
    public Valuations valuations(TradingAsker c, Scope scope, Map<String, Object> cfg) {
        String cond = scopeCondition(scope);
        Dates d = dates(c);
        if (cond == null || d.business() == null) {
            return new Valuations(m("available", false, "reason", cond == null ? "no books in your scope" : "the server gave no business date"),
                    Map.of(), null);
        }
        int maxRows = count(cfg, "max-rows", 20000, PAGE, 200000);
        String key = "tval\u0001" + c.key() + "\u0001" + scope.kind + "\u0001" + scope.id + "\u0001" + scope.booksKey() + "\u0001" + d.business();
        try {
            return (Valuations) cache.once(key, () -> makeValuations(c, cond, maxRows, d, cfg));
        } catch (Refused e) {
            return new Valuations(m("available", false, "reason", e.say()), Map.of(), null);
        }
    }

    private Valuations makeValuations(TradingAsker c, String cond, int maxRows, Dates d, Map<String, Object> cfg) {
        Read now = readValuations(c, cond, maxRows);
        // the day the valuations are really of (a lake not loaded since serves its latest day on later dates): its day before is compared
        LocalDate dataDay = dataDate(c, now);
        if (dataDay == null) {
            dataDay = d.business();
        }
        LocalDate before = previousBusinessDay(dataDay, d.holidays());
        Read was = null;
        String wasProblem = null;
        try {
            TradingAsker then = c.onDate(before);
            was = readValuations(then, cond, maxRows);
            LocalDate wasDay = dataDate(then, was);
            if (wasDay != null && !wasDay.equals(before)) {           // carried from an earlier day: no valuations of that day
                was = new Read(Map.of(), was.matched(), was.partial(), m("businessDate", before.toString(), "latestDate", wasDay.toString()));
            }
        } catch (Refused e) {
            wasProblem = e.say();
            was = null;
        }
        List<String> live = live(cfg);
        Object[] figures = engineFigures(c, now, was, live);
        Map<String, Object> out = summarise(now, was, dataDay, before, live, wasProblem, figures);
        out.put("businessDate", d.business().toString());
        out.put("dataDate", dataDay.toString());
        Map<String, Object> changes = Boolean.TRUE.equals(out.get("hasPrevious")) ? changes(now.rows(), was.rows(), dataDay) : null;
        return new Valuations(out, now.rows(), changes);
    }

    /** The rows the engine aggregates, one per trade valuation of the business date, and what it is asked. */
    static final List<Field> FIG_FIELDS = List.of(new Field("BOOK", "string"), new Field("TRADER", "string"), new Field("CP", "string"),
            new Field("MTM", "number"), new Field("CHANGE", "number"), new Field("DV01", "number"), new Field("POS", "number"));
    static final List<Map<String, Object>> FIG_TOTALS = List.of(InlineFigures.measure("sum(MTM)", "mtm"), InlineFigures.measure("sum(CHANGE)", "pnl"),
            InlineFigures.measure("sum(DV01)", "dv01"), InlineFigures.measure("count(DV01)", "dv01Trades"));
    static final List<Map<String, Object>> FIG_COUNTERPARTY = List.of(InlineFigures.measure("sum(POS)", "positive"),
            InlineFigures.measure("sum(POS)", "share", "pct-grand"));

    static boolean hadBefore(Read was) {
        return was != null && !truthy(was.noData()) && !was.rows().isEmpty();
    }

    /** As a JSON value reads in a condition: null, false, 0, "" and an empty list or object are false. */
    static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v) || "".equals(v)) {
            return false;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof Map<?, ?> mm) {
            return !mm.isEmpty();
        }
        return !(v instanceof List<?> l) || !l.isEmpty();
    }

    /**
     * The rows the server's engine adds up (pure), in FIG_FIELDS' order: each valuation's book, trader (left out when masked), MtM, its
     * change since the day before (a trade valued on both days only), DV01 (live trades only) and, for a live trade whose MtM is
     * positive, its counterparty and that MtM. No total is made here.
     */
    static List<List<Object>> figureRows(Read now, Read was, List<String> live) {
        Map<String, Map<String, Object>> old = was == null ? Map.of() : was.rows();
        boolean had = hadBefore(was);
        List<List<Object>> out = new ArrayList<>();
        now.rows().forEach((tid, r) -> {
            Double m1 = num(r.get("MTM"));
            Map<String, Object> p = had ? old.get(tid) : null;
            Double m0 = p == null ? null : num(p.get("MTM"));
            Object trader = r.get("TRADER_ID");
            Object cp = r.get("COUNTERPARTY");
            boolean liveNow = live.contains(str(r.get("TRADE_STATUS")).toUpperCase(Locale.ROOT));
            boolean positive = liveNow && m1 != null && m1 > 0 && cp != null && !"".equals(cp) && !masked(cp);
            String book = str(r.get("PORTFOLIO"));
            List<Object> row = new ArrayList<>();
            row.add(book.isEmpty() ? null : book);
            row.add(trader != null && !"".equals(trader) && !masked(trader) ? str(trader) : null);
            row.add(positive ? str(cp) : null);
            row.add(m1);
            row.add(m1 != null && m0 != null ? m1 - m0 : null);
            row.add(liveNow ? num(r.get("DV01")) : null);
            row.add(positive ? m1 : null);
            out.add(row);
        });
        return out;
    }

    /** The scope's totals from the server's pivot engine: {totals, counterparties, problem}. */
    Object[] engineFigures(TradingAsker c, Read now, Read was, List<String> live) {
        List<List<Object>> rows = figureRows(now, was, live);
        try {
            CompletableFuture<Figures> totals = async(() -> c.figures(FIG_FIELDS, rows, FIG_TOTALS, "BOOK", "TRADER", null));
            CompletableFuture<Figures> cps = async(() -> c.figures(FIG_FIELDS, rows, FIG_COUNTERPARTY, "CP", null,
                    Map.of("CP", Map.of("n", 1, "by", "positive"))));
            return new Object[] {join(totals), join(cps), null};
        } catch (Refused e) {
            return new Object[] {null, null, "the server's engine did not give the totals: " + e.say()};
        } catch (IllegalArgumentException e) {
            return new Object[] {null, null, "the server's engine did not give the totals: " + e.getMessage()};
        }
    }

    /**
     * The landing's answer of two dates' valuations (pure): counts, movers and lists from the rows; every total from {@code figures} (the
     * server's engine), shown as it comes, rounded for display.
     */
    static Map<String, Object> summarise(Read now, Read was, LocalDate business, LocalDate before, List<String> live, String wasProblem,
            Object[] figures) {
        Map<String, Map<String, Object>> rows = now.rows();
        Map<String, Map<String, Object>> old = was == null ? Map.of() : was.rows();
        boolean had = hadBefore(was);
        Figures totals = figures == null ? null : (Figures) figures[0];
        Figures cps = figures == null ? null : (Figures) figures[1];
        String problem = figures == null ? "the server's engine was not asked" : (String) figures[2];
        Map<String, Map<String, Object>> byBook = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byTrader = new LinkedHashMap<>();
        List<Map<String, Object>> movers = new ArrayList<>();
        boolean traderMasked = rows.values().stream().anyMatch(r -> masked(r.get("TRADER_ID")));
        boolean mtmMasked = rows.values().stream().anyMatch(r -> masked(r.get("MTM")));
        int both = 0;
        for (Map.Entry<String, Map<String, Object>> e : rows.entrySet()) {
            Map<String, Object> r = e.getValue();
            Double m1 = num(r.get("MTM"));
            Map<String, Object> p = old.get(e.getKey());
            Double m0 = p == null ? null : num(p.get("MTM"));
            String book = str(r.get("PORTFOLIO"));
            Object trader = r.get("TRADER_ID");
            boolean liveNow = live.contains(str(r.get("TRADE_STATUS")).toUpperCase(Locale.ROOT));
            Map<String, Object> b = byBook.computeIfAbsent(book, k -> m("book", k, "trades", 0));
            Map<String, Object> t = trader != null && !"".equals(trader) && !masked(trader)
                    ? byTrader.computeIfAbsent(str(trader), k -> m("trader", k, "trades", 0)) : null;
            if (liveNow) {
                b.put("trades", (Integer) b.get("trades") + 1);
                if (t != null) {
                    t.put("trades", (Integer) t.get("trades") + 1);
                }
            }
            if (m1 != null && m0 != null) {
                both++;
                double dlt = m1 - m0;
                if (dlt != 0) {
                    movers.add(m("trade", e.getKey(), "book", book, "product", r.get("PRODUCT_TYPE"), "counterparty", r.get("COUNTERPARTY"),
                            "change", round(dlt), "mtm", round(m1), "valuation", r.get("VALUATION_ID")));
                }
            }
        }
        movers.sort(Comparator.comparingLong(x -> -Math.abs((Long) x.get("change"))));
        List<Map<String, Object>> liveRows = rows.values().stream().filter(r -> live.contains(str(r.get("TRADE_STATUS")).toUpperCase(Locale.ROOT))).toList();
        Map<String, Integer> products = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Map<String, Object> r : liveRows) {
            Double notional = num(r.get("NOTIONAL"));
            if (!str(r.get("PRODUCT_TYPE")).isEmpty() && notional != null && notional != 0) {
                products.merge(str(r.get("PRODUCT_TYPE")), 1, Integer::sum);
            }
            if (!str(r.get("COUNTERPARTY")).isEmpty() && !masked(r.get("COUNTERPARTY"))) {
                seen.merge(str(r.get("COUNTERPARTY")), 1, Integer::sum);
            }
        }
        java.util.function.BiFunction<Double, Object, Object> shown = (v, empty) -> v != null ? (Object) round(v) : (totals != null ? empty : null);
        Map<String, Object> largest = null;
        if (cps != null) {
            Object top = cps.members().stream().filter(x -> !"".equals(x) && !InlineFigures.BLANK.equals(x)).findFirst().orElse(null);
            Double allPositive = cps.get("positive");
            if (top != null && cps.get("positive", top, null) != null && allPositive != null && allPositive > 0) {
                largest = m("counterparty", top, "share", cps.get("share", top, null), "positiveMtm", round(cps.get("positive", top, null)),
                        "totalPositiveMtm", round(allPositive));
            }
        }
        boolean hadBoth = had && both > 0;
        List<Map<String, Object>> books = new ArrayList<>();
        byBook.values().stream().filter(b -> !str(b.get("book")).isEmpty()).sorted(Comparator.comparing(b -> str(b.get("book")))).forEach(b -> {
            Map<String, Object> x = new LinkedHashMap<>(b);
            x.put("pnl", had ? shown.apply(totals == null ? null : totals.get("pnl", b.get("book"), null), 0L) : null);
            x.put("mtm", shown.apply(totals == null ? null : totals.get("mtm", b.get("book"), null), 0L));
            books.add(x);
        });
        List<Map<String, Object>> traders = new ArrayList<>();
        byTrader.values().stream().sorted(Comparator.comparing(t -> str(t.get("trader")))).forEach(t -> {
            Map<String, Object> x = new LinkedHashMap<>(t);
            x.put("pnl", had ? shown.apply(totals == null ? null : totals.get("pnl", null, t.get("trader")), 0L) : null);
            traders.add(x);
        });
        Double dv01Trades = totals == null ? null : totals.get("dv01Trades");
        return m("available", true, "businessDate", business.toString(), "previousDate", before.toString(), "hasPrevious", had,
                "previousProblem", wasProblem, "trades", rows.size(), "liveTrades", liveRows.size(), "valuedBoth", both,
                "matched", now.matched(), "partial", now.partial() || (was != null && was.partial()),
                "pnl", hadBoth && !mtmMasked ? shown.apply(totals == null ? null : totals.get("pnl"), null) : null,
                "mtm", !mtmMasked ? shown.apply(totals == null ? null : totals.get("mtm"), 0L) : null,
                "mtmMasked", mtmMasked, "traderMasked", traderMasked,
                "dv01", shown.apply(totals == null ? null : totals.get("dv01"), null), "dv01Trades", dv01Trades == null ? 0 : dv01Trades.intValue(),
                "figuresProblem", rows.isEmpty() ? null : problem,
                "byBook", books, "byTrader", traders,
                "movers", had ? movers.subList(0, Math.min(10, movers.size())) : List.of(),
                "products", ranked(products, 40), "counterparties", ranked(seen, 60),
                "largestCounterparty", largest);
    }

    /** The keys by their count, most first, then by name; the first {@code n}. */
    static List<String> ranked(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream().sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue()).thenComparing(Map.Entry::getKey))
                .limit(n).map(Map.Entry::getKey).toList();
    }

    // -- today's trades ---------------------------------------------------------------------------------------------------------------
    /**
     * The scope's trades touched on the day its valuations are of ({@code SOURCE_UPDATE_TIME}; the business date unless the lake's latest
     * day is earlier), newest first, each new, amended or cancelled; with the live result set's page ({@code page}), which the console
     * draws its grid from, and each trade's notional from the valuations.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> blotter(TradingAsker c, Scope scope, Map<String, Object> cfg) {
        Dates d = dates(c);
        List<String> books = scope.books;
        if (books.isEmpty() || d.business() == null) {
            return m("available", false, "reason", books.isEmpty() ? "no books in your scope" : "the server gave no business date");
        }
        Valuations tv = valuations(c, scope, cfg);              // the same read as the tiles (one at a time): the day, each notional
        LocalDate dataDay = day(tv.answer().get("dataDate"));
        LocalDate business = dataDay != null ? dataDay : d.business();
        String cond = "(" + String.join(" or ", books.stream().map(b -> "PORTFOLIO = " + q(b)).toList()) + ")";
        String query = "TRD where " + cond + " and SOURCE_UPDATE_TIME startswith " + q(business.toString())
                + " and (TRADE_ACTION != null or TRADE_VERSION != null or COUNTERPARTY != null or PRODUCT_TYPE != null or TRADE_STATUS != null"
                + " or TRADE_DATE != null) order by SOURCE_UPDATE_TIME desc limit " + PAGE;
        String key = "blotter\u0001" + c.key() + "\u0001" + scope.kind + "\u0001" + scope.id + "\u0001" + scope.booksKey() + "\u0001" + business;
        Map<String, Object> made = (Map<String, Object>) cache.once(key, () -> {
            try {
                Map<String, Object> page = c.live(query, PAGE);
                Map<String, Object> out = events(page, business);
                out.put("how", "trades");
                Map<String, Object> set = map(page, "set");
                out.put("live", m("set", set.get("id"), "live", truthy(page.get("live")), "total", page.get("total"), "seq", page.get("seq")));
                out.put("page", page);                           // the live result set the console's grid follows
                return out;
            } catch (Refused e) {                    // the trades' events read every trade document: too slow on a large lake (kept two minutes)
                if (tv.changes() == null) {
                    return m("available", false, "reason", e.say());
                }
                Map<String, Object> out = new LinkedHashMap<>(tv.changes());
                out.put("how", "valuations");
                out.put("note", "from the valuations' status against " + tv.answer().get("previousDate") + " (the trades answered " + e.say()
                        + "): no time of day");
                return out;
            }
        });
        if (!Boolean.TRUE.equals(made.get("available"))) {
            return made;
        }
        Map<String, Object> out = new LinkedHashMap<>(made);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object e : list(made, "rows")) {
            Map<String, Object> x = new LinkedHashMap<>(map(e));
            Map<String, Object> r = tv.rows().get(str(x.get("trade")));
            x.put("notional", r == null ? null : num(r.get("NOTIONAL")));
            rows.add(x);
        }
        out.put("rows", rows);
        return out;
    }

    /** The day's events from two days' valuations (pure), when the trades cannot say: new, cancelled, amended; no time of day. */
    static Map<String, Object> changes(Map<String, Map<String, Object>> rows, Map<String, Map<String, Object>> old, LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> counts = m("new", 0, "amended", 0, "cancelled", 0);
        rows.forEach((tid, r) -> {
            Map<String, Object> was = old.get(tid);
            String status = str(r.get("TRADE_STATUS")).toUpperCase(Locale.ROOT);
            String before = was == null ? "" : str(was.get("TRADE_STATUS")).toUpperCase(Locale.ROOT);
            String what = was == null ? "new" : ("CANCELLED".equals(status) && !"CANCELLED".equals(before)) ? "cancelled"
                    : ("AMENDED".equals(status) && !"AMENDED".equals(before)) ? "amended" : null;
            if (what != null) {
                counts.put(what, (Integer) counts.get(what) + 1);
                out.add(m("trade", tid, "event", what, "at", null, "time", "", "book", r.get("PORTFOLIO"), "counterparty", r.get("COUNTERPARTY"),
                        "product", r.get("PRODUCT_TYPE"), "version", null, "status", r.get("TRADE_STATUS")));
            }
        });
        out.sort(Comparator.comparingDouble(e -> {
            Double n = num(rows.get(str(e.get("trade"))).get("NOTIONAL"));
            return -Math.abs(n == null ? 0 : n);
        }));
        return m("available", true, "businessDate", day.toString(), "counts", counts, "total", out.size(), "matched", out.size(), "partial", false,
                "rows", new ArrayList<>(out.subList(0, Math.min(50, out.size()))));
    }

    /**
     * The day's trade events from a live result set's page (pure): cancelled (status CANCELLED), new (TRADE_ACTION NEW, or booked on the
     * day), else amended; with counts, and {@code partial} when the server matched more than it returned.
     */
    static Map<String, Object> events(Map<String, Object> page, LocalDate business) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> counts = m("new", 0, "amended", 0, "cancelled", 0);
        for (Object r : list(page, "rows")) {
            Map<String, Object> v = map(r, "doc");
            String status = str(v.get("TRADE_STATUS")).toUpperCase(Locale.ROOT);
            String action = str(v.get("TRADE_ACTION")).toUpperCase(Locale.ROOT);
            String tradeDate = str(v.get("TRADE_DATE"));
            String what = "CANCELLED".equals(status) ? "cancelled"
                    : "NEW".equals(action) || (tradeDate.length() >= 10 && tradeDate.substring(0, 10).equals(business.toString())) ? "new" : "amended";
            counts.put(what, (Integer) counts.get(what) + 1);
            String when = str(v.get("SOURCE_UPDATE_TIME"));
            rows.add(m("trade", str(get(r, "id")), "event", what, "at", when, "time", when.length() >= 16 ? when.substring(11, 16) : when.length() > 11 ? when.substring(11) : "",
                    "book", v.get("PORTFOLIO"), "counterparty", v.get("COUNTERPARTY"), "product", v.get("PRODUCT_TYPE"),
                    "version", v.get("TRADE_VERSION"), "status", v.get("TRADE_STATUS")));
        }
        Map<String, Object> search = map(page, "search");
        Double mm = num(search.get("matched"));
        int matched = mm == null || mm == 0 ? rows.size() : mm.intValue();
        return m("available", true, "businessDate", business.toString(), "counts", counts, "total", rows.size(), "matched", matched,
                "partial", matched > rows.size() || truthy(search.get("partial")), "rows", new ArrayList<>(rows.subList(0, Math.min(50, rows.size()))));
    }

    // -- VaR and limits -----------------------------------------------------------------------------------------------------------------
    /** The VaR engine's scope of "mine": the desk's node, or the union of the trader's books; null without books. */
    static String varScope(Scope scope) {
        if ("desk".equals(scope.kind)) {
            return "DESK:" + scope.id;
        }
        return scope.books.isEmpty() ? null : String.join(" + ", scope.books.stream().map(b -> "BOOK:" + b).toList());
    }

    /**
     * The scope's VaR (intraday when the engine applied updates), each book's VaR against its limit, the worst book, breaches and books
     * near their limit, and the largest positions by VaR contribution: the VaR engine's own answers.
     */
    public Map<String, Object> risk(TradingAsker c, Scope scope) {
        String text = varScope(scope);
        if (text == null) {
            return m("available", false, "reason", "no books in your scope");
        }
        CompletableFuture<Asked> headT = async(() -> Asked.of(() -> c.varScope(text)));
        List<CompletableFuture<Asked>> bookTs = scope.books.stream()
                .map(b -> async(() -> Asked.of(() -> c.varScope("BOOK:" + b)))).toList();
        CompletableFuture<Asked> partsT = async(() -> Asked.of(() -> c.varContributions(text, "position")));
        Asked head = join(headT);
        List<Map<String, Object>> books = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < scope.books.size(); i++) {
            String b = scope.books.get(i);
            Asked a = join(bookTs.get(i));
            if (a.answer() == null) {
                problems.add("BOOK:" + b + ": " + a.why());
                books.add(m("book", b, "var", null, "limit", null, "limitUse", null, "state", "none"));
                continue;
            }
            Map<String, Object> x = a.answer();
            books.add(m("book", b, "var", num(x.get("var")), "limit", num(x.get("limit")), "limitUse", num(x.get("limitUse")),
                    "state", stateOf(x.get("limitStatus")), "trades", x.get("trades")));
        }
        Asked parts = join(partsT);
        if (head.answer() == null) {
            return m("available", false, "reason", head.why(), "books", books, "problems", problems);
        }
        Map<String, Object> worst = books.stream().filter(b -> b.get("limitUse") != null)
                .max(Comparator.comparingDouble(b -> (Double) b.get("limitUse"))).orElse(null);
        List<Map<String, Object>> positions = new ArrayList<>();
        List<Object> prow = parts.answer() == null ? List.of() : list(parts.answer(), "rows");
        for (Object r : prow.subList(0, Math.min(6, prow.size()))) {
            Object label = get(r, "label");
            positions.add(m("label", label != null && !"".equals(label) ? label : get(r, "key"), "scope", get(r, "scope"),
                    "componentVar", num(get(r, "componentVar")), "marketValue", num(get(r, "marketValue")), "pct", num(get(r, "pct")),
                    "book", get(r, "book"), "productType", get(r, "productType")));
        }
        Map<String, Object> h = head.answer();
        Object intraday = h.get("intraday") instanceof Map<?, ?> in ? m("trades", in.get("trades"), "lastAt", in.get("lastAt"), "updates", in.get("updates")) : null;
        Object currency = h.get("currency");
        return m("available", true, "scope", text, "businessDate", h.get("businessDate"), "var", num(h.get("var")), "es", num(h.get("es")),
                "confidence", h.get("confidence"), "currency", currency != null && !"".equals(currency) ? currency : "USD", "trades", h.get("trades"),
                "limit", num(h.get("limit")), "limitUse", num(h.get("limitUse")), "state", stateOf(h.get("limitStatus")), "near", num(h.get("limitNear")),
                "intraday", intraday, "books", books, "worst", worst,
                "breaches", books.stream().filter(b -> "breach".equals(b.get("state"))).toList(),
                "nearLimit", books.stream().filter(b -> "near".equals(b.get("state"))).toList(),
                "positions", positions, "positionsProblem", parts.why(), "problems", problems);
    }

    static Map<String, Object> node(Map<String, Object> nodes, String book) {
        for (String nid : List.of("MGMT~" + book, "MGMT~BOOK-" + book)) {
            if (nodes.get(nid) instanceof Map<?, ?>) {
                return map(nodes.get(nid));
            }
        }
        return null;
    }

    /** Each book's VaR ÷ limit over the last business dates, from each date's official VaR record (the numbers of record). */
    public Map<String, Object> limits(TradingAsker c, Scope scope, Map<String, Object> cfg) {
        Dates d = dates(c);
        if (d.business() == null || scope.books.isEmpty()) {
            return m("available", false, "reason", d.business() != null ? "no books in your scope" : "the server gave no business date");
        }
        List<LocalDate> days = businessDays(d.business(), count(cfg, "limit-days", 5, 1, 20), d.holidays());
        List<CompletableFuture<Asked>> ts = days.stream()
                .map(day -> async(() -> Asked.of(() -> c.onDate(day).varOfficial()))).toList();
        List<Asked> answers = ts.stream().map(TradingLanding::join).toList();
        if (answers.stream().allMatch(a -> a.answer() == null)) {
            String why = answers.get(answers.size() - 1).why();
            return m("available", false, "reason", why != null ? why : "no official record");
        }
        Double near = answers.stream().filter(a -> a.answer() != null).findFirst().map(a -> num(a.answer().get("limitNear"))).orElse(null);
        List<Map<String, Object>> grid = new ArrayList<>();
        for (String b : scope.books) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int i = 0; i < days.size(); i++) {
                Asked a = answers.get(i);
                Map<String, Object> n = a.answer() == null ? null : node(map(a.answer(), "nodes"), b);
                cells.add(m("date", days.get(i).toString(), "var", n == null ? null : num(n.get("var")), "limit", n == null ? null : num(n.get("limit")),
                        "limitUse", n == null ? null : num(n.get("limitUse")), "state", n == null ? "missing" : stateOf(n.get("limitStatus")),
                        "why", n != null ? null : a.answer() == null ? "no official record of this date" : "the book is not a node of the record"));
            }
            grid.add(m("book", b, "cells", cells));
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            if (answers.get(i).answer() == null) {
                missing.add(days.get(i).toString());
            }
        }
        return m("available", true, "dates", days.stream().map(LocalDate::toString).toList(), "near", near, "books", grid, "missingDates", missing);
    }

    /**
     * The desk's daily P&amp;L against its VaR, from its official history (each day's hypothetical P&amp;L against the VaR before it). A
     * replayed history is scenario P&amp;L, not a day's: then the card is left out and says why.
     */
    public Map<String, Object> pnlVsVar(TradingAsker c, Scope scope, Map<String, Object> cfg) {
        String text = "desk".equals(scope.kind) ? varScope(scope) : null;
        if (text == null) {
            return m("available", false, "reason", "a desk's history: pick a desk");
        }
        Asked h = Asked.of(() -> c.varHistory(text));
        if (h.answer() == null) {
            return m("available", false, "reason", h.why());
        }
        if (!"official".equals(str(h.answer().get("mode")))) {
            return m("available", false, "reason", "the desk has no official end-of-day history on more than one day: a replay's scenario P&L "
                    + "is not a day's P&L, so there is nothing honest to draw");
        }
        int n = count(cfg, "pnl-var-days", 60, 5, 250);
        List<Object> all = list(h.answer(), "rows");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object r : all.subList(Math.max(0, all.size() - n), all.size())) {
            Object date = get(r, "date");
            if (date == null || "".equals(date)) {
                continue;
            }
            Double v = num(get(r, "var"));
            Double p = num(get(r, "pnl"));
            if (v != null && p != null) {
                rows.add(m("date", date, "var", v, "pnl", p, "exception", truthy(get(r, "exception"))));
            }
        }
        if (rows.isEmpty()) {
            return m("available", false, "reason", "the official history holds no day with both a P&L and a VaR");
        }
        return m("available", true, "scope", text, "rows", rows, "days", rows.size(),
                "exceptions", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("exception"))).count(),
                "backtest", h.answer().get("backtest"), "note", h.answer().get("note"));
    }

    /**
     * The desk by trader: each trader's P&amp;L and live trades from the valuations' TRADER_ID, and their VaR as the server computes it
     * ({@code /scope/contributions?by=attribute:TRADER_ID}). A role that sees TRADER_ID masked gets the trader records' counts only.
     */
    public Map<String, Object> traders(TradingAsker c, Scope scope, Map<String, Object> cfg) {
        if (!"desk".equals(scope.kind)) {
            return m("available", false, "reason", "by trader is a desk's: pick a desk");
        }
        Asked listed = Asked.of(() -> search(c, "TRDR where DESK = " + q(scope.id) + " and (NAME != null or TRADE_COUNT != null) limit 200"));
        CompletableFuture<Valuations> tvT = async(() -> valuations(c, scope, cfg));
        CompletableFuture<Asked> groupsT = async(() -> Asked.of(() -> c.varContributions("DESK:" + scope.id, "attribute:TRADER_ID")));
        Map<String, Object> tv = join(tvT).answer();
        Asked groups = join(groupsT);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Object r : listed.answer() == null ? List.of() : list(listed.answer(), "rows")) {
            String tid = str(map(r, "ref"), "id");
            Map<String, Object> v = map(r, "values");
            if (!tid.isEmpty()) {
                Object name = v.get("$.NAME");
                Object title = get(r, "title");
                rows.put(tid, m("trader", tid, "name", name != null && !"".equals(name) ? str(name) : title != null && !"".equals(title) ? str(title) : tid,
                        "pnl", null, "var", null, "trades", null, "recordTrades", num(v.get("$.TRADE_COUNT"))));
            }
        }
        boolean maskedIds = Boolean.TRUE.equals(tv.get("available")) && Boolean.TRUE.equals(tv.get("traderMasked"));
        for (Object t : list(tv, "byTrader")) {
            String id = str(get(t, "trader"));
            Map<String, Object> row = rows.computeIfAbsent(id, k -> m("trader", k, "name", k, "var", null, "recordTrades", null));
            row.put("pnl", get(t, "pnl"));
            row.put("trades", get(t, "trades"));
        }
        String varNote = groups.why();
        Map<String, Object> g = groups.answer();
        if (g != null && truthy(g.get("masked"))) {
            maskedIds = true;
            varNote = null;                                          // the server said so; the note below says it once
        } else if (g != null && !maskedIds) {
            for (Object r : list(g, "rows")) {                            // the server's figures, shown as they come
                String t = str(get(r, "key"));
                if (!t.isEmpty()) {
                    rows.computeIfAbsent(t, k -> m("trader", k, "name", k, "pnl", null, "trades", null, "recordTrades", null))
                            .put("var", num(get(r, "componentVar")));
                } else if (num(get(r, "componentVar")) != null && num(get(r, "componentVar")) != 0) {
                    varNote = str(get(r, "label")) + ": " + str(get(r, "trades")) + " trades of the desk name no trader (component VaR "
                            + str(get(r, "componentVar")) + ")";
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        out.sort(Comparator.<Map<String, Object>>comparingDouble(r -> {
            Double p = num(r.get("pnl"));
            return -(p != null ? Math.abs(p) : -1);
        }).thenComparing(r -> str(r.get("trader"))));
        return m("available", !out.isEmpty(), "reason", out.isEmpty() ? listed.why() : null, "rows", out, "traderMasked", maskedIds,
                "hasPrevious", tv.get("hasPrevious"), "varNote", varNote,
                "note", maskedIds ? "trader ids are masked for your role: P&L, VaR and trades by trader need a role that sees TRADER_ID" : null);
    }

    // -- the new-trade check -----------------------------------------------------------------------------------------------------------
    private static final Pattern AMOUNT = Pattern.compile("\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|m|mm|bn|b)?\\s*");

    /** {@code 250m}, {@code 1.5bn}, {@code 400k}, {@code 250,000,000} or a number; null otherwise. */
    static Double parseAmount(Object v) {
        if (v instanceof Number n && !(v instanceof Boolean)) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        Matcher mt = AMOUNT.matcher(str(v).toLowerCase(Locale.ROOT));
        if (!mt.matches()) {
            return null;
        }
        double n = Double.parseDouble(mt.group(1).replace(",", ""));
        String unit = mt.group(2) == null ? "" : mt.group(2);
        return n * switch (unit) {
            case "k" -> 1e3;
            case "m", "mm" -> 1e6;
            case "bn", "b" -> 1e9;
            default -> 1;
        };
    }

    /**
     * The booked trade the check copies (pure): a live trade of the product with a notional, the counterparty's first, then the book's,
     * then the largest notional.
     */
    static Map.Entry<String, Map<String, Object>> template(Map<String, Map<String, Object>> rows, String product, String counterparty, String book,
            List<String> live) {
        List<Map.Entry<String, Map<String, Object>>> cands = new ArrayList<>();
        rows.forEach((tid, r) -> {
            Double n = num(r.get("NOTIONAL"));
            if (str(r.get("PRODUCT_TYPE")).equals(product) && n != null && n != 0 && live.contains(str(r.get("TRADE_STATUS")).toUpperCase(Locale.ROOT))) {
                cands.add(Map.entry(tid, r));
            }
        });
        if (cands.isEmpty()) {
            return null;
        }
        cands.sort(Comparator.<Map.Entry<String, Map<String, Object>>, Boolean>comparing(x -> !str(x.getValue().get("COUNTERPARTY")).toUpperCase(Locale.ROOT).equals(counterparty))
                .thenComparing(x -> book != null && !book.isEmpty() && !str(x.getValue().get("PORTFOLIO")).equals(book))
                .thenComparingDouble(x -> -Math.abs(num(x.getValue().get("NOTIONAL"))))
                .thenComparing(Map.Entry::getKey));
        return cands.get(0);
    }

    /**
     * Does a new trade fit? One VaR what-if on the book and one pre-deal check on the counterparty, each the engine's own, on a copy of a
     * booked trade of the product scaled to the notional (factor = notional ÷ its notional, negative for the other side).
     */
    public Map<String, Object> check(TradingAsker c, Scope scope, Map<String, Object> cfg, Map<String, Object> body) {
        String product = str(body.get("product")).strip();
        String cp = str(body.get("counterparty")).strip().toUpperCase(Locale.ROOT);
        Double notional = parseAmount(body.get("notional"));
        String sideText = str(body.get("side")).strip().toUpperCase(Locale.ROOT);
        String side = sideText.isEmpty() ? "" : sideText.substring(0, 1);
        if (product.isEmpty() || notional == null || notional <= 0 || !SAFE_ID.matcher(cp.isEmpty() ? "-" : cp).matches()) {
            return m("ok", false, "reason", "name the product, a notional (250m, 1.5bn) and the counterparty (CP-…)");
        }
        Valuations tv = valuations(c, scope, cfg);
        if (!tv.available()) {
            Object why = tv.answer().get("reason");
            return m("ok", false, "reason", why != null && !"".equals(why) ? why : "no valuations in your scope");
        }
        String book = str(body.get("book")).strip();
        if (!book.isEmpty() && !scope.books.contains(book)) {
            book = "";
        }
        Map.Entry<String, Map<String, Object>> pick = template(tv.rows(), product, cp, book, live(cfg));
        if (pick == null) {
            return m("ok", false, "reason", "no live trade of " + product + " with a notional in your books to price from: the check copies a booked trade");
        }
        String tid = pick.getKey();
        Map<String, Object> t = pick.getValue();
        double baseNotional = Math.abs(num(t.get("NOTIONAL")));
        String ownText = str(t.get("BUY_SELL_CODE")).toUpperCase(Locale.ROOT);
        String own = ownText.isEmpty() ? "" : ownText.substring(0, 1);
        double sign = ("B".equals(side) || "S".equals(side)) && ("B".equals(own) || "S".equals(own)) && !side.equals(own) ? -1.0 : 1.0;
        double factor = sign * notional / baseNotional;
        if (book.isEmpty()) {
            String its = str(t.get("PORTFOLIO"));
            book = scope.books.contains(its) ? its : scope.books.isEmpty() ? null : scope.books.get(0);
        }
        String label = product + " " + String.format(Locale.ROOT, "%,.0f", notional) + " with " + cp;
        String onBook = book;
        CompletableFuture<Map<String, Object>> varT = async(() -> varCheck(c, onBook, tid, factor, label));
        CompletableFuture<Map<String, Object>> expT = async(() -> exposureCheck(c, cp, tid, t, factor, notional, sign));
        return m("ok", true, "template", m("trade", tid, "product", product, "notional", baseNotional, "book", t.get("PORTFOLIO"),
                        "counterparty", t.get("COUNTERPARTY"), "side", t.get("BUY_SELL_CODE")),
                "factor", factor, "notional", notional, "counterparty", cp, "book", book, "var", join(varT), "exposure", join(expT));
    }

    /** The VaR what-if on the book: VaR before and after, and the book's limit, its use before and after and the status after. */
    static Map<String, Object> varCheck(TradingAsker c, String book, String tid, double factor, String label) {
        if (book == null || book.isEmpty()) {
            return m("state", "unavailable", "reason", "no book in your scope");
        }
        String scope = "BOOK:" + book;
        Asked w = Asked.of(() -> c.varWhatIf(m("scope", scope, "items", List.of(m("copyOf", tid, "factor", factor, "label", label)))));
        if (w.answer() == null) {
            return m("state", "unavailable", "scope", scope, "reason", w.why(), "code", w.code());
        }
        Map<String, Object> a = w.answer();
        Map<String, Object> lim = map(a, "limit");
        return m("state", stateOf(lim.get("status")), "scope", scope, "before", num(map(a, "before").get("var")), "after", num(map(a, "after").get("var")),
                "change", num(map(a, "change").get("var")), "limit", num(lim.get("limit")), "useBefore", num(lim.get("useBefore")),
                "useAfter", num(lim.get("useAfter")), "confidence", a.get("confidence"), "currency", a.get("currency"),
                "engine", a.get("engine"), "note", a.get("note"));
    }

    /**
     * The exposure engine's pre-deal check on the counterparty (EXPOSURE_GUIDE.md section 18.9): the copy's paths on the date's run, exact;
     * when the copy has no paths on the run, the same check described (asset class, notional, maturity), the engine's conservative proxy,
     * which only refers. The decision, the limit use and every figure are the server's, the state too.
     */
    static Map<String, Object> exposureCheck(TradingAsker c, String cp, String tid, Map<String, Object> t, double factor, double notional, double sign) {
        String side = sign < 0 ? "reverse" : "same";
        Asked a = Asked.of(() -> c.exposurePreDeal(m("counterparty", cp, "like", tid, "notional", notional, "side", side)));
        String fellBack = null;
        if (a.answer() == null && "DRS-1001".equals(a.code())) {        // the copy has no paths on the run: the check described, the proxy
            fellBack = a.why();
            Object maturity = null;
            try {
                String vid = str(t.get("VALUATION_ID"));
                maturity = map(raw(c, "trade-valuation", vid.isEmpty() ? "TVAL-" + tid : vid), "data").get("MATURITY_DATE");
            } catch (Refused e) {
                // no maturity: the proxy's one year
            }
            Object group = t.get("PRODUCT_GROUP");
            Double mtm = num(t.get("MTM"));
            Map<String, Object> described = m("counterparty", cp, "assetClass", group != null && !"".equals(group) ? group : "rates", "notional", notional,
                    "maturity", maturity != null && !"".equals(maturity) ? maturity : "1Y", "mtm", (mtm == null ? 0 : mtm) * factor, "side", side);
            a = Asked.of(() -> c.exposurePreDeal(described));
        }
        if (a.answer() == null) {
            return m("state", "unavailable", "scope", "CPTY:" + cp, "reason", a.why(), "code", a.code());
        }
        Map<String, Object> x = a.answer();
        Map<String, Object> lim = map(x, "limit");
        Object decision = x.get("decision");
        String state = "decline".equals(decision) ? "breach" : "refer".equals(decision) ? "refer" : stateOf(lim.get("status"));
        Map<String, Object> method = map(x, "method");
        Map<String, Object> base = map(map(x, "exposure"), "base");
        Map<String, Object> with = map(map(x, "exposure"), "with");
        Object scope = x.get("scope");
        Object conf = lim.get("confidence");
        return m("state", state, "decision", decision, "reason", x.get("reason"), "reasons", list(x, "reasons"), "checkId", x.get("checkId"),
                "scope", scope != null && !"".equals(scope) ? scope : "CPTY:" + cp, "method", method.get("id"), "proxy", !truthy(method.get("exact")),
                "fallback", fellBack, "nettingSet", map(x, "nettingSet").get("id"),
                "before", num(lim.containsKey("before") ? lim.get("before") : base.get("peakPfe")),
                "after", num(lim.containsKey("after") ? lim.get("after") : with.get("peakPfe")), "limit", num(lim.get("limit")),
                "useBefore", num(lim.get("useBefore")), "useAfter", num(lim.get("useAfter")),
                "confidence", conf != null && !"".equals(conf) ? conf : x.get("confidence"), "flags", list(x, "flags"),
                "note", method.get("text"), "command", x.get("command"));
    }

    /** Drops what was kept (tests). */
    public void forget() {
        cache.clear();
    }
}
