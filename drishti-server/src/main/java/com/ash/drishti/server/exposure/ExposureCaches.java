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

import com.ash.drishti.server.caches.CacheReport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exposure engine's caches as Admin, Caches shows them (2.9.7, O1, O2): answers, trade paths, open dates, hot files and views; each
 * purged whole or for one business date.
 */
public final class ExposureCaches implements CacheReport.Owner {

    static final String ANSWERS = "exposure.answers";
    static final String PATHS = "exposure.trade-paths";
    static final String DATES = "exposure.dates";
    static final String HOT = "exposure.hot-files";
    static final String VIEWS = "exposure.views";

    private final ExposureService svc;

    public ExposureCaches(ExposureService svc) {
        this.svc = svc;
    }

    @Override
    public List<CacheReport> reports() {
        List<CacheReport> out = new ArrayList<>();
        if (!svc.props().enabled()) {
            return out;
        }
        out.add(CacheReport.of(ANSWERS, "exposure", "Exposure answers per scope, date, run and parameters", "heap", svc.answers(), null,
                (long) ExposureService.ANSWERS, null, "data-version", true));
        out.add(CacheReport.of(PATHS, "exposure", "Trades' paths read from the lake, per date and set of trades", "heap", svc.pathsCache(),
                256L << 20, null, null, "data-version", true));
        List<Map<String, Object>> loaded = svc.loaded();
        long bytes = loaded.stream().mapToLong(m -> m.get("matrixBytes") instanceof Number n ? n.longValue() : 0).sum();
        out.add(new CacheReport(DATES, "exposure", "Open business dates (netting-set matrices mapped or on the heap)", "mapped", null, bytes,
                (long) (svc.props().memoryBudgetGb() * (1L << 30)), (long) svc.openDates().size(), (long) svc.props().hotDates(), null, null,
                null, null, "data-version (watched every drishti.ccr.exposure.version-check)", true, Map.of()));
        ExposureTier tier = svc.loader().tier();
        Map<String, Object> t = tier.describe();
        out.add(new CacheReport(HOT, "exposure", "Exposure hot files", "disk", tier.root().toString(), t.get("diskBytes") instanceof Number n
                ? n.longValue() : null, tier.maxDiskBytes(), t.get("files") instanceof Number f ? f.longValue() : null,
                (long) svc.props().diskDates(), null, null, null, null, "data-version (inputs digest)", true, Map.of("open", t.get("open"))));
        out.add(CacheReport.of(VIEWS, "exposure", "Viewers' views per date, version and role set", "heap", svc.access().cache(),
                (long) svc.props().viewCacheMb() << 20, null, 3600L, "data-version", true));
        return out;
    }

    @Override
    public List<String> purge(String name, LocalDate date) {
        List<String> done = new ArrayList<>();
        boolean all = "all".equals(name) || "exposure".equals(name);
        if (all || ANSWERS.equals(name) || PATHS.equals(name) || VIEWS.equals(name) || DATES.equals(name) || HOT.equals(name)) {
            if (date == null) {
                svc.reload();
            } else {
                svc.reload(date);                                       // the date, its answers, paths and views
            }
            done.add(all ? "exposure" : name);
        }
        if (all || HOT.equals(name)) {
            ExposureTier tier = svc.loader().tier();
            if (date == null) {
                tier.dates().forEach(m -> tier.purgeDateLater(LocalDate.parse((String) m.get("date"))));
            } else {
                tier.purgeDateLater(date);
            }
            if (all) {
                done.add(HOT);
            }
        }
        return done;
    }

    @Override
    public List<Map<String, Object>> dates(String name) {
        Map<String, Map<String, Object>> open = new LinkedHashMap<>();
        for (Map<String, Object> m : svc.loaded()) {
            if (m.get("version") != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("date", m.get("businessDate"));
                r.put("state", "open");
                r.put("sizeBytes", m.get("matrixBytes"));
                r.put("versions", Map.of("version", String.valueOf(m.get("version")), "run", String.valueOf(m.get("runId"))));
                r.put("loadMs", m.get("loadMs"));
                open.put((String) m.get("businessDate"), r);
            }
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
        return new ArrayList<>(open.values());
    }
}
