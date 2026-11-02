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

import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.cube.CubeEngine;
import com.ash.drishti.server.cube.CubeView;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The MR cube on a sharded date (MR_CUBE_LENS.md section 4.11): the date of {@link VarHierarchyFixture} cut into one shard per legal entity
 * and desk ({@link VarShardSingleNodeTest}'s settings), its view's sets summed in one batch through the shards
 * ({@code VarBook.vectors}); every cell and total equals the engine's VaR of the cell's scope on the same sharded date to the cent, and the
 * same view on the date read whole (not sharded) gives the same figures. The remote side (one call per owner for all the sets, each set's
 * vector the single set's to the bit) is {@code ShardReadAheadTest} in drishti-compute.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/var-cube-shard/connectors",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.hierarchies.LL.title=Limit letters", "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK",
        "drishti.var.hierarchies.LL.word=LL", "drishti.var.hierarchies.LL.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.root=target/var-cube-shard/official",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.var.currencies[0]=USD",
        "drishti.var.sharding.enabled=true", "drishti.var.sharding.target-shard-mb=0.0003", "drishti.var.sharding.stable-seconds=0",
        "drishti.var.sharding.release-after-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
class VarCubeShardTest {

    static final Path BASE = Path.of("target", "var-cube-shard").toAbsolutePath();
    static final Path LAKE = BASE.resolve("lake");
    static final LocalDate DAY = VarHierarchyFixture.DAY;
    static final Principal ME = new Principal("rita", List.of("risk"));

    static {
        try {
            if (Files.exists(BASE)) {
                try (Stream<Path> s = Files.walk(BASE)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            VarHierarchyFixture.write(LAKE);
            VarPvFixture.write(LAKE, DAY);
            Path conn = Files.createDirectories(BASE.resolve("connectors"));
            Files.writeString(conn.resolve("risk-lake.yaml"), VarShardClusterTest.riskLake("delta", LAKE, BASE));
        } catch (Exception e) {
            throw new IllegalStateException("the lake cannot be written: " + e, e);
        }
    }

    @Autowired VarService svc;
    @Autowired CubeEngine engine;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> a) {
        List<Map<String, Object>> all = new ArrayList<>((List<Map<String, Object>>) a.get("cells"));
        all.addAll((List<Map<String, Object>>) a.get("rows"));
        all.addAll((List<Map<String, Object>>) a.get("columns"));
        all.add((Map<String, Object>) a.get("total"));
        return all;
    }

    @SuppressWarnings("unchecked")
    private static double var(Map<String, Object> e) {
        return ((Number) ((Map<String, Object>) e.get("values")).get("VAR")).doubleValue();
    }

    @Test
    void aViewOnAShardedDateIsTheEnginesVarOfEveryCellsScopeAndTheWholeDatesToo() {
        VarBook book = svc.date(DAY).book();
        assertThat(book.sharded()).as("the date put together from its shards").isTrue();
        CubeView view = CubeView.parse("MCUBE BANK BY MGMT.BOOK,LEGAL_ENTITY MEASURE VAR,COMP_VAR DATE " + DAY, "mr");
        Map<String, Object> a = engine.query(ME, view);
        List<Map<String, Object>> all = entries(a);
        assertThat(all.size()).isGreaterThanOrEqualTo(5);
        double comp = 0;
        for (Map<String, Object> e : all) {
            String scope = String.valueOf(e.get("scope"));
            double engineVar = svc.scoped(ME, scope, DAY).measures().var();
            assertThat(var(e)).as(scope).isCloseTo(engineVar, org.assertj.core.data.Offset.offset(0.005));
        }
        for (Object r : (List<?>) a.get("rows")) {
            comp += ((Number) ((Map<?, ?>) ((Map<?, ?>) r).get("values")).get("COMP_VAR")).doubleValue();
        }
        assertThat(comp).isCloseTo(var((Map<String, Object>) a.get("total")), org.assertj.core.data.Offset.offset(0.02));
        // the same sets on the whole date (read without its shards): the same figures
        VarBook whole = svc.loader().load(DAY).book();
        try {
            for (Map<String, Object> e : all) {
                String scope = String.valueOf(e.get("scope"));
                double[] v = whole.vector(svc.scoped(ME, scope, DAY).set(), svc.sums());
                assertThat(var(e)).as(scope + " on the whole date").isCloseTo(svc.calc().var(v), org.assertj.core.data.Offset.offset(0.005));
            }
        } finally {
            svc.loader().tier().retire(whole, false);
        }
    }
}
