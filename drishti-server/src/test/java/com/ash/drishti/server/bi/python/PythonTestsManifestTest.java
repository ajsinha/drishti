package com.ash.drishti.server.bi.python;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code tests:} of a Python item: what is read, and every shape that is refused with the path of the key (DRS-9070). */
class PythonTestsManifestTest {

    static final List<String> PACKAGES = List.of("pandas", "numpy");
    static final String HEAD = "python: x\ninterface: measure\ninputs: [pnl1d]\nsource: |\n  def compute(table, params):\n      return 1\n";

    static PythonManifest.Result parse(String tests) {
        return PythonManifest.parse(HEAD + tests, PACKAGES, 60, 1024);
    }

    @Test
    void aMeasureTestWithAnInlineFrameAndANumberIsRead() {
        PythonManifest.Result r = parse("tests:\n  - name: sums\n    input: { columns: [pnl1d], rows: [[1.0], [2.0]] }\n    expect: 3.0\n    tolerance: 0.01\n");
        assertThat(r.ok()).as(r.problems().toString()).isTrue();
        assertThat(r.manifest().tests()).hasSize(1);
        assertThat(r.manifest().tests().get(0)).containsEntry("name", "sums").containsEntry("expect", 3.0);
        assertThat(r.manifest().toMap()).containsKey("tests");
        assertThat(PythonManifest.parse(HEAD, PACKAGES, 60, 1024).manifest().tests()).isEmpty();
    }

    @Test
    void aGroupedMeasureExpectsANumberForEachGroupAndASampleNamesADataset() {
        assertThat(parse("tests:\n  - { name: g, input: { columns: [desk, pnl1d], rows: [[A, 1], [B, 2]] }, by: [desk], expect: { A: 1, B: 2 } }\n").ok()).isTrue();
        assertThat(parse("tests:\n  - { name: s, sample: { dataset: trading-risk, fields: [trades.desk, trades.pnl1d], limit: 100 }, expect: 5 }\n").ok()).isTrue();
    }

    @Test
    void everyKindOfMistakeIsRefusedWithThePathOfTheKey() {
        assertThat(parse("tests: oops\n").problems()).anyMatch(p -> p.path().equals("tests"));
        assertThat(parse("tests:\n  - { input: { columns: [a], rows: [[1]] }, expect: 1 }\n").problems()).anyMatch(p -> p.path().equals("tests[0].name"));
        assertThat(parse("tests:\n  - { name: a, expect: 1 }\n").problems()).anyMatch(p -> p.path().equals("tests[0]") && p.message().contains("input"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a], rows: [[1]] }, sample: { dataset: d, fields: [t.c] }, expect: 1 }\n").problems()).anyMatch(p -> p.path().equals("tests[0]"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a, b], rows: [[1]] }, expect: 1 }\n").problems()).anyMatch(p -> p.path().equals("tests[0].input.rows[0]"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a], rows: [[1]] } }\n").problems()).anyMatch(p -> p.path().equals("tests[0].expect"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a], rows: [[1]] }, expect: high }\n").problems()).anyMatch(p -> p.path().equals("tests[0].expect"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a], rows: [[1]] }, expect: 1, tolerance: -1 }\n").problems()).anyMatch(p -> p.path().equals("tests[0].tolerance"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a], rows: [[1]] }, expect: 1, colour: red }\n").problems()).anyMatch(p -> p.path().equals("tests[0].colour"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a], rows: [[1]] }, expect: 1 }\n  - { name: a, input: { columns: [a], rows: [[1]] }, expect: 1 }\n").problems())
                .anyMatch(p -> p.message().contains("two tests are called 'a'"));
        assertThat(parse("tests:\n  - { name: a, input: { columns: [a], rows: [[1]] }, by: [a], expect: 1 }\n").problems()).anyMatch(p -> p.path().equals("tests[0].expect"));
    }

    @Test
    void aVisualExpectsAKindAndATransformExpectsColumnsOrRows() {
        String visual = "python: v\ninterface: visual\noutput: vega-lite\nsource: |\n  def render(table, params):\n      return {}\n";
        assertThat(PythonManifest.parse(visual + "tests:\n  - { name: v, input: { columns: [a], rows: [[1]] }, expect: { kind: vega-lite, has: { mark: bar } } }\n", PACKAGES, 60, 1024).ok()).isTrue();
        assertThat(PythonManifest.parse(visual + "tests:\n  - { name: v, input: { columns: [a], rows: [[1]] }, expect: { kind: html } }\n", PACKAGES, 60, 1024).problems())
                .anyMatch(p -> p.path().equals("tests[0].expect"));
        String transform = "python: t\ninterface: transform\ninputs: [trading-risk]\ntarget: server\nsource: |\n  def run(trading_risk):\n      return trading_risk\n";
        assertThat(PythonManifest.parse(transform + "tests:\n  - { name: t, input: { trading-risk: { columns: [a], rows: [[1]] } }, expect: { rows: 1 } }\n", PACKAGES, 60, 1024).ok()).isTrue();
        assertThat(PythonManifest.parse(transform + "tests:\n  - { name: t, sample: { dataset: d, fields: [t.c] }, expect: { rows: 1 } }\n", PACKAGES, 60, 1024).problems())
                .anyMatch(p -> p.path().equals("tests[0].sample"));
    }
}
