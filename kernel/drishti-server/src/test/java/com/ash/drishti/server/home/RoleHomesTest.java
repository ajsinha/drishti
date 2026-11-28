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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.MeasureSource;
import com.ash.drishti.kernel.api.home.HomeDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A Home test per role (docs/architecture/ROLE_HOME_SUTRAS.md 5): the real files of {@code config/homes} load without fault; each role is
 * offered its Home and none it should not have; a Home needs its modules and its mnemonics; the Sutra is built from library fragments only
 * and reads only tables the server serves; a locked Home is compiled with {@code user-can-arrange: false}; the tables answer under a fake
 * engine and give nothing, not an error, when an engine is off. No Spring context.
 */
class RoleHomesTest {

    static final Map<String, List<String>> ROLES = Map.ofEntries(Map.entry("risk-manager", List.of("risk-manager")), Map.entry("trader", List.of("trader")),
            Map.entry("risk-officer", List.of("risk-officer")), Map.entry("credit-officer", List.of("credit-officer")),
            Map.entry("operations", List.of("operations")), Map.entry("quant", List.of("quant")), Map.entry("economist", List.of("economist")), Map.entry("genomicist", List.of("genomicist")));

    @TempDir Path data;

    static Path homes() {
        return Path.of(System.getProperty("lake.repo.root")).resolve("config/homes");   // the repository's root, absolute (TestPathsGuardTest)
    }

    HomeCatalogue catalogue() {
        return new HomeCatalogue(new HomeProperties(true, homes().toString(), data.toString(), ROLES, "17:00", 30));
    }

    @Test
    void everyHomeFileLoads() {
        HomeCatalogue c = catalogue();
        assertEquals(Map.of(), c.faults());
        assertEquals(Set.of("risk-manager", "trader", "risk-officer", "credit-officer", "operations", "quant", "economist", "genomicist"),
                c.all().stream().map(HomeDefinition::name).collect(java.util.stream.Collectors.toSet()));
    }

    @ParameterizedTest
    @CsvSource({"risk-manager,risk-manager", "trader,trader", "risk-officer,risk-officer", "credit-officer,credit-officer", "operations,operations",
            "quant,quant", "economist,economist", "genomicist,genomicist"})
    void aRoleOpensItsHomeAndOnlyItsHome(String role, String home) {
        HomeCatalogue c = catalogue();
        List<HomeDefinition> offered = c.offered(new Principal("u", List.of(role)), false, m -> true, m -> true);
        assertEquals(List.of(home), offered.stream().map(HomeDefinition::name).toList());
        assertEquals(home, c.start(offered, "").orElseThrow().name());
        assertEquals(home, c.start(offered, "no-such-home").orElseThrow().name(), "a choice the person is not offered falls back to the role's Home");
    }

    @Test
    void anAdministratorOpensEveryHomeAndAnUnknownRoleNone() {
        HomeCatalogue c = catalogue();
        assertEquals(8, c.offered(new Principal("a", List.of("admin")), true, m -> true, m -> true).size());
        assertTrue(c.offered(new Principal("x", List.of("viewer")), false, m -> true, m -> true).isEmpty());
    }

    @Test
    void aHomeNeedsItsModules() {
        HomeCatalogue c = catalogue();
        assertTrue(c.offered(new Principal("r", List.of("risk-manager")), false, m -> !m.equals("banking"), m -> true).isEmpty());
        assertEquals(1, c.offered(new Principal("e", List.of("economist")), false, m -> m.equals("economy"), m -> true).size());
    }

    @ParameterizedTest
    @CsvSource({"risk-manager", "trader", "risk-officer", "credit-officer", "operations", "quant", "economist", "genomicist"})
    void theSutraIsFragmentsOnlyAndReadsServedTables(String home) {
        HomeDefinition d = catalogue().find(home).orElseThrow();
        String text = d.sutraText();
        assertFalse(Pattern.compile("(?m)^\\s*-?\\s*kind:").matcher(text).find(), "no panel is written in a Home: " + home);
        Matcher use = Pattern.compile("use:\\s*([a-z-]+)/").matcher(text);
        int n = 0;
        while (use.find()) {
            n++;
            assertTrue(Set.of("core", "finance", "economy").contains(use.group(1)), "library " + use.group(1));
        }
        assertTrue(n >= 3, "a Home is built of fragments");
        Matcher table = Pattern.compile("table:\\s*(home_[a-z_]+)").matcher(text);
        while (table.find()) {
            assertTrue(HomeTables.BUILT_IN.contains(table.group(1)), home + " reads " + table.group(1) + ", which no server table serves");
        }
        assertTrue(text.contains("user-can-arrange: true"));
        assertEquals("home-" + home, d.sutraName());
    }

    @Test
    void aLockedHomeIsCompiledNotArrangeable() throws IOException {
        Path dir = Files.createDirectories(data.resolve("homes/x"));
        Files.writeString(dir.resolve("one.sutra.yaml"), "rachana: 1.2\nsutra: home-one\nlayout:\n  user-can-arrange: true\n");
        Files.writeString(dir.resolve("one.home.yaml"), "home: one\ntitle: One\nsutra: one.sutra.yaml\narrange: locked\n");
        Files.writeString(dir.resolve("bad.home.yaml"), "home: bad\ntitle: Bad\nsutra: gone.sutra.yaml\n");
        HomeCatalogue c = new HomeCatalogue(new HomeProperties(true, data.resolve("homes").toString(), data.toString(), Map.of("r", List.of("one")), null, null));
        HomeDefinition d = c.find("one").orElseThrow();
        assertFalse(c.arrangementApplies(d));
        assertTrue(c.sutraFor(d).contains("user-can-arrange: false"));
        assertEquals(1, c.faults().size(), "a Home with a fault is left out and said, never half-loaded");
    }

    @Test
    void relativeFoldersAreRefused() {
        try {
            new HomeProperties(true, "config/homes", null, null, null, null);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("not an absolute path"));
            return;
        }
        throw new AssertionError("a relative folder must be refused");
    }

    /** A fake engine: desk VaR with a limit, and a KPI. */
    static final class FakeVar implements MeasureSource {
        boolean on = true;

        public String engine() {
            return "var";
        }

        public List<LimitTable> limitTables() {
            return List.of(new LimitTable("home_desk_limits", "VAR", "desk", "DESK"));
        }

        public boolean on() {
            return on;
        }

        public OptionalDouble kpi(Principal p, String m, Map<String, Object> a, LocalDate d) {
            return m.equals("VAR") ? OptionalDouble.of(5_000_000) : OptionalDouble.empty();
        }

        public List<HomeData.MeasureValue> values(Principal p, String m, String kind, LocalDate d) {
            return m.equals("LIM_USE") ? List.of(new HomeData.MeasureValue("desk", "DESK-A", "LIM_USE", 0.97), new HomeData.MeasureValue("desk", "DESK-B", "LIM_USE", 0.83),
                    new HomeData.MeasureValue("desk", "DESK-C", "LIM_USE", 0.2)) : List.of();
        }

        public List<HomeData.LimitRow> limits(Principal p, String m, String kind, LocalDate d) {
            return List.of(new HomeData.LimitRow("DESK-A", 970, 1000.0), new HomeData.LimitRow("DESK-B", 100, null));
        }
    }

    HomeTables tables(FakeVar var, HomeStore store) {
        BusinessDates dates = new BusinessDates(new BusinessDateProperties(null, "UTC", null, "2026-11-20"), Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        return new HomeTables(new HomeProperties(true, null, data.toString(), null, null, null), store, new HomeClock(dates, Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC)),
                List.of(var), List.of(), ChangeEngineHost.NOT_HOSTED, List::of);
    }

    @Test
    void theRiskManagersTablesAnswerFromTheEngineAndStaySilentWhenItIsOff() {
        HomeStore store = new HomeStore(data, 30);
        FakeVar var = new FakeVar();
        HomeTables t = tables(var, store);
        HomeDefinition d = catalogue().find("risk-manager").orElseThrow();
        Principal p = new Principal("u", List.of("risk-manager"));

        List<Map<String, Object>> exc = t.rows("home_exceptions", p, d).orElseThrow();
        assertEquals(2, exc.size(), "95% is high, 80% medium, 20% nothing; one row a subject");
        assertEquals("high", exc.get(0).get("SEVERITY"));
        assertEquals("VAR DESK-A", exc.get(0).get("COMMAND"));
        assertTrue(String.valueOf(exc.get(0).get("MESSAGE")).contains("97%"));

        List<Map<String, Object>> kpis = t.rows("home_kpis", p, d).orElseThrow();
        assertEquals(1, kpis.size(), "only the figures the engine answers");
        assertEquals("Firm VaR", kpis.get(0).get("LABEL"));
        assertEquals("no earlier figure", kpis.get(0).get("CAPTION"));

        store.putKpis(LocalDate.parse("2026-11-19"), List.of(new HomeData.KpiPoint("Firm VaR", LocalDate.parse("2026-11-19"), 4_000_000)));
        assertEquals(1_000_000.0, (Double) t.rows("home_kpis", p, d).orElseThrow().get(0).get("DELTA"));

        assertEquals("DESK-A", t.rows("home_desk_limits", p, d).orElseThrow().get(0).get("DESK"), "the nearest to its limit first");
        assertTrue(t.rows("home_changes", p, d).orElseThrow().isEmpty(), "no changes engine is hosted yet: no rows, said by /status");
        assertEquals("yesterday 17:00", t.rows("home_meta", p, d).orElseThrow().get(0).get("SINCE"));
        assertTrue(t.rows("home_nonsense", p, d).isEmpty());

        var.on = false;
        assertTrue(t.rows("home_kpis", p, d).orElseThrow().isEmpty());
        assertTrue(t.rows("home_exceptions", p, d).orElseThrow().isEmpty());
        assertTrue(t.rows("home_desk_limits", p, d).orElseThrow().isEmpty());
    }

    @Test
    void theSnapshotIsTakenOnceAndTheEventsAndDriversAreKept() {
        HomeStore store = new HomeStore(data, 30);
        LocalDate day = LocalDate.parse("2026-11-19");
        store.putSnapshot(day, Instant.parse("2026-11-19T22:00:00Z"), List.of(new HomeData.MeasureValue("desk", "DESK-A", "VAR", 1.5)));
        assertTrue(store.hasSnapshot(day));
        assertEquals(1.5, store.snapshot(day).get(0).value());
        store.addEvent(day, new HomeData.Event("feed", "p/k", "load", Instant.parse("2026-11-19T23:00:00Z"), "loaded"));
        assertEquals(1, store.events(day, Instant.parse("2026-11-19T22:00:00Z")).size());
        assertEquals(0, store.events(day, Instant.parse("2026-11-20T00:00:00Z")).size());
        store.putDrivers(day, List.of(new HomeData.Driver("desk", "DESK-A", "VAR", "market-data", 0.5)));
        assertEquals("market-data", store.drivers(day).get(0).driver());
        store.putSnapshot(day.plusDays(40), Instant.parse("2026-12-29T22:00:00Z"), List.of());
        assertFalse(store.hasSnapshot(day), "days beyond keep-days are removed");
    }

    /** A fake engine whose desk VaR moved: DESK-A now 150 (was 100), DESK-B unchanged. */
    static final class MovingVar implements MeasureSource {
        public String engine() {
            return "var";
        }

        public boolean on() {
            return true;
        }

        public OptionalDouble kpi(Principal p, String m, Map<String, Object> a, LocalDate d) {
            return OptionalDouble.empty();
        }

        public List<HomeData.MeasureValue> values(Principal p, String m, String kind, LocalDate d) {
            return List.of(new HomeData.MeasureValue("desk", "DESK-A", "VAR", 150), new HomeData.MeasureValue("desk", "DESK-B", "VAR", 80));
        }

        public List<HomeData.LimitRow> limits(Principal p, String m, String kind, LocalDate d) {
            return List.of();
        }
    }

    /** A module's own table, served to its own Home only. */
    record OwnTable(String name, List<String> homes) implements com.ash.drishti.kernel.api.home.HomeTable {
        public List<Map<String, Object>> rows(Context c) {
            return List.of(Map.of("SINCE", String.valueOf(c.since())));
        }
    }

    @Test
    void theDigestReadsFromTheGivenTimeAndAModulesTableIsItsHomesOnly() throws IOException {
        Path dir = Files.createDirectories(data.resolve("homes/x"));
        Files.writeString(dir.resolve("one.sutra.yaml"), "rachana: 1.2\nsutra: home-one\n");
        Files.writeString(dir.resolve("one.home.yaml"), "home: one\ntitle: One\nsutra: one.sutra.yaml\nmeasures: one.measures.yaml\n");
        Files.writeString(dir.resolve("one.measures.yaml"), "home: one\nfollow:\n  - { measure: VAR, engine: var, subjects: desk, min-abs: 10, min-rel: 0 }\nevents: [ load ]\n");
        Files.writeString(dir.resolve("two.sutra.yaml"), "rachana: 1.2\nsutra: home-two\n");
        Files.writeString(dir.resolve("two.home.yaml"), "home: two\ntitle: Two\nsutra: two.sutra.yaml\n");
        HomeCatalogue c = new HomeCatalogue(new HomeProperties(true, data.resolve("homes").toString(), data.toString(), Map.of("r", List.of("one", "two")), null, null));
        HomeStore store = new HomeStore(data, 30);
        store.putSnapshot(LocalDate.parse("2026-11-19"), Instant.parse("2026-11-19T22:00:00Z"),
                List.of(new HomeData.MeasureValue("desk", "DESK-A", "VAR", 100), new HomeData.MeasureValue("desk", "DESK-B", "VAR", 80)));
        store.addEvent(LocalDate.parse("2026-11-19"), new HomeData.Event("feed", "p/old", "load", Instant.parse("2026-11-19T20:00:00Z"), "p/old loaded"));
        store.addEvent(LocalDate.parse("2026-11-19"), new HomeData.Event("feed", "p/new", "load", Instant.parse("2026-11-19T23:00:00Z"), "p/new loaded"));
        store.addEvent(LocalDate.parse("2026-11-20"), new HomeData.Event("feed", "p/today", "load", Instant.parse("2026-11-20T09:00:00Z"), "p/today loaded"));
        store.addEvent(LocalDate.parse("2026-11-20"), new HomeData.Event("desk", "DESK-Z", "limit-state", Instant.parse("2026-11-20T10:00:00Z"), "not a kind of this Home"));
        BusinessDates dates = new BusinessDates(new BusinessDateProperties(null, "UTC", null, "2026-11-20"), Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        HomeTables t = new HomeTables(new HomeProperties(true, null, data.toString(), null, null, null), store,
                new HomeClock(dates, Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC)), List.of(new MovingVar()),
                List.of(new OwnTable("module_table", List.of("one"))), ChangeEngineHost.NOT_HOSTED, List::of);
        Principal p = new Principal("u", List.of("r"));
        HomeDefinition one = c.find("one").orElseThrow(), two = c.find("two").orElseThrow();

        List<String> texts = t.rows("home_digest", p, one, Instant.parse("2026-11-20T00:00:00Z")).orElseThrow().stream().map(r -> String.valueOf(r.get("TEXT"))).toList();
        assertTrue(texts.contains("p/today loaded") && !texts.contains("p/new loaded") && !texts.contains("p/old loaded"), "only what happened since the visit: " + texts);
        assertTrue(texts.stream().anyMatch(x -> x.equals("DESK-A VAR 100 to 150 (+50)")) && texts.stream().noneMatch(x -> x.contains("DESK-B") || x.contains("not a kind")),
                "a followed measure that moved, not one that did not, and no event of another kind: " + texts);
        List<String> since = t.rows("home_digest", p, one, Instant.parse("2026-11-19T21:00:00Z")).orElseThrow().stream().map(r -> String.valueOf(r.get("TEXT"))).toList();
        assertTrue(since.contains("p/new loaded") && since.contains("p/today loaded") && !since.contains("p/old loaded"));
        assertEquals(3, t.rows("home_digest", p, one).orElseThrow().stream().filter(r -> String.valueOf(r.get("TEXT")).endsWith("loaded")).count(),
                "with no time the Home's own cut-off (yesterday 17:00) is the start");

        assertTrue(t.rows("module_table", p, one, null).isPresent());
        assertTrue(t.rows("module_table", p, two, null).isEmpty(), "a module's table is served to its own Homes only");
        assertTrue(t.names().contains("module_table") && t.names().contains("home_digest"));
    }
}
