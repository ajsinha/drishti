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
package com.ash.drishti.server.compute;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.reactive.ReactiveHost;
import com.ash.drishti.engine.source.SourceFailures;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;
import org.springframework.scheduling.support.CronExpression;

/**
 * What the server gives reactive connectors (REACTIVE_CONNECTORS.md section 6.2): the viewer of the request being served (from the
 * caller every {@code /api/} request carries), whether their roles may open a computed kind, inputs read through the router <em>as that
 * viewer</em> (kinds refused, field masks applied before anything reaches an engine), data versions for pins, the compute workers, audit,
 * cron and live entities. A viewer's view signature is a hash of what decides what they may see (their roles and the embedding app, as
 * {@link Principal#securitySignature()}): two people with the same rights share a cached answer, two with different rights never do.
 */
final class ReactiveHostImpl implements ReactiveHost {

    private static final Duration READ_BUDGET = Duration.ofSeconds(20);

    private final SourceRouter router;
    private final Entitlements entitlements;
    private final BusinessDates dates;
    private final ComputeService compute;
    private final AuditLog audit;
    private final boolean inProcessJava;

    ReactiveHostImpl(SourceRouter router, Entitlements entitlements, BusinessDates dates, ComputeService compute, AuditLog audit, boolean inProcessJava) {
        this.router = router;
        this.entitlements = entitlements;
        this.dates = dates;
        this.compute = compute;
        this.audit = audit;
        this.inProcessJava = inProcessJava;
    }

    /** The viewer of a principal: user, roles, and the hash of their rights. */
    static Viewer viewerOf(Principal p) {
        return new Viewer(p.user(), p.roles(), signature(p));
    }

    static String signature(Principal p) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(p.securitySignature().getBytes(StandardCharsets.UTF_8));
            return "vs-" + HexFormat.of().formatHex(h).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Principal principal(Viewer v) {
        return new Principal(v.user(), v.roles());
    }

    @Override
    public Optional<Viewer> viewer() {
        Optional<Viewer> v = Viewer.current();
        if (v.isPresent()) {
            return v;
        }
        return NetCaller.current().map(NetCaller.Caller::principal).map(ReactiveHostImpl::viewerOf);
    }

    @Override
    public Optional<String> refusal(Viewer viewer, String connector, String kind) {
        return entitlements.mayOpen(principal(viewer), kind) ? Optional.empty() : Optional.of("your role cannot see " + kind);
    }

    @Override
    public List<DataNode> read(Viewer viewer, String kind, List<String> ids, AsOf asOf, int limit) {
        Principal p = principal(viewer);
        if (!entitlements.mayOpen(p, kind)) {
            throw new SecurityException("your role cannot read " + kind + ", which this computation needs");
        }
        AsOf at = asOf == null ? dates.resolve(AsOf.LATEST) : asOf.businessDate() == null ? dates.resolve(asOf) : asOf;
        List<EntityRef> refs = new ArrayList<>();
        if (ids == null) {
            SourceFailures failures = new SourceFailures();
            for (EntityHit h : router.listAcross(kind, limit, READ_BUDGET, at, failures).hits()) {
                refs.add(h.ref());
            }
        } else {
            new LinkedHashSet<>(ids).forEach(id -> refs.add(EntityRef.of(kind, id)));
        }
        if (refs.isEmpty()) {
            return List.of();
        }
        Map<EntityRef, EntityDocument> docs = router.fetchAll(refs, READ_BUDGET, at);
        UnaryOperator<DataNode> redact = entitlements.redactor(p);
        List<DataNode> out = new ArrayList<>(refs.size());
        for (EntityRef r : refs) {
            EntityDocument d = docs.get(r);
            if (d != null && !d.deleted() && out.size() < limit) {
                out.add(redact.apply(d.data()));
            }
        }
        return out;
    }

    @Override
    public Optional<String> dataVersion(String kind, AsOf asOf) {
        try {
            return router.dataVersion(kind, asOf == null || asOf.businessDate() == null ? dates.resolve(asOf == null ? AsOf.LATEST : asOf) : asOf);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public LocalDate today() {
        return dates.current();
    }

    @Override
    public ComputeService compute() {
        return compute;
    }

    @Override
    public boolean inProcessJava() {
        return inProcessJava;
    }

    @Override
    public void audit(String actor, String action, String subject, String detail) {
        if (audit != null) {
            audit.record(actor, action, subject, detail.length() > 2000 ? detail.substring(0, 2000) : detail);
        }
    }

    @Override
    public Optional<Instant> nextCron(String cron, String zone, Instant after) {
        try {
            ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(ZoneId.of(zone == null ? "UTC" : zone)));
            return Optional.ofNullable(next).map(ZonedDateTime::toInstant);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public AutoCloseable subscribe(String kind, String id, LongConsumer onChange) {
        try {
            Subscription s = router.subscribe(EntityRef.of(kind, id), d -> onChange.accept(d.provenance() == null ? 0 : d.provenance().generation()));
            return s::close;
        } catch (RuntimeException e) {
            return () -> { };                                        // not live here: nothing to follow
        }
    }
}
