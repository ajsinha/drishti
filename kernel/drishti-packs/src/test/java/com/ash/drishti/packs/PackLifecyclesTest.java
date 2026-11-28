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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.ComponentSuspended;
import com.ash.drishti.api.lifecycle.LifecycleEvent;
import com.ash.drishti.api.lifecycle.LifecycleRefused;
import com.ash.drishti.api.lifecycle.LifecycleState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The packs' lifecycle (docs/architecture/LIFECYCLE.md section 3) against a registry and actions that record what the server would do:
 * the gate, suspend and resume (with the connectors only a pack uses), cascades to the packs that extend one, shutdown leaving nothing
 * registered, starts refused for a cycle or a missing dependency, and the health roll-up.
 */
class PackLifecyclesTest {

    /** The server's side, recorded: the registry follows load and unload, the connectors' states are a map. */
    static final class Recorded implements PackLifecycles.Actions {
        final PackRegistry registry;
        final Map<String, Pack> disk = new LinkedHashMap<>();
        final List<String> log = new CopyOnWriteArrayList<>();
        final Map<String, String> connectorState = new ConcurrentHashMap<>();

        Recorded(PackRegistry registry) {
            this.registry = registry;
        }

        public Pack validate(String pack) {
            if (!disk.containsKey(pack)) {
                throw new IllegalStateException("no pack '" + pack + "'");
            }
            log.add("validate " + pack);
            return disk.get(pack);
        }

        public void load(String pack, String actor) {
            List<Pack> now = new ArrayList<>(registry.packs());
            now.add(disk.get(pack));
            registry.replace(now);
            log.add("load " + pack);
        }

        public void unload(String pack, String actor) {
            registry.replace(registry.packs().stream().filter(p -> !p.name().equals(pack)).toList());
            log.add("unload " + pack);
        }

        public List<String> suspendParts(String pack, String actor) {
            Set<String> others = new LinkedHashSet<>();
            registry.packs().stream().filter(p -> !p.name().equals(pack)).forEach(p -> others.addAll(p.connectorRefs()));
            List<String> mine = registry.packs().stream().filter(p -> p.name().equals(pack)).findFirst().orElseThrow().connectorRefs().stream()
                    .filter(c -> !others.contains(c)).toList();
            mine.forEach(c -> connectorState.put(c, "SUSPENDED"));
            log.add("suspend parts of " + pack + " " + mine);
            return mine;
        }

        public void resumeParts(String pack, List<String> connectors, String actor) {
            connectors.forEach(c -> connectorState.put(c, "RUNNING"));
            log.add("resume parts of " + pack + " " + connectors);
        }
    }

    static Pack pack(String name, List<String> kinds, List<String> extend, List<String> connectors, Map<String, Object> uses) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pack", name);
        m.put("kinds", kinds);
        m.put("extends", extend);
        m.put("connectors", connectors);
        if (uses != null) {
            m.put("uses", uses);
        }
        return new Pack(name, "1.0.0", name, "", Path.of("/packs/" + name), m);
    }

    static final Pack CORE = pack("core", List.of("book"), List.of(), List.of("ref-lake"), null);
    static final Pack RISK = pack("market-risk", List.of("var-result", "scenario-pv"), List.of("core"), List.of("scenario-lake", "ref-lake"),
            Map.of("engines", List.of(Map.of("engine", "var", "outputs", List.of("headline")), Map.of("engine", "frtb", "optional", true))));
    static final Pack DESK = pack("desk-views", List.of("desk-view"), List.of("market-risk"), List.of(), null);

    PackRegistry registry = PackRegistry.of(List.of(CORE, RISK, DESK));
    Recorded actions = new Recorded(registry);
    Set<String> connectors = new LinkedHashSet<>(Set.of("ref-lake", "scenario-lake"));
    Set<String> engines = new LinkedHashSet<>(Set.of("var", "frtb"));
    Map<String, ComponentHealth> engineHealth = new ConcurrentHashMap<>();
    PackLifecycles lc = new PackLifecycles(registry);
    List<LifecycleEvent> events = new CopyOnWriteArrayList<>();

    PackLifecyclesTest() {
        List.of(CORE, RISK, DESK).forEach(p -> actions.disk.put(p.name(), p));
        lc.wire(actions, new PackLifecycles.World(() -> connectors, () -> engines, Map::of, Map::of,
                c -> "SUSPENDED".equals(actions.connectorState.get(c)) ? ComponentHealth.of(ComponentHealth.Status.SUSPENDED, "suspended by ops", Instant.now())
                        : ComponentHealth.up(Instant.now()),
                e -> engineHealth.getOrDefault(e, ComponentHealth.up(Instant.now()))));
        lc.onTransition(events::add);
        lc.sync("system");
    }

    @Test
    void thePacksTheServerBootedAreRunning() {
        assertThat(lc.state("core")).isEqualTo(LifecycleState.RUNNING);
        assertThat(lc.state("market-risk")).isEqualTo(LifecycleState.RUNNING);
        assertThat(lc.state("not-a-pack")).isEqualTo(LifecycleState.NEW);
        lc.requireServing("var-result");
        lc.requireServing("a-kind-no-pack-owns");
        assertThat(lc.stats("market-risk").total().requests()).isEqualTo(1);
    }

    @Test
    void aSuspendedPackAnswersSuspendedAndResumeServesAgainUnchanged() {
        List<String> along = lc.suspend("market-risk", "ops");
        assertThat(along).as("desk-views extends it: suspended first").containsExactly("desk-views", "market-risk");
        assertThatThrownBy(() -> lc.requireServing("var-result")).isInstanceOf(ComponentSuspended.class)
                .hasMessageContaining("pack market-risk is suspended").hasMessageContaining("by ops");
        assertThatThrownBy(() -> lc.requireServing("desk-view")).isInstanceOf(ComponentSuspended.class);
        lc.requireServing("book");                                               // its parent serves on
        assertThat(actions.connectorState).as("only the connector no other pack uses").containsExactly(Map.entry("scenario-lake", "SUSPENDED"));
        assertThat(registry.packs()).as("nothing unregistered").extracting(Pack::name).containsExactly("core", "market-risk", "desk-views");
        long version = registry.version();

        List<String> back = lc.resume("market-risk", "ops");
        assertThat(back).containsExactly("market-risk", "desk-views");
        lc.requireServing("var-result");
        lc.requireServing("desk-view");
        assertThat(actions.connectorState).containsEntry("scenario-lake", "RUNNING");
        assertThat(registry.version()).as("nothing reloaded").isEqualTo(version);
        assertThat(actions.log).doesNotContain("load market-risk", "unload market-risk");
        assertThat(lc.stats("market-risk").total().refusals()).isEqualTo(1);
        assertThat(events).filteredOn(e -> e.name().equals("market-risk")).extracting(LifecycleEvent::auditAction)
                .containsExactly("pack-init", "pack-start", "pack-suspend", "pack-resume");
    }

    @Test
    void thePackSwitchStopsTheConnectorsOnlyThePackUsesAndSwitchingOnResumesThem() {
        assertThat(lc.follow("desk-views", false, "admin")).containsExactly("desk-views");
        assertThat(lc.state("desk-views")).isEqualTo(LifecycleState.SUSPENDED);
        assertThat(lc.follow("desk-views", false, "admin")).as("already off: unchanged").isEmpty();
        assertThat(lc.follow("market-risk", false, "admin")).containsExactly("market-risk");
        assertThat(actions.connectorState).as("scenario-lake only market-risk uses; ref-lake core uses too").containsExactly(Map.entry("scenario-lake", "SUSPENDED"));
        assertThatThrownBy(() -> lc.requireServing("var-result")).isInstanceOf(ComponentSuspended.class);
        assertThat(lc.follow("market-risk", true, "admin")).containsExactly("market-risk");
        assertThat(actions.connectorState).containsEntry("scenario-lake", "RUNNING");
        lc.requireServing("var-result");
        assertThat(lc.state("desk-views")).as("a pack that extends it keeps its own switch").isEqualTo(LifecycleState.SUSPENDED);
        assertThat(lc.follow("not-a-pack", false, "admin")).isEmpty();
    }

    @Test
    void illegalTransitionsAreRefusedAndChangeNothing() {
        assertThatThrownBy(() -> lc.resume("core", "ops")).isInstanceOf(LifecycleRefused.class).hasMessageContaining("is running");
        assertThatThrownBy(() -> lc.start("core", "ops")).isInstanceOf(LifecycleRefused.class);
        assertThatThrownBy(() -> lc.suspend("not-a-pack", "ops")).isInstanceOf(LifecycleRefused.class);
        lc.suspend("desk-views", "ops");
        assertThatThrownBy(() -> lc.suspend("desk-views", "ops")).isInstanceOf(LifecycleRefused.class).hasMessageContaining("is suspended");
        assertThat(lc.state("desk-views")).isEqualTo(LifecycleState.SUSPENDED);
        assertThat(lc.state("core")).isEqualTo(LifecycleState.RUNNING);
    }

    @Test
    void shutdownIsRefusedWhileAPackDependsOnItUnlessTheCascadeIsAsked() {
        assertThatThrownBy(() -> lc.shutdown("core", "ops", false)).isInstanceOf(LifecycleRefused.class)
                .hasMessageContaining("pack:market-risk").hasMessageContaining("ask for the cascade");
        assertThat(lc.state("core")).isEqualTo(LifecycleState.RUNNING);
        List<String> done = lc.shutdown("core", "ops", true);
        assertThat(done).as("dependants first").containsExactly("desk-views", "market-risk", "core");
        assertThat(actions.log).containsSubsequence("unload desk-views", "unload market-risk", "unload core");
    }

    @Test
    void shutdownLeavesNothingRegisteredAndIsHarmlessTwice() {
        lc.suspend("desk-views", "ops");
        lc.shutdown("desk-views", "ops", false);
        assertThat(lc.state("desk-views")).isEqualTo(LifecycleState.SHUTDOWN);
        assertThat(registry.packs()).extracting(Pack::name).doesNotContain("desk-views");
        assertThat(registry.packs().stream().flatMap(p -> p.kinds().stream())).doesNotContain("desk-view");
        assertThat(registry.removedOwner("desk-view")).isEqualTo("desk-views");
        lc.requireServing("desk-view");                                         // no pack owns it any more: the gate has nothing to say
        assertThat(lc.shutdown("desk-views", "ops", false)).isEmpty();
        assertThat(actions.log.stream().filter(l -> l.equals("unload desk-views"))).hasSize(1);
    }

    @Test
    void aShutDownPackStartsAgainAsANewInstance() {
        lc.shutdown("desk-views", "ops", false);
        lc.init("desk-views", "ops");
        assertThat(lc.state("desk-views")).isEqualTo(LifecycleState.INITIALISED);
        assertThat(registry.packs()).extracting(Pack::name).doesNotContain("desk-views");     // init registers nothing
        lc.start("desk-views", "ops");
        assertThat(lc.state("desk-views")).isEqualTo(LifecycleState.RUNNING);
        assertThat(registry.packs()).extracting(Pack::name).contains("desk-views");
        assertThat(actions.log).containsSubsequence("validate desk-views", "load desk-views");
    }

    @Test
    void aStartIsRefusedInOneSentenceForAMissingDependencyOrACycle() {
        Pack orphan = pack("orphan", List.of("o"), List.of(), List.of("no-such-lake"), null);
        actions.disk.put("orphan", orphan);
        assertThatThrownBy(() -> lc.start("orphan", "ops")).isInstanceOf(LifecycleRefused.class)
                .hasMessageContaining("pack:orphan needs connector:no-such-lake, which does not exist here");
        assertThat(registry.packs()).extracting(Pack::name).doesNotContain("orphan");

        Pack a = pack("a", List.of("ka"), List.of("b"), List.of(), null);
        Pack b = pack("b", List.of("kb"), List.of("a"), List.of(), null);
        actions.disk.put("a", a);
        actions.disk.put("b", b);
        registry.replace(new ArrayList<>(List.of(CORE, RISK, DESK, a, b)));       // as a folder edit would bring them
        assertThatThrownBy(() -> lc.start("a", "ops")).isInstanceOf(LifecycleRefused.class).hasMessageContaining("depend on each other in a cycle")
                .hasMessageContaining("pack:a").hasMessageContaining("pack:b");
        assertThat(lc.state("a")).as("refused: still only initialized").isEqualTo(LifecycleState.INITIALISED);
    }

    @Test
    void healthRollsUpTheConnectorsAndEnginesItNeeds() {
        assertThat(lc.health("market-risk").status()).isEqualTo(ComponentHealth.Status.UP);
        actions.connectorState.put("scenario-lake", "SUSPENDED");
        ComponentHealth h = lc.health("market-risk");
        assertThat(h.status()).isEqualTo(ComponentHealth.Status.DEGRADED);
        assertThat(h.reasons()).containsExactly("needs connector:scenario-lake, which is suspended (suspended by ops)");
        assertThat(lc.health("desk-views").status()).as("its parent is degraded: so is it, naming it").isEqualTo(ComponentHealth.Status.DEGRADED);
        actions.connectorState.put("scenario-lake", "RUNNING");
        engineHealth.put("frtb", ComponentHealth.of(ComponentHealth.Status.DOWN, "off", Instant.now()));
        assertThat(lc.health("market-risk").status()).as("frtb is optional").isEqualTo(ComponentHealth.Status.UP);
        engineHealth.put("var", ComponentHealth.of(ComponentHealth.Status.DOWN, "off", Instant.now()));
        assertThat(lc.health("market-risk").reasons()).containsExactly("needs engine:var, which is down (off)");
        lc.suspend("market-risk", "ops");
        assertThat(lc.health("market-risk").status()).isEqualTo(ComponentHealth.Status.SUSPENDED);
    }

    @Test
    void theGraphIsBuiltFromWhatThePacksAlreadyDeclare() {
        var g = lc.graph();
        assertThat(g.dependencies("pack:market-risk")).extracting(d -> d.to() + " " + d.edge() + (d.optional() ? " optional" : ""))
                .containsExactly("pack:core EXTENDS", "connector:scenario-lake USES", "connector:ref-lake USES", "engine:var USES", "engine:frtb USES optional");
        assertThat(g.startOrder(List.of("pack:desk-views"))).containsExactly("connector:ref-lake", "pack:core", "connector:scenario-lake", "engine:var",
                "engine:frtb", "pack:market-risk", "pack:desk-views");
    }
}
