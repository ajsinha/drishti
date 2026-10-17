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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceFailures;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which Sutra lays out which data: for a kind of a pack, the distinct combinations in the data of the fields its Sutras match on
 * ({@code $.SOURCE_SYSTEM}, {@code $.PRODUCT_TYPE}) and show panels by ({@code when: $.PRODUCT_SUB_TYPE == …}), each with how many
 * entities have it, the Sutras whose {@code match} holds for one of them, the panels its {@code when}s show, and a state: {@code one}
 * (right), {@code none} (inferred: no Sutra lays it out) or {@code several} (more than one matches: priority decides, which is
 * probably not what the pack meant). Read from every connector serving the kind (a union's systems together). Folders of Sutras
 * mean nothing here: only each Sutra's own {@code match} does.
 */
@RestController
@RequestMapping("/api/v1/packs")
public class SutraCoverageController {

    private static final Pattern FIELD = Pattern.compile("\\$\\.([A-Za-z_][A-Za-z0-9_]*)\\b(?!\\s*\\[)(?!\\.)");
    private static final int MAX_ROWS = 2000;

    private final PackRegistry packs;
    private final PackAccess access;
    private final Entitlements entitlements;
    private final SourceRouter router;
    private final SutraRegistry sutras;
    private final SutraMatcher matcher;

    public SutraCoverageController(PackRegistry packs, PackAccess access, Entitlements entitlements, SourceRouter router, SutraRegistry sutras,
            SutraMatcher matcher) {
        this.packs = packs;
        this.access = access;
        this.entitlements = entitlements;
        this.router = router;
        this.sutras = sutras;
        this.matcher = matcher;
    }

    @GetMapping("/{name}/coverage")
    public Map<String, Object> coverage(@PathVariable String name, @RequestParam(required = false) String kind, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Pack pack = access.byCodeOrName(principal.user(), name).flatMap(n -> packs.packs().stream().filter(p -> p.name().equals(n)).findFirst())
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no pack '" + name + "' is switched on"));
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (String k : pack.kinds()) {
            if ((kind == null || kind.isBlank() || kind.equals(k)) && entitlements.mayOpen(principal, k) && !sutras.forKind(k).isEmpty()) {
                kinds.add(kind(k, asOf));
            }
        }
        return Map.of("pack", pack.name(), "kinds", kinds);
    }

    /** The fields a kind's Sutras match on and show panels by, in the order met. */
    static List<String> fields(List<Sutra> list) {
        Set<String> out = new LinkedHashSet<>();
        for (Sutra s : list) {
            add(s.match().where(), out);
        }
        for (Sutra s : list) {
            for (Panel p : s.panels()) {
                p.option(Panel.WHEN).ifPresent(w -> add(w, out));
                p.parts().forEach(q -> q.option(Panel.WHEN).ifPresent(w -> add(w, out)));
            }
        }
        return List.copyOf(out);
    }

    private static void add(String expr, Set<String> out) {
        if (expr != null) {
            Matcher m = FIELD.matcher(expr);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
    }

    private Map<String, Object> kind(String kind, AsOf asOf) {
        List<Sutra> list = sutras.forKind(kind);
        List<String> fields = fields(list);
        Map<List<String>, long[]> counts = new TreeMap<>((a, b) -> String.join("\u0000", a).compareTo(String.join("\u0000", b)));
        Map<List<String>, String> example = new LinkedHashMap<>();
        Duration budget = Duration.ofSeconds(20);
        SourceFailures failures = new SourceFailures();
        ColumnSet cols = fields.isEmpty() ? null : router.columns(kind, fields, asOf, budget, failures).orElse(null);
        if (cols != null) {
            for (int i = 0; i < cols.size(); i++) {
                List<String> key = new ArrayList<>();
                for (String f : fields) {
                    Object v = cols.value(f, i);
                    key.add(v == null ? "" : v instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(v));
                }
                counts.computeIfAbsent(key, x -> new long[1])[0]++;
                example.putIfAbsent(key, cols.ids()[i]);
            }
        } else {                                         // a source without the fields as columns: the documents it lists
            List<EntityRef> refs = router.list(kind, "", 5000, budget, asOf, failures).hits().stream().map(EntityHit::ref).toList();
            Map<EntityRef, EntityDocument> docs = router.fetchAll(refs, budget, asOf);
            for (EntityRef r : refs) {
                EntityDocument d = docs.get(r);
                if (d == null) {
                    continue;
                }
                List<String> key = fields.stream().map(f -> d.data().get(f).asText()).toList();
                counts.computeIfAbsent(key, x -> new long[1])[0]++;
                example.putIfAbsent(key, r.id());
            }
        }
        Map<EntityRef, EntityDocument> docs = router.fetchAll(example.values().stream().limit(MAX_ROWS).map(id -> EntityRef.of(kind, id)).toList(), budget, asOf);
        List<Map<String, Object>> rows = new ArrayList<>();
        int none = 0;
        int several = 0;
        for (Map.Entry<List<String>, long[]> e : counts.entrySet()) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                values.put(fields.get(i), e.getKey().get(i));
            }
            String id = example.get(e.getKey());
            EntityDocument d = docs.get(EntityRef.of(kind, id));
            DataNode data = d == null ? DataNode.missing() : d.data();
            List<Sutra> hits = d == null ? List.of() : matcher.matching(kind, data);
            String state = hits.isEmpty() ? "none" : hits.size() == 1 ? "one" : "several";
            none += hits.isEmpty() ? 1 : 0;
            several += hits.size() > 1 ? 1 : 0;
            row.put("values", values);
            row.put("count", e.getValue()[0]);
            row.put("example", id);
            row.put("sutras", hits.stream().map(Sutra::name).toList());
            row.put("shown", hits.isEmpty() ? List.of() : matcher.shownByWhen(hits.get(0), data));
            row.put("state", state);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("fields", fields);
        out.put("rows", rows);
        out.put("combinations", counts.size());
        out.put("none", none);
        out.put("several", several);
        out.put("failed", failures.asMap());
        return out;
    }
}
