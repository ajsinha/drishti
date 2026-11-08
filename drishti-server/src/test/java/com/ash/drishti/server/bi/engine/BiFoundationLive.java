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

import com.ash.drishti.server.bi.Layered;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The live replay of the 2.9.11.e parity proof (docs/architecture/BI_DESIGN.md section 5.1, "Live"): the test lake's live table
 * fed a fixed, deterministic sequence of ticks (updates by key, a new trade, a change applied twice), each applied to the live table directly (as the
 * feed does, without waiting on its thread), and its answers at fixed checkpoints, as risk, a desk-head and a reader without the raw
 * power. Uses only what BI has before the switch ({@link LiveTablesTest.LiveLake}, {@link QueryService}, the live table's {@code apply}), so
 * the recorder runs on the unchanged tree; after the switch the same sequence runs against the layer's live materialisation.
 */
public final class BiFoundationLive {

    private BiFoundationLive() {}

    /** The questions asked at each checkpoint: by desk, and the whole table. */
    static final List<String> QUESTIONS = List.of("{\"measures\":[\"Trades\",\"MTM\",\"Notional\"]}",
            "{\"fields\":[\"trades.desk\"],\"measures\":[\"Trades\",\"MTM\",\"Notional\"]}");

    /**
     * Replays the ticks and returns the answers per checkpoint, person and question, the values normalised by {@code norm}.
     *
     * @param store a temporary folder for the engine
     * @param norm how a row of values is kept (the cases' normalisation)
     */
    public static Map<String, Object> replay(Path store, Function<List<List<Object>>, List<List<Object>>> norm) {
        LiveTablesTest.LiveLake lake = new LiveTablesTest.LiveLake();
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = BiLiveProperties.defaults().fast(Duration.ofMillis(20), Duration.ofHours(1));
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC);
        Map<String, Object> out = new TreeMap<>();
        try (QueryService service = new QueryService(props, live, new DuckDbEngine(props), lake, clock)) {
            String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    mode: live");
            QueryService.Loaded ds = new QueryService.Loaded("file:dataset:live-foundation", Layers.key("file:dataset:live-foundation"),
                    Layered.dataset(yaml).dataset(), "h1");
            Map<String, QueryService.Caller> persons = new TreeMap<>();
            persons.put("risk", caller("rita", true, "", "risk"));
            persons.put("desk-head", caller("hana", true, "DESK-A", "desk-head"));
            persons.put("no-raw-power", caller("nora", false, "", "risk"));
            ask(service, ds, persons.get("risk"));                           // the first question loads the table
            LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
            List<String> ids = lake.docs.keySet().stream().map(EntityRef::id).sorted().toList();
            int tick = 0;
            out.put("checkpoint-0", checkpoint(service, ds, persons, norm));
            for (int i = 0; i < 12; i++) {                                  // updates by key: a fixed MTM per tick
                String id = ids.get(i % ids.size());
                lake.set(id, "mtm", 1000.0 * (i + 1) - 7500.0);
                t.apply(List.of(lake.docs.get(EntityRef.of("trade", id))));
                tick++;
                if (tick % 4 == 0) {
                    out.put("checkpoint-" + tick, checkpoint(service, ds, persons, norm));
                }
            }
            lake.add("T-NEW", "DESK-A", 2500000.0, 4321.0);                // a new trade
            t.apply(List.of(lake.docs.get(EntityRef.of("trade", "T-NEW"))));
            out.put("checkpoint-new", checkpoint(service, ds, persons, norm));
            lake.set(ids.get(0), "notional", 0.0);                           // a value that changes, then the same value again (skipped)
            t.apply(List.of(lake.docs.get(EntityRef.of("trade", ids.get(0)))));
            t.apply(List.of(lake.docs.get(EntityRef.of("trade", ids.get(0)))));
            out.put("checkpoint-repeat", checkpoint(service, ds, persons, norm));
        }
        return out;
    }

    private static QueryService.Caller caller(String user, boolean raw, String desk, String role) {
        com.ash.drishti.server.bi.engine.SqlCompiler.Masks masks = (tb, c) -> !raw && (c.equals("cpty") || tb.equals("trades") && c.equals("mtm"));
        return new QueryService.Caller(user, Set.of(role), true, UserAttributes.of(user, List.of(role), desk, null), masks);
    }

    private static QueryService.Result ask(QueryService service, QueryService.Loaded ds, QueryService.Caller who) {
        try {
            return service.query(ds, QueryRequest.of(new ObjectMapper().readTree(QUESTIONS.get(0))), who, false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Map<String, Object> checkpoint(QueryService service, QueryService.Loaded ds, Map<String, QueryService.Caller> persons,
            Function<List<List<Object>>, List<List<Object>>> norm) {
        Map<String, Object> out = new TreeMap<>();
        ObjectMapper json = new ObjectMapper();
        persons.forEach((name, who) -> {
            Map<String, Object> mine = new TreeMap<>();
            for (int q = 0; q < QUESTIONS.size(); q++) {
                try {
                    QueryService.Result r = service.query(ds, QueryRequest.of(json.readTree(QUESTIONS.get(q))), who, false);
                    List<List<Object>> rows = new ArrayList<>();
                    for (int i = 0; i < r.rows(); i++) {
                        List<Object> row = new ArrayList<>();
                        for (ArrowIpcWriter.Column c : r.columns()) {
                            row.add(c.values()[i]);
                        }
                        rows.add(row);
                    }
                    List<List<Object>> kept = new ArrayList<>(norm.apply(rows));
                    kept.sort(java.util.Comparator.comparing(String::valueOf));
                    Map<String, Object> a = new TreeMap<>();
                    a.put("rows", kept);
                    a.put("layer", r.meta().layer());
                    mine.put("q" + q, a);
                } catch (DrishtiException e) {
                    mine.put("q" + q, Map.of("refused", e.errorCode().code()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            out.put(name, mine);
        });
        return out;
    }
}
