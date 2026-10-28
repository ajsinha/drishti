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
package com.ash.drishti.server.bi.ai;

import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BI on the AI layer (docs/guides/BI_GUIDE.md): {@code GET /status}, {@code POST /draft} (report from words), {@code POST /ask} (ask the data),
 * {@code POST /explain} (explain this number) and the authoring helpers {@code POST /suggest}, {@code /explain-checks} and {@code /dataset-helper}. Off with the AI layer (DRS-4015); an unusable model answer is DRS-4020; the AI policy's refusals are DRS-4016..4019.
 */
@RestController
@RequestMapping("/api/v1/bi/ai")
public class BiAiController {

    private final BiAiService service;
    private final BiAiCopilot copilot;

    public BiAiController(BiAiService service, BiAiCopilot copilot) {
        this.service = service;
        this.copilot = copilot;
    }

    /** Body {@code {dataset, goal, yaml?}}: visuals and slicers for the goal, each as YAML to add; nothing is saved or applied. */
    @PostMapping("/suggest")
    public ResponseEntity<Map<String, Object>> suggest(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(copilot.suggest(p, body.path("dataset").asText(""), body.path("goal").asText(""), body.path("yaml").asText("")));
    }

    /** Body {@code {yaml}}: the report checker's problems for the draft, each with a plain explanation and a fix. */
    @PostMapping("/explain-checks")
    public ResponseEntity<Map<String, Object>> explainChecks(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(copilot.explainChecks(p, body.path("yaml").asText("")));
    }

    /** Body {@code {dataset}}: proposed relationships, measures (with descriptions) and column descriptions, each checked against the model; nothing is applied. */
    @PostMapping("/dataset-helper")
    public ResponseEntity<Map<String, Object>> helper(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(copilot.helper(p, body.path("dataset").asText("")));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(service.status(p));
    }

    /** Body {@code {dataset, request}}: a draft report YAML over the dataset, checked by the report checker before it is returned; nothing is saved. */
    @PostMapping("/draft")
    public ResponseEntity<Map<String, Object>> draft(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(service.draft(p, body.path("dataset").asText(""), body.path("request").asText("")));
    }

    /** Body {@code {report | dataset, question}}: the query the model chose and the engine's answer to it as the caller. */
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(service.ask(p, body.path("report").asText(""), body.path("dataset").asText(""), body.path("question").asText("")));
    }

    /** Body {@code {report, page, visual, measure?, state?}}: lineage, the engine's breakdown and (aggregates mode) the model's wording. */
    @PostMapping("/explain")
    public ResponseEntity<Map<String, Object>> explain(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(service.explain(p, body.path("report").asText(""), body.path("page").asText(""), body.path("visual").asText(""), body.path("measure").asText(""),
                body.get("state")));
    }

    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> m) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(m);
    }
}
