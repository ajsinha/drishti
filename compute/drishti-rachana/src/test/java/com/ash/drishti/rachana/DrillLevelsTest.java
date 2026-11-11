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

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code levels:} of a table or ladder (a drill-down, any depth): what the parser accepts, what it refuses, and the expressions it checks. */
class DrillLevelsTest {

    private final SutraParser parser = new SutraParser();

    private static String sutra(String extra) {
        return """
                rachana: 1
                sutra: deep-deal
                version: 1
                match: { kind: deal }
                panels:
                  - id: legs
                    kind: table
                    rows: $.legs
                    crumb: "'Leg ' + @.leg"
                    root: "'Deal ' + $.id"
                    empty: No legs
                    columns:
                      - { label: Leg, bind: "@.leg" }
                """ + extra.indent(4);
    }

    private List<String> problems(String extra) {
        try {
            parser.parse(sutra(extra), "t.sutra.yaml", "x");
            return List.of();
        } catch (SutraException e) {
            return e.problems().stream().map(p -> p.code() + " " + p.message()).toList();
        }
    }

    static final String THREE_LEVELS = """
            levels:
              - rows: "@.cashflows"
                title: Cash flows
                crumb: "'Flow ' + @.n"
                columns:
                  - { label: Pay date, bind: "@.payDate", fmt: date }
                  - { label: Amount, bind: "@.amount", fmt: amount0, total: true }
              - rows: "@.resets"
                title: Resets
                columns:
                  - { label: Fixing, bind: "@.date", fmt: date }
              - rows: "@.observations"
                title: Observations
            """;

    @Test
    void aTableDrillsIntoLevelsAtAnyDepth() {
        Sutra s = parser.parse(sutra(THREE_LEVELS), "t.sutra.yaml", "x");
        var p = s.panels().get(0);
        assertThat(p.options().get("levels")).isInstanceOf(List.class);
        assertThat((List<?>) p.options().get("levels")).hasSize(3);
        assertThat(((Map<?, ?>) ((List<?>) p.options().get("levels")).get(0)).get("title")).isEqualTo("Cash flows");
        assertThat(p.option("crumb")).contains("'Leg ' + @.leg");
        assertThat(p.option("empty")).contains("No legs");
        assertThat(new SutraExpressions(new ElCompiler()).check(s)).isEmpty();
    }

    @Test
    void aBadLevelIsRefusedWithTheReason() {
        assertThat(problems("levels: \"@.cashflows\"")).anyMatch(m -> m.startsWith("DRS-2029") && m.contains("list of 1 to 6 levels"));
        assertThat(problems("levels:\n  - { title: Flows }")).anyMatch(m -> m.startsWith("DRS-2029") && m.contains("rows"));
        assertThat(problems("levels:\n  - { rows: \"@.x\", sortBy: date }")).anyMatch(m -> m.contains("not 'sortBy'"));
        assertThat(problems("levels:\n  - { rows: \"@.x\", columns: [ { label: A } ] }")).anyMatch(m -> m.contains("with a bind"));
        assertThat(problems("children: \"@.kids\"\nlevels:\n  - { rows: \"@.x\" }")).anyMatch(m -> m.contains("cannot also have 'children'"));
        String seven = "levels:\n" + "  - { rows: \"@.x\" }\n".repeat(7);
        assertThat(problems(seven)).anyMatch(m -> m.contains("1 to 6 levels"));
    }

    @Test
    void aBrokenExpressionInALevelIsReportedAtLoad() {
        Sutra s = parser.parse(sutra("levels:\n  - rows: \"@.cashflows[\"\n    columns:\n      - { label: A, bind: \"@.a +\" }"), "t.sutra.yaml", "x");
        assertThat(new SutraExpressions(new ElCompiler()).check(s)).hasSizeGreaterThanOrEqualTo(2)
                .allMatch(p -> p.code().equals("DRS-2101"));
    }
}
