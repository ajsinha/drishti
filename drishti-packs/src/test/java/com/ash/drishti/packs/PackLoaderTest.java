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
}
