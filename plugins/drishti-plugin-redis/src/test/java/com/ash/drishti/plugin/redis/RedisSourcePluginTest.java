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
package com.ash.drishti.plugin.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Redis connector against Redis 8 in Docker, loaded with the contract's rows by {@link RedisLoader}: the same tests
 * as the Delta Lake, PostgreSQL and Aerospike connectors, plus the column hash, merging loads and live pushes. Skipped
 * where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisSourcePluginTest extends DatedSourceContract {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2").withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1).withStartupTimeout(Duration.ofMinutes(1)));

    private static RedisSourcePlugin plugin;
    private static final JsonCodec CODEC = new JsonCodec();

    private static String uri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    /** The rows as the loader's JSON lines, trades with their two promoted fields. */
    private static String lines(List<Row> rows) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Row r : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("domain", "desk");
            line.put("kind", r.kind());
            line.put("id", r.id());
            line.put("date", r.date().toString());
            line.put("doc", r.json());
            if (r.kind().equals("trade")) {                            // the layout below promotes these two
                var doc = CODEC.read(r.json());
                Map<String, Object> columns = new LinkedHashMap<>();
                columns.put("mtm", doc.get("mtm").isNull() ? null : doc.get("mtm").asDouble());
                columns.put("nettingSet", doc.get("nettingSet").isNull() ? null : doc.get("nettingSet").asText());
                line.put("columns", columns);
            }
            out.append(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(line)).append('\n');
        }
        return out.toString();
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        loadInto("desk", ROWS);
        RedisSourcePlugin p = new RedisSourcePlugin();
        p.start(context(Map.of("uri", uri(), "domain", "desk", "mode.counterparty", "effective", "source-name", "desk-redis",
                "layout.trade.columns", "mtm,nettingSet", "refresh-seconds", "3600")));
        plugin = p;
        return p;
    }

    @Test
    void aDaysColumnHashAnswersSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
        assertThat(c.ids()).containsExactly("T-1", "T-2");
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            assertThat(c.value("nettingSet", i)).isEqualTo(doc.get("nettingSet").asText());
        }
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1)).orElseThrow().size()).isEqualTo(3);
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1.minusDays(30)))).isEmpty();   // not held: the next store answers
        assertThat(p.reverse(EntityRef.of("netting-set", "NS-A"), "trade", AsOf.LATEST)).isNotEmpty()
                .allSatisfy(r -> assertThat(r.kind()).isEqualTo("trade"));
        assertThat(p.cacheStats()).containsEntry("kinds", 2).containsEntry("ids", 3);
    }

    @Test
    void reverseLookupsWithoutPromotedFieldsReadTheDaysDocuments() throws Exception {
        plugin();
        RedisSourcePlugin bare = new RedisSourcePlugin();
        bare.start(context(Map.of("uri", uri(), "domain", "desk", "mode.counterparty", "effective", "refresh-seconds", "3600")));
        try {
            assertThat(bare.columnar("trade")).isEmpty();
            assertThat(bare.reverse(EntityRef.of("netting-set", "NS-A"), "trade", AsOf.of(D2)))
                    .containsExactly(EntityRef.of("trade", "T-1"), EntityRef.of("trade", "T-2"));
            assertThat(bare.reverse(EntityRef.of("netting-set", "NS-B"), "trade", AsOf.of(D3))).isEmpty();
        } finally {
            bare.close();
        }
    }

    @Test
    void aLaterLoadOfPartOfADayMergesIntoItsColumnsAndIsPushedToOpenViews() throws Exception {
        LocalDateRows rows = new LocalDateRows();
        rows.add("trade", "X-1", "{\"tradeId\":\"X-1\",\"mtm\":1,\"nettingSet\":\"NS-X\"}");
        rows.add("trade", "X-2", "{\"tradeId\":\"X-2\",\"mtm\":2,\"nettingSet\":\"NS-X\"}");
        RedisSourcePlugin p = new RedisSourcePlugin();
        p.start(context(Map.of("uri", uri(), "domain", "merge", "layout.trade.columns", "mtm,nettingSet", "refresh-seconds", "3600")));
        try {
            loadInto("merge", rows.rows);
            p.refresh();
            assertThat(p.columns("trade", List.of("mtm"), AsOf.LATEST).orElseThrow().ids()).containsExactly("X-1", "X-2");
            List<EntityDocument> pushed = new CopyOnWriteArrayList<>();
            Subscription s = p.subscribe(EntityRef.of("trade", "X-2"), pushed::add);
            LocalDateRows update = new LocalDateRows();
            update.add("trade", "X-2", "{\"tradeId\":\"X-2\",\"mtm\":20,\"nettingSet\":\"NS-X\"}");
            update.add("trade", "X-3", "{\"tradeId\":\"X-3\",\"mtm\":3,\"nettingSet\":\"NS-Y\"}");
            loadInto("merge", update.rows, "--publish");
            ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
            assertThat(c.ids()).containsExactly("X-1", "X-2", "X-3");                // X-1 kept from the first load
            assertThat(c.value("mtm", 1)).isEqualTo(20.0);
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (pushed.isEmpty() && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(pushed).isNotEmpty();
            assertThat(pushed.get(pushed.size() - 1).data().get("mtm").asDouble()).isEqualTo(20.0);
            assertThat(pushed.get(0).provenance().live()).isTrue();
            assertThat(p.lastUpdate()).isNotNull();
            s.close();
            until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (p.search("trade", "x-3", 5).isEmpty() && System.nanoTime() < until) {
                Thread.sleep(50);                                  // the day's announcement refreshes the ids
            }
            assertThat(p.search("trade", "x-", 5)).extracting(h -> h.ref().id()).containsExactly("X-1", "X-2", "X-3");
        } finally {
            p.close();
        }
    }

    /** The rows of one test domain, all on D3. */
    private static final class LocalDateRows {
        final List<Row> rows = new ArrayList<>();

        void add(String kind, String id, String json) {
            rows.add(new Row(kind, id, D3, json));
        }
    }

    private static void loadInto(String domain, List<Row> rows, String... flags) throws Exception {
        String[] args = new String[flags.length + 2];
        args[0] = "-";
        args[1] = uri();
        System.arraycopy(flags, 0, args, 2, flags.length);
        RedisLoader.Options o = RedisLoader.Options.parse(args);
        try (RedisConnection c = RedisConnection.open(o.uri(), false, null, null, Duration.ofSeconds(10))) {
            new RedisLoader(o, c).load(new BufferedReader(new StringReader(lines(rows).replace("\"domain\":\"desk\"", "\"domain\":\"" + domain + "\""))));
        }
    }

    @Test
    void withoutAUriThePluginStaysIdle() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RedisSourcePlugin().start(context(Map.of())))
                .isInstanceOf(com.ash.drishti.api.PluginNotConfigured.class);
    }

    @Test
    void documentsAreStoredCompressedAndReadBackWithEveryCodec() {
        byte[] json = ROWS.get(0).json().getBytes(StandardCharsets.UTF_8);
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            samples.add(("{\"tradeId\":\"MX-" + (30_000_000 + i) + "\",\"productType\":\"IRS_FIXFLOAT\",\"book\":\"BOOK-RATES-" + i % 7
                    + "\",\"notional\":" + i * 1000 + ",\"currency\":\"USD\"}").getBytes(StandardCharsets.UTF_8));
        }
        byte[] dict = DocCodec.train(samples, 4096);
        assertThat(dict).isNotNull();
        DocCodec.Dictionary d = DocCodec.Dictionary.of(dict, 3);
        for (DocCodec.Kind k : DocCodec.Kind.values()) {
            DocCodec codec = new DocCodec(k, 3, d);
            byte[] stored = codec.encode(samples.get(7));
            assertThat(DocCodec.decode(stored, id -> id == d.id() ? d : null)).isEqualTo(samples.get(7));
            assertThat(DocCodec.decode(codec.encode(json), id -> d)).isEqualTo(json);
        }
        assertThat(new DocCodec(DocCodec.Kind.ZSTD_DICT, 3, d).encode(samples.get(7)).length).isLessThan(samples.get(7).length / 2);
        assertThat(DocCodec.Kind.of("zstd-dict")).isEqualTo(DocCodec.Kind.ZSTD_DICT);
    }

    @Test
    void columnChunksRoundTripNumbersTextsAndNulls() {
        double[] numbers = {1.5, Double.NaN, -2e9, 0, 42};
        String[] repeated = new String[100];
        String[] unique = new String[100];
        for (int i = 0; i < 100; i++) {
            repeated[i] = i % 10 == 0 ? null : "BOOK-" + i % 3;
            unique[i] = i == 5 ? null : "MX-" + i;
        }
        double[] n = new double[5];
        ColumnCodec.readNumbers(ColumnCodec.numbers(numbers, 0, 5), n, 0);
        assertThat(n).containsExactly(numbers);
        for (String[] texts : List.of(repeated, unique)) {
            String[] t = new String[100];
            ColumnCodec.readTexts(ColumnCodec.texts(texts, 0, 100), t, 0, ColumnCodec.pool());
            assertThat(t).containsExactly(texts);
        }
        String[] asText = new String[5];
        ColumnCodec.readNumbersAsTexts(ColumnCodec.numbers(numbers, 0, 5), asText, 0);
        assertThat(asText).containsExactly("1.5", null, "-2000000000", "0", "42");
        var meta = new ColumnCodec.Meta(7, 100, 10, 10, Map.of("mtm", true));
        assertThat(ColumnCodec.Meta.decode(meta.encode())).isEqualTo(meta);
        assertThat(RedisLayout.doc("trading", "trade", "MX-1", D3)).isEqualTo("trading:trade:{MX-1}:20260930");
        assertThat(RedisLayout.columns("trading", "trade", D3)).isEqualTo("{trading:trade:20260930}:cols");
        assertThat(RedisConnection.describe("rediss://app:secret@redis.internal:6380/0")).isEqualTo("rediss://redis.internal:6380/0");
    }
}
