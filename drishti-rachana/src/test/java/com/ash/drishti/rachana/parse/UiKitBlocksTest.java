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
package com.ash.drishti.rachana.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Drishti UI kit (SUTRA_NEXT.md): its fragments load with no pack at all; a pack's own fragments and blocks are namespaced by the
 * pack ({@code demo/deal-figure@1}) and answer to their plain name too; a pack fragment named like a kit fragment loses the plain
 * name to the kit, with a warning, and stays reachable qualified.
 */
class UiKitBlocksTest {

    static final Path KIT = Path.of("../config/ui-kit");

    @TempDir Path dir;

    @AfterEach
    void noKit() {
        SutraBlocks.kit(null);
        SutraBlocks.load(List.of());
    }

    static String sutra(String panels) {
        return "rachana: 1\nsutra: probe\nversion: 1\nmatch: { kind: ir-curve }\ntitle: { id: $.CURVE_ID }\npanels:\n" + panels;
    }

    /** A pack folder (pack.yaml naming it) whose sutras/ holds a copy of the kit's key-figure renamed, and one under the kit's own name. */
    private Path pack() throws Exception {
        Path pack = dir.resolve("demo-folder");
        Path lib = Files.createDirectories(pack.resolve("sutras").resolve("common"));
        Files.writeString(pack.resolve("pack.yaml"), "name: demo\ntitle: Demo\n");
        String keyFigure = Files.readString(KIT.resolve("fragments/key-figure.v1.fragment.yaml"));
        Files.writeString(lib.resolve("deal-figure.v1.fragment.yaml"), keyFigure.replace("key-figure", "deal-figure"));
        Files.writeString(lib.resolve("key-figure.v1.fragment.yaml"), keyFigure.replace("title: Key figure", "title: Pack figure")
                .replace("default: Key figure", "default: Pack figure"));
        return pack.resolve("sutras");
    }

    @Test
    void theKitLoadsWithNoPack() {
        SutraBlocks.kit(KIT);
        assertThat(SutraBlocks.load(List.of())).isEmpty();
        assertThat(SutraBlocks.blocks()).hasSizeGreaterThanOrEqualTo(14).allMatch(SutraBlocks::fromKit).allMatch(SutraBlocks.Block::fragment);
        Sutra s = new SutraParser().parse(sutra("  - { use: series-line@1 }\n  - { use: source-footer@1 }\n"), "probe.v1.sutra.yaml", "x");
        assertThat(s.panels()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(SutraBlocks.clashes()).isEmpty();
    }

    @Test
    void aPackFragmentIsNamespacedByItsPackAndAClashWithTheKitWarnsAndTheKitWins() throws Exception {
        SutraBlocks.kit(KIT);
        assertThat(SutraBlocks.packOf(pack())).isEqualTo("demo");
        assertThat(SutraBlocks.load(List.of(dir.resolve("demo-folder/sutras")))).isEmpty();

        assertThat(SutraBlocks.find("demo/deal-figure@1")).get().extracting(SutraBlocks.Block::name).isEqualTo("demo/deal-figure");
        assertThat(SutraBlocks.find("deal-figure@1")).as("its plain name too, the kit having none").isPresent();
        assertThat(SutraBlocks.find("key-figure@1")).get().matches(SutraBlocks::fromKit, "the kit's key-figure");
        assertThat(SutraBlocks.find("demo/key-figure@1")).get().matches(b -> !SutraBlocks.fromKit(b), "the pack's, qualified");
        assertThat(SutraBlocks.clashes()).extracting(SutraProblem::message).singleElement().asString()
                .contains("pack demo's key-figure").contains("the kit's wins").contains("demo/key-figure@1");

        Sutra s = new SutraParser().parse(sutra("""
                  - { use: key-figure@1, with: { id: a } }
                  - { use: demo/key-figure@1, with: { id: b } }
                  - { use: demo/deal-figure@1, with: { id: c } }
                """), "probe.v1.sutra.yaml", "x");
        assertThat(s.panels()).extracting(Panel::title).containsExactly("Key figure", "Pack figure", "Key figure");
        assertThat(SutraBlocks.blocks()).extracting(SutraBlocks.Block::name).doesNotHaveDuplicates()
                .contains("demo/deal-figure", "demo/key-figure", "key-figure");
    }

    @Test
    void aCompositeMayUseAQualifiedFragment() throws Exception {
        SutraBlocks.kit(KIT);
        Path sutras = pack();
        String footer = Files.readString(KIT.resolve("fragments/source-footer.v1.fragment.yaml"));
        assertThat(footer).contains("use: data-provenance@1");
        Files.writeString(sutras.resolve("common/demo-footer.v1.fragment.yaml"), footer.replace("source-footer", "demo-footer")
                .replace("linked-entities@1", "demo/deal-figure@1"));
        assertThat(SutraBlocks.load(List.of(sutras))).isEmpty();
        assertThat(SutraBlocks.find("demo/demo-footer@1")).isPresent();
        Sutra s = new SutraParser().parse(sutra("  - { use: demo/demo-footer@1 }\n"), "probe.v1.sutra.yaml", "x");
        assertThat(s.panels()).extracting(Panel::id).containsExactly("source", "built", "figure");
    }
}
