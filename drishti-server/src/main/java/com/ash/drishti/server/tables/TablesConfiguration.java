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
package com.ash.drishti.server.tables;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.ash.drishti.server.data.DataViewService;
import com.ash.drishti.server.edition.EditionCommands;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.tables.answer.ModelAnswers;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The virtual tables' bean ({@code drishti.tables.*}, TABLES_GUIDE.md section 7a): the catalogue under {@code drishti.tables.root} and the
 * reads for a person; a row rule knows the reader as BI's dataset rules do ({@code user.name}, {@code user.roles}, {@code user.desk},
 * {@code user.desks}, {@code user.email}).
 */
@Configuration
public class TablesConfiguration {

    @Bean
    public TablesService tablesService(Environment env, DataViewService data, com.ash.drishti.engine.source.SourceRouter router,
            Entitlements entitlements, EditionCommands edition,
            SecurityProperties security, ObjectProvider<UserService> users) {
        Path root = Path.of(env.getProperty("drishti.tables.root", "./config/tables")).toAbsolutePath().normalize();
        long maxRows = env.getProperty("drishti.tables.read-max-rows", Long.class, 2_000_000L);
        long warn = env.getProperty("drishti.tables.scan-warn-rows", Long.class, 1_000_000L);
        TablesService.Readers readers = new TablesService.Readers() {
            @Override
            public ModelAnswers.Reader reader(Principal p) {
                UserService u = users.getIfAvailable();
                Optional<User> user = security.enabled() && u != null ? u.find(p.user()) : Optional.empty();
                return new ModelAnswers.Reader(Set.copyOf(p.roles()), UserAttributes.of(p.user(), p.roles(), user.map(User::desk).orElse(""),
                        user.map(User::email).orElse("")), entitlements.raw(p));
            }

            @Override
            public boolean enforce() {
                return security.enabled();
            }
        };
        return new TablesService(root, data, router, entitlements, edition, readers, maxRows, warn, java.time.Clock.systemUTC());
    }
}
