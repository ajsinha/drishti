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
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.server.design.DesignAboutService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The About text of a Design, {@code /api/v1/builder/designs/{id}/about}: the workbench's About tab. {@code GET} answers the card
 * of the saved text over a sample; {@code POST /preview} answers it for the text in the editor (nothing saved); {@code PUT} saves
 * the text as a step of the Design's log (undo, redo and revisions as for the Sutra). Like the Design itself it is open to the
 * signed-in owner. The rules are in {@link DesignAboutService}.
 */
@RestController
@RequestMapping("/api/v1/builder/designs/{id}/about")
public class DesignAboutController {

    private static final int BODY_MAX = 1 << 20;

    private final DesignService designs;
    private final DesignAboutService about;
    private final ObjectMapper mapper = new ObjectMapper();

    public DesignAboutController(DesignService designs, DesignAboutService about) {
        this.designs = designs;
        this.about = about;
    }

    /** The saved About text and its card over {@code sample} (a name; default the first). */
    @GetMapping
    public ObjectNode read(@PathVariable String id, @RequestParam(required = false) String sample, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        return about.answer(who, id, null, sample);
    }

    /** Body {@code {text, sample?}}: the card for {@code text} without saving it. */
    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode preview(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = body(request);
        if (!b.path("text").isTextual()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'text' is required: the about.yaml text to explain with");
        }
        return about.answer(who, id, b.get("text").asText(), b.path("sample").asText(null));
    }

    /** Body {@code {baseRev, text, sample?}}: keeps {@code text} as the Design's About text (a new revision) and answers its card. {@code 409 DRS-5007} when the revision is stale. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode save(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = body(request);
        if (!b.path("baseRev").isIntegralNumber()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'baseRev' is required: the revision you built on (the 'rev' of the design)");
        }
        if (!b.path("text").isTextual()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'text' is required: the about.yaml text to keep");
        }
        StoredDesign d = designs.setAbout(who.user(), id, b.get("baseRev").asInt(), b.get("text").asText());
        ObjectNode out = about.answer(who, id, null, b.path("sample").asText(null));
        out.put("status", d.status).put("opsAt", d.opsAt).put("opsCount", d.ops.size());
        return out;
    }

    private JsonNode body(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(BODY_MAX + 1);
            if (bytes.length > BODY_MAX) {
                throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the request body is over " + BODY_MAX / 1024 + " KB");
            }
            JsonNode n = mapper.readTree(bytes);
            if (n == null || !n.isObject()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the body must be a JSON object");
            }
            return n;
        } catch (JsonProcessingException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the body is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
