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
package com.ash.drishti.server.liveset;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.security.Entitlements;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Pages of live sets as a person may see them: the body of {@code GET /api/v1/live/query} and the page every live read answers, called by
 * {@link LiveSetController} and by the kernel's typed {@code LiveQueries.query} alike (KERNEL_AND_MODULES.md section 2.3).
 */
@Component
public class LivePages {

    private static final int MAX_PAGE = 5_000;

    private final LiveSets sets;
    private final Entitlements entitlements;
    private com.ash.drishti.server.edition.EditionCommands edition = com.ash.drishti.server.edition.EditionCommands.NONE;

    public LivePages(LiveSets sets, Entitlements entitlements) {
        this.sets = sets;
        this.entitlements = entitlements;
    }

    /** The edition's command allow-list (a kind it leaves out is refused as by the search). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEdition(com.ash.drishti.server.edition.EditionCommands edition) {
        this.edition = edition;
    }

    /**
     * A search's matches as a live result set, a page of it as the person may see it: the body of {@code GET /api/v1/live/query}
     * ({@code again}: a static set read afresh).
     */
    public Map<String, Object> query(String q, int offset, int limit, String sort, boolean descending, String filter, boolean again,
            com.ash.drishti.api.AsOf asOf, Principal principal) {
        String kind = sets.kindOf(q);
        entitlements.requireOpen(principal, kind);
        edition.requireKind(kind);
        LiveReader reader = sets.reader(principal, kind);
        LiveSet set = sets.query(q, asOf, principal, reader, again);
        Map<String, Object> out = page(set, set.spec(), offset, limit, sort, descending, filter, principal);
        out.put("search", origin(set, reader));
        out.put("asOfNotices", asOfNotices(kind, asOf == null || asOf.live() ? null : asOf.businessDate()));
        return out;
    }

    /** What the search that filled the set said, as the reader may see it: which node answered what for administrators only. */
    public static Map<String, Object> origin(LiveSet set, LiveReader reader) {
        Map<String, Object> o = set.origin();
        if (reader.admin() || !o.containsKey("nodes")) {
            return o;
        }
        Map<String, Object> out = new LinkedHashMap<>(o);
        out.remove("nodes");
        return out;
    }

    private com.ash.drishti.engine.source.SourceRouter router;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void router(com.ash.drishti.engine.source.SourceRouter r) {
        this.router = r;
    }

    /**
     * The sources of the kind (each trading system's connector) that answer the date (null: the latest) from an earlier one, in words: the
     * records page shows them per source system, so a date with no load is never shown silently with an older date's rows.
     */
    public List<Map<String, Object>> asOfNotices(String kind, java.time.LocalDate date) {
        try {
            return router == null ? List.of() : com.ash.drishti.server.pivot.KindSources.asOfNotices(router.carried(kind, date));
        } catch (RuntimeException e) {
            return List.of();                                          // a source that cannot say never fails the page
        }
    }

    /** A page of a set's rows: {@code offset}, {@code limit} (at most 5,000), {@code sort}, {@code descending}, {@code filter}. */
    public Map<String, Object> page(LiveSet set, LiveSetSpec spec, int offset, int limit, String sort, boolean descending, String filter,
            Principal principal) {
        LiveSet.Page page = set.page(offset, Math.min(MAX_PAGE, Math.max(1, limit)), sort, descending, filter, sets.reader(principal, spec.kind()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set", described(spec, principal));
        out.put("live", set.live());
        out.put("total", page.total());
        out.put("seq", page.seq());
        out.put("offset", offset);
        out.put("rows", page.rows());
        return out;
    }

    /** The declaration as this person sees it: editable only when the site and their rights allow it, and why not. */
    public Map<String, Object> described(LiveSetSpec spec, Principal principal) {
        Map<String, Object> m = spec.describe();
        java.util.Set<String> off = sets.excluded(spec.kind());
        if (!off.isEmpty()) {
            // a page of the kind says which source systems it leaves out: "excludes ENDUR (switched off)"
            m.put("excludes", off);
            m.put("banner", "excludes " + String.join(", ", off) + " (switched off)");
        }
        List<String> paused = sets.livePaused(spec.kind());
        if (!paused.isEmpty()) {
            // a stream whose broker is not there is calm: the page serves the lake snapshot and says its live updates are paused
            m.put("livePaused", paused);
            m.putIfAbsent("banner", "live updates paused: " + String.join("; ", paused));
        }
        String no = sets.readOnly(principal, spec);
        m.put("editable", no == null);
        m.put("readOnly", no);
        if (no != null) {
            m.remove("fields");
        }
        return m;
    }
}
