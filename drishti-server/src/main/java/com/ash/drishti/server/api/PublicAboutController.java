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

    public PublicAboutController(Branding branding, ObjectProvider<BuildProperties> build, SecurityProperties security,
            ObjectProvider<OidcProperties> oidc) {
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
        OidcProperties o = oidc.getIfAvailable();
        m.put("signIn", Map.of("required", security.enabled(), "password", true, "singleSignOn", o != null && Boolean.TRUE.equals(o.enabled())));
        return m;
    }
}
