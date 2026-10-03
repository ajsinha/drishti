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
import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.engine.shape.Sample;
import com.ash.drishti.engine.shape.Shape;
import com.ash.drishti.engine.shape.ShapeException;
import com.ash.drishti.engine.shape.ShapeService;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Screen Builder, step 1: {@code POST /api/v1/builder/shape} turns up to {@code drishti.builder.max-samples} JSON
 * documents into one JSON Schema with roles and a report. For authors only (as Studio); writes nothing anywhere;
 * logs counts, never content. The body is size-checked before it is read and parsed one sample at a time, so an
 * over-limit request is refused before everything is parsed.
 */
@RestController
@RequestMapping("/api/v1/builder")
public class BuilderController {

    private static final Logger LOG = LoggerFactory.getLogger(BuilderController.class);

    private final ShapeService shapes;
    private final BuilderProperties limits;
    private final Entitlements entitlements;
    private final ObjectMapper mapper;

    public BuilderController(ShapeService shapes, Entitlements entitlements) {
        this.shapes = shapes;
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
        if (!entitlements.mayAuthor(principal)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, principal.user() + " is not a Sutra author");
        }
        byte[] body = read(request);
        List<Sample> samples = parse(body);
        LOG.info("builder shape: {} sample(s), {} bytes", samples.size(), body.length);
        return shapes.infer(samples);
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

    private List<Sample> parse(byte[] body) throws IOException {
        List<Sample> samples = new ArrayList<>();
        try (JsonParser p = mapper.getFactory().createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw bad("the body must be a JSON object {\"samples\": [...]}");
            }
            boolean found = false;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if ("samples".equals(field)) {
                    found = true;
                    samples(p, samples);
                } else {
                    p.skipChildren();
                }
            }
            if (!found) {
                throw bad("'samples' is required: a list of {name, document}");
            }
        } catch (StreamConstraintsException e) {
            throw tooBig("a document is nested deeper than " + limits.maxDepth() + " levels (drishti.builder.max-depth)");
        } catch (JsonParseException e) {
            throw bad("the body is not valid JSON: " + e.getOriginalMessage());
        }
        if (samples.isEmpty()) {
            throw bad("'samples' is empty: send at least one document");
        }
        return samples;
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
