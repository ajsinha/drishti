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

import java.util.Locale;
import java.util.Map;

/**
 * How many business days of ids a connector keeps for type-ahead, and how. Read from the connector's settings
 * {@code index.days}, {@code index.max-memory}, {@code index.warm} and {@code index.any-recent-day}; a kind overrides any of
 * them with {@code index.kinds.<kind>.<setting>}. The default is one day: today's behaviour, the newest day only.
 *
 * @param days business days kept (1 or more)
 * @param maxMemoryBytes the most the kept days may use (an estimate); older days are dropped early above it; 0 = no cap
 * @param warm how past days are built: {@code background} after start, newest first, or {@code lazy} on the first search
 * @param anyRecentDay a bare id on the newest day may also suggest ids seen only on earlier kept days
 * @param storage {@code heap}: closed days stay on the heap until {@code max-memory}, then move to memory-mapped files;
 *     {@code mmap}: closed days go to files at once
 * @param maxDiskBytes the most the connector's segment files may use; the oldest days drop above it; 0 = no cap
 */
public record DayIndexSettings(int days, long maxMemoryBytes, Warm warm, boolean anyRecentDay, Storage storage, long maxDiskBytes) {

    /** How past days are built. */
    public enum Warm {
        BACKGROUND,
        LAZY
    }

    /** Where closed days live. */
    public enum Storage {
        HEAP,
        MMAP
    }

    public static final DayIndexSettings DEFAULT = new DayIndexSettings(1, 600L << 20, Warm.BACKGROUND, false, Storage.HEAP, 2L << 30);

    public DayIndexSettings {
        days = Math.max(1, days);
        maxMemoryBytes = Math.max(0, maxMemoryBytes);
        maxDiskBytes = Math.max(0, maxDiskBytes);
    }

    /** True when more than the newest day is kept. */
    public boolean multiDay() {
        return days > 1;
    }

    /** The settings for {@code kind} (null for the connector's defaults): kind overrides first, then connector, then default. */
    public static DayIndexSettings of(Map<String, String> settings, String kind) {
        int days = (int) number(settings, kind, "days", DEFAULT.days(), false);
        long mem = number(settings, kind, "max-memory", DEFAULT.maxMemoryBytes(), true);
        String warm = text(settings, kind, "warm", "background");
        String recent = text(settings, kind, "any-recent-day", "false");
        long disk = number(settings, kind, "max-disk", DEFAULT.maxDiskBytes(), true);
        Storage storage = "mmap".equalsIgnoreCase(text(settings, kind, "storage", "heap")) ? Storage.MMAP : Storage.HEAP;
        return new DayIndexSettings(days, mem, "lazy".equalsIgnoreCase(warm) ? Warm.LAZY : Warm.BACKGROUND, Boolean.parseBoolean(recent), storage, disk);
    }

    private static String text(Map<String, String> s, String kind, String key, String fallback) {
        String v = kind == null ? null : s.get("index.kinds." + kind + "." + key);
        if (v == null || v.isBlank()) {
            v = s.get("index." + key);
        }
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static long number(Map<String, String> s, String kind, String key, long fallback, boolean size) {
        String v = text(s, kind, key, null);
        if (v == null) {
            return fallback;
        }
        try {
            return size ? bytes(v) : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code 600MB}, {@code 2GB}, {@code 512k} or plain bytes; 0 means no cap. */
    static long bytes(String text) {
        String t = text.trim().toUpperCase(Locale.ROOT).replace("IB", "B");
        long unit = 1;
        if (t.endsWith("GB") || t.endsWith("G")) {
            unit = 1L << 30;
        } else if (t.endsWith("MB") || t.endsWith("M")) {
            unit = 1L << 20;
        } else if (t.endsWith("KB") || t.endsWith("K")) {
            unit = 1L << 10;
        }
        String digits = t.replaceAll("[^0-9.]", "");
        return (long) (Double.parseDouble(digits) * unit);
    }
}
