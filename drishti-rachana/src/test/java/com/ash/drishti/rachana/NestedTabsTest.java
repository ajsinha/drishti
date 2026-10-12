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

import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code panels} of a {@code tabs} panel: what a tab may hold, the problems, and the Sutra written back. */
class NestedTabsTest {

    private final SutraParser parser = new SutraParser();

    private static String sutra(String panels) {
        return """
                rachana: 1
                sutra: deep-trade
                version: 1
                match: { kind: trade }
                panels:
                  - id: legs
                    kind: tabs
                    each: $.legs
                    tabTitle: "'Leg ' + @.leg"
                    panels:
                """ + panels.indent(6);
    }

    private List<String> problems(String panels) {
        try {
            parser.parse(sutra(panels), "t.sutra.yaml", "x");
            return List.of();
        } catch (SutraException e) {
            return e.problems().stream().map(p -> p.code() + " " + p.message()).toList();
        }
    }

    static final String GOOD = """
            - id: terms
              kind: kv
              columns:
                - { label: Index, bind: "@.index" }
            - id: flows
              kind: table
              rows: "@.cashflows"
              children: "@.fixings"
              expand: 1
              columns:
                - { label: Pay date, bind: "@.payDate", fmt: date }
                - { label: Rate, bind: "@.rate", fmt: pct4 }
            """;

    @Test
    void aTabsPanelHoldsPanelsReadAgainstTheTabsElement() {
        Sutra s = parser.parse(sutra(GOOD), "t.sutra.yaml", "x");
        Panel legs = s.panels().get(0);
        assertThat(legs.parts()).extracting(Panel::id).containsExactly("terms", "flows");
        assertThat(legs.parts().get(1).option("rows")).contains("@.cashflows");
        assertThat(legs.parts().get(1).option("children")).contains("@.fixings");
        assertThat(legs.parts().get(1).columns()).hasSize(2);
    }

    @Test
    void theSutraWrittenBackParsesToTheSamePanels() {
        Sutra s = parser.parse(sutra(GOOD), "t.sutra.yaml", "x");
        String written = new SutraWriter().write(s, "deep-trade", 1);
        Sutra again = parser.parse(written, "t.sutra.yaml", "x");
        assertThat(again.panels().get(0).parts()).extracting(Panel::id).containsExactly("terms", "flows");
        assertThat(again.panels().get(0).parts().get(1).option("children")).contains("@.fixings");
    }

    @Test
    void anExpressionInsideATabIsCheckedLikeAnyOther() {
        Sutra s = parser.parse(sutra("""
                - id: terms
                  kind: kv
                  columns:
                    - { label: Index, bind: "@.index +" }
                """), "t.sutra.yaml", "x");
        assertThat(new SutraExpressions(new com.ash.drishti.rachana.el.ElCompiler()).check(s)).anyMatch(p -> p.code().equals("DRS-2101"));
    }

    @Test
    void aPanelInATabNeedsAnIdAndAKindATabCanHold() {
        assertThat(problems("- { kind: kv }\n")).anyMatch(p -> p.startsWith("DRS-2010"));
        assertThat(problems("- { id: curve, kind: line, rows: \"@.points\", x: t, y: v }\n")).anyMatch(p -> p.startsWith("DRS-2023") && p.contains("cannot sit inside a tab"));
        assertThat(problems("- { id: more, kind: tabs, each: \"@.x\" }\n")).anyMatch(p -> p.startsWith("DRS-2023"));
    }

    @Test
    void idsAreUniqueWithinATabAndSizesAndPivotsBelongToTopLevelPanels() {
        assertThat(problems("""
                - { id: a, kind: kv }
                - { id: a, kind: kv }
                """)).anyMatch(p -> p.startsWith("DRS-2024"));
        assertThat(problems("- { id: a, kind: kv, span: 6 }\n")).anyMatch(p -> p.startsWith("DRS-2030"));
        assertThat(problems("- { id: a, kind: table, rows: \"@.cashflows\", pivot: true }\n")).anyMatch(p -> p.contains("cannot have a Pivot tab"));
    }

    @Test
    void onlyTabsTakePanels() {
        try {
            parser.parse("""
                    rachana: 1
                    sutra: wrong
                    version: 1
                    match: { kind: trade }
                    panels:
                      - id: terms
                        kind: kv
                        panels:
                          - { id: a, kind: kv }
                    """, "t.sutra.yaml", "x");
            throw new AssertionError("expected DRS-2023");
        } catch (SutraException e) {
            assertThat(e.problems()).anyMatch(p -> p.code().equals("DRS-2023") && p.message().contains("only 'tabs'"));
        }
    }

    @Test
    void theWorkbenchSchemaOffersPanelsOnEveryPanel() {
        var schema = RachanaSchema.build(List.of("date"), List.of("trade"));
        assertThat(schema.toString()).contains("panels=").contains("tabs only: panels shown inside each tab");
    }

    @SuppressWarnings("unchecked")
    @Test
    void theEditorsOptionListForTabsNamesBodyAndPanelsSoItDoesNotCallThemUnknown() {
        var schema = RachanaSchema.build(List.of("date"), List.of("trade"));
        var panel = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) schema.get("$defs")).get("panel");
        var tabs = ((List<java.util.Map<String, Object>>) panel.get("allOf")).stream()
                .map(m -> (java.util.Map<String, Object>) m.get("then"))
                .filter(t -> ((List<String>) t.get("required")).contains("each")).findFirst().orElseThrow();
        assertThat((List<String>) tabs.get("x-rachana-options")).contains("each", "tabTitle", "layout", "body", "panels");
    }
}
