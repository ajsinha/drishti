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
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repeated heavy questions become rollup suggestions (docs/architecture/BI_RUNS.md section 8.3a): counted per model, grain and filters' shape (never a
 * value), past {@code suggest.min-runs} in {@code suggest.window} and {@code suggest.min-seconds}; accepted (the rollup drafted into the model's draft,
 * the decision kept across a restart) or dismissed; {@code built} once the model has a rollup at that grain.
 */
class RunSuggestionsTest {

    @TempDir Path dir;
    static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    static final SplittableRandom RANDOM = new SplittableRandom(7);
    static final RunsProperties.Suggest SETTINGS = new RunsProperties.Suggest(5, Duration.ofDays(7), 60);

    static Run heavy(String desk, long seconds, Instant at, String fields, Tier tier) {
        String q = "{\"type\":\"mq\",\"model\":\"trading-risk\",\"mq\":{\"mq\":1,\"model\":\"trading-risk\",\"select\":{\"fields\":" + fields
                + ",\"measures\":[\"MTM\",\"Trades\"]},\"where\":[{\"field\":\"trades.DESK\",\"op\":\"in\",\"values\":[\"" + desk + "\"]}]}}";
        Run r = Run.planned(RunId.next("java-1", at, RANDOM), "ann", "ann", "mq", q, "k", "s", tier, "local", "{}", "java-1", at);
        return r.moveTo(RunState.RUNNING, null, null, at).moveTo(RunState.DONE, null, null, at.plusSeconds(seconds));
    }

    static List<Run> log(int n, long seconds, String fields) {
        List<Run> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(heavy("DESK-" + i, seconds, NOW.minus(Duration.ofHours(i + 1)), fields, Tier.AD_HOC_HEAVY));     // other values, the same shape
        }
        return out;
    }

    static final String GRAIN = "[\"trades.DESK\",\"trades.TRADE_DATE.month\"]";

    @Test
    void fiveSlowRunsOfOneShapeAreASuggestionWithTheRollupDrafted() {
        RunSuggestions s = new RunSuggestions(SETTINGS, dir);
        List<RunSuggestions.Suggestion> out = s.of(log(6, 240, GRAIN), NOW, RunSuggestions.NONE_BUILT);
        assertThat(out).singleElement().satisfies(x -> {
            Map<String, Object> m = x.toMap();
            assertThat(m).containsEntry("model", "trading-risk").containsEntry("runs", 6).containsEntry("medianSeconds", 240.0).containsEntry("status", "suggested");
            assertThat(m.get("grain")).isEqualTo(List.of("trades.DESK", "trades.TRADE_DATE.month"));
            assertThat(m.get("filters")).isEqualTo(List.of("trades.DESK in"));               // the shape, never a value
            assertThat(m.toString()).doesNotContain("DESK-0").doesNotContain("DESK-1");
            assertThat(m.get("rollup")).isEqualTo(Map.of("name", "suggested_desk_trade_date", "table", "trades", "by", List.of("DESK", "TRADE_DATE"),
                    "measures", List.of("MTM", "Trades")));
            assertThat((String) m.get("words")).contains("asked 6 times").contains("4 min");
        });
    }

    @Test
    void belowEitherThresholdOrOutsideTheWindowOrNotHeavyIsNoSuggestion() {
        RunSuggestions s = new RunSuggestions(SETTINGS, dir);
        assertThat(s.of(log(4, 240, GRAIN), NOW, RunSuggestions.NONE_BUILT)).isEmpty();                 // too few
        assertThat(s.of(log(8, 20, GRAIN), NOW, RunSuggestions.NONE_BUILT)).isEmpty();                  // too fast
        List<Run> old = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            old.add(heavy("D", 240, NOW.minus(Duration.ofDays(8 + i)), GRAIN, Tier.AD_HOC_HEAVY));
        }
        assertThat(s.of(old, NOW, RunSuggestions.NONE_BUILT)).isEmpty();                                // outside the window
        List<Run> interactive = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            interactive.add(heavy("D", 240, NOW.minusSeconds(3600 + i), GRAIN, Tier.INTERACTIVE));
        }
        assertThat(s.of(interactive, NOW, RunSuggestions.NONE_BUILT)).isEmpty();                        // only ad-hoc heavy runs count
        List<Run> mixed = new ArrayList<>(log(3, 240, GRAIN));
        mixed.addAll(log(3, 240, "[\"trades.BOOK\"]"));
        assertThat(s.of(mixed, NOW, RunSuggestions.NONE_BUILT)).isEmpty();                              // two shapes of three: neither passes
    }

    @Test
    void acceptingDraftsTheRollupIntoTheModelAndIsKeptAcrossARestart() {
        RunSuggestions s = new RunSuggestions(SETTINGS, dir);
        AtomicReference<Map<String, Object>> drafted = new AtomicReference<>();
        s.drafter((admin, model, rollup) -> {
            drafted.set(rollup);
            return Optional.of("itm-model-1");
        });
        List<Run> log = log(5, 90, GRAIN);
        String id = s.of(log, NOW, RunSuggestions.NONE_BUILT).get(0).id();
        RunSuggestions.Suggestion a = s.accept(id, log, NOW, new Principal("ops", List.of("bi-admin")));
        assertThat(a.status()).isEqualTo("accepted");
        assertThat(a.item()).isEqualTo("itm-model-1");
        assertThat(drafted.get()).containsEntry("table", "trades");
        assertThat(Files.exists(dir.resolve("suggestions.json"))).isTrue();
        RunSuggestions again = new RunSuggestions(SETTINGS, dir);                                       // a restart
        assertThat(again.of(log, NOW, RunSuggestions.NONE_BUILT).get(0).status()).isEqualTo("accepted");
        assertThat(again.of(log, NOW, (m, r) -> "trading-risk".equals(m) && "trades".equals(r.get("table"))).get(0).status()).isEqualTo("built");
        assertThat(s.dismiss(id, log, NOW, "ops").status()).isEqualTo("dismissed");
        assertThatThrownBy(() -> s.accept("sg-nothing", log, NOW, new Principal("ops", List.of("bi-admin")))).isInstanceOf(DrishtiException.class);
    }

    @Test
    void theRollupJoinsTheModelsRollupsKeepingItsComments() {
        Map<String, Object> r = Map.of("name", "suggested_desk", "table", "trades", "by", List.of("DESK"), "measures", List.of("MTM"));
        String body = "rachana: 2\nmodel: m   # the model\nrollups:\n  - { name: a, table: trades, by: [BOOK], measures: [MTM] }\nsecurity: {}\n";
        assertThat(RunSuggestions.withRollup(body, r)).isEqualTo("rachana: 2\nmodel: m   # the model\nrollups:\n"
                + "  - { name: suggested_desk, table: trades, by: [DESK], measures: [MTM] }\n  - { name: a, table: trades, by: [BOOK], measures: [MTM] }\nsecurity: {}\n");
        assertThat(RunSuggestions.withRollup("model: m\n", r)).isEqualTo("model: m\nrollups:\n  - { name: suggested_desk, table: trades, by: [DESK], measures: [MTM] }\n");
        assertThat(RunSuggestions.withRollup("model: m\nrollups: []\n", r)).isEqualTo("model: m\nrollups:\n  - { name: suggested_desk, table: trades, by: [DESK], measures: [MTM] }\n");
        assertThatThrownBy(() -> RunSuggestions.withRollup("rollups: [{ name: a }]\n", r)).isInstanceOf(DrishtiException.class);
    }
}
