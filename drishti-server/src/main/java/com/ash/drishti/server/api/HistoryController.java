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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.history.DocumentDiff;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * History (W16): what changed in an entity between two points in time. Each side is a business date (or live) and,
 * optionally, a "known at" instant, so both questions work: how did the trade change from Monday to Tuesday, and
 * what did we know about Tuesday then versus now (a restatement). Both versions are fetched in parallel through the
 * normal routing and redacted for the caller before they are compared.
 */
@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    /**
     * @param businessDate the business date of this side
     * @param knownAt the "known at" instant, or null for the latest knowledge
     * @param provenance where this version came from
     */
    public record Side(LocalDate businessDate, String knownAt, Provenance provenance) {}

    /**
     * @param path readable path of the leaf
     * @param label the field's label (Sutra-independent: pack taxonomy, global taxonomy, else humanized)
     */
    public record Change(String path, String label, String kind, Object before, Object after, Double delta) {}

    public record Diff(ViewModelRef ref, Side from, Side to, List<Change> changes, int added, int removed, int changed, boolean truncated) {}

    public record ViewModelRef(String kind, String id) {}

    private static final int LIMIT = 2000;
    private final SourceRouter router;
    private final BusinessDates dates;
    private final Entitlements entitlements;
    private final DocumentDiff differ = new DocumentDiff(LIMIT);

    public HistoryController(SourceRouter router, BusinessDates dates, Entitlements entitlements) {
        this.router = router;
        this.dates = dates;
        this.entitlements = entitlements;
    }

    /**
     * Changes from {@code from} to {@code to}. {@code to} defaults to the request's as-of (the date box), {@code from}
     * to the business day before {@code to}. Either side may add a "known at" instant (ISO-8601).
     */
    @GetMapping("/{kind}/{id}/diff")
    public Diff diff(@PathVariable String kind, @PathVariable String id, AsOf current,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String fromKnownAt, @RequestParam(required = false) String toKnownAt,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        EntityRef ref = EntityRef.of(kind, id);
        AsOf later = to == null && toKnownAt == null ? current : dates.parse(to == null ? String.valueOf(current.businessDate()) : to, toKnownAt);
        AsOf earlier = dates.parse(from == null ? String.valueOf(dates.calendar().previous(later.businessDate())) : from, fromKnownAt);
        CompletableFuture<EntityDocument> a = router.fetch(ref, earlier);
        CompletableFuture<EntityDocument> b = router.fetch(ref, later);
        EntityDocument before = join(a);
        EntityDocument after = join(b);
        DocumentDiff.Result r = differ.diff(entitlements.redact(principal, before.data()), entitlements.redact(principal, after.data()));
        List<Change> changes = r.changes().stream().map(c -> new Change(c.path(), label(c.path()), c.kind().name().toLowerCase(java.util.Locale.ROOT),
                c.before(), c.after(), c.delta())).toList();
        return new Diff(new ViewModelRef(kind, id), side(earlier, before), side(later, after), changes, r.added(), r.removed(), r.changed(), r.truncated());
    }

    private static Side side(AsOf asOf, EntityDocument d) {
        return new Side(asOf.businessDate(), asOf.knownAt() == null ? null : asOf.knownAt().toString(), d.provenance());
    }

    /** The label of the leaf's own field: {@code legs[FIXED].notional} is labelled as {@code notional} would be. */
    static String label(String path) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        int bracket = last.indexOf('[');
        String field = bracket >= 0 ? last.substring(0, bracket) : last;
        String label = Semantics.humanize(field.isEmpty() ? path : field);
        return bracket >= 0 && last.endsWith("]") ? label + " " + last.substring(bracket) : label;
    }

    private static EntityDocument join(CompletableFuture<EntityDocument> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof DrishtiException de ? de : new DrishtiException(ErrorCode.SOURCE_FAILED, e.getMessage());
        }
    }
}
