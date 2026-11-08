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
package com.ash.drishti.server.modules;

import com.ash.drishti.packs.Modules;
import java.util.function.Predicate;
import org.springframework.core.env.Environment;

/**
 * Whether a domain folder's content is read (docs/architecture/DOMAIN_MODULES.md section 8): the BI files, the tables and models of
 * {@code config/bi/<kind>/<domain>/} and {@code config/tables/<domain>/}. A folder named after a module is read only while that module is
 * on; any other folder (the core's own, such as {@code bi}) always is.
 */
public final class DomainFolders {

    private DomainFolders() {
    }

    /** The test for the environment's registry. */
    public static Predicate<String> on(Environment env) {
        var registry = Modules.of(env);
        return folder -> registry.module(folder).map(s -> s.on()).orElse(true);
    }
}
