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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.kernel.api.services.LivePage;
import com.ash.drishti.kernel.api.services.LiveQueries;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.server.live.FormResults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link LiveQueries} in process: the body of {@code GET /api/v1/rdp/searches} (a search as a query on a live table, {@link FormResults}),
 * its first page as rows {@code {id, doc}} (the row by column name).
 */
@Component
public class KernelLiveQueries implements LiveQueries {

    private final FormResults forms;
    private final KernelDoors doors;

    public KernelLiveQueries(FormResults forms, KernelDoors doors) {
        this.forms = forms;
        this.doors = doors;
    }

    @Override
    public LivePage query(Person person, String query, int limit) {
        RequestPerson p = RequestPerson.of(person);
        return page(doors.call(p, "GET", "/api/v1/rdp/searches", () -> forms.answer(query, p.asOf(), p.principal())), limit);
    }

    /**
     * What {@code GET /api/v1/rdp/searches} answered ({@code {table, query, live, page, search}}) as the typed answer: its first {@code limit}
     * rows as {@code {id, doc}}. The one reading of that answer, in process and over REST alike.
     */
    @SuppressWarnings("unchecked")
    public static LivePage page(Map<String, Object> a, int limit) {
        Map<String, Object> page = (Map<String, Object>) a.get("page");
        List<String> columns = ((List<Object>) page.getOrDefault("columns", List.of())).stream()
                .map(c -> c instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : String.valueOf(c)).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object v : (List<Object>) page.getOrDefault("values", List.of())) {
            if (rows.size() >= Math.max(0, limit)) {
                break;
            }
            List<Object> vals = (List<Object>) v;
            Map<String, Object> doc = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < vals.size(); i++) {
                doc.put(columns.get(i), vals.get(i));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", doc.get(com.ash.drishti.engine.search.SearchFilter.KEY));
            r.put("doc", doc);
            rows.add(r);
        }
        long total = page.get("total") instanceof Number n ? n.longValue() : rows.size();
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("id", a.get("table"));
        set.put("table", a.get("table"));
        set.put("query", a.get("query"));
        return new LivePage(set, Boolean.TRUE.equals(a.get("live")), (int) total, 0L, 0, rows, (Map<String, Object>) a.get("search"), List.of());
    }
}
