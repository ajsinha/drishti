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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnScans;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.ArrowScan;
import com.ash.drishti.api.ScanRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small generated lake for the engine's tests: two business days of trades (a dated connector that promotes columns), a desks table (an undated
 * connector), and trades with nested legs for the array table. Sizes are tiny on purpose. Implements {@link SourceGateway} the way the server does,
 * over in-memory data, so the engine runs unchanged.
 */
public class TestLake implements SourceGateway {

    public static final LocalDate DAY1 = LocalDate.of(2026, 10, 8);
    public static final LocalDate DAY2 = LocalDate.of(2026, 10, 9);
    public static final List<String> DESKS = List.of("DESK-A", "DESK-B", "DESK-C");

    public record Trade(String id, String desk, String ccy, String cpty, double notional, double mtm, String tradeDate, List<Map<String, Object>> legs) {}

    public final Map<LocalDate, List<Trade>> trades = new LinkedHashMap<>();
    public final AtomicInteger scans = new AtomicInteger();
    final AtomicInteger recordReads = new AtomicInteger();
    volatile long scanDelayMillis;
    final boolean promoted;
    final LocalDate current = DAY2;

    /** @param promoted false makes the trades connector unable to scan, so the engine reads documents */
    public TestLake(boolean promoted) {
        this(promoted, 40);
    }

    /** @param rowsPerDay trades on each of the two days (the measurements use 10,000, the most a local test may hold) */
    public TestLake(boolean promoted, int rowsPerDay) {
        this.promoted = promoted;
        for (LocalDate day : List.of(DAY1, DAY2)) {
            List<Trade> list = new ArrayList<>();
            for (int i = 0; i < rowsPerDay; i++) {
                int shift = day.equals(DAY2) ? 1 : 0;
                List<Map<String, Object>> legs = List.of(Map.of("leg", 1, "payReceive", "Pay", "notional", 1000.0 * (i + 1), "currency", "USD"),
                        Map.of("leg", 2, "payReceive", "Receive", "notional", 1000.0 * (i + 1), "currency", i % 2 == 0 ? "EUR" : "GBP"));
                list.add(new Trade(String.format("T-%s-%03d", day.getDayOfMonth(), i), DESKS.get(i % 3), i % 2 == 0 ? "USD" : "EUR", "CP-" + (i % 4),
                        100.0 * (i + 1) + shift, ((i % 7) - 3) * 1000.0 + shift, day.toString(), legs));
            }
            trades.put(day, list);
        }
    }

    public static String yaml() {
        return """
                rachana: 1
                dataset: lake-test
                version: 1
                tables:
                  trades:
                    source: { connector: trading-lake, kind: trade }
                    columns:
                      desk: string
                      currency: { path: ccy, type: string }
                      cpty: { path: counterparty.id, type: string }
                      notional: decimal
                      mtm: decimal
                      tradeDate: date
                      mtmK: { type: decimal, expr: "mtm / 1000" }
                  desks:
                    source: { connector: desk-lake, kind: desk }
                    columns:
                      desk: { type: string, key: true }
                      region: string
                relationships:
                  - { from: trades.desk, to: desks.desk, cardinality: many-to-one }
                measures:
                  Notional: { expr: "sum(trades.notional)" }
                  MTM: { expr: "sum(trades.mtm)" }
                  Trades: { expr: "count(trades)" }
                  AvgMTM: { expr: "avg(trades.mtm)" }
                  MTMperM: { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000" }
                  Names: { expr: "distinct(trades.cpty)" }
                  P50: { expr: "percentile(trades.mtm, 0.5)" }
                  EurMTM: { expr: "sum(trades.mtm, where=trades.currency == 'EUR')" }
                  Biggest: { expr: "max(trades.notional)" }
                  Share: { expr: "measure('MTM') / measure('Notional')" }
                  NotionalM: { expr: "round(sum(trades.notional) / 1_000_000, 1)" }
                security:
                  rows:
                    - { role: desk-head, where: "trades.desk in user.desks" }
                    - { role: risk, where: "true" }
                    - { role: auditor, where: "trades.desk == 'DESK-B' or trades.desk == 'DESK-C'" }
                  masked: [cpty, trades.mtm]
                rollups:
                  - { name: byDesk, table: trades, by: [desk, currency], measures: [Notional, MTM, Trades, AvgMTM, MTMperM, Biggest, Share] }
                """;
    }

    // ---- the connectors ----------------------------------------------------------------------------------------------

    private abstract static class Base implements SourcePlugin {
        @Override
        public void start(SourceContext context) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }
    }

    final SourcePlugin tradingLake = new Base() {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("trading-lake", "1", Set.of("trade"), new SourceCapabilities(false, false, false, true));
        }

        @Override
        public Set<String> columnar(String kind) {
            return promoted ? Set.of("desk", "ccy", "counterparty.id", "notional", "mtm", "tradeDate") : Set.of();
        }

        @Override
        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            LocalDate d = asOf.businessDate() == null ? current : asOf.businessDate();
            List<Trade> list = trades.get(d);
            if (list == null || !promoted) {
                return Optional.empty();
            }
            scans.incrementAndGet();
            if (scanDelayMillis > 0) {
                try {
                    Thread.sleep(scanDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int n = list.size();
            String[] ids = list.stream().map(Trade::id).toArray(String[]::new);
            Map<String, double[]> nums = new LinkedHashMap<>();
            Map<String, String[]> texts = new LinkedHashMap<>();
            nums.put("notional", list.stream().mapToDouble(Trade::notional).toArray());
            nums.put("mtm", list.stream().mapToDouble(Trade::mtm).toArray());
            texts.put("desk", list.stream().map(Trade::desk).toArray(String[]::new));
            texts.put("ccy", list.stream().map(Trade::ccy).toArray(String[]::new));
            texts.put("counterparty.id", list.stream().map(Trade::cpty).toArray(String[]::new));
            texts.put("tradeDate", list.stream().map(Trade::tradeDate).toArray(String[]::new));
            return Optional.of(new ColumnSet(ids, nums, texts, d));
        }

        @Override
        public Optional<ArrowScan> scan(ScanRequest request) {
            return ColumnScans.over(this, request);
        }
    };

    final SourcePlugin deskLake = new Base() {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("desk-lake", "1", Set.of("desk"), new SourceCapabilities(false, false, false, false));
        }

        @Override
        public Set<String> columnar(String kind) {
            return Set.of("desk", "region");
        }

        @Override
        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            return Optional.of(new ColumnSet(DESKS.toArray(String[]::new), Map.of(),
                    Map.of("desk", DESKS.toArray(String[]::new), "region", new String[] {"EMEA", "AMER", "AMER"}), null));
        }

        @Override
        public Optional<ArrowScan> scan(ScanRequest request) {
            return ColumnScans.over(this, request);
        }
    };

    /** A second fact table, undated: seven limits over the three desks (four, two and one), so a join to the trades would repeat rows. */
    public static final String[] LIMIT_DESKS = {"DESK-A", "DESK-A", "DESK-A", "DESK-A", "DESK-B", "DESK-B", "DESK-C"};
    public static final double[] LIMIT_AMOUNTS = {10, 20, 30, 40, 5, 6, 7};

    final SourcePlugin limitLake = new Base() {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("limit-lake", "1", Set.of("limit"), new SourceCapabilities(false, false, false, false));
        }

        @Override
        public Set<String> columnar(String kind) {
            return Set.of("desk", "amount");
        }

        @Override
        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            return Optional.of(new ColumnSet(new String[] {"L1", "L2", "L3", "L4", "L5", "L6", "L7"}, Map.of("amount", LIMIT_AMOUNTS), Map.of("desk", LIMIT_DESKS), null));
        }

        @Override
        public Optional<ArrowScan> scan(ScanRequest request) {
            return ColumnScans.over(this, request);
        }
    };

    // ---- the gateway -------------------------------------------------------------------------------------------------

    @Override
    public Optional<SourcePlugin> plugin(String connector, String kind) {
        return switch (connector.isBlank() ? kind : connector) {
            case "trading-lake", "trade" -> Optional.of(tradingLake);
            case "desk-lake", "desk" -> Optional.of(deskLake);
            case "limit-lake", "limit" -> Optional.of(limitLake);
            default -> Optional.empty();
        };
    }

    @Override
    public List<EntityRef> ids(String kind, LocalDate date, int limit) {
        return trades.getOrDefault(date == null ? current : date, List.of()).stream().limit(limit).map(t -> EntityRef.of(kind, t.id())).toList();
    }

    @Override
    public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date) {
        Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
        Map<String, Trade> byId = new LinkedHashMap<>();
        trades.getOrDefault(date == null ? current : date, List.of()).forEach(t -> byId.put(t.id(), t));
        for (EntityRef r : refs) {
            Trade t = byId.get(r.id());
            if (t != null) {
                recordReads.incrementAndGet();
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("tradeId", t.id());
                doc.put("desk", t.desk());
                doc.put("ccy", t.ccy());
                doc.put("counterparty", Map.of("id", t.cpty()));
                doc.put("notional", t.notional());
                doc.put("mtm", t.mtm());
                doc.put("tradeDate", t.tradeDate());
                doc.put("legs", t.legs());
                out.put(r, new EntityDocument(r, DataNode.of(doc), new Provenance("test", 1, java.time.Instant.now(), false)));
            }
        }
        return out;
    }

    @Override
    public LocalDate currentDate() {
        return current;
    }
}
