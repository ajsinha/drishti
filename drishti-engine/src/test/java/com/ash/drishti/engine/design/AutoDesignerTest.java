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
package com.ash.drishti.engine.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.engine.shape.Sample;
import com.ash.drishti.engine.shape.Shape;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.Semantics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Auto-design: the examples' data draws the kinds their data calls for, every panel has reasons and alternatives, and pruning and suggest work. */
class AutoDesignerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIR = Path.of("..", "docs", "guides", "examples");
    private static final Semantics SEMANTICS = Semantics.defaults();

    private static ShapeService shapes(BuilderProperties props) {
        GraphProperties graph = new GraphProperties(
                List.of(new GraphProperties.IdPattern("^CP-", "counterparty"), new GraphProperties.IdPattern("^NS-", "netting-set")),
                Map.of("nettingSet", new GraphProperties.FieldRef("netting-set", "Netting set")), null, null, null);
        return new ShapeService(props, SEMANTICS, new ReferenceCatalog(graph));
    }

    private static List<Sample> example(String name) throws IOException {
        return List.of(new Sample(name + ".json", JSON.readTree(Files.readString(DIR.resolve(name + ".json")))));
    }

    private static Design design(List<Sample> samples, DesignPreviewer previewer) {
        BuilderProperties props = BuilderProperties.defaults();
        Shape shape = shapes(props).infer(samples);
        return new AutoDesigner(props, SEMANTICS).design(shape, samples, "trade", previewer);
    }

    private static List<String> kinds(Design d) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^    kind: (\\S+)").matcher(d.yaml());
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Map<String, String> kindById(Design d) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^  - id: (\\S+)\\n    kind: (\\S+)").matcher(d.yaml());
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    void theShowcaseDrawsTheKindsItsDataCallsFor() throws IOException {
        Design d = design(example("all-panels-showcase"), null);
        Map<String, String> k = kindById(d);
        assertThat(k.values()).contains("line", "waterfall", "candlestick", "histogram", "graph", "timeline", "status", "pivot");
        assertThat(k.get("pnlhistory")).isEqualTo("line");
        assertThat(k.get("pnlexplain")).isEqualTo("waterfall");
        assertThat(k.get("scenariopnl")).isEqualTo("histogram");
        assertThat(k.get("ohlc")).isEqualTo("candlestick");
        assertThat(k.get("hierarchy")).isEqualTo("graph");
        assertThat(k.get("lifecycle-timeline")).isEqualTo("timeline");
        assertThat(k.get("sensitivities")).isEqualTo("hbar");
        assertThat(k.get("profile")).isEqualTo("area");
        assertThat(k.get("grid")).isEqualTo("surface");
        assertThat(k.get("positions")).isEqualTo("pivot");
        assertThat(k.get("booktree")).isEqualTo("table");
        assertThat(d.yaml()).contains("children: \"@.children\"").contains("by: [");
    }

    @Test
    void everyExampleDrawsATitleAStripAndPanelsWithReasonsAndAlternatives() throws IOException {
        try (var files = Files.list(DIR)) {
            for (String name : files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.replace(".json", "")).toList()) {
                Design d = design(example(name), null);
                assertThat(d.yaml()).as(name).contains("title:", "panels:", "match: { kind: trade");
                assertThat(d.reasons()).as(name).containsKeys("title", "strip");
                Map<String, String> ids = kindById(d);
                assertThat(ids.size()).as(name + " " + ids).isGreaterThanOrEqualTo(2);
                assertThat(ids).as(name).containsKey("built");
                ids.forEach((id, kind) -> {
                    if (!"provenance".equals(kind) && !"links".equals(kind)) {
                        assertThat(d.reasons()).as(name + " reason for " + id).containsKey(id);
                    }
                });
                if (!name.equals("linked-sources")) {
                    assertThat(d.alternatives().values().stream().anyMatch(a -> !a.isEmpty())).as(name + " has alternatives").isTrue();
                }
                assertThat(d.alternatives().values().stream().flatMap(List::stream).allMatch(a -> !a.reason().isBlank() && !a.options().isEmpty() || "links".equals(a.kind()) || "kv".equals(a.kind())))
                        .as(name).isTrue();
                assertThat(d.yaml().lines().filter(l -> l.startsWith("  - { label:")).count()).as(name + " strip").isLessThanOrEqualTo(6);
            }
        }
    }

    @Test
    void specificExamplesUseTheirExpectedKinds() throws IOException {
        assertThat(kindById(design(example("market-charts"), null)).values()).contains("candlestick", "surface", "line");
        assertThat(kindById(design(example("pivot-row-groups"), null)).values()).contains("pivot");
        assertThat(design(example("pivot-row-groups"), null).yaml()).contains("by: [");
        assertThat(kindById(design(example("tree-table"), null)).get("units")).isEqualTo("table");
        assertThat(design(example("tree-table"), null).yaml()).contains("children: \"@.children\"");
        assertThat(kindById(design(example("pnl-explain"), null)).values()).contains("waterfall");
        assertThat(kindById(design(example("exposure-profile"), null)).get("profile")).isEqualTo("area");
        assertThat(kindById(design(example("relationships"), null)).values()).contains("graph");
        assertThat(kindById(design(example("operations-status"), null)).values()).contains("timeline", "status");
        assertThat(kindById(design(example("risk-distribution"), null)).values()).contains("histogram");
    }

    @Test
    void linksGetKeysAndTheTitleShowsTheIdAndALink() throws IOException {
        Design d = design(example("all-panels-showcase"), null);
        assertThat(d.yaml()).contains("id: \"$.tradeId\"").contains("keys: {").contains("F7: \"link($.nettingSet, 'netting-set')\"");
        assertThat(d.yaml()).contains("with: \"link($.counterparty.id, 'counterparty', $.counterparty.name)\"");
    }

    // ----------------------------------------------------------------------------------------------- sample-aware

    private static JsonNode doc(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<Sample> varied(int n, boolean withExtra) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ObjectNode o = (ObjectNode) doc("{\"tradeId\":\"TRD-" + (1000 + i) + "\",\"book\":\"B" + (i % 2) + "\",\"fixed\":5,\"mtm\":" + (1000 * (i + 1) * (i % 2 == 0 ? 1 : 3))
                    + ",\"notional\":" + (500000 + i * 10) + ",\"pnlHistory\":[{\"date\":\"2026-09-01\",\"pnl\":1},{\"date\":\"2026-09-02\",\"pnl\":3},{\"date\":\"2026-09-03\",\"pnl\":2},"
                    + "{\"date\":\"2026-09-04\",\"pnl\":5},{\"date\":\"2026-09-05\",\"pnl\":4},{\"date\":\"2026-09-06\",\"pnl\":6}]}");
            if (withExtra && i == 0) {
                o.set("rareSeries", doc("[{\"date\":\"2026-09-01\",\"v\":1},{\"date\":\"2026-09-02\",\"v\":2},{\"date\":\"2026-09-03\",\"v\":4},"
                        + "{\"date\":\"2026-09-04\",\"v\":3},{\"date\":\"2026-09-05\",\"v\":5},{\"date\":\"2026-09-06\",\"v\":7}]"));
            }
            out.add(new Sample("s" + i + ".json", o));
        }
        return out;
    }

    private static boolean present(JsonNode document, String id) {
        for (var it = document.fieldNames(); it.hasNext();) {
            if (it.next().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** Renders only what a document has: a panel is empty when the document lacks the field its id names. */
    private static DesignPreviewer byPresence() {
        return (yaml, kind, document) -> {
            List<ViewModel.PanelView> panels = new ArrayList<>();
            Matcher m = Pattern.compile("(?m)^  - id: (\\S+)\\n    kind: (\\S+)").matcher(yaml);
            while (m.find()) {
                String id = m.group(1);
                boolean has = present(document, id) || id.equals("built") || id.equals("refs");
                panels.add(new ViewModel.PanelView(id, m.group(2), id, null, null, "main", false, null, null, null, !has));
            }
            return new ViewModel(new ViewModel.Ref(kind, "x"), null, null, List.of(), panels, List.of(), null, Map.of());
        };
    }

    @Test
    void aPanelOnAFieldMissingFromMostSamplesIsPrunedWithAReason() {
        Design d = design(varied(5, true), byPresence());
        assertThat(d.pruned()).anySatisfy(p -> {
            assertThat(p.panel()).isEqualTo("rareseries");
            assertThat(p.action()).isEqualTo("dropped");
            assertThat(p.bad()).isEqualTo(4);
            assertThat(p.of()).isEqualTo(5);
            assertThat(p.reason()).contains("empty in 4 of 5 samples");
        });
        assertThat(kindById(d)).doesNotContainKey("rareseries").containsKey("pnlhistory");
        assertThat(d.preview()).isNotNull();
    }

    @Test
    void aPanelMissingInFewSamplesStaysAndSaysSo() {
        Design d = design(varied(5, true), byPresence());
        List<Sample> mostly = new ArrayList<>(varied(5, true));
        mostly.set(1, new Sample("s1.json", withRare(mostly.get(1))));
        mostly.set(2, new Sample("s2.json", withRare(mostly.get(2))));
        mostly.set(3, new Sample("s3.json", withRare(mostly.get(3))));
        Design e = design(mostly, byPresence());
        assertThat(e.pruned().stream().filter(p -> p.panel().equals("rareseries"))).isEmpty();
        assertThat(e.reasons().get("rareseries")).contains("empty in 1 of 5 samples").contains("so it stays");
        assertThat(d.pruned()).isNotEmpty();
    }

    private static JsonNode withRare(Sample s) {
        ObjectNode o = ((ObjectNode) s.document()).deepCopy();
        o.set("rareSeries", doc("[{\"date\":\"2026-09-01\",\"v\":1},{\"date\":\"2026-09-02\",\"v\":2},{\"date\":\"2026-09-03\",\"v\":4},"
                + "{\"date\":\"2026-09-04\",\"v\":3},{\"date\":\"2026-09-05\",\"v\":5},{\"date\":\"2026-09-06\",\"v\":7}]"));
        return o;
    }

    @Test
    void thePruneShareIsConfigurable() {
        BuilderProperties strict = new BuilderProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0.1, null,
                null, null, null, null, null, null, null, null, null, null, null);
        List<Sample> samples = new ArrayList<>(varied(5, true));
        samples.set(1, new Sample("s1.json", withRare(samples.get(1))));
        samples.set(2, new Sample("s2.json", withRare(samples.get(2))));
        samples.set(3, new Sample("s3.json", withRare(samples.get(3))));
        Shape shape = shapes(strict).infer(samples);
        Design d = new AutoDesigner(strict, SEMANTICS).design(shape, samples, "trade", byPresence());
        assertThat(d.pruned()).anyMatch(p -> p.panel().equals("rareseries"));
    }

    @Test
    void aPanelThatFailsIsDemotedToItsRunnerUpWhenThatWorks() {
        List<Sample> samples = varied(4, false);
        DesignPreviewer lineBreaks = (yaml, kind, document) -> {
            List<ViewModel.PanelView> panels = new ArrayList<>();
            Matcher m = Pattern.compile("(?m)^  - id: (\\S+)\\n    kind: (\\S+)").matcher(yaml);
            while (m.find()) {
                boolean bad = m.group(2).equals("line");
                panels.add(new ViewModel.PanelView(m.group(1), m.group(2), "t", null, null, "main", false, null, null, bad ? "boom" : null, false));
            }
            return new ViewModel(new ViewModel.Ref(kind, "x"), null, null, List.of(), panels, List.of(), null, Map.of());
        };
        Design d = design(samples, lineBreaks);
        assertThat(d.pruned()).anySatisfy(p -> {
            assertThat(p.panel()).isEqualTo("pnlhistory");
            assertThat(p.action()).isEqualTo("demoted");
            assertThat(p.kind()).isEqualTo("line");
            assertThat(p.to()).isEqualTo("area");
            assertThat(p.reason()).contains("an error in 4 of 4 samples (boom)");
        });
        assertThat(kindById(d).get("pnlhistory")).isEqualTo("area");
    }

    @Test
    void theStripIsCappedAndEmphasisGoesToTheMostVaryingMeasure() {
        Design d = design(varied(6, false), null);
        assertThat(d.yaml()).contains("emphasis: true");
        String strip = d.yaml().split("strip:\n")[1].split("panels:")[0];
        assertThat(strip.lines().filter(l -> l.contains("emphasis: true")).findFirst().orElse("")).contains("Mtm");
        assertThat(d.reasons().get("strip.Mtm")).contains("emphasised");
        assertThat(d.reasons().get("strip.Fixed")).contains("same in every sample");
        assertThat(strip.lines().filter(l -> l.startsWith("  - {")).count()).isLessThanOrEqualTo(6);
    }

    @Test
    void aFieldInFewSamplesIsLeftOutOfTheStrip() {
        List<Sample> samples = new ArrayList<>(varied(5, false));
        ObjectNode o = ((ObjectNode) samples.get(0).document()).deepCopy();
        o.put("rareAmount", 12345.5);
        samples.set(0, new Sample("s0.json", o));
        Design d = design(samples, null);
        assertThat(d.reasons().get("strip.Rare amount")).contains("left out").contains("1 of 5");
        assertThat(d.yaml()).doesNotContain("$.rareAmount\", fmt");
    }

    // ---------------------------------------------------------------------------------------------------- suggest

    private static String first(String example, String path) throws IOException {
        List<Sample> samples = example(example);
        BuilderProperties props = BuilderProperties.defaults();
        Shape shape = shapes(props).infer(samples);
        List<PanelChoice> s = new AutoDesigner(props, SEMANTICS).suggest(shape, samples, path, null);
        assertThat(s).as(path).isNotEmpty();
        assertThat(s).allSatisfy(c -> assertThat(c.reason()).isNotBlank());
        return s.get(0).kind();
    }

    @Test
    void suggestReturnsTheExpectedFirstChoicePerRole() throws IOException {
        assertThat(first("all-panels-showcase", "$.pnlHistory")).isEqualTo("line");
        assertThat(first("all-panels-showcase", "$.sensitivities")).isEqualTo("hbar");
        assertThat(first("all-panels-showcase", "$.pnlExplain")).isEqualTo("waterfall");
        assertThat(first("all-panels-showcase", "$.scenarioPnl")).isEqualTo("histogram");
        assertThat(first("all-panels-showcase", "$.ohlc")).isEqualTo("candlestick");
        assertThat(first("all-panels-showcase", "$.grid")).isEqualTo("surface");
        assertThat(first("all-panels-showcase", "$.hierarchy")).isEqualTo("graph");
        assertThat(first("all-panels-showcase", "$.lifecycle.timeline")).isEqualTo("timeline");
        assertThat(first("all-panels-showcase", "$.bookTree")).isEqualTo("table");
        assertThat(first("all-panels-showcase", "$.confirmation.status")).isEqualTo("status");
        assertThat(first("all-panels-showcase", "$.counterparty")).isEqualTo("links");
        assertThat(first("all-panels-showcase", "$.mtm")).isEqualTo("gauge");
        assertThat(first("all-panels-showcase", "$.terms")).isEqualTo("kv");
        assertThat(first("all-panels-showcase", "$.profile")).isEqualTo("area");
        assertThat(first("all-panels-showcase", "$.positions")).isEqualTo("pivot");
    }

    @Test
    void suggestForADimensionAndAMeasureIsAPivotWithRowGroups() throws IOException {
        List<Sample> samples = example("all-panels-showcase");
        BuilderProperties props = BuilderProperties.defaults();
        Shape shape = shapes(props).infer(samples);
        AutoDesigner designer = new AutoDesigner(props, SEMANTICS);
        List<PanelChoice> s = designer.suggest(shape, samples, "$.positions[].book", "$.positions[].mtm");
        assertThat(s.get(0).kind()).isEqualTo("pivot");
        assertThat(s.get(0).options()).containsKeys("rows", "by", "across", "value");
        assertThat(s.get(0).options().get("rows")).isEqualTo("$.positions");
        assertThat(designer.suggest(shape, samples, "$.positions[].mtm", "$.positions[].book").get(0).kind()).isEqualTo("pivot");
        assertThat(designer.suggest(shape, samples, "$.positions[].mtm", "$.positions[].trades").get(0).kind()).isEqualTo("scatter");
        assertThat(designer.suggest(shape, samples, "$.positions[].book", null).get(0).kind()).isEqualTo("pivot");
        assertThat(designer.suggest(shape, samples, "$.positions[].mtm", null).get(0).kind()).isEqualTo("histogram");
    }

    @Test
    void suggestRefusesAPathThatIsNotInTheShape() throws IOException {
        List<Sample> samples = example("tree-table");
        BuilderProperties props = BuilderProperties.defaults();
        Shape shape = shapes(props).infer(samples);
        assertThatThrownBy(() -> new AutoDesigner(props, SEMANTICS).suggest(shape, samples, "$.nope", null)).hasMessageContaining("no such path");
    }

    @Test
    void designIsDeterministic() throws IOException {
        assertThat(design(example("all-panels-showcase"), null).yaml()).isEqualTo(design(example("all-panels-showcase"), null).yaml());
    }

    @Test
    void kindsListIsNotEmptyForEveryExample() throws IOException {
        assertThat(kinds(design(example("linked-sources"), null))).isNotEmpty();
    }
}
