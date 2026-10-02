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

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * QA 2026-10-01 GRAM-05 and GRAM-07: input the parser used to accept silently (or misreport) is a located problem with
 * its own code.
 */
class StrictSutraTest {

    static final String HEAD = "rachana: 1\nsutra: qa-strict\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\n";
    private final SutraParser parser = new SutraParser();

    private List<SutraProblem> problems(String yaml) {
        try {
            parser.parse(yaml, "qa.sutra.yaml", "qa");
        } catch (SutraException e) {
            return e.problems();
        }
        throw new AssertionError("accepted: " + yaml);
    }

    private static String panel(String p) {
        return HEAD + "panels:\n  - " + p + "\n";
    }

    @Test
    void ambiguousYamlIsAProblemWithItsLine() {
        assertThat(problems(HEAD + "panels: []\nversion: 2\n")).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("DRS-2033");
            assertThat(p.message()).contains("duplicate key 'version'", "line 3");
            assertThat(p.location().line()).isEqualTo(7);
        });
        assertThat(problems(panel("{ id: a, id: b, kind: provenance }"))).extracting(SutraProblem::code).containsExactly("DRS-2033");
        assertThat(problems(HEAD + "panels: []\n---\nrachana: 1\nsutra: other\n")).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("DRS-2033");
            assertThat(p.message()).contains("second YAML document");
            assertThat(p.location().line()).isEqualTo(7);
        });
        assertThat(problems(HEAD + "panels:\n  - !panel { id: a, kind: provenance }\n")).singleElement()
                .satisfies(p -> assertThat(p.message()).contains("tag '!panel'"));
        assertThat(problems(HEAD.replace("version: 1", "version: !!binary MQ==") + "panels: []\n"))
                .extracting(SutraProblem::code).contains("DRS-2033");
        // a leading '---' and the core tags are plain YAML
        assertThat(parser.parse("---\n" + HEAD.replace("sutra: qa-strict", "sutra: !!str qa-strict") + "panels: []\n", "qa.sutra.yaml", "qa").id())
                .isEqualTo("qa-strict@1");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            match.kind 42          | match: { kind: 42 }                    | DRS-2012 | 'kind' in match
            match.priority high    | match: { kind: trade, priority: high } | DRS-2012 | 'priority' in match
            match.priority 1e99    | match: { kind: trade, priority: 1e99 } | DRS-2012 | 'priority' in match
            title without id       | title: { pill: X }                     | DRS-2010 | missing 'id' in title
            """)
    void headerValuesOfTheWrongShape(String name, String line, String code, String message) {
        String key = line.substring(0, line.indexOf(':') + 1);
        StringBuilder yaml = new StringBuilder();
        HEAD.lines().forEach(l -> yaml.append(l.startsWith(key) ? line : l).append('\n'));
        assertThat(problems(yaml + "panels: []\n")).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo(code);
            assertThat(p.message()).contains(message);
            assertThat(p.location().line()).isPositive();
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            table rows 5         | { id: a, kind: table, rows: 5 }                          | option 'rows' of 'table' panels is an expression
            table limit many     | { id: a, kind: table, rows: $.cashflows, limit: many }   | option 'limit' of 'table' panels must be a whole number
            table limit -5       | { id: a, kind: table, rows: $.cashflows, limit: -5 }     | option 'limit' of 'table' panels must be a whole number
            table search maybe   | { id: a, kind: table, rows: $.cashflows, search: maybe } | option 'search' of 'table' panels must be true or false
            ladder search maybe  | { id: a, kind: ladder, rows: $.cashflows, search: maybe } | option 'search' of 'ladder' panels must be true or false
            status fields 5      | { id: a, kind: status, fields: 5 }                       | option 'fields' of 'status' panels must be a list
            area series 5        | { id: a, kind: area, rows: $.cashflows, series: 5 }      | option 'series' of 'area' panels must be a list
            markdown text list   | { id: a, kind: markdown, text: [1, 2] }                  | option 'text' of 'markdown' panels is text
            """)
    void panelOptionsOfTheWrongType(String name, String panel, String message) {
        assertThat(problems(panel(panel))).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("DRS-2029");
            assertThat(p.message()).contains(message);
            assertThat(p.location().line()).isEqualTo(7);
        });
    }

    @Test
    void anOutOfRangeVersionIsAVersionProblemNotYamlSyntax() {
        for (String v : List.of("99999999999999999999", "3000000000")) {
            assertThat(problems(HEAD.replace("version: 1", "version: " + v) + "panels: []\n")).singleElement().satisfies(p -> {
                assertThat(p.code()).isEqualTo("DRS-2020");
                assertThat(p.message()).contains("version must be a positive integer", v);
            });
        }
    }

    @Test
    void expressionMessagesSayWhatIsWrong() {
        ElCompiler el = new ElCompiler();
        assertThatThrownBy(() -> el.compile("nosuchfn($.mtm)")).isInstanceOfSatisfying(ElException.class, e -> {
            String known = e.getMessage().substring(e.getMessage().indexOf("known: ") + 7, e.getMessage().lastIndexOf(" at "));
            assertThat(List.of(known.split(", "))).hasSizeGreaterThan(5).isSorted();
        });
        assertThatThrownBy(() -> el.compile("$.mtm >")).hasMessageContaining("ends early").hasMessageNotContaining("''");
        assertThatThrownBy(() -> el.compile("$.legs[0")).hasMessageContaining("expected ']' but the expression ends");
    }
}
