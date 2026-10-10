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
package com.ash.drishti.server.design;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.search.form.FormField;
import com.ash.drishti.engine.search.form.FormInference;
import com.ash.drishti.engine.search.form.FormSpec;
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.identity.design.StoredDesign.SampleInfo;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The workbench's Form tab: the search form of a Design's kind ({@code FORM <pack>}), edited as a YAML list of fields. This answers what
 * the tab shows for a text: the problems in it, the form it makes, the field palette (the scalar fields of the Design's samples, typed
 * from their values), a starter inferred from the samples, and the form now in force for the kind (the pack's or an approved one).
 * Reading only: nothing is saved here. Samples are read with the caller's rights. Stateless; thread-safe.
 */
@Service
public class DesignFormService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PALETTE_MAX = 80;
    private static final int SAMPLES_READ = 5;

    private final DesignService designs;
    private final SutraRegistry sutras;
    private final ViewPipeline pipeline;
    private final Entitlements entitlements;
    private final JsonCodec codec;
    private final FormDefinitions definitions;
    private final FormInference inference = new FormInference();

    public DesignFormService(DesignService designs, SutraRegistry sutras, ViewPipeline pipeline, Entitlements entitlements, JsonCodec codec,
            FormDefinitions definitions) {
        this.designs = designs;
        this.sutras = sutras;
        this.pipeline = pipeline;
        this.entitlements = entitlements;
        this.codec = codec;
        this.definitions = definitions;
    }

    /** The kind the Design's form is for: its Sutra's match kind, else the Design's kind. */
    public String kindOf(StoredDesign d) {
        try {
            Sutra s = d.sutra == null || d.sutra.isBlank() ? null : sutras.check(d.sutra);
            if (s != null && s.match() != null && s.match().kind() != null && !s.match().kind().isBlank()) {
                return s.match().kind();
            }
        } catch (DrishtiException e) {
            // the Sutra does not check yet: the Design's own kind
        }
        return d.kind;
    }

    /** {@code {rev, kind, form (the text), effective, problems[], spec?, palette[], starter}} for {@code text} (null: the Design's saved form). */
    public ObjectNode answer(Principal who, String id, String text) {
        StoredDesign d = designs.get(who.user(), id);
        String form = text == null ? d.form : text;
        String kind = kindOf(d);
        ObjectNode out = JSON.createObjectNode();
        out.put("rev", d.rev).put("kind", kind).put("form", form == null ? "" : form).put("effective", definitions.yamlOf(kind));
        definitions.packOf(kind).ifPresent(p -> out.put("pack", p.name()));
        ArrayNode problems = out.putArray("problems");
        FormSpec spec = parse(kind, form, problems);
        if (spec != null) {
            out.set("spec", JSON.valueToTree(spec.toMap()));
        }
        Map<String, List<Object>> samples = samples(who, d);
        ArrayNode palette = out.putArray("palette");
        samples.forEach((path, values) -> {
            if (palette.size() >= PALETTE_MAX) {
                return;
            }
            ObjectNode e = palette.addObject();
            e.put("path", path).put("label", FormField.humanize(path)).put("type", inference.typeOf(path, values).id());
            ArrayNode shown = e.putArray("samples");
            values.stream().filter(v -> v != null).map(String::valueOf).distinct().limit(3).forEach(shown::add);
        });
        FormSpec starter = inference.infer(kind, List.copyOf(samples.keySet().stream().limit(12).toList()), List.of(), List.of(), p -> null, samples);
        out.set("starter", JSON.valueToTree(starter.fields().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", f.path());
            m.put("label", f.label());
            m.put("type", f.type().id());
            return m;
        }).toList()));
        return out;
    }

    private FormSpec parse(String kind, String text, ArrayNode problems) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Object raw;
        try {
            raw = new Yaml().load(text);
        } catch (YAMLException e) {
            problems.addObject().put("code", "DRS-2060").put("message", "the form is not valid YAML: " + firstLine(e.getMessage())).put("severity", "error");
            return null;
        }
        List<String> found = new ArrayList<>();
        FormSpec spec = FormSpec.parse(kind, raw, found);
        found.forEach(m -> problems.addObject().put("code", "DRS-2060").put("message", m.replaceFirst("^form\\.[^ :]+[.:]? ?", "")).put("severity", "error"));
        return spec;
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    /** Scalar fields (two levels deep) of the first samples, by path, with their values. */
    private Map<String, List<Object>> samples(Principal who, StoredDesign d) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        for (SampleInfo info : d.samples.stream().limit(SAMPLES_READ).toList()) {
            try {
                JsonNode doc = document(who, d, info);
                walk(doc, "", 0, out);
            } catch (RuntimeException e) {
                // a sample that cannot be read adds nothing to the palette
            }
        }
        return out;
    }

    private JsonNode document(Principal who, StoredDesign d, SampleInfo info) {
        try {
            if (StoredDesign.REF.equals(info.type())) {
                if (!entitlements.mayOpen(who, info.refKind())) {
                    throw new DrishtiException(ErrorCode.FORBIDDEN, Entitlements.DENIED + ": you may not open " + info.refKind() + " entities");
                }
                return JSON.readTree(codec.toJson(pipeline.document(EntityRef.of(info.refKind(), info.refId()), AsOf.LATEST).data()));
            }
            return JSON.readTree(designs.sample(who.user(), d.id, info.name()));
        } catch (java.io.IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the sample could not be read");
        }
    }

    private static void walk(JsonNode n, String prefix, int depth, Map<String, List<Object>> out) {
        if (n == null || !n.isObject()) {
            return;
        }
        n.fields().forEachRemaining(e -> {
            String name = e.getKey();
            if (name.startsWith("_")) {
                return;
            }
            String path = prefix + name;
            JsonNode v = e.getValue();
            if (v.isObject()) {
                if (depth < 1) {
                    walk(v, path + ".", depth + 1, out);
                }
            } else if (v.isValueNode()) {
                Object o = v.isBoolean() ? (Object) v.asBoolean() : v.isNumber() ? (Object) v.numberValue() : v.isNull() ? null : v.asText();
                out.computeIfAbsent(path, k -> new ArrayList<>()).add(o);
            }
        });
    }
}
