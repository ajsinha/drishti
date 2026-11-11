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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shared panel blocks: written once beside the Sutras, used by many, parameters filled, versions chosen, mistakes named (DRS-2048). */
class SutraBlocksTest {

    @TempDir Path dir;

    static final String TICKET = """
            rachana: 1
            block: ticket
            version: 1
            description: A ticket.
            params: { rate-fmt: price4 }
            panels:
              - id: ticket
                kind: kv
                columns:
                  - { label: Price, bind: $.PRICE, fmt: "%{rate-fmt}" }
              - { id: built, kind: provenance, title: How this view was built }
            """;

    static String sutra(String panels) {
        return "rachana: 1\nsutra: deal\nversion: 1\nmatch: { kind: trade, where: \"$.SOURCE_SYSTEM == 'ENDUR' && $.PRODUCT_TYPE == 'Cash'\" }\n"
                + "title: { id: $.TRADE_ID }\npanels:\n" + panels;
    }

    @AfterEach
    void empty() {
        SutraBlocks.load(List.of());
    }

    @Test
    void aUseIsReplacedByTheBlocksPanelsWithItsParametersFilled() throws Exception {
        Files.createDirectories(dir.resolve("common"));
        Files.writeString(dir.resolve("common/ticket.v1.block.yaml"), TICKET);
        Files.writeString(dir.resolve("common/ticket.v2.block.yaml"), TICKET.replace("version: 1", "version: 2").replace("Price", "Price v2"));
        assertThat(SutraBlocks.load(List.of(dir))).isEmpty();
        assertThat(SutraBlocks.library().get("ticket")).containsOnlyKeys(1, 2);

        Sutra s = new SutraParser().parse(sutra("  - { use: ticket@1, with: { rate-fmt: pct4 } }\n  - { id: notes, kind: markdown, text: hi }\n"), "deal.v1.sutra.yaml", "endur");
        assertThat(s.panels()).extracting(Panel::id).containsExactly("ticket", "built", "notes");
        assertThat(s.panels().get(0).columns().get(0).fmt()).isEqualTo("pct4");
        Sutra latest = new SutraParser().parse(sutra("  - { use: ticket }\n"), "deal.v1.sutra.yaml", "endur");
        assertThat(latest.panels().get(0).columns().get(0).label()).isEqualTo("Price v2");                // no version: the newest
        assertThat(latest.panels().get(0).columns().get(0).fmt()).isEqualTo("price4");                   // the default
    }

    @Test
    void anUnknownBlockOrParameterIsDrs2048AndABlockMayNotUseAnother() throws Exception {
        Files.writeString(dir.resolve("ticket.v1.block.yaml"), TICKET);
        SutraBlocks.load(List.of(dir));
        assertThatThrownBy(() -> new SutraParser().parse(sutra("  - { use: nothing@1 }\n"), "deal.v1.sutra.yaml", "x"))
                .isInstanceOf(SutraException.class).satisfies(e -> assertThat(((SutraException) e).problems())
                        .extracting(SutraProblem::code).contains(SutraBlocks.BLOCK));
        assertThatThrownBy(() -> new SutraParser().parse(sutra("  - { use: ticket@1, with: { colour: red } }\n"), "deal.v1.sutra.yaml", "x"))
                .isInstanceOf(SutraException.class).hasMessageContaining("no parameter 'colour'");
        Files.writeString(dir.resolve("nested.v1.block.yaml"), "rachana: 1\nblock: nested\nversion: 1\npanels:\n  - { use: ticket@1 }\n");
        assertThat(SutraBlocks.load(List.of(dir))).extracting(SutraProblem::message).anyMatch(m -> m.contains("uses another block"));
        Files.writeString(dir.resolve("wrong.v1.block.yaml"), TICKET);
        assertThat(SutraBlocks.load(List.of(dir))).extracting(SutraProblem::message).anyMatch(m -> m.contains("name it ticket.v1.block.yaml"));
    }
}
