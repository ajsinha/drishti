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

import com.ash.drishti.identity.db.IdentityRepositories;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Beans contributed by {@code drishti-identity}: users, roles, preferences and the audit trail, all in the identity
 * database ({@link IdentityDatabase}). The server supplies {@link RoleNames}: built-in roles plus administrators' roles.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
@Import(IdentityDatabase.class)
public class IdentityConfiguration {

    @Bean
    public JpaAuditLog auditLog(IdentityRepositories.Audit audit) {
        return new JpaAuditLog(audit);
    }

    @Bean
    public JpaUserStore userStore(IdentityRepositories.Users users, TransactionTemplate identityTransactions) {
        return new JpaUserStore(users, identityTransactions);
    }

    @Bean
    public PreferenceStore preferenceStore(IdentityRepositories.Preferences prefs, TransactionTemplate identityTransactions) {
        return new JpaPreferenceStore(prefs, identityTransactions, 64 * 1024, 50);
    }

    @Bean
    public RoleStore roleStore(IdentityRepositories.Roles roles, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new RoleStore(roles, identityTransactions, auditLog);
    }

    @Bean
    public PackStateStore packStateStore(IdentityRepositories.PackStates states, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new PackStateStore(states, identityTransactions, auditLog);
    }

    @Bean
    public UserService userService(JpaUserStore store, JpaAuditLog auditLog, PreferenceStore preferences, IdentityProperties props,
            RoleNames roles) {
        LegacyImport.run(props, store, auditLog, preferences);
        UserService s = new UserService(store, new PasswordHasher(props.iterations()), auditLog, props, roles);
        s.seedIfEmpty();
        return s;
    }
}
