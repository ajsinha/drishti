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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.parse.SutraMarkdown;
import com.ash.drishti.rachana.SutraWriter;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sutra Studio: read a Sutra's source, preview an unsaved Sutra against any entity, start a Sutra from
 * what inference makes of an entity, and (where enabled, for authors) save.
 */
@RestController
@RequestMapping("/api/v1")
public class StudioController {

    /**
     * @param yaml the Sutra text
     * @param kind the entity kind to preview against
     * @param id the entity id
     */
    public record PreviewRequest(String yaml, String kind, String id, com.fasterxml.jackson.databind.JsonNode document) {}

    /**
     * @param kind entity kind the pasted document is
     * @param id identifier to show
     * @param document the pasted JSON
     * @param name name for the new Sutra
     */
    public record InferRequest(String kind, String id, com.fasterxml.jackson.databind.JsonNode document, String name) {}

    private com.ash.drishti.api.EntityDocument pasted(String kind, String id, com.fasterxml.jackson.databind.JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new DrishtiException(ErrorCode.INVALID_JSON, "sample JSON must be an object");
        }
        var data = codec.read(json.toString());
        return new com.ash.drishti.api.EntityDocument(EntityRef.of(kind, id == null || id.isBlank() ? "SAMPLE" : id), data,
                new com.ash.drishti.api.Provenance("studio sample JSON", 0, java.time.Instant.now(), false));
    }

    private final SutraRegistry sutras;
    private final ViewPipeline pipeline;
    private final RachanaProperties props;
    private final Entitlements entitlements;
    private final SutraWriter writer = new SutraWriter();
    private final com.ash.drishti.common.JsonCodec codec;
    private final com.ash.drishti.server.governance.SutraGovernance governance;

    public StudioController(SutraRegistry sutras, ViewPipeline pipeline, RachanaProperties props, Entitlements entitlements,
            com.ash.drishti.common.JsonCodec codec, com.ash.drishti.server.governance.SutraGovernance governance) {
        this.governance = governance;
        this.codec = codec;
        this.sutras = sutras;
        this.pipeline = pipeline;
        this.props = props;
        this.entitlements = entitlements;
    }

    @GetMapping(path = "/sutras/{name}/{version}/source", produces = "text/markdown")
    public String source(@PathVariable String name, @PathVariable int version) {
        return sutras.source(name, version)
                .orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, name + "@" + version));
    }

    @PostMapping("/studio/preview")
    public ViewModel preview(@RequestBody PreviewRequest req, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, req.kind());
        Sutra s = sutras.check(req.yaml());
        if (req.document() != null && !req.document().isNull()) {
            return entitlements.restrict(principal, pipeline.preview(java.util.Optional.of(s), pasted(req.kind(), req.id(), req.document())));
        }
        return entitlements.restrict(principal, pipeline.preview(s, EntityRef.of(req.kind(), req.id()), asOf));
    }

    @GetMapping(path = "/studio/inferred/{kind}/{id}", produces = "text/markdown")
    public String inferred(@PathVariable String kind, @PathVariable String id, @RequestParam(defaultValue = "") String name, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        String n = name.isBlank() ? kind + "-custom" : name;
        return SutraMarkdown.wrap(n, 1, "Started from what inference makes of " + kind + " " + id + ".",
                writer.write(pipeline.inferred(EntityRef.of(kind, id), asOf), n, 1));
    }

    @PostMapping(path = "/studio/inferred", produces = "text/markdown")
    public String inferredFromSample(@RequestBody InferRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, req.kind());
        String n = req.name() == null || req.name().isBlank() ? req.kind() + "-custom" : req.name();
        return SutraMarkdown.wrap(n, 1, "Started from what inference makes of a pasted " + req.kind() + " document.",
                writer.write(pipeline.inferred(pasted(req.kind(), req.id(), req.document())), n, 1));
    }

    @GetMapping("/studio/settings")
    public Map<String, Object> settings(@RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return Map.of("save", props.studioSave() && entitlements.mayAuthor(principal), "review", governance.enabled(),
                "approve", entitlements.mayApprove(principal));
    }

    /**
     * Saves a Sutra from Studio. With governance on (the default) it becomes a proposal for review ({@code 202} with the
     * proposal); otherwise it is written and goes live at once.
     */
    @PostMapping(path = "/sutras", consumes = {"text/markdown", "text/yaml", MediaType.TEXT_PLAIN_VALUE})
    public org.springframework.http.ResponseEntity<?> save(@RequestBody String yaml, @RequestParam(required = false) String note,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) throws IOException {
        if (!props.studioSave()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "saving from Studio is disabled (drishti.rachana.studio-save)");
        }
        if (!entitlements.mayAuthor(principal)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, principal.user() + " is not a Sutra author");
        }
        if (governance.enabled()) {
            var pr = governance.propose(yaml, note, principal);
            return org.springframework.http.ResponseEntity.accepted().body(Map.of("proposal", Map.of("id", pr.id(), "name", pr.name(),
                    "version", pr.version(), "status", pr.status())));
        }
        Sutra s = sutras.save(yaml);
        return org.springframework.http.ResponseEntity.ok(new ApiDtos.SutraInfo(s.name(), s.version(), sutras.versions(s.name()), s.domain(),
                s.match().kind(), s.match().where(), s.match().priority()));
    }
}
