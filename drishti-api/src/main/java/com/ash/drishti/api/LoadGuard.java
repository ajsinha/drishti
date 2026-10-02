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
package com.ash.drishti.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What every loader checks before it writes or drops a business day, so that one mis-dated row cannot wipe a store's
 * history (shared by the loaders of the dated connectors: PostgreSQL, DuckDB, MongoDB, Redis, Iceberg, Aerospike and
 * the JSON-lines files):
 *
 * <ul>
 *   <li><b>No business date in the future.</b> A row dated after today plus {@code --future-days} (1: tomorrow) in the
 *       business zone ({@code --zone}, else {@code DRISHTI_BUSINESS_ZONE}, else {@code America/New_York}) is quarantined:
 *       it is not written, the first few are named on standard error, and the load ends with an error once the rows
 *       that are good are in ({@link #finish()}).</li>
 *   <li><b>Retention counts back from today.</b> A retention cut-off ({@code --keep-days}, {@code --keep-months},
 *       {@code --ttl-days}) is computed from {@code --as-of} (default: today in the business zone), never from the
 *       newest date of the load or of the store.</li>
 *   <li><b>No mass drop by accident.</b> A run that would drop more than {@code --max-drop-share} (0.5) of a table's
 *       data refuses to drop anything unless {@code --force-drop} is given ({@link #checkDrop}).</li>
 * </ul>
 *
 * <p>Thread-safe: the loaders' parsing threads call {@link #accept} at once.
 */
public final class LoadGuard {

    /** The business zone when neither {@code --zone} nor {@code DRISHTI_BUSINESS_ZONE} names one. */
    public static final String DEFAULT_ZONE = "America/New_York";
    /** Rows named on standard error before the rest are only counted. */
    private static final int EXAMPLES = 10;

    private final ZoneId zone;
    private final LocalDate today;
    private final LocalDate asOf;
    private final int futureDays;
    private final double maxDropShare;
    private final boolean forceDrop;
    private final AtomicLong quarantined = new AtomicLong();

    /**
     * A guard.
     *
     * @param zone the business zone whose calendar date is today
     * @param clock the clock (the system clock outside tests)
     * @param asOf the date retention counts back from (null: today)
     * @param futureDays how many days after today a business date may be (1: tomorrow)
     * @param maxDropShare the largest share of a table's data one run may drop without {@code forceDrop}
     * @param forceDrop drop even more than that
     */
    public LoadGuard(ZoneId zone, Clock clock, LocalDate asOf, int futureDays, double maxDropShare, boolean forceDrop) {
        if (futureDays < 0) {
            throw new IllegalArgumentException("--future-days must be 0 or more: " + futureDays);
        }
        if (!(maxDropShare >= 0 && maxDropShare <= 1)) {
            throw new IllegalArgumentException("--max-drop-share must be between 0 and 1: " + maxDropShare);
        }
        this.zone = zone;
        this.today = LocalDate.now(clock.withZone(zone));
        this.asOf = asOf == null ? today : asOf;
        this.futureDays = futureDays;
        this.maxDropShare = maxDropShare;
        this.forceDrop = forceDrop;
    }

    /**
     * The guard a loader's command line asks for: {@code --zone Z}, {@code --as-of yyyy-MM-dd}, {@code --future-days N},
     * {@code --max-drop-share F}, {@code --force-drop}; anything else is the loader's own.
     */
    public static LoadGuard fromArgs(String[] args) {
        return fromArgs(args, System.getenv(), Clock.systemUTC());
    }

    static LoadGuard fromArgs(String[] args, Map<String, String> env, Clock clock) {
        String zone = value(args, "--zone", env.getOrDefault("DRISHTI_BUSINESS_ZONE", ""));
        String asOf = value(args, "--as-of", "");
        return new LoadGuard(ZoneId.of(zone.isBlank() ? DEFAULT_ZONE : zone), clock, asOf.isBlank() ? null : LocalDate.parse(asOf),
                Integer.parseInt(value(args, "--future-days", "1")), Double.parseDouble(value(args, "--max-drop-share", "0.5")),
                List.of(args).contains("--force-drop"));
    }

    private static String value(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    /** Today in the business zone. */
    public LocalDate today() {
        return today;
    }

    /** The date retention counts back from: {@code --as-of}, else today. */
    public LocalDate asOf() {
        return asOf;
    }

    /** The latest business date a row may carry: today plus {@code --future-days}. */
    public LocalDate latestAllowed() {
        return today.plusDays(futureDays);
    }

    /**
     * True when a row of this business date may be written; false when it is in the future, and the row is
     * quarantined (counted, and named on standard error while few).
     */
    public boolean accept(LocalDate businessDate, String what) {
        if (!businessDate.isAfter(latestAllowed())) {
            return true;
        }
        long n = quarantined.incrementAndGet();
        if (n <= EXAMPLES) {
            String line = "quarantined (business date " + businessDate + " is after " + latestAllowed() + "): " + what;
            System.err.println(line.length() > 300 ? line.substring(0, 300) + "…" : line);
        }
        return false;
    }

    /** Rows quarantined so far. */
    public long quarantined() {
        return quarantined.get();
    }

    /** Ends a load: an error naming the quarantined rows when there were any (the good rows are already in). */
    public void finish() {
        long n = quarantined.get();
        if (n > 0) {
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                    "%,d rows not loaded: their business date is after %s (today %s in %s, plus --future-days %d); fix the source or pass "
                            + "--future-days. The other rows are loaded.", n, latestAllowed(), today, zone, futureDays));
        }
    }

    /** The first day kept by a retention of {@code keepDays} calendar days counted back from {@link #asOf()}. */
    public LocalDate keepFromDays(int keepDays) {
        return asOf.minusDays(Math.max(1, keepDays) - 1L);
    }

    /** The first day kept by a retention of {@code keepMonths} calendar months (the month of {@link #asOf()} is the newest). */
    public LocalDate keepFromMonths(int keepMonths) {
        return asOf.withDayOfMonth(1).minusMonths(Math.max(1, keepMonths) - 1L);
    }

    /**
     * The first day kept by a retention of the {@code keep} newest business days a table holds on or before
     * {@link #asOf()} (days after it are never counted, nor dropped); empty when it holds no more than that.
     */
    public Optional<LocalDate> keepFromNewest(Collection<LocalDate> days, int keep) {
        TreeSet<LocalDate> past = new TreeSet<>();
        for (LocalDate d : days) {
            if (!d.isAfter(asOf)) {
                past.add(d);
            }
        }
        if (keep <= 0 || past.size() <= keep) {
            return Optional.empty();
        }
        return Optional.of(past.descendingSet().stream().skip(keep - 1L).findFirst().orElseThrow());
    }

    /**
     * Refuses a retention step that would drop more than {@code --max-drop-share} of a table's data (rows, or
     * business days where rows are not counted) unless {@code --force-drop} is given.
     *
     * @throws IllegalStateException when the share is too large; nothing has been dropped yet
     */
    public void checkDrop(String what, long dropping, long total, String unit) {
        if (forceDrop || total <= 0 || dropping <= maxDropShare * total) {
            return;
        }
        throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                "%s: retention would drop %,d of %,d %s (more than --max-drop-share %.2f), counting back from %s; nothing was dropped. "
                        + "Check the dates, or pass --force-drop.", what, dropping, total, unit, maxDropShare, asOf));
    }
}
