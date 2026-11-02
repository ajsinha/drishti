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
import com.ash.drishti.rachana.model.Sutra;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The phone display's grammar (MOBILE_DISPLAY.md section 5): a panel's tier, its compact options, the screen's block, a fragment's doc.phone. */
class PhoneReaderTest {

    @TempDir Path dir;

    static String sutra(String top, String panels) {
        return "rachana: 1\nsutra: nset\nversion: 1\nmatch: { kind: netting-set }\ntitle: { id: $.ID }\n" + top + "panels:\n" + panels;
    }

    static final String PANELS = """
              - { id: header, kind: kv, columns: [ { label: MTM, bind: $.MTM } ], phone: must }
              - id: trades
                kind: table
                rows: $.TRADES
                columns: [ { label: Trade, bind: "@.ID" }, { label: MTM, bind: "@.MTM" } ]
                phone: { tier: glance, rows: 5, columns: [Trade, MTM], sort: -MTM }
              - { id: notes, kind: markdown, text: hi, phone: desk }
              - { id: built, kind: provenance }
            """;

    @AfterEach
    void empty() {
        SutraBlocks.load(List.of());
    }

    @Test
    void aPanelsTierAndCompactOptionsAndTheScreensBlockAreRead() {
        Sutra s = new SutraParser().parse(sutra("phone:\n  order: [trades, header]\n  default: more\n  summary: \"Net {$.MTM}\"\n", PANELS), "nset.v1.sutra.yaml", "x");
        assertThat(s.panels().get(0).options().get("phone")).isEqualTo(Map.of("tier", "must", "from", "sutra"));
        assertThat(s.panels().get(1).options().get("phone")).isEqualTo(Map.of("tier", "glance", "from", "sutra", "rows", 5,
                "columns", List.of("Trade", "MTM"), "sort", "-MTM"));
        assertThat(s.panels().get(3).options()).doesNotContainKey("phone");
        assertThat(s.phone().order()).containsExactly("trades", "header");
        assertThat(s.phone().defaultTier()).isEqualTo("more");
        assertThat(s.phone().summary()).isEqualTo("Net {$.MTM}");
    }

    @Test
    void aWordThatIsNotATierOrAnOptionThatDoesNotFitTheKindIsDrs2059() {
        assertThatThrownBy(() -> new SutraParser().parse(sutra("", "  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ], phone: always }\n"), "a.yaml", "x"))
                .isInstanceOf(SutraException.class)
                .satisfies(e -> assertThat(((SutraException) e).problems()).extracting(SutraProblem::code).contains("DRS-2059"))
                .hasMessageContaining("not a phone tier");
        assertThatThrownBy(() -> new SutraParser().parse(sutra("", "  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ], phone: { tier: glance, rows: 5 } }\n"), "a.yaml", "x"))
                .isInstanceOf(SutraException.class).hasMessageContaining("'rows' is a compact option of");
        assertThatThrownBy(() -> new SutraParser().parse(sutra("phone: { order: [notes] }\n", PANELS), "a.yaml", "x"))
                .isInstanceOf(SutraException.class).hasMessageContaining("a desk panel; the order arranges only must and glance panels");
        assertThatThrownBy(() -> new SutraParser().parse(sutra("phone: { order: [nothing] }\n", PANELS), "a.yaml", "x"))
                .isInstanceOf(SutraException.class).hasMessageContaining("not a panel of this screen");
    }

    @Test
    void aFragmentsDocPhoneIsPutOnItsPanelsAndAUseOverridesIt() throws Exception {
        Files.writeString(dir.resolve("ladder.v1.block.yaml"), """
                rachana: 1
                block: ladder
                version: 1
                description: Flows.
                params: { id: flows }
                doc:
                  phone: { tier: more }
                panels:
                  - { id: "%{id}", kind: ladder, rows: $.FLOWS, columns: [ { label: Date, bind: "@.D" } ] }
                """);
        assertThat(SutraBlocks.load(List.of(dir))).isEmpty();
        Sutra s = new SutraParser().parse(sutra("", "  - { use: ladder@1 }\n  - { use: ladder@1, with: { id: other }, phone: glance }\n"), "a.yaml", "x");
        assertThat(s.panels().get(0).options().get("phone")).isEqualTo(Map.of("tier", "more", "from", "fragment"));
        assertThat(s.panels().get(1).options().get("phone")).isEqualTo(Map.of("tier", "glance", "from", "sutra"));
    }
}
