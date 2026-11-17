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

import com.ash.drishti.testroots.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The packs shipped in {@code config/packs} load together (the default {@code drishti.packs.enabled}, plus the opt-in public-data packs), any two of them load
 * without a role or mnemonic declared differently by both, and every retired example pack in {@code examples/packs} still loads
 * on top of them (through {@code drishti.packs.extra-dirs}).
 */
class AllShippedPacksTest {

    static final Path SHIPPED = Path.of(TestRoots.repo(), "config", "packs").toAbsolutePath().normalize();

    /** The repository's resolution with every module on, and the examples' folder as the site's deployed packs (or none). */
    static com.ash.drishti.api.resolution.ConfigResolution repo(Path deployed) {
        Path modules = Path.of(TestRoots.repo(), "modules").toAbsolutePath().normalize();
        return com.ash.drishti.api.resolution.ConfigResolution.build(new com.ash.drishti.api.resolution.ResolutionSettings(SHIPPED,
                deployed == null ? SHIPPED.resolve("no-deployed-packs") : deployed, SHIPPED.resolve("core"), modules, java.util.List.of(),
                com.ash.drishti.api.resolution.ModuleHomes.source(Path.of(TestRoots.repo()))), id -> true);
    }

    static final com.ash.drishti.api.resolution.ConfigResolution REPO = repo(null);
    static final Path EXAMPLES = Path.of(TestRoots.repo(), "examples", "packs").toAbsolutePath().normalize();

    /** Shipped but not on by default: switched on with DRISHTI_PACKS (their READMEs say how). */
    static final List<String> OPT_IN = List.of("us-equities", "gleif", "funds", "compute-demo",
            // the public-sector module's packs, filled by their daily feeds (docs/guides/WORLD_DATA_GUIDE.md)
            "world-development", "central-banks", "national-statistics", "oecd", "official-news", "gdelt-events",
            // the genomics module's showcase packs, read from the demo lake like funds and us-equities (docs/architecture/GENOMICS_SHOWCASE.md)
            "genomics-showcase", "protein-engineering");

    @Test
    void theShippedPacksAreTheDefaultOnesPlusTheOptInOnesAndLoadTogether() {
        assertThat(REPO.problems()).as("the repository resolves cleanly").isEmpty();
        List<String> shipped = REPO.packs().names();
        assertThat(shipped).contains(PackPaths.DEFAULT_ENABLED.split(","));
        assertThat(shipped.stream().filter(n -> !List.of(PackPaths.DEFAULT_ENABLED.split(",")).contains(n)).toList()).isSubsetOf(OPT_IN);
        PackLoader loader = new PackLoader();
        Map<String, Object> p = loader.properties(loader.load(REPO, List.of(PackPaths.DEFAULT_ENABLED.split(","))));
        assertThat(p).containsEntry("drishti.commands.mnemonics.TRD.kind", "trade").containsEntry("drishti.commands.mnemonics.CPTY.kind", "counterparty")
                .containsEntry("drishti.commands.mnemonics.AGR.kind", "agreement").containsEntry("drishti.commands.mnemonics.ECON.kind", "economy")
                .containsEntry("drishti.commands.mnemonics.JUR.kind", "jurisdiction").containsEntry("drishti.commands.mnemonics.CO.kind", "listed-company")
                .containsEntry("drishti.graph.fields.ECONOMY.kind", "economy").containsEntry("drishti.graph.fields.NEWS_ID.kind", "news");
    }

    @Test
    void everyExamplePackLoadsOnTopOfTheShippedOnes() {
        com.ash.drishti.api.resolution.ConfigResolution dirs = repo(EXAMPLES);
        List<String> examples = PackLoader.folders(List.of(EXAMPLES));
        assertThat(examples).contains("finance", "genomics", "logistics", "retail-banking", "climate-risk", "operational-risk", "liquidity-risk")
                .doesNotContainAnyElementsOf(REPO.packs().names());
        for (String name : examples) {
            PackLoader loader = new PackLoader();
            assertThat(loader.properties(loader.load(dirs, List.of(name)))).as(name).containsKey("drishti.packs.loaded");
        }
    }

    @Test
    void noTwoShippedPacksDeclareTheSameRoleDifferently() {
        Path dir = SHIPPED;
        assertThat(Files.isDirectory(dir)).as("the shipped packs folder").isTrue();
        List<String> all = REPO.packs().names();
        assertThat(all).hasSizeGreaterThanOrEqualTo(PackPaths.DEFAULT_ENABLED.split(",").length);
        List<String> clashes = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                try {
                    PackLoader loader = new PackLoader();
                    loader.properties(loader.load(REPO, List.of(all.get(i), all.get(j))));
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
