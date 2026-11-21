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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.marketdata.MarketStandIn.Person;
import com.ash.drishti.testroots.RecordedAnswers;
import com.ash.lake.tck.TestRoots;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Drishti Market's landings worked out by the market-data module (THIN_CONSOLE_DESIGN.md step S8a) over the stand-in market data with known
 * problems ({@link MarketStandIn}): the figures the console's Python worked out before S8a (the desk's tiles with a day-over-day change only
 * where both days hold real data, the USD curves with the week-ago line, the biggest moves in sigma or none, each quality check by severity
 * with the clean share), two people at once, and the recorded answers the console's stand-in replays
 * ({@code drishti-console/tests/answers/landings/market-data.json}, kept by {@link RecordedAnswers}: {@code -Ddrishti.record=true} writes it
 * again, otherwise a recording that differs from what this code answers fails here).
 */
class MarketLandingRecordTest {

    static final Path REPO = Path.of(TestRoots.repo());
    /** The people the console's tests sign in as (test_edition_market_browser.py: ash; test_edition.py: alice), in the market lens. */
    static final List<Person> RECORDED = List.of(new Person("ash", "Ash", List.of("*"), "market"), new Person("alice", "Alice", List.of("*"), "market"));

    static Map<String, Object> part(MarketStandIn kernel, MarketLandingController api, Person who, String part) {
        return MarketStandIn.wire(kernel.as(who, () -> api.part(part).getBody()));     // as a client reads it
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> byId(Object tiles) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object t : (List<Object>) tiles) {
            out.put((String) ((Map<String, Object>) t).get("id"), t);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static <T> T at(Object node, String... path) {
        Object x = node;
        for (String p : path) {
            x = ((Map<String, Object>) x).get(p);
        }
        return (T) x;
    }

    @Test
    void theDeskTilesAndCurvesComeFromTheData() {
        MarketStandIn kernel = new MarketStandIn(REPO, true);
        Map<String, Object> d = part(kernel, new MarketLandingController(new MarketLanding(kernel)), RECORDED.get(0), "desk");
        Map<String, Object> tiles = byId(d.get("tiles"));
        assertThat(d).containsEntry("businessDate", "2026-09-30").containsEntry("dataDate", "2026-09-30").containsEntry("curves", 28)
                .containsEntry("kinds", 20);
        assertThat((Double) at(tiles, "ois10y", "value")).isCloseTo(3.926491, within(1e-4));
        assertThat((Double) at(tiles, "ois10y", "change", "delta")).isCloseTo(-4.0, within(1e-9));
        assertThat((String) at(tiles, "ois10y", "change", "since")).isEqualTo("2026-09-29");
        assertThat((Double) at(tiles, "slope", "value")).isEqualTo(46.0);
        assertThat((Double) at(tiles, "slope", "change", "delta")).isCloseTo(1.0, within(1e-9));
        assertThat((Double) at(tiles, "fxvol", "value")).isEqualTo(7.8);
        assertThat((Object) at(tiles, "fxvol", "change")).as("no history the day before").isNull();
        assertThat((Double) at(tiles, "cds", "value")).as("the median of three names, the engine's").isEqualTo(70.0);
        assertThat((Double) at(tiles, "cds", "change", "delta")).isCloseTo(1.0, within(1e-9));
        assertThat((String) at(tiles, "cds", "sub")).isEqualTo("CDS · 3 names");
        List<Map<String, Object>> ois = at(d, "curve", "ois");
        assertThat(ois.stream().limit(3).map(p -> p.get("tenor"))).containsExactly("1M", "3M", "6M");
        assertThat((List<?>) at(d, "curve", "projection")).hasSize(12);
        assertThat((String) at(d, "curve", "weekAgo", "date")).isEqualTo("2026-09-23");
        List<Map<String, Object>> ago = at(d, "curve", "weekAgo", "points");
        assertThat((Double) ago.get(0).get("rate")).isCloseTo((Double) ois.get(0).get("rate") + 0.05, within(1e-9));
        assertThat((List<?>) d.get("problems")).isEmpty();
    }

    @Test
    void withoutHistoryNoChangeNoWeekAgoAndNoMoves() {
        MarketStandIn kernel = new MarketStandIn(REPO, false);
        MarketLandingController api = new MarketLandingController(new MarketLanding(kernel));
        Map<String, Object> d = part(kernel, api, RECORDED.get(0), "desk");
        byId(d.get("tiles")).values().forEach(t -> assertThat((Object) at(t, "change")).isNull());
        assertThat((Object) at(d, "curve", "weekAgo")).isNull();
        assertThat((List<?>) at(d, "curve", "projection")).isNotEmpty();
        Map<String, Object> m = part(kernel, api, RECORDED.get(0), "moves");
        assertThat(m).containsEntry("mode", "none");
        assertThat((List<?>) m.get("items")).isEmpty();
    }

    @Test
    void theBiggestMovesAreInSigmaOfTheirOwnHistory() {
        MarketStandIn kernel = new MarketStandIn(REPO, true);
        Map<String, Object> m = part(kernel, new MarketLandingController(new MarketLanding(kernel)), RECORDED.get(0), "moves");
        assertThat(m).containsEntry("mode", "sigma");
        List<Map<String, Object>> items = at(m, "items");
        Map<String, Object> top = items.get(0);
        assertThat(top).containsEntry("id", "CRV-EUR-OIS").containsEntry("unit", "bp").containsEntry("date", "2026-09-30").containsEntry("since", "2026-09-29");
        assertThat(((Number) top.get("move")).doubleValue()).isCloseTo(31.0, within(1.1));
        assertThat(((Number) top.get("sigma")).doubleValue()).isGreaterThan(3);
        assertThat(((Number) top.get("days")).intValue()).isGreaterThanOrEqualTo(MarketLanding.SIGMA_MIN_DAYS);
        assertThat(items).allSatisfy(i -> assertThat((String) i.get("cmd")).matches("^(CRV|CDS) .*"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theQualityChecksFindEachKnownProblemAtTheLenssThreshold() {
        MarketStandIn kernel = new MarketStandIn(REPO, true);
        MarketLanding landing = new MarketLanding(kernel);
        Map<String, Object> q = part(kernel, new MarketLandingController(landing), RECORDED.get(0), "quality");
        assertThat(q).containsEntry("businessDate", "2026-09-30").containsEntry("staleDays", 2)    // config/lenses/banking/market.yaml
                .containsEntry("checked", 14).containsEntry("clean", 8);
        assertThat(((Number) q.get("cleanPct")).doubleValue()).isCloseTo(57.1, within(0.05));
        Set<String> crit = ((List<Map<String, Object>>) q.get("critical")).stream().map(e -> e.get("id") + "/" + e.get("check")).collect(Collectors.toSet());
        Set<String> warn = ((List<Map<String, Object>>) q.get("warnings")).stream().map(e -> e.get("id") + "/" + e.get("check")).collect(Collectors.toSet());
        assertThat(crit).containsExactlyInAnyOrder("CRV-EUR-OIS/discount factor", "FXV-USDTRY/calendar spread", "EQV-ZERO/negative vol");
        assertThat(warn).containsExactlyInAnyOrder("CRV-GBP-OIS/stale", "INF-UKRPI/stale", "CRV-AUD-PROJ/gap", "CRV-EUR-OIS/negative forward");
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (String k : List.of("critical", "warnings")) {
            ((List<Map<String, Object>>) q.get(k)).forEach(e -> by.put(e.get("id") + "/" + e.get("check"), e));
        }
        assertThat(by.get("CRV-GBP-OIS/stale")).containsEntry("detail", "as of 2026-09-24, 4 business days old");
        assertThat((String) by.get("INF-UKRPI/stale").get("detail")).contains("latest INF");
        assertThat((String) by.get("CRV-AUD-PROJ/gap").get("detail")).startsWith("15Y missing");
        assertThat(by.get("CRV-EUR-OIS/discount factor")).containsEntry("detail", "does not fall at 30Y");
        assertThat(by.get("FXV-USDTRY/calendar spread")).containsEntry("detail", "total variance falls 3M to 6M");
        assertThat(by.get("CRV-EUR-OIS/negative forward")).containsEntry("cmd", "CRV CRV-EUR-OIS");
        Map<String, Object> flagged = new LinkedHashMap<>();
        ((List<Map<String, Object>>) q.get("checks")).forEach(c -> flagged.put((String) c.get("id"), c.get("flagged")));
        assertThat(flagged).containsExactly(Map.entry("stale", 2), Map.entry("gap", 1), Map.entry("forwards", 1), Map.entry("surface", 2));
        // the threshold is the lens's: four business days is not more than 4; with 0 both are stale
        Map<String, Object> four = MarketStandIn.wire(kernel.as(RECORDED.get(0), () -> landing.quality(landing.caller(), 4)));
        assertThat((List<Map<String, Object>>) four.get("warnings")).noneMatch(e -> "stale".equals(e.get("check")));
        Map<String, Object> none = MarketStandIn.wire(kernel.as(RECORDED.get(1), () -> landing.quality(landing.caller(), 0)));
        assertThat(((List<Map<String, Object>>) none.get("warnings")).stream().filter(e -> "stale".equals(e.get("check"))).map(e -> e.get("id")))
                .containsExactlyInAnyOrder("CRV-GBP-OIS", "INF-UKRPI");
    }

    @Test
    void twoPeopleAtOnceEachReadAsThemselves() {
        MarketStandIn kernel = new MarketStandIn(REPO, true);
        MarketLandingController api = new MarketLandingController(new MarketLanding(kernel));
        List<CompletableFuture<Map<String, Object>>> runs = new ArrayList<>();
        for (Person p : RECORDED) {
            runs.add(CompletableFuture.supplyAsync(() -> part(kernel, api, p, "desk")));
        }
        List<Map<String, Object>> answers = runs.stream().map(CompletableFuture::join).toList();
        assertThat(answers.get(0)).isEqualTo(answers.get(1));
        for (Person p : RECORDED) {
            assertThat(kernel.asked).as("every read of " + p.user() + " made as " + p.user()).anyMatch(a -> a.startsWith(p.user() + " GET /search"));
        }
    }

    @Test
    void theRecordedAnswersAreTheModulesOwn() throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        MarketStandIn kernel = new MarketStandIn(REPO, true);
        MarketLandingController api = new MarketLandingController(new MarketLanding(kernel));
        List<Map<String, Object>> people = new ArrayList<>();
        for (Person p : RECORDED) {
            Map<String, Object> answers = new LinkedHashMap<>();
            for (String part : List.of("desk", "moves", "quality")) {
                answers.put("GET /market-data/landing/" + part, part(kernel, api, p, part));
            }
            people.add(MarketStandIn.m("user", p.user(), "roles", p.roles(), "lens", p.lens(), "settings", Map.of(), "answers", answers,
                    "checks", List.of()));
        }
        RecordedAnswers rec = RecordedAnswers.under(REPO, "landings");
        rec.keep("market-data.json", json.writeValueAsString(MarketStandIn.m("schema", 1, "module", "market-data",
                "about", "Drishti Market's landings as the market-data module answers them over MarketStandIn's data: written by "
                        + "MarketLandingRecordTest (-Ddrishti.record=true), replayed by the console's stand-in (tests/landing_answers.py)",
                "people", people)));
        rec.assertCurrent();
    }
}
