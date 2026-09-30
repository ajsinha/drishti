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

    @Bean
    public Entitlements entitlements(SecurityProperties props) {
        return new Entitlements(props);
    }
}
