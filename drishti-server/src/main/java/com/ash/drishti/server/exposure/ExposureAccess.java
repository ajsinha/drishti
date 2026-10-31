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
package com.ash.drishti.server.exposure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who sees what of the exposure engine (EXPOSURE_GUIDE.md section 9, NSET.md section 11). Each role is given, per counterparty, connected group
 * or bank legal entity, one of three levels ({@code drishti.ccr.exposure.access.<role>[CP-ALDERSHOT]}): {@code trades} (the figures,
 * the netting sets and the trades), {@code aggregate} (the figures only: no netting-set row below it, no trade, no trade
 * contribution) or {@code none}. A netting set takes the widest level of its counterparty, its counterparty's group and its legal
 * entity. A role with no entry sees everything at {@code trades} when it may open the exposure paths; a role with entries sees nothing
 * else. A scope is computed over the netting sets the viewer sees; the others are left out and counted. The counterparty kind's
 * {@code NAME} mask shows a counterparty's id in place of its name.
 */
final class ExposureAccess {

    static final int NONE = 0;
    static final int AGGREGATE = 1;
    static final int TRADES = 2;

    /**
     * What one viewer sees on one date.
     *
     * @param level per netting set ordinal: {@link #NONE}, {@link #AGGREGATE} or {@link #TRADES}
     * @param visible the trades of the sets the viewer sees at any level
     * @param detailed the trades of the sets the viewer sees at trades level
     */
    record View(int[] level, OrdinalSet visible, OrdinalSet detailed, boolean full, String signature) {
        boolean seesTrades(OrdinalSet set) {
            return full || detailed.containsAll(set);
        }

        boolean seesSet(int s) {
            return full || level[s] >= AGGREGATE;
        }

        boolean detailsSet(int s) {
            return full || level[s] == TRADES;
        }
    }

    private final ExposureProperties props;
    private final Entitlements entitlements;
    /**
     * The views built, per date, version and role set: bounded by their bytes ({@code drishti.ccr.exposure.view-cache-mb}) and an idle hour,
     * and released with their date ({@link #forget}).
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, View> views;

    ExposureAccess(ExposureProperties props, Entitlements entitlements) {
        this.props = props;
        this.entitlements = entitlements;
        this.views = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(Math.max(1L, props.viewCacheMb()) << 20)
                .weigher((String k, View v) -> weight(v)).expireAfterAccess(java.time.Duration.ofHours(1)).recordStats().build();
    }

    private static int weight(View v) {
        long b = 64L + (long) v.level().length * 4 + (long) v.visible().words().length * 8 + (long) v.detailed().words().length * 8;
        return (int) Math.min(Integer.MAX_VALUE, b);
    }

    /** Releases the views of a date (every version); null releases them all. */
    void forget(java.time.LocalDate date) {
        if (date == null) {
            views.invalidateAll();
        } else {
            views.asMap().keySet().removeIf(k -> k.startsWith(date + "@"));
        }
    }

    /** The views cache, for Admin, Caches. */
    com.github.benmanes.caffeine.cache.Cache<String, View> cache() {
        return views;
    }

    /** The viewer's view of the date (refused when the caller may not open the paths); the server itself ({@code p} null) sees all. */
    View view(Principal p, ExposureDate d) {
        if (p == null) {
            return views.get(d.date() + "@" + d.version() + "|server", s -> build(Map.of(), d, s));
        }
        if (!entitlements.mayOpen(p, props.kinds().paths())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role may not see exposure (the kind " + props.kinds().paths() + ")");
        }
        Map<String, String> levels = levels(p);
        String sig = d.date() + "@" + d.version() + "|" + new java.util.TreeMap<>(levels);
        return views.get(sig, s -> build(levels, d, s));
    }

    /** The levels the caller's roles give (a later role widens); empty: no restriction. */
    Map<String, String> levels(Principal p) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String role : p.roles() == null ? List.<String>of() : p.roles()) {
            Map<String, String> m = props.access().get(role);
            if (m == null) {
                continue;
            }
            m.forEach((key, level) -> {
                String l = level.trim().toLowerCase(Locale.ROOT);
                String now = out.get(key);
                if (now == null || rank(l) > rank(now)) {
                    out.put(key, l);
                }
            });
        }
        return out;
    }

    private static int rank(String level) {
        return switch (level) {
            case "trades", "positions" -> TRADES;
            case "aggregate" -> AGGREGATE;
            default -> NONE;
        };
    }

    private static View build(Map<String, String> levels, ExposureDate d, String sig) {
        ExposureBook b = d.book();
        int n = b.trades().size();
        if (levels.isEmpty()) {
            OrdinalSet all = OrdinalSet.range(0, n);
            int[] lv = new int[b.sets().size()];
            java.util.Arrays.fill(lv, TRADES);
            return new View(lv, all, all, true, "all|" + sig);
        }
        Map<String, String> groupOf = new java.util.HashMap<>();
        d.groups().forEach((g, ms) -> ms.forEach(c -> groupOf.putIfAbsent(c, g)));
        int[] lv = new int[b.sets().size()];
        OrdinalSet.Builder vis = new OrdinalSet.Builder();
        OrdinalSet.Builder det = new OrdinalSet.Builder();
        for (int s = 0; s < lv.length; s++) {
            ExposureBook.SetInfo set = b.set(s);
            int l = NONE;
            for (String key : new String[] {set.id(), set.counterparty(), groupOf.get(set.counterparty()), set.legalEntity()}) {
                if (key != null && levels.containsKey(key)) {
                    l = Math.max(l, rank(levels.get(key)));
                }
            }
            lv[s] = l;
            if (l >= AGGREGATE) {
                vis.addRange(set.from(), set.to());
            }
            if (l == TRADES) {
                det.addRange(set.from(), set.to());
            }
        }
        return new View(lv, vis.build(), det.build(), false, sig);
    }

    boolean masked(Principal p, String field) {
        return p != null && entitlements.masked(p, field);
    }

    /** True when the caller sees everything at trades level (the official records are theirs to read). */
    boolean seesAll(Principal p) {
        return p == null || levels(p).isEmpty() && entitlements.mayOpen(p, props.kinds().paths());
    }
}
