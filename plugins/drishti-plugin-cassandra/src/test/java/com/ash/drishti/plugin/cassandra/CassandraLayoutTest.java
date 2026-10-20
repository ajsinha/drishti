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

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The names, the bucket hash and the loader's command line: no Cassandra needed. */
class CassandraLayoutTest {

    @Test
    void namesFollowTheDeltaRuleInLowerCase() {
        assertThat(CassandraLayout.column("LEGS.CURRENCY")).isEqualTo("legs__currency");
        assertThat(CassandraLayout.column("PRODUCT_SUB_TYPE")).isEqualTo("product_sub_type");
        assertThat(CassandraLayout.column("counterparty.id")).isEqualTo("counterparty__id");
        assertThat(CassandraLayout.column("id")).isEqualTo("f_id");                     // never a key or the document
        assertThat(CassandraLayout.column("doc")).isEqualTo("f_doc");
        assertThat(CassandraLayout.column("2nd-leg")).isEqualTo("c_2nd_leg");
        assertThat(CassandraLayout.table("netting-set")).isEqualTo("netting_set");
        assertThat(CassandraLayout.table("kind_days")).isEqualTo("kind_days_k");        // never a shared table
        assertThat(CassandraLayout.keyspace("Trading")).isEqualTo("trading");
        String longPath = "a.very.long.path.that.goes.on.and.on.beyond.forty.eight.characters";
        assertThat(CassandraLayout.column(longPath)).hasSize(48).isNotEqualTo(CassandraLayout.column(longPath + "s"));
    }

    @Test
    void theBucketIsMurmur3OfTheIdSoEveryClientAgrees() {
        // reference values of MurmurHash3_x86_32 with seed 0
        assertThat(CassandraLayout.murmur3(new byte[0])).isZero();
        assertThat(CassandraLayout.murmur3("hello".getBytes(StandardCharsets.UTF_8))).isEqualTo(0x248bfa47);
        assertThat(CassandraLayout.murmur3("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8))).isEqualTo(0x2e4ff723);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            int b = CassandraLayout.bucket("MX-" + (20_000_000 + i), 128);
            assertThat(b).isBetween(0, 127);
            seen.add(b);
        }
        assertThat(seen).hasSize(128);                                                 // a book spreads over every bucket
        assertThat(CassandraLayout.bucket("T-1", 1)).isZero();
    }

    @Test
    void theLoadersCommandLine() {
        CassandraLoader.Options o = CassandraLoader.parse(new String[] {"-", "cass1:9042,cass2", "dc-east", "--create", "--ttl-days", "90",
                "--buckets", "64", "--buckets", "trade=256", "--setting", "tls.enabled=true", "--keyspace", "risk"});
        assertThat(o.settings()).containsEntry("contact-points", "cass1:9042,cass2").containsEntry("local-datacenter", "dc-east")
                .containsEntry("tls.enabled", "true").containsEntry("consistency", "LOCAL_QUORUM");
        assertThat(o.create()).isTrue();
        assertThat(o.ttlDays()).isEqualTo(90);
        assertThat(o.bucketsFor("trade")).isEqualTo(256);
        assertThat(o.bucketsFor("position")).isEqualTo(64);
        assertThat(o.keyspace()).isEqualTo("risk");
        CassandraLoader.Options d = CassandraLoader.parse(new String[] {"data.jsonl"});
        assertThat(d.settings()).containsEntry("contact-points", "localhost:9042").containsEntry("local-datacenter", "datacenter1");
        assertThat(d.buckets()).isEqualTo(128);
        assertThat(d.inFlight()).isEqualTo(256);
    }

    @Test
    void settingsAreCheckedBeforeAnythingConnects() {
        assertThatThrownBy(() -> CassandraSessions.plan(Map.of("local-datacenter", "dc1"), k -> null)).hasMessageContaining("contact-points is required");
        assertThatThrownBy(() -> CassandraSessions.plan(Map.of("contact-points", "h"), k -> null)).hasMessageContaining("local-datacenter is required");
        assertThatThrownBy(() -> CassandraSessions.plan(Map.of("contact-points", "h", "local-datacenter", "dc1", "consistency", "SERIALISH"), k -> null))
                .hasMessageContaining("consistency");
        assertThatThrownBy(() -> CassandraSessions.plan(Map.of("contact-points", "h", "local-datacenter", "dc1", "proxy.host", "p"), k -> null))
                .hasMessageContaining("TCP");
        assertThatThrownBy(() -> CassandraSessions.plan(Map.of("secure-connect-bundle", "/no/such/bundle.zip"), k -> null))
                .hasMessageContaining("no such file");
        assertThat(CassandraSessions.contactPoints("a, b:9043,[::1]:9044,[fe80::1]")).extracting(a -> a.getHostString() + "/" + a.getPort())
                .containsExactly("a/9042", "b/9043", "::1/9044", "fe80::1/9042");
        var plan = CassandraSessions.plan(Map.of("contact-points", "h1,h2", "local-datacenter", "dc1", "consistency", "local_one"), k -> null);
        assertThat(plan.consistency()).isEqualTo("LOCAL_ONE");
        assertThat(plan.connectionsPerNode()).isEqualTo(2);
        assertThat(plan.tls()).isNull();
    }
}
