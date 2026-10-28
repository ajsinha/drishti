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
 * What people keep of the VaR engine, in the content store (CONTENT_STORE.md), so it follows its owner across the servers of a
 * cluster and every version is kept:
 *
 * <ul>
 *   <li><b>Baskets</b> ({@code var/baskets/<owner>/<name>.json}): an artificial portfolio, saved from the explorer's <i>New scope</i>
 *       as a BASKET (its members) or a RULE (a condition); private to its owner, shared read-only with named people or roles. A
 *       reader sees it through their own entitlements. Read as {@code BASKET:<owner>/<name>}.</li>
 *   <li><b>Captures</b> ({@code var/captures/<owner>/<id>.json}): an immutable snapshot of a scope's VaR at one moment: the numbers,
 *       the full vector, the members, and how they were made (VAR_GUIDE.md section 6). Never changed once written.</li>
 * </ul>
 */
final class VarSaved {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private final ContentDocs docs;
    /** Who may read the official end-of-day captures: a viewer who sees every node at positions level. */
    private final java.util.function.Predicate<Principal> seesAll;

    VarSaved(ContentStore store, java.util.function.Predicate<Principal> seesAll) {
        this.docs = new ContentDocs(store);
        this.seesAll = seesAll;
    }

    static String slug(String name) {
        String s = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (!NAME.matcher(s).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a basket's name is 1-64 letters, digits, dots, dashes or underscores");
        }
        return s;
    }

    /** The configured hierarchies' scope words a basket may use (2.9.5). */
    private java.util.function.Supplier<java.util.Set<String>> words = java.util.Set::of;

    VarSaved words(java.util.function.Supplier<java.util.Set<String>> w) {
        this.words = w;
        return this;
    }

    private static String owner(String user) {
        return ContentDocs.name(user);
    }

    // ------------------------------------------------------------------------------------------------------------------- baskets

    @SuppressWarnings("unchecked")
    Map<String, Object> saveBasket(Principal p, Map<String, Object> body) {
        String name = slug(String.valueOf(body.getOrDefault("name", "")));
        String scope = String.valueOf(body.getOrDefault("scope", "")).trim();
        if (scope.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a basket has a scope (its members or its rule)");
        }
        com.ash.drishti.compute.var.ScopeExpr.parse(scope, words.get());  // refused here when it does not parse
        String type = scope.startsWith("WHERE:") && !scope.contains(" + ") ? "RULE" : "BASKET";
        String key = "var/baskets/" + owner(p.user()) + "/" + name + ".json";
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", owner(p.user()) + "/" + name);
        b.put("name", body.getOrDefault("title", name));
        b.put("description", String.valueOf(body.getOrDefault("description", "")));
        b.put("type", type);
        b.put("scope", scope);
        b.put("owner", p.user());
        Object shared = body.get("sharedWith");
        b.put("sharedWith", shared instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
        b.put("updatedAt", Instant.now().toString());
        Map<String, Object> saved = docs.update(key, Map.class, old -> {
            Map<String, Object> m = new LinkedHashMap<>(b);
            m.put("createdAt", old == null ? m.get("updatedAt") : old.get("createdAt"));
            m.put("version", old == null ? 1 : ((Number) old.getOrDefault("version", 0)).intValue() + 1);
            return m;
        });
        return saved;
    }

    /** A basket by id ({@code owner/name}) the caller may read: theirs, or shared with them or one of their roles. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> basket(Principal p, String id) {
        int slash = id.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        String key = "var/baskets/" + ContentDocs.name(id.substring(0, slash)) + "/" + id.substring(slash + 1).toLowerCase(Locale.ROOT) + ".json";
        Optional<Map<String, Object>> b = docs.value(key, Map.class).map(m -> (Map<String, Object>) m);
        return b.filter(m -> readable(p, m));
    }

    private boolean readable(Principal p, Map<String, Object> m) {
        if (Boolean.TRUE.equals(m.get("official"))) {
            return seesAll.test(p);
        }
        if (p.user().equals(m.get("owner"))) {
            return true;
        }
        Object s = m.get("sharedWith");
        if (s instanceof List<?> l) {
            for (Object o : l) {
                String who = String.valueOf(o);
                if (who.equals(p.user()) || p.roles() != null && p.roles().contains(who.replaceFirst("^role:", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The caller's baskets and those shared with them. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> baskets(Principal p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : docs.store().list("var/baskets/")) {
            docs.value(key, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m)).ifPresent(out::add);
        }
        out.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        return out;
    }

    boolean deleteBasket(Principal p, String name) {
        String key = "var/baskets/" + owner(p.user()) + "/" + slug(name) + ".json";
        return docs.store().get(key).map(item -> docs.store().delete(key, item.version())).orElse(false);
    }

    // ------------------------------------------------------------------------------------------------------------------- captures

    /** Writes a capture once; its id is {@code CAP-<yyyymmdd>-<n>}, the sequence the content store keeps. */
    String capture(Principal p, Map<String, Object> capture, String businessDate) {
        long n = ContentDocs.next(docs.store(), "var/captures-seq");
        String id = "CAP-" + businessDate.replace("-", "") + "-" + n;
        capture.put("id", id);
        String key = "var/captures/" + owner(p.user()) + "/" + id + ".json";
        if (!docs.create(key, capture)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a capture " + id + " exists already");
        }
        return id;
    }

    /** A capture by id the caller may read (their own, or shared). */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> capture(Principal p, String id) {
        for (String key : docs.store().list("var/captures/")) {
            if (key.endsWith("/" + id + ".json")) {
                return docs.value(key, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m));
            }
        }
        return Optional.empty();
    }

    /**
     * The captures people made that the caller may read, newest first, optionally of one scope (canonical text). The official
     * end-of-day captures are listed from their dates' records instead ({@link VarOfficialStore#captures}).
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> captures(Principal p, String canonical) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : docs.store().list("var/captures/")) {
            if (key.startsWith(VarOfficialStore.FOLDER)) {
                continue;
            }
            docs.value(key, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m))
                    .filter(m -> canonical == null || canonical.equals(m.get("canonical"))).ifPresent(m -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        for (String f : List.of("id", "scope", "canonical", "label", "businessDate", "capturedAt", "owner", "note", "var", "es")) {
                            s.put(f, m.get(f));
                        }
                        out.add(s);
                    });
        }
        out.sort((a, b) -> String.valueOf(b.get("capturedAt")).compareTo(String.valueOf(a.get("capturedAt"))));
        return out;
    }
}
