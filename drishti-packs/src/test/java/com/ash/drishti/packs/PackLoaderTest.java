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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackLoaderTest {

    static final Path PACKS = Path.of("..", "packs").toAbsolutePath().normalize();

    @Test
    void theShippedPacksLoadAndContributeProperties() {
        PackLoader l = new PackLoader();
        List<Pack> packs = l.load(PACKS, List.of("finance", "logistics"));
        assertThat(packs).extracting(Pack::name).containsExactly("finance", "logistics");
        Map<String, Object> p = l.properties(packs);
        assertThat(p).containsEntry("drishti.commands.mnemonics.TRD.kind", "trade")
                .containsEntry("drishti.commands.mnemonics.SHP.kind", "shipment")
                .containsEntry("drishti.graph.fields.nettingSet.kind", "netting-set")
                .containsEntry("drishti.security.roles.trader.kinds[0]", "trade")
                .containsEntry("drishti.packs.loaded", "finance,logistics");
        assertThat(p.get("drishti.rachana.pack-dirs[0]").toString()).endsWith("finance/sutras");
        assertThat(p.get("drishti.rachana.pack-dirs[1]").toString()).endsWith("logistics/sutras");
        assertThat(p.get("drishti.sources.plugins.demo.settings.dirs").toString()).contains("finance/samples").contains("logistics/samples");
        assertThat(p.keySet()).anyMatch(k -> k.startsWith("drishti.graph.id-patterns[") && k.endsWith("].kind"));
        // pivot: a kind's search results opt into a Pivot tab; kept as JSON for the search engine to read
        assertThat(p.get("drishti.search.pivot.trade").toString()).startsWith("{\"fields\":[\"book\",").contains("\"rows\":[\"book\"]")
                .contains("\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"}]");
    }

    @Test
    void aPivotIsTrueFalseOrAMapping(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("a"));
        Files.writeString(dir.resolve("a").resolve("pack.yaml"), "pack: a\nkinds: [thing]\npivot:\n  thing: true\n");
        PackLoader l = new PackLoader();
        assertThat(l.properties(l.load(dir, List.of("a")))).containsEntry("drishti.search.pivot.thing", "true");
        Files.writeString(dir.resolve("a").resolve("pack.yaml"), "pack: a\nkinds: [thing]\npivot:\n  thing: [book]\n");
        assertThatThrownBy(() -> l.properties(l.load(dir, List.of("a")))).hasMessageContaining("pivot of thing is true, false or a mapping");
    }

    @Test
    void conflictsAndMissingPacksAreErrors(@TempDir Path dir) throws Exception {
        for (String n : new String[] {"a", "b"}) {
            Files.createDirectories(dir.resolve(n));
            Files.writeString(dir.resolve(n).resolve("pack.yaml"), "pack: " + n + "\nmnemonics:\n  TRD: { kind: " + n + " }\n");
        }
        PackLoader l = new PackLoader();
        assertThatThrownBy(() -> l.properties(l.load(dir, List.of("a", "b")))).hasMessageContaining("both pack 'a' and pack 'b'");
        assertThatThrownBy(() -> l.load(dir, List.of("nope"))).hasMessageContaining("not found");
        assertThatThrownBy(() -> l.load(dir, List.of("../a"))).hasMessageContaining("not found");
    }

    @Test
    void requiredPacksLoadFirstAndBringTheirConnectors(@TempDir Path dir) throws Exception {
        write(dir, "core", "kinds: [counterparty]\nconnectors:\n  core-lake:\n    plugin: delta\n    kinds: [counterparty]\n"
                + "    settings: { root: \"${DRISHTI_DELTA_ROOT:./data/delta}\", domain: core }\n");
        write(dir, "risk", "requires: [core]\nkinds: [var]\nconnectors:\n  risk-lake: { plugin: delta, settings: { domain: risk } }\n"
                + "  core-lake:\n    plugin: delta\n    kinds: [counterparty]\n"
                + "    settings: { root: \"${DRISHTI_DELTA_ROOT:./data/delta}\", domain: core }\nroutes: { var: risk-lake }\n");
        PackLoader l = new PackLoader();
        List<Pack> packs = l.load(dir, List.of("risk"));
        assertThat(packs).extracting(Pack::name).containsExactly("core", "risk");
        var props = l.properties(packs);
        assertThat(props).containsEntry("drishti.sources.connectors.core-lake.plugin", "delta")
                .containsEntry("drishti.sources.connectors.core-lake.kinds[0]", "counterparty")
                .containsEntry("drishti.sources.connectors.core-lake.settings.domain", "core")
                .containsEntry("drishti.sources.connectors.core-lake.settings.root", "${DRISHTI_DELTA_ROOT:./data/delta}")
                .containsEntry("drishti.sources.connectors.risk-lake.settings.domain", "risk")
                .containsEntry("drishti.sources.routes.var", "risk-lake")
                .containsEntry("drishti.packs.loaded", "core,risk");
        write(dir, "clash", "connectors:\n  core-lake: { plugin: delta, settings: { domain: other } }\n");
        assertThatThrownBy(() -> l.properties(l.load(dir, List.of("risk", "clash")))).hasMessageContaining("do not inherit from each other");
        write(dir, "a", "requires: [b]\n");
        write(dir, "b", "requires: [a]\n");
        assertThatThrownBy(() -> l.load(dir, List.of("a"))).hasMessageContaining("cycle: a -> b -> a");
        write(dir, "c", "requires: [ghost]\n");
        assertThatThrownBy(() -> l.load(dir, List.of("c"))).hasMessageContaining("required by 'c'");
    }

    private static void write(Path dir, String name, String body) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve(name));
        java.nio.file.Files.writeString(dir.resolve(name).resolve("pack.yaml"), "pack: " + name + "\nversion: 1.0.0\n" + body);
    }

    @Test
    void aChildInheritsItsParentsAndTheRightmostParentWins(@TempDir Path dir) throws Exception {
        write(dir, "base", "kinds: [curve]\nmnemonics: { CRV: { kind: curve, label: Curve } }\nroles: { viewer: { kinds: [curve] } }\n"
                + "graph: { badges: { curve: \"'base'\" } }\n");
        write(dir, "left", "extends: [base]\nkinds: [trade]\nmnemonics: { TRD: { kind: trade, label: Left trade } }\n"
                + "roles: { trader: { kinds: [trade] } }\ngraph: { badges: { curve: \"'left'\" } }\n");
        write(dir, "right", "extends: [base]\nkinds: [quote]\nmnemonics: { TRD: { kind: trade, label: Right trade } }\n"
                + "roles: { trader: { kinds: [trade, quote] } }\n");
        write(dir, "child", "extends: [left, right]\nkinds: [var]\nroles: { viewer: { kinds: [curve, trade, var] } }\n");
        PackLoader l = new PackLoader();
        List<Pack> packs = l.load(dir, List.of("child"));
        assertThat(packs).extracting(Pack::name).containsExactlyInAnyOrder("base", "left", "right", "child");
        assertThat(PackLoader.lineage(packs).linearisation("child")).containsExactly("child", "right", "left", "base");
        Map<String, Object> p = l.properties(packs);
        assertThat(p).containsEntry("drishti.commands.mnemonics.TRD.label", "Right trade")            // rightmost parent wins
                .containsEntry("drishti.security.roles.trader.kinds[1]", "quote")
                .containsEntry("drishti.security.roles.viewer.kinds[2]", "var")                        // the child wins over all
                .containsEntry("drishti.graph.badges.curve", "'left'")                                // only left redefines it: left over base
                .containsEntry("drishti.commands.mnemonics.CRV.kind", "curve");                        // inherited untouched
        assertThat(p.values()).anyMatch(v -> v.equals("mnemonic TRD: right overrides left"))
                .anyMatch(v -> v.equals("role viewer: child overrides base"));
    }

    @Test
    void unrelatedPacksStillMayNotClashAndKindsAreNeverOverridden(@TempDir Path dir) throws Exception {
        write(dir, "a", "mnemonics: { TRD: { kind: trade } }\n");
        write(dir, "b", "mnemonics: { TRD: { kind: deal } }\n");
        PackLoader l = new PackLoader();
        assertThatThrownBy(() -> l.properties(l.load(dir, List.of("a", "b")))).hasMessageContaining("do not inherit from each other");
        write(dir, "owner", "kinds: [trade]\n");
        write(dir, "thief", "extends: [owner]\nkinds: [trade]\n");
        assertThatThrownBy(() -> l.properties(l.load(dir, List.of("thief")))).hasMessageContaining("kind trade");
    }

    @Test
    void inconsistentOrdersAndCyclesAreRefused(@TempDir Path dir) throws Exception {
        write(dir, "x", "kinds: [x]\n");
        write(dir, "y", "kinds: [y]\n");
        write(dir, "xy", "extends: [x, y]\n");
        write(dir, "yx", "extends: [y, x]\n");
        write(dir, "both", "extends: [xy, yx]\n");
        PackLoader l = new PackLoader();
        assertThatThrownBy(() -> l.properties(l.load(dir, List.of("both")))).hasMessageContaining("inconsistent");
        write(dir, "p", "extends: [q]\n");
        write(dir, "q", "extends: [p]\n");
        assertThatThrownBy(() -> l.load(dir, List.of("p"))).hasMessageContaining("cycle");
    }
}
