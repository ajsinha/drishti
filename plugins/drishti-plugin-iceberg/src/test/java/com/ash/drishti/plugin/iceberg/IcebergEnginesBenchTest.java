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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The engines' speed table (DRISHTI_ICEBERG.md section 12; ICEBERG_CONNECTOR.md, engines): the same JSON lines (a trade
 * book from {@code tools/samplegen/bulk_trades.py --jsonl}) loaded by each engine's loader into its own lake, then read
 * by the connector on that engine in this JVM. Opt-in, not part of a build: {@code -Ddrishti.iceberg.bench=<jsonl>}
 * (and {@code -Ddrishti.iceberg.bench.dir=<folder for the lakes>}); prints a table and checks the engines agree.
 */
class IcebergEnginesBenchTest {

    static final List<String> COLUMNS = List.of("PRODUCT_TYPE", "PRODUCT_GROUP", "TRADE_STATUS", "BUY_SELL_CODE", "CURRENCY", "QUANTITY",
            "COUNTERPARTY", "LEGAL_ENTITY", "PORTFOLIO", "TRADER_ID", "NOTIONAL", "INDEX_NAME", "MATURITY_DATE", "TRADE_DATE", "SOURCE_SYSTEM");

    record Result(String engine, double loadS, double startMs, double firstMs, double warmMedianMs, double warmP95Ms, double columnsColdMs,
            double columnsAgainMs, double pastDayMs, String answers) {}

    static double ms(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    static Result run(String engine, Path jsonl, Path dir) throws Exception {
        Path root = dir.resolve(engine);
        long t0 = System.nanoTime();
        IcebergLoader.load(new String[] {jsonl.toString(), root.toString(), "--engine", engine, "--spill-dir", dir.resolve("spill-" + engine).toString(),
            "--file-rows", "250000", "--threads", "3"});
        double load = (System.nanoTime() - t0) / 1e9;
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "trading", "engine", engine, "index.days", "1",
                "layout.trade.columns", String.join(",", COLUMNS)));
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        long t1 = System.nanoTime();
        p.start(DatedSourceContract.context(s));
        double start = ms(t1);
        try {
            List<String> ids = new ArrayList<>(p.search("trade", "MX-", 100_000).stream().map(h -> h.ref().id()).toList());
            ids.sort(null);
            long t2 = System.nanoTime();
            var first = p.fetch(EntityRef.of("trade", ids.get(0))).orElseThrow();
            double firstMs = ms(t2);
            Random r = new Random(7);
            double[] warm = new double[300];
            for (int i = 0; i < warm.length; i++) {
                String id = ids.get(r.nextInt(ids.size()));
                p.purgeDocs();
                long t = System.nanoTime();
                assertThat(p.fetch(EntityRef.of("trade", id))).isPresent();
                warm[i] = ms(t);
            }
            Arrays.sort(warm);
            long t3 = System.nanoTime();
            ColumnSet c = p.columns("trade", COLUMNS, AsOf.LATEST).orElseThrow();
            double colsCold = ms(t3);
            long t4 = System.nanoTime();
            assertThat(p.columns("trade", COLUMNS, AsOf.LATEST)).isPresent();
            double colsAgain = ms(t4);
            long t5 = System.nanoTime();
            var past = p.fetch(EntityRef.of("trade", ids.get(ids.size() / 2)), AsOf.of(LocalDate.of(2026, 9, 29)));
            double pastMs = ms(t5);
            double notional = 0;
            for (int i = 0; i < c.size(); i++) {
                Object v = c.value("NOTIONAL", i);
                notional += v == null ? 0 : (Double) v;
            }
            String answers = ids.size() + " ids; first " + first.data().get("TRADE_ID").asText() + "; columns " + c.size() + "; notional "
                    + Math.round(notional) + "; past " + past.map(d -> d.data().get("TRADE_ID").asText()).orElse("-");
            return new Result(engine, load, start, firstMs, warm[warm.length / 2], warm[(int) (warm.length * 0.95)], colsCold, colsAgain, pastMs, answers);
        } finally {
            p.close();
        }
    }

    @Test
    void theEnginesSideBySide() throws Exception {
        String file = System.getProperty("drishti.iceberg.bench");
        assumeTrue(file != null && Files.exists(Path.of(file)), "set -Ddrishti.iceberg.bench=<trades.jsonl> to measure");
        Path dir = Path.of(System.getProperty("drishti.iceberg.bench.dir", Path.of(file).getParent().resolve("lakes").toString()));
        Map<String, Result> out = new LinkedHashMap<>();
        for (String engine : List.of("library", "native")) {
            out.put(engine, run(engine, Path.of(file), dir));
        }
        StringBuilder t = new StringBuilder("\n| | native | library |\n|---|---|---|\n");
        Result n = out.get("native");
        Result l = out.get("library");
        t.append(String.format("| load (3 days) | %.1f s | %.1f s |%n", n.loadS(), l.loadS()));
        t.append(String.format("| connector start (plan, type-ahead) | %.0f ms | %.0f ms |%n", n.startMs(), l.startMs()));
        t.append(String.format("| first trade (cold: id map, footer) | %.0f ms | %.0f ms |%n", n.firstMs(), l.firstMs()));
        t.append(String.format("| one trade, warm median / p95 | %.1f / %.1f ms | %.1f / %.1f ms |%n", n.warmMedianMs(), n.warmP95Ms(), l.warmMedianMs(),
                l.warmP95Ms()));
        t.append(String.format("| a day's 15 columns, cold / again | %.0f / %.1f ms | %.0f / %.1f ms |%n", n.columnsColdMs(), n.columnsAgainMs(),
                l.columnsColdMs(), l.columnsAgainMs()));
        t.append(String.format("| a trade on a past date (cold day) | %.0f ms | %.0f ms |%n", n.pastDayMs(), l.pastDayMs()));
        System.out.println(t);
        System.out.println("native:  " + n.answers());
        System.out.println("library: " + l.answers());
        assertThat(n.answers()).isEqualTo(l.answers());
    }
}
