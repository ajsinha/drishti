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
package com.ash.drishti.module.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The content modules of the distributions (docs/architecture/DISTRIBUTIONS.md): {@code modules/market-data}, {@code modules/capital-markets}
 * and {@code modules/economy}, each packed by the module tool from its folder and the repository content its {@code content.yaml} names,
 * and each passing the fifteen conformance checks.
 */
class ContentModulesTest {

    static final Path MODULES = Path.of("../modules");

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"market-data", "capital-markets", "economy"})
    void eachContentModuleIsPackedByTheToolAndPassesTheFifteenChecks(String id) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
        Path folder = MODULES.resolve(id);
        assertThat(ModuleTool.run(new String[] {"pack", folder.toString(), "--out", tmp.toString()}, o, e)).as(err.toString()).isZero();
        Path dmod = tmp.resolve(id + "-1.0.0.dmod");
        assertThat(ModuleTool.run(new String[] {"check", dmod.toString(), "--cycles", "2"}, o, e)).as(out + "\n" + err).isZero();
        assertThat(out.toString()).contains("all 15 conformance checks passed");

        Path dir = tmp.resolve("unpacked");
        Dmod.unpack(dmod, dir);
        DmodManifest m = ModuleAssembler.manifest(dir);
        assertThat(m.java()).isNull();                                                            // content only
        assertThat(dir.resolve(ModuleAssembler.CONTENT)).doesNotExist();                          // the list itself is not packed
        for (String pack : m.kinds().keySet()) {
            String yaml = Files.readString(dir.resolve("packs").resolve(pack).resolve("pack.yaml"), StandardCharsets.UTF_8);
            assertThat(yaml).as(pack).doesNotContainPattern("(?m)^connectors:").contains("mnemonics:");
        }
        if ("economy".equals(id)) {
            assertThat(dir.resolve("packs/world-economy/samples")).doesNotExist();               // the IMF figures stay internal
        }
    }

    @Test
    void bankingIsNotACarrierOfTheMarketDataPackAnyMore() throws Exception {
        String banking = Files.readString(MODULES.resolve("banking/banking-service/src/main/resources/META-INF/drishti/module.yaml"),
                StandardCharsets.UTF_8);
        assertThat(banking).containsPattern("requires: \\[market-data\\]");
        assertThat(ModuleAssembler.manifest(MODULES.resolve("market-data")).section("packs").toString()).contains("market-data");
        for (String id : List.of("capital-markets", "economy")) {
            assertThat(ModuleAssembler.manifest(MODULES.resolve(id)).requires()).extracting(DmodManifest.Requirement::id).containsExactly("market-data");
        }
    }

    @Test
    void sectionsOutDropsOnlyTheNamedTopLevelSections() {
        String yaml = "pack: x\nconnectors:\n  a:\n    plugin: delta\n# a note\nmnemonics:\n  X: {kind: x}\n";
        assertThat(ModuleAssembler.withoutSections(yaml, List.of("connectors"))).isEqualTo("pack: x\nmnemonics:\n  X: {kind: x}\n");
    }
}
