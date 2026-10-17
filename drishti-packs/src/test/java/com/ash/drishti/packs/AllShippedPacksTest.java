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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The seven packs shipped in {@code config/packs} load together (the default {@code drishti.packs.enabled}), any two of them load
 * without a role or mnemonic declared differently by both, and every retired example pack in {@code examples/packs} still loads
 * on top of them (through {@code drishti.packs.extra-dirs}).
 */
class AllShippedPacksTest {

    static final Path SHIPPED = Path.of("..", "config", "packs").toAbsolutePath().normalize();
    static final Path EXAMPLES = Path.of("..", "examples", "packs").toAbsolutePath().normalize();

    @Test
    void theShippedPacksAreTheSevenAndLoadTogether() {
        assertThat(PackLoader.folders(List.of(SHIPPED))).containsExactlyInAnyOrder(PackPaths.DEFAULT_ENABLED.split(","));
        PackLoader loader = new PackLoader();
        Map<String, Object> p = loader.properties(loader.load(SHIPPED, List.of(PackPaths.DEFAULT_ENABLED.split(","))));
        assertThat(p).containsEntry("drishti.commands.mnemonics.TRD.kind", "trade").containsEntry("drishti.commands.mnemonics.CPTY.kind", "counterparty")
                .containsEntry("drishti.commands.mnemonics.AGR.kind", "agreement").containsEntry("drishti.commands.mnemonics.ECON.kind", "economy")
                .containsEntry("drishti.commands.mnemonics.JUR.kind", "jurisdiction").containsEntry("drishti.commands.mnemonics.CO.kind", "listed-company")
                .containsEntry("drishti.graph.fields.economy.kind", "economy").containsEntry("drishti.graph.fields.newsId.kind", "news");
    }

    @Test
    void everyExamplePackLoadsOnTopOfTheShippedOnes() {
        List<Path> dirs = PackLoader.dirs(SHIPPED.toString(), null, EXAMPLES.toString());
        List<String> examples = PackLoader.folders(List.of(EXAMPLES));
        assertThat(examples).contains("finance", "genomics", "logistics", "retail-banking", "climate-risk", "operational-risk", "liquidity-risk")
                .doesNotContainAnyElementsOf(PackLoader.folders(List.of(SHIPPED)));
        for (String name : examples) {
            PackLoader loader = new PackLoader();
            assertThat(loader.properties(loader.load(dirs, List.of(name)))).as(name).containsKey("drishti.packs.loaded");
        }
    }

    @Test
    void noTwoShippedPacksDeclareTheSameRoleDifferently() {
        Path dir = SHIPPED;
        assertThat(Files.isDirectory(dir)).as("the shipped packs folder").isTrue();
        List<String> all = PackLoader.folders(List.of(dir));
        assertThat(all).hasSize(7);
        List<String> clashes = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                try {
                    PackLoader loader = new PackLoader();
                    loader.properties(loader.load(dir, List.of(all.get(i), all.get(j))));
                } catch (IllegalStateException e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.startsWith("role ") || m.contains("mnemonic")) {
                        clashes.add(all.get(i) + " + " + all.get(j) + ": " + m);
                    }
                }
            }
        }
        assertThat(clashes).as("role or mnemonic clashes between shipped packs").isEmpty();
    }
}
