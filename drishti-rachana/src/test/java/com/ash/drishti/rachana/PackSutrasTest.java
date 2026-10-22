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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.parse.SutraBlocks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every pack's Sutras load with no problems, each pack ALONE with the Drishti UI kit (config/ui-kit, loaded whatever packs are
 * enabled; no pack needs to extend another for its fragments): grammar, expressions and unique name@version.
 */
class PackSutrasTest {

    static final Path KIT = Path.of("..", "config", "ui-kit");

    static Stream<Path> packs() throws Exception {
        try (Stream<Path> s = Files.list(Path.of("..", "config", "packs"))) {
            return s.filter(p -> Files.isDirectory(p.resolve("sutras"))).sorted().toList().stream();
        }
    }

    @AfterEach
    void noKit() {
        SutraBlocks.kit(null);
        SutraBlocks.load(List.of());
    }

    @ParameterizedTest
    @MethodSource("packs")
    void packSutrasLoadWithoutProblemsWithTheUiKitAndNoOtherPack(Path pack) throws Exception {
        long files;
        try (Stream<Path> s = Files.walk(pack.resolve("sutras"))) {
            files = s.filter(p -> p.toString().endsWith(".sutra.yaml")).count();
        }
        SutraBlocks.kit(KIT);
        try (SutraRegistry r = new SutraRegistry(new RachanaProperties(List.of(pack.resolve("sutras").toString()), false,
                null, null, null, null, null, null, null, null), new ElCompiler())) {
            assertThat(r.problems()).as(pack.getFileName() + " problems").isEmpty();
            assertThat(r.all()).hasSize((int) files);
            assertThat(SutraBlocks.find("series-line@1")).as("the kit's fragments load with " + pack.getFileName()).isPresent();
            assertThat(SutraBlocks.clashes()).as(pack.getFileName() + " names no block like a kit fragment").isEmpty();
        }
    }
}
