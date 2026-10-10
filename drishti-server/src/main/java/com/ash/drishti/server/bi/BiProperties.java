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
package com.ash.drishti.server.bi;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business intelligence, Rupaka phase 1 (docs/architecture/RUPAKA.md): the shell and the catalogue. The Phase 0 proof of concept
 * keeps its own {@code drishti.bi.poc} block.
 *
 * @param enabled Rupaka's endpoints answer; off: {@code DRS-9006} (the console then hides the BI menu)
 * @param dir the BI folder with shipped, read-only content: {@code datasets/}, {@code reports/}, {@code boards/}, {@code notebooks/}
 * @param watch how the folder is watched: {@code auto} (file events with polling as a safety net), {@code poll} or {@code off} (read when
 *     {@code folderRefresh} has passed, on a request)
 * @param folderRefresh the polling interval (shipped files appear, change and go without a restart)
 * @param workspaceCreation who may create shared workspaces: {@code authors} (bi-author), {@code stewards} (bi-steward) or {@code admins} (bi-admin only);
 *     an administrator can change it at run time in Admin, BI settings
 * @param maxBodyBytes largest YAML body one item may hold
 * @param versionsKeep published versions kept per item (the oldest go first)
 * @param usageDays days of views counted as an item's usage
 * @param recent items shown under Recent on Home and in the BI menu
 * @param listLimit most rows one catalogue or workspace page returns
 */
@ConfigurationProperties("drishti.bi")
public record BiProperties(Boolean enabled, String dir, String watch, Duration folderRefresh, String workspaceCreation, Integer maxBodyBytes, Integer versionsKeep,
        Integer usageDays, Integer recent, Integer listLimit) {

    public BiProperties {
        enabled = enabled == null || enabled;
        dir = dir == null || dir.isBlank() ? "./config/bi" : dir;
        watch = watch == null || watch.isBlank() ? "auto" : watch.strip().toLowerCase(java.util.Locale.ROOT);
        folderRefresh = folderRefresh == null || folderRefresh.isNegative() ? Duration.ofSeconds(5) : folderRefresh;
        workspaceCreation = workspaceCreation == null || workspaceCreation.isBlank() ? "authors" : workspaceCreation;
        maxBodyBytes = maxBodyBytes == null || maxBodyBytes <= 0 ? 262_144 : maxBodyBytes;
        versionsKeep = versionsKeep == null || versionsKeep <= 0 ? 50 : versionsKeep;
        usageDays = usageDays == null || usageDays <= 0 ? 30 : Math.min(usageDays, 365);
        recent = recent == null || recent <= 0 ? 12 : Math.min(recent, 100);
        listLimit = listLimit == null || listLimit <= 0 ? 200 : Math.min(listLimit, 2000);
    }
}
