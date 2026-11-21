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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.testroots.RecordedAnswers;
import com.ash.lake.tck.TestRoots;
import com.ash.drishti.trading.TradingStandIn.Person;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Drishti Trading's landings worked out by the trading module (THIN_CONSOLE_DESIGN.md step S8a) over the stand-in server of
 * {@link TradingStandIn}: the figures the console's Python worked out before S8a ("my" scope from the server's mapping, the lens's, a trader
 * record or the person's choice; P&amp;L today only from valuations on both dates; today's events; VaR and limits as the engines say;
 * the official limit use and P&amp;L against VaR; by trader, masked for a role without raw; the new-trade check from one VaR what-if and one
 * pre-deal check), two people at once, and the recorded answers the console's stand-in replays
 * ({@code drishti-console/tests/answers/landings/trading.json}, kept by {@link RecordedAnswers}: {@code -Ddrishti.record=true} writes it
 * again, otherwise a recording that differs from what this code answers fails here).
 */
class TradingLandingRecordTest {

    static final Path REPO = Path.of(TestRoots.repo());
    static final List<String> PARTS = List.of("me", "valuations", "blotter", "risk", "limits", "pnl-var", "traders");
    /** The browser's new-trade check (drishti_trading-landing.js: the book is the Select's first, the person's first book). */
    static final Map<String, Object> BROWSER_CHECK = TradingStandIn.m("product", "IR_SWAP", "side", "S", "notional", "250m", "counterparty", "CP-ALDERSHOT",
            "book", "BOOK-RATES-1");
    static final Map<String, Object> CHECK = TradingStandIn.m("product", "IR_SWAP", "notional", "250m", "counterparty", "CP-ALDERSHOT", "side", "S");

    static Person who(String user, String display, String role, String lens, String view) {
        return new Person(user, display, List.of(role), lens, view == null ? Map.of() : Map.of("tradingView", view));
    }

    /**
     * The people the console's tests sign in as, each with the checks they post: test_edition_trading_browser.py (ash, mapped by the server
     * to A. Shah; zed, mapped to nobody), test_edition.py (alice; t.brennan, the desk head; drishti-dev-admin, mapped by the trading lens;
     * kim, found by her trader record's name; zed with and without a View as choice; root, an administrator whose choice wins). A person whose
     * landings the tests draw has every part recorded (and the Desk head's, {@code ?for=desk}); the others, who "my" is and their role.
     */
    static final List<Object[]> RECORDED = List.of(
            new Object[] {who("ash", "Ash", "*", "trading", null), true, List.of(BROWSER_CHECK)},
            new Object[] {who("zed", "Zed", "viewer", "trading", null), true, List.of()},
            new Object[] {who("zed", "Zed", "viewer", "trading", "desk:DESK-RATES"), true, List.of()},
            new Object[] {who("alice", "Alice", "*", "trading", null), false, List.of()},
            new Object[] {who("t.brennan", "T.Brennan", "viewer", "trading", null), false, List.of()},
            new Object[] {who("drishti-dev-admin", "Drishti-Dev-Admin", "viewer", "trading", null), false, List.of()},
            new Object[] {who("drishti-dev-admin", "Drishti-Dev-Admin", "*", "trading", null), false, List.of(CHECK, TradingStandIn.m("product", "IR_SWAP"),
                    TradingStandIn.m("product", "IR_SWAP", "notional", "1m", "counterparty", "CP-ALDERSHOT"))},
            new Object[] {who("drishti-dev-admin", "Drishti-Dev-Admin", "*", "world", null), false, List.of()},
            new Object[] {who("kim", "K. Moreau", "viewer", "trading", null), false, List.of()},
            new Object[] {who("root", "Root", "admin", "trading", null), false, List.of()},
            new Object[] {who("root", "Root", "admin", "trading", "trader:TRDR-KMOREAU"), false, List.of()});

    /** The server's drishti.trading.people of this stand-in (the lens file adds drishti-dev-admin). */
    static MockEnvironment env() {
        return new MockEnvironment().withProperty("drishti.trading.people[ash].trader", "TRDR-ASHAH")
                .withProperty("drishti.trading.people[t.brennan].desk", "DESK-RATES").withProperty("drishti.trading.people[t.brennan].role", "desk-head")
                .withProperty("drishti.trading.people[root].trader", "TRDR-ASHAH");
    }

    record Server(TradingStandIn kernel, TradingLandingController api) {
        static Server fresh() {
            TradingStandIn k = new TradingStandIn(REPO);
            return new Server(k, new TradingLandingController(new TradingLanding(k, env())));
        }

        Map<String, Object> get(Person p, String part, String forWhom) {
            return TradingStandIn.wire(kernel.as(p, () -> "role".equals(part) ? api.role().getBody() : api.part(part, forWhom).getBody()));
        }

        Map<String, Object> check(Person p, Map<String, Object> body) {
            return TradingStandIn.wire(kernel.as(p, () -> api.check(new LinkedHashMap<>(body)).getBody()));
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T at(Object node, String... path) {
        Object x = node;
        for (String p : path) {
            x = x instanceof List<?> l ? l.get(Integer.parseInt(p)) : ((Map<String, Object>) x).get(p);
        }
        return (T) x;
    }

    static double num(Object node, String... path) {
        return ((Number) at(node, path)).doubleValue();
    }

    @Test
    void myScopeFromTheServersMappingTheLensARecordOrAChoice() {
        Server s = Server.fresh();
        Map<String, Object> dev = s.get(who("drishti-dev-admin", "Drishti-Dev-Admin", "viewer", "trading", null), "me", null);
        assertThat((Map<String, Object>) at(dev, "scope")).containsEntry("id", "TRDR-ASHAH").containsEntry("source", "config")
                .containsEntry("books", List.of("BOOK-RATES-1", "BOOK-RATES-2")).containsEntry("name", "A. Shah").containsEntry("canPick", false);
        assertThat(dev).doesNotContainKey("options");
        Map<String, Object> head = s.get(who("t.brennan", "T.Brennan", "viewer", "trading", "trader:TRDR-KMOREAU"), "me", null);
        assertThat((Map<String, Object>) at(head, "scope")).as("the server's mapping: a choice cannot override it").containsEntry("kind", "desk")
                .containsEntry("id", "DESK-RATES").containsEntry("role", "desk-head").containsEntry("head", "T. Brennan");
        assertThat((String) at(s.get(who("kmoreau", "kmoreau", "viewer", "trading", null), "me", null), "scope", "source")).isEqualTo("record");
        assertThat((String) at(s.get(who("kim", "K. Moreau", "viewer", "trading", null), "me", null), "scope", "id")).isEqualTo("TRDR-KMOREAU");
        Map<String, Object> zed = s.get(who("zed", "Zed", "viewer", "trading", null), "me", null);
        assertThat((String) at(zed, "scope", "kind")).isEmpty();
        assertThat((Boolean) at(zed, "scope", "canPick")).isTrue();
        assertThat(((List<Map<String, Object>>) at(zed, "options", "desks")).stream().map(o -> o.get("id"))).containsExactly("DESK-RATES", "DESK-COMM");
        assertThat(s.get(who("zed", "Zed", "viewer", "trading", null), "valuations", null)).containsEntry("available", false);
        Map<String, Object> chose = s.get(who("zed", "Zed", "viewer", "trading", "desk:DESK-RATES"), "me", null);
        assertThat((Map<String, Object>) at(chose, "scope")).containsEntry("kind", "desk").containsEntry("source", "choice").containsEntry("role", "desk-head");
        Map<String, Object> admin = s.get(who("root", "Root", "admin", "trading", "trader:TRDR-KMOREAU"), "me", null);
        assertThat((Map<String, Object>) at(admin, "scope")).as("an administrator's choice wins").containsEntry("id", "TRDR-KMOREAU")
                .containsEntry("source", "choice").containsEntry("canPick", true);
        assertThat(s.get(who("t.brennan", "T.Brennan", "viewer", "trading", null), "role", null)).containsEntry("role", "desk-head");
        assertThat(s.get(who("drishti-dev-admin", "Drishti-Dev-Admin", "viewer", "trading", null), "role", null)).containsEntry("role", "trader");
        assertThat(s.get(who("zed", "Zed", "viewer", "trading", null), "role", null)).containsEntry("role", "");
    }

    @Test
    void pnlTodayIsTheMtmChangeOfTradesValuedOnBothDatesAndEveryTotalIsTheEngines() {
        Server s = Server.fresh();
        Map<String, Object> v = s.get(who("drishti-dev-admin", "Drishti-Dev-Admin", "viewer", "trading", null), "valuations", null);
        assertThat(v).containsEntry("businessDate", "2026-09-30").containsEntry("previousDate", "2026-09-29").containsEntry("hasPrevious", true)
                .containsEntry("valuedBoth", 8).containsEntry("partial", false).containsEntry("liveTrades", 8).containsEntry("traderMasked", true)
                .containsEntry("byTrader", List.of()).containsEntry("products", List.of("IR_SWAP", "SWAPTION")).doesNotContainKey("__rows");
        assertThat(num(v, "pnl")).isEqualTo(480_000);
        assertThat(num(v, "mtm")).isZero();
        assertThat(num(v, "dv01")).isEqualTo(-48_000);
        assertThat(num(v, "dv01Trades")).isEqualTo(8);
        Map<Object, Object> byBook = ((List<Map<String, Object>>) at(v, "byBook")).stream()
                .collect(Collectors.toMap(b -> b.get("book"), b -> ((Number) b.get("pnl")).longValue()));
        assertThat(byBook).containsExactlyInAnyOrderEntriesOf(Map.of("BOOK-RATES-1", 220_000L, "BOOK-RATES-2", 260_000L));
        assertThat((String) at(v, "movers", "0", "trade")).isEqualTo("MX-20000011");
        assertThat(num(v, "movers", "0", "change")).isEqualTo(110_000);
        assertThat((String) at(v, "largestCounterparty", "counterparty")).isEqualTo("CP-NORTHPLAINS");
        assertThat(num(v, "largestCounterparty", "share")).isCloseTo(16.0 / 24, within(1e-9));
        assertThat(s.kernel().asked).as("the day before read on its own date box, the request's untouched")
                .anyMatch(a -> a.contains("/search 2026-09-29")).anyMatch(a -> a.contains("/search 2026-09-30"));
    }

    @Test
    void todaysEventsVarLimitsAndPnlAgainstVarAreTheEnginesOwn() {
        Server s = Server.fresh();
        Person dev = who("drishti-dev-admin", "Drishti-Dev-Admin", "viewer", "trading", null);
        Map<String, Object> b = s.get(dev, "blotter", null);
        assertThat((Map<String, Object>) at(b, "counts")).containsExactlyInAnyOrderEntriesOf(Map.of("new", 1, "amended", 1, "cancelled", 1));
        assertThat(((List<Map<String, Object>>) at(b, "rows")).stream().map(r -> r.get("trade"))).containsExactly("MX-20000001", "MX-20000002", "MX-20000004");
        assertThat((String) at(b, "rows", "0", "time")).isEqualTo("13:52");
        assertThat(num(b, "rows", "0", "notional")).isEqualTo(50_000_000);
        Map<String, Object> r = s.get(dev, "risk", null);
        assertThat(r).containsEntry("scope", "BOOK:BOOK-RATES-1 + BOOK:BOOK-RATES-2").containsEntry("breaches", List.of());
        assertThat(num(r, "var")).isEqualTo(8_000_000);
        assertThat((Map<String, Object>) at(r, "worst")).containsEntry("book", "BOOK-RATES-1").containsEntry("state", "near");
        assertThat((String) at(r, "positions", "0", "label")).isEqualTo("USD SOFR swaps 10Y");
        assertThat(s.get(dev, "pnl-var", null)).as("a trader: the desk's card").containsEntry("available", false);
        Person head = who("t.brennan", "T.Brennan", "viewer", "trading", null);
        Map<String, Object> d = s.get(head, "risk", null);
        assertThat(d).containsEntry("scope", "DESK:DESK-RATES").containsEntry("state", "ok");
        assertThat(((List<Map<String, Object>>) at(d, "breaches")).stream().map(x -> x.get("book"))).containsExactly("BOOK-RATES-3");
        assertThat(((List<Map<String, Object>>) at(d, "nearLimit")).stream().map(x -> x.get("book"))).containsExactly("BOOK-RATES-1");
        Map<String, Object> h = s.get(head, "limits", null);
        assertThat(h).containsEntry("dates", List.of("2026-09-24", "2026-09-25", "2026-09-28", "2026-09-29", "2026-09-30"))
                .containsEntry("missingDates", List.of("2026-09-25"));
        assertThat((String) at(h, "books", "2", "book")).isEqualTo("BOOK-RATES-3");
        assertThat((String) at(h, "books", "2", "cells", "1", "state")).isEqualTo("missing");
        assertThat((String) at(h, "books", "2", "cells", "4", "state")).isEqualTo("breach");
        assertThat(num(h, "books", "2", "cells", "4", "limitUse")).isCloseTo(9_000_000 * 0.975 / 8_000_000, within(1e-9));
        Map<String, Object> p = s.get(head, "pnl-var", null);
        assertThat(p).containsEntry("available", true).containsEntry("days", 60).containsEntry("exceptions", 1);
    }

    @Test
    void byTraderNeedsARoleThatSeesTraderIds() {
        Server s = Server.fresh();
        Map<String, Object> t = s.get(who("t.brennan", "T.Brennan", "admin", "trading", null), "traders", null);
        Map<Object, Map<String, Object>> rows = ((List<Map<String, Object>>) at(t, "rows")).stream().collect(Collectors.toMap(r -> r.get("trader"), r -> r));
        assertThat(num(rows.get("TRDR-ASHAH"), "pnl")).isEqualTo(480_000);
        assertThat(num(rows.get("TRDR-ASHAH"), "trades")).isEqualTo(8);
        assertThat(num(rows.get("TRDR-KMOREAU"), "trades")).isEqualTo(3);
        assertThat(num(rows.get("TRDR-ASHAH"), "var")).isEqualTo(List.of(0, 1, 3, 4, 6, 7, 9, 10).stream().mapToDouble(i -> 1_000_000 + 100_000 * i).sum());
        assertThat(rows.get("TRDR-ASHAH")).containsEntry("name", "A. Shah");
        assertThat(t).containsEntry("traderMasked", false);
        Map<String, Object> viewer = s.get(who("t.brennan", "T.Brennan", "viewer", "trading", null), "traders", null);
        assertThat(viewer).containsEntry("traderMasked", true);
        assertThat((String) viewer.get("note")).contains("masked");
        assertThat((List<Map<String, Object>>) at(viewer, "rows")).allSatisfy(r -> assertThat(r.get("pnl")).isNull())
                .allSatisfy(r -> assertThat(r.get("var")).isNull());
    }

    @Test
    void theCheckIsOneVarWhatIfAndOnePreDealCheck() {
        Server s = Server.fresh();
        Map<String, Object> c = s.check(who("drishti-dev-admin", "Drishti-Dev-Admin", "viewer", "trading", null), CHECK);
        assertThat(c).containsEntry("ok", true).containsEntry("book", "BOOK-RATES-1");
        assertThat((String) at(c, "template", "trade")).isEqualTo("MX-20000010");
        assertThat(num(c, "factor")).isCloseTo(250e6 / 500e6, within(1e-12));
        assertThat((Map<String, Object>) at(c, "var")).containsEntry("scope", "BOOK:BOOK-RATES-1").containsEntry("state", "near");
        assertThat(num(c, "var", "before")).isEqualTo(6_120_000);
        assertThat(num(c, "var", "after")).isEqualTo(6_300_000);
        assertThat((Map<String, Object>) at(c, "exposure")).containsEntry("scope", "CPTY:CP-ALDERSHOT").containsEntry("method", "paths")
                .containsEntry("nettingSet", "NS-ALDERSHOT-NY").containsEntry("state", "near").containsEntry("decision", "approve");
        assertThat(num(c, "exposure", "after")).isEqualTo(409_200_000);
        assertThat(s.kernel().asked.stream().filter(a -> a.contains(" POST /risk/"))).hasSize(2);
        assertThat(s.check(who("drishti-dev-admin", "Drishti-Dev-Admin", "viewer", "trading", null), TradingStandIn.m("product", "IR_SWAP")))
                .containsEntry("ok", false);
    }

    @Test
    void twoPeopleAtOnceEachSeeTheirOwn() {
        Server s = Server.fresh();
        Person ash = who("ash", "Ash", "*", "trading", null);
        Person kim = who("kim", "K. Moreau", "viewer", "trading", null);
        CompletableFuture<Map<String, Object>> a = CompletableFuture.supplyAsync(() -> s.get(ash, "valuations", null));
        CompletableFuture<Map<String, Object>> k = CompletableFuture.supplyAsync(() -> s.get(kim, "valuations", null));
        CompletableFuture<Map<String, Object>> am = CompletableFuture.supplyAsync(() -> s.get(ash, "me", null));
        CompletableFuture<Map<String, Object>> km = CompletableFuture.supplyAsync(() -> s.get(kim, "me", null));
        assertThat((String) at(am.join(), "scope", "id")).isEqualTo("TRDR-ASHAH");
        assertThat((String) at(km.join(), "scope", "id")).isEqualTo("TRDR-KMOREAU");
        assertThat(num(a.join(), "pnl")).isEqualTo(480_000);
        assertThat(num(k.join(), "pnl")).as("K. Moreau's four trades of BOOK-RATES-3").isEqualTo(300_000);
        assertThat(a.join()).containsEntry("traderMasked", false);
        assertThat(k.join()).as("kim's own role: TRADER_ID masked").containsEntry("traderMasked", true);
        assertThat(s.kernel().asked).anyMatch(x -> x.startsWith("ash GET /search")).anyMatch(x -> x.startsWith("kim GET /search"));
    }

    @Test
    void theRecordedAnswersAreTheModulesOwn() throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Server s = Server.fresh();
        List<Map<String, Object>> people = new ArrayList<>();
        for (Object[] row : RECORDED) {
            Person p = (Person) row[0];
            Map<String, Object> answers = new LinkedHashMap<>();
            answers.put("GET /trading/landing/role", s.get(p, "role", null));
            for (String part : (Boolean) row[1] ? PARTS : List.of("me")) {
                answers.put("GET /trading/landing/" + part, s.get(p, part, null));
                if ((Boolean) row[1]) {
                    answers.put("GET /trading/landing/" + part + "?for=desk", s.get(p, part, "desk"));
                }
            }
            List<Map<String, Object>> checks = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> bodies = (List<Map<String, Object>>) row[2];
            for (Map<String, Object> body : bodies) {
                checks.add(TradingStandIn.m("body", body, "answer", s.check(p, body)));
            }
            people.add(TradingStandIn.m("user", p.user(), "roles", p.roles(), "lens", p.lens(), "settings", p.settings(), "answers", answers,
                    "checks", checks));
        }
        RecordedAnswers rec = RecordedAnswers.under(REPO, "landings");
        rec.keep("trading.json", json.writeValueAsString(TradingStandIn.m("schema", 1, "module", "trading",
                "about", "Drishti Trading's landings as the trading module answers them over TradingStandIn's data: written by "
                        + "TradingLandingRecordTest (-Ddrishti.record=true), replayed by the console's stand-in (tests/landing_answers.py)",
                "people", people)));
        rec.assertCurrent();
    }
}
