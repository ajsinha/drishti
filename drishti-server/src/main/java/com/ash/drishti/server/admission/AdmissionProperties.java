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
package com.ash.drishti.server.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Busy protection, {@code drishti.admission.*} (docs/admin/OPERATIONS.md, "Busy protection"): the levels at which new heavy work and new
 * sign-ins wait, so the server refuses politely instead of running out of memory. Shares are of {@code -Xmx}; every level has a warn
 * level below it that only colours Admin, Health.
 *
 * @param enabled false: nothing waits (the readings are still shown)
 * @param memoryWarn the heap after the last collection past which Health says "getting full" (0.80)
 * @param memoryRefuseHeavy past this, new date loads, downloads, BI runs and bulk loads wait (0.85)
 * @param memoryRefuseSignIn past this, new sign-ins wait too (0.92); people signed in keep working
 * @param memoryFreeMinMb the memory levels act only while the heap left after the last collection is also under this (768 MB): a
 *     small heap that sits past 85% in its steady state is not short of memory, and a large one still has room at 85%
 * @param queuedLoads the date loads that may wait behind the running ones ({@code load-threads}) before a new one waits (2)
 * @param openHeapRefuse new date loads wait when the open dates' heap passes this share of their safety net (1.2: the dates in use alone
 *     are well over it, so a new date could not be made room for)
 * @param requestHandlers the requests the server answers at once before it counts as full (200)
 * @param requestsWarn the share of {@code request-handlers} busy past which Health says "getting full" (0.75)
 * @param requestsRefuse past this share, new heavy work waits (0.90)
 * @param queueRefuse new heavy work waits when more requests than this wait beyond {@code request-handlers} (50)
 * @param diskWarnGb free disk under this, Health says "getting full" (20)
 * @param diskRefuseGb free disk under this, new date loads, downloads and bulk loads wait (10)
 * @param retryAfter the seconds each kind of work is told to wait ({@code Retry-After})
 * @param keep the refusals kept for Health (50)
 * @param overrideMaxMinutes the longest an operator's override may last (240)
 */
@ConfigurationProperties("drishti.admission")
public record AdmissionProperties(Boolean enabled, Double memoryWarn, Double memoryRefuseHeavy, Double memoryRefuseSignIn, Integer memoryFreeMinMb,
        Integer queuedLoads,
        Double openHeapRefuse, Integer requestHandlers, Double requestsWarn, Double requestsRefuse, Integer queueRefuse, Double diskWarnGb,
        Double diskRefuseGb, RetryAfter retryAfter, Integer keep, Integer overrideMaxMinutes) {

    /** The seconds each kind of work waits before it is tried again. */
    public record RetryAfter(Integer signIn, Integer load, Integer download, Integer biRun, Integer bulkLoad, Integer pivotView) {

        public RetryAfter {
            signIn = pos(signIn, 45);
            load = pos(load, 30);
            download = pos(download, 30);
            biRun = pos(biRun, 60);
            bulkLoad = pos(bulkLoad, 120);
            pivotView = pos(pivotView, 20);
        }

        int of(Work w) {
            return switch (w) {
                case SIGN_IN -> signIn;
                case LOAD -> load;
                case DOWNLOAD -> download;
                case BI_RUN -> biRun;
                case BULK_LOAD -> bulkLoad;
                case PIVOT_VIEW -> pivotView;
                case FLIGHT -> download;
            };
        }
    }

    public AdmissionProperties {
        enabled = enabled == null || enabled;
        memoryWarn = share(memoryWarn, 0.80);
        memoryRefuseHeavy = share(memoryRefuseHeavy, 0.85);
        memoryRefuseSignIn = share(memoryRefuseSignIn, 0.92);
        memoryFreeMinMb = memoryFreeMinMb == null || memoryFreeMinMb < 0 ? 768 : memoryFreeMinMb;
        queuedLoads = queuedLoads == null || queuedLoads < 0 ? 2 : queuedLoads;
        openHeapRefuse = openHeapRefuse == null || openHeapRefuse <= 0 ? 1.2 : openHeapRefuse;
        requestHandlers = pos(requestHandlers, 200);
        requestsWarn = share(requestsWarn, 0.75);
        requestsRefuse = share(requestsRefuse, 0.90);
        queueRefuse = queueRefuse == null || queueRefuse < 0 ? 50 : queueRefuse;
        diskWarnGb = diskWarnGb == null || diskWarnGb < 0 ? 20.0 : diskWarnGb;
        diskRefuseGb = diskRefuseGb == null || diskRefuseGb < 0 ? 10.0 : diskRefuseGb;
        retryAfter = retryAfter == null ? new RetryAfter(null, null, null, null, null, null) : retryAfter;
        keep = pos(keep, 50);
        overrideMaxMinutes = pos(overrideMaxMinutes, 240);
    }

    /** The defaults (tests, and a server without the section). */
    public static AdmissionProperties defaults() {
        return new AdmissionProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static double share(Double v, double fallback) {
        return v == null || v <= 0 || v > 1 ? fallback : v;
    }

    private static int pos(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }
}
