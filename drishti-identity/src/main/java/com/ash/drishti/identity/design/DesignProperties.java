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
package com.ash.drishti.identity.design;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.builder.designs.*}: where Designs are kept and how much of it one user may use.
 *
 * @param store {@code file} (default: {@code dir}, files readable by the server only) or {@code jpa} (the identity database)
 * @param dir the file store's directory
 * @param maxPerUser most Designs one user keeps
 * @param maxSamples most samples in one Design
 * @param maxMb most megabytes of sample documents in one Design
 * @param maxUserMb most megabytes of sample documents across one user's Designs
 * @param scratchTtl an unnamed (scratch) Design is deleted this long after it was last touched
 * @param namedTtl a named Design is deleted this long after it was last touched
 * @param warnAfter a named Design untouched this long is listed with a warning that it will expire
 * @param sweepInterval how often expired Designs are deleted
 * @param maxSutraKb largest Sutra text of one Design, in kilobytes (DRS-5005 beyond it)
 * @param maxNotesKb largest notes text
 * @param maxTestsKb largest tests (the JSON of the list)
 * @param maxScratch most scratch (unnamed) Designs one user keeps; the oldest is deleted to make room, and they do not count toward {@code maxPerUser}
 * @param maxOps most steps of the operation log a Design keeps for undo and redo (the oldest are dropped)
 */
@ConfigurationProperties("drishti.builder.designs")
public record DesignProperties(String store, String dir, Integer maxPerUser, Integer maxSamples, Integer maxMb, Integer maxUserMb,
        Duration scratchTtl, Duration namedTtl, Duration warnAfter, Duration sweepInterval, Integer maxOps,
        Integer maxSutraKb, Integer maxNotesKb, Integer maxTestsKb, Integer maxScratch) {


    public DesignProperties {
        store = store == null || store.isBlank() ? "file" : store.trim().toLowerCase();
        dir = dir == null || dir.isBlank() ? "./data/designs" : dir;
        maxPerUser = positive(maxPerUser, 50);
        maxSamples = positive(maxSamples, 50);
        maxMb = positive(maxMb, 25);
        maxUserMb = positive(maxUserMb, 250);
        scratchTtl = scratchTtl == null ? Duration.ofDays(1) : scratchTtl;
        namedTtl = namedTtl == null ? Duration.ofDays(90) : namedTtl;
        warnAfter = warnAfter == null ? Duration.ofDays(75) : warnAfter;
        sweepInterval = sweepInterval == null ? Duration.ofHours(1) : sweepInterval;
        maxOps = positive(maxOps, 100);
        maxSutraKb = positive(maxSutraKb, 1024);
        maxNotesKb = positive(maxNotesKb, 256);
        maxTestsKb = positive(maxTestsKb, 1024);
        maxScratch = positive(maxScratch, 10);
    }

    public static DesignProperties defaults() {
        return new DesignProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean jpa() {
        return "jpa".equals(store);
    }

    public long maxBytes() {
        return maxMb * 1024L * 1024L;
    }

    public long maxUserBytes() {
        return maxUserMb * 1024L * 1024L;
    }

    private static Integer positive(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }
}
