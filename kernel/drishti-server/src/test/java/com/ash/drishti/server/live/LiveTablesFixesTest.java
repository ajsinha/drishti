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
package com.ash.drishti.server.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityDocument;
import com.ash.rachana.data.ProtocolError;
import com.ash.rachana.live.LiveTables;
import com.ash.rachana.live.spi.LiveStream;
import com.ash.rachana.live.spi.SnapshotSource;
import com.ash.rachana.table.LiveTableSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * G4 live tables LS1, LS3 and LV4 in the adapter: a failed source read is told to the reader in the source's words, made safe (no path,
 * no credential), and cleared by a good read; a titled snapshot (a FORM's result) gives each row its search title; a live table's audit
 * record names the caller and the change id.
 */
class LiveTablesFixesTest {

    @Test
    void aSourcesWordsAreMadeSafeToShow() {
        assertThat(ReadFailures.safe("no VaR inputs on this server: /home/ops/drishti/data/lakes/var/inputs.parquet is missing"))
                .isEqualTo("no VaR inputs on this server: inputs.parquet is missing");
        assertThat(ReadFailures.safe("cannot open C:\\Users\\ops\\lake\\pos.json")).isEqualTo("cannot open pos.json");
        assertThat(ReadFailures.safe("connect jdbc:postgresql://risk:s3cret@db.local:5432/risk failed")).doesNotContain("s3cret")
                .contains("***@db.local");
        assertThat(ReadFailures.safe("auth failed: password=hunter2 token: abc.def")).doesNotContain("hunter2").doesNotContain("abc.def");
        assertThat(ReadFailures.safe("a\nb\tc")).isEqualTo("a b c");
        assertThat(ReadFailures.safe("x".repeat(400))).hasSize(ReadFailures.MAX);
        assertThat(ReadFailures.safe(new RuntimeException(new IllegalStateException("POS needs VaR inputs")))).isEqualTo("POS needs VaR inputs");
        assertThat(ReadFailures.safe("see https://example.org/a/b for help")).isEqualTo("see https://example.org/a/b for help");
    }

    @Test
    void aFailedReadIsKeptInTheSourcesWordsAndAGoodOneClearsIt() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        Clock clock = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        ReadFailures failures = new ReadFailures(clock);
        boolean[] fail = {true};
        LiveTablesAdapterTest.Bindings b = new LiveTablesAdapterTest.Bindings() {
            @Override
            public ReadFailures failures() {
                return failures;
            }

            @Override
            public boolean documents(String kind, java.util.function.Consumer<EntityDocument> sink) {
                if (fail[0]) {
                    throw new IllegalStateException("position has no VaR inputs on this server (/srv/drishti/data/var is empty)");
                }
                return super.documents(kind, sink);
            }
        };
        KindSnapshot s = new KindSnapshot("position", b, List.of("id"));
        assertThatThrownBy(() -> s.read(null, r -> { })).isInstanceOf(IllegalStateException.class);
        assertThat(failures.of("position")).contains("position has no VaR inputs on this server (var is empty)");
        now.addAndGet(ReadFailures.FRESH_MS + 1);
        assertThat(failures.of("position")).as("an old failure is not told").isEmpty();
        assertThatThrownBy(() -> s.read(null, r -> { })).isInstanceOf(IllegalStateException.class);
        fail[0] = false;
        s.read(null, r -> { });
        assertThat(failures.of("position")).as("a good read clears it").isEmpty();
    }

    @Test
    void theProtocolsUnavailableAnswerCarriesTheSourcesWords() {
        ReadFailures failures = new ReadFailures();
        LiveTablesAdapterTest.Bindings b = new LiveTablesAdapterTest.Bindings() {
            @Override
            public ReadFailures failures() {
                return failures;
            }

            @Override
            public boolean documents(String kind, java.util.function.Consumer<EntityDocument> sink) {
                throw new IllegalStateException("POS needs VaR inputs: none are loaded for 2026-11-20");
            }
        };
        LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            public SnapshotSource snapshot(String kind) { return new KindSnapshot(kind, b, List.of("TRADE_ID")); }
            public LiveStream stream(String kind) { return new KindStream(kind, b, List.of("TRADE_ID")); }
        }, LiveTables.Options.DEFAULTS.withUpkeepMs(0), Clock.systemUTC());
        lt.declare(LiveTableSpec.read(LiveTablesAdapterTest.TRADE, "trade.live.yaml", DrishtiLiveTables.PROFILE_KEYS, f -> true));
        ProtocolError e = org.junit.jupiter.api.Assertions.assertThrows(ProtocolError.class, () -> lt.rows("trade", Map.of(), null));
        assertThat(e.status()).isEqualTo(503);
        LiveTablesController c = new LiveTablesController(lt, null, null, null, b, new com.fasterxml.jackson.databind.ObjectMapper());
        Object told = c.explained(e.status(), e.body());
        assertThat(String.valueOf(told)).contains("rdp.unavailable").contains("POS needs VaR inputs: none are loaded for 2026-11-20");
        assertThat(c.explained(404, Map.of("error", Map.of("code", "rdp.table.unknown")))).isEqualTo(Map.of("error", Map.of("code", "rdp.table.unknown")));
        lt.close();
    }

    @Test
    void aTitledSnapshotGivesEachRowItsSearchTitle() throws Exception {
        LiveTablesAdapterTest.Bindings b = new LiveTablesAdapterTest.Bindings() {
            @Override
            public Map<String, String> titles(String kind, com.ash.drishti.api.AsOf at) {
                return Map.of("T1", "Swap 5y EUR");
            }
        };
        b.docs.add(LiveTablesAdapterTest.trade("T1", 1, "B1", null));
        b.docs.add(LiveTablesAdapterTest.trade("T2", 1, "B1", null));
        List<Map<String, Object>> rows = new ArrayList<>();
        new KindSnapshot("trade", b, List.of("id"), com.ash.drishti.api.AsOf.LATEST, true, null, null, true).read(null, rows::add);
        assertThat(rows).extracting(r -> r.get(KindSnapshot.TITLE)).containsExactly("Swap 5y EUR", null);
        rows.clear();
        new KindSnapshot("trade", b, List.of("id"), com.ash.drishti.api.AsOf.LATEST, true, null, null, false).read(null, rows::add);
        assertThat(rows).allMatch(r -> !r.containsKey(KindSnapshot.TITLE));
    }

    @Test
    void aLiveAuditRecordNamesTheCallerAndTheChange() {
        List<String[]> got = new ArrayList<>();
        com.ash.drishti.identity.AuditLog a = new com.ash.drishti.identity.AuditLog() {
            public void record(String actor, String action, String subject, String detail) {
                got.add(new String[] {actor, action, subject, detail});
            }

            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("caller", new com.ash.drishti.kernel.api.security.Principal("ann", List.of("trader")));
        e.put("table", "trade");
        e.put("event", "write-held");
        e.put("reason", "waiting for approval (1 needed)");
        e.put("change", "01JCHANGE0001");
        e.put("columns", List.of("BOOK"));
        DrishtiLiveTables.audit(a, e);
        assertThat(got).hasSize(1);
        assertThat(got.get(0)).containsExactly("ann", "live-write-held", "trade:01JCHANGE0001",
                "waiting for approval (1 needed); change 01JCHANGE0001 (BOOK)");
    }
}
