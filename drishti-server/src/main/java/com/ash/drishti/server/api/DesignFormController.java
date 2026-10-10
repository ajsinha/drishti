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
import com.ash.drishti.server.design.DesignFormService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The search form of a Design, {@code /api/v1/builder/designs/{id}/form}: the workbench's Form tab. {@code GET} answers the saved form
 * with the field palette of the Design's samples; {@code POST /preview} answers it for the text in the editor (nothing saved);
 * {@code PUT} keeps the text as a step of the design's log ({@code 409 DRS-5007} on a stale revision). A Design that changes the form is
 * proposed, reviewed and approved like a Sutra: the change travels with the proposal. Owner only. The rules are in {@link DesignFormService}.
 */
@RestController
@RequestMapping("/api/v1/builder/designs/{id}/form")
public class DesignFormController {

    private static final int BODY_MAX = 1 << 20;

    private final DesignService designs;
    private final DesignFormService forms;
    private final ObjectMapper mapper = new ObjectMapper();

    public DesignFormController(DesignService designs, DesignFormService forms) {
        this.designs = designs;
        this.forms = forms;
    }

    @GetMapping
    public ObjectNode read(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        return forms.answer(who, id, null);
    }

    /** Body {@code {text}}: the answer for {@code text} without saving it. */
    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode preview(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = body(request);
        if (!b.path("text").isTextual()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'text' is required: the form (a YAML list of fields) to read");
        }
        return forms.answer(who, id, b.get("text").asText());
    }

    /** Body {@code {baseRev, text}}: keeps {@code text} as the Design's search form (a new revision). */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode save(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = body(request);
        if (!b.path("baseRev").isIntegralNumber()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'baseRev' is required: the revision you built on (the 'rev' of the design)");
        }
        if (!b.path("text").isTextual()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'text' is required: the form (a YAML list of fields) to keep");
        }
        StoredDesign d = designs.setForm(who.user(), id, b.get("baseRev").asInt(), b.get("text").asText());
        ObjectNode out = forms.answer(who, id, null);
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
