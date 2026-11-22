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
package com.ash.drishti.enginehost;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.host.EngineFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** Binding by contract (engine specification 10.2): the site's file first, then exactly one conforming kind; several or none in words. */
class InputBinderTest {

    private static Descriptor groupTotals() throws Exception {
        Path jar = Fixtures.sampleJar();
        try (JarFile j = new JarFile(jar.toFile())) {
            String text = new String(j.getInputStream(j.getEntry("META-INF/drishti-engine/group-totals.engine.yaml")).readAllBytes(), StandardCharsets.UTF_8);
            return Descriptors.parse(text);
        }
    }

    private static EngineFile file(String inputs) throws Exception {
        return EngineFile.read("group-totals", "enabled: true\n" + inputs + "access: { visibility: row-rules }\n");
    }

    private static KindContracts conforming(String... kindsAndPacks) {
        Map<String, Map<String, Object>> manifests = new java.util.TreeMap<>();
        for (int i = 0; i < kindsAndPacks.length; i += 2) {
            manifests.computeIfAbsent(kindsAndPacks[i + 1], p -> new java.util.TreeMap<>(Map.of("kinds", new java.util.TreeMap<String, Object>())));
            @SuppressWarnings("unchecked")
            Map<String, Object> kinds = (Map<String, Object>) manifests.get(kindsAndPacks[i + 1]).get("kinds");
            kinds.put(kindsAndPacks[i], Map.of("conforms", List.of("sample/item@1")));
        }
        return KindContracts.of(manifests);
    }

    @Test
    void theSitesFileWinsWithItsColumnMap() throws Exception {
        InputBinder.Binding b = InputBinder.bind(groupTotals(), file("inputs:\n  items: { kinds: [ stock ], columns: { GROUP: TT_GROUP } }\n"),
                conforming("item", "shop"), k -> true);
        assertThat(b.ok()).isTrue();
        InputBinder.Bound items = b.inputs().get("items");
        assertThat(items.rule()).isEqualTo(1);
        assertThat(items.kinds()).containsExactly("stock");
        assertThat(items.column("GROUP")).isEqualTo("TT_GROUP");
        assertThat(items.column("QTY")).isEqualTo("QTY");
    }

    @Test
    void exactlyOneConformingKindIsBound() throws Exception {
        InputBinder.Binding b = InputBinder.bind(groupTotals(), file(""), conforming("item", "shop"), k -> true);
        assertThat(b.inputs().get("items").rule()).isEqualTo(2);
        assertThat(b.inputs().get("items").how()).contains("item (shop)");
    }

    @Test
    void severalConformingKindsBlockAndAreListedTheHostNeverGuesses() throws Exception {
        InputBinder.Binding b = InputBinder.bind(groupTotals(), file(""), conforming("item", "shop", "stock-item", "warehouse"), k -> true);
        assertThat(b.ok()).isFalse();
        assertThat(b.problems()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("ENG-2012");
            assertThat(p.words()).contains("item (shop)").contains("stock-item (warehouse)").contains("inputs.items.kinds");
        });
    }

    @Test
    void noConformingKindForARequiredInputBlocks() throws Exception {
        InputBinder.Binding b = InputBinder.bind(groupTotals(), file(""), conforming("item", "shop"), k -> false);
        assertThat(b.problems()).singleElement().satisfies(p -> assertThat(p.code()).isEqualTo("ENG-2011"));
    }

    @Test
    void aContractsMinorIsReadAsItsMajor() {
        assertThat(KindContracts.normal("risk/position@1.2")).isEqualTo("risk/position@1");
        assertThat(conforming("item", "shop").conforming("sample/item@1.0")).containsOnlyKeys("item");
        assertThat(Set.copyOf(conforming("item", "shop").contracts())).containsExactly("sample/item@1");
    }
}
