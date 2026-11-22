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
package com.ash.drishti.server.home;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Role Homes (docs/architecture/ROLE_HOME_SUTRAS.md): where the Home files are, which roles open which Homes, and how the change detection
 * keeps its snapshots. Both folders are absolute (the explicit-paths rule): a relative one is refused at start.
 *
 * @param enabled Homes are offered at all
 * @param dir the folder of {@code <module>/<home>.home.yaml} files (the distribution's {@code config/homes})
 * @param dataDir where the cut-off snapshots, change events and drivers are kept ({@code <dataDir>/homes})
 * @param roles role name to the Homes it opens, first is the default (the distribution's {@code homes.roles}; Admin, Roles edits it)
 * @param cutoff the reference cut-off, {@code HH:mm} in the site's time zone
 * @param keepDays days of snapshots kept
 */
@ConfigurationProperties("drishti.homes")
public record HomeProperties(Boolean enabled, String dir, String dataDir, Map<String, List<String>> roles, String cutoff, Integer keepDays) {

    public HomeProperties {
        enabled = enabled == null || enabled;
        roles = roles == null ? Map.of() : Map.copyOf(roles);
        cutoff = cutoff == null || !cutoff.matches("([01]\\d|2[0-3]):[0-5]\\d") ? "17:00" : cutoff;
        keepDays = keepDays == null || keepDays < 2 ? 30 : keepDays;
        for (String p : new String[] {dir, dataDir}) {
            if (p != null && !p.isBlank() && !Path.of(p).isAbsolute()) {
                throw new IllegalArgumentException("drishti.homes: " + p + " is not an absolute path");
            }
        }
    }

    public java.time.LocalTime cutoffTime() {
        return java.time.LocalTime.parse(cutoff);
    }
}
