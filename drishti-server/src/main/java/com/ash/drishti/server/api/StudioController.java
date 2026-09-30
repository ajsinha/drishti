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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraRegistry;
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
    public record PreviewRequest(String yaml, String kind, String id) {}

    private final SutraRegistry sutras;
    private final ViewPipeline pipeline;
    private final RachanaProperties props;
    private final Entitlements entitlements;
    private final SutraWriter writer = new SutraWriter();

    public StudioController(SutraRegistry sutras, ViewPipeline pipeline, RachanaProperties props, Entitlements entitlements) {
        this.sutras = sutras;
        this.pipeline = pipeline;
        this.props = props;
        this.entitlements = entitlements;
    }

    @GetMapping(path = "/sutras/{name}/{version}/source", produces = "text/yaml")
    public String source(@PathVariable String name, @PathVariable int version) {
        return sutras.source(name, version)
                .orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, name + "@" + version));
    }

    @PostMapping("/studio/preview")
    public ViewModel preview(@RequestBody PreviewRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, req.kind());
        Sutra s = sutras.check(req.yaml());
        return entitlements.restrict(principal, pipeline.preview(s, EntityRef.of(req.kind(), req.id())));
    }

    @GetMapping(path = "/studio/inferred/{kind}/{id}", produces = "text/yaml")
    public String inferred(@PathVariable String kind, @PathVariable String id, @RequestParam(defaultValue = "") String name,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        String n = name.isBlank() ? kind + "-custom" : name;
        return writer.write(pipeline.inferred(EntityRef.of(kind, id)), n, 1);
    }

    @GetMapping("/studio/settings")
    public Map<String, Object> settings(@RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return Map.of("save", props.studioSave() && entitlements.mayAuthor(principal));
    }

    @PostMapping(path = "/sutras", consumes = {"text/yaml", MediaType.TEXT_PLAIN_VALUE})
    public ApiDtos.SutraInfo save(@RequestBody String yaml, @RequestAttribute(Principal.ATTRIBUTE) Principal principal)
            throws IOException {
        if (!props.studioSave()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "saving from Studio is disabled (drishti.rachana.studio-save)");
        }
        if (!entitlements.mayAuthor(principal)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, principal.user() + " is not a Sutra author");
        }
        Sutra s = sutras.save(yaml);
        return new ApiDtos.SutraInfo(s.name(), s.version(), sutras.versions(s.name()), s.domain(), s.match().kind(),
                s.match().where(), s.match().priority());
    }
}
