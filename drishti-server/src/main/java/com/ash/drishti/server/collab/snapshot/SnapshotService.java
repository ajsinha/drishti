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
package com.ash.drishti.server.collab.snapshot;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watermarked snapshots (COLLABORATION.md, step 10): a PNG of a shared view for the share's email and its page, drawn on the server
 * with Java2D. Off unless {@code drishti.collab.snapshots.enabled} (a pack may override it per pack).
 *
 * <p>The picture is computed for the <b>most restrictive</b> of the people it is for: the view is built once, with the field masks of
 * anyone who lacks {@code raw} and with a panel left out if any of them may not open its gate kind, so no recipient sees more than the
 * least-entitled one. A recipient who may not open the kind is not in the audience (the share never reached them). The watermark names
 * the sender, the recipients (a count, or names by policy), the time, the data's date and generation, and the link. The view and the
 * picture are cached (view, generation and rights profile; plus the share for the picture); drawing is bounded by a timeout, a size and
 * a PNG byte limit. Every picture produced is recorded in the access log as {@code export}.
 */
public final class SnapshotService {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** A picture and what it was made for. */
    public record Image(byte[] png, int width, int height, boolean cached, boolean masked, int recipients) {}

    private final CollabProperties props;
    private final ViewPipeline pipeline;
    private final Entitlements entitlements;
    private final Principals principals;
    private final PackAccess packs;
    private final ShareStore shares;
    private final AccessLog accessLog;
    private final SnapshotPainter painter = new SnapshotPainter();
    private final Cache<String, ViewModel> views;
    private final Cache<String, byte[]> pictures;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "drishti-snapshot");
        t.setDaemon(true);
        return t;
    });

    @SuppressWarnings("java:S107")
    public SnapshotService(CollabProperties props, ViewPipeline pipeline, Entitlements entitlements, Principals principals, PackAccess packs,
            ShareStore shares, AccessLog accessLog) {
        this.props = props;
        this.pipeline = pipeline;
        this.entitlements = entitlements;
        this.principals = principals;
        this.packs = packs;
        this.shares = shares;
        this.accessLog = accessLog;
        CollabProperties.Snapshots c = props.snapshots();
        this.views = Caffeine.newBuilder().maximumSize(c.cacheSize()).expireAfterWrite(c.cacheTtl()).build();
        this.pictures = Caffeine.newBuilder().maximumSize(c.cacheSize()).expireAfterWrite(c.cacheTtl()).build();
    }

    public void close() {
        pool.shutdownNow();
    }

    // ---- policy ---------------------------------------------------------------------------------------------------

    /** Whether a picture of this kind may be made: the owning pack's {@code snapshots.enabled}, else the global one. */
    public boolean allowed(String kind) {
        String owner = kind == null ? null : packs.ownerOf(kind);
        Object s = owner == null ? null : props.packs().getOrDefault(owner, Map.of()).get("snapshots");
        if (s instanceof Map<?, ?> m && m.get("enabled") != null) {
            return "true".equalsIgnoreCase(String.valueOf(m.get("enabled")).trim());
        }
        return props.snapshots().enabled();
    }

    /** DRS-7015 unless pictures are allowed for the kind (and a shared panel's gate kind). */
    public void require(String kind, String gateKind) {
        if (!props.enabled() || !allowed(kind) || (gateKind != null && !allowed(gateKind))) {
            throw new DrishtiException(ErrorCode.SNAPSHOT_REFUSED, "pictures are switched off for " + kind
                    + " views (drishti.collab.snapshots.enabled, or this kind's pack under drishti.collab.packs.<pack>.snapshots.enabled)");
        }
    }

    public boolean allowed(String kind, String gateKind) {
        return props.enabled() && allowed(kind) && (gateKind == null || allowed(gateKind));
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    /** The picture of a stored share for the people it reached. {@code actor} is whoever asks (for the access log). */
    public Image forShare(Share share, String actor) {
        List<Principal> audience = new ArrayList<>();
        for (Recipient r : shares.recipients(share.id())) {
            if (Recipient.NOTIFIED.equals(r.state())) {
                audience.add(principals.of(r.username()));
            }
        }
        // A redraw can only get more restrictive: the rights profile frozen when the share was made, the recipients as they are now and
        // whoever asks all bind it (a recipient promoted to raw later, or a sender asking, never widens what the picture shows).
        List<Principal> bound = new ArrayList<>(frozen(share));
        if (actor != null && !actor.isBlank()) {
            bound.add(principals.of(actor));
        }
        return render(share, audience, bound, actor, false);
    }

    /** The profile to store with a new share: each distinct role set of the people it reaches, written {@code a+b|c}. */
    public static String profileOf(List<Principal> audience) {
        TreeSet<String> sets = new TreeSet<>();
        audience.forEach(p -> sets.add(String.join("+", new TreeSet<>(p.roles())).replace(",", "").replace("|", "")));
        return String.join("|", sets);
    }

    /** The principals of the profile stored with the share ({@code profile=} among its channels); none for an older share. */
    static List<Principal> frozen(Share share) {
        List<Principal> out = new ArrayList<>();
        for (String c : share.channels().split(",")) {
            if (c.startsWith("profile=")) {
                for (String set : c.substring(8).split("\\|", -1)) {
                    out.add(new Principal("frozen-profile", set.isEmpty() ? List.of() : List.of(set.split("\\+"))));
                }
            }
        }
        return out;
    }

    /** The picture for an audience: drawn (or taken from the cache), recorded, and returned. */
    public Image render(Share share, List<Principal> audience, String actor, boolean preview) {
        return render(share, audience, List.of(), actor, preview);
    }

    /** As above; {@code bound} are principals whose rights also limit the picture without being counted as recipients. */
    private Image render(Share share, List<Principal> audience, List<Principal> bound, String actor, boolean preview) {
        require(share.kind(), share.gateKind());
        List<Principal> may = audience.stream().filter(p -> entitlements.mayOpen(p, share.kind())
                && (share.gateKind() == null || entitlements.mayOpen(p, share.gateKind()))).toList();
        if (may.isEmpty()) {
            throw new DrishtiException(ErrorCode.SNAPSHOT_REFUSED, "no recipient may open this view, so there is no picture to make");
        }
        List<Principal> rights = new ArrayList<>(may);
        rights.addAll(bound);
        boolean masked = rights.stream().anyMatch(entitlements::masks);
        String viewKey = viewKey(share, rights, masked);
        String picKey = viewKey + '|' + (preview ? "preview-" + actor : share.id());
        byte[] cached = pictures.getIfPresent(picKey);
        if (cached != null) {
            log(share, actor, may.size(), masked, cached.length, true, preview);
            return new Image(cached, 0, 0, true, masked, may.size());
        }
        SnapshotModel model = bounded(() -> model(share, may, rights, viewKey, preview ? null : share.id(), masked));
        byte[] png = bounded(() -> {
            try {
                return painter.png(model);
            } catch (IOException e) {
                throw new DrishtiException(ErrorCode.SNAPSHOT_FAILED, "the picture could not be drawn: " + e.getMessage(), e);
            }
        });
        if (png.length > props.snapshots().maxBytes()) {
            throw new DrishtiException(ErrorCode.SNAPSHOT_FAILED, "the picture is " + png.length + " bytes; the limit is "
                    + props.snapshots().maxBytes() + " (drishti.collab.snapshots.max-bytes)");
        }
        pictures.put(picKey, png);
        log(share, actor, may.size(), masked, png.length, false, preview);
        return new Image(png, model.width(), model.height(), false, masked, may.size());
    }

    /**
     * What the picture would draw for the audience, before any pixel: the most-restrictive view laid out with its watermark. Exposed so the
     * rights and the watermark can be checked as data.
     */
    public SnapshotModel model(Share share, List<Principal> audience) {
        List<Principal> may = audience.stream().filter(p -> entitlements.mayOpen(p, share.kind())
                && (share.gateKind() == null || entitlements.mayOpen(p, share.gateKind()))).toList();
        if (may.isEmpty()) {
            throw new DrishtiException(ErrorCode.SNAPSHOT_REFUSED, "no recipient may open this view, so there is no picture to make");
        }
        boolean masked = may.stream().anyMatch(entitlements::masks);
        return model(share, may, may, viewKey(share, may, masked), share.id(), masked);
    }

    private SnapshotModel model(Share share, List<Principal> may, List<Principal> rights, String viewKey, String shareRef, boolean masked) {
        ViewModel view = views.getIfPresent(viewKey);
        if (view == null) {
            view = buildView(share, rights, masked);
            views.put(viewKey, view);
        }
        CollabProperties.Snapshots c = props.snapshots();
        if (share.panelId() != null && view.panels().stream().noneMatch(p -> p.id().equals(share.panelId()) && p.denied() == null)) {
            throw new DrishtiException(ErrorCode.SNAPSHOT_REFUSED, "the panel '" + share.panelId() + "' is not open to every recipient, so there is no picture of it");
        }
        SnapshotLayout layout = new SnapshotLayout(c.width(), c.maxHeight(), c.maxPanels(), c.maxRows());
        return layout.build(view, share.panelId(), watermark(share, may, view, shareRef));
    }

    private ViewModel buildView(Share share, List<Principal> may, boolean masked) {
        Principal maskOf = may.stream().filter(entitlements::masks).findFirst().orElse(may.get(0));
        UnaryOperator<DataNode> redact = masked ? entitlements.redactor(maskOf) : UnaryOperator.identity();
        Predicate<String> mayOpen = k -> may.stream().allMatch(p -> entitlements.mayOpen(p, k));
        ViewModel v = pipeline.view(EntityRef.of(share.kind(), share.entityId()), asOf(share), redact, mayOpen);
        for (Principal p : may) {
            v = entitlements.restrict(p, v);
        }
        return v;
    }

    private static AsOf asOf(Share s) {
        return s.pin() == null || s.pin().live() || s.pin().businessDate() == null ? AsOf.LATEST : AsOf.of(s.pin().businessDate());
    }

    private SnapshotLayout.Watermark watermark(Share share, List<Principal> may, ViewModel view, String shareRef) {
        String sender = principals.user(share.sender()).map(User::displayName).filter(n -> n != null && !n.isBlank()).orElse(share.sender());
        String who;
        if ("names".equals(props.snapshots().recipients())) {
            List<String> names = may.stream().map(p -> principals.user(p.user()).map(User::displayName).filter(n -> n != null && !n.isBlank())
                    .orElse(p.user())).limit(5).toList();
            who = String.join(", ", names) + (may.size() > names.size() ? " and " + (may.size() - names.size()) + " more" : "");
        } else {
            who = may.size() + (may.size() == 1 ? " recipient" : " recipients");
        }
        ViewModel.Provenance pv = view.provenance();
        String asOf = pv != null && pv.businessDate() != null ? pv.businessDate()
                : share.pin() != null && share.pin().businessDate() != null ? share.pin().businessDate().toString() : "live";
        long generation = pv != null ? pv.generation() : share.pin() == null ? 0 : share.pin().generation();
        String base = props.consoleUrl() == null ? "" : props.consoleUrl().replaceAll("/+$", "");
        String link = shareRef == null ? "(the link is added when the share is sent)" : base + "/share/" + shareRef;
        return new SnapshotLayout.Watermark(sender, who, WHEN.format(share.createdAt() == null ? Instant.now() : share.createdAt()), asOf, generation, link);
    }

    /** The view's identity, its generation and the rights profile: what the cached view is a function of. */
    private String viewKey(Share share, List<Principal> may, boolean masked) {
        TreeSet<String> roleSets = new TreeSet<>();
        may.forEach(p -> roleSets.add(String.join("+", new TreeSet<>(p.roles()))));
        long gen = share.pin() == null ? 0 : share.pin().generation();
        return share.kind() + '\u0000' + share.entityId() + '\u0000' + asOf(share) + '\u0000' + gen + '\u0000' + (masked ? "masked" : "raw") + '\u0000'
                + String.join("|", roleSets) + '\u0000' + share.gateKind() + '\u0000' + share.panelId();
    }

    private <T> T bounded(java.util.concurrent.Callable<T> job) {
        Future<T> f = pool.submit(job);
        try {
            return f.get(props.snapshots().timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new DrishtiException(ErrorCode.SNAPSHOT_FAILED, "the picture took longer than " + props.snapshots().timeout()
                    + " (drishti.collab.snapshots.timeout)");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.SNAPSHOT_FAILED, "the picture was interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DrishtiException de) {
                throw de;
            }
            LOG.warn("snapshot failed: {}", e.getCause().toString());
            throw new DrishtiException(ErrorCode.SNAPSHOT_FAILED, "the picture could not be drawn: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private void log(Share share, String actor, int recipients, boolean masked, int bytes, boolean cached, boolean preview) {
        if (accessLog == null) {
            return;
        }
        try {
            accessLog.recordNow(new AccessLog.Event(Instant.now(), actor, "export", share.kind(), share.entityId(),
                    "snapshot " + (preview ? "preview" : share.id()) + " for " + recipients + (recipients == 1 ? " recipient, " : " recipients, ")
                            + (masked ? "masked, " : "unmasked, ") + bytes + " bytes" + (cached ? ", cached" : ""),
                    share.pin() == null || share.pin().businessDate() == null ? null : share.pin().businessDate().toString()));
        } catch (RuntimeException e) {
            LOG.debug("could not record the snapshot: {}", e.toString());
        }
    }
}
