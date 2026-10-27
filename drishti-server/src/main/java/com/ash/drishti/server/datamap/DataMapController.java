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
package com.ash.drishti.server.datamap;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data map, {@code DATAMAP <GO>} (docs/guides/USER_GUIDE.md "The data map"): the caller's packs, their kinds with how many entities each
 * holds on the business date, and the joins the data contracts declare with how well the data honours them. Only kinds the caller may
 * open now ({@link Entitlements#mayOpen}: role, pack switched on and active, command on); a join is shown when the caller may open both
 * ends. Answers at once with what is known ({@code state}: {@code counting}, {@code checking}, {@code ready}); ask again for the rest.
 */
@RestController
@RequestMapping("/api/v1/datamap")
public class DataMapController {

    private final DataMapService service;
    private final Entitlements entitlements;

    public DataMapController(DataMapService service, Entitlements entitlements) {
        this.service = service;
        this.entitlements = entitlements;
    }

    /**
     * {@code {businessDate, state, built, packs[{name, code, title, description, extends}], kinds[{kind, pack, code, label, title,
     * description, grain, class, computed, count, more, example, failed}], joins[{from, field, to, declaredBy, state, why, rows, filled,
     * values, matched, coverage, partial}]}}; {@code coverage} is matched / values (null when no id was read).
     */
    @GetMapping
    public Map<String, Object> map(AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        DataMap.Result r = service.current(asOf);
        Set<String> visible = new HashSet<>();
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (DataMap.Kind k : r.structure.kinds()) {
            if (!entitlements.mayOpen(p, k.kind())) {
                continue;
            }
            visible.add(k.kind());
            DataMap.Count c = r.counts.get(k.kind());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k.kind());
            m.put("pack", k.pack());
            m.put("code", k.code());
            m.put("label", k.label());
            m.put("title", k.title());
            m.put("description", k.description());
            m.put("grain", k.grain());
            m.put("class", k.dataClass());
            m.put("computed", k.computed());
            m.put("count", c == null ? null : c.count());
            m.put("more", c != null && c.more());
            m.put("example", c == null ? null : c.example());
            m.put("failed", c == null ? null : c.failed());
            kinds.add(m);
        }
        List<Map<String, Object>> packs = new ArrayList<>();
        for (Pack pk : r.structure.packs()) {
            if (kinds.stream().anyMatch(k -> pk.name().equals(k.get("pack")))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", pk.name());
                m.put("code", pk.code());
                m.put("title", pk.title());
                m.put("description", pk.description());
                m.put("extends", pk.parents());
                packs.add(m);
            }
        }
        List<Map<String, Object>> joins = new ArrayList<>();
        for (DataMap.Join j : r.structure.joins()) {
            if (!visible.contains(j.from()) || !visible.contains(j.to())) {
                continue;
            }
            DataMap.Coverage c = r.coverage.get(j.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", j.from());
            m.put("field", j.field());
            m.put("to", j.to());
            m.put("declaredBy", j.declaredBy());
            m.put("state", c.state());
            m.put("why", c.why());
            m.put("rows", c.rows());
            m.put("filled", c.filled());
            m.put("values", c.values());
            m.put("matched", c.matched());
            m.put("coverage", "checked".equals(c.state()) && c.values() > 0 ? (double) c.matched() / c.values() : null);
            m.put("partial", c.partial());
            joins.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", r.date);
        out.put("state", r.state);
        out.put("built", r.built == null ? null : r.built.toString());
        out.put("packs", packs);
        out.put("kinds", kinds);
        out.put("joins", joins);
        return out;
    }
}
