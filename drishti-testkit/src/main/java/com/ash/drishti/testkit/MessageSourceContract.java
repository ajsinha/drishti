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
package com.ash.drishti.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * What every message-queue connector must do, against a real broker: documents and envelopes become entities,
 * updates reach live views, deletes remove, the latest state survives a restart of Drishti (a queue cannot send it
 * again), messages sent while Drishti was down arrive when it returns, and search finds what arrived. A broker's test
 * extends this and supplies the plugin and a producer. Destinations: {@code drishti.trades} (documents of kind
 * {@code trade}, id field {@code tradeId}) and {@code drishti.entities} (envelopes).
 */
public abstract class MessageSourceContract {

    public static final String TRADES = "drishti.trades";
    public static final String ENTITIES = "drishti.entities";

    /** A started plugin reading both destinations, keeping its state in {@code state}. */
    protected abstract SourcePlugin start(Path state) throws Exception;

    /** Sends one message; {@code id} and {@code deleted} become headers when given. */
    protected abstract void send(String destination, String id, String body, boolean deleted) throws Exception;

    /** The settings every broker's plugin shares (merge in the connection settings). */
    protected static Map<String, String> shared(Path state) {
        return Map.of("kind." + TRADES, "trade", "id-field." + TRADES, "tradeId", "state.dir", state.toString(), "source-name", "queue-test");
    }

    protected static void eventually(BooleanSupplier ok, int seconds) throws InterruptedException {
        long until = System.nanoTime() + seconds * 1_000_000_000L;
        while (!ok.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(ok.getAsBoolean()).as("within " + seconds + " s").isTrue();
    }

    private static double mtm(SourcePlugin p, String id) {
        try {
            return p.fetch(EntityRef.of("trade", id)).map(d -> d.data().get("mtm").asDouble()).orElse(Double.NaN);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    @Test
    void messagesBecomeLiveEntitiesThatSurviveARestart() throws Exception {
        Path state = Files.createTempDirectory("drishti-queue-state");
        SourcePlugin p = start(state);
        try {
            eventually(() -> p.health().startsWith("UP"), 60);
            send(TRADES, null, "{\"tradeId\":\"Q-1\",\"mtm\":10}", false);
            send(ENTITIES, null, "{\"kind\":\"netting-set\",\"id\":\"NS-Q\",\"doc\":{\"nettingSetId\":\"NS-Q\",\"netMtm\":5}}", false);
            send(TRADES, "Q-9", "{\"mtm\":90}", false);                                          // the id from a header
            eventually(() -> mtm(p, "Q-1") == 10 && mtm(p, "Q-9") == 90, 30);
            assertThat(p.fetch(EntityRef.of("netting-set", "NS-Q")).orElseThrow().data().get("netMtm").asDouble()).isEqualTo(5);
            List<Double> seen = new CopyOnWriteArrayList<>();
            var sub = p.subscribe(EntityRef.of("trade", "Q-1"), (EntityDocument d) -> seen.add(d.data().get("mtm").asDouble()));
            send(TRADES, null, "{\"tradeId\":\"Q-1\",\"mtm\":11}", false);
            eventually(() -> seen.contains(11.0), 30);
            sub.close();
            send(TRADES, "Q-9", "", true);                                                     // a delete
            eventually(() -> Double.isNaN(mtm(p, "Q-9")), 30);
            assertThat(p.search("trade", "q-", 10)).extracting(h -> h.ref().id()).containsExactly("Q-1");
            assertThat(p.manifest().kinds()).contains("trade", "netting-set");
        } finally {
            p.close();
        }
        send(TRADES, null, "{\"tradeId\":\"Q-2\",\"mtm\":20}", false);                          // while Drishti is down
        SourcePlugin again = start(state);
        try {
            assertThat(mtm(again, "Q-1")).isEqualTo(11);                                        // kept on disk: not sent again
            eventually(() -> mtm(again, "Q-2") == 20, 60);                                      // waited in the broker
            assertThat(Double.isNaN(mtm(again, "Q-9"))).isTrue();
        } finally {
            again.close();
        }
    }
}
