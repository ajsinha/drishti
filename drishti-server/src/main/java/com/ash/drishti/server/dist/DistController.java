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
package com.ash.drishti.server.dist;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Distribution (RUPAKA.md section 15): export of a report, page, visual or Sutra view as PDF, PNG, Excel or CSV, and the caller's subscriptions
 * (a report or view delivered by email on a schedule), with an administrators' list of everyone's.
 */
@RestController
public class DistController {

    private final ContentExporter exports;
    private final SubscriptionService subscriptions;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final ObjectMapper json = new ObjectMapper();

    public DistController(ContentExporter exports, SubscriptionService subscriptions, Entitlements entitlements, AuditLog audit) {
        this.exports = exports;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /**
     * Body {@code {type: report|view, id, page?, visual?, state?, date?, view?}}: the file, made for the caller (their masks and row rules).
     * {@code format} is {@code pdf}, {@code png}, {@code xlsx}, {@code csv} or {@code jsonl} (one row a line, docs/guides/DATA_FORMATS.md).
     */
    @PostMapping("/api/v1/export/{format}")
    public ResponseEntity<byte[]> export(@PathVariable String format, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ContentExporter.Format f = ContentExporter.Format.of(format);
        if (f == ContentExporter.Format.HTML) {
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "format is pdf, png, xlsx, csv or jsonl");
        }
        ContentExporter.Target t = new ContentExporter.Target(body.path("type").asText("report"), body.path("id").asText(""), body.path("page").asText(""),
                body.path("visual").asText(""), body.path("state").isObject() ? body.get("state") : null, body.path("date").asText(""), body.path("view").asText(""));
        ContentExporter.Output o = exports.render(t, f, p);
        audit.record(p.user(), "export", t.type() + "/" + t.id(), f.extension() + " · " + o.rows() + " rows · " + o.bytes().length + " bytes");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(o.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(o.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Export-Rows", String.valueOf(o.rows()))
                .body(o.bytes());
    }

    /**
     * What the caller can do here, so a page shows Export and Subscribe only when they work and says why when they do not: whether exports are on, whether
     * subscriptions are on, how many subscriptions the caller owns and how many they may.
     */
    @GetMapping("/api/v1/distribution/status")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("export", Map.of("enabled", exports.limits().enabled()),
                "subscriptions", Map.of("enabled", subscriptions.enabled(), "perUser", subscriptions.perUser(), "mine", subscriptions.of(p.user()).size()));
    }

    @GetMapping("/api/v1/me/subscriptions")
    public List<JsonNode> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return subscriptions.of(p.user());
    }

    @GetMapping("/api/v1/me/subscriptions/{name}")
    public JsonNode get(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return subscriptions.get(p.user(), name).orElseThrow(() -> missing(name));
    }

    /**
     * Body {@code {target: {type, id, page?, visual?, state?, view?}, schedule, format: html|pdf|xlsx, recipients: [user | role:NAME | address], date?: today|previous|load,
     * enabled?, title?}}.
     */
    @PutMapping("/api/v1/me/subscriptions/{name}")
    public JsonNode save(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return subscriptions.save(p.user(), name, body);
    }

    @DeleteMapping("/api/v1/me/subscriptions/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!subscriptions.delete(p.user(), name)) {
            throw missing(name);
        }
    }

    /** Runs it now: one render and one mail per recipient, each as that recipient. Answers with the run. */
    @PostMapping("/api/v1/me/subscriptions/{name}/run")
    public JsonNode run(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        get(name, p);
        return subscriptions.run(p.user(), name, "by hand");
    }

    @PostMapping("/api/v1/me/subscriptions/{name}/pause")
    public JsonNode pause(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return subscriptions.setEnabled(p.user(), name, false, p.user());
    }

    @PostMapping("/api/v1/me/subscriptions/{name}/resume")
    public JsonNode resume(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return subscriptions.setEnabled(p.user(), name, true, p.user());
    }

    /** Body {@code {schedule}}: the next three times it would run (empty for {@code after-load}, which waits for a data load). */
    @PostMapping("/api/v1/subscriptions/preview")
    public Map<String, Object> preview(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<String> next = new ArrayList<>();
        try {
            Instant at = Instant.now();
            for (int i = 0; i < 3; i++) {
                Instant n = subscriptions.preview(body.path("schedule").asText(""), at);
                if (n == null) {
                    break;
                }
                next.add(n.toString());
                at = n;
            }
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.SUBSCRIPTION_INVALID, e.getMessage());
        }
        return Map.of("next", next);
    }

    /** Everyone's subscriptions, with owner, state and last run (administrators). */
    @GetMapping("/api/v1/admin/subscriptions")
    public List<JsonNode> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return subscriptions.all();
    }

    @PostMapping("/api/v1/admin/subscriptions/{owner}/{name}/pause")
    public JsonNode adminPause(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return subscriptions.setEnabled(owner, name, false, p.user());
    }

    @PostMapping("/api/v1/admin/subscriptions/{owner}/{name}/resume")
    public JsonNode adminResume(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return subscriptions.setEnabled(owner, name, true, p.user());
    }

    @DeleteMapping("/api/v1/admin/subscriptions/{owner}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminDelete(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!subscriptions.delete(owner, name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no subscription '" + name + "' of " + owner);
        }
    }

    private static DrishtiException missing(String name) {
        return new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no subscription '" + name + "'");
    }
}
