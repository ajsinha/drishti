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
package com.ash.drishti.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.live.LiveMessage;
import com.ash.drishti.api.live.LivePosition;
import com.ash.drishti.common.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The live lander (LIVE_CONNECTORS_DESIGN.md sections 2.4 and 7.5): a landed kind's rows collect in a window, the newest per key; the
 * window lands as one batch per kind and date when its age or its keys say so, or on "apply now"; the batch id is its transport span; the
 * engine's rejects reach the reject log. One topic of two kinds told apart by a field ({@code kind-by}).
 */
class LiveLanderTest {

    private static final JsonCodec CODEC = new JsonCodec();

    /** scenario-pv and pnl-vector: landed, keyed for the window by TRADE_ID or POSITION_ID, ordered by COMPUTED_AT. */
    static final KindContracts PVS = new KindContracts() {
        final Map<String, LiveRules> rules = new LinkedHashMap<>();

        {
            for (String k : List.of("scenario-pv", "pnl-vector")) {
                Map<String, Object> live = new LinkedHashMap<>();
                live.put("order", List.of("COMPUTED_AT"));
                live.put("apply", "land");
                live.put("land-key", List.of("TRADE_ID", "POSITION_ID"));
                live.put("validate", "deferred");
                live.put("closed-dates", "restate");
                live.put("as-of", "BUSINESS_DATE");
                Map<String, Object> c = Map.of("kind", k, "class", "input", "key", List.of("SCENARIO_PV_ID"), "fields", List.of(Map.of("name", "SCENARIO_PV_ID"),
                        Map.of("name", "TRADE_ID"), Map.of("name", "POSITION_ID"), Map.of("name", "COMPUTED_AT"), Map.of("name", "VALUE_TYPE"), Map.of("name", "BUSINESS_DATE")), "live", live);
                LiveRules.Parsed p = LiveRules.parse(c, null);
                assertThat(p.problems()).isEmpty();
                rules.put(k, p.rules());
            }
        }

        @Override
        public LiveRules rules(String kind) {
            return rules.getOrDefault(kind, LiveRules.defaults(kind, List.of(), false));
        }

        @Override
        public Map<String, Object> contract(String kind) {
            return Map.of();
        }
    };

    static final class Lake implements Landing {
        final List<Batch> batches = new ArrayList<>();

        @Override
        public Landed land(Batch b) {
            batches.add(b);
            List<Reject> rejects = b.rows().stream().filter(r -> r.path("BAD").asBoolean(false))
                    .map(r -> new Reject(r.path("TRADE_ID").asText(), "DRS-5201", "the engine refused it")).toList();
            return new Landed(b.rows().size() - rejects.size(), rejects, null);
        }
    }

    private long offset;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);

    private LiveFeed feed(Lake lake, Map<String, String> settings, RejectLog log) {
        Map<String, String> s = new LinkedHashMap<>(Map.of("shape", "jsonl", "kind-by.field", "VALUE_TYPE", "kind-by.PV", "scenario-pv",
                "kind-by.PNL", "pnl-vector"));
        s.putAll(settings);
        LiveFeed f = new LiveFeed("risk-store-live", Set.of("scenario-pv", "pnl-vector"), s, PVS, null,
                b -> CODEC.read(new String(b, StandardCharsets.UTF_8)), new LiveState(1 << 20, null), log,
                LiveDates.of(() -> LocalDate.of(2026, 11, 16), d -> d.plusDays(1)), null, null);
        f.lander(new LiveLander("risk-store-live", s, lake, clock));
        return f;
    }

    private LiveMessage msg(String... lines) {
        return new LiveMessage(null, String.join("\n", lines), new LivePosition("pv", 0, offset++), Instant.parse("2026-11-16T10:00:00Z"));
    }

    private static String pv(String trade, String at) {
        return "{\"TRADE_ID\":\"" + trade + "\",\"VALUE_TYPE\":\"PV\",\"COMPUTED_AT\":\"" + at + "\",\"BUSINESS_DATE\":\"2026-11-16\"}";
    }

    @Test
    void theWindowKeepsTheNewestPerKeyAndLandsOneBatchPerKindOnApplyNow() {
        Lake lake = new Lake();
        LiveFeed f = feed(lake, Map.of(), null);
        f.accept(msg(pv("T-1", "2026-11-16T09:00:00Z"), pv("T-2", "2026-11-16T09:00:00Z")));
        f.accept(msg(pv("T-1", "2026-11-16T09:05:00Z")));
        f.accept(msg(pv("T-1", "2026-11-16T08:00:00Z")));                    // older than the waiting one: late
        f.accept(msg("{\"POSITION_ID\":\"P-1\",\"VALUE_TYPE\":\"PNL\",\"COMPUTED_AT\":\"2026-11-16T09:00:00Z\"}"));
        assertThat(f.lander().waiting()).isEqualTo(3);
        assertThat(f.counted("late")).isEqualTo(1);
        f.landIfDue();
        assertThat(lake.batches).as("not due: 5 minutes, 500 keys").isEmpty();
        f.lander().applyNow();
        f.landIfDue();
        assertThat(lake.batches).extracting(Landing.Batch::kind).containsExactly("pnl-vector", "scenario-pv");
        Landing.Batch spv = lake.batches.get(1);
        assertThat(spv.rows()).extracting(r -> r.path("COMPUTED_AT").asText()).containsExactly("2026-11-16T09:05:00Z", "2026-11-16T09:00:00Z");
        assertThat(spv.batchId()).startsWith("L-scenario-pv-2026-11-16-0.");
        assertThat(spv.date()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(f.lander().waiting()).isZero();
        assertThat(f.state().size()).as("a landed kind never enters the live state").isZero();
    }

    @Test
    void theWindowIsDueAtItsKeysAndTheEnginesRejectsAreLogged() throws Exception {
        Lake lake = new Lake();
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rejects");
        RejectLog log = new RejectLog(dir, 7, clock);
        LiveFeed f = feed(lake, Map.of("land.max-keys", "2"), log);
        f.accept(msg(pv("T-1", "2026-11-16T09:00:00Z"), pv("T-2", "2026-11-16T09:00:00Z").replace("}", ",\"BAD\":true}")));
        f.landIfDue();
        assertThat(lake.batches).hasSize(1);
        assertThat(f.counted("rejected.landing")).isEqualTo(1);
        assertThat(log.newest()).singleElement().satisfies(n -> assertThat(n.toString()).contains("DRS-5201").contains("T-2"));
    }

    @Test
    void anOverlayKindBoundForBothIsOverlaidAtOnceAndLandedByWindow() {
        Map<String, Object> c = Map.of("kind", "trade", "class", "input", "key", List.of("TRADE_ID"), "fields", List.of(Map.of("name", "TRADE_ID"),
                Map.of("name", "TRADE_VERSION"), Map.of("name", "MTM")), "live", Map.of("order", List.of("TRADE_VERSION")));
        LiveRules.Parsed parsed = LiveRules.parse(c, null);
        assertThat(parsed.problems()).isEmpty();
        LiveRules both = parsed.rules().withApply(LiveRules.Apply.BOTH);          // the site's binding: live: {connectors: [...], apply: both}
        KindContracts contracts = new KindContracts() {
            @Override
            public LiveRules rules(String kind) {
                return kind.equals("trade") ? both : LiveRules.defaults(kind, List.of(), false);
            }

            @Override
            public Map<String, Object> contract(String kind) {
                return c;
            }
        };
        Lake lake = new Lake();
        Map<String, String> s = Map.of("shape", "jsonl", "kind", "trade");
        LiveFeed f = new LiveFeed("trade-mq", Set.of("trade"), s, contracts, null, b -> CODEC.read(new String(b, StandardCharsets.UTF_8)),
                new LiveState(1 << 20, null), null, LiveDates.of(() -> LocalDate.of(2026, 11, 16), d -> d.plusDays(1)), null, null);
        f.lander(new LiveLander("trade-mq", s, lake, clock));
        f.accept(msg("{\"TRADE_ID\":\"T-1\",\"TRADE_VERSION\":1,\"MTM\":10}"));
        f.accept(msg("{\"TRADE_ID\":\"T-1\",\"TRADE_VERSION\":2,\"MTM\":11}"));
        assertThat(f.state().size()).as("overlaid at once").isEqualTo(1);
        f.lander().applyNow();
        f.landIfDue();
        assertThat(lake.batches).singleElement().satisfies(b -> {
            assertThat(b.kind()).isEqualTo("trade");
            assertThat(b.rows()).singleElement().satisfies(r -> assertThat(r.path("MTM").asInt()).isEqualTo(11));
        });
        assertThat(f.state().size()).as("still overlaid after it landed").isEqualTo(1);
    }

    @Test
    void theSameSpanIsTheSameBatch() {
        Lake a = new Lake();
        Lake b = new Lake();
        for (Lake lake : List.of(a, b)) {
            offset = 40;
            LiveFeed f = feed(lake, Map.of(), null);
            f.accept(msg(pv("T-1", "2026-11-16T09:00:00Z")));
            f.accept(msg(pv("T-2", "2026-11-16T09:00:00Z")));
            f.lander().applyNow();
            f.landIfDue();
        }
        assertThat(a.batches.get(0).batchId()).isEqualTo(b.batches.get(0).batchId()).isEqualTo("L-scenario-pv-2026-11-16-0.40-0.41");
    }

    @Test
    void aClosedDateIsRestatedOnlyWhereTheContractSaysSo() {
        Lake lake = new Lake();
        LiveFeed f = feed(lake, Map.of(), null);
        f.accept(msg(pv("T-1", "2026-11-13T09:00:00Z").replace("2026-11-16\"", "2026-11-13\"")));
        assertThat(f.lander().waiting()).as("closed-dates: restate on a landed kind").isEqualTo(1);
    }
}
