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
package com.ash.drishti.server.bi;

import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * Business intelligence, phase 4 (docs/architecture/RUPAKA.md section 20.4): the history of saved states, the diff, the review page, lineage and impact,
 * and bundles for moving content between environments. Who may see what is decided in {@link BiGovernance} and {@link BiBundles}, from the caller's identity.
 */
@RestController
@RequestMapping("/api/v1/bi")
public class BiGovController {

    private final BiGovernance gov;
    private final BiBundles bundles;
    private final BiService bi;
    private final ExpectedValueRunner tests;

    public BiGovController(BiGovernance gov, BiBundles bundles, BiService bi, ExpectedValueRunner tests) {
        this.gov = gov;
        this.bundles = bundles;
        this.bi = bi;
        this.tests = tests;
    }

    /**
     * Runs the expected values (<code>tests:</code>) of a report or dataset, as the caller or as a role they hold (a bi-steward as any): {@code {role, yaml}}, both
     * optional ({@code yaml} is a report draft an author has not saved). Answers each test as pass, fail or error with the figure it found. Stores nothing.
     */
    @PostMapping("/items/{id}/tests")
    public Map<String, Object> runTests(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object role = body == null ? null : body.get("role");
        Object yaml = body == null ? null : body.get("yaml");
        return tests.run(p, id, yaml instanceof String s && !s.isBlank() ? s : null, role instanceof String r && !r.isBlank() ? r : null).toMap();
    }

    @GetMapping("/items/{id}/history")
    public Map<String, Object> history(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return gov.history(p, id);
    }

    @GetMapping("/items/{id}/history/{seq}")
    public Map<String, Object> revision(@PathVariable String id, @PathVariable int seq, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return gov.revision(p, id, seq);
    }

    /** Makes a saved state the draft again, as a new saved state. */
    @PostMapping("/items/{id}/history/{seq}/restore")
    public Map<String, Object> restore(@PathVariable String id, @PathVariable int seq, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.restoreRevision(p, id, seq);
    }

    /** {@code from} and {@code to}: {@code draft}, {@code published}, {@code vN} (a published version) or {@code rN} (a saved state). */
    @GetMapping("/items/{id}/diff")
    public Map<String, Object> diff(@PathVariable String id, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return gov.diff(p, id, from, to);
    }

    @GetMapping("/items/{id}/review")
    public Map<String, Object> review(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return gov.review(p, id);
    }

    @GetMapping("/items/{id}/lineage")
    public Map<String, Object> lineage(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return gov.lineage(p, id);
    }

    /** {@code remove=table.column,Measure}: what breaks if these were taken away; without it, what publishing the dataset's draft would break. */
    @GetMapping("/items/{id}/impact")
    public Map<String, Object> impact(@PathVariable String id, @RequestParam(required = false) String remove, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<String> names = remove == null || remove.isBlank() ? null : Arrays.stream(remove.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return gov.impact(p, id, names);
    }

    /** What depends on the item (reports on a dataset, boards showing a report): the count and the names the caller may read, for the delete confirmation. */
    @GetMapping("/items/{id}/dependants")
    public Map<String, Object> dependants(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return gov.dependants(p, id);
    }

    /** The impact of a dataset text on the reports that read the stored dataset (the Model view's Check). */
    @PostMapping("/items/{id}/impact")
    public Map<String, Object> impactOfText(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return gov.impactOfText(p, id, String.valueOf(body.getOrDefault("yaml", "")));
    }

    /** A bundle of items (and the datasets their reports read) as a downloadable JSON file. */
    @GetMapping("/bundle")
    public ResponseEntity<JsonNode> export(@RequestParam(required = false) String items, @RequestParam(required = false) String workspace,
            @RequestParam(defaultValue = "true") boolean dependencies, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<String> ids = items == null || items.isBlank() ? List.of() : Arrays.stream(items.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        JsonNode bundle = bundles.export(p, ids, workspace, dependencies);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"drishti-bi-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + ".bi-bundle.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(bundle);
    }

    /**
     * Body {@code {bundle, dryRun, overwrite, force, workspace, workspaces: {from: to}, owners: {from: to}}}. A dry run (the default) changes nothing and lists
     * what would be created, changed, left alone or refused.
     */
    @PostMapping("/bundle/import")
    public Map<String, Object> imports(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        BiBundles.Options o = new BiBundles.Options(!body.has("dryRun") || body.path("dryRun").asBoolean(true), body.path("overwrite").asBoolean(false), body.path("force").asBoolean(false),
                body.path("workspace").asText(""), map(body.path("workspaces")), map(body.path("owners")));
        return bundles.imports(p, body.path("bundle"), o);
    }

    /** The imports whose replaced states are kept (a steward). */
    @GetMapping("/imports")
    public Map<String, Object> imported(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bundles.imported(p);
    }

    @GetMapping("/imports/{id}")
    public Map<String, Object> importDetail(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bundles.importDetail(p, id);
    }

    /** Puts back what the import replaced; {@code {force: true}} (a bi-admin) restores items edited since. */
    @PostMapping("/imports/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bundles.rollback(p, id, body != null && Boolean.TRUE.equals(body.get("force")));
    }

    private static Map<String, String> map(JsonNode n) {
        Map<String, String> m = new LinkedHashMap<>();
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        }
        return m;
    }
}
