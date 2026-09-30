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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SutraParserTest {

    static final Path SUTRAS = Path.of("..", "sutras");
    private final SutraParser parser = new SutraParser();

    private Sutra load(String rel) throws Exception {
        Path p = SUTRAS.resolve(rel);
        return parser.parse(Files.readString(p), p.getFileName().toString(), p.getParent().getFileName().toString());
    }

    @Test
    void referenceSutrasParse() throws Exception {
        Sutra irs = load("rates/irs-vanilla.v3.yaml");
        assertThat(irs.id()).isEqualTo("irs-vanilla@3");
        assertThat(irs.domain()).isEqualTo("rates");
        assertThat(irs.strip()).hasSize(8);
        assertThat(irs.panels()).extracting(p -> p.id()).containsExactly("legs", "cashflows", "leg2", "built", "curve", "refs", "dv01");
        assertThat(irs.panels().get(0).body().kind()).isEqualTo(PanelKind.KV);
        assertThat(irs.panels().get(4).area()).isEqualTo(Area.RIGHT);
        assertThat(irs.keys()).containsKeys("F7", "F8", "F9");
        for (String f : List.of("fx/fx-swap.v2.yaml", "commodities/listed-future.v1.yaml", "credit/netting-set.v1.yaml")) {
            assertThat(load(f).panels()).isNotEmpty();
        }
        assertThat(load("credit/netting-set.v1.yaml").panels().get(0).options().get("series")).isInstanceOf(List.class);
    }

    @Test
    void reportsEveryProblemWithLineAndColumn() {
        String yaml = """
                sutra: Bad_Name
                version: 0
                match: { kind: trade }
                colour: red
                strip:
                  - { label: X }
                panels:
                  - { id: a, kind: table, key: F2 }
                  - { id: a, kind: pie }
                  - { id: b, kind: kv, key: F2, wobble: 1 }
                """;
        assertThatThrownBy(() -> parser.parse(yaml, "bad.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class, e -> {
                    assertThat(e.problems()).extracting(SutraProblem::code).contains(
                            "DRS-2020", "DRS-2011", "DRS-2010", "DRS-2022", "DRS-2021", "DRS-2023", "DRS-2025");
                    assertThat(e.problems()).filteredOn(p -> p.code().equals("DRS-2011"))
                            .first().satisfies(p -> assertThat(p.location().line()).isEqualTo(4));
                });
    }

    @Test
    void yamlSyntaxErrorsHaveAPosition() {
        assertThatThrownBy(() -> parser.parse("sutra: x\n  version: [1,\n", "broken.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class,
                        e -> assertThat(e.problems().get(0).code()).isEqualTo("DRS-2001"));
    }
}
