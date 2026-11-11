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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Dates refused for room, remembered (VAR_GUIDE.md section 26.8; the VaR and exposure engines share it): a date the engine had no room
 * for (capacity, disk) is refused once; asked again with the same inputs and settings, it is refused at once with {@code Retry-After}
 * the seconds left, and the lake is not read again. A refusal is forgotten when its time is up ({@code refusal-ttl-seconds}), when room is
 * freed (a date or book retired, a reload, a purge), when the date's inputs change (another stamp), or when a setting changes (the stamp
 * holds the settings that decide room).
 */
public final class Refusals {

    /** The refusal codes that mean "no room now" (others are the data's, not the room's, and are not remembered here). */
    private final Set<ErrorCode> codes;
    private final IntSupplier ttlSeconds;
    private final LongSupplier clock;
    private final Map<LocalDate, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(String stamp, ErrorCode code, String reason, long atMillis, long untilMillis) {}

    /**
     * @param codes the refusals remembered
     * @param ttlSeconds how long one is remembered (0: not at all)
     * @param clock the time in milliseconds
     */
    public Refusals(Set<ErrorCode> codes, IntSupplier ttlSeconds, LongSupplier clock) {
        this.codes = Set.copyOf(codes);
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    /** True when {@code e} is a refusal for room this keeps. */
    public boolean remembers(DrishtiException e) {
        return codes.contains(e.errorCode()) && !(e instanceof RefusedForNow);
    }

    /** Remembers a refusal of {@code date} at {@code stamp} (the date's inputs and the settings of room). */
    public void remember(LocalDate date, String stamp, DrishtiException e) {
        int ttl = ttlSeconds.getAsInt();
        if (ttl <= 0 || !remembers(e)) {
            return;
        }
        long now = clock.getAsLong();
        String reason = e.getMessage().startsWith(e.errorCode().code() + " ") ? e.getMessage().substring(e.errorCode().code().length() + 1)
                : e.getMessage();
        entries.put(date, new Entry(stamp, e.errorCode(), reason, now, now + ttl * 1000L));
    }

    /** The remembered refusal of {@code date} at {@code stamp}, to throw now; empty when none (or its time is up, or the stamp moved). */
    public Optional<RefusedForNow> check(LocalDate date, String stamp) {
        Entry e = entries.get(date);
        if (e == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (now >= e.untilMillis() || !e.stamp().equals(stamp)) {
            entries.remove(date, e);
            return Optional.empty();
        }
        long left = (e.untilMillis() - now + 999) / 1000;
        return Optional.of(new RefusedForNow(e.code(), e.reason() + " (refused " + (now - e.atMillis()) / 1000 + " s ago and remembered: "
                + "asked again in " + left + " s, or at once when room is freed)", left));
    }

    /** Room was freed (a date or book retired, a reload, a purge): every refusal is forgotten, the next request tries again. */
    public void roomFreed() {
        entries.clear();
    }

    public void forget(LocalDate date) {
        entries.remove(date);
    }

    public int size() {
        return entries.size();
    }

    /** What the status lists: each refused date, its code, why, and the seconds left. */
    public List<Map<String, Object>> describe() {
        long now = clock.getAsLong();
        List<Map<String, Object>> out = new ArrayList<>();
        entries.forEach((d, e) -> {
            if (now < e.untilMillis()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("businessDate", d.toString());
                m.put("code", e.code().code());
                m.put("refused", e.reason());
                m.put("retryAfterSeconds", (e.untilMillis() - now + 999) / 1000);
                out.add(m);
            }
        });
        return out;
    }
}
