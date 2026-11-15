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
package com.ash.drishti.common.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.live.LiveMessage;
import com.ash.drishti.api.live.LivePosition;
import com.ash.drishti.common.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live intake with a fake transport, no broker (LIVE_CONNECTORS_DESIGN.md sections 3 and 4): shapes, operations, merge patches, order,
 * duplicates, late events, deletes and their tombstones, the contract's checks, the connector's shape, rejects, read-back.
 */
class LiveFeedTest {

    @TempDir
    Path tmp;

    private static final JsonCodec CODEC = new JsonCodec();

    /** The trade contract, abridged: key, a version and an event time as its order, absorbed by order. */
    static final class Trades implements KindContracts {
        final Map<String, Object> contract = new LinkedHashMap<>();
        final LiveRules rules;

        Trades(Map<String, Object> live) {
            contract.put("kind", "trade");
            contract.put("class", "fundamental");
            contract.put("key", List.of("TRADE_ID"));
            contract.put("fields", List.of(
                    Map.of("name", "TRADE_ID", "type", "id", "presence", "required"),
                    Map.of("name", "TRADE_VERSION", "type", "integer", "presence", "required"),
                    Map.of("name", "SOURCE_UPDATE_TIME", "type", "timestamp", "presence", "optional"),
                    Map.of("name", "TRADE_STATUS", "type", "text", "presence", "optional", "enum", List.of("LIVE", "CANCELLED")),
                    Map.of("name", "BOOK", "type", "text", "presence", "optional"),
                    Map.of("name", "UPDATED_BY", "type", "text", "presence", "optional")));
            contract.put("live", live);
            LiveRules.Parsed p = LiveRules.parse(contract, null);
            assertThat(p.problems()).isEmpty();
            rules = p.rules();
        }

        @Override
        public LiveRules rules(String kind) {
            return "trade".equals(kind) ? rules : LiveRules.defaults(kind, List.of(), false);
        }

        @Override
        public Map<String, Object> contract(String kind) {
            return "trade".equals(kind) ? contract : Map.of();
        }
    }

    static Map<String, Object> ordered() {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("order", List.of("TRADE_VERSION", "SOURCE_UPDATE_TIME"));
        live.put("changed-at", "SOURCE_UPDATE_TIME");
        live.put("changed-by", "UPDATED_BY");
        live.put("absorb", "by-order");
        return live;
    }

    static DataNode parse(byte[] b) {
        return CODEC.read(new String(b, StandardCharsets.UTF_8));
    }

    private LiveFeed feed(KindContracts contracts, Map<String, String> settings, UnaryOperator<EntityDocument> shape) {
        return new LiveFeed("trade-endur-live", Set.of("trade"), settings, contracts, shape, LiveFeedTest::parse, new LiveState(1 << 20, null), new RejectLog(tmp.resolve("rejects").resolve("trade-endur-live"), 7, Clock.systemUTC()));
    }

    private static long offset;

    private static LiveMessage msg(String key, String value) {
        return msg("trades", 0, key, value);
    }

    private static LiveMessage msg(String topic, int partition, String key, String value) {
        return new LiveMessage(key, value, new LivePosition(topic, partition, offset++), Instant.parse("2026-11-16T10:00:00Z"));
    }

    private static String trade(String id, int version, String book) {
        return "{\"TRADE_ID\":\"" + id + "\",\"TRADE_VERSION\":" + version + ",\"BOOK\":\"" + book + "\",\"SOURCE_UPDATE_TIME\":\"2026-11-16T10:0"
                + version + ":00Z\"}";
    }

    private static String book(LiveFeed f, String id) {
        return f.fetch(EntityRef.of("trade", id)).map(d -> d.data().get("BOOK").asText()).orElse(null);
    }

    @Test
    void newerReplacesEqualIsADuplicateOlderIsLate() {
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade"), null);
        f.accept(msg("MX-1", trade("MX-1", 2, "B2")));
        f.accept(msg("MX-1", trade("MX-1", 2, "B2")));                     // sent again: the same order, a later offset, newer by position
        f.accept(msg("MX-1", trade("MX-1", 1, "B1")));                     // late: version 1 after 2
        f.accept(msg("MX-1", trade("MX-1", 3, "B3")));
        assertThat(book(f, "MX-1")).isEqualTo("B3");
        assertThat(f.counted("applied")).isEqualTo(3);
        assertThat(f.counted("late")).isEqualTo(1);
    }

    @Test
    void aReplayOfTheSamePositionIsADuplicate() {
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade"), null);
        LiveMessage m = msg("MX-1", trade("MX-1", 1, "B1"));
        f.accept(m);
        f.accept(m);
        assertThat(f.counted("duplicate")).isEqualTo(1);
    }

    @Test
    void acrossPartitionsTheOrderFieldsDecide() {
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade"), null);
        f.accept(msg("trades", 1, "MX-1", trade("MX-1", 3, "B3")));
        f.accept(msg("trades", 0, "MX-1", trade("MX-1", 2, "B2")));       // arrives later, from another partition, older
        assertThat(book(f, "MX-1")).isEqualTo("B3");
        f.accept(msg("trades", 0, "MX-1", trade("MX-1", 3, "B9")));       // the same order from another partition: a duplicate
        assertThat(book(f, "MX-1")).isEqualTo("B3");
        assertThat(f.counted("duplicate")).isEqualTo(1);
    }

    @Test
    void byArrivalTheLaterMessageWins() {
        LiveFeed g = new LiveFeed("notes", Set.of(), Map.of("kind", "note", "id-field", "ID"), KindContracts.NONE, null,
                LiveFeedTest::parse, new LiveState(1 << 20, null), null);
        g.accept(msg("notes", 1, null, "{\"ID\":\"N-1\",\"V\":\"a\"}"));
        g.accept(msg("notes", 0, null, "{\"ID\":\"N-1\",\"V\":\"b\"}"));
        assertThat(g.fetch(EntityRef.of("note", "N-1")).orElseThrow().data().get("V").asText()).isEqualTo("b");
        assertThat(g.counted("applied")).isEqualTo(2);
    }

    @Test
    void aDeleteIsRememberedSoAnOlderUpsertCannotBringTheRowBack() {
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade"), null);
        List<EntityDocument> seen = new ArrayList<>();
        f.subscribe(EntityRef.of("trade", "MX-1"), seen::add);
        f.accept(msg("MX-1", trade("MX-1", 2, "B2")));
        f.accept(msg("MX-1", null));                                       // a tombstone
        assertThat(f.fetch(EntityRef.of("trade", "MX-1"))).isEmpty();
        assertThat(f.deleted(EntityRef.of("trade", "MX-1"))).isTrue();
        assertThat(seen).last().matches(EntityDocument::deleted);
        f.accept(msg("trades", 1, "MX-1", trade("MX-1", 1, "B1")));        // older, from another partition
        assertThat(f.fetch(EntityRef.of("trade", "MX-1"))).isEmpty();
        assertThat(f.counted("late")).isEqualTo(1);
        f.accept(msg("trades", 1, "MX-1", trade("MX-1", 3, "B3")));        // newer: the trade is back
        assertThat(book(f, "MX-1")).isEqualTo("B3");
    }

    @Test
    void theEnvelopeCarriesKindKeyOpAndDoc() {
        LiveFeed f = new LiveFeed("mixed", Set.of(), Map.of(), new Trades(ordered()), null, LiveFeedTest::parse,
                new LiveState(1 << 20, null), null);
        f.accept(msg("entities", 0, "trade/MX-5", "{\"kind\":\"trade\",\"key\":\"MX-5\",\"doc\":" + trade("MX-5", 1, "B1") + "}"));
        f.accept(msg("entities", 0, "netting-set/NS-1", "{\"kind\":\"netting-set\",\"id\":\"NS-1\",\"doc\":{\"netMtm\":2}}"));
        assertThat(book(f, "MX-5")).isEqualTo("B1");
        assertThat(f.fetch(EntityRef.of("netting-set", "NS-1"))).isPresent();
        f.accept(msg("entities", 0, "trade/MX-5", "{\"kind\":\"trade\",\"key\":\"MX-5\",\"op\":\"delete\"}"));
        assertThat(f.fetch(EntityRef.of("trade", "MX-5"))).isEmpty();
        f.accept(msg("entities", 0, "trade/MX-6", "{\"kind\":\"trade\",\"key\":\"MX-7\",\"doc\":" + trade("MX-6", 1, "B1") + "}"));
        assertThat(f.counted("rejected.key-mismatch")).isEqualTo(1);
        f.accept(msg("entities", 0, "x", "{\"kind\":\"trade\",\"op\":\"replace-begin\"}"));
        assertThat(f.counted("rejected.contract")).isEqualTo(1);
    }

    @Test
    void jsonlCarriesSeveralRows() {
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade", "shape", "jsonl"), null);
        f.accept(msg(null, trade("MX-1", 1, "B1") + "\n" + trade("MX-2", 1, "B2") + "\nnot json\n"));
        assertThat(book(f, "MX-1")).isEqualTo("B1");
        assertThat(book(f, "MX-2")).isEqualTo("B2");
        assertThat(f.counted("rejected.not-json")).isEqualTo(1);
    }

    @Test
    void theContractDecidesWhatIsRejectedAndEveryRejectIsLogged() throws Exception {
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade", "max-message-kb", "1"), null);
        f.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\"}"));                                       // TRADE_VERSION missing
        f.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\",\"TRADE_VERSION\":\"seven\"}"));           // not a number
        f.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\",\"TRADE_VERSION\":1,\"TRADE_STATUS\":\"GONE\"}")); // outside the enum
        f.accept(msg("MX-2", trade("MX-1", 1, "B1")));                                          // the message key says MX-2
        f.accept(msg("MX-1", "{\"TRADE_VERSION\":1}"));                                         // no key
        f.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\",\"PAD\":\"" + "x".repeat(2000) + "\"}"));   // over 1 KB
        f.accept(msg("MX-1", "{oops"));
        assertThat(f.counted("rejected.contract")).isEqualTo(3);
        assertThat(f.counted("rejected.key-mismatch")).isEqualTo(1);
        assertThat(f.counted("rejected.no-key")).isEqualTo(1);
        assertThat(f.counted("rejected.too-large")).isEqualTo(1);
        assertThat(f.counted("rejected.not-json")).isEqualTo(1);
        assertThat(f.fetch(EntityRef.of("trade", "MX-1"))).isEmpty();
        Path log = tmp.resolve("rejects").resolve("trade-endur-live");
        List<String> lines;
        try (var files = Files.list(log)) {
            lines = Files.readAllLines(files.findFirst().orElseThrow(), StandardCharsets.UTF_8);
        }
        assertThat(lines).hasSize(7);
        assertThat(lines.get(0)).contains("\"reason\":\"contract\"").contains("\"code\":\"DRS-1065\"").contains("TRADE_VERSION is required")
                .contains("\"position\":\"trades/0@");
        assertThat(f.rejects().newest()).hasSize(7);
    }

    @Test
    void aLenientKindPassesValuesOutsideItsEnumAndADeferredOneChecksOnlyItsKey() {
        Map<String, Object> lenient = ordered();
        lenient.put("validate", "lenient");
        LiveFeed f = feed(new Trades(lenient), Map.of("kind", "trade"), null);
        f.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\",\"TRADE_VERSION\":1,\"TRADE_STATUS\":\"GONE\"}"));
        assertThat(f.fetch(EntityRef.of("trade", "MX-1"))).isPresent();
        Map<String, Object> deferred = ordered();
        deferred.put("validate", "deferred");
        LiveFeed g = feed(new Trades(deferred), Map.of("kind", "trade"), null);
        g.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\",\"TRADE_VERSION\":\"seven\"}"));
        assertThat(g.fetch(EntityRef.of("trade", "MX-1"))).isPresent();
    }

    @Test
    void aMergeKindPatchesTheRowItHolds() {
        Map<String, Object> live = ordered();
        live.put("partial", "merge");
        LiveFeed f = feed(new Trades(live), Map.of("kind", "trade"), null);
        f.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\",\"TRADE_VERSION\":1,\"BOOK\":\"B1\",\"UPDATED_BY\":\"ann\"}"));
        f.accept(msg("MX-1", "{\"TRADE_ID\":\"MX-1\",\"TRADE_VERSION\":2,\"BOOK\":\"B2\",\"UPDATED_BY\":null}"));
        DataNode row = f.fetch(EntityRef.of("trade", "MX-1")).orElseThrow().data();
        assertThat(row.get("BOOK").asText()).isEqualTo("B2");
        assertThat(row.get("UPDATED_BY").isMissing()).as("an explicit null clears").isTrue();
        LiveFeed g = new LiveFeed("env", Set.of("trade"), Map.of("shape", "envelope"), new Trades(live), null, LiveFeedTest::parse,
                new LiveState(1 << 20, null), null);
        g.accept(msg("e", 0, "trade/MX-9", "{\"kind\":\"trade\",\"key\":\"MX-9\",\"op\":\"patch\",\"doc\":{\"TRADE_ID\":\"MX-9\",\"BOOK\":\"B1\"}}"));
        assertThat(g.counted("rejected.patch-without-base")).isEqualTo(1);
        LiveFeed whole = new LiveFeed("env", Set.of("trade"), Map.of("shape", "envelope"), new Trades(ordered()), null,
                LiveFeedTest::parse, new LiveState(1 << 20, null), null);
        whole.accept(msg("e", 0, "trade/MX-9", "{\"kind\":\"trade\",\"key\":\"MX-9\",\"op\":\"patch\",\"doc\":{\"TRADE_ID\":\"MX-9\"}}"));
        assertThat(whole.counted("rejected.contract")).as("a patch on a whole-row kind").isEqualTo(1);
    }

    @Test
    void theConnectorsShapeRunsBeforeTheContractAndItsWhereLeavesRowsOut() {
        UnaryOperator<EntityDocument> shape = d -> {
            Map<String, Object> raw = new LinkedHashMap<>(castMap(d.data().unwrap()));
            if ("MUREX".equals(raw.get("SYS"))) {
                return null;                                                    // where SYS = 'ENDUR'
            }
            raw.put("TRADE_ID", raw.remove("DealNum"));                         // map: DealNum -> TRADE_ID
            return new EntityDocument(d.ref(), DataNode.of(raw), d.provenance());
        };
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade"), shape);
        f.accept(msg("E-1", "{\"DealNum\":\"E-1\",\"TRADE_VERSION\":1,\"SYS\":\"ENDUR\"}"));
        f.accept(msg("M-1", "{\"DealNum\":\"M-1\",\"TRADE_VERSION\":1,\"SYS\":\"MUREX\"}"));
        assertThat(f.fetch(EntityRef.of("trade", "E-1"))).isPresent();
        assertThat(f.fetch(EntityRef.of("trade", "M-1"))).isEmpty();
        assertThat(f.counted("filtered")).isEqualTo(1);
    }

    @Test
    void aKindTheConnectorDoesNotServeOrThatTakesNoFeedIsRejected() {
        LiveFeed f = new LiveFeed("x", Set.of("trade"), Map.of("shape", "envelope"), KindContracts.NONE, null, LiveFeedTest::parse,
                new LiveState(1 << 20, null), null);
        f.accept(msg("e", 0, "curve/C-1", "{\"kind\":\"curve\",\"key\":\"C-1\",\"doc\":{\"A\":1}}"));
        assertThat(f.counted("rejected.unknown-kind")).isEqualTo(1);
        KindContracts computed = new KindContracts() {
            @Override
            public LiveRules rules(String kind) {
                return LiveRules.defaults(kind, List.of("POSITION_ID"), true);
            }

            @Override
            public Map<String, Object> contract(String kind) {
                return Map.of();
            }
        };
        LiveFeed g = new LiveFeed("p", Set.of("position"), Map.of("kind", "position"), computed, null, LiveFeedTest::parse,
                new LiveState(1 << 20, null), null);
        g.accept(msg("P-1", "{\"POSITION_ID\":\"P-1\"}"));
        assertThat(g.counted("rejected.not-allowed")).isEqualTo(1);
    }

    @Test
    void aDocumentEvictedFromMemoryIsReadBackFromTheTransport() {
        LiveState state = new LiveState(1, null);                           // room for nothing: every read goes back
        LiveFeed f = new LiveFeed("lean", Set.of("trade"), Map.of("kind", "trade"), new Trades(ordered()), null, LiveFeedTest::parse,
                state, null);
        Map<LivePosition, LiveMessage> log = new LinkedHashMap<>();
        AtomicInteger reads = new AtomicInteger();
        state.reload(e -> f.reread(e, at -> {
            reads.incrementAndGet();
            return Optional.ofNullable(log.get(at));
        }));
        LiveMessage m = msg("MX-1", trade("MX-1", 1, "B1"));
        log.put(m.position(), m);
        f.accept(m);
        assertThat(book(f, "MX-1")).isEqualTo("B1");
        assertThat(reads.get()).isPositive();
    }

    @Test
    void searchFollowsTheStateOnceCaughtUpAndRetentionBoundsIt() {
        Map<String, Object> live = ordered();
        live.put("retention", Map.of("ttl", "1s", "max-rows", 2));
        LiveFeed f = feed(new Trades(live), Map.of("kind", "trade"), null);
        f.accept(msg("MX-1", trade("MX-1", 1, "B1")));
        assertThat(f.search("trade", "MX-1", 5)).isEmpty();                    // built when the replay is over
        f.caughtUp();
        assertThat(f.search("trade", "mx-1", 5)).extracting(h -> h.ref().id()).containsExactly("MX-1");
        f.accept(msg("MX-2", trade("MX-2", 1, "B1")));
        f.accept(msg("MX-3", trade("MX-3", 1, "B1")));
        f.expire(Instant.now());
        assertThat(f.state().size()).isEqualTo(2);                            // max-rows: the oldest left
        f.expire(Instant.now().plus(Duration.ofSeconds(5)));
        assertThat(f.state().size()).isZero();                                // ttl
        assertThat(f.counted("expired")).isEqualTo(3);
    }

    @Test
    void anAcknowledgementRunsOnceTheMessageIsDoneAppliedOrRejected() {
        LiveFeed f = feed(new Trades(ordered()), Map.of("kind", "trade"), null);
        AtomicInteger acks = new AtomicInteger();
        f.accept(new LiveMessage("MX-1", trade("MX-1", 1, "B1"), new LivePosition("q", 0, -1), Instant.now(), acks::incrementAndGet));
        f.accept(new LiveMessage("MX-1", "{oops", new LivePosition("q", 0, -1), Instant.now(), acks::incrementAndGet));
        assertThat(acks.get()).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
