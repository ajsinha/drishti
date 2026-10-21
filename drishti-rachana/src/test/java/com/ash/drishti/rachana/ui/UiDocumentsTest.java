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
package com.ash.drishti.rachana.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.ui.BoardDoc.TransformKind;
import com.ash.drishti.rachana.ui.PinSpec.PinRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

class UiDocumentsTest {

    @Test
    void yamlAndJsonOfTheSameBoardGiveTheSameModel() {
        BoardDoc yaml = UiDocuments.readBoard(UiFixtures.text("book-trades.board.yaml"));
        BoardDoc json = UiDocuments.readBoard(UiFixtures.text("book-trades.board.json"));
        assertThat(json).isEqualTo(yaml);
        assertThat(yaml.chips()).containsOnlyKeys("trades", "ticket", "flows");
        assertThat(yaml.chips().get("trades").use()).isEqualTo(new ChipRef("filterable-table", 2));
        assertThat(yaml.chips().get("trades").map().get("rows.measures").attributes()).containsExactly("NOTIONAL", "MTM_USD");
        assertThat(yaml.traces()).hasSize(3);
        assertThat(yaml.traces().get(1).transform().kind()).isEqualTo(TransformKind.FILTER_ON);
        assertThat(yaml.layout().desktop().areas()).hasSize(3);
        assertThat(yaml.layout().phone().collapsed()).containsExactly("flows");
        assertThat(yaml.params().get("book").type().of()).isEqualTo("trading/book");
    }

    @Test
    void yamlAndJsonOfTheSameChipGiveTheSameModel() throws Exception {
        String yaml = UiFixtures.text("filterable-table.v2.chip.yaml");
        String json = new ObjectMapper().writeValueAsString(new ObjectMapper(new YAMLFactory()).readTree(yaml));
        ChipDoc a = UiDocuments.readChip(yaml);
        assertThat(UiDocuments.readChip(json)).isEqualTo(a);
        assertThat(a.ref()).hasToString("filterable-table@2");
        assertThat(a.inPins().get("rows").role()).isEqualTo(PinRole.DATA);
        assertThat(a.inPins().get("rows").shape().get("measures").many()).isTrue();
        assertThat(a.inPins().get("rows").shape().get("measures").max()).isEqualTo(6);
        assertThat(a.inPins().get("page-size").type().max()).isEqualTo(200.0);
        assertThat(a.outPins().get("selected").ofPin()).isEqualTo("rows.id");
        assertThat(a.manual().examples()).hasSize(1);
        assertThat(a.compatibleWith()).containsExactly(1);
    }

    @Test
    void everyReadingProblemIsListedWithItsPath() {
        UiDocuments.Read<BoardDoc> r = UiDocuments.parseBoard("""
                rachana: 2
                board: Bad_Name
                colour: red
                chips:
                  t: { use: filterable-table@2, supply: { connector: a/b, model: m } }
                traces:
                  - { from: t.selected }
                """);
        assertThat(r.problems()).extracting(UiProblem::rule).containsOnly("BC-01");
        assertThat(r.problems()).extracting(UiProblem::path).contains("rachana", "board", "colour", "chips.t.supply", "traces[0]");
    }

    @Test
    void anUnreadableDocumentIsDrs2070() {
        assertThatThrownBy(() -> UiDocuments.readBoard("chips: [unclosed"))
                .isInstanceOf(UiDocumentException.class)
                .satisfies(e -> assertThat(((UiDocumentException) e).errorCode()).isEqualTo(ErrorCode.UI_DOCUMENT_INVALID));
        assertThat(ErrorCode.UI_DOCUMENT_INVALID.code()).isEqualTo("DRS-2070");
        assertThat(ErrorCode.UI_RENDER_ENDED.code()).isEqualTo("DRS-2079");
    }

    @Test
    void aChipWithAnUnknownPinTypeIsRefused() {
        UiDocuments.Read<ChipDoc> r = UiDocuments.parseChip("""
                rachana: 1
                chip: x
                version: 1
                title: X
                category: c
                manual: { purpose: p }
                pins: { in: { a: { role: data, type: table } }, out: {} }
                body: { panels: [] }
                """);
        assertThat(r.problems()).singleElement().satisfies(p -> {
            assertThat(p.path()).isEqualTo("pins.in.a.type");
            assertThat(p.message()).contains("unknown type 'table'");
        });
    }
}
