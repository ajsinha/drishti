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
package com.ash.drishti.server.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Token verification and entitlements. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    public TokenVerifier tokenVerifier(SecurityProperties props) {
        String secret = props.secret();
        if (!props.enabled() && (secret == null || secret.length() < 32)) {
            secret = "development-only-secret-not-used-when-disabled";
        }
        return new TokenVerifier(secret, props.clockSkew().toSeconds());
    }

    @Bean
    public FilterRegistrationBean<TokenFilter> tokenFilter(SecurityProperties props, TokenVerifier verifier) {
        FilterRegistrationBean<TokenFilter> r = new FilterRegistrationBean<>(new TokenFilter(props, verifier));
        r.addUrlPatterns("/api/*");
        r.setOrder(1);
        return r;
    }

    /** Guards /actuator (except health) and /api/docs when security is on. */
    @Bean
    public FilterRegistrationBean<ManagementGuard> managementGuard(SecurityProperties props, TokenVerifier verifier,
            org.springframework.core.env.Environment env, org.springframework.beans.factory.ObjectProvider<Entitlements> entitlements) {
        // admin power from any role (built-in or defined in Admin → Roles), resolved when a request needs it
        FilterRegistrationBean<ManagementGuard> r = new FilterRegistrationBean<>(new ManagementGuard(props, verifier,
                env.getProperty("drishti.security.metrics-token", ""), p -> entitlements.getObject().isAdmin(p)));
        r.addUrlPatterns("/actuator/*", "/api/docs/*", "/api/docs");
        r.setOrder(0);
        return r;
    }

    /** Every role: configuration and packs (built in), plus those administrators define (Admin → Roles). */
    @Bean
    public RoleCatalog roleCatalog(SecurityProperties props, com.ash.drishti.identity.RoleStore store) {
        return new RoleCatalog(props, store);
    }

    @Bean
    public PackAccess packAccess(com.ash.drishti.packs.PackRegistry registry, com.ash.drishti.identity.UserService users,
            com.ash.drishti.identity.PreferenceStore prefs, org.springframework.core.env.Environment env,
            com.ash.drishti.identity.PackStateStore states) {
        String defaults = env.getProperty("drishti.packs.default-for-users", "");
        return new PackAccess(registry, users, prefs, java.util.Arrays.stream(defaults.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList(), states);
    }

    @Bean
    public Entitlements entitlements(SecurityProperties props, PackAccess packAccess, RoleCatalog roles) {
        return new Entitlements(props, packAccess, roles);
    }
}
