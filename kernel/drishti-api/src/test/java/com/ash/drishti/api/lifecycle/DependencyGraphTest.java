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
package com.ash.drishti.api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.lifecycle.DependencyGraph.Edge;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one dependency graph (docs/architecture/LIFECYCLE.md section 5): start order, the cascades of suspend and shutdown, the health
 * roll-up, a cycle refused naming it, a missing dependency refused in one sentence.
 *
 * <p>The node: module {@code banking} contains packs {@code core} and {@code market-risk} (which extends {@code core}) and connector
 * {@code scenario-lake}; {@code market-risk} uses {@code scenario-lake} and engine {@code var}; {@code core} uses {@code ref-lake};
 * {@code desk-views} (outside the module) extends {@code market-risk}.
 */
class DependencyGraphTest {

    static DependencyGraph bank() {
        return DependencyGraph.builder()
                .node("module:banking").node("pack:core").node("pack:market-risk").node("pack:desk-views")
                .node("connector:scenario-lake").node("connector:ref-lake").node("engine:var")
                .depends("pack:market-risk", "pack:core", Edge.EXTENDS, false)
                .depends("pack:desk-views", "pack:market-risk", Edge.EXTENDS, false)
                .depends("pack:market-risk", "connector:scenario-lake", Edge.USES, false)
                .depends("pack:market-risk", "engine:var", Edge.USES, false)
                .depends("pack:core", "connector:ref-lake", Edge.USES, false)
                .depends("module:banking", "pack:core", Edge.CONTAINS, false)
                .depends("module:banking", "pack:market-risk", Edge.CONTAINS, false)
                .depends("module:banking", "connector:scenario-lake", Edge.CONTAINS, false)
                .build();
    }

    @Test
    void startOrderPutsEveryDependencyFirst() {
        List<String> order = bank().startOrder(List.of("pack:desk-views"));
        assertThat(order).containsExactly("connector:ref-lake", "pack:core", "connector:scenario-lake", "engine:var", "pack:market-risk", "pack:desk-views");
        List<String> module = bank().startOrder(List.of("module:banking"));
        assertThat(module.indexOf("connector:scenario-lake")).as("connectors before the packs that use them").isLessThan(module.indexOf("pack:market-risk"));
        assertThat(module.indexOf("pack:core")).isLessThan(module.indexOf("pack:market-risk"));
        assertThat(module.get(module.size() - 1)).isEqualTo("module:banking");
    }

    @Test
    void stopOrderIsTheReverseDependantsFirst() {
        List<String> stop = bank().stopOrder(List.of("pack:desk-views"));
        assertThat(stop.get(0)).isEqualTo("pack:desk-views");
        assertThat(stop.indexOf("pack:market-risk")).isLessThan(stop.indexOf("pack:core"));
        assertThat(stop.indexOf("pack:market-risk")).as("packs before their connectors").isLessThan(stop.indexOf("connector:scenario-lake"));
    }

    @Test
    void aSuspendCascadesToThePacksThatExtendItAndDegradesThoseThatOnlyUseIt() {
        assertThat(bank().suspendCascade("pack:core")).containsExactly("pack:desk-views", "pack:market-risk", "pack:core");
        assertThat(bank().suspendCascade("connector:scenario-lake")).as("a connector suspends only itself").containsExactly("connector:scenario-lake");
        assertThat(bank().degraded("connector:scenario-lake")).containsExactly("pack:market-risk");
        assertThat(bank().degraded("engine:var")).containsExactly("pack:market-risk");
        List<String> module = bank().suspendCascade("module:banking");
        assertThat(module.get(module.size() - 1)).as("the module after its parts").isEqualTo("module:banking");
        assertThat(module.indexOf("pack:market-risk")).as("packs before connectors").isLessThan(module.indexOf("connector:scenario-lake"));
        assertThat(module).contains("pack:desk-views");                     // it extends a pack of the module, so it goes with it
    }

    @Test
    void aShutdownCascadeTakesEveryDependantFirst() {
        List<String> c = bank().shutdownCascade("connector:scenario-lake");
        assertThat(c).containsExactly("pack:desk-views", "pack:market-risk", "connector:scenario-lake");
        assertThat(bank().runningDependants("connector:scenario-lake", java.util.Set.of("pack:market-risk"))).containsExactly("pack:market-risk");
        assertThat(bank().runningDependants("pack:core", java.util.Set.of())).isEmpty();
    }

    @Test
    void healthRollsUpADependencyDownOrSuspendedAsDegradedNamingIt() {
        Instant now = Instant.now();
        Map<String, ComponentHealth> h = Map.of(
                "connector:scenario-lake", ComponentHealth.of(ComponentHealth.Status.SUSPENDED, "suspended by ops", now),
                "engine:var", ComponentHealth.up(now),
                "pack:core", ComponentHealth.up(now));
        ComponentHealth mr = bank().rollUp("pack:market-risk", ComponentHealth.up(now), h::get);
        assertThat(mr.status()).isEqualTo(ComponentHealth.Status.DEGRADED);
        assertThat(mr.reasons()).containsExactly("needs connector:scenario-lake, which is suspended (suspended by ops)");
        Map<String, ComponentHealth> down = Map.of("connector:scenario-lake", ComponentHealth.up(now),
                "engine:var", ComponentHealth.of(ComponentHealth.Status.DOWN, "off", now), "pack:core", ComponentHealth.up(now));
        ComponentHealth mr2 = bank().rollUp("pack:market-risk", ComponentHealth.up(now), down::get);
        assertThat(mr2.status()).as("never DOWN for a dependency: it serves what does not read it").isEqualTo(ComponentHealth.Status.DEGRADED);
        assertThat(mr2.reasons()).containsExactly("needs engine:var, which is down (off)");
        ComponentHealth suspendedItself = bank().rollUp("pack:market-risk", ComponentHealth.of(ComponentHealth.Status.SUSPENDED, "suspended", now), down::get);
        assertThat(suspendedItself.status()).isEqualTo(ComponentHealth.Status.SUSPENDED);
        Map<String, ComponentHealth> allUp = Map.of("connector:scenario-lake", ComponentHealth.up(now), "engine:var", ComponentHealth.up(now),
                "pack:core", ComponentHealth.up(now));
        assertThat(bank().rollUp("pack:market-risk", ComponentHealth.up(now), allUp::get).status()).isEqualTo(ComponentHealth.Status.UP);
    }

    @Test
    void anOptionalDependencyNeitherDegradesNorRefuses() {
        DependencyGraph g = DependencyGraph.builder().node("pack:a").depends("pack:a", "engine:frtb", Edge.USES, true).build();
        assertThat(g.startProblem(List.of("pack:a"))).isNull();
        assertThat(g.rollUp("pack:a", ComponentHealth.up(Instant.now()), n -> null).status()).isEqualTo(ComponentHealth.Status.UP);
    }

    @Test
    void aCycleIsRefusedAtStartNamingIt() {
        DependencyGraph g = DependencyGraph.builder().node("pack:a").node("pack:b").node("pack:c")
                .depends("pack:a", "pack:b", Edge.EXTENDS, false)
                .depends("pack:b", "pack:c", Edge.EXTENDS, false)
                .depends("pack:c", "pack:a", Edge.EXTENDS, false).build();
        assertThat(g.cycle()).containsExactly("pack:a", "pack:b", "pack:c", "pack:a");
        assertThat(g.startProblem(List.of("pack:b"))).isEqualTo(
                "the parts depend on each other in a cycle: pack:a -> pack:b -> pack:c -> pack:a; remove one of these dependencies");
        assertThatThrownBy(() -> g.startOrder(List.of("pack:a"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("cycle");
        assertThat(g.describe().get("cycle")).isEqualTo(List.of("pack:a", "pack:b", "pack:c", "pack:a"));
    }

    @Test
    void aMissingDependencyIsRefusedAtStartInOneSentence() {
        DependencyGraph g = DependencyGraph.builder().node("pack:a").node("pack:b")
                .depends("pack:a", "pack:b", Edge.EXTENDS, false)
                .depends("pack:b", "connector:gone", Edge.USES, false).build();
        assertThat(g.startProblem(List.of("pack:a"))).isEqualTo(
                "pack:b needs connector:gone, which does not exist here; add it, or mark the dependency optional");
        assertThat(g.describe().get("missing")).isEqualTo(List.of("pack:b -> connector:gone"));
        assertThat(bank().startProblem(List.of("module:banking"))).isNull();
    }
}
