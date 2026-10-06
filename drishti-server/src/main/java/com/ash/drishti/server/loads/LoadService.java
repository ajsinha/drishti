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
package com.ash.drishti.server.loads;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceFailures;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.alerts.AlertEngine;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * "A batch landed": records the announcement (once per pack, kind, date and batch) and, when the load is {@code ready}, makes
 * Drishti act on it, each as a step with its own result: <b>refresh</b> (the pack's connectors drop what they cached, so dates and
 * views include the new data now), <b>verify</b> (the kind has entities on the date), <b>alerts</b> (every rule on the kind is
 * evaluated on the date), <b>smoke</b> (open a few sample entities, when the pack asks), <b>notices</b> (the people the pack names).
 * A {@code failed} load is recorded and told, and nothing else runs. Drishti never ingests: the data is where the ETL put it.
 */
public final class LoadService {

    /** What the ETL sends. {@code businessDate} may also arrive as {@code asOf}; {@code status} defaults to ready. */
    public record Announcement(String kind, String businessDate, String asOf, String status, Long rows, Long rejected, String source,
            String batchId, String note) {}

    /** The answer: the record, and whether it was an announcement seen before (then nothing ran again). */
    public record Outcome(LoadRecord load, boolean duplicate) {}

    private static final Pattern BATCH = Pattern.compile("[A-Za-z0-9._:/#@ -]{1,100}");

    private final PackRegistry packs;
    private final LoadStore store;
    private final LoadsConfig.Overrides configs;
    private final LoadsProperties props;
    private final SourceRegistry sources;
    private final SourceRouter router;
    private final ViewPipeline pipeline;
    private final AlertEngine alerts;
    private final LoadNotifier notifier;
    private final BusinessDates dates;
    private final AuditLog audit;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public LoadService(PackRegistry packs, LoadStore store, LoadsConfig.Overrides configs, LoadsProperties props, SourceRegistry sources,
            SourceRouter router, ViewPipeline pipeline, AlertEngine alerts, LoadNotifier notifier, BusinessDates dates, AuditLog audit, Clock clock) {
        this.packs = packs;
        this.store = store;
        this.configs = configs;
        this.props = props;
        this.sources = sources;
        this.router = router;
        this.pipeline = pipeline;
        this.alerts = alerts;
        this.notifier = notifier;
        this.dates = dates;
        this.audit = audit;
        this.clock = clock;
    }

    public LoadStore store() {
        return store;
    }

    public LoadsConfig.Overrides configs() {
        return configs;
    }

    public LoadsProperties properties() {
        return props;
    }

    public Clock clock() {
        return clock;
    }

    public Pack pack(String name) {
        return packs.packs().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.LOAD_PACK_NOT_FOUND, "no loaded pack named '" + name + "'"));
    }

    public List<Pack> loadedPacks() {
        return packs.packs();
    }

    /** Records the announcement and runs the steps; {@code by} is the user the request is made as (for the history and the audit log). */
    public Outcome announce(String packName, Announcement a, String by) {
        Pack pack = pack(packName);
        String kind = text(a.kind(), "kind", 100);
        if (!pack.kinds().contains(kind)) {
            throw new DrishtiException(ErrorCode.LOAD_KIND_UNKNOWN, "pack '" + packName + "' has no kind '" + kind + "'; its kinds are " + pack.kinds());
        }
        String status = a.status() == null || a.status().isBlank() ? LoadRecord.READY : a.status().trim().toLowerCase(Locale.ROOT);
        if (!LoadRecord.READY.equals(status) && !LoadRecord.FAILED.equals(status)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "status must be ready or failed");
        }
        LocalDate date = date(a);
        if (a.rows() != null && a.rows() < 0 || a.rejected() != null && a.rejected() < 0) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "rows and rejected cannot be negative");
        }
        String batch = a.batchId() == null || a.batchId().isBlank() ? null : a.batchId().trim();
        if (batch != null && !BATCH.matcher(batch).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "batchId may hold letters, digits and . _ : / # @ - and spaces, up to 100 characters");
        }
        String source = optional(a.source(), "source", 200);
        String note = optional(a.note(), "note", 1000);
        Instant start = clock.instant();
        LoadStore.Begun begun = store.begin(packName, kind, date, status, a.rows(), a.rejected(), source, batch, note, start, by);
        if (begun.duplicate()) {
            return new Outcome(begun.record(), true);
        }
        LoadRecord r = begun.record();
        long t0 = System.nanoTime();
        LoadsConfig cfg = configs.effective(pack);
        List<LoadRecord.Step> steps = new ArrayList<>();
        steps.add(new LoadRecord.Step("record", "ok", (r.reload() ? "recorded as a reload of an earlier ready load" : r.attempt() > 1 ? "recorded as attempt " + r.attempt() : "recorded"), 0));
        String verified = "skipped";
        Long entities = null;
        int fired = 0;
        List<EntityRef> sample = List.of();
        if (LoadRecord.READY.equals(status)) {
            steps.add(refresh(kind));
            Verify v = verify(kind, date);
            steps.add(v.step());
            verified = v.state();
            entities = v.count();
            sample = v.sample();
            if (!"verified".equals(verified)) {
                // nothing to evaluate on a date the data is not on: the alert step says so rather than reading nothing
                steps.add(new LoadRecord.Step("alerts", "skipped", "the data is not readable for " + date + ", so no rule was evaluated", 0));
            } else {
                long a0 = System.nanoTime();
                LoadRecord.Step st;
                try {
                    fired = alerts.evaluateKindOn(kind, date, props.verifyTimeout());
                    st = new LoadRecord.Step("alerts", "ok", fired + " alert" + (fired == 1 ? "" : "s") + " fired", ms(a0));
                } catch (RuntimeException e) {
                    st = new LoadRecord.Step("alerts", "failed", message(e), ms(a0));
                }
                steps.add(st);
            }
            steps.add(smoke(cfg, date, sample));
            if (store.clearLate(packName, kind, date)) {
                steps.add(new LoadRecord.Step("late", "ok", "the late-data flag for " + kind + " " + date + " is cleared", 0));
            }
        } else {
            steps.add(new LoadRecord.Step("late", "skipped", "a failed load does not clear a late-data flag", 0));
        }
        LoadRecord counted = r.completed(verified, entities, fired, 0, steps, 0);
        String summary = summary(counted);
        int told = notifier.tell(cfg, packName, kind, date, LoadNotifier.TYPE, summary, "load:" + packName + ":" + r.id(), by);
        steps.add(new LoadRecord.Step("notices", told == 0 ? "skipped" : "ok", told == 0 ? "no one to tell: nobody with the roles " + cfg.notifyRoles()
                + (cfg.notifyUsers().isEmpty() ? "" : " or the users " + cfg.notifyUsers()) + " may open " + kind
                : told + " " + (told == 1 ? "person" : "people") + " told" + (cfg.email() ? " (email queued where an address is set)" : ""), 0));
        LoadRecord done = r.completed(verified, entities, fired, told, steps, ms(t0));
        store.update(done);
        audit.record(by, "data-load-" + status, packName, kind + " " + date + (batch == null ? "" : " batch " + batch) + ", " + verified + ", " + fired + " alerts, " + told + " told");
        return new Outcome(done, false);
    }

    /** The one-line notice: {@code trade for 2026-10-06 loaded: 48,213 rows, 12 rejected; 3 alerts fired}. */
    public static String summary(LoadRecord r) {
        StringBuilder s = new StringBuilder(r.kind()).append(' ');
        if (LoadRecord.FAILED.equals(r.status())) {
            s.append("load for ").append(r.businessDate()).append(" FAILED");
            if (r.note() != null && !r.note().isBlank()) {
                s.append(": ").append(r.note());
            }
            return s.toString();
        }
        s.append("for ").append(r.businessDate()).append(r.reload() ? " reloaded" : " loaded");
        List<String> parts = new ArrayList<>();
        if (r.rows() != null) {
            parts.add(String.format(Locale.ROOT, "%,d rows", r.rows()));
        }
        if (r.rejected() != null) {
            parts.add(String.format(Locale.ROOT, "%,d rejected", r.rejected()));
        }
        if (!parts.isEmpty()) {
            s.append(": ").append(String.join(", ", parts));
        }
        if ("not-found".equals(r.verified())) {
            s.append("; NOT FOUND in the data");
        } else if ("verified".equals(r.verified())) {
            s.append("; ").append(r.alerts()).append(" alert").append(r.alerts() == 1 ? "" : "s").append(" fired");
        }
        return s.toString();
    }

    // -- steps -------------------------------------------------------------------------------------------------------------------

    private LoadRecord.Step refresh(String kind) {
        long t0 = System.nanoTime();
        List<String> purged = new ArrayList<>();
        try {
            for (SourcePlugin p : sources.plugins()) {
                if (p.manifest().serves(kind)) {
                    p.purgeCaches();
                    purged.add(p.manifest().name());
                }
            }
            pipeline.purgeCaches();
            return new LoadRecord.Step("refresh", "ok", purged.isEmpty() ? "no connector serves " + kind + "; engine caches dropped"
                    : "caches dropped: " + String.join(", ", purged), ms(t0));
        } catch (RuntimeException e) {
            return new LoadRecord.Step("refresh", "failed", message(e), ms(t0));
        }
    }

    private record Verify(LoadRecord.Step step, String state, Long count, List<EntityRef> sample) {}

    private Verify verify(String kind, LocalDate date) {
        long t0 = System.nanoTime();
        try {
            SourceFailures failures = new SourceFailures();
            List<EntityHit> hits = router.list(kind, "", props.verifyLimit(), props.verifyTimeout(), AsOf.of(date), failures).hits();
            long n = hits.size();
            String more = n >= props.verifyLimit() ? " or more" : "";
            if (n > 0) {
                return new Verify(new LoadRecord.Step("verify", failures.isEmpty() ? "ok" : "warn", n + more + " " + kind + " entities on " + date
                        + (failures.isEmpty() ? "" : "; some sources could not answer: " + failures.asMap()), ms(t0)), "verified", n,
                        hits.stream().map(EntityHit::ref).toList());
            }
            return new Verify(new LoadRecord.Step("verify", "warn", "no " + kind + " entities found on " + date
                    + (failures.isEmpty() ? "; check the connector's root, dates and caches" : "; sources that could not answer: " + failures.asMap()), ms(t0)),
                    "not-found", 0L, List.of());
        } catch (RuntimeException e) {
            return new Verify(new LoadRecord.Step("verify", "failed", message(e), ms(t0)), "not-found", null, List.of());
        }
    }

    private LoadRecord.Step smoke(LoadsConfig cfg, LocalDate date, List<EntityRef> sample) {
        if (cfg.smoke() <= 0) {
            return new LoadRecord.Step("smoke", "skipped", "not asked for (loads.smoke is 0)", 0);
        }
        long t0 = System.nanoTime();
        if (sample.isEmpty()) {
            return new LoadRecord.Step("smoke", "skipped", "no entities to open", 0);
        }
        int opened = 0;
        List<String> bad = new ArrayList<>();
        for (EntityRef ref : sample.stream().limit(cfg.smoke()).toList()) {
            try {
                pipeline.view(ref, AsOf.of(date));
                opened++;
            } catch (RuntimeException e) {
                bad.add(ref.id() + ": " + message(e));
            }
        }
        return new LoadRecord.Step("smoke", bad.isEmpty() ? "ok" : "failed", "opened " + opened + " of " + (opened + bad.size()) + " sample entities"
                + (bad.isEmpty() ? "" : "; failed: " + String.join("; ", bad.subList(0, Math.min(3, bad.size())))), ms(t0));
    }

    // -- input -------------------------------------------------------------------------------------------------------------------

    private LocalDate date(Announcement a) {
        String raw = a.businessDate() != null && !a.businessDate().isBlank() ? a.businessDate() : a.asOf();
        if (raw == null || raw.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "businessDate (or asOf) is required, as yyyy-MM-dd");
        }
        try {
            LocalDate d = LocalDate.parse(raw.trim());
            if (d.isAfter(LocalDate.now(clock.withZone(dates.zone())))) {
                throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "business date " + d + " is in the future");
            }
            return d;
        } catch (DateTimeException e) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "cannot read business date '" + raw + "' (use yyyy-MM-dd)");
        }
    }

    private static String text(String s, String name, int max) {
        if (s == null || s.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is required");
        }
        String t = s.trim();
        if (t.length() > max) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is longer than " + max + " characters");
        }
        return t;
    }

    private static String optional(String s, String name, int max) {
        return s == null || s.isBlank() ? null : text(s, name, max);
    }

    private static long ms(long nanos) {
        return Math.round((System.nanoTime() - nanos) / 1e6);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** For callers that need a wait bound of their own. */
    public Duration verifyTimeout() {
        return props.verifyTimeout();
    }
}
