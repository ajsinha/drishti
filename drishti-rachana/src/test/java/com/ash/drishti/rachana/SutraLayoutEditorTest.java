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

import com.ash.drishti.rachana.SutraLayoutEditor.Placement;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class SutraLayoutEditorTest {

    private final SutraLayoutEditor editor = new SutraLayoutEditor();
    private final SutraParser parser = new SutraParser();

    private static String irs() throws Exception {
        return Files.readString(SutraParserTest.SUTRAS.resolve("rates/irs-vanilla.v3.sutra.yaml"));
    }

    private Sutra parse(String text) {
        return parser.parse(text, "t.sutra.yaml", "rates");
    }

    @Test
    void movesResizesAndReordersPanelsKeepingTheRestOfTheText() throws Exception {
        String src = irs();
        var edit = editor.apply(src, List.of(
                new Placement("cashflows", Area.MAIN, 8, 10, false),
                new Placement("legs", Area.MAIN, null, null, false),
                new Placement("built", Area.MAIN, 4, null, false),
                new Placement("leg2", Area.MAIN, null, null, false),
                new Placement("dv01", Area.RIGHT, null, null, false),
                new Placement("refs", Area.MAIN, 6, null, false),          // flow mapping: moved to main, sized
                new Placement("curve", Area.RIGHT, null, 6, false)), false);
        Sutra s = parse(edit.text());
        assertThat(edit.fromVersion()).isEqualTo(3);
        assertThat(edit.version()).isEqualTo(4);
        assertThat(s.version()).isEqualTo(4);
        assertThat(s.panels().stream().filter(p -> p.area() == Area.MAIN).map(Panel::id))
                .containsExactly("cashflows", "legs", "built", "leg2", "refs");
        assertThat(s.panels().stream().filter(p -> p.area() == Area.RIGHT).map(Panel::id)).containsExactly("dv01", "curve");
        Panel cash = s.panels().stream().filter(p -> p.id().equals("cashflows")).findFirst().orElseThrow();
        assertThat(cash.span()).contains(8);
        assertThat(cash.height()).contains(10);
        assertThat(s.panels().stream().filter(p -> p.id().equals("refs")).findFirst().orElseThrow().span()).contains(6);
        // the text keeps its comments, strip, keys and every untouched panel as written
        assertThat(edit.text()).contains("keys: { F7: \"link($.nettingSet, 'netting-set')\", F8: impact, F9: raw }")
                .contains("  - { id: refs, kind: links, title: Linked entities, code: REFS, span: 6 }")
                .contains("  - { id: built, kind: provenance, title: How this view was built, span: 4 }")
                .contains("    kind: table\n    span: 8\n    height: 10\n    title: Cashflows · Leg 1");
        String header = src.substring(0, src.indexOf("version: 3"));
        assertThat(edit.text()).startsWith(header);
        assertThat(edit.changes()).anySatisfy(c -> assertThat(c).contains("'refs' moves to the main column"))
                .anySatisfy(c -> assertThat(c).contains("'cashflows' is 8 of 12 columns wide"))
                .anySatisfy(c -> assertThat(c).contains("the main column reads cashflows, legs, built, leg2, refs"));
    }

    @Test
    void anUnchangedLayoutOnlyRaisesTheVersionAndDefaultsRemoveKeys() throws Exception {
        String src = irs();
        var same = editor.apply(src, List.of(), false);
        assertThat(same.text()).isEqualTo(src.replace("version: 3", "version: 4"));
        assertThat(same.changes()).isEmpty();
        // dv01 back to main removes its area; a full-width span is no key at all
        var back = editor.apply(src, List.of(new Placement("dv01", Area.MAIN, 12, null, false)), false);
        assertThat(back.text()).doesNotContain("    area: right\n    rows: $.dv01ByTenor");
        assertThat(parse(back.text()).panels().stream().filter(p -> p.id().equals("dv01")).findFirst().orElseThrow().span()).isEmpty();
        // sizes set once can be changed and removed again
        String sized = editor.apply(src, List.of(new Placement("leg2", Area.MAIN, 6, 4, false)), false).text();
        String resized = editor.apply(sized, List.of(new Placement("leg2", Area.MAIN, 3, null, false)), false).text();
        Panel leg2 = parse(resized).panels().stream().filter(p -> p.id().equals("leg2")).findFirst().orElseThrow();
        assertThat(leg2.span()).contains(3);
        assertThat(leg2.height()).isEmpty();
        assertThat(parse(resized).version()).isEqualTo(5);
    }

    @Test
    void hiddenPanelsStayUnlessAskedToGoAndUnknownIdsAreIgnored() throws Exception {
        String src = irs();
        var kept = editor.apply(src, List.of(new Placement("built", Area.MAIN, null, null, true), new Placement("ghost", Area.MAIN, 4, null, false)), false);
        assertThat(parse(kept.text()).panels()).extracting(Panel::id).contains("built");
        var dropped = editor.apply(src, List.of(new Placement("built", Area.MAIN, null, null, true)), true);
        assertThat(parse(dropped.text()).panels()).extracting(Panel::id).doesNotContain("built");
        assertThat(dropped.changes()).anySatisfy(c -> assertThat(c).contains("'built' is removed"));
    }

    @Test
    void commentsAbovePanelsTravelWithThemAndAFlowListIsRefused() {
        String src = """
                rachana: 1
                sutra: c-test
                version: 1
                match: { kind: trade }
                panels:
                  # the terms first
                  - { id: a, kind: markdown, text: A }
                  # then the notes
                  - id: b
                    kind: markdown
                    text: B   # inline

                # ---- keys ----
                keys: { F9: raw }
                """;
        var e = editor.apply(src, List.of(new Placement("b", Area.MAIN, 5, 3, false), new Placement("a", Area.MAIN, null, null, false)), false);
        assertThat(e.text()).contains("panels:\n  # then the notes\n  - id: b\n    kind: markdown\n    span: 5\n    height: 3\n"
                + "    text: B   # inline\n  # the terms first\n  - { id: a, kind: markdown, text: A }\n");
        assertThat(e.text()).contains("\n\n# ---- keys ----\nkeys: { F9: raw }");
        String flowList = "rachana: 1\nsutra: f-test\nversion: 1\nmatch: { kind: trade }\npanels: [ { id: a, kind: markdown, text: A } ]\n";
        assertThatThrownBy(() -> editor.apply(flowList, List.of(), false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flow list");
    }

    @Test
    void flowEntriesAreFoundAtTheirOwnDepthOnly() {
        String t = "  - { id: x, kind: table, rows: $.r, columns: [{ label: span, bind: \"@.span\" }], area: right }";
        assertThat(SutraLayoutEditor.flowSet(t, "area", null)).isEqualTo("  - { id: x, kind: table, rows: $.r, columns: [{ label: span, bind: \"@.span\" }] }");
        assertThat(SutraLayoutEditor.flowSet(t, "span", "4")).endsWith("area: right, span: 4 }");
        assertThat(SutraLayoutEditor.flowSet(t, "area", "main")).endsWith("area: main }");
    }

    @Test
    void theGrammarChecksSpanAndHeight() {
        String base = "rachana: 1\nsutra: s-test\nversion: 1\nmatch: { kind: trade }\npanels:\n";
        Sutra ok = parse(base + "  - { id: a, kind: markdown, text: A, span: 6, height: 24 }\n");
        assertThat(ok.panels().get(0).span()).contains(6);
        assertThat(ok.panels().get(0).height()).contains(24);
        for (String bad : List.of("span: 0", "span: 13", "span: wide", "height: 25", "height: 2.5")) {
            assertThatThrownBy(() -> parse(base + "  - { id: a, kind: markdown, text: A, " + bad + " }\n"))
                    .satisfies(e -> assertThat(((SutraException) e).problems()).extracting(SutraProblem::code).containsExactly("DRS-2030"));
        }
        assertThatThrownBy(() -> parse(base + "  - { id: t, kind: tabs, each: $.legs, body: { kind: kv, span: 4 } }\n"))
                .satisfies(e -> assertThat(((SutraException) e).problems().get(0).message()).contains("a tabs body takes the size of its panel"));
    }
}
