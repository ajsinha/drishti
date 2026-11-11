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

/** The view model's layout (RACHANA_REFERENCE.md 13): the Sutra's layout and each panel's place, main and right mapped onto the areas. */
class ScreenGridTest {

    private static Sutra parse(String text) {
        return new SutraParser().parse(text, "t.sutra.yaml", "x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachPanelIsPlacedInAnAreaOfThePicture() {
        Sutra s = parse("""
                rachana: 1
                sutra: book-desk
                version: 1
                match: { kind: book }
                layout:
                  areas: ["head head", "body side"]
                  columns: [8, 4]
                  split: { between: [body, side] }
                panels:
                  - { id: a, kind: kv, area: head, columns: [ { label: A, bind: $.A } ] }
                  - { id: b, kind: kv, columns: [ { label: A, bind: $.A } ] }
                  - { id: c, kind: kv, area: right, order: 3, collapsible: true, columns: [ { label: A, bind: $.A } ] }
                """);
        Map<String, Object> grid = ScreenGrid.of(s);
        assertThat(grid).containsEntry("areas", List.of("head head", "body side")).containsEntry("columns", List.of(8, 4))
                .containsEntry("arrange", false);
        Map<String, Map<String, Object>> place = (Map<String, Map<String, Object>>) grid.get("place");
        assertThat(place.get("a")).containsEntry("area", "head");
        assertThat(place.get("b")).containsEntry("area", "head");          // main: the first area of the picture
        assertThat(place.get("c")).containsEntry("area", "side").containsEntry("order", 3).containsEntry("collapsible", true);
        assertThat((Map<String, Object>) grid.get("split")).containsEntry("between", List.of("body", "side")).containsEntry("remember", "user");
    }

    @Test
    void aSutraWithoutALayoutHasNone() {
        assertThat(ScreenGrid.of(parse("rachana: 1\nsutra: plain\nversion: 1\nmatch: { kind: book }\npanels:\n  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ] }\n")))
                .isNull();
    }
}
