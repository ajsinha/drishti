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
@EnableConfigurationProperties({SecurityProperties.class, RequestLimitProperties.class})
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
    public FilterRegistrationBean<TokenFilter> tokenFilter(SecurityProperties props, TokenVerifier verifier,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.identity.ApiTokenStore> tokens,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.identity.UserService> users,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.identity.AuditLog> audit,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.embed.EmbedTokenService> embed,
            @org.springframework.beans.factory.annotation.Value("${drishti.security.registered-users-only:true}") boolean registeredOnly) {
        // an API token stands for its user as they are now: their roles, and only while the account is enabled
        java.util.function.Function<String, java.util.Optional<TokenFilter.Grant>> apiTokens = bearer -> tokens.getObject().verify(bearer)
                .flatMap(t -> users.getObject().find(t.user()).filter(com.ash.drishti.identity.User::enabled)
                        .map(u -> new TokenFilter.Grant(t.id(), new Principal(u.username(), java.util.List.copyOf(u.roles())), t.scopes(), t.expiresAt())));
        // a signed token stands for an account that must still exist and be enabled (the console's own service identity is exempt)
        java.util.function.Predicate<Principal> account = p -> !registeredOnly || p.roles().contains(Entitlements.SERVICE)
                || users.getObject().find(p.user()).filter(com.ash.drishti.identity.User::enabled).isPresent();
        FilterRegistrationBean<TokenFilter> r = new FilterRegistrationBean<>(new TokenFilter(props, verifier, apiTokens, account, audit.getObject())
                .withEmbed(embed.getIfAvailable()));
        r.addUrlPatterns("/api/*");
        r.setOrder(1);
        return r;
    }

    /**
     * Refuses non-canonical request lines under /api and /actuator (path parameters, needless encodings, dot or empty
     * segments) with 400 before any other filter, so every decision is taken on the path that is served (SEC-02).
     */
    @Bean
    public FilterRegistrationBean<PathGuard> pathGuard() {
        FilterRegistrationBean<PathGuard> r = new FilterRegistrationBean<>(new PathGuard());
        r.addUrlPatterns("/api", "/api/*", "/actuator", "/actuator/*");
        r.setOrder(-10);
        return r;
    }

    /** Answers an oversized request body with a clean 413 DRS-5005 before the body is read (S2-08). */
    @Bean
    public FilterRegistrationBean<RequestSizeFilter> requestSizeFilter(RequestLimitProperties limits) {
        FilterRegistrationBean<RequestSizeFilter> r = new FilterRegistrationBean<>(new RequestSizeFilter(limits.rules()));
        r.addUrlPatterns("/api/*");
        r.setEnabled(limits.enabled());
        r.setOrder(-9);
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
            com.ash.drishti.identity.PackStateStore states, com.ash.drishti.packs.SamplePolicy samples, RoleCatalog roles, SecurityProperties props) {
        String defaults = env.getProperty("drishti.packs.default-for-users", "");
        PackAccess access = new PackAccess(registry, users, prefs, java.util.Arrays.stream(defaults.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList(), states);
        // a developer has the author or admin power; with security off everyone does
        access.samples(samples, user -> !props.enabled() || developer(user, users, roles));
        return access;
    }

    /**
     * True when the user holds a role with the author or admin power: by the roles on the request being served when it is
     * theirs (a sign-in through an identity provider may not be in the user store), else by the roles the user store holds.
     */
    private static boolean developer(String user, com.ash.drishti.identity.UserService users, RoleCatalog roles) {
        java.util.Collection<String> names = null;
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes sra
                && sra.getRequest().getAttribute(Principal.ATTRIBUTE) instanceof Principal p && p.user().equals(user)) {
            names = p.roles();
        }
        if (names == null) {
            names = users.find(user).map(com.ash.drishti.identity.User::roles).orElse(java.util.Set.of());
        }
        if (names.contains("*")) {
            return true;
        }
        return names.stream().map(roles::find).anyMatch(r -> r.isPresent() && (r.get().author() || r.get().admin()));
    }

    /** Who sees the sample packs: {@code drishti.packs.samples}, overridden by the setting saved from Admin → Packs. */
    @Bean
    public com.ash.drishti.packs.SamplePolicy samplePolicy(org.springframework.core.env.Environment env) {
        return com.ash.drishti.packs.SamplePolicy.of(env);
    }

    @Bean
    public Entitlements entitlements(SecurityProperties props, PackAccess packAccess, RoleCatalog roles) {
        return new Entitlements(props, packAccess, roles);
    }
}
