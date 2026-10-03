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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.AutoDesigner;
import com.ash.drishti.engine.design.Design;
import com.ash.drishti.engine.design.DesignPreviewer;
import com.ash.drishti.engine.design.PanelChoice;
import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.engine.shape.RoleInfo;
import com.ash.drishti.engine.shape.Sample;
import com.ash.drishti.engine.shape.Shape;
import com.ash.drishti.engine.shape.ShapeException;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Screen Builder: step 1, {@code POST /api/v1/builder/shape}, then step 3, {@code POST /api/v1/builder/design} and
 * {@code /suggest}. Step 1: {@code POST /api/v1/builder/shape} turns up to {@code drishti.builder.max-samples} JSON
 * documents into one JSON Schema with roles and a report. For authors only (as Studio); writes nothing anywhere;
 * logs counts, never content. The body is size-checked before it is read and parsed one sample at a time, so an
 * over-limit request is refused before everything is parsed.
 */
@RestController
@RequestMapping("/api/v1/builder")
public class BuilderController {

    private static final Logger LOG = LoggerFactory.getLogger(BuilderController.class);

    private final ShapeService shapes;
    private final AutoDesigner designer;
    private final ViewPipeline pipeline;
    private final SutraRegistry sutras;
    private final com.ash.drishti.common.JsonCodec codec;
    private final BuilderProperties limits;
    private final Entitlements entitlements;
    private final ObjectMapper mapper;

    public BuilderController(ShapeService shapes, Entitlements entitlements, AutoDesigner designer, ViewPipeline pipeline,
            SutraRegistry sutras, com.ash.drishti.common.JsonCodec codec) {
        this.shapes = shapes;
        this.designer = designer;
        this.pipeline = pipeline;
        this.sutras = sutras;
        this.codec = codec;
        this.limits = shapes.limits();
        this.entitlements = entitlements;
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(limits.maxDepth() + 3).build()).build();
        this.mapper = new ObjectMapper(factory);
    }

    /**
     * Body {@code {"samples": [{"name": "a.json", "document": {...}}, ...]}}: the shape of the documents, as
     * {@code {schema, roles, report}}.
     */
    @PostMapping(path = "/shape", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Shape shape(HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) throws IOException {
        byte[] body = read(request);
        List<Sample> samples = parse(body).samples();
        if (samples.isEmpty()) {
            throw bad("'samples' is required: a list of {name, document}");
        }
        LOG.info("builder shape: {} sample(s), {} bytes", samples.size(), body.length);
        return shapes.infer(samples);
    }

    /**
     * Body {@code {"samples": [...]}} and/or {@code {"shape": {schema, roles}}}, optional {@code "kind"}: a drafted Sutra
     * ({@code yaml}), a {@code reasons} entry for every decision, runner-up {@code alternatives} per panel, what was
     * {@code pruned} because the samples could not fill it, and a {@code preview} of the first sample. With samples the draft is
     * previewed against each of them and panels empty or failing for more than {@code drishti.builder.prune-share} of them
     * are dropped or demoted. Open to every signed-in user (designing is not saving); writes nothing.
     */
    @PostMapping(path = "/design", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Design design(HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) throws IOException {
        byte[] body = read(request);
        Request req = parse(body);
        if (req.samples().isEmpty() && req.shape() == null) {
            throw bad("send 'samples' (a list of {name, document}) or a 'shape' ({schema, roles})");
        }
        String kind = req.kind() == null || req.kind().isBlank() ? "sample" : req.kind();
        Shape shape = req.shape() != null ? req.shape() : shapes.infer(req.samples());
        LOG.info("builder design: {} sample(s), {} bytes", req.samples().size(), body.length);
        return designer.design(shape, req.samples(), kind, previewer(principal));
    }

    /**
     * Body {@code {"shape": {schema, roles}, "path": "$.profile", "at": "$.rows[].mtm"}} (or {@code samples} instead of
     * {@code shape}): the panel kinds that suit the field at {@code path}, best first, each with options filled and a reason.
     * {@code at} names a second field of the same rows (drop a measure on a dimension): then the choices are for the pair.
     */
    @PostMapping(path = "/suggest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> suggest(HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal principal)
            throws IOException {
        Request req = parse(read(request));
        if (req.path() == null || req.path().isBlank()) {
            throw bad("'path' is required: the field to suggest panels for, for example \"$.profile\"");
        }
        if (req.samples().isEmpty() && req.shape() == null) {
            throw bad("send a 'shape' ({schema, roles}) or 'samples' to infer one from");
        }
        Shape shape = req.shape() != null ? req.shape() : shapes.infer(req.samples());
        List<PanelChoice> choices = designer.suggest(shape, req.samples(), req.path(), req.at());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", req.path());
        if (req.at() != null && !req.at().isBlank()) {
            out.put("at", req.at());
        }
        out.put("suggestions", choices);
        return out;
    }

    /** Studio's preview path: the Sutra text against a pasted document, links masked and restricted for the caller. */
    private DesignPreviewer previewer(Principal principal) {
        Object[] last = new Object[2];
        return (yaml, kind, document) -> {
            if (!yaml.equals(last[0])) {
                last[1] = sutras.check(yaml);
                last[0] = yaml;
            }
            EntityDocument doc = new EntityDocument(EntityRef.of(kind, "SAMPLE"), codec.read(document.toString()),
                    new com.ash.drishti.api.Provenance("auto-design sample JSON", 0, java.time.Instant.now(), false));
            return entitlements.restrict(principal,
                    pipeline.preview(Optional.of((Sutra) last[1]), doc, entitlements.redactor(principal), k -> entitlements.mayOpen(principal, k)));
        };
    }

    /** The body, refused as soon as it is known to be over the total limit (by its length, else by reading one byte past it). */
    private byte[] read(HttpServletRequest request) throws IOException {
        long max = limits.maxTotalBytes();
        if (request.getContentLengthLong() > max) {
            throw tooBig("the request is " + request.getContentLengthLong() + " bytes, over the limit of " + limits.maxTotalMb()
                    + " MB (drishti.builder.max-total-mb)");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
            if (body.length > max) {
                throw tooBig("the request is over the limit of " + limits.maxTotalMb() + " MB (drishti.builder.max-total-mb)");
            }
            return body;
        }
    }

    /** What a builder request carries; {@code samples} may be empty when a shape is given. */
    private record Request(List<Sample> samples, Shape shape, String kind, String path, String at) {}

    private Request parse(byte[] body) throws IOException {
        List<Sample> samples = new ArrayList<>();
        Shape shape = null;
        String kind = null;
        String path = null;
        String at = null;
        boolean found = false;
        try (JsonParser p = mapper.getFactory().createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw bad("the body must be a JSON object {\"samples\": [...]}");
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "samples" -> {
                        found = true;
                        samples(p, samples);
                    }
                    case "shape" -> shape = shape(mapper.readTree(p));
                    case "kind" -> kind = text(p);
                    case "path" -> path = text(p);
                    case "at" -> at = text(p);
                    default -> p.skipChildren();
                }
            }
        } catch (StreamConstraintsException e) {
            throw tooBig("a document is nested deeper than " + limits.maxDepth() + " levels (drishti.builder.max-depth)");
        } catch (JsonParseException e) {
            throw bad("the body is not valid JSON: " + e.getOriginalMessage());
        }
        if (!found && shape == null && kind == null && path == null) {
            throw bad("'samples' is required: a list of {name, document}");
        }
        if (found && samples.isEmpty() && shape == null) {
            throw bad("'samples' is empty: send at least one document");
        }
        return new Request(samples, shape, kind, path, at);
    }

    private static String text(JsonParser p) throws IOException {
        String v = p.currentToken() == JsonToken.VALUE_STRING ? p.getText() : null;
        p.skipChildren();
        return v;
    }

    private Shape shape(JsonNode node) {
        if (node == null || !node.path("schema").isObject()) {
            throw bad("'shape' must be {schema, roles}: the JSON returned by /api/v1/builder/shape");
        }
        Map<String, RoleInfo> roles = new LinkedHashMap<>();
        node.path("roles").fields().forEachRemaining(e -> roles.put(e.getKey(),
                new RoleInfo(e.getValue().path("role").asText(), e.getValue().path("reason").asText(),
                        e.getValue().hasNonNull("kind") ? e.getValue().get("kind").asText() : null)));
        return new Shape((com.fasterxml.jackson.databind.node.ObjectNode) node.get("schema"), roles, null);
    }

    private void samples(JsonParser p, List<Sample> out) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            throw bad("'samples' must be a list of {name, document}");
        }
        while (p.nextToken() != JsonToken.END_ARRAY) {
            if (out.size() >= limits.maxSamples()) {
                throw tooBig("more than " + limits.maxSamples() + " samples (drishti.builder.max-samples)");
            }
            if (p.currentToken() != JsonToken.START_OBJECT) {
                throw bad("sample " + (out.size() + 1) + " must be an object {name, document}");
            }
            long start = p.currentTokenLocation().getByteOffset();
            String name = null;
            JsonNode document = null;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if ("name".equals(field)) {
                    name = p.currentToken() == JsonToken.VALUE_STRING ? p.getText() : null;
                    p.skipChildren();
                } else if ("document".equals(field)) {
                    document = mapper.readTree(p);
                } else {
                    p.skipChildren();
                }
            }
            long size = p.currentLocation().getByteOffset() - start;
            String label = name == null || name.isBlank() ? "sample " + (out.size() + 1) : "'" + name + "'";
            if (size > limits.maxFileBytes()) {
                throw tooBig(label + " is " + size + " bytes, over the limit of " + limits.maxFileMb() + " MB per document (drishti.builder.max-file-mb)");
            }
            if (document == null) {
                throw bad(label + " has no 'document'");
            }
            out.add(new Sample(name, document));
        }
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, message);
    }

    private static ShapeException tooBig(String message) {
        return new ShapeException(message);
    }
}
