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
package com.ash.drishti.module.runtime.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.module.runtime.placement.PlacementResolver.State;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Placement resolution, singleton election and failover inside the placement, the heavy-work refusals (KERNEL_AND_MODULES.md 4.11). */
class PlacementResolverTest {

    static final ModuleParts SAMPLE = ModuleParts.of("sample", "1.0.0", "sample", List.of(
            new ModuleParts.Part("api", PartClass.LIGHT, 0, List.of()),
            new ModuleParts.Part("compute", PartClass.HEAVY, 2048, List.of("compute", "var/run")),
            new ModuleParts.Part("feeds", PartClass.FEED, 0, List.of("feeds"))));
    static final ModuleParts BANKING = ModuleParts.inProcess("banking", "2.9.11");

    static List<NodeView> nodes(boolean aUp, boolean bUp, boolean cUp) {
        return List.of(new NodeView("a", List.of("web"), aUp), new NodeView("b", List.of("heavy", "role:web"), bUp),
                new NodeView("c", List.of(), cUp));
    }

    static final String YAML = """
            format: 1
            version: 3
            modules:
              sample:
                parts:
                  api:     { roles: [web] }
                  compute: { roles: [heavy], max-nodes: 1 }
                  feeds:   { nodes: [b, a] }
            """;

    @Test
    void resolvesByNodeAndByRoleAndNothingIsPlacedByDefault() {
        Placement p = Placement.read(YAML);
        assertThat(p.version()).isEqualTo(3);
        PlacementResolver.Matrix m = PlacementResolver.resolve(p, List.of(SAMPLE, BANKING), nodes(true, true, true));
        assertThat(m.part("sample", "api").orElseThrow().placed()).containsExactly("a", "b");
        assertThat(m.part("sample", "compute").orElseThrow().placed()).containsExactly("b");
        assertThat(m.part("sample", "compute").orElseThrow().cells()).extracting(PlacementResolver.Cell::state)
                .containsExactly(State.NOT_PLACED, State.ACTIVE, State.NOT_PLACED);
        // today's in-process banking: every part on every node, unguarded
        assertThat(m.part("banking", "compute").orElseThrow().status()).isEqualTo("in-process");
        assertThat(m.part("banking", "feeds").orElseThrow().placed()).containsExactly("a", "b", "c");
        // no all-nodes default: an empty placement puts the module nowhere, and says so
        PlacementResolver.Matrix none = PlacementResolver.resolve(Placement.EMPTY, List.of(SAMPLE), nodes(true, true, true));
        assertThat(none.parts()).allMatch(r -> r.status().equals("not-placed") && r.placed().isEmpty());
        assertThat(none.problems()).anyMatch(s -> s.startsWith("sample is not placed"));
    }

    @Test
    void aSingletonHasOneOwnerAndFailsOverOnlyInsideItsPlacement() {
        Placement p = Placement.read(YAML);
        PlacementResolver.PartView f = PlacementResolver.resolve(p, List.of(SAMPLE), nodes(true, true, true)).part("sample", "feeds").orElseThrow();
        assertThat(f.owner()).isEqualTo("a");                                   // the first live candidate by name
        assertThat(f.cells()).extracting(PlacementResolver.Cell::state).containsExactly(State.ACTIVE, State.STANDBY, State.NOT_PLACED);
        // a goes down: b (placed) takes over, never c
        assertThat(PlacementResolver.resolve(p, List.of(SAMPLE), nodes(false, true, true)).part("sample", "feeds").orElseThrow().owner())
                .isEqualTo("b");
        // one election object: told of the change when the owner moves
        java.util.Set<String> live = new java.util.HashSet<>(List.of("a", "b", "c"));
        FirstLiveElection e = new FirstLiveElection(() -> live);
        List<String> changed = new java.util.ArrayList<>();
        e.onChange(changed::add);
        e.candidates("sample.feeds", List.of("b", "a"));
        assertThat(e.owner("sample.feeds")).contains("a");
        live.remove("a");
        assertThat(e.owner("sample.feeds")).contains("b");
        assertThat(changed).containsExactly("sample.feeds");
        // a and b down: unavailable, not moved to c
        PlacementResolver.Matrix gone = PlacementResolver.resolve(p, List.of(SAMPLE), nodes(false, false, true));
        assertThat(gone.part("sample", "feeds").orElseThrow().owner()).isNull();
        assertThat(gone.part("sample", "feeds").orElseThrow().status()).isEqualTo("unavailable");
        assertThat(gone.problems()).anyMatch(s -> s.contains("sample.feeds is unavailable"));
    }

    @Test
    void heavyWorkNeverSpreadsByItself() {
        Placement wide = Placement.read("""
                modules:
                  sample:
                    parts:
                      compute: { roles: [all], max-nodes: 2 }
                      nonsense: { nodes: [a] }
                  banking:
                    parts:
                      compute: { nodes: [a] }
                """);
        List<String> r = PlacementResolver.refusals(wide, List.of(SAMPLE, BANKING), nodes(true, true, true));
        assertThat(r).anyMatch(s -> s.contains("on 3 nodes: its max-nodes is 2"))
                .anyMatch(s -> s.contains("on 3 of 3 nodes") && s.contains("allow-all"))
                .anyMatch(s -> s.contains("no part `nonsense`"))
                .anyMatch(s -> s.startsWith("banking is built into the server"));
        Placement allowed = Placement.read("modules: { sample: { parts: { compute: { roles: [all], allow-all: true } } } }");
        assertThat(PlacementResolver.refusals(allowed, List.of(SAMPLE), nodes(true, true, true))).isEmpty();
    }

    @Test
    void theFileIsCheckedAndWrittenBack() {
        assertThat(Placement.read("")).isEqualTo(Placement.EMPTY);
        assertThatThrownBy(() -> Placement.read("modules: { sample: { parts: { api: {} } } }")).hasMessageContaining("names no node and no role");
        assertThatThrownBy(() -> Placement.read("modules: { sample: { parts: { api: { hosts: [a] } } } }")).hasMessageContaining("unknown field `hosts`");
        assertThatThrownBy(() -> Placement.read("colour: blue")).hasMessageContaining("unknown field `colour`");
        Placement p = Placement.read(YAML);
        assertThat(Placement.read(p.toYaml())).isEqualTo(p);
        assertThat(Placement.read(p.withVersion(4).toYaml()).version()).isEqualTo(4);
    }

    @Test
    void aRequestPathBelongsToTheLongestDeclaredPart() {
        assertThat(SAMPLE.partFor("compute/x").orElseThrow().name()).isEqualTo("compute");
        assertThat(SAMPLE.partFor("/var/run/7").orElseThrow().name()).isEqualTo("compute");
        assertThat(SAMPLE.partFor("var/other").orElseThrow().name()).isEqualTo("api");
        assertThat(SAMPLE.partFor("computer").orElseThrow().name()).isEqualTo("api");
        assertThat(new NodeView("x", List.of("role:Heavy"), true).has("heavy")).isTrue();
        assertThat(new NodeView("x", List.of("heavy"), true).has("role:heavy")).isTrue();
    }
}
