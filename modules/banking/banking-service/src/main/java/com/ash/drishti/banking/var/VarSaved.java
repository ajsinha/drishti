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
package com.ash.drishti.banking.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What people keep of the VaR engine, in the content store (HOW_IT_FITS.md), so it follows its owner across the servers of a
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
    /** The system baskets: the same basket store, under the system scope (VAR_GUIDE.md section 5.7). */
    private final VarSystemBaskets system;
    /** Who may read the official end-of-day captures: a viewer who sees every node at positions level. */
    private final java.util.function.Predicate<Principal> seesAll;

    VarSaved(ContentStore store, java.util.function.Predicate<Principal> seesAll) {
        this.docs = new ContentDocs(store);
        this.system = new VarSystemBaskets(store);
        this.seesAll = seesAll;
    }

    /** The system baskets, beside people's baskets in the same store. */
    VarSystemBaskets system() {
        return system;
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

    Map<String, Object> saveBasket(Principal p, Map<String, Object> body) {
        return saveBasket(p.user(), body, null);
    }

    /**
     * Saves a basket under {@code ownerUser} (the caller, or the owner an administrator edits for): from its {@code scope} text, or from
     * its {@code sheet} (the sheet's lists, kept as they are: the scope is composed from them when read, VAR_GUIDE.md section 3.5). The
     * same name keeps the basket and is its next version; {@code expected}, when given, is the version the editor opened: a basket changed
     * since answers {@code 409}.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> saveBasket(String ownerUser, Map<String, Object> body, Integer expected) {
        String name = slug(String.valueOf(body.getOrDefault("name", "")));
        Map<String, Object> sheet = null;
        String scope;
        if (body.get("sheet") != null) {
            VarBasketSheet s = VarBasketSheet.read(body.get("sheet"), words.get());
            scope = s.scope();
            if (scope.isEmpty()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "a basket adds at least one part, or starts from the whole bank");
            }
            sheet = s.toMap();
        } else {
            scope = String.valueOf(body.getOrDefault("scope", "")).trim();
            if (scope.isEmpty()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "a basket has a scope (its members or its rule), or a sheet");
            }
        }
        try {
            com.ash.drishti.banking.compute.var.ScopeExpr.parse(scope, words.get());   // refused here when it does not parse
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        String type = sheet == null && scope.startsWith("WHERE:") && !scope.contains(" + ") ? "RULE" : "BASKET";
        String key = "var/baskets/" + owner(ownerUser) + "/" + name + ".json";
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", owner(ownerUser) + "/" + name);
        b.put("name", body.getOrDefault("title", name));
        b.put("description", String.valueOf(body.getOrDefault("description", "")));
        b.put("type", type);
        if (sheet != null) {
            b.put("sheet", sheet);
            b.put("parts", VarBasketSheet.read(sheet, words.get()).parts());
        } else {
            b.put("scope", scope);
        }
        b.put("owner", ownerUser);
        Object shared = body.get("sharedWith");
        b.put("sharedWith", shared instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
        b.put("updatedAt", Instant.now().toString());
        if (body.get("editedBy") != null) {
            b.put("editedBy", String.valueOf(body.get("editedBy")));
        }
        Map<String, Object> saved = docs.update(key, Map.class, old -> {
            if (expected != null && (old == null ? 0 : ((Number) old.getOrDefault("version", 0)).intValue()) != expected) {
                throw new DrishtiException(ErrorCode.STALE_REVISION, "the basket " + b.get("id") + " changed since you opened it (now version "
                        + (old == null ? "none: it was deleted" : old.get("version")) + "): open it again before saving");
            }
            Map<String, Object> m = new LinkedHashMap<>(b);
            m.put("createdAt", old == null ? m.get("updatedAt") : old.get("createdAt"));
            m.put("version", old == null ? 1 : ((Number) old.getOrDefault("version", 0)).intValue() + 1);
            return m;
        });
        return withScope(saved);
    }

    /** A basket kept from a sheet answers with its scope too, composed from its lists (never stored as one long text). */
    Map<String, Object> withScope(Map<String, Object> m) {
        if (m == null || m.get("scope") != null || m.get("sheet") == null) {
            return m;
        }
        Map<String, Object> out = new LinkedHashMap<>(m);
        try {
            out.put("scope", VarBasketSheet.read(m.get("sheet"), words.get()).scope());
        } catch (DrishtiException e) {
            out.put("scope", "TRD:NONE");                                 // a sheet that no longer reads (a word unconfigured): nothing
        }
        return out;
    }

    /** A basket by owner and name whoever asks (an administrator's edit: the caller has checked the right). */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> rawBasket(String owner, String name) {
        return docs.value(key(owner, name), Map.class).map(m -> (Map<String, Object>) m);
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
        return b.filter(m -> readable(p, m)).map(this::withScope);
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
            docs.value(key, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m)).map(this::listed).ifPresent(out::add);
        }
        out.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        return out;
    }

    /** A basket as a list shows it: without its sheet's lists (opened one at a time), with its scope and how many parts it has. */
    private Map<String, Object> listed(Map<String, Object> m) {
        Map<String, Object> out = withScope(m);
        if (out.containsKey("sheet")) {
            out = new LinkedHashMap<>(out);
            out.remove("sheet");
            out.put("fromSheet", true);
        }
        return out;
    }

    private static String key(String owner, String name) {
        return "var/baskets/" + ContentDocs.name(owner) + "/" + slug(name) + ".json";
    }

    /**
     * Deletes the basket {@code owner/name} at the version read, whoever asks (the caller has checked the right: its owner, or an
     * administrator): the basket as it was, empty when there is none. {@code expected}, when given, is the basket's {@code version} the
     * asker saw: a basket changed since answers {@code 409} ({@link ErrorCode#STALE_REVISION}), as does one changed between the read and
     * the delete. Captures and official records that used it keep their own stored scope text and members; people it was shared with
     * lose it.
     */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> deleteBasket(String owner, String name, Integer expected) {
        String key = key(owner, name);
        Optional<ContentDocs.Doc<Map>> cur = docs.read(key, Map.class);
        if (cur.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> b = (Map<String, Object>) cur.get().value();
        if (expected != null && ((Number) b.getOrDefault("version", 0)).intValue() != expected) {
            throw new DrishtiException(ErrorCode.STALE_REVISION, "the basket " + b.get("id") + " changed since you looked (now version "
                    + b.get("version") + "): look again before deleting it");
        }
        try {
            return docs.store().delete(key, cur.get().version()) ? Optional.of(b) : Optional.empty();
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                throw new DrishtiException(ErrorCode.STALE_REVISION, "the basket " + b.get("id") + " changed while it was being deleted: look again");
            }
            throw e;
        }
    }

    /** Whether the basket {@code owner/name} exists, whoever may read it (a capture says when the basket it was taken of is gone). */
    boolean basketExists(String id) {
        int slash = id.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        try {
            return docs.store().exists(key(id.substring(0, slash), id.substring(slash + 1)));
        } catch (DrishtiException e) {
            return false;                                                    // a name no basket can have
        }
    }

    /**
     * Every person's baskets, for an administrator (the caller has checked): owner, name, scope, sharing, dates and the stored size in
     * bytes; {@code owner} (optional) keeps the baskets whose owner contains it (any case).
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> allBaskets(String owner) {
        String want = owner == null ? "" : owner.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : docs.store().list("var/baskets/")) {
            docs.store().get(key).ifPresent(item -> {
                Map<String, Object> m = withScope(ContentDocs.decode(item.bytes(), Map.class));
                if (!String.valueOf(m.get("owner")).toLowerCase(Locale.ROOT).contains(want)) {
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (String f : List.of("id", "owner", "name", "description", "type", "scope", "parts", "sharedWith", "createdAt", "updatedAt", "version")) {
                    row.put(f, m.get(f));
                }
                row.put("bytes", item.bytes().length);
                out.add(row);
            });
        }
        out.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        return out;
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
                        for (String f : List.of("id", "scope", "canonical", "label", "businessDate", "capturedAt", "owner", "note", "var", "es", "horizon",
                                "method", "confidence", "lens")) {
                            s.put(f, m.get(f));
                        }
                        out.add(s);
                    });
        }
        out.sort((a, b) -> String.valueOf(b.get("capturedAt")).compareTo(String.valueOf(a.get("capturedAt"))));
        return out;
    }
}
