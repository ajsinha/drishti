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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The loader's schema handling, retention and TTL against Cassandra 5 in Docker. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class CassandraLoaderTest {

    private static String[] without(String[] args, String... drop) {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            if (!List.of(drop).contains(a)) {
                out.add(a);
            }
        }
        return out.toArray(new String[0]);
    }

    @Test
    void withoutCreateTheLoaderPrintsTheCqlAndRefuses() throws Exception {
        String node = CassandraNode.cassandra();
        String[] noKeyspace = without(CassandraNode.args(node, "ld_none"), "--create", "--create-keyspace", "1");
        assertThatThrownBy(() -> CassandraNode.load(noKeyspace, CassandraNode.contractLines("desk")))
                .hasMessageContaining("keyspace ld_none does not exist").hasMessageContaining("--create-keyspace");
        try (CqlSession s = CassandraNode.session(node)) {
            s.execute(CassandraLayout.createKeyspace("ld_admin", 1));
        }
        String[] noCreate = without(CassandraNode.args(node, "ld_admin"), "--create", "--create-keyspace", "1");
        assertThatThrownBy(() -> CassandraNode.load(noCreate, CassandraNode.contractLines("desk"))).hasMessageContaining("run the CQL above");
        try (CqlSession s = CassandraNode.session(node)) {
            assertThat(s.getMetadata().getKeyspace("ld_admin").orElseThrow().getTables()).isEmpty();      // nothing was created
        }
    }

    @Test
    void anAddedPromotedFieldBecomesAColumn() throws Exception {
        String node = CassandraNode.cassandra();
        CassandraNode.load(CassandraNode.args(node, "ld_alter"), CassandraNode.contractLines("desk"));
        String doc = "{\"tradeId\":\"T-9\",\"mtm\":5,\"nettingSet\":\"NS-A\",\"LEGS\":{\"CURRENCY\":\"EUR\"}}";
        String line = CassandraNode.line("desk", new DatedSourceContract.Row("trade", "T-9", DatedSourceContract.D3, doc))
                .replace("\"columns\":{", "\"columns\":{\"LEGS.CURRENCY\":\"EUR\",");
        CassandraNode.load(CassandraNode.args(node, "ld_alter"), List.of(line));
        try (CqlSession s = CassandraNode.session(node)) {
            var t = s.getMetadata().getKeyspace("ld_alter").orElseThrow().getTable("trade").orElseThrow();
            assertThat(t.getColumn(CqlIdentifier.fromInternal("legs__currency"))).isPresent();
            assertThat(t.getOptions().get(CqlIdentifier.fromInternal("comment"))).hasToString("drishti kind: trade");
        }
    }

    @Test
    void keepDaysDropsTheOldestDaysButNotMostOfThemWithoutForceDrop() throws Exception {
        String node = CassandraNode.cassandra();
        CassandraNode.load(CassandraNode.args(node, "ld_keep"), CassandraNode.contractLines("desk"));
        // trades have three days: keeping one would drop two of three, more than half
        List<String> trades = CassandraNode.contractLines("desk").stream().filter(l -> l.contains("\"kind\":\"trade\"")).toList();
        assertThatThrownBy(() -> CassandraNode.load(CassandraNode.args(node, "ld_keep", "--keep-days", "1"), trades)).hasMessageContaining("--force-drop");
        CassandraLoader.Result r = CassandraNode.load(CassandraNode.args(node, "ld_keep", "--keep-days", "1", "--force-drop"), trades);
        assertThat(r.daysDropped()).isEqualTo(2);
        try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
            p.start(DatedSourceContract.context(CassandraNode.settings(node, "ld_keep", "keep-cassandra")));
            assertThat(p.inventory("trade", 10)).containsOnlyKeys(DatedSourceContract.D3);
            assertThat(p.fetch(EntityRef.of("trade", "T-3"), AsOf.of(DatedSourceContract.D1))).isEmpty();       // not held: the next connector is asked
            assertThat(p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DatedSourceContract.D3))).isPresent();
            assertThat(p.inventory("counterparty", 10)).hasSize(2);                                             // not loaded this time: kept
        }
    }

    @Test
    void ttlDaysWritesEveryCellWithATtlOnOneDayTimeWindows() throws Exception {
        String node = CassandraNode.cassandra();
        CassandraLoader.Result r = CassandraNode.load(CassandraNode.args(node, "ld_ttl", "--ttl-days", "30"), CassandraNode.contractLines("desk"));
        int gen = r.days().stream().filter(d -> d.kind().equals("trade") && d.date().equals(DatedSourceContract.D3)).findFirst().orElseThrow().generation();
        try (CqlSession s = CassandraNode.session(node)) {
            int bucket = CassandraLayout.bucket("T-1", 4);
            int ttl = s.execute("SELECT TTL(doc) FROM ld_ttl.trade WHERE business_date = ? AND generation = ? AND bucket = ? AND id = ?",
                    DatedSourceContract.D3, gen, (short) bucket, "T-1").one().getInt(0);
            assertThat(ttl).isBetween(30 * 86_400 - 600, 30 * 86_400);
            int kindTtl = s.execute("SELECT TTL(generation) FROM ld_ttl.kind_days WHERE kind = ? AND business_date = ?", "trade", DatedSourceContract.D3)
                    .one().getInt(0);
            assertThat(kindTtl).isPositive();
            var compaction = s.getMetadata().getKeyspace("ld_ttl").orElseThrow().getTable("trade").orElseThrow().getOptions()
                    .get(CqlIdentifier.fromInternal("compaction"));
            assertThat(String.valueOf(compaction)).contains("TimeWindowCompactionStrategy");
        }
    }
}
