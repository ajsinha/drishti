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
package com.ash.drishti.server.var;

import com.ash.drishti.server.caches.CacheReport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The VaR engine's caches as Admin, Caches shows them (2.9.7, O1, O2): the answers, the open dates (their skeleton on the heap, their rows
 * mapped), the hot files on disk, the viewers' views and the shard books; each purged whole or for one business date.
 */
public final class VarCaches implements CacheReport.Owner {

    static final String ANSWERS = "var.answers";
    static final String DATES = "var.dates";
    static final String HOT = "var.hot-files";
    static final String VIEWS = "var.views";
    static final String SHARDS = "var.shard-books";

    private final VarService svc;

    public VarCaches(VarService svc) {
        this.svc = svc;
    }

    @Override
    public List<CacheReport> reports() {
        List<CacheReport> out = new ArrayList<>();
        if (!svc.props().enabled()) {
            return out;
        }
        out.add(CacheReport.of(ANSWERS, "var", "VaR answers per date, version, view and scope", "heap", svc.answers(), null,
                (long) VarService.answersLimit(), null, "data-version", true));
        List<Map<String, Object>> loaded = svc.loaded();
        long heap = 0;
        long mapped = 0;
        for (Map<String, Object> m : loaded) {
            long agg = num(m.get("aggregateBytes"));
            long vec = num(m.get("vectorBytes"));
            heap += agg;
            mapped += "heap".equals(m.get("tier")) ? 0 : vec;
            heap += "heap".equals(m.get("tier")) ? vec : 0;
        }
        out.add(new CacheReport(DATES, "var", "Open business dates: the skeleton and aggregates on the heap, the rows mapped", "heap", null, heap,
                svc.props().memoryBudgetMb() * (1L << 20), (long) loaded.size(), (long) svc.props().hotDates(), null, null, null, null,
                "data-version (watched every drishti.var.version-check)", true, Map.of("mappedBytes", mapped)));
        VarTier tier = svc.loader().tier();
        Map<String, Object> t = tier.describe();
        out.add(new CacheReport(HOT, "var", "VaR hot files (dates, listed currencies, shard books)", "disk", tier.root().toString(), tier.diskBytes(),
                tier.maxDiskBytes(), num(t.get("files")), (long) tier.settings().diskDates(), null, null, null, null, "data-version (inputs digest)",
                true, Map.of("open", t.get("open"), "built", t.get("built"), "reopened", t.get("reopened"))));
        out.add(CacheReport.of(VIEWS, "var", "Viewers' views per date, version, currency and role set", "heap", svc.access().cache(),
                (long) svc.props().viewCacheMb() << 20, null, 3600L, "data-version", true));
        VarShards sh = svc.shards();
        if (sh != null && sh.on()) {
            List<VarShardBooks.Key> keys = sh.books().keys();
            out.add(new CacheReport(SHARDS, "var", "Shard books held by this member", "mapped", tier.root().toString(), null, null,
                    (long) keys.size(), null, null, null, null, null, "data-version (shard digest)", true, Map.of()));
        }
        return out;
    }

    @Override
    public List<String> purge(String name, LocalDate date) {
        List<String> done = new ArrayList<>();
        boolean all = "all".equals(name) || "var".equals(name);
        if (all || ANSWERS.equals(name)) {
            if (date == null) {
                svc.answers().invalidateAll();
            } else {
                svc.answers().asMap().keySet().removeIf(k -> k.startsWith(date + "|"));
            }
            done.add(ANSWERS);
        }
        if (all || VIEWS.equals(name)) {
            svc.access().forget(date);
            done.add(VIEWS);
        }
        if (all || DATES.equals(name) || HOT.equals(name)) {
            if (date == null) {
                svc.reload();
            } else {
                svc.reload(date);
            }
            done.add(DATES);
        }
        if (all || HOT.equals(name)) {
            VarTier tier = svc.loader().tier();
            if (date == null) {
                tier.dates().forEach(m -> tier.purgeDateLater(LocalDate.parse((String) m.get("date"))));
            } else {
                tier.purgeDateLater(date);
            }
            done.add(HOT);
        }
        VarShards sh = svc.shards();
        if (sh != null && (all || SHARDS.equals(name))) {
            for (VarShardBooks.Key k : sh.books().keys()) {
                if (date == null || date.equals(k.date())) {
                    sh.books().release(k, false);
                }
            }
            done.add(SHARDS);
        }
        return done;
    }

    @Override
    public List<Map<String, Object>> dates(String name) {
        if (ANSWERS.equals(name) || VIEWS.equals(name)) {
            Map<String, Integer> by = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
            var keys = ANSWERS.equals(name) ? svc.answers().asMap().keySet() : svc.access().cache().asMap().keySet();
            for (String k : keys) {
                String d = k.length() >= 10 ? k.substring(0, 10) : k;
                by.merge(d, 1, Integer::sum);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            by.forEach((d, n) -> out.add(Map.of("date", d, "state", "held", "entries", n)));
            return out;
        }
        Map<String, Map<String, Object>> open = new LinkedHashMap<>();
        for (Map<String, Object> m : svc.loaded()) {
            if (m.get("businessDate") != null && m.get("version") != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("date", m.get("businessDate"));
                r.put("state", "open");
                r.put("sizeBytes", num(m.get("aggregateBytes")) + num(m.get("vectorBytes")));
                r.put("versions", Map.of("version", String.valueOf(m.get("version"))));
                r.put("loadMs", m.get("loadMs"));
                r.put("tier", m.get("tier"));
                open.put((String) m.get("businessDate"), r);
            }
        }
        if (DATES.equals(name)) {
            return new ArrayList<>(open.values());
        }
        if (HOT.equals(name)) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> f : svc.loader().tier().dates()) {
                Map<String, Object> r = new LinkedHashMap<>(f);
                Map<String, Object> o = open.get((String) f.get("date"));
                if (o != null) {
                    r.put("state", "open");
                    r.put("versions", o.get("versions"));
                }
                out.add(r);
            }
            return out;
        }
        if (SHARDS.equals(name) && svc.shards() != null) {
            return svc.shards().books().describe();
        }
        return List.of();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
