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
package com.ash.drishti.server.compute;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engines' version watch (RESOURCES_AND_SCALE.md section 4.4, O9, 2.9.7; decision PO-9): an open business date notices that its data
 * changed in the lake without a data-load signal. On a request for an open date, at most once every {@code every} per date, the lake's
 * version of the date (a lookup on the connector's own layout, refreshed every {@code refresh-seconds}) is compared with the version the
 * date was loaded from. When it moved, the date is reloaded in the background and swapped in when ready; meanwhile the open date answers,
 * with a warning saying so. One reload at a time per date, however many requests ask; a failed reload keeps the old date, and the warning
 * says why; a version that cannot be read is tried again at the next interval, with no warning.
 */
public final class VersionWatch {

    private static final Logger LOG = LoggerFactory.getLogger(VersionWatch.class);

    private final String engine;
    private final Duration every;
    private final Function<LocalDate, String> lakeVersion;
    private final LongSupplier clock;
    private final Map<LocalDate, Long> checkedAt = new ConcurrentHashMap<>();
    private final Set<LocalDate> refreshing = ConcurrentHashMap.newKeySet();
    private final Map<LocalDate, String> failed = new ConcurrentHashMap<>();
    private final AtomicLong reloads = new AtomicLong();

    public VersionWatch(String engine, Duration every, Function<LocalDate, String> lakeVersion) {
        this(engine, every, lakeVersion, System::currentTimeMillis);
    }

    public VersionWatch(String engine, Duration every, Function<LocalDate, String> lakeVersion, LongSupplier clock) {
        this.engine = engine;
        this.every = every;
        this.lakeVersion = lakeVersion;
        this.clock = clock;
    }

    /**
     * Checks an open date loaded from {@code openVersion} ({@code skip}: a date the watch leaves to its signal, such as a VaR date with an
     * intraday layer); when the lake moved on, runs {@code reload} once in the background. Returns the warning the answer carries, or null.
     */
    public String check(LocalDate d, String openVersion, boolean skip, Runnable reload) {
        if (every.isZero() || skip || openVersion == null || openVersion.isEmpty()) {
            return null;
        }
        if (refreshing.contains(d)) {
            return loading(d);
        }
        long now = clock.getAsLong();
        Long at = checkedAt.get(d);
        if (at != null && now - at < every.toMillis()) {
            return failed.get(d);
        }
        checkedAt.put(d, now);
        String v;
        try {
            v = lakeVersion.apply(d);
        } catch (RuntimeException e) {
            return null;                                                // the log cannot be read now: no warning, tried again later
        }
        if (v == null || v.isEmpty() || v.equals(openVersion)) {
            failed.remove(d);
            return null;
        }
        if (!refreshing.add(d)) {
            return loading(d);
        }
        reloads.incrementAndGet();
        LOG.info("{}: the lake's data of {} changed without a data-load signal ({} -> {}): reloading it in the background", engine, d,
                abbrev(openVersion), abbrev(v));
        Thread.ofVirtual().name("drishti-" + engine + "-watch-" + d).start(() -> {
            try {
                reload.run();
                failed.remove(d);
            } catch (RuntimeException e) {
                failed.put(d, "the lake's data of " + d + " changed, but the new version could not be loaded (" + e.getMessage() + "): these "
                        + "figures are from the version loaded before");
                LOG.warn("{}: {} could not be reloaded after its data changed: {}", engine, d, e.toString());
            } finally {
                refreshing.remove(d);
            }
        });
        return loading(d);
    }

    private static String loading(LocalDate d) {
        return "the lake's data of " + d + " changed at " + Instant.now().toString().substring(0, 19) + "Z; a new version is loading";
    }

    private static String abbrev(String v) {
        return v.length() > 24 ? v.substring(0, 24) + "…" : v;
    }

    /** Forgets a date that left memory. */
    public void forget(LocalDate d) {
        if (d == null) {
            checkedAt.clear();
            failed.clear();
        } else {
            checkedAt.remove(d);
            failed.remove(d);
        }
    }

    /** True while a date is being reloaded by the watch (tests). */
    public boolean refreshing(LocalDate d) {
        return refreshing.contains(d);
    }

    /** Reloads the watch started. */
    public long reloads() {
        return reloads.get();
    }
}
