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
package com.ash.drishti.server.metrics;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform metrics and their alerts, {@code drishti.metrics.*} (OPERATIONS_GUIDE.md section 13.6, CLUSTER_GUIDE.md 8.3 and 8.5).
 *
 * @param enabled export the platform gauges and evaluate the alert rules (true)
 * @param sampleInterval how often the gauges are sampled and the rules evaluated (15 s)
 * @param diskInterval how often the disk folders are measured for the gauges (60 s)
 * @param alerts the alert rules' file, who is told, and how many past alerts are kept
 */
@ConfigurationProperties("drishti.metrics")
public record MetricsProperties(Boolean enabled, Duration sampleInterval, Duration diskInterval, Alerts alerts) {

    public MetricsProperties {
        enabled = enabled == null || enabled;
        sampleInterval = positive(sampleInterval, Duration.ofSeconds(15));
        diskInterval = positive(diskInterval, Duration.ofSeconds(60));
        alerts = alerts == null ? new Alerts(null, null, null, null) : alerts;
    }

    /**
     * @param enabled evaluate the rules (true)
     * @param file the rules ({@code ./config/alerts/platform-alerts.yaml})
     * @param notifyRoles the roles told in their inbox when an alert opens ({@code admin}); empty: nobody
     * @param keep alerts opened and closed kept for the page (200)
     */
    public record Alerts(Boolean enabled, String file, List<String> notifyRoles, Integer keep) {
        public Alerts {
            enabled = enabled == null || enabled;
            file = file == null || file.isBlank() ? "./config/alerts/platform-alerts.yaml" : file;
            notifyRoles = notifyRoles == null ? List.of("admin") : notifyRoles.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            keep = keep == null || keep < 0 ? 200 : keep;
        }
    }

    /** The defaults (tests). */
    public static MetricsProperties defaults() {
        return new MetricsProperties(null, null, null, null);
    }

    private static Duration positive(Duration d, Duration dflt) {
        return d == null || d.isNegative() || d.isZero() ? dflt : d;
    }
}
