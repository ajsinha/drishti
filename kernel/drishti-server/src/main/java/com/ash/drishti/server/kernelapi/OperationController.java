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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.server.net.NetCallFilter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * An operation another node asks this one to run (KERNEL_AND_MODULES.md section 2.3): a node's call only ({@code NetCallFilter}: signed
 * with the cluster key, the person from its verified on-behalf-of claims), with the calling person's lens, date box, known-at and token
 * scopes in its body. It runs here or is refused here, never sent on (one hop); a refusal is answered with its status, code and words.
 */
@RestController
public class OperationController {

    private final KernelServices kernel;
    private final KernelOperations operations;

    public OperationController(KernelServices kernel, KernelOperations operations) {
        this.kernel = kernel;
        this.operations = operations;
    }

    /** {@link KernelOperations#PATH}, written out so the API guide's check reads it. */
    @PostMapping(value = "/api/v1/net/modules/operation/{module}/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> run(@PathVariable String module, @PathVariable String name, @RequestBody(required = false) JsonNode body,
            HttpServletRequest req) {
        if (req.getAttribute(NetCallFilter.PEER) == null || !(req.getAttribute(Principal.ATTRIBUTE) instanceof Principal p)) {
            return refused(new Kernel.Refused(401, "DRS-5051", "an operation is asked by a node of the cluster, for a person"));
        }
        JsonNode who = body == null ? null : body.path("person");
        List<String> scopes = null;
        if (who != null && who.path("scopes").isArray()) {
            scopes = new ArrayList<>();
            for (JsonNode s : who.path("scopes")) {
                scopes.add(s.asText());
            }
        }
        try {
            RequestPerson person = kernel.person(p, text(who, "lens"), text(who, "asOf"), text(who, "knownAt"), scopes);
            return ResponseEntity.ok(operations.served(person, module, name, body == null ? null : body.get("request")));
        } catch (Kernel.Refused r) {
            return refused(r);
        }
    }

    private static String text(JsonNode n, String field) {
        return n == null || n.path(field).isNull() || n.path(field).isMissingNode() ? null : n.path(field).asText();
    }

    private static ResponseEntity<Object> refused(Kernel.Refused r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "about:blank");
        p.put("title", "operation refused");
        p.put("status", r.status());
        p.put("code", r.code());
        p.put("detail", r.getMessage());
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
