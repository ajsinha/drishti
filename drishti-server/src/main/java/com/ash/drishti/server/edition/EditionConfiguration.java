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
package com.ash.drishti.server.edition;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.packs.edition.Edition;
import com.ash.drishti.packs.edition.Editions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The edition the server runs (docs/admin/EDITIONS.md) as beans: the {@link Edition} itself (read once at start, from the edition
 * file's property layer and the site's overrides) and {@link EditionCommands}, the commands it shows people. Without
 * {@code drishti.edition} both answer "allowed" everywhere: the full Drishti.
 */
@Configuration(proxyBeanMethods = false)
public class EditionConfiguration {

    @Bean
    public Edition edition(Environment env) {
        return Editions.of(env);
    }

    @Bean
    public EditionContext editionContext(Edition edition, Environment env) {
        return new EditionContext(edition, Editions.lenses(env));
    }

    @Bean
    public EditionCommands editionCommands(EditionContext context, Mnemonics mnemonics) {
        return new EditionCommands(context, mnemonics);
    }

    @Bean
    public EditionBi editionBi(EditionContext context) {
        return new EditionBi(context);
    }
}
