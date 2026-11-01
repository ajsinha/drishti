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
package com.ash.drishti.server.caches;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which business dates stay in memory (VAR_GUIDE.md section 26.8, the product owner's rule; the VaR and exposure engines share it):
 *
 * <ol>
 *   <li>a configured number of dates ({@code hot-dates}) is open at once;</li>
 *   <li>the least recently used date leaves first (every request stamps the date it reads, not only its load);</li>
 *   <li>protected dates never leave: the date just used, the newest business date, and the dates pinned in memory (counted within the
 *       number; when the protected dates alone exceed it, the number is exceeded and the status says so);</li>
 *   <li>a byte safety net over every open book's heap: over it, the least recently used date's extra books (its reporting currencies, then
 *       its stress book) leave first, then whole dates least recently used first, never a protected one, and never a date read in the
 *       last {@code -Ddrishti.open-dates.net-grace-seconds} (120): a date in use stays even over the net, since closing it would only
 *       load it again at once (each request closing the other date: a reload storm under load).</li>
 * </ol>
 *
 * <p>This class decides; the engine retires (its books are unmapped after the close grace, so requests still reading finish).
 */
public final class OpenDates {

    /** The grace of a date just read against the byte net, in seconds ({@code -Ddrishti.open-dates.net-grace-seconds}, 120). */
    public static final long NET_GRACE_SECONDS = Long.getLong("drishti.open-dates.net-grace-seconds", 120L);

    private final Map<LocalDate, Long> used = new ConcurrentHashMap<>();
    private final Map<LocalDate, Long> usedAt = new ConcurrentHashMap<>();
    private final AtomicLong tick = new AtomicLong();
    private final java.util.function.LongSupplier clockMillis;
    private final long graceMillis;

    public OpenDates() {
        this(System::currentTimeMillis, NET_GRACE_SECONDS * 1000L);
    }

    /** With a clock and the net's grace (milliseconds; 0: none), for tests. */
    public OpenDates(java.util.function.LongSupplier clockMillis, long graceMillis) {
        this.clockMillis = clockMillis;
        this.graceMillis = Math.max(0L, graceMillis);
    }

    /** A date was read now (a load or any request of an open date). */
    public void touch(LocalDate d) {
        used.put(d, tick.incrementAndGet());
        usedAt.put(d, clockMillis.getAsLong());
    }

    public void forget(LocalDate d) {
        used.remove(d);
        usedAt.remove(d);
    }

    /** Whether the date was read within the net's grace: the byte net leaves it (and its extra books) open. */
    public boolean inUse(LocalDate d) {
        Long at = usedAt.get(d);
        return graceMillis > 0 && at != null && clockMillis.getAsLong() - at < graceMillis;
    }

    /** The order of use of a date (higher: more recent; 0: never). */
    public long lastUsed(LocalDate d) {
        return used.getOrDefault(d, 0L);
    }

    /** The open dates, least recently used first (older dates first among those never used). */
    public List<LocalDate> leastRecentFirst(Collection<LocalDate> open) {
        List<LocalDate> ds = new ArrayList<>(open);
        ds.sort(Comparator.comparingLong(this::lastUsed).thenComparing(Comparator.naturalOrder()));
        return ds;
    }

    /** The open dates to close to bring them to {@code hotDates}: least recently used first, never a protected one. */
    public List<LocalDate> overCount(Collection<LocalDate> open, int hotDates, Set<LocalDate> protect) {
        int over = open.size() - hotDates;
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d : leastRecentFirst(open)) {
            if (out.size() >= over) {
                break;
            }
            if (!protect.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** One extra book of a date that can leave before the date (a reporting currency's, the stress set's): rebuilt from disk on ask. */
    public record Extra(String what, long bytes, Runnable retire) {}

    /** What the byte safety net reads and does. */
    public interface Books {
        /** The heap bytes of every book of the date now (its base book, its extra books, its intraday layers). */
        long bytes(LocalDate d);

        /** The date's extra books, in the order they leave (the currencies first, then the stress book). */
        List<Extra> extras(LocalDate d);

        /** Closes the date whole (with its books). */
        void retire(LocalDate d);
    }

    /**
     * Brings the open books' heap under {@code netBytes}: the least recently used dates' extra books first (but those of {@code keep}, the
     * date just used, and of a date in use), then whole dates least recently used first, never a protected one or a date in use. Returns what left, in order ({@code
     * <date> <what>} or {@code <date> date}); when it is still over (only protected dates left), it stops: nothing is refused here.
     */
    public List<String> underNet(Collection<LocalDate> open, long netBytes, LocalDate keep, Set<LocalDate> protect, Books books) {
        List<String> done = new ArrayList<>();
        if (netBytes <= 0) {
            return done;
        }
        List<LocalDate> order = leastRecentFirst(open);
        long total = 0;
        for (LocalDate d : order) {
            total += books.bytes(d);
        }
        for (LocalDate d : order) {
            if (total <= netBytes) {
                return done;
            }
            if (d.equals(keep) || inUse(d)) {
                continue;
            }
            for (Extra x : books.extras(d)) {
                if (total <= netBytes) {
                    return done;
                }
                x.retire().run();
                total -= x.bytes();
                done.add(d + " " + x.what());
            }
        }
        for (LocalDate d : order) {
            if (total <= netBytes) {
                return done;
            }
            if (!protect.contains(d) && !inUse(d)) {
                total -= books.bytes(d);
                books.retire(d);
                done.add(d + " date");
            }
        }
        return done;
    }

    /**
     * The automatic safety net (VAR_GUIDE.md section 27.1): {@code 0.4 x heap - 0.5 GB - loadThreads x the transient of one load}, at
     * least a tenth of the heap.
     */
    public static long automaticNet(long maxHeap, int loadThreads, long perLoadBytes) {
        long net = (long) (0.4 * maxHeap) - (512L << 20) - (long) loadThreads * perLoadBytes;
        return Math.max(net, maxHeap / 10);
    }
}
