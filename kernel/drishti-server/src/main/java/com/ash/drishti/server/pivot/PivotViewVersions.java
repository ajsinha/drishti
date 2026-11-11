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
package com.ash.drishti.server.pivot;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versions of a saved pivot view (ARCHITECTURE.md section 26.7, item 10): every save is kept ({@code pivot/view-versions/<owner>/<name>/<n>.json},
 * written by {@link PivotViews#save}); a reader of the view lists them, reads one, sees what changed between two (the definition, key by
 * key: rows, columns, values, filters, open nodes, sorts, top N, date) and the owner restores an old one as the next version. Definitions
 * only: a view never keeps numbers.
 */
public final class PivotViewVersions {

    private static final String PREFIX = "pivot/view-versions/";
    private final PivotViews views;

    public PivotViewVersions(PivotViews views) {
        this.views = views;
    }

    static String key(String owner, String name, int version) {
        return PREFIX + ContentDocs.name(owner) + "/" + PivotViews.slug(name) + "/" + String.format(java.util.Locale.ROOT, "%06d", version) + ".json";
    }

    private Map<String, Object> current(Principal p, String id) {
        return views.get(p, id).orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no saved pivot view " + id
                + " that you may open"));
    }

    /** Every version of a view the caller may open: number, when, by whom, title; newest first. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(Principal p, String id) {
        Map<String, Object> cur = current(p, id);
        String owner = String.valueOf(cur.get("owner"));
        String name = String.valueOf(cur.get("name"));
        List<Map<String, Object>> out = new ArrayList<>();
        ContentDocs docs = views.docs();
        for (String k : docs.store().list(PREFIX + ContentDocs.name(owner) + "/" + PivotViews.slug(name) + "/")) {
            if (!k.matches(".*/[0-9]{6}\\.json")) {
                continue;                                     // the view's cell comments are kept beside its versions
            }
            docs.value(k, Map.class).map(m -> (Map<String, Object>) m).ifPresent(m -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("version", m.get("version"));
                e.put("updatedAt", m.get("updatedAt"));
                e.put("title", m.get("title"));
                out.add(e);
            });
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> ((Number) m.get("version")).intValue()).reversed());
        return out;
    }

    /** One version of a view the caller may open. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> version(Principal p, String id, int version) {
        Map<String, Object> cur = current(p, id);
        return views.docs().value(key(String.valueOf(cur.get("owner")), String.valueOf(cur.get("name")), version), Map.class)
                .map(m -> (Map<String, Object>) m).orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, id + " has no version "
                        + version));
    }

    /** What changed in the definition from version {@code from} to {@code to}: per key, both values, and the words. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> diff(Principal p, String id, int from, int to) {
        Map<String, Object> a = (Map<String, Object>) version(p, id, from).get("view");
        Map<String, Object> b = (Map<String, Object>) version(p, id, to).get("view");
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        List<Map<String, Object>> changes = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (String k : keys) {
            Object x = a.get(k);
            Object y = b.get(k);
            if (!Objects.equals(x, y)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", k);
                c.put("from", x);
                c.put("to", y);
                changes.add(c);
                words.add(k + ": " + (x == null ? "none" : x) + " → " + (y == null ? "none" : y));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("from", from);
        out.put("to", to);
        out.put("changes", changes);
        out.put("words", changes.isEmpty() ? "the two versions define the same view" : String.join("; ", words));
        return out;
    }

    /** The owner's old version saved again as the next version (nothing is lost: the versions between stay). */
    public Map<String, Object> restore(Principal p, String name, int version) {
        String id = ContentDocs.name(p.user()) + "/" + PivotViews.slug(name);
        Map<String, Object> old = version(p, id, version);
        if (!p.user().equals(old.get("owner"))) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "only the owner restores a version of " + id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("title", old.get("title"));
        body.put("description", old.get("description"));
        body.put("view", old.get("view"));
        body.put("sharedWith", old.get("sharedWith"));
        Map<String, Object> saved = views.save(p, body);
        Map<String, Object> out = new LinkedHashMap<>(saved);
        out.put("restoredFrom", version);
        return out;
    }
}
