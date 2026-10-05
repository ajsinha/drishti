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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.server.security.TokenScopes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The guard: every endpoint that changes anything must say what a personal API token may do with it (read POST, a scope, or never). */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
class TokenScopeGuardTest {

    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired SecurityProperties props;

    @Test
    void everyNonGetEndpointDeclaresItsTokenScope() {
        TokenScopes scopes = new TokenScopes(props);
        List<String> undeclared = new ArrayList<>();
        int checked = 0;
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            var patterns = info.getPathPatternsCondition() == null ? Set.<String>of() : info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/v1/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]*}", "x");
                for (RequestMethod m : methods) {
                    if (m == RequestMethod.GET || m == RequestMethod.HEAD || m == RequestMethod.OPTIONS) {
                        continue;
                    }
                    checked++;
                    if (!scopes.declared(m.name(), path)) {
                        undeclared.add(m + " " + pattern);
                    }
                }
            }
        }
        assertThat(checked).as("non-GET endpoints found").isGreaterThan(50);
        assertThat(undeclared).as("add each to token-read-posts, a token-scopes entry or token-never in application.yaml").isEmpty();
        assertThat(props.tokenScopes()).containsKeys("design:write", "design:approve", "packs:admin");
        assertThat(props.tokenNever()).noneMatch(p -> p.matches("\\S+ /api/v1/\\*\\*"));   // no blanket never
    }
}
