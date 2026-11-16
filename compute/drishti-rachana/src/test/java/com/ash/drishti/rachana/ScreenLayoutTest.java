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
import com.ash.drishti.rachana.model.ScreenLayout;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A screen's {@code layout:} (RACHANA_REFERENCE.md 13): the grammar, each panel's place, and lint DRS-2059. */
class ScreenLayoutTest {

    private final SutraParser parser = new SutraParser();

    private static String sutra(String layout, String panels) {
        return "rachana: 1\nsutra: book-desk\nversion: 1\nmatch: { kind: book }\n" + (layout.isEmpty() ? "" : "layout:\n" + layout.indent(2))
                + "panels:\n" + panels.indent(2);
    }

    private List<String> problems(String layout, String panels) {
        try {
            parser.parse(sutra(layout, panels), "t.sutra.yaml", "x");
            return List.of();
        } catch (SutraException e) {
            return e.problems().stream().map(p -> p.code() + " " + p.message()).toList();
        }
    }

    static final String AREAS = """
            areas:
              - "header header"
              - "trades detail"
              - "trades limits"
            columns: [7, 5]
            split: { between: [trades, detail], resizable: true, remember: user }
            tabs: [detail]
            sticky: header
            user-can-arrange: true
            phone: [header, detail, trades, limits]
            tablet: { areas: ["header header", "trades trades", "detail limits"], columns: [6, 6] }
            """;

    static final String PANELS = """
            - { id: summary, kind: kv, area: header, columns: [ { label: Book, bind: $.BOOK } ] }
            - { id: trades, kind: table, area: trades, rows: $.TRADES, columns: [ { label: Id, bind: "@.ID" } ], rows-height: 0 }
            - { id: terms, kind: kv, area: detail, order: 2, columns: [ { label: Id, bind: $.ID } ] }
            - { id: flows, kind: kv, area: detail, order: 1, columns: [ { label: Id, bind: $.ID } ] }
            - { id: limits, kind: kv, area: limits, collapsible: true, height: 6, columns: [ { label: Id, bind: $.ID } ] }
            """.replace(", rows-height: 0", "");

    @Test
    void aLayoutWithAreasReadsEveryPart() {
        Sutra s = parser.parse(sutra(AREAS, PANELS), "t.sutra.yaml", "x");
        ScreenLayout l = s.layout();
        assertThat(l.areaNames()).containsExactly("header", "trades", "detail", "limits");
        assertThat(l.columns()).containsExactly(7, 5);
        assertThat(l.split()).isEqualTo(new ScreenLayout.Split("trades", "detail", true, "user"));
        assertThat(l.tabs()).containsExactly("detail");
        assertThat(l.sticky()).isEqualTo("header");
        assertThat(l.userCanArrange()).isTrue();
        assertThat(l.phone()).containsExactly("header", "detail", "trades", "limits");
        assertThat(l.tabletColumns()).containsExactly(6, 6);
        Panel limits = s.panels().get(4);
        assertThat(limits.option(Panel.GRID_AREA)).contains("limits");
        assertThat(limits.height()).contains(6);
        assertThat(limits.options()).containsEntry(Panel.COLLAPSIBLE, true);
        assertThat(s.panels().get(1).option("rows")).contains("$.TRADES");   // rows as text stays the rows a table reads
        assertThat(s.panels().get(2).option(Panel.ORDER)).contains("2");
    }

    @Test
    void aSutraWithoutALayoutFlowsAsBefore() {
        Sutra s = parser.parse(sutra("", "- { id: a, kind: kv, area: right, span: 6, columns: [ { label: A, bind: $.A } ] }"), "t.sutra.yaml", "x");
        assertThat(s.layout()).isNull();
        assertThat(s.panels().get(0).area().name()).isEqualTo("RIGHT");
    }

    @Test
    void theFlowGridTakesSectionsAndOrder() {
        Sutra s = parser.parse(sutra("""
                columns: 12
                sections:
                  - { id: overview, title: Overview }
                  - { id: risk, title: Risk, collapsed: true }
                user-can-arrange: true
                """, """
                - { id: a, kind: kv, section: overview, span: 6, columns: [ { label: A, bind: $.A } ] }
                - { id: b, kind: kv, section: risk, order: 1, columns: [ { label: A, bind: $.A } ] }
                - { id: c, kind: kv, columns: [ { label: A, bind: $.A } ] }
                """), "t.sutra.yaml", "x");
        assertThat(s.layout().sections()).extracting(ScreenLayout.Section::id).containsExactly("overview", "risk");
        assertThat(s.layout().section("risk").collapsed()).isTrue();
        Map<String, Object> view = s.layout().toView();
        assertThat(view).containsEntry("arrange", true).doesNotContainKey("areas");
    }

    @Test
    void lintFindsUnknownAreasOverlapsAndBadSplitsOrTabs() {
        assertThat(problems(AREAS, PANELS.replace("area: limits", "area: risk")))
                .anyMatch(p -> p.startsWith("DRS-2059") && p.contains("area 'risk'"));
        assertThat(problems("areas: [\"a b\", \"b a\"]", "- { id: x, kind: kv, area: a, columns: [ { label: A, bind: $.A } ] }"))
                .anyMatch(p -> p.contains("'a' is not a rectangle"));
        assertThat(problems("areas: [\"a b\", \"a\"]", "- { id: x, kind: kv, area: a, columns: [ { label: A, bind: $.A } ] }"))
                .anyMatch(p -> p.contains("same number of cells"));
        assertThat(problems(AREAS.replace("columns: [7, 5]", "columns: [7, 6]"), PANELS)).anyMatch(p -> p.contains("add up to 13"));
        assertThat(problems(AREAS.replace("between: [trades, detail]", "between: [header, limits]"), PANELS))
                .anyMatch(p -> p.contains("not side by side"));
        assertThat(problems(AREAS.replace("tabs: [detail]", "tabs: [limits]"), PANELS)).anyMatch(p -> p.contains("tabs need two or more"));
        assertThat(problems(AREAS.replace("tabs: [detail]", "tabs: [nowhere]"), PANELS)).anyMatch(p -> p.contains("tabs name area 'nowhere'"));
        assertThat(problems(AREAS.replace("sticky: header", "sticky: top"), PANELS)).anyMatch(p -> p.contains("sticky names 'top'"));
        assertThat(problems(AREAS.replace("phone: [header,", "phone: [ghost,"), PANELS)).anyMatch(p -> p.contains("phone lists 'ghost'"));
        assertThat(problems(AREAS.replace("columns: [6, 6]", "columns: [6, 6, 1]"), PANELS)).anyMatch(p -> p.contains("3 widths for 2"));
        assertThat(problems("split: { between: [a, b] }", "- { id: x, kind: kv, columns: [ { label: A, bind: $.A } ] }"))
                .anyMatch(p -> p.contains("name them in layout.areas first"));
        assertThat(problems("sections: [ { id: s } ]", "- { id: x, kind: kv, section: t, columns: [ { label: A, bind: $.A } ] }"))
                .anyMatch(p -> p.contains("section 't'"));
        assertThat(problems("wobble: 1", "- { id: x, kind: kv, columns: [ { label: A, bind: $.A } ] }"))
                .anyMatch(p -> p.contains("unknown key 'wobble' in layout"));
        assertThat(problems(AREAS, PANELS)).isEmpty();
    }

    @Test
    void aNamedAreaNeedsTheLayoutToNameIt() {
        assertThat(problems("", "- { id: x, kind: kv, area: trades, columns: [ { label: A, bind: $.A } ] }"))
                .anyMatch(p -> p.startsWith("DRS-2027") && p.contains("layout.areas"));
        assertThat(problems("", "- { id: x, kind: kv, order: 1000, columns: [ { label: A, bind: $.A } ] }"))
                .anyMatch(p -> p.contains("0 to 999"));
    }
}
