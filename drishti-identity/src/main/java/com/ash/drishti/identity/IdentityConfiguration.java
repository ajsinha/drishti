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
package com.ash.drishti.identity;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans contributed by {@code drishti-identity}. The set of known role names is supplied by the server. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {

    @Bean
    public UserStore userStore(IdentityProperties props) {
        return new FileUserStore(Path.of(props.usersFile()));
    }

    @Bean
    public UserService userService(UserStore store, IdentityProperties props, @Qualifier("drishtiRoleNames") Set<String> roles) {
        UserService s = new UserService(store, new PasswordHasher(props.iterations()), new AuditLog(Path.of(props.auditFile())), props, roles);
        s.seedIfEmpty();
        return s;
    }
}
