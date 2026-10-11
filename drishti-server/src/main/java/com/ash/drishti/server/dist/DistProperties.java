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
package com.ash.drishti.server.dist;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Distribution of BI and Sutra content ({@code drishti.distribution}): export limits and subscription settings.
 *
 * @param export limits of a PDF, PNG, Excel or CSV export
 * @param subscriptions the scheduled delivery of a report or a view by email
 */
@ConfigurationProperties("drishti.distribution")
public record DistProperties(Export export, Subscriptions subscriptions) {

    public DistProperties {
        export = export == null ? new Export(null, null, null, null, null, null, null, null, null) : export;
        subscriptions = subscriptions == null ? new Subscriptions(null, null, null, null, null, null, null, null) : subscriptions;
    }

    /**
     * @param enabled false turns every export off
     * @param maxRows most rows of one table in an Excel or CSV export (a table is cut there, and the file says so)
     * @param pdfMaxRows most rows drawn per table in a PDF or PNG (the picture is for reading; Excel holds the rest)
     * @param maxBytes largest file an export may produce; over it the export is refused
     * @param timeout longest an export may take
     * @param maxVisuals most visuals one export (a page, or a whole report) may hold
     * @param width the width of the page layout in pixels (the PDF scales it to the page)
     * @param landscape PDF pages are landscape A4 (false: portrait)
     * @param pngMaxHeight tallest PNG in pixels
     */
    public record Export(Boolean enabled, Integer maxRows, Integer pdfMaxRows, Long maxBytes, Duration timeout, Integer maxVisuals, Integer width, Boolean landscape,
            Integer pngMaxHeight) {

        public Export {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            maxRows = maxRows == null ? 50_000 : Math.max(1, maxRows);
            pdfMaxRows = pdfMaxRows == null ? 500 : Math.max(1, pdfMaxRows);
            maxBytes = maxBytes == null ? 25L * 1024 * 1024 : Math.max(1024, maxBytes);
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
            maxVisuals = maxVisuals == null ? 40 : Math.max(1, maxVisuals);
            width = width == null ? 1000 : Math.max(600, width);
            landscape = landscape == null ? Boolean.TRUE : landscape;
            pngMaxHeight = pngMaxHeight == null ? 12_000 : Math.max(400, pngMaxHeight);
        }
    }

    /**
     * @param enabled false stops the scheduler and refuses new subscriptions (existing ones are kept)
     * @param perUser most subscriptions a person may own
     * @param maxRecipients most recipients (after groups are expanded) one run delivers to
     * @param tick how often the scheduler looks for due subscriptions
     * @param keepRuns runs remembered per subscription (history)
     * @param maxAttempts a failed delivery is tried this many times in all, with the shared retry policy ({@code drishti.retry.distribution.*}) between tries
     * @param emailRows most rows per table in an inline HTML email
     * @param loadDelay after a data load, wait this long for the BI layers to rebuild before an after-load subscription runs
     */
    public record Subscriptions(Boolean enabled, Integer perUser, Integer maxRecipients, Duration tick, Integer keepRuns, Integer maxAttempts, Integer emailRows, Duration loadDelay) {

        public Subscriptions {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            perUser = perUser == null ? 20 : Math.max(1, perUser);
            maxRecipients = maxRecipients == null ? 200 : Math.max(1, maxRecipients);
            tick = tick == null ? Duration.ofSeconds(30) : tick;
            keepRuns = keepRuns == null ? 30 : Math.max(1, keepRuns);
            maxAttempts = maxAttempts == null ? 4 : Math.max(1, maxAttempts);
            emailRows = emailRows == null ? 25 : Math.max(1, emailRows);
            loadDelay = loadDelay == null ? Duration.ofSeconds(5) : loadDelay;
        }
    }
}
