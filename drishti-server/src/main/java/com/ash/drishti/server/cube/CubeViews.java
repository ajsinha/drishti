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
package com.ash.drishti.server.cube;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Saved cube views (MR_CUBE_LENS.md section 4.3), shared by both cubes, in the content store ({@code cube/views/<owner>/<name>.json}) so a
 * view follows its owner across a cluster and every version is kept. A view keeps its scope, dimensions, measures, filters, lens and sort,
 * never the numbers: it is private to its owner, or shared read-only with named people or roles ({@code role:risk}), and a reader opens it
 * with their own entitlements. Opened by id ({@code asha/morning}), by a link or by {@code MCUBE @asha/morning}.
 */
public final class CubeViews {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final String PREFIX = "cube/views/";
    private final ContentDocs docs;

    public CubeViews(ContentStore store) {
        this.docs = new ContentDocs(store);
    }

    static String slug(String name) {
        String s = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (!NAME.matcher(s).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a view's name is 1-64 letters, digits, dots, dashes or underscores");
        }
        return s;
    }

    private static String key(String owner, String name) {
        return PREFIX + ContentDocs.name(owner) + "/" + slug(name) + ".json";
    }

    /** Saves the caller's view under {@code name} (the same name is its next version): {@code {name, title, description, view, sharedWith}}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> save(Principal p, Map<String, Object> body, String cube) {
        String name = slug(String.valueOf(body.getOrDefault("name", "")));
        Object v = body.get("view");
        CubeView view = CubeView.of(v instanceof Map<?, ?> m ? (Map<String, Object>) m : body, cube);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", ContentDocs.name(p.user()) + "/" + name);
        b.put("name", name);
        b.put("title", String.valueOf(body.getOrDefault("title", name)));
        b.put("description", String.valueOf(body.getOrDefault("description", "")));
        b.put("cube", view.cube());
        b.put("view", view.toMap());
        b.put("owner", p.user());
        Object shared = body.get("sharedWith");
        b.put("sharedWith", shared instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
        b.put("updatedAt", Instant.now().toString());
        return docs.update(key(p.user(), name), Map.class, old -> {
            Map<String, Object> m = new LinkedHashMap<>(b);
            m.put("createdAt", old == null ? m.get("updatedAt") : old.get("createdAt"));
            m.put("version", old == null ? 1 : ((Number) old.getOrDefault("version", 0)).intValue() + 1);
            return m;
        });
    }

    /** A view by id ({@code owner/name}) the caller may read: theirs, or shared with them or one of their roles. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> get(Principal p, String id) {
        int slash = id == null ? -1 : id.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        String k;
        try {
            k = key(id.substring(0, slash), id.substring(slash + 1));
        } catch (DrishtiException e) {
            return Optional.empty();
        }
        return docs.value(k, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m));
    }

    /** The view of a saved id as a {@link CubeView}, refused in words when it is not there or not shared with the caller. */
    @SuppressWarnings("unchecked")
    public CubeView open(Principal p, String id) {
        Map<String, Object> m = get(p, id).orElseThrow(() -> new DrishtiException(ErrorCode.CUBE_VIEW_NOT_FOUND, "no saved cube view " + id
                + " that you may open (a view is its owner's, or shared with you or your role)"));
        return CubeView.of((Map<String, Object>) m.get("view"), String.valueOf(m.get("cube")));
    }

    private static boolean readable(Principal p, Map<String, Object> m) {
        if (p.user().equals(m.get("owner"))) {
            return true;
        }
        if (m.get("sharedWith") instanceof List<?> l) {
            for (Object o : l) {
                String who = String.valueOf(o);
                if (who.equals(p.user()) || p.roles() != null && p.roles().contains(who.replaceFirst("^role:", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The caller's views and those shared with them (without their bodies' history). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(Principal p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : docs.store().list(PREFIX)) {
            docs.value(k, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m)).ifPresent(out::add);
        }
        out.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        return out;
    }

    /** Deletes the caller's own view; false when there was none. People it was shared with lose it. */
    public boolean delete(Principal p, String name) {
        return docs.store().deleteLatest(key(p.user(), name));
    }
}
