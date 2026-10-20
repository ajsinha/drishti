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
import com.ash.drishti.engine.history.HistoryAnswer;
import com.ash.drishti.engine.history.HistoryService;
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
    private final com.ash.drishti.rachana.el.ElCompiler el;
    private final com.ash.drishti.rachana.format.Formats formats;

    private final HistoryService history;

    public HistoryController(SourceRouter router, BusinessDates dates, Entitlements entitlements, com.ash.drishti.rachana.el.ElCompiler el,
            com.ash.drishti.rachana.format.Formats formats, HistoryService history) {
        this.history = history;
        this.router = router;
        this.dates = dates;
        this.entitlements = entitlements;
        this.el = el;
        this.formats = formats;
    }

    /**
     * One point of a field's history.
     *
     * @param date the business date asked for
     * @param value the field's value that day (null when the entity or the field was not there)
     * @param dataDate the business date the data is for: earlier than {@code date} when a source carried an older day
     * @param source the connector that answered
     * @param generation the source's version of the document
     */
    public record Point(LocalDate date, Object value, LocalDate dataDate, String source, Long generation) {}

    /** A field over business days, oldest first; {@code dated} is false when no dated source answered (one value repeated). */
    public record Series(ViewModelRef ref, String path, String label, List<Point> points, boolean dated) {}

    /**
     * The entity over the business days it has data (HISTORY_QUERIES.md sections 7 and 8): one row per day, its top-level fields, what
     * changed from the day before, and for market kinds the headline numbers. The range is {@code days} (days with data), {@code range}
     * ({@code 30D}, {@code 3M}, {@code YTD}...) or {@code from} / {@code to}; it ends at the date box unless {@code to} says otherwise.
     * Every day is redacted for the caller before anything is computed from it.
     */
    @GetMapping("/{kind}/{id}")
    public HistoryAnswer rows(@PathVariable String kind, @PathVariable String id, AsOf current,
            @RequestParam(required = false) Integer days, @RequestParam(required = false) String range,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String fields, @RequestParam(defaultValue = "false") boolean diff,
            @RequestParam(required = false) String knownAt, @RequestParam(defaultValue = "asc") String order,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        refuseUnknownKind(kind, id);
        entitlements.requireOpen(principal, kind);
        java.time.Instant known = current.knownAt();
        if (knownAt != null && !knownAt.isBlank()) {
            try {
                known = java.time.Instant.parse(knownAt.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new DrishtiException(ErrorCode.HISTORY_BAD_RANGE, "knownAt '" + knownAt + "' is not an ISO-8601 instant (2026-10-19T18:00:00Z)");
            }
        }
        java.util.Set<String> wanted = fields == null || fields.isBlank() ? java.util.Set.of()
                : java.util.Arrays.stream(fields.split(",")).map(String::trim).filter(f -> !f.isEmpty())
                        .map(f -> f.startsWith("$.") ? f.substring(2) : f).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        HistoryService.Query q = new HistoryService.Query(kind, id, days, range, from, to, wanted, diff, known,
                "desc".equalsIgnoreCase(order), current.businessDate());
        // the signature keys the days read through a peer, which arrive masked there for this caller (SOAK2-01)
        return history.read(q, HistoryService.Viewer.of(d -> entitlements.redact(principal, d), f -> entitlements.masked(principal, f),
                principal.securitySignature()));
    }

    private com.ash.drishti.engine.command.Mnemonics mnemonics;
    private com.ash.drishti.server.security.PackAccess packs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void kinds(com.ash.drishti.engine.command.Mnemonics m, com.ash.drishti.server.security.PackAccess p) {
        this.mnemonics = m;
        this.packs = p;
    }

    /**
     * A mnemonic or a plural where the kind belongs (QA1-18): {@code TRD} says to use {@code trade}, {@code trades} says it is no kind,
     * instead of "no source holds TRD/1000001". A kind in another case ({@code Trade}) is no kind either, as everywhere in the API, and
     * says which it means; a kind no pack, source or mnemonic knows ({@code nokind}) names the kinds there are, unless a source that
     * serves any kind holds that very entity (QA1-208).
     */
    private void refuseUnknownKind(String kind, String id) {
        refuseMisnamedKind(kind);
        if (mnemonics == null || packs == null || kind == null || packs.ownerOf(kind) != null) {
            return;
        }
        java.util.Set<String> known = new java.util.TreeSet<>(packs.ownedKinds());
        known.addAll(router.declaredKinds());
        mnemonics.all().values().forEach(m -> known.add(m.kind()));
        if (known.isEmpty() || known.contains(kind)) {
            return;
        }
        for (String k : known) {
            if (k.equalsIgnoreCase(kind)) {
                throw new DrishtiException(ErrorCode.BAD_SEARCH, "unknown kind " + kind + ": kinds are written in lower case, did you mean " + k + "?");
            }
        }
        if (id != null && !router.fetchAll(java.util.List.of(com.ash.drishti.api.EntityRef.of(kind, id)), java.time.Duration.ofSeconds(2)).isEmpty()) {
            return;                                                   // a source that serves any kind holds it
        }
        throw new DrishtiException(ErrorCode.BAD_SEARCH, "unknown kind " + kind + ": the kinds here are " + String.join(", ",
                known.stream().limit(12).toList()) + (known.size() > 12 ? " and " + (known.size() - 12) + " more" : ""));
    }

    private void refuseMisnamedKind(String kind) {
        if (mnemonics == null || packs == null || kind == null || packs.ownerOf(kind) != null) {
            return;
        }
        var m = mnemonics.of(kind.toUpperCase(java.util.Locale.ROOT));
        if (m.isPresent() && !m.get().kind().equals(kind)) {
            throw new DrishtiException(ErrorCode.BAD_SEARCH, kind + " is a mnemonic: use the kind " + m.get().kind() + " (history "
                    + m.get().kind() + " <id>)");
        }
        for (String singular : java.util.List.of(kind.replaceAll("ies$", "y"), kind.replaceAll("es$", ""), kind.replaceAll("s$", ""))) {
            if (!singular.equals(kind) && packs.ownerOf(singular) != null) {
                throw new DrishtiException(ErrorCode.BAD_SEARCH, "unknown kind " + kind + ": did you mean " + singular + "?");
            }
        }
    }

    /** {@link #rows} for an id a path cannot carry ({@code /}, {@code \}, {@code ..}): {@code /api/v1/history/{kind}/~?id=}. */
    @GetMapping("/{kind}/~")
    public HistoryAnswer rowsById(@PathVariable String kind, @RequestParam String id, AsOf current,
            @RequestParam(required = false) Integer days, @RequestParam(required = false) String range,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String fields, @RequestParam(defaultValue = "false") boolean diff,
            @RequestParam(required = false) String knownAt, @RequestParam(defaultValue = "asc") String order,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return rows(kind, id, current, days, range, from, to, fields, diff, knownAt, order, principal);
    }

    /**
     * One field's value on each of the last {@code days} business days up to {@code to} (default: the request's date),
     * read through the normal routing for each day, as the caller may see it. {@code path} is any Rachana-EL expression
     * over the document ({@code $.mtm}, {@code $.legs[0].rate}).
     */
    @GetMapping("/{kind}/{id}/series")
    public Series series(@PathVariable String kind, @PathVariable String id, @RequestParam String path,
            @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) String to, AsOf current,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        String expr = path.startsWith("$") ? path : "$." + path;
        com.ash.drishti.rachana.el.Expr compiled;
        try {
            compiled = el.compile(expr);
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot read the path '" + path + "': " + e.getMessage());
        }
        int n = Math.max(2, Math.min(history.limits().maxDays(), days));
        LocalDate last = to == null ? (current.businessDate() == null ? dates.current() : current.businessDate()) : LocalDate.parse(to);
        java.util.ArrayDeque<LocalDate> wanted = new java.util.ArrayDeque<>();
        for (LocalDate d = last; wanted.size() < n && !d.isBefore(dates.earliest()); d = dates.calendar().previous(d)) {
            wanted.addFirst(d);
        }
        EntityRef ref = EntityRef.of(kind, id);
        List<LocalDate> list = List.copyOf(wanted);
        List<CompletableFuture<EntityDocument>> reads = list.stream().map(d -> router.fetch(ref, AsOf.of(d))).toList();
        List<Point> points = new java.util.ArrayList<>();
        boolean dated = false;
        for (int i = 0; i < list.size(); i++) {
            EntityDocument doc;
            try {
                doc = reads.get(i).join();
            } catch (CompletionException e) {
                points.add(new Point(list.get(i), null, null, null, null));      // not there that day
                continue;
            }
            dated |= doc.provenance().businessDate() != null;
            Object v;
            try {
                v = com.ash.drishti.rachana.el.Values.simplify(compiled.eval(com.ash.drishti.rachana.el.EvalContext.of(
                        entitlements.redact(principal, doc.data()), formats)));
                if (v instanceof com.ash.drishti.api.DataNode node) {
                    v = node.isNull() ? null : node.unwrap();
                }
            } catch (RuntimeException e) {
                v = null;
            }
            points.add(new Point(list.get(i), v, doc.provenance().businessDate(), doc.provenance().source(), doc.provenance().generation()));
        }
        return new Series(new ViewModelRef(kind, id), expr, label(expr.substring(2)), points, dated);
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
