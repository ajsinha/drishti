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
import org.junit.jupiter.api.Test;

/**
 * Any two packs shipped in {@code config/packs} load together without a role or mnemonic declared differently by both.
 * Some packs are alternatives that define the same kind (trading and finance both define {@code trade}): a site picks one,
 * so a kind clash between two packs is allowed here; a role clash is always a mistake, since roles are shared by name.
 */
class AllShippedPacksTest {

    @Test
    void noTwoShippedPacksDeclareTheSameRoleDifferently() {
        Path dir = Path.of("..", "config", "packs");
        assertThat(Files.isDirectory(dir)).as("the shipped packs folder").isTrue();
        List<String> all = PackLoader.folders(List.of(dir));
        assertThat(all).hasSizeGreaterThan(10);
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
