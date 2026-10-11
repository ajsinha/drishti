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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business intelligence, phase 3 (docs/architecture/RUPAKA.md section 8): reports. The viewer asks for a report once and then for each visual's
 * data as the person works; every answer is the dataset engine's, as that caller (their row rules, their masks).
 */
@RestController
@RequestMapping("/api/v1/bi/reports")
public class BiReportController {

    private final BiReportService reports;

    public BiReportController(BiReportService reports) {
        this.reports = reports;
    }

    /** The visuals a report may hold, their wells, and the types reserved for later. */
    @GetMapping("/catalogue")
    public Map<String, Object> catalogue(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.catalogue(p);
    }

    /** The model of the dataset a report (or a draft) names, for the designer's Fields pane (authors). */
    @GetMapping("/dataset")
    public Map<String, Object> dataset(@RequestParam String ref, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.datasetModel(p, ref);
    }

    /** Body {@code {yaml, quality?}}: checks report YAML without storing it; with {@code quality} also the accessibility and layout warnings. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.validate(p, String.valueOf(body.getOrDefault("yaml", "")), Boolean.TRUE.equals(body.get("quality")));
    }

    /** Body {@code {report: {...}}}: the report model written as YAML. */
    @PostMapping("/format")
    public Map<String, Object> format(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.format(p, body.path("report"));
    }

    /** Body {@code {yaml, page, visual, state}}: one visual of draft YAML (authors), for the designer's preview. */
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> draftQuery(@RequestBody JsonNode body, @RequestParam(defaultValue = "false") boolean explain,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(reports.query(p, null, body.path("yaml").asText(""), body.path("page").asText(""), body.path("visual").asText(""), body.get("state"), explain));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(reports.get(p, id));
    }

    /** Body {@code {page, visual, state}}: the data of one visual under the page's slicers, selections, drill path and carried values. */
    @PostMapping("/{id}/query")
    public ResponseEntity<Map<String, Object>> query(@PathVariable String id, @RequestBody JsonNode body, @RequestParam(defaultValue = "false") boolean explain,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(reports.query(p, id, null, body.path("page").asText(""), body.path("visual").asText(""), body.get("state"), explain));
    }

    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
