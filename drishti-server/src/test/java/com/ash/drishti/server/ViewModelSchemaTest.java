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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.command.CommandParser;
import com.ash.drishti.engine.live.Frame;
import com.ash.drishti.engine.live.Patch;
import com.ash.drishti.engine.live.PatchDiffer;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModelSchema;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import com.ash.drishti.server.api.ViewModelSchemaController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The ViewModel's JSON Schema ({@code GET /api/v1/viewmodel/schema}, VIEWMODEL.md section 9) against real views and frames: the
 * showcase with every panel kind, the reference trades, broken documents, masked values, panels denied, and live frames. Validated
 * as the server writes them (its own ObjectMapper). No JSON Schema library is in the build, so {@link Check} is a small validator of
 * the keywords the schema uses; it fails on any keyword it does not know, so the schema cannot outgrow it unnoticed.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false"})
class ViewModelSchemaTest {

    private static final Path EXAMPLES = Path.of("..", "docs", "guides", "examples");

    @Autowired ViewPipeline pipeline;
    @Autowired CommandParser parser;
    @Autowired JsonCodec codec;
    @Autowired ObjectMapper mapper;

    private JsonNode schema() {
        return mapper.valueToTree(new ViewModelSchemaController().schema());
    }

    private Sutra showcase() throws Exception {
        return new SutraParser().parse(Files.readString(EXAMPLES.resolve("all-panels-showcase.sutra.yaml")), "all-panels-showcase.sutra.yaml", "test");
    }

    private EntityDocument showcaseDoc(long generation, UnaryOperator<String> edit) throws Exception {
        String json = edit.apply(Files.readString(EXAMPLES.resolve("all-panels-showcase.json")));
        return new EntityDocument(EntityRef.of("trade", "DEMO-BOND-1"), codec.read(json), new Provenance("test", generation, Instant.now(), true));
    }

    /** The errors of {@code value} against the schema's {@code def} (the root ViewModel when null). */
    private List<String> errors(Object value, String def) {
        JsonNode root = schema();
        JsonNode node = mapper.valueToTree(value);
        List<String> out = new ArrayList<>();
        new Check(root, out).validate(def == null ? root : root.at("/$defs/" + def), node, "$");
        return out;
    }

    private static Set<String> kinds(List<ViewModel.PanelView> panels) {
        Set<String> out = new TreeSet<>();
        for (ViewModel.PanelView p : panels) {
            out.add(p.kind());
            if (p.data() instanceof PanelData.Tabs t) {
                t.tabs().forEach(tab -> out.addAll(kinds(tab.panels())));
            }
        }
        return out;
    }

    @Test
    void theSchemaNamesEveryPanelKindAndEveryDataRecord() {
        Set<String> all = Arrays.stream(PanelKind.values()).map(PanelKind::id).collect(Collectors.toCollection(TreeSet::new));
        assertThat(ViewModelSchema.DATA_BY_KIND.keySet()).as("a data record for every PanelKind").containsExactlyInAnyOrderElementsOf(all);
        assertThat(new HashSet<Class<?>>(ViewModelSchema.DATA_BY_KIND.values())).as("every PanelData record belongs to a kind")
                .containsExactlyInAnyOrder(PanelData.class.getPermittedSubclasses());
        JsonNode s = schema();
        assertThat(s.at("/$ref").asText()).isEqualTo("#/$defs/ViewModel");
        List<String> enumKinds = new ArrayList<>();
        s.at("/$defs/PanelView/properties/kind/enum").forEach(n -> enumKinds.add(n.asText()));
        assertThat(enumKinds).containsExactlyInAnyOrderElementsOf(all);
        assertThat(s.at("/$defs/Frame/properties/patches/anyOf/0/items/$ref").asText()).isEqualTo("#/$defs/Patch");
    }

    @Test
    void theShowcaseHoldsEveryKindAndMatchesTheSchema() throws Exception {
        ViewModel v = pipeline.preview(Optional.of(showcase()), showcaseDoc(1, x -> x));
        assertThat(kinds(v.panels())).as("the showcase covers every kind the schema names").containsAll(ViewModelSchema.DATA_BY_KIND.keySet());
        assertThat(errors(v, null)).isEmpty();
    }

    @Test
    void theReferenceViewsAndBrokenDocumentsMatchTheSchema() throws Exception {
        for (String cmd : List.of("TRD IRS-48213", "NSET NS-NORTH-01")) {
            assertThat(errors(pipeline.view(parser.require(cmd)), null)).as(cmd).isEmpty();
        }
        for (String id : List.of("FXS-20931", "CFT-77120")) {
            assertThat(errors(pipeline.view(EntityRef.of("trade", id)), null)).as(id).isEmpty();
        }
        EntityDocument broken = new EntityDocument(EntityRef.of("trade", "IRS-1001"), codec.read(ChartPanelsTest.DOC), new Provenance("test", 1, Instant.now(), false));
        ViewModel charts = pipeline.preview(Optional.of(ChartPanelsTest.SUTRA), broken);
        assertThat(errors(charts, null)).as("waterfall, histogram, scatter, candlestick, graph, timeline, pivots and trees on a broken document").isEmpty();
        EntityDocument empty = new EntityDocument(EntityRef.of("trade", "DEMO-BOND-1"), codec.read("{\"TRADE_ID\": \"DEMO-BOND-1\"}"),
                new Provenance("test", 1, Instant.now(), false));
        ViewModel nothing = pipeline.preview(Optional.of(showcase()), empty);
        assertThat(nothing.panels()).anyMatch(ViewModel.PanelView::empty);
        assertThat(errors(nothing, null)).as("every kind with nothing to show").isEmpty();
    }

    @Test
    void maskedValuesAndDeniedPanelsMatchTheSchema() throws Exception {
        Set<String> masked = Set.of("MTM", "NOTIONAL", "PNL", "DV01", "COUNTERPARTY", "CLOSE", "VALUE");
        UnaryOperator<DataNode> redact = d -> mask(d, masked);
        ViewModel v = pipeline.preview(Optional.of(showcase()), showcaseDoc(1, x -> x), redact, k -> false);
        assertThat(v.provenance().masked()).isPositive();
        assertThat(v.provenance().maskedPanels()).isNotEmpty();
        assertThat(errors(v, null)).isEmpty();
        ViewModel denied = pipeline.view(EntityRef.of("trade", "IRS-48213"), com.ash.drishti.api.AsOf.LATEST, UnaryOperator.identity(),
                k -> !"curve".equals(k), com.ash.drishti.engine.control.ControlValues.NONE);
        assertThat(denied.panels()).anyMatch(p -> "no access to curve".equals(p.denied()) && p.data() == null);
        assertThat(errors(denied, null)).as("a panel whose source the caller may not open").isEmpty();
    }

    private static DataNode mask(DataNode d, Set<String> names) {
        if (d instanceof DataNode.Obj o) {
            Map<String, DataNode> f = new LinkedHashMap<>();
            o.fields().forEach((k, x) -> f.put(k, names.contains(k) ? DataNode.masked() : mask(x, names)));
            return new DataNode.Obj(f);
        }
        if (d instanceof DataNode.Arr a) {
            return new DataNode.Arr(a.elements().stream().map(x -> mask(x, names)).toList());
        }
        return d;
    }

    @Test
    void liveFramesMatchTheSchema() throws Exception {
        ViewModel before = pipeline.preview(Optional.of(showcase()), showcaseDoc(1, x -> x));
        ViewModel after = pipeline.preview(Optional.of(showcase()), showcaseDoc(2, x -> x.replace("\"MTM\": 1209000", "\"MTM\": -1300000")));
        List<Patch> patches = new PatchDiffer().diff(before, after);
        assertThat(patches).extracting(Patch::op).contains("strip", "panel", "provenance");
        assertThat(errors(new Frame(1, 2, patches, 0.4, 1.9), "Frame")).isEmpty();
        Frame gone = new Frame(2, 2, List.of(new Patch("deleted", null, null, null, null, Instant.parse("2026-10-01T09:30:05Z"))), 0.1, 1.9);
        Frame back = new Frame(3, 3, List.of(new Patch("restored", null, null, null, null)), 0.1, 1.9);
        assertThat(errors(gone, "Frame")).isEmpty();
        assertThat(errors(back, "Frame")).isEmpty();
    }

    @Test
    void theValidatorRejectsWhatTheSchemaForbids() throws Exception {
        ViewModel v = pipeline.preview(Optional.of(showcase()), showcaseDoc(1, x -> x));
        JsonNode node = mapper.valueToTree(v);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("surprise", 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("panels").get(0)).put("kind", "sparkline");
        JsonNode table = null;
        for (JsonNode p : node.get("panels")) {
            if ("gauge".equals(p.get("kind").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) p).set("data", mapper.valueToTree(Map.of("fields", List.of())));
            }
            if ("table".equals(p.get("kind").asText())) {
                table = p;
            }
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) table.get("data")).put("rows", "none");
        List<String> out = new ArrayList<>();
        JsonNode root = schema();
        new Check(root, out).validate(root, node, "$");
        assertThat(String.join("\n", out)).contains("$.surprise", "sparkline", "gauge", "$.panels[");
        assertThat(out.size()).isGreaterThanOrEqualTo(4);
    }

    /** A validator for the JSON Schema keywords {@link ViewModelSchema} writes; an unknown keyword is an error. */
    static final class Check {

        private static final Set<String> KNOWN = Set.of("$schema", "$id", "title", "description", "$ref", "$defs", "type", "properties",
                "required", "additionalProperties", "items", "enum", "const", "anyOf", "allOf", "if", "then", "format");
        private final JsonNode root;
        private final List<String> errors;

        Check(JsonNode root, List<String> errors) {
            this.root = root;
            this.errors = errors;
        }

        boolean validate(JsonNode schema, JsonNode value, String at) {
            int before = errors.size();
            schema.fieldNames().forEachRemaining(k -> {
                if (!KNOWN.contains(k)) {
                    errors.add(at + ": the schema uses " + k + ", which this check does not know");
                }
            });
            if (schema.has("$ref")) {
                validate(root.at(schema.get("$ref").asText().substring(1)), value, at);
            }
            if (schema.has("type") && !typeOk(schema.get("type").asText(), value)) {
                errors.add(at + ": expected " + schema.get("type").asText() + ", got " + value);
                return false;
            }
            if (schema.has("enum")) {
                boolean found = false;
                for (JsonNode e : schema.get("enum")) {
                    found |= e.equals(value);
                }
                if (!found) {
                    errors.add(at + ": " + value + " is not one of " + schema.get("enum"));
                }
            }
            if (schema.has("const") && !schema.get("const").equals(value)) {
                errors.add(at + ": expected " + schema.get("const"));
            }
            if (schema.has("anyOf")) {
                boolean any = false;
                for (JsonNode alt : schema.get("anyOf")) {
                    List<String> trial = new ArrayList<>();
                    if (new Check(root, trial).validate(alt, value, at) && trial.isEmpty()) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    List<String> first = new ArrayList<>();
                    new Check(root, first).validate(schema.get("anyOf").get(0), value, at);
                    errors.add(at + ": matches no alternative (" + (first.isEmpty() ? value : first.get(0)) + ")");
                }
            }
            if (schema.has("allOf")) {
                for (JsonNode part : schema.get("allOf")) {
                    validate(part, value, at);
                }
            }
            if (schema.has("if")) {
                List<String> trial = new ArrayList<>();
                new Check(root, trial).validate(schema.get("if"), value, at);
                if (trial.isEmpty() && schema.has("then")) {
                    validate(schema.get("then"), value, at + "(kind " + value.path("kind").asText() + ")");
                }
            }
            if (value.isObject()) {
                JsonNode props = schema.get("properties");
                if (schema.has("required")) {
                    for (JsonNode r : schema.get("required")) {
                        if (!value.has(r.asText())) {
                            errors.add(at + ": missing " + r.asText());
                        }
                    }
                }
                value.fieldNames().forEachRemaining(k -> {
                    if (props != null && props.has(k)) {
                        validate(props.get(k), value.get(k), at + "." + k);
                    } else if (schema.has("additionalProperties")) {
                        JsonNode extra = schema.get("additionalProperties");
                        if (extra.isBoolean() && !extra.asBoolean()) {
                            errors.add(at + "." + k + ": not in the schema");
                        } else if (extra.isObject()) {
                            validate(extra, value.get(k), at + "." + k);
                        }
                    }
                });
            }
            if (value.isArray() && schema.has("items")) {
                for (int i = 0; i < value.size(); i++) {
                    validate(schema.get("items"), value.get(i), at + "[" + i + "]");
                }
            }
            return errors.size() == before;
        }

        private static boolean typeOk(String type, JsonNode v) {
            return switch (type) {
                case "object" -> v.isObject();
                case "array" -> v.isArray();
                case "string" -> v.isTextual();
                case "boolean" -> v.isBoolean();
                case "integer" -> v.isIntegralNumber() || (v.isNumber() && v.asDouble() == Math.rint(v.asDouble()));
                case "number" -> v.isNumber();
                case "null" -> v.isNull();
                default -> false;
            };
        }
    }
}
