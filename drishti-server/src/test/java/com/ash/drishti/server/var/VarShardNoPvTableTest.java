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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.shard.ShardMap;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.server.PinnedBusinessDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A lake with P&amp;L vectors and no {@code scenario-pv} table is sharded with the default {@code kinds: [scenario-pv, pnl-vector]}
 * (2.9.7): the connector answers {@code risk-lake=none} for the kind it holds no table of, and that counts as holding nothing; the date is
 * cut on its P&amp;L vectors.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/var-shard-nopv/connectors",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.official.root=target/var-shard-nopv/official",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.var.currencies[0]=USD",
        "drishti.var.sharding.enabled=true", "drishti.var.sharding.target-shard-mb=0.0003", "drishti.var.sharding.stable-seconds=0",
        "drishti.var.sharding.release-after-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
class VarShardNoPvTableTest {

    static final Path BASE = Path.of("target", "var-shard-nopv").toAbsolutePath();
    static final Path LAKE = BASE.resolve("lake");
    static final LocalDate DAY = VarHierarchyFixture.DAY;

    static {
        try {
            if (Files.exists(BASE)) {
                try (Stream<Path> s = Files.walk(BASE)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            VarHierarchyFixture.write(LAKE);                           // P&L vectors only: no scenario-pv table
            Path conn = Files.createDirectories(BASE.resolve("connectors"));
            Files.writeString(conn.resolve("risk-lake.yaml"), VarShardClusterTest.riskLake("delta", LAKE, BASE));
        } catch (Exception e) {
            throw new IllegalStateException("the lake cannot be written: " + e, e);
        }
    }

    @Autowired VarService svc;

    @Test
    void aLakeWithoutScenarioPvsIsStillCut() {
        assertThat(svc.shards().settings().kinds()).as("the default kinds").contains("scenario-pv", "pnl-vector");
        assertThat(svc.loader().router().dataVersion("scenario-pv", com.ash.drishti.api.AsOf.of(DAY)).orElse(""))
                .as("what the connector answers for the kind it holds no table of").endsWith("=none");
        ShardMap map = svc.shards().map(DAY);
        assertThat(map.size()).as("cut by legal entity and desk, not one shard").isGreaterThan(1);
        VarBook a = svc.date(DAY).book();
        assertThat(a.sharded()).isTrue();
        VarBook w = svc.loader().load(DAY).book();
        try {
            for (VarBook.Node n : w.nodes().values()) {
                assertThat(a.aggregate(n.id())).as(n.id()).containsExactly(w.aggregate(n.id()));
            }
        } finally {
            svc.loader().tier().retire(w, false);
        }
    }

    @Test
    void holdingNothingIsNoneWithOrWithoutTheSourcesName() {
        assertThat(VarLoader.holdsNothing("")).isTrue();
        assertThat(VarLoader.holdsNothing("none")).isTrue();
        assertThat(VarLoader.holdsNothing("risk-store=none")).isTrue();
        assertThat(VarLoader.holdsNothing("risk-store=none|other=none")).isTrue();
        assertThat(VarLoader.holdsNothing("risk-store=12")).isFalse();
        assertThat(VarLoader.holdsNothing("risk-store=none|other=7")).isFalse();
        assertThat(VarLoader.holdsNothing("risk-store=nonesuch")).isFalse();
    }
}
