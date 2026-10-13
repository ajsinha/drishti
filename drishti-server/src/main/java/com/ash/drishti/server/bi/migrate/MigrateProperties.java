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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The BI migrations, {@code drishti.bi.migrate.*} (docs/admin/CONFIGURATION.md): at start-up, stored items in the old expression syntax are converted to DEL
 * v2 and {@code rachana: 1} datasets to datasets and models (docs/architecture/BI_MODEL_SPEC.md section 8.3), audited, the text before kept in each item's
 * history.
 *
 * @param onStart run the migrations once at start-up (they are idempotent: a converted item has nothing left to convert)
 * @param actor the name the history, the governance events and the audit log give the start-up run
 */
@ConfigurationProperties("drishti.bi.migrate")
public record MigrateProperties(Boolean onStart, String actor) {

    public MigrateProperties {
        onStart = onStart == null || onStart;
        actor = actor == null || actor.isBlank() ? "system" : actor.strip();
    }
}
