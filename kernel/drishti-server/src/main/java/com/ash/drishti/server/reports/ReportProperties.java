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
package com.ash.drishti.server.reports;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scheduled reports ({@code drishti.reports}).
 *
 * @param enabled false stops the scheduler (reports can still be run by hand); run it on one server of a group
 * @param folder where folder deliveries are written: {@code <folder>/<user>/<report>/<report>-<date>-<HHmm>.csv}
 * @param webhooks URL prefixes a report may be posted to; empty means no webhooks (no request forgery through reports)
 * @param perUser most reports a user may keep
 * @param tick how often the scheduler looks for due reports
 * @param keepRuns runs remembered per report
 */
@ConfigurationProperties("drishti.reports")
public record ReportProperties(Boolean enabled, String folder, List<String> webhooks, Integer perUser, Duration tick, Integer keepRuns) {

    public ReportProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        folder = folder == null || folder.isBlank() ? com.ash.drishti.api.DataDir.under("reports") : folder;
        webhooks = webhooks == null ? List.of() : List.copyOf(webhooks);
        perUser = perUser == null ? 20 : perUser;
        tick = tick == null ? Duration.ofSeconds(30) : tick;
        keepRuns = keepRuns == null ? 20 : keepRuns;
    }

    /** A webhook URL is allowed when it starts with one of the configured prefixes (and is http or https). */
    public boolean webhookAllowed(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://")) && webhooks.stream().anyMatch(url::startsWith);
    }
}
