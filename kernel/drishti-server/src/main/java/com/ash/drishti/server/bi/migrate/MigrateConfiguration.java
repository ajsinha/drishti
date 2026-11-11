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
package com.ash.drishti.server.bi.migrate;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiGovProperties;
import com.ash.drishti.server.bi.BiProperties;
import com.ash.drishti.server.bi.BiService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The BI migrations: the service, and its start-up run before the server takes requests (after the expression reader is configured). */
@Configuration
@EnableConfigurationProperties(MigrateProperties.class)
public class MigrateConfiguration {

    @Bean
    public BiMigration biMigration(MigrateProperties props, BiStore store, AuditLog audit, BiService bi, BiProperties biProps, BiGovProperties gov) {
        return new BiMigration(props, store, audit, bi, biProps.versionsKeep(), gov.revisionsKeep());
    }

    @Bean
    public InitializingBean biMigrationAtStart(BiMigration migration, @org.springframework.beans.factory.annotation.Qualifier("expressionSettings") InitializingBean expressionSettings) {
        return migration::start;
    }
}
