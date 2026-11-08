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
package com.ash.drishti.engine.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The view model's phone (docs/guides/PACK_DEVELOPER_GUIDE.md 5): each panel's tier with the layer that set it, the order, the default, the summary line. */
class PhoneViewTest {

    private static Sutra parse(String text) {
        return new SutraParser().parse(text, "t.sutra.yaml", "x");
    }

    @Test
    void thePanelsTiersTheBlockAndTheSummaryAreCarried() {
        Sutra s = parse("""
                rachana: 1
                sutra: nset
                version: 1
                match: { kind: netting-set }
                phone: { order: [b], default: more, summary: "Net {$.MTM}" }
                panels:
                  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ] }
                  - { id: b, kind: kv, phone: must, columns: [ { label: A, bind: $.A } ] }
                """);
        Map<String, Object> phone = PhoneView.of(s, "Net 12");
        assertThat(phone).containsEntry("order", List.of("b")).containsEntry("default", "more").containsEntry("summary", "Net 12");
        assertThat(phone.get("panels")).isEqualTo(Map.of("b", Map.of("tier", "must", "from", "sutra")));
    }

    @Test
    void aSutraThatSaysNothingAboutThePhoneCarriesNothing() {
        Sutra s = parse("rachana: 1\nsutra: plain\nversion: 1\nmatch: { kind: book }\npanels:\n  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ] }\n");
        assertThat(PhoneView.of(s, null)).isNull();
    }
}
