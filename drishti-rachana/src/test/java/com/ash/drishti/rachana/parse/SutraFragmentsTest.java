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
package com.ash.drishti.rachana.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sutra Next phase 1: the fragment library (the Drishti UI kit, config/ui-kit/fragments) loads, every fragment carries a complete doc:
 * (DRS-2055, strict), every fragment and every example of its doc expands into panels a Sutra accepts, parameters are filled, and
 * the migrated ir-curve Sutra is built from them.
 */
class SutraFragmentsTest {

    static final Path LIBRARY = Path.of("../config/ui-kit/fragments");
    static final Path MARKET = Path.of("../config/packs/market-data/sutras");

    @TempDir Path dir;

    @AfterEach
    void empty() {
        SutraBlocks.load(List.of());
    }

    static String sutra(String panels) {
        return "rachana: 1\nsutra: probe\nversion: 1\nmatch: { kind: ir-curve }\ntitle: { id: $.CURVE_ID }\npanels:\n" + panels;
    }

    @Test
    void theLibraryLoadsAndEveryFragmentAndEveryExampleOfItsDocExpandsIntoPanels() throws Exception {
        assertThat(SutraBlocks.load(List.of(LIBRARY))).isEmpty();
        var fragments = SutraBlocks.library().values().stream().flatMap(v -> v.values().stream()).toList();
        assertThat(fragments).hasSizeBetween(8, 30).allMatch(SutraBlocks.Block::fragment);
        assertThat(fragments).extracting(SutraBlocks.Block::name).contains("series-line", "curve-table", "field-list", "ranked-bars",
                "cashflow-ladder", "linked-entities", "data-provenance", "key-figure", "measure-table", "region-map", "source-footer");
        for (SutraBlocks.Block b : fragments) {
            int inner = b.text().split("\n  - use:", -1).length - 1;             // a composite draws one panel per inner (one-panel) fragment
            int drawn = inner == 0 ? 1 : inner;
            Map<String, Object> doc = b.doc();
            assertThat(doc).as(b.ref()).containsKeys("summary", "description", "params", "emits", "expects", "examples", "since");
            Sutra s = new SutraParser().parse(sutra("  - { use: " + b.ref() + " }\n"), "probe.v1.sutra.yaml", "x");
            assertThat(s.panels()).as(b.ref() + " with its defaults").hasSize(drawn);
            @SuppressWarnings("unchecked")
            List<Object> examples = (List<Object>) doc.get("examples");
            for (Object example : examples) {
                String yaml = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(example);   // JSON is YAML
                assertThat(new SutraParser().parse(sutra("  - " + yaml + "\n"), "probe.v1.sutra.yaml", "x").panels())
                        .as(b.ref() + " example " + yaml).hasSize(drawn);
            }
        }
    }

    @Test
    void parametersAreFilledAndEmptyOptionalOnesLeaveTheKeyOut() {
        SutraBlocks.load(List.of(LIBRARY));
        Sutra s = new SutraParser().parse(sutra("""
                  - { use: series-line@1, with: { id: curve, title: "Zero rates (%)", code: CRV, key: F2, rows: $.POINTS, x: TENOR, y: ZERO_RATE } }
                  - { use: series-line@1, with: { id: fwd, area: right, rows: $.FORWARDS, y: RATE, fmt: pct4 } }
                  - { use: curve-table@1, with: { zero: ZERO, df-fmt: price4 } }
                """), "probe.v1.sutra.yaml", "x");
        Panel curve = s.panels().get(0);
        assertThat(curve.id()).isEqualTo("curve");
        assertThat(curve.title()).isEqualTo("Zero rates (%)");
        assertThat(curve.code()).isEqualTo("CRV");
        assertThat(curve.key()).isEqualTo("F2");
        assertThat(curve.area()).isEqualTo(Area.MAIN);
        assertThat(curve.options()).containsEntry("y", "ZERO_RATE").containsEntry("fmt", "price2");
        Panel fwd = s.panels().get(1);
        assertThat(fwd.code()).isNull();
        assertThat(fwd.key()).isNull();
        assertThat(fwd.area()).isEqualTo(Area.RIGHT);
        assertThat(fwd.options()).containsEntry("fmt", "pct4").containsEntry("x", "TENOR");
        Panel table = s.panels().get(2);
        assertThat(table.columns()).extracting(c -> c.bind()).containsExactly("@.TENOR", "@.INSTRUMENT", "@.QUOTE", "@.ZERO", "@.DF");
        assertThat(table.columns().get(4).fmt()).isEqualTo("price4");
    }

    @Test
    void theMigratedIrCurveSutraIsBuiltFromTheLibraryInItsOrder() throws Exception {
        SutraBlocks.load(List.of(LIBRARY));
        String text = Files.readString(MARKET.resolve("market-data/ir-curve.v1.sutra.yaml"));
        Sutra s = new SutraParser().parse(text, "ir-curve.v1.sutra.yaml", "market-data");
        assertThat(s.panels()).extracting(Panel::id).containsExactly("curve", "points", "forwards", "built", "refs");
        assertThat(s.panels()).extracting(Panel::title)
                .containsExactly("Zero rates (%)", "Pillars", "1Y forward rates (%)", "How this view was built", "Linked entities");
        PNode root = new PositionalYamlReader().read(text, new ArrayList<>());
        assertThat(SutraDocstring.checkSutra(root, "sutra ir-curve", "ir-curve.v1.sutra.yaml")).isEmpty();
    }

    static final String FRAGMENT = """
            rachana: 1
            fragment: probe-line
            version: 1
            params: { rows: $.POINTS, fmt: price2 }
            doc:
              summary: A line.
              description: Draws a line.
              params:
                rows: { type: expression, default: $.POINTS, meaning: The rows }
                fmt: { type: format, default: price2, meaning: The format }
              emits: []
              expects: []
              examples:
                - { use: probe-line@1, with: { fmt: pct4 } }
              since: "2.7.0"
            panels:
              - { id: line, kind: line, rows: "%{rows}", x: TENOR, y: Y, fmt: "%{fmt}" }
            """;

    @Test
    void aFragmentWithoutACompleteDocIsDrs2055AndIsNotLoaded() throws Exception {
        Files.writeString(dir.resolve("probe-line.v1.fragment.yaml"), FRAGMENT);
        assertThat(SutraBlocks.load(List.of(dir))).isEmpty();
        assertThat(SutraBlocks.find("probe-line@1")).isPresent();

        Map<String, String> broken = Map.of(
                "no doc", FRAGMENT.substring(0, FRAGMENT.indexOf("doc:")) + FRAGMENT.substring(FRAGMENT.indexOf("panels:")),
                "no summary", FRAGMENT.replace("  summary: A line.\n", ""),
                "a parameter undocumented", FRAGMENT.replace("    fmt: { type: format, default: price2, meaning: The format }\n", ""),
                "a wrong default", FRAGMENT.replace("default: price2", "default: pct4"),
                "a bad type", FRAGMENT.replace("type: format", "type: colour"),
                "an undeclared parameter documented", FRAGMENT.replace("  emits: []",
                        "    tone: { type: text, default: x, meaning: y }\n  emits: []"),
                "no emits", FRAGMENT.replace("  emits: []\n", ""),
                "an example of another fragment", FRAGMENT.replace("use: probe-line@1", "use: other@1"),
                "no since", FRAGMENT.replace("  since: \"2.7.0\"\n", ""));
        for (Map.Entry<String, String> e : broken.entrySet()) {
            Files.writeString(dir.resolve("probe-line.v1.fragment.yaml"), e.getValue());
            List<SutraProblem> problems = SutraBlocks.load(List.of(dir));
            assertThat(problems).as(e.getKey()).isNotEmpty().allMatch(p -> p.code().equals(SutraDocstring.CODE));
            assertThat(SutraBlocks.find("probe-line@1")).as(e.getKey()).isEmpty();
        }
    }

    @Test
    void aCompositeFragmentPassesItsParametersExplicitlyToTheFragmentsItPins() {
        SutraBlocks.load(List.of(LIBRARY));
        Sutra s = new SutraParser().parse(sutra("  - { use: curve-overview@1, with: { rows: $.CURVE, zero-fmt: pct4, curve-key: F2, table-title: Terms } }\n"),
                "probe.v1.sutra.yaml", "x");
        assertThat(s.panels()).extracting(Panel::id).containsExactly("curve", "points");
        assertThat(s.panels().get(0).key()).isEqualTo("F2");
        assertThat(s.panels().get(0).code()).isNull();
        assertThat(s.panels().get(0).options()).containsEntry("rows", "$.CURVE").containsEntry("fmt", "pct4");
        assertThat(s.panels().get(1).title()).isEqualTo("Terms");
        assertThat(s.panels().get(1).columns().get(3).fmt()).isEqualTo("pct4");
        assertThat(s.panels().get(1).columns().get(4).fmt()).isEqualTo("df4");          // not passed: curve-table's own default
    }

    static String composite(String name, String inner) {
        return FRAGMENT.replace("probe-line", name).replace("  since: \"2.7.0\"", "  uses: [" + inner + "]\n  since: \"2.7.0\"")
                .replace("  - { id: line, kind: line, rows: \"%{rows}\", x: TENOR, y: Y, fmt: \"%{fmt}\" }",
                        "  - { use: " + inner + ", with: { rows: \"%{rows}\", fmt: \"%{fmt}\" } }");
    }

    @Test
    void compositesPinVersionsListTheirInnerFragmentsAndNeitherCycleNorNestPastThree() throws Exception {
        Files.writeString(dir.resolve("probe-line.v1.fragment.yaml"), FRAGMENT);
        Files.writeString(dir.resolve("one.v1.fragment.yaml"), composite("one", "probe-line@1"));
        Files.writeString(dir.resolve("two.v1.fragment.yaml"), composite("two", "one@1"));
        assertThat(SutraBlocks.load(List.of(dir))).isEmpty();
        assertThat(new SutraParser().parse(sutra("  - { use: two@1 }\n"), "probe.v1.sutra.yaml", "x").panels()).extracting(Panel::id)
                .containsExactly("line");                                                    // two > one > probe-line: three fragments deep

        Files.writeString(dir.resolve("four.v1.fragment.yaml"), composite("four", "two@1").replace("one@1", "two@1"));
        Files.writeString(dir.resolve("three.v1.fragment.yaml"), composite("three", "two@1"));
        assertThat(SutraBlocks.load(List.of(dir))).extracting(SutraProblem::message).anyMatch(m -> m.contains("nest at most 3"));

        Files.delete(dir.resolve("four.v1.fragment.yaml"));
        Files.delete(dir.resolve("three.v1.fragment.yaml"));
        Files.writeString(dir.resolve("loop-a.v1.fragment.yaml"), composite("loop-a", "loop-b@1"));
        Files.writeString(dir.resolve("loop-b.v1.fragment.yaml"), composite("loop-b", "loop-a@1"));
        assertThat(SutraBlocks.load(List.of(dir))).extracting(SutraProblem::message).anyMatch(m -> m.contains("uses itself"));

        Files.delete(dir.resolve("loop-a.v1.fragment.yaml"));
        Files.delete(dir.resolve("loop-b.v1.fragment.yaml"));
        Files.writeString(dir.resolve("one.v1.fragment.yaml"), composite("one", "probe-line@1").replace("use: probe-line@1, with", "use: probe-line, with"));
        assertThat(SutraBlocks.load(List.of(dir))).extracting(SutraProblem::message).anyMatch(m -> m.contains("without a version"));
        Files.writeString(dir.resolve("one.v1.fragment.yaml"), composite("one", "probe-line@1").replace("uses: [probe-line@1]", "uses: []"));
        assertThat(SutraBlocks.load(List.of(dir))).extracting(SutraProblem::message).anyMatch(m -> m.contains("doc.uses lists"));
    }

    static final String TABLE = """
            rachana: 1
            fragment: probe-table
            version: 1
            params: { rows: $.ROWS, columns: [] }
            doc:
              summary: A table.
              description: A table whose columns are a parameter.
              params:
                rows: { type: expression, default: $.ROWS, meaning: The rows }
                columns: { type: columns, default: [], meaning: "The columns, each { label, bind, fmt, total, tone }" }
              emits: []
              expects: []
              examples:
                - { use: probe-table@1, with: { columns: [ { label: Trade, bind: "@.ID" } ] } }
              since: "2.7.0"
            panels:
              - id: rows
                kind: table
                rows: %{rows}
                columns: %{columns}
            """;

    @Test
    void aColumnsParameterIsAListOfColumnsFilledInAsWritten() throws Exception {
        Files.writeString(dir.resolve("probe-table.v1.fragment.yaml"), TABLE);
        assertThat(SutraBlocks.load(List.of(dir))).isEmpty();
        Sutra s = new SutraParser().parse(sutra("""
                  - use: probe-table@1
                    with:
                      rows: $.TRADES
                      columns:
                        - { label: Trade, bind: "link(@.TRADE_ID, 'trade')" }
                        - { label: "Notional \\"x\\"", bind: "@.NOTIONAL", fmt: amount0, total: true }
                        - { label: Side, bind: "@.SIDE == 'B' ? 'Buy' : '–'", tone: status }
                """), "probe.v1.sutra.yaml", "x");
        Panel t = s.panels().get(0);
        assertThat(t.columns()).extracting(c -> c.label()).containsExactly("Trade", "Notional \"x\"", "Side");
        assertThat(t.columns()).extracting(c -> c.bind()).containsExactly("link(@.TRADE_ID, 'trade')", "@.NOTIONAL", "@.SIDE == 'B' ? 'Buy' : '–'");
        assertThat(t.columns().get(1).fmt()).isEqualTo("amount0");
        assertThat(t.columns().get(1).total()).isTrue();
        assertThat(new SutraParser().parse(sutra("  - { use: probe-table@1 }\n"), "probe.v1.sutra.yaml", "x").panels().get(0).columns()).isEmpty();
        Files.writeString(dir.resolve("probe-table.v1.fragment.yaml"), TABLE.replace("default: [], meaning", "default: [x], meaning"));
        assertThat(SutraBlocks.load(List.of(dir))).extracting(SutraProblem::message).anyMatch(m -> m.contains("is not the declared default"));
    }

    @Test
    void anOlderBlockOrASutraWithoutADocIsAWarningOnly() throws Exception {
        Files.writeString(dir.resolve("ticket.v1.block.yaml"), SutraBlocksTest.TICKET);
        assertThat(SutraBlocks.load(List.of(dir))).isEmpty();
        assertThat(SutraBlocks.find("ticket@1")).isPresent();
        assertThat(SutraBlocks.warnings()).extracting(SutraProblem::code).containsExactly(SutraDocstring.CODE);
        PNode plain = new PositionalYamlReader().read(sutra("  - { id: notes, kind: markdown, text: hi }\n"), new ArrayList<>());
        assertThat(SutraDocstring.checkSutra(plain, "sutra probe", "probe.v1.sutra.yaml")).extracting(SutraProblem::message)
                .containsExactly("sutra probe has no doc: block (summary, description, examples, since)");
        assertThat(new SutraParser().parse(sutra("  - { id: notes, kind: markdown, text: hi }\n")
                .replace("panels:", "doc: { summary: A probe. }\npanels:"), "probe.v1.sutra.yaml", "x")).isNotNull();   // doc: is a known key
    }
}
