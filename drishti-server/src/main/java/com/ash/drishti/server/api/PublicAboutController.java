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

import com.ash.drishti.common.Branding;
import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.server.security.oidc.OidcProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a console's server picker may show before anyone signs in (ADR-016): the product's name, version, notice and how
 * to sign in. Nothing about the data, packs, users or connectors: an exclusive server stays exclusive.
 */
@RestController
public class PublicAboutController {

    private final Branding branding;
    private final Optional<BuildProperties> build;
    private final SecurityProperties security;
    private final ObjectProvider<OidcProperties> oidc;

    private final com.ash.drishti.server.edition.EditionContext edition;
    private final ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines;
    private final ObjectProvider<com.ash.drishti.server.engines.EngineCluster> cluster;

    public PublicAboutController(Branding branding, ObjectProvider<BuildProperties> build, SecurityProperties security,
            ObjectProvider<OidcProperties> oidc, com.ash.drishti.server.edition.EditionContext edition,
            ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines, ObjectProvider<com.ash.drishti.server.engines.EngineCluster> cluster) {
        this.edition = edition;
        this.engines = engines;
        this.cluster = cluster;
        this.branding = branding;
        this.build = Optional.ofNullable(build.getIfAvailable());
        this.security = security;
        this.oidc = oidc;
    }

    @GetMapping("/public/about")
    public Map<String, Object> about() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", branding.product());
        m.put("version", build.map(BuildProperties::getVersion).orElse("dev"));
        m.put("notice", branding.notice());
        m.put("tagline", branding.tagline());
        m.put("edition", edition.instance().about());       // the console learns the edition here (EDITIONS.md › The design as built, section 5.1)
        m.put("lenses", edition.lensNames());               // the editions a person may open as a lens (none with an edition)
        m.put("engines", engines());                         // what the console shows or hides (COMPUTE_ENGINES.md section 5.2)
        OidcProperties o = oidc.getIfAvailable();
        m.put("signIn", Map.of("required", security.enabled(), "password", true, "singleSignOn", o != null && Boolean.TRUE.equals(o.enabled())));
        return m;
    }

    /**
     * Each compute engine: {@code {id, title, here, cluster}}; {@code here} it runs on this node, {@code cluster} on this node or any other
     * reachable one (an engine is offered to people when {@code cluster} is true). Absent (an older server): the console hides nothing.
     */
    java.util.List<Map<String, Object>> engines() {
        com.ash.drishti.server.engines.EngineRegistry r = engines.getIfAvailable();
        if (r == null) {
            return null;
        }
        com.ash.drishti.server.engines.EngineCluster c = cluster.getIfAvailable();
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var e : r.entries()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", e.id());
            x.put("title", e.title());
            x.put("here", r.running(e.id()));
            x.put("cluster", c == null ? r.running(e.id()) : c.available(e.id()));
            out.add(x);
        }
        return out;
    }
}
