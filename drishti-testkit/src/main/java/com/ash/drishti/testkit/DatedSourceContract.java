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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every dated source must share, whatever it stores data in (Delta Lake, PostgreSQL, Aerospike):
 * each business date reads its own data; a {@code snapshot} kind takes the newest date on or before the one asked
 * (an entity absent from it is gone); an {@code effective} kind carries the last change forward; reverse lookups
 * and search follow the date. A plugin's test extends this class, loads {@link #ROWS} into its store and returns
 * the started plugin (with {@code mode.counterparty = effective}); the tests below are then identical for all.
 */
public abstract class DatedSourceContract {

    /** One stored row: an entity's document for a business date. */
    public record Row(String kind, String id, LocalDate date, String json) {}

    public static final LocalDate D1 = LocalDate.of(2026, 9, 28);
    public static final LocalDate D2 = LocalDate.of(2026, 9, 29);
    public static final LocalDate D3 = LocalDate.of(2026, 9, 30);

    /** The fixture: trades are snapshot data (a full copy each date); counterparties are effective-dated. */
    public static final List<Row> ROWS = List.of(
            new Row("trade", "T-1", D1, "{\"tradeId\":\"T-1\",\"mtm\":100,\"nettingSet\":\"NS-A\"}"),
            new Row("trade", "T-2", D1, "{\"tradeId\":\"T-2\",\"mtm\":-100,\"nettingSet\":\"NS-A\"}"),
            new Row("trade", "T-3", D1, "{\"tradeId\":\"T-3\",\"mtm\":7,\"nettingSet\":\"NS-B\"}"),
            new Row("trade", "T-1", D2, "{\"tradeId\":\"T-1\",\"mtm\":110,\"nettingSet\":\"NS-A\"}"),
            new Row("trade", "T-2", D2, "{\"tradeId\":\"T-2\",\"mtm\":-110,\"nettingSet\":\"NS-A\"}"),
            new Row("trade", "T-1", D3, "{\"tradeId\":\"T-1\",\"mtm\":125,\"nettingSet\":\"NS-A\",\"restated\":true}"),
            new Row("trade", "T-2", D3, "{\"tradeId\":\"T-2\",\"mtm\":-120,\"nettingSet\":\"NS-A\"}"),
            new Row("counterparty", "CP-X", D1, "{\"id\":\"CP-X\",\"rating\":\"A\"}"),
            new Row("counterparty", "CP-X", D3, "{\"id\":\"CP-X\",\"rating\":\"A-\"}"));

    /** The plugin under test, started over a store holding {@link #ROWS}. Called once per test class. */
    protected abstract SourcePlugin plugin() throws Exception;

    /** A context for starting a plugin with {@code settings}. */
    public static SourceContext context(Map<String, String> settings) {
        JsonCodec codec = new JsonCodec();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "contract-scheduler");
            t.setDaemon(true);
            return t;
        });
        return new SourceContext() {
            public Map<String, String> settings() {
                return settings;
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return scheduler;
            }
        };
    }

    private Optional<EntityDocument> at(String kind, String id, LocalDate date) throws Exception {
        return plugin().fetch(EntityRef.of(kind, id), AsOf.of(date));
    }

    @Test
    void isDatedAndServesTheKindsItHolds() throws Exception {
        assertThat(plugin().manifest().capabilities().dated()).isTrue();
        assertThat(plugin().manifest().kinds()).contains("trade", "counterparty");
        assertThat(plugin().health()).startsWith("UP");                // "UP", or "UP (detail)": the server reads it so
    }

    @Test
    void eachBusinessDateReadsItsOwnData() throws Exception {
        EntityDocument d = at("trade", "T-1", D2).orElseThrow();
        assertThat(d.data().get("mtm").asDouble()).isEqualTo(110);
        assertThat(d.provenance().businessDate()).isEqualTo(D2);
        assertThat(at("trade", "T-1", D1).orElseThrow().data().get("mtm").asDouble()).isEqualTo(100);
        assertThat(at("trade", "T-1", D3).orElseThrow().data().get("restated").asBoolean()).isTrue();
    }

    @Test
    void aSnapshotKindTakesTheNewestDateOnOrBeforeTheOneAsked() throws Exception {
        assertThat(at("trade", "T-1", D3.plusDays(3)).orElseThrow().provenance().businessDate()).isEqualTo(D3);
        assertThat(at("trade", "T-3", D1)).isPresent();
        assertThat(at("trade", "T-3", D3)).isEmpty();                 // gone from the latest snapshot
        assertThat(at("trade", "T-1", LocalDate.of(2026, 9, 1))).isEmpty();
        assertThat(at("trade", "NOPE", D3)).isEmpty();
    }

    @Test
    void anEffectiveKindCarriesTheLastChangeForward() throws Exception {
        assertThat(at("counterparty", "CP-X", D2).orElseThrow().data().get("rating").asText()).isEqualTo("A");
        assertThat(at("counterparty", "CP-X", D2).orElseThrow().provenance().businessDate()).isEqualTo(D1);
        assertThat(at("counterparty", "CP-X", D3).orElseThrow().data().get("rating").asText()).isEqualTo("A-");
    }

    @Test
    void reverseLookupFollowsTheDate() throws Exception {
        assertThat(plugin().reverse(EntityRef.of("netting-set", "NS-A"), "trade", AsOf.of(D2)))
                .containsExactly(EntityRef.of("trade", "T-1"), EntityRef.of("trade", "T-2"));
        assertThat(plugin().reverse(EntityRef.of("netting-set", "NS-B"), "trade", AsOf.of(D1))).containsExactly(EntityRef.of("trade", "T-3"));
        assertThat(plugin().reverse(EntityRef.of("netting-set", "NS-B"), "trade", AsOf.of(D3))).isEmpty();
    }

    @Test
    void searchFindsIdentifiers() throws Exception {
        assertThat(plugin().search("trade", "t-", 10)).extracting(h -> h.ref().id()).contains("T-1", "T-2");
        assertThat(plugin().search("counterparty", "cp-x", 10)).extracting(h -> h.ref().id()).containsExactly("CP-X");
    }
}
