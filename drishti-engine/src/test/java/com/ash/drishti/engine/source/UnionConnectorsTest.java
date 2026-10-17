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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.DateCoverage;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Connectors with {@code union: true} serve one kind together, one source system each (trade_endur, trade_calypso, trade_murex): a
 * read finds an entity in whichever holds it, a search and a type-ahead list every system, columns are put together, and a store
 * behind the union (which holds the date) is still not asked (DATA-12).
 */
class UnionConnectorsTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    /** One source system's table: the ids of its {@code ids} setting, holding every date, with SOURCE_SYSTEM as a column. */
    public static final class Slice implements SourcePlugin {
        private String name = "slice";
        private List<String> ids = List.of();
        private boolean numericIds;

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("slice", "t", Set.of("trade"), new SourceCapabilities(false, false, true, true));
        }

        @Override
        public void start(SourceContext c) {
            name = c.setting("source-name", "slice");
            ids = Arrays.stream(c.setting("ids", "").split(",")).filter(s -> !s.isBlank()).toList();
            numericIds = Boolean.parseBoolean(c.setting("numeric", "false"));
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.of(DAY));
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            return ids.contains(ref.id())
                    ? Optional.of(new EntityDocument(ref, DataNode.of(Map.of("SOURCE_SYSTEM", name)), new Provenance(name, 1, Instant.now(), false, DAY)))
                    : Optional.empty();
        }

        @Override
        public DateCoverage coverage(String kind, AsOf asOf) {
            return DateCoverage.HELD;
        }

        @Override
        public List<EntityHit> search(String kind, String text, int limit) {
            return ids.stream().filter(i -> i.contains(text)).limit(limit).map(i -> new EntityHit(EntityRef.of("trade", i), i, name)).toList();
        }

        @Override
        public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
            return search(kind, text, limit);
        }

        @Override
        public Set<String> columnar(String kind) {
            return Set.of("TRADE_ID", "SOURCE_SYSTEM");
        }

        @Override
        public Optional<ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) {
            String[] id = ids.toArray(String[]::new);
            String[] sys = new String[id.length];
            Arrays.fill(sys, name);
            Map<String, double[]> n = new LinkedHashMap<>();
            Map<String, String[]> t = new LinkedHashMap<>();
            if (numericIds) {
                n.put("TRADE_ID", Arrays.stream(id).mapToDouble(Double::parseDouble).toArray());
            } else {
                t.put("TRADE_ID", id);
            }
            t.put("SOURCE_SYSTEM", sys);
            return Optional.of(new ColumnSet(id, n, t, DAY));
        }
    }

    private static SourcesProperties.ConnectorSettings slice(String ids, boolean union, boolean numeric) {
        return new SourcesProperties.ConnectorSettings("slice", true, List.of("trade"),
                Map.of("ids", ids, "union", String.valueOf(union), "numeric", String.valueOf(numeric)));
    }

    private static SourceRouter router(Map<String, SourcesProperties.ConnectorSettings> connectors) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, connectors);
        return new SourceRouter(new SourceRegistry(List.of(new Slice()), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static Map<String, SourcesProperties.ConnectorSettings> threeSystems() {
        var cfg = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        cfg.put("ENDUR", slice("1000001,1000002", true, true));
        cfg.put("CALYPSO", slice("CLY-3000001,CLY-3000002", true, false));
        cfg.put("MUREX", slice("MX-20000001", true, false));
        cfg.put("behind", slice("OLD-1", false, false));               // a store of another kind of data, behind the union
        return cfg;
    }

    @Test
    void aReadFindsTheEntityInWhicheverSourceSystemHoldsIt() {
        SourceRouter r = router(threeSystems());
        AsOf day = AsOf.of(DAY);
        assertThat(r.fetch(EntityRef.of("trade", "1000002"), Duration.ofSeconds(2), day).join().data().get("SOURCE_SYSTEM").asText()).isEqualTo("ENDUR");
        assertThat(r.fetch(EntityRef.of("trade", "CLY-3000001"), Duration.ofSeconds(2), day).join().data().get("SOURCE_SYSTEM").asText()).isEqualTo("CALYPSO");
        assertThat(r.fetch(EntityRef.of("trade", "MX-20000001"), Duration.ofSeconds(2), day).join().data().get("SOURCE_SYSTEM").asText()).isEqualTo("MUREX");
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "OLD-1"), Duration.ofSeconds(2), day).join())
                .cause().isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND))
                .hasMessageContaining("ENDUR, CALYPSO, MUREX hold trade");
    }

    @Test
    void withoutUnionTheFirstStoreThatHoldsTheDateAnswersAlone() {
        var cfg = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        cfg.put("ENDUR", slice("1000001", false, true));
        cfg.put("CALYPSO", slice("CLY-3000001", false, false));
        SourceRouter r = router(cfg);
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "CLY-3000001"), Duration.ofSeconds(2), AsOf.of(DAY)).join())
                .cause().hasMessageContaining("ENDUR holds trade");
        assertThat(r.list("trade", "", 10, Duration.ofSeconds(2), AsOf.of(DAY), new SourceFailures()).hits()).extracting(h -> h.ref().id())
                .containsExactly("1000001");
    }

    @Test
    void aSearchListsEverySourceSystemInTurnAndNotTheStoreBehind() {
        SourceRouter r = router(threeSystems());
        var hits = r.list("trade", "", 10, Duration.ofSeconds(2), AsOf.of(DAY), new SourceFailures()).hits();
        assertThat(hits).extracting(h -> h.ref().id()).containsExactly("1000001", "CLY-3000001", "MX-20000001", "1000002", "CLY-3000002");
        var three = r.list("trade", "", 3, Duration.ofSeconds(2), AsOf.of(DAY), new SourceFailures()).hits();   // a short type-ahead shows each system
        assertThat(three).extracting(EntityHit::subtitle).containsExactly("ENDUR", "CALYPSO", "MUREX");
        assertThat(r.unionOf("trade")).extracting(p -> p.manifest().name()).containsExactly("ENDUR", "CALYPSO", "MUREX");
    }

    @Test
    void columnsOfTheUnionArePutTogetherWithIdsNumericInOneSystemAndTextInAnother() {
        SourceRouter r = router(threeSystems());
        ColumnSet c = r.columns("trade", List.of("TRADE_ID", "SOURCE_SYSTEM"), AsOf.of(DAY), Duration.ofSeconds(2)).orElseThrow();
        assertThat(c.ids()).containsExactly("1000001", "1000002", "CLY-3000001", "CLY-3000002", "MX-20000001");
        assertThat(c.texts().get("SOURCE_SYSTEM")).containsExactly("ENDUR", "ENDUR", "CALYPSO", "CALYPSO", "MUREX");
        assertThat(c.mixed()).containsKey("TRADE_ID");
        assertThat(c.value("TRADE_ID", 0)).isEqualTo(1000001.0);
        assertThat(c.value("TRADE_ID", 4)).isEqualTo("MX-20000001");
        assertThat(c.businessDate()).isEqualTo(DAY);
    }
}
