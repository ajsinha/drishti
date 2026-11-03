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
package com.ash.drishti.dataload;

import java.time.Duration;

/**
 * The data inbox, {@code drishti.dataload.*} (docs/architecture/DATALOAD_INBOX.md section 9; config/engines/dataload.yaml). Plain values:
 * the server binds the keys onto this record; this module reads no configuration of its own but the routing rules' file.
 *
 * @param owner whether this node runs the inbox (true; in a cluster exactly one node should)
 * @param dir the inbox's folder ({@code <data>/dataload}): {@code inbox/}, {@code work/}, {@code outbox/}, {@code failed/}, {@code ledger/}
 * @param interval how often a cycle runs (10 minutes)
 * @param stableSeconds a file is taken once its size and time have not changed for this long (30)
 * @param maxFiles the most files one cycle takes (500)
 * @param maxGb the most gigabytes one cycle takes (50; the first file is always taken)
 * @param loaderHeap the loader's heap, the child process's {@code -Xmx} (4g)
 * @param loadTimeout the longest one load may take before it is stopped and its files go back (2 hours)
 * @param rejectMaxPercent the share of rejected rows a load may have and still commit (0: none)
 * @param reloadDuplicates whether a file whose SHA-256 was loaded before is loaded again (false: skipped, to the outbox)
 * @param keepOutboxDays the days the outbox and failed folders are kept (30; the ledger is kept)
 * @param lake the lake root the tables are written under (the packs' Delta root)
 * @param rulesFile the routing rules ({@code config/dataload.yaml})
 * @param nice whether the loader runs at a lower priority ({@code nice}, where the system has it; true)
 */
public record DataLoadSettings(Boolean owner, String dir, Duration interval, Integer stableSeconds, Integer maxFiles, Double maxGb, String loaderHeap,
        Duration loadTimeout, Double rejectMaxPercent, Boolean reloadDuplicates, Integer keepOutboxDays, String lake, String rulesFile, Boolean nice) {

    public DataLoadSettings {
        owner = owner == null || owner;
        dir = blank(dir) ? "./data/dataload" : dir.trim();
        interval = interval == null || interval.isNegative() || interval.isZero() ? Duration.ofMinutes(10) : interval;
        stableSeconds = stableSeconds == null || stableSeconds < 0 ? 30 : stableSeconds;
        maxFiles = maxFiles == null || maxFiles < 1 ? 500 : maxFiles;
        maxGb = maxGb == null || maxGb <= 0 ? 50.0 : maxGb;
        loaderHeap = blank(loaderHeap) ? "4g" : loaderHeap.trim();
        if (!loaderHeap.matches("[0-9]+[kKmMgG]?")) {
            throw new IllegalArgumentException("drishti.dataload.loader-heap " + loaderHeap + ": a size as java -Xmx takes it (4g, 512m)");
        }
        loadTimeout = loadTimeout == null || loadTimeout.isNegative() || loadTimeout.isZero() ? Duration.ofHours(2) : loadTimeout;
        rejectMaxPercent = rejectMaxPercent == null || rejectMaxPercent < 0 ? 0.0 : Math.min(100.0, rejectMaxPercent);
        reloadDuplicates = reloadDuplicates != null && reloadDuplicates;
        keepOutboxDays = keepOutboxDays == null || keepOutboxDays < 0 ? 30 : keepOutboxDays;
        lake = blank(lake) ? "./data/delta" : lake.trim();
        rulesFile = blank(rulesFile) ? "./config/dataload.yaml" : rulesFile.trim();
        nice = nice == null || nice;
    }

    /** Every default (tests, and a server that sets nothing). */
    public static DataLoadSettings defaults() {
        return new DataLoadSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The same settings with another folder, lake and rules file (tests). */
    public DataLoadSettings at(String dir, String lake, String rulesFile) {
        return new DataLoadSettings(owner, dir, interval, stableSeconds, maxFiles, maxGb, loaderHeap, loadTimeout, rejectMaxPercent, reloadDuplicates,
                keepOutboxDays, lake, rulesFile, nice);
    }

    /** {@link #maxGb} in bytes. */
    public long maxBytes() {
        return (long) (maxGb * 1024 * 1024 * 1024);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
