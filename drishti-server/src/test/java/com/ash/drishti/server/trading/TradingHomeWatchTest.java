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
package com.ash.drishti.server.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The trader home's ticks: a new data version of a watched kind is a new generation and names the kind; nothing else is. */
class TradingHomeWatchTest {

    @Test
    void aNewVersionOfAWatchedKindIsANewGenerationThatNamesIt() {
        Map<String, String> v = new HashMap<>(Map.of("trade", "7", "trade-valuation", "3"));
        TradingHomeWatch w = new TradingHomeWatch(List.of("trade", "trade-valuation", "ir-curve"), k -> Optional.ofNullable(v.get(k)));
        TradingHomeWatch.State a = w.read();
        assertEquals("none", a.versions().get("ir-curve"), "a kind no connector serves yet is none");
        assertEquals(a.generation(), w.read().generation(), "the same versions: the same generation");
        v.put("trade-valuation", "4");
        TradingHomeWatch.State b = w.read();
        assertNotEquals(a.generation(), b.generation());
        assertEquals(List.of("trade-valuation"), b.changedSince(a));
        assertEquals(3, b.changedSince(null).size(), "no state before: every kind is new");
    }

    @Test
    void aSourceThatFailsIsNoneAndNeverATick() {
        TradingHomeWatch w = new TradingHomeWatch(List.of("trade"), k -> { throw new IllegalStateException("down"); });
        assertEquals("none", w.read().versions().get("trade"));
        assertEquals(w.read().generation(), w.read().generation());
    }

    @Test
    void theGenerationIsTheSameOnEveryNodeWhateverTheOrder() {
        assertEquals(TradingHomeWatch.generation(Map.of("a", "1", "b", "2")), TradingHomeWatch.generation(Map.of("b", "2", "a", "1")));
    }

    @Test
    void theDefaultsWatchTheTradingKindsAndBoundTheStreams() {
        TradingHomeProperties d = TradingHomeProperties.defaults();
        assertTrue(d.watch().containsAll(List.of("trade", "trade-valuation", "var-limit")));
        assertEquals(Duration.ofSeconds(2), d.frame());
        assertEquals(8, d.maxStreamsPerUser());
        assertEquals(List.of("x"), new TradingHomeProperties(List.of(" x ", "x", ""), null, null, null).watch());
    }

    @Test
    void theStreamSaysHelloThenTicksWhenTheDataMoves() throws Exception {
        Map<String, String> v = new HashMap<>(Map.of("trade", "1"));
        TradingHomeWatch w = new TradingHomeWatch(List.of("trade"), k -> Optional.ofNullable(v.get(k)));
        TradingHomeProperties props = new TradingHomeProperties(List.of("trade"), Duration.ofMillis(20), Duration.ofSeconds(30), 1);
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        try {
            TradingHomeController c = new TradingHomeController(w, props, null, ex);
            com.ash.drishti.server.security.Principal p = com.ash.drishti.server.security.Principal.anonymous("li.wei");
            SseEmitter e = c.live(p);
            assertTrue(e != null);
            org.junit.jupiter.api.Assertions.assertThrows(com.ash.drishti.common.DrishtiException.class, () -> c.live(p),
                    "one stream per person at most here: the second is refused in words");
            Map<String, Object> state = c.state(p).getBody();
            assertEquals(List.of("trade"), state.get("kinds"));
        } finally {
            ex.shutdownNow();
        }
    }
}
