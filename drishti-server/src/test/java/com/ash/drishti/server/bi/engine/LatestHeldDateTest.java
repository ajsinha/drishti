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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "The latest" of a fact table, joined to a dimension, when the source holds an earlier day than the current business date (2.6.7: a
 * report's trades table built for today, empty, while its dimensions read the latest held date). The fact is read on the latest date
 * the connector holds; an empty build for today is never made valid nor remembered; and a named connector whose kinds leave the table's
 * kind out (a connector file generated from an older pack template) is said, not read as an empty table.
 */
class LatestHeldDateTest {

    @TempDir Path store;

    private static final String MQ = "{\"fields\": [\"desks.region\"], \"measures\": [\"Trades\"]}";

    /** The documents connector of the trades, holding what the lake holds, its kinds as given (a named connector's file). */
    private static SourcePlugin connector(TestLake lake, Set<String> kinds) {
        return new SourcePlugin() {
            @Override
            public PluginManifest manifest() {
                return new PluginManifest("trading-lake", "1", kinds, new SourceCapabilities(false, false, false, true));
            }

            @Override
            public void start(SourceContext context) {}

            @Override
            public Optional<EntityDocument> fetch(EntityRef ref) {
                return Optional.empty();
            }

            @Override
            public Optional<java.util.NavigableSet<LocalDate>> heldDates(String kind) {
                return manifest().serves(kind) ? Optional.of(new java.util.TreeSet<>(lake.trades.keySet())) : Optional.empty();
            }

            @Override
            public Map<LocalDate, Long> inventory(String kind, int maxDates) {
                Map<LocalDate, Long> out = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
                if (manifest().serves(kind)) {
                    lake.trades.forEach((d, l) -> out.put(d, (long) l.size()));
                }
                return out;
            }
        };
    }

    private static TestLake lake(Set<String> kinds) {
        return new TestLake(false) {
            final SourcePlugin trades = connector(this, kinds);

            @Override
            public Optional<SourcePlugin> plugin(String connector, String kind) {
                return "trading-lake".equals(connector) ? Optional.of(trades) : super.plugin(connector, kind);
            }
        };
    }

    private static QueryService.Loaded dataset() {
        DatasetParser.Result r = DatasetParser.parse(TestLake.yaml());
        return new QueryService.Loaded("file:dataset:held", Layers.key("file:dataset:held"), r.dataset(), "h1");
    }

    private static final QueryService.Caller WHO = new QueryService.Caller("u", Set.of("risk"), true, UserAttributes.of("u", List.of("risk"), null, null), (t, c) -> false);

    private static long total(QueryService service, QueryService.Loaded ds) throws Exception {
        return total(service, ds, MQ);
    }

    private static long total(QueryService service, QueryService.Loaded ds, String mq) throws Exception {
        var answer = service.query(ds, QueryRequest.of(new ObjectMapper().readTree(mq)), WHO, true);
        long n = 0;
        for (Object v : answer.columns().get(1).values()) {
            n += v == null ? 0 : ((Number) v).longValue();
        }
        return n;
    }

    private Path partition(QueryService.Loaded ds, LocalDate d) {
        return store.resolve("columns").resolve(ds.key()).resolve("trades").resolve("date=" + d);
    }

    @Test
    void theFactReadsTheLatestHeldDateNotAnEmptyToday() throws Exception {
        TestLake lake = lake(Set.of("trade"));
        lake.trades.remove(TestLake.DAY2);                     // today (DAY2) holds nothing yet; the lake's latest is DAY1
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC())) {
            var ds = dataset();
            assertThat(total(service, ds)).isEqualTo(lake.trades.get(TestLake.DAY1).size());
            assertThat(partition(ds, TestLake.DAY2).resolve("meta.json")).doesNotExist();
        }
    }

    @Test
    void asOfALaterDayEachTableReadsItsLatestDateOnOrBeforeIt() throws Exception {
        TestLake lake = lake(Set.of("trade"));
        lake.trades.remove(TestLake.DAY2);                     // asked as of DAY2 (a holiday, or before the load): the trades hold DAY1
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC())) {
            var ds = dataset();
            String asOf = "{\"fields\": [\"desks.region\"], \"measures\": [\"Trades\"], \"dates\": {\"from\": \"" + TestLake.DAY2 + "\"}}";
            assertThat(total(service, ds, asOf)).isEqualTo(lake.trades.get(TestLake.DAY1).size());
            var answer = service.query(ds, QueryRequest.of(new ObjectMapper().readTree(asOf)), WHO, true);
            assertThat(answer.meta().dates()).contains(TestLake.DAY1.toString());
            assertThat(partition(ds, TestLake.DAY2).resolve("meta.json")).doesNotExist();
            String later = asOf.replace(TestLake.DAY2.toString(), TestLake.DAY2.plusDays(3).toString());   // a weekend later: still DAY1
            assertThat(total(service, ds, later)).isEqualTo(lake.trades.get(TestLake.DAY1).size());
            String earlier = asOf.replace(TestLake.DAY2.toString(), TestLake.DAY1.minusDays(1).toString());  // before anything held: nothing
            assertThat(total(service, ds, earlier)).isZero();
        }
    }

    @Test
    void anEmptyLatestIsNeverCachedAsTheAnswer() throws Exception {
        TestLake lake = new TestLake(true);
        var day2 = lake.trades.remove(TestLake.DAY2);           // the source answers 'the latest' with nothing: built for today, empty
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC())) {
            var ds = dataset();
            assertThat(total(service, ds)).isZero();
            assertThat(partition(ds, TestLake.DAY2).resolve("meta.json")).as("an empty latest is not a valid partition").doesNotExist();
            lake.trades.put(TestLake.DAY2, day2);              // the day's data lands: the next read sees it, not a remembered empty table
            assertThat(total(service, ds)).isEqualTo(day2.size());
            assertThat(partition(ds, TestLake.DAY2).resolve("meta.json")).exists();
        }
    }

    @Test
    void aNamedConnectorThatDoesNotServeTheKindIsSaidNotReadAsEmpty() throws Exception {
        TestLake lake = lake(Set.of("var", "pnl-explain"));     // a connector file from an older template: the kind is missing
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC())) {
            var ds = dataset();
            assertThatThrownBy(() -> total(service, ds)).isInstanceOf(DrishtiException.class)
                    .hasMessageContaining("connector 'trading-lake' does not serve kind 'trade'").hasMessageContaining("config/connectors/trading-lake.yaml");
            assertThat(partition(ds, TestLake.DAY2)).as("nothing built for today").satisfiesAnyOf(
                    p -> assertThat(p).doesNotExist(), p -> assertThat(p.resolve("meta.json")).doesNotExist());
            assertThat(Files.exists(partition(ds, TestLake.DAY1))).isFalse();
        }
    }
}
