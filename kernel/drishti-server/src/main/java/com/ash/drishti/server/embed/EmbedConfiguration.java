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
package com.ash.drishti.server.embed;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.EmbedAppStore;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.server.security.oidc.IdTokenVerifier;
import com.ash.drishti.server.security.oidc.OidcProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Embedded views (Drishti Elements): the signing key and the token service. Off unless {@code drishti.embed.enabled}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbedProperties.class)
public class EmbedConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EmbedConfiguration.class);

    @Bean
    public EmbedKeys embedKeys(EmbedProperties props) {
        if (!props.enabled()) {
            return EmbedKeys.load(null);              // never used to sign; a throw-away key keeps the beans uniform
        }
        EmbedKeys keys = EmbedKeys.load(props.signingKey());
        if (keys.generated()) {
            LOG.warn("drishti.embed.signing-key is not set: embed tokens are signed with a key made now, so they stop working at restart and"
                    + " a second server could not verify them. Set a PEM file for production.");
        }
        return keys;
    }

    @Bean
    public EmbedUsage embedUsage(io.micrometer.core.instrument.MeterRegistry meters) {
        return new EmbedUsage(meters, Clock.systemUTC());
    }

    @Bean
    public EmbedTokenService embedTokenService(EmbedProperties props, EmbedKeys keys, EmbedAppStore apps, UserService users, AuditLog audit,
            IdTokenVerifier idTokens, OidcProperties oidc, SecurityProperties security, EmbedUsage usage) {
        apps.setConfigured(props.apps().stream().map(EmbedProperties.ConfiguredApp::toConfigured).toList());
        return new EmbedTokenService(props, keys, apps, users, audit, idTokens, oidc, Clock.systemUTC(), security.enabled(), usage);
    }
}
