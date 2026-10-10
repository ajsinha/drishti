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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.design.ops.OpApplier;
import com.ash.drishti.rachana.design.ops.Ops;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Control;
import com.ash.drishti.rachana.model.ControlKind;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code controls:} in Sutras: the grammar, its problem codes (DRS-2034 to 2039), {@code ctl.<id>} and {@code [*]} in Rachana-EL. */
class ControlsTest {

    private final SutraParser parser = new SutraParser();
    private static final ElCompiler EL = new ElCompiler();
    private static final Formats F = Formats.load(null, List.of());
    private static final DataNode DOC = new JsonCodec().read("""
            {"legs":[{"id":"L1","leg":1,"payer":true},{"id":"L2","leg":2,"payer":false}],
             "schedule":[{"leg":1,"payDate":"2026-10-15","amount":10},{"leg":2,"payDate":"2026-11-15","amount":20},{"leg":1,"payDate":"2027-01-15","amount":30}]}""");

    private static String sutra(String controls, String panels) {
        return "rachana: 1\nsutra: ctl-test\nversion: 1\nmatch: { kind: trade }\n" + controls + "panels:\n" + panels;
    }

    private static final String TABLE = "  - id: cf\n    kind: table\n    rows: $.schedule\n    columns: [{ label: Leg, bind: \"@.leg\" }]\n";

    private List<String> problems(String controls, String panels) {
        try {
            Sutra s = parser.parse(sutra(controls, panels), "t.sutra.yaml", "x");
            return new SutraExpressions(EL).check(s).stream().map(p -> p.code() + " " + p.message()).toList();
        } catch (SutraException e) {
            return e.problems().stream().map(p -> p.code() + " " + p.message()).toList();
        }
    }

    private Sutra parse(String controls, String panels) {
        return parser.parse(sutra(controls, panels), "t.sutra.yaml", "x");
    }

    private static Object eval(String src, Map<String, Object> ctl) {
        return Values.simplify(EL.compile(src).eval(EvalContext.of(DOC, F).withControls(ctl)));
    }

    @Test
    void everyKindReadsWithItsFields() {
        Sutra s = parse("""
                controls:
                  - { id: leg, kind: dropdown, label: Leg, options: "$.legs[*].id", default: first, remember: user, required: true }
                  - { id: legs, kind: multi-select, options: ["L1", { value: L2, label: Second }], default: all }
                  - { id: sub, kind: cascading, parent: leg, options: "$.legs[?(@.id == ctl.leg)][*].leg" }
                  - { id: side, kind: toggle, options: [Pay, Receive], default: Pay }
                  - { id: view, kind: segmented, options: [A, B, C] }
                  - { id: d, kind: date, default: today }
                  - { id: when, kind: date-range, default: next-90d, placement: side }
                  - { id: size, kind: number-range, min: 0, max: "max($.legs[*].leg)" }
                  - { id: top, kind: top-n, options: [5, 10], default: 10 }
                  - { id: q, kind: search, placement: header, panel: cf }
                """, TABLE);
        assertThat(s.controls()).extracting(Control::id).containsExactly("leg", "legs", "sub", "side", "view", "d", "when", "size", "top", "q");
        assertThat(s.controls()).extracting(Control::kind).containsExactly(ControlKind.DROPDOWN, ControlKind.MULTI_SELECT, ControlKind.CASCADING,
                ControlKind.TOGGLE, ControlKind.SEGMENTED, ControlKind.DATE, ControlKind.DATE_RANGE, ControlKind.NUMBER_RANGE, ControlKind.TOP_N,
                ControlKind.SEARCH);
        Control leg = s.control("leg").orElseThrow();
        assertThat(leg.from()).isEqualTo("$.legs[*].id");
        assertThat(leg.remember()).isTrue();
        assertThat(leg.required()).isTrue();
        assertThat(s.control("legs").orElseThrow().choices()).extracting(Control.Choice::value, Control.Choice::label)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("L1", "L1"), org.assertj.core.groups.Tuple.tuple("L2", "Second"));
        assertThat(s.control("when").orElseThrow().placement()).isEqualTo(Control.Placement.SIDE);
        assertThat(s.control("q").orElseThrow().panel()).isEqualTo("cf");
        assertThat(new SutraExpressions(EL).check(s)).isEmpty();
    }

    @Test
    void aSutraWithoutControlsHasNone() {
        assertThat(parse("", TABLE).controls()).isEmpty();
    }

    @Test
    void malformedControlsAreDrs2034() {
        assertThat(problems("controls:\n  - { id: leg, kind: slider }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("unknown control kind"));
        assertThat(problems("controls:\n  - { id: leg, kind: dropdown }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("needs 'options'"));
        assertThat(problems("controls:\n  - { id: 9x, kind: search }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("control id"));
        assertThat(problems("controls:\n  - { kind: search }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("needs an 'id'"));
        assertThat(problems("controls:\n  - { id: q, kind: search, options: [a] }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("takes no 'options'"));
        assertThat(problems("controls:\n  - { id: q, kind: search, placement: left }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("placement"));
        assertThat(problems("controls:\n  - { id: q, kind: search, placement: header }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("names the panel"));
        assertThat(problems("controls:\n  - { id: q, kind: search, placement: header, panel: nope }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("'nope'"));
        assertThat(problems("controls:\n  - { id: q, kind: search, remember: always }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2034") && p.contains("remember"));
        assertThat(problems("controls:\n  - { id: q, kind: search, colour: red }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2011"));
        assertThat(problems("controls: 5\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2012"));
    }

    @Test
    void duplicateIdsAreDrs2035() {
        assertThat(problems("controls:\n  - { id: q, kind: search }\n  - { id: q, kind: search }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2035"));
    }

    @Test
    void aCtlReferenceToAnUnknownControlIsDrs2036() {
        String panels = "  - id: cf\n    kind: table\n    rows: $.schedule\n    where: \"@.leg == ctl.nope\"\n    columns: [{ label: Leg, bind: \"@.leg\" }]\n";
        assertThat(problems("controls:\n  - { id: leg, kind: search }\n", panels)).anyMatch(p -> p.startsWith("DRS-2036") && p.contains("ctl.nope") && p.contains("leg"));
        assertThat(problems("", panels)).anyMatch(p -> p.startsWith("DRS-2036") && p.contains("no controls"));
        String title = "  - id: cf\n    kind: table\n    title: \"Leg ${ctl.gone}\"\n    rows: $.schedule\n    columns: [{ label: Leg, bind: \"@.leg\" }]\n";
        assertThat(problems("controls:\n  - { id: leg, kind: search }\n", title)).anyMatch(p -> p.startsWith("DRS-2036"));
        assertThat(problems("controls:\n  - { id: leg, kind: search }\n", panels.replace("ctl.nope", "ctl.leg"))).isEmpty();
    }

    @Test
    void aCascadingControlNeedsAnEarlierParentDrs2037() {
        assertThat(problems("controls:\n  - { id: c, kind: cascading, options: \"$.x\" }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2037"));
        assertThat(problems("controls:\n  - { id: c, kind: cascading, parent: p, options: \"$.x\" }\n  - { id: p, kind: search }\n", TABLE))
                .anyMatch(p -> p.startsWith("DRS-2037") && p.contains("declared before"));
    }

    @Test
    void onSelectMustNameAControlAndPanelsDrs2038() {
        String ok = "controls:\n  - { id: leg, kind: search }\n";
        assertThat(problems(ok, TABLE + "    on-select: { by: leg, filter: [cf] }\n")).isEmpty();
        assertThat(problems(ok, TABLE + "    on-select: { by: nope }\n")).anyMatch(p -> p.startsWith("DRS-2038") && p.contains("'by'"));
        assertThat(problems(ok, TABLE + "    on-select: { by: leg, filter: [ghost] }\n")).anyMatch(p -> p.startsWith("DRS-2038") && p.contains("ghost"));
        assertThat(problems(ok, TABLE + "    on-select: { by: leg, colour: red }\n")).anyMatch(p -> p.startsWith("DRS-2038"));
    }

    @Test
    void defaultsMustFitTheKindDrs2039() {
        assertThat(problems("controls:\n  - { id: a, kind: dropdown, options: [x, y], default: z }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2039"));
        assertThat(problems("controls:\n  - { id: a, kind: top-n, options: [5], default: 7 }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2039"));
        assertThat(problems("controls:\n  - { id: a, kind: date-range, default: soon }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2039") && p.contains("next-90d"));
        assertThat(problems("controls:\n  - { id: a, kind: date, default: 2026-13-45 }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2039"));
        assertThat(problems("controls:\n  - { id: a, kind: number-range, default: abc }\n", TABLE)).anyMatch(p -> p.startsWith("DRS-2039"));
        assertThat(problems("controls:\n  - { id: a, kind: date-range, default: \"2026-10-01..2026-12-31\" }\n  - { id: b, kind: date, default: previous-business-day }\n"
                + "  - { id: c, kind: number-range, default: \"..5000000\" }\n", TABLE)).isEmpty();
    }

    @Test
    void wherePivotFreeOptionsAreChecked() {
        String panels = "  - id: cf\n    kind: table\n    rows: $.schedule\n    where: 5\n    columns: [{ label: Leg, bind: \"@.leg\" }]\n";
        assertThat(problems("", panels)).anyMatch(p -> p.startsWith("DRS-2029") && p.contains("condition"));
        assertThat(problems("", "  - id: k\n    kind: kv\n    where: \"true\"\n    columns: [{ label: A, bind: \"$.x\" }]\n")).anyMatch(p -> p.startsWith("DRS-2023"));
        assertThat(problems("controls:\n  - { id: top, kind: top-n, options: [5] }\n", TABLE + "    limit: ctl.top\n")).isEmpty();
    }

    @Test
    void ctlReadsTheControlValuesInExpressions() {
        Map<String, Object> ctl = Map.of("leg", "L2", "legs", DataNode.of(List.of("1")), "when", "2026-10-01..2026-12-31", "top", 3L, "q", "");
        assertThat(eval("$.legs[?(@.id == ctl.leg)][0].leg", ctl)).isEqualTo(2L);
        assertThat(eval("size($.legs[?(@.id == ctl.leg)])", ctl)).isEqualTo(1L);
        assertThat(eval("ctl.top + 1", ctl)).isEqualTo(4L);
        assertThat(eval("ctl.leg == 'L2'", ctl)).isEqualTo(true);
        assertThat(eval("ctl.unset", ctl)).isNull();
        assertThat(eval("ctl.leg", Map.of())).isNull();
    }

    @Test
    void projectionListsAFieldOfEveryElement() {
        DataNode ids = (DataNode) EL.compile("$.legs[*].id").eval(EvalContext.of(DOC, F));
        assertThat(ids.size()).isEqualTo(2);
        assertThat(ids.get(0).asText()).isEqualTo("L1");
        assertThat(ids.get(1).asText()).isEqualTo("L2");
        assertThat(eval("size($.legs[*].id)", Map.of())).isEqualTo(2L);
        assertThat(eval("sum($.schedule[*].amount)", Map.of())).isEqualTo(60L);
        assertThat(eval("$.nothing[*].id", Map.of())).isNull();
    }

    @Test
    void withinAndOneOfFilterRows() {
        Map<String, Object> ctl = Map.of("when", "2026-10-01..2026-12-31", "legs", DataNode.of(List.of("1")), "none", DataNode.of(List.of()));
        assertThat(eval("$.schedule[?(within(@.payDate, ctl.when))][*].amount", ctl).toString()).contains("10", "20").doesNotContain("30");
        assertThat(eval("size($.schedule[?(within(@.payDate, ctl.when) && oneOf(@.leg, ctl.legs))])", ctl)).isEqualTo(1L);
        assertThat(eval("size($.schedule[?(oneOf(@.leg, ctl.none))])", ctl)).isEqualTo(3L);               // nothing chosen: no filter
        assertThat(eval("within(5, '1..10')", ctl)).isEqualTo(true);
        assertThat(eval("within(50, '1..10')", ctl)).isEqualTo(false);
        assertThat(eval("within(50, '..100')", ctl)).isEqualTo(true);
        assertThat(eval("within('2026-10-15T10:00:00Z', '2026-10-15..2026-10-15')", ctl)).isEqualTo(true);   // a timestamp compares by its date
        assertThat(eval("within($.missing, '1..2')", ctl)).isEqualTo(false);
        assertThat(eval("within(1, '')", ctl)).isEqualTo(true);
        assertThat(eval("oneOf('a', 'a')", ctl)).isEqualTo(true);
    }

    @Test
    void aMaskedValueIsNeverWithinOrOneOf() {
        DataNode masked = new JsonCodec().read("{\"d\":\"" + DataNode.MASK + "\"}");
        EvalContext ctx = EvalContext.of(masked, F).withControls(Map.of("when", "2026-10-01..2026-12-31"));
        assertThat(Values.truthy(EL.compile("within($.d, ctl.when)").eval(ctx))).isFalse();
    }

    @Test
    void theWorkbenchSetControlsOpWritesTheSection() {
        String yaml = "rachana: 1\nsutra: ctl-test\nversion: 1\nmatch: { kind: trade }\nstrip:\n  - { label: A, bind: $.a }\npanels:\n" + TABLE;
        var out = new OpApplier().apply(yaml,
                Ops.parse("[{\"op\":\"setControls\",\"items\":[{\"id\":\"q\",\"kind\":\"search\",\"label\":\"Find\"}]}]"));
        assertThat(out.problems()).isEmpty();
        assertThat(out.yaml()).contains("controls:\n  - ").contains("id: q").contains("kind: search");
        assertThat(out.yaml().indexOf("controls:")).isBetween(out.yaml().indexOf("strip:"), out.yaml().indexOf("panels:"));
        assertThat(parser.parse(out.yaml(), "t.sutra.yaml", "x").controls()).hasSize(1);
        var gone = new OpApplier().apply(out.yaml(), Ops.parse("[{\"op\":\"setControls\",\"items\":[]}]"));
        assertThat(gone.yaml()).doesNotContain("controls:");
    }

    @Test
    void thePanelOptionsAreKeptOnThePanel() {
        Sutra s = parse("controls:\n  - { id: leg, kind: search }\n", TABLE + "    where: \"@.leg == 1\"\n    on-select: { by: leg, field: leg, filter: [cf] }\n");
        Panel p = s.panels().get(0);
        assertThat(p.options().get(Panel.WHERE)).isEqualTo("@.leg == 1");
        assertThat(p.options().get(Panel.ON_SELECT)).isInstanceOf(Map.class);
    }
}
