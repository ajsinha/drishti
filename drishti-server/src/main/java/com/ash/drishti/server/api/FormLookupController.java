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
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceFailures;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live search behind a form's entity field (RACHANA_REFERENCE.md 10): {@code GET /api/v1/forms/lookup?kind=&pack=&q=&limit=} finds the
 * records of a kind whose id or label holds the text, read as the caller. The connectors' search answers first; a kind whose sources
 * cannot search is listed (at most {@link #SCAN}) and matched on what the caller sees. A masked value or label is never matched or
 * offered (a masked label shows the id); a kind the caller may not open is refused; the limit is capped at {@link #MAX} and every read
 * has a short budget ({@link #BUDGET}). {@link #exists} is the drafts' check that an entity id names a record.
 */
@RestController
public class FormLookupController {

    /** The most records one lookup answers. */
    public static final int MAX = 50;
    /** The most records of a kind listed when its sources cannot search. */
    public static final int SCAN = 500;
    /** How long a lookup or an existence check may take. */
    public static final Duration BUDGET = Duration.ofSeconds(2);
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$");

    private final SourceRouter router;
    private final Entitlements entitlements;

    public FormLookupController(SourceRouter router, Entitlements entitlements) {
        this.router = router;
        this.entitlements = entitlements;
    }

    /** One record found: the id kept (the record's id, or its {@code value} field) and the label shown. */
    public record Found(String id, String label) {}

    @GetMapping("/api/v1/forms/lookup")
    public Map<String, Object> lookup(@RequestParam String kind, @RequestParam(required = false) String pack,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String label, @RequestParam(required = false) String value,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!NAME.matcher(kind).matches() || pack != null && !pack.isBlank() && !NAME.matcher(pack).matches()
                || label != null && !FIELD.matcher(label).matches() || value != null && !FIELD.matcher(value).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a lookup names a kind (legal-entity), optionally its pack, the label and value fields");
        }
        entitlements.requireOpen(p, kind);
        String text = q.strip();
        if (text.length() > 100) {
            text = text.substring(0, 100);
        }
        int n = Math.max(1, Math.min(MAX, limit));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("q", text);
        out.put("results", text.isEmpty() ? List.of() : find(p, kind, text, n, label == null ? "NAME" : label, value));
        return out;
    }

    /** The records of {@code kind} whose visible id or label holds {@code text}, at most {@code limit}. */
    List<Found> find(Principal p, String kind, String text, int limit, String label, String value) {
        String needle = text.toLowerCase(Locale.ROOT);
        Set<EntityRef> refs = new LinkedHashSet<>();
        try {
            router.search(kind, text, limit * 4, BUDGET, AsOf.LATEST).stream().map(EntityHit::ref).forEach(refs::add);
            if (refs.isEmpty()) {                                // sources that cannot search: list the kind and match what is seen
                router.listAcross(kind, SCAN, BUDGET, AsOf.LATEST, new SourceFailures()).hits().stream().map(EntityHit::ref).forEach(refs::add);
            }
        } catch (DrishtiException e) {
            return List.of();
        }
        Map<EntityRef, EntityDocument> docs = refs.isEmpty() ? Map.of() : router.fetchAll(refs, BUDGET, AsOf.LATEST);
        List<Found> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (EntityRef r : refs) {
            EntityDocument d = docs.get(r);
            if (d == null || out.size() >= limit) {
                continue;
            }
            DataNode visible = entitlements.redact(p, d.data());
            String id = r.id();
            if (value != null) {
                id = shown(visible.get(value));
                if (id == null) {
                    continue;                                    // a masked value is never offered: it would be typed back unseen
                }
            }
            String lb = shown(visible.get(label));
            boolean hit = id.toLowerCase(Locale.ROOT).contains(needle) || lb != null && lb.toLowerCase(Locale.ROOT).contains(needle);
            if (hit && seen.add(id)) {
                out.add(new Found(id, lb == null ? id : lb));
            }
        }
        return out;
    }

    /**
     * Whether {@code id} names a record of {@code kind} the caller may open: one read, within {@link #BUDGET}. Null when it does;
     * else why not, in words.
     */
    public String exists(Principal p, String kind, String id) {
        if (!entitlements.mayOpen(p, kind)) {
            return "you may not open " + kind + " records";
        }
        try {
            EntityDocument d = router.fetch(EntityRef.of(kind, id), BUDGET).get(BUDGET.toMillis() + 500, TimeUnit.MILLISECONDS);
            return d == null ? "no " + kind.replace('-', ' ') + " '" + id + "' was found" : null;
        } catch (java.util.concurrent.ExecutionException | CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof DrishtiException de && de.errorCode() == ErrorCode.ENTITY_NOT_FOUND) {
                return "no " + kind.replace('-', ' ') + " '" + id + "' was found";
            }
            return "'" + id + "' could not be checked just now: try again in a moment";
        } catch (java.util.concurrent.TimeoutException e) {
            return "'" + id + "' could not be checked just now: try again in a moment";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "'" + id + "' could not be checked just now";
        } catch (DrishtiException e) {
            return e.errorCode() == ErrorCode.ENTITY_NOT_FOUND ? "no " + kind.replace('-', ' ') + " '" + id + "' was found" : "'" + id + "' could not be checked just now";
        }
    }

    private static String shown(DataNode n) {
        if (n == null || n.isMasked()) {
            return null;
        }
        Object v = Values.simplify(n);
        return v == null || Values.masked(v) ? null : Values.text(v);
    }
}
