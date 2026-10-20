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
package com.ash.drishti.plugin.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.LoadGuard;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The measurements of docs/connectors/CASSANDRA_CONNECTOR.md section 9, at the connector (no HTTP): a generated trade book
 * ({@code bulk_trades.py --jsonl}) loaded into the shared Cassandra 5 node, then each read path timed, medians of 25. Runs
 * only when asked: {@code -Ddrishti.cassandra.bench=<the JSON lines file>} (keep it at 10,000 trades a day locally).
 */
@EnabledIfSystemProperty(named = "drishti.cassandra.bench", matches = ".+")
class CassandraScaleBench {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static double median(int runs, Callable<?> what) throws Exception {
        double[] ms = new double[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            what.call();
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(ms);
        return ms[runs / 2];
    }

    private static double once(Callable<?> what) throws Exception {
        long t0 = System.nanoTime();
        what.call();
        return (System.nanoTime() - t0) / 1e6;
    }

    @Test
    void measure() throws Exception {
        Path file = Path.of(System.getProperty("drishti.cassandra.bench"));
        // the promoted paths and a few ids, from the file itself
        List<String> paths = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Map<String, Long> perDay = new HashMap<>();
        String portfolio = null;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode n = JSON.readTree(line);
                perDay.merge(n.get("date").asText(), 1L, Long::sum);
                n.get("columns").fieldNames().forEachRemaining(f -> {
                    if (!paths.contains(f)) {
                        paths.add(f);
                    }
                });
                if (ids.size() < 25) {
                    ids.add(n.get("id").asText());
                }
                if (portfolio == null && n.get("columns").has("PORTFOLIO")) {
                    portfolio = n.get("columns").get("PORTFOLIO").asText();
                }
            }
        }
        List<LocalDate> days = perDay.keySet().stream().map(LocalDate::parse).sorted().toList();
        String node = CassandraNode.cassandra();
        String[] args = CassandraNode.args(node, "bench_trading", "--buckets", "128", "--in-flight", "256");
        long t0 = System.nanoTime();
        CassandraLoader.Result r;
        try (CassandraLoader loader = new CassandraLoader(CassandraLoader.parse(args), LoadGuard.fromArgs(args));
             BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            r = loader.load(in);
        }
        double loadS = (System.nanoTime() - t0) / 1e9;
        Map<String, String> settings = new HashMap<>(CassandraNode.settings(node, "bench_trading", "bench-cassandra"));
        settings.remove("mode.counterparty");
        settings.put("layout.trade.columns", String.join(",", paths));
        Map<String, Double> m = new java.util.LinkedHashMap<>();
        m.put("load " + r.rows() + " trade-days (s)", loadS);
        try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
            m.put("start, catalogue and ids ready (ms)", once(() -> {
                p.start(DatedSourceContract.context(settings));
                return null;
            }));
            List<String> cols = new ArrayList<>(p.columnar("trade"));
            // a path no row carries a value for (always null in the book) has no column: searches on it read documents
            assertThat(paths).containsAll(cols);
            assertThat(cols).hasSizeGreaterThan(paths.size() / 2);
            String id = ids.get(0);
            LocalDate newest = days.get(days.size() - 1);
            LocalDate past = days.get(0);
            String prefix = id.substring(0, Math.min(id.length(), 7)).toLowerCase(Locale.ROOT);
            m.put("type-ahead '" + prefix + "' (ms)", median(25, () -> p.search("trade", prefix, 10)));
            m.put("a trade's document, first read (ms)", median(25, () -> {
                p.dropCaches();
                return p.fetch(EntityRef.of("trade", id), AsOf.of(newest));
            }));
            m.put("a trade's document, again (cached) (ms)", median(25, () -> p.fetch(EntityRef.of("trade", id), AsOf.of(newest))));
            m.put("a trade's document, a past day (ms)", median(25, () -> {
                p.dropCaches();
                return p.fetch(EntityRef.of("trade", ids.get(3)), AsOf.of(past));
            }));
            m.put("a day's " + cols.size() + " promoted columns, first read (ms)", median(5, () -> {
                p.dropCaches();
                return p.columns("trade", cols, AsOf.of(newest));
            }));
            ColumnSet c = p.columns("trade", cols, AsOf.of(newest)).orElseThrow();
            assertThat((long) c.size()).isEqualTo(perDay.get(newest.toString()));
            m.put("a day's columns, again (cached) (ms)", median(25, () -> p.columns("trade", cols, AsOf.of(newest))));
            String book = portfolio;
            m.put("reverse lookup of a portfolio (promoted, cached) (ms)", median(25, () -> p.reverse(EntityRef.of("book", book), "trade", AsOf.of(newest))));
            m.put("a day's documents, one pass (ms)", median(5, () -> p.documents("trade", AsOf.of(newest), d -> { })));
            m.put("inventory (ms)", median(25, () -> p.inventory("trade", 10)));
        }
        StringBuilder out = new StringBuilder("\nCassandra connector, " + perDay.size() + " days, " + r.rows() + " rows, " + paths.size() + " promoted columns\n");
        m.forEach((k, v) -> out.append(String.format(Locale.ROOT, "| %s | %.1f |%n", k, v)));
        System.out.println(out);
        Files.writeString(file.resolveSibling("bench-result.md"), out.toString());
    }
}
