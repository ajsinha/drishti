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
package com.ash.drishti.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The state store of a message source stays within its budget: the entities written longest ago go first. */
class StateStoreBudgetTest {

    /** A message source without a broker: the test hands it messages. */
    static final class Fed extends MessageStateSource {
        @Override
        protected String plugin() {
            return "fed";
        }

        @Override
        protected void connect() {
            health.set("UP");
        }

        boolean send(String id, String body) {
            return accept(new Inbound("trades", id, body, false));
        }
    }

    private static Fed source(Path dir, String whenFull) throws Exception {
        Map<String, String> settings = new HashMap<>();
        settings.put("state.dir", dir.toString());
        settings.put("state.max-gb", String.valueOf(1.0 / 1024));         // 1 MB
        settings.put("state.when-full", whenFull);
        settings.put("state.durability", "wal");
        settings.put("state.check-seconds", "3600");                      // the test calls the check itself
        settings.put("kind", "trade");
        Fed f = new Fed();
        f.start(DatedSourceContract.context(settings));
        return f;
    }

    private static String doc(Random r, int i) {
        StringBuilder pad = new StringBuilder();
        for (int k = 0; k < 4000; k++) {
            pad.append((char) ('a' + r.nextInt(26)));                     // little to compress: the store really grows
        }
        return "{\"tradeId\":\"MX-" + i + "\",\"pad\":\"" + pad + "\"}";
    }

    @Test
    void theOldestEntitiesAreEvictedToStayWithinTheBudget() throws Exception {
        Path dir = Files.createTempDirectory("state");
        Fed f = source(dir, "evict-oldest");
        Random r = new Random(11);
        for (int i = 0; i < 1_000; i++) {                                 // about 4 MB into a 1 MB budget
            assertThat(f.send("MX-" + i, doc(r, i))).isTrue();
        }
        f.keepWithinBudget();
        assertThat(f.cacheStats().get("evicted")).isNotEqualTo(0L);
        assertThat(f.fetch(EntityRef.of("trade", "MX-999"))).as("the newest stays").isPresent();
        assertThat(f.fetch(EntityRef.of("trade", "MX-0"))).as("the oldest goes").isEmpty();
        assertThat((Double) f.cacheStats().get("stateMb")).isLessThanOrEqualTo(1.0);
        assertThat(f.health()).isEqualTo("UP");
        f.close();
    }

    @Test
    void warnKeepsEverythingAndSaysSo() throws Exception {
        Path dir = Files.createTempDirectory("state");
        Fed f = source(dir, "warn");
        Random r = new Random(11);
        for (int i = 0; i < 1_000; i++) {
            f.send("MX-" + i, doc(r, i));
        }
        f.keepWithinBudget();
        assertThat(f.fetch(EntityRef.of("trade", "MX-0"))).isPresent();
        assertThat(f.health()).startsWith("UP (state store over its budget").contains("nothing is dropped");
        f.close();
    }

    @Test
    void aMessageTheStoreCannotKeepIsNotAcknowledged() throws Exception {
        Path dir = Files.createTempDirectory("state");
        Fed f = source(dir, "evict-oldest");
        assertThat(f.send("MX-1", "{\"tradeId\":\"MX-1\"}")).isTrue();
        assertThat(f.send("MX-2", "not json")).as("unreadable: done with, so it does not come back forever").isTrue();
        f.close();                                                        // the store is closed: nothing can be kept
        assertThat(f.send("MX-3", "{\"tradeId\":\"MX-3\"}")).isFalse();
        assertThat(f.health()).startsWith("DOWN: the store is closed");
    }
}
