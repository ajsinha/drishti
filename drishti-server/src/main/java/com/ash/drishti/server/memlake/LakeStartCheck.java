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
package com.ash.drishti.server.memlake;

import com.ash.drishti.lakeconfig.LakeCheck;
import com.ash.drishti.lakeconfig.Lakes;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The lake files' check at start (docs/architecture/DATA_ARCHITECTURE.md section 8.6; docs/guides/LAKE_GUIDE.md section 5): each lake file is
 * read and every table of it on a local disk compared with its Delta log or Iceberg metadata (format, partition columns, promoted
 * columns). A difference is logged as a warning, never rewritten; a lake file the reader refuses is logged with its reason. A connector
 * that names an unknown lake fails when it starts, with the reason on Admin -> Connectors. {@code drishti.py lake check} runs the same.
 */
@Component
class LakeStartCheck {

    private static final Logger LOG = LoggerFactory.getLogger(LakeStartCheck.class);

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        try {
            Lakes lakes = Lakes.current();
            if (lakes.all().isEmpty()) {
                return;
            }
            int errors = 0;
            for (LakeCheck.Finding f : LakeCheck.check(lakes, Map.of())) {
                if (f.level().equals("error")) {
                    errors++;
                    LOG.warn("lake {} table {}: {}", f.lake(), f.table(), f.message());
                }
            }
            LOG.info("lake files: {} lakes in {} checked against their tables, {} differences", lakes.all().size(), Lakes.dir(), errors);
        } catch (RuntimeException e) {
            LOG.warn("the lake files in {} were refused: {}", Lakes.dir(), e.getMessage());
        }
    }
}
