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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DateCoverage;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes entity reads to plugins. The configured route for a kind is tried first, then the default route,
 * then any other plugin that serves the kind. Every read runs on a virtual thread with a deadline.
 */
public final class SourceRouter implements com.ash.drishti.common.LiveSettings {

    private final SourceRegistry registry;
    private final SourcesProperties props;
    /** Kind to connector, as the site and the loaded packs set it; swapped whole when packs change their routes. */
    private volatile java.util.Map<String, String> routes;
    private final ExecutorService executor;

    private final SourceStats stats = new SourceStats();

    /** Reads per connector, for the health page. */
    public SourceStats stats() {
        return stats;
    }

    /** Packs changed: the kinds they route to connectors are read again. */
    @Override
    public Runnable prepare(org.springframework.core.env.Environment candidate) {
        java.util.Map<String, String> next = java.util.Map.copyOf(org.springframework.boot.context.properties.bind.Binder.get(candidate)
                .bind("drishti.sources.routes", org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class)).orElse(java.util.Map.of()));
        return () -> routes = next;
    }

    public SourceRouter(SourceRegistry registry, SourcesProperties props, ExecutorService virtualExecutor) {
        this.registry = registry;
        this.props = props;
        this.routes = props.routes();
        this.executor = virtualExecutor;
        registry.attach(this);
    }

    /** The kinds the sources name in their manifests (a source that serves any kind names none). */
    public java.util.Set<String> declaredKinds() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        registry.plugins().forEach(p -> out.addAll(p.manifest().kinds()));
        return out;
    }

    /** Plugins to try for {@code kind}, in order. */
    List<SourcePlugin> candidates(String kind) {
        Map<String, SourcePlugin> ordered = new LinkedHashMap<>();
        for (String name : new String[] {routes.get(kind), props.defaultRoute()}) {
            if (name != null) {
                registry.plugin(name).filter(p -> p.manifest().serves(kind)).ifPresent(p -> ordered.put(name, p));
            }
        }
        for (SourcePlugin p : registry.plugins()) {
            if (p.manifest().serves(kind)) {
                ordered.putIfAbsent(p.manifest().name(), p);
            }
        }
        return adjacentUnion(new ArrayList<>(ordered.values()));
    }

    /** The union connectors of a kind moved up to sit right after the first of them, so the union is read as one store. */
    private List<SourcePlugin> adjacentUnion(List<SourcePlugin> in) {
        int first = -1;
        for (int i = 0; i < in.size(); i++) {
            if (registry.union(in.get(i).manifest().name())) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return in;
        }
        List<SourcePlugin> out = new ArrayList<>(in.subList(0, first));
        in.stream().filter(p -> registry.union(p.manifest().name())).forEach(out::add);
        in.subList(first, in.size()).stream().filter(p -> !registry.union(p.manifest().name())).forEach(out::add);
        return out;
    }

    /**
     * One entity over business dates (HISTORY_QUERIES.md section 9.3): the kind's dated connectors in read order, each asked for the dates
     * no earlier one holds or covers, then the Drishti*net peers for the rest. A {@code knownAt} put to a dated store without time travel
     * fails with {@code DRS-1007}.
     */
    public SourceHistory.Routed history(com.ash.drishti.api.HistoryRequest request) {
        List<SourcePlugin> candidates = candidates(request.ref().kind());
        readOrder(candidates, AsOf.of(request.to()));
        return new SourceHistory(this).read(request, candidates, fallback);
    }

    /**
     * True when some source of the kind keeps daily history here (a dated connector), or the cluster may hold it on a peer; false when
     * every connector of the kind holds current data only.
     */
    public boolean keepsHistory(EntityRef ref, java.time.LocalDate date) {
        if (candidates(ref.kind()).stream().anyMatch(p -> p.manifest().capabilities().dated())) {
            return true;
        }
        Fallback fb = fallback;
        return fb != null && !fb.peers(ref, AsOf.of(date)).isEmpty();
    }

    /** The names of the kind's connectors that hold current data only (for the message when the kind keeps no history). */
    public List<String> undatedSources(String kind) {
        return candidates(kind).stream().filter(p -> !p.manifest().capabilities().dated()).map(p -> p.manifest().name()).toList();
    }

    boolean isUnion(String connector) {
        return registry.union(connector);
    }

    /** The union connectors among a kind's candidates, in order (empty when the kind has no union). */
    public List<SourcePlugin> unionOf(String kind) {
        return candidates(kind).stream().filter(p -> registry.union(p.manifest().name())).toList();
    }

    public CompletableFuture<EntityDocument> fetch(EntityRef ref) {
        return fetch(ref, props.fetchTimeout(), AsOf.LATEST);
    }

    public CompletableFuture<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        return fetch(ref, props.fetchTimeout(), asOf);
    }

    public CompletableFuture<EntityDocument> fetch(EntityRef ref, Duration timeout) {
        return fetch(ref, timeout, AsOf.LATEST);
    }

    /** Why nothing serves {@code kind}: a connector the kind is routed to that does not exist, is off, or failed; else no source at all. */
    DrishtiException noSource(String kind) {
        String routed = routes.get(kind);
        if (routed != null && registry.plugin(routed).isEmpty()) {
            if (registry.restartingFor(kind, routed).contains(routed)) {
                return new DrishtiException(ErrorCode.CONNECTOR_RESTARTING, "connector " + routed + " is restarting (kind '" + kind
                        + "' is routed to it) and was not back within " + RESTART_WAIT.toSeconds() + " s: try again in a moment");
            }
            var status = registry.connectorStatuses().get(routed);
            if (status == null) {
                return new DrishtiException(ErrorCode.CONNECTOR_NOT_CONFIGURED, "connector " + routed + " is not configured: kind '" + kind
                        + "' is routed to it, but no connector file " + routed + ".yaml exists (create it in Admin -> Connectors)");
            }
            return new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "connector " + routed + " is " + status.state().toLowerCase(java.util.Locale.ROOT)
                    + (status.problem() == null ? "" : ": " + status.problem()) + " (kind '" + kind + "' is routed to it)");
        }
        return new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no source serves kind '" + kind + "'");
    }

    /**
     * Reads {@code ref} as of {@code asOf}. Dated sources get the date and stamp the business date their document
     * is for; undated ones answer with what they hold and their documents carry no date, so the view can say so.
     */
    public CompletableFuture<EntityDocument> fetch(EntityRef ref, Duration timeout, AsOf asOf) {
        return fetch(ref, timeout, asOf, timeout);
    }

    /**
     * As {@link #fetch(EntityRef, Duration, AsOf)}, and a read that has gone on to the peers (Drishti*net: no local source holds the
     * entity, or a grouped page is gathered) may take {@code peerTimeout} from its start instead: a call between servers takes longer
     * than a read of this node's own data, which keeps {@code timeout}.
     */
    CompletableFuture<EntityDocument> fetch(EntityRef ref, Duration timeout, AsOf asOf, Duration peerTimeout) {
        List<String> restarting = registry.restartingFor(ref.kind(), routes.get(ref.kind()));
        if (!restarting.isEmpty()) {
            // a connector of the kind is being restarted (its file changed, a retry) and is out of routing for its drain and its start: the
            // read waits for it, as a query does (ServerSourceGateway), instead of answering "no source" or "not found" meanwhile (QA2-202)
            return CompletableFuture.runAsync(() -> registry.awaitStarted(restarting, RESTART_WAIT), executor)
                    .thenCompose(x -> fetchNow(ref, timeout, asOf, peerTimeout));
        }
        return fetchNow(ref, timeout, asOf, peerTimeout);
    }

    /** How long a read waits for a connector of its kind that is being (re)started: its drain and its start. */
    static final Duration RESTART_WAIT = Duration.ofSeconds(30);

    private CompletableFuture<EntityDocument> fetchNow(EntityRef ref, Duration timeout, AsOf asOf, Duration peerTimeout) {
        List<SourcePlugin> candidates = candidates(ref.kind());
        Fallback fb = fallback;
        if (candidates.isEmpty() && fb == null) {
            return CompletableFuture.failedFuture(noSource(ref.kind()));
        }
        readOrder(candidates, asOf);
        AtomicReference<String> asking = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean peers = new java.util.concurrent.atomic.AtomicBoolean();
        // the read knows when its caller stops waiting (its budget, or the local budget once it completed it): a connector's queue for a
        // slot of its own (ReadSlots) then drops it instead of reading for nobody (QA3A-01)
        long deadline = System.nanoTime() + (fb == null || peerTimeout.compareTo(timeout) <= 0 ? timeout : peerTimeout).toNanos();
        AtomicReference<CompletableFuture<EntityDocument>> self = new AtomicReference<>();
        java.util.function.BooleanSupplier gone = () -> {
            CompletableFuture<EntityDocument> f = self.get();
            return f != null && f.isDone();
        };
        CompletableFuture<EntityDocument> read = CompletableFuture.supplyAsync(() -> com.ash.drishti.api.ReadDeadline.within(deadline, gone,
                () -> fb == null ? readFirst(ref, candidates, asOf, asking) : readWithPeers(ref, candidates, asOf, asking, fb, peers)), executor);
        self.set(read);
        if (fb == null || peerTimeout.compareTo(timeout) <= 0) {
            read.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            // the local budget for this node's own sources; the read that went on to the peers by then waits up to the peer budget
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
                if (!peers.get()) {
                    read.completeExceptionally(new TimeoutException());
                }
            });
            read.orTimeout(peerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return read.exceptionallyCompose(e -> CompletableFuture.failedFuture(translate(ref, e, asking.get(), peers.get() ? peerTimeout : timeout)));
    }

    /**
     * Sources outside this server that a read falls back to when no local source holds the entity: Drishti*net's peers
     * (DRISHTI_NET.md section 7, data forwarding). Not set while the cluster is off, so a single server reads exactly as before.
     */
    public interface Fallback {
        /**
         * The peers to ask for {@code ref}, best first: none when the cluster is off, when the request was itself forwarded (one hop),
         * or when no peer the caller may reach holds the kind. A peer that reads the same table as a local source is never listed.
         */
        List<SourcePlugin> peers(EntityRef ref, AsOf asOf);

        /**
         * Why the entity cannot be read although a peer holds the kind (that peer is down or suspect), or this node cannot tell yet (it is
         * still joining the cluster), or empty.
         */
        default Optional<DrishtiException> unreachable(EntityRef ref) {
            return Optional.empty();
        }

        /**
         * Why this node cannot yet tell which peers hold a kind: it is still joining the cluster and has not had its peers' digests (SOAK3-01).
         * A read nothing here holds is then not "not found". Empty once it has them, and always for a single server.
         */
        default Optional<String> joining(String kind) {
            return Optional.empty();
        }

        /**
         * A grouped page (a derived kind) gathered from every node that holds members of the kind it groups, replacing {@code local} (this
         * node's page, or null when no member is here); empty when there is nothing to gather (not a derived kind, no other node holds
         * members, a forwarded request). DRISHTI_NET.md section 8.
         */
        default Optional<EntityDocument> gather(EntityRef ref, AsOf asOf, EntityDocument local) {
            return Optional.empty();
        }

        /**
         * How long {@link #fetchAll} waits for {@code ref} once its read has gone on to the peers, when {@code local} is its budget on a
         * single server (a view's linked entities, {@code drishti.graph.link-budget}): longer, since a call between servers takes more than
         * a read of this node's own data, so a linked entity a peer holds shows as itself and not as pending. The local sources keep
         * {@code local}. Asked once per kind of a {@link #fetchAll}.
         */
        default Duration budget(EntityRef ref, AsOf asOf, Duration local) {
            return local;
        }

        /**
         * The version of the data the peers a read of {@code ref} would ask hold for its kind (each node's sources' data versions, as their
         * digests carry them), so an answer read through them can be cached under it: a load on a peer changes it. Empty when one of those
         * peers cannot say (its answer is then never cached); an empty string when no peer would be asked.
         */
        default Optional<String> version(EntityRef ref, AsOf asOf) {
            return Optional.of("");
        }

        /** The nodes that hold the kind but cannot be reached now, each with its state ({@code qa1-b (suspect)}); none by default. */
        default List<String> downHolders(String kind) {
            return List.of();
        }

        /**
         * The nodes that hold the kind, cannot be reached now and may hold the business date: by the date range of their sources of the
         * kind as their digest last said before they went down (SOAK-01). A node whose sources hold only other dates is not named for this
         * one. {@link #downHolders(String)} by default.
         */
        default List<String> downHolders(String kind, java.time.LocalDate date) {
            return downHolders(kind);
        }

        /**
         * Whether the peer {@code peer} (one of {@link #peers}) may hold the kind on {@code date}, by the date range of its sources of the
         * kind as its digest says; true when it cannot be told. A peer that failed or cannot be reached counts only for those dates.
         */
        default boolean mayHold(SourcePlugin peer, String kind, java.time.LocalDate date) {
            return true;
        }

        /**
         * The entities of the kind the peers hold, listed there for the caller (the peers a search of the kind asks: not a replica of what
         * this node reads), at most {@code limit} from each, within {@code budget}; a peer that cannot answer is added to {@code failures}.
         * None while the cluster is off or for a forwarded request (QA4-02: a map panel on a node that reads its kind only from a peer).
         */
        default List<EntityHit> list(String kind, int limit, Duration budget, AsOf asOf, SourceFailures failures) {
            return List.of();
        }
    }

    /** {@link Fallback#version} of the peers a read of {@code ref} would ask: an empty string while the cluster is off. */
    public Optional<String> peerVersion(EntityRef ref, AsOf asOf) {
        Fallback fb = fallback;
        return fb == null ? Optional.of("") : fb.version(ref, asOf);
    }

    /** A derived source that serves a kind here, by its connector name: Drishti*net gathers its groups across nodes. */
    public record Derived(String source, com.ash.drishti.engine.source.derived.DerivedSourcePlugin plugin) {}

    /** The derived source (plugin {@code derived}) that serves {@code kind} here, if one does. */
    public Optional<Derived> derivedOf(String kind) {
        for (SourcePlugin p : candidates(kind)) {
            SourcePlugin d = p instanceof ConnectorInstance c ? c.delegate() : p;
            if (d instanceof com.ash.drishti.engine.source.derived.DerivedSourcePlugin x && x.groupedFrom(kind).isPresent()) {
                return Optional.of(new Derived(p.manifest().name(), x));
            }
        }
        return Optional.empty();
    }

    private volatile Fallback fallback;

    private volatile java.util.function.Supplier<String> partition = () -> "";

    /**
     * Who the current read is for, as far as the peers' answers differ (Drishti*net: the caller's security signature; "" outside a cluster).
     * A peer masks and routes for the user it acts for, so a cache of what reads through this router return is shared only within one
     * partition (SOAK2-01).
     */
    public void partition(java.util.function.Supplier<String> p) {
        this.partition = p == null ? () -> "" : p;
    }

    public String partition() {
        return partition.get();
    }

    /** Sets (or, with null, removes) the sources a read falls back to after the local ones. */
    public void fallback(Fallback f) {
        this.fallback = f;
    }

    /**
     * The local sources first, as {@link #readFirst}; when none holds the entity (not found, or no source of the kind here), the peers
     * the fallback names, in its order. A peer that does not hold it passes to the next; one that fails is counted, and the read gives up
     * after two failures (a read is tried once more on the next peer, never more). A local source that failed is never replaced by a
     * peer's data: its failure is the answer, as with two local sources.
     */
    private EntityDocument readWithPeers(EntityRef ref, List<SourcePlugin> candidates, AsOf asOf, AtomicReference<String> asking, Fallback fb,
            java.util.concurrent.atomic.AtomicBoolean toPeers) {
        DrishtiException first;
        if (candidates.isEmpty()) {
            first = noSource(ref.kind());
        } else {
            try {
                EntityDocument d = readFirst(ref, candidates, asOf, asking);
                toPeers.set(true);                                                // what is left is the peers' (a grouped page)
                return fb.gather(ref, asOf, d).orElse(d);
            } catch (SourceFailure e) {
                throw e;
            } catch (DrishtiException e) {
                ErrorCode c = e.errorCode();
                if (c != ErrorCode.ENTITY_NOT_FOUND && c != ErrorCode.NO_SOURCE_FOR_KIND && c != ErrorCode.CONNECTOR_NOT_CONFIGURED) {
                    throw e;
                }
                first = e;
            }
        }
        toPeers.set(true);                                                        // none here holds it: the peers are asked
        if (!candidates.isEmpty()) {
            Optional<EntityDocument> gathered = fb.gather(ref, asOf, null);          // a group with no member here, members elsewhere
            if (gathered.isPresent()) {
                return gathered.get();
            }
        }
        List<SourcePlugin> peers = fb.peers(ref, asOf);
        if (peers.isEmpty()) {
            Optional<DrishtiException> why = fb.unreachable(ref);
            throw why.isPresent() ? why.get() : first;
        }
        int failed = 0;
        SourceFailure failure = null;
        for (SourcePlugin p : peers) {
            String name = p.manifest().name();
            asking.set(name);
            long t0 = System.nanoTime();
            try {
                Optional<EntityDocument> d = p.fetch(ref, asOf);
                if (d.isPresent()) {
                    stats.found(name, System.nanoTime() - t0);
                    return d.get();
                }
                stats.notHeld(name, System.nanoTime() - t0);
            } catch (Exception e) {
                stats.failed(name, System.nanoTime() - t0, e);
                failure = new SourceFailure(ErrorCode.NET_PEER_FAILED, name, SourceFailure.reason(e, props.fetchTimeout()),
                        "the node " + name.replaceFirst("^net:", "") + " that holds " + ref.kind() + " failed reading " + ref
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
                if (++failed >= 2) {
                    break;
                }
            }
        }
        // a peer that failed may hold it: "not found" would be wrong, so its failure is the answer
        if (failure != null) {
            throw failure;
        }
        // every peer asked said "not here", but a node that holds the kind is down: "not found" would be wrong too, so say which
        Optional<DrishtiException> why = fb.unreachable(ref);
        throw why.isPresent() ? why.get() : first;
    }

    /**
     * The order sources are asked in: for a picked date, dated sources first (history comes from them; undated ones only
     * answer what nothing dated holds); for live data, sources that stream first.
     */
    private void readOrder(List<SourcePlugin> candidates, AsOf asOf) {
        if (!asOf.live()) {
            candidates.sort(java.util.Comparator.comparing(p -> !p.manifest().capabilities().dated()));
        } else {
            liveFirst(candidates);
        }
    }

    /** Whether a dated source holds the kind for the date ({@link SourcePlugin#coverage}); undecided when it cannot say. */
    private static DateCoverage coverage(SourcePlugin p, String kind, AsOf asOf) {
        if (!p.manifest().capabilities().dated()) {
            return DateCoverage.UNKNOWN;
        }
        try {
            DateCoverage c = p.coverage(kind, asOf);
            return c == null ? DateCoverage.UNKNOWN : c;
        } catch (RuntimeException e) {
            return DateCoverage.UNKNOWN;                    // its read says what is wrong
        }
    }

    /**
     * True when a read "as known at" an instant cannot be put to this source: it is dated, keeps no earlier versions
     * ({@link SourcePlugin#timeTravel}) and may hold the date. It would answer with today's data (DATA-15).
     */
    private static boolean refusesKnownAt(SourcePlugin p, AsOf asOf, DateCoverage coverage) {
        return asOf.knownAt() != null && p.manifest().capabilities().dated() && !p.timeTravel() && coverage != DateCoverage.NOT_HELD;
    }

    /** Why a source cannot answer a read "as known at" an instant, as shown with the error and with a partial search. */
    static final String NO_VERSIONS = "keeps no earlier versions, so a read 'as known at' an instant cannot be answered from it "
            + "(only stores with time travel, such as Delta Lake or Iceberg, can)";

    /**
     * The first candidate that holds {@code ref} answers. One that does not hold it (an empty answer) passes to the next,
     * unless it holds the date ({@link DateCoverage#HELD}): a store that holds a date is authoritative for it, so an
     * entity it does not list then is not held and the read stops with {@code DRS-1001} naming it (DATA-12). One that
     * fails (throws) stops the read with {@code DRS-1003} naming it: another store's data is never shown in place of the
     * data a failing store holds. The error says why only when the source says so with an {@link UnreadableData} (an
     * unsupported codec, and what to do); other causes go to the log and Admin → Health. A read "as known at" an instant
     * stops with {@code DRS-1007} at a dated store without time travel that may hold the date (DATA-15).
     */
    private EntityDocument readFirst(EntityRef ref, List<SourcePlugin> candidates, AsOf asOf, AtomicReference<String> asking) {
        String heldBy = null;                                  // a union connector that holds the date and does not list ref
        for (SourcePlugin p : candidates) {
            if (heldBy != null && !registry.union(p.manifest().name())) {
                break;                                         // the union holds the date: stores behind it are not asked (DATA-12)
            }
            Optional<EntityDocument> d;
            boolean dated = p.manifest().capabilities().dated();
            String name = p.manifest().name();
            asking.set(name);
            DateCoverage coverage = coverage(p, ref.kind(), asOf);
            if (refusesKnownAt(p, asOf, coverage)) {
                throw new SourceFailure(ErrorCode.NO_TIME_TRAVEL, name, NO_VERSIONS,
                        name + " " + NO_VERSIONS + "; " + ref + " was asked for as known at " + asOf.knownAt(), null);
            }
            long t0 = System.nanoTime();
            try {
                d = dated ? p.fetch(ref, asOf) : p.fetch(ref);
            } catch (Exception e) {
                stats.failed(name, System.nanoTime() - t0, e);
                String detail = UnreadableData.in(e).map(u -> ": " + u.getMessage()).orElse("");
                throw new SourceFailure(ErrorCode.SOURCE_FAILED, name, SourceFailure.reason(e, props.fetchTimeout()),
                        name + " failed reading " + ref + detail, e);
            }
            if (d.isPresent()) {
                stats.found(name, System.nanoTime() - t0);
                return named(d.get(), name);
            }
            stats.notHeld(name, System.nanoTime() - t0);
            if (coverage == DateCoverage.HELD && registry.union(name)) {
                heldBy = heldBy == null ? name : heldBy + ", " + name;   // another source system of the union may hold it
                continue;
            }
            if (coverage == DateCoverage.HELD) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, name + " holds " + ref.kind()
                        + (asOf.businessDate() == null ? "" : " for " + asOf.businessDate()) + " and does not list " + ref);
            }
        }
        if (heldBy != null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, heldBy + " hold " + ref.kind()
                    + (asOf.businessDate() == null ? "" : " for " + asOf.businessDate()) + " and none lists " + ref);
        }
        String routed = routes.get(ref.kind());
        if (routed != null && registry.plugin(routed).isEmpty()) {
            List<String> standIns = registry.standIns(routed);
            if (!standIns.isEmpty()) {
                throw servedInstead(ref, routed, candidates);   // an internet connector that is off: its stand-ins were asked and do not hold it
            }
            if (candidates.isEmpty()) {
                throw noSource(ref.kind());             // the connector the pack routes this kind to is not there: say that, not just "nothing holds it"
            }
            // other connectors serve the kind and were asked (QA2-10): not found, naming them and the routed connector that is off
            var status = registry.connectorStatuses().get(routed);
            String off = status == null ? "is not configured" : "is " + status.state().toLowerCase(java.util.Locale.ROOT);
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no source holds " + ref + " (asked "
                    + String.join(", ", candidates.stream().map(p -> p.manifest().name()).toList()) + "; " + routed + ", which kind '"
                    + ref.kind() + "' is routed to, " + off + ")");
        }
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no source holds " + ref);
    }

    /**
     * Not found, when the kind's routed connector reaches the internet and is off (disabled, or offline mode) and the other connectors
     * that serve the kind (such as a store built from a captured snapshot) were asked instead: that is where the data comes from, so the
     * answer is "they do not hold it", naming them, and when none of them has a table for the kind yet, saying so.
     */
    private DrishtiException servedInstead(EntityRef ref, String routed, List<SourcePlugin> asked) {
        String names = String.join(", ", asked.stream().map(p -> p.manifest().name()).toList());
        boolean noTable = asked.stream().allMatch(p -> {
            try {
                return p.manifest().capabilities().dated() && p.tableIdentity(ref.kind()).isEmpty();
            } catch (RuntimeException e) {
                return false;
            }
        });
        var status = registry.connectorStatuses().get(routed);
        String off = status != null && OfflineMode.STATE.equals(status.state()) ? "off (offline mode)" : "off";
        return new StandInNotHeld("no source holds " + ref + ": connector " + routed + " reaches the internet and is "
                + off + ", so kind '" + ref.kind() + "' is served by " + names + (noTable
                        ? ", which has no table for it yet (build it, for a pack's demo data with drishti.py lake demo)"
                        : ", which does not hold it"));
    }

    /**
     * Not found by the stand-ins of an internet connector that is off: the entity may well exist (a snapshot holds a few), so a link to it
     * is not "missing" (fetchAll leaves it out of the missing set), while a view of it still answers DRS-1001 naming the stand-ins.
     */
    static final class StandInNotHeld extends DrishtiException {
        StandInNotHeld(String message) {
            super(ErrorCode.ENTITY_NOT_FOUND, message);
        }
    }

    /** A document whose connector gave it no source name carries the connector's name, so a view never says "Source: , gen 0". */
    static EntityDocument named(EntityDocument doc, String connector) {
        var pv = doc.provenance();
        if (pv == null || (pv.source() != null && !pv.source().isBlank())) {
            return doc;
        }
        return new EntityDocument(doc.ref(), doc.data(), new com.ash.drishti.api.Provenance(connector, pv.generation(), pv.fetchedAt(), pv.live(), pv.businessDate()), doc.deleted());
    }

    private static Throwable translate(EntityRef ref, Throwable e, String source, Duration timeout) {
        Throwable t = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (t instanceof TimeoutException) {
            return new SourceFailure(ErrorCode.SOURCE_TIMEOUT, source, SourceFailure.reason(t, timeout), "timed out reading " + ref, t);
        }
        return t;
    }

    /** Reads several entities concurrently; entries that fail are absent from the result. */
    public Map<EntityRef, EntityDocument> fetchAll(Collection<EntityRef> refs, Duration budget) {
        return fetchAll(refs, budget, AsOf.LATEST);
    }

    public Map<EntityRef, EntityDocument> fetchAll(Collection<EntityRef> refs, Duration budget, AsOf asOf) {
        return fetchAll(refs, budget, asOf, new SourceFailures());
    }

    /**
     * Reads several entities concurrently; entries that are not held or fail are absent from the result, and the sources
     * that failed or timed out are added to {@code failures} with why.
     */
    public Map<EntityRef, EntityDocument> fetchAll(Collection<EntityRef> refs, Duration budget, AsOf asOf, SourceFailures failures) {
        return fetchAll(refs, budget, asOf, failures, new java.util.HashSet<>());
    }

    /**
     * As above, and the refs that no source holds (as opposed to a source that failed or timed out) are added to
     * {@code missing}, so a caller can say "not found" rather than "waiting for".
     */
    public Map<EntityRef, EntityDocument> fetchAll(Collection<EntityRef> refs, Duration budget, AsOf asOf, SourceFailures failures,
            java.util.Set<EntityRef> missing) {
        Map<EntityRef, CompletableFuture<EntityDocument>> futures = new LinkedHashMap<>();
        Fallback fb = fallback;
        Map<String, Duration> byKind = new java.util.HashMap<>();          // the peers' budget, asked once per kind
        refs.forEach(r -> futures.put(r, fetch(r, budget, asOf,
                fb == null ? budget : byKind.computeIfAbsent(r.kind(), k -> peerBudget(fb, r, asOf, budget)))));
        Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
        futures.forEach((r, f) -> {
            try {
                out.put(r, f.join());
            } catch (CompletionException | DrishtiException e) {
                // a missing link must not fail the caller; a failing source is reported to it
                Throwable t = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                if (t instanceof SourceFailure sf) {
                    failures.add(sf.source(), sf.reason());
                } else if (t instanceof DrishtiException de && de.errorCode() == ErrorCode.ENTITY_NOT_FOUND && !(t instanceof StandInNotHeld)) {
                    missing.add(r);
                }
            }
        });
        return out;
    }

    /**
     * How long one read of {@link #fetchAll} may take once it has gone on to the peers: the fallback's budget, never shorter than the local
     * one. The local sources keep the local budget: an entity held here, or a slow local source, is not waited for any longer.
     */
    private Duration peerBudget(Fallback fb, EntityRef ref, AsOf asOf, Duration budget) {
        try {
            Duration d = fb.budget(ref, asOf, budget);
            return d == null || d.compareTo(budget) < 0 ? budget : d;
        } catch (RuntimeException e) {
            return budget;
        }
    }

    /** What a search across sources found, and the sources that could not answer (source name to why). */
    public record Listing(List<EntityHit> hits, Map<String, String> failed) {}

    /** Searches every search-capable plugin in parallel; slow plugins are dropped after {@code budget}. */
    public List<EntityHit> search(String kind, String text, int limit, Duration budget) {
        return search(kind, text, limit, budget, AsOf.LATEST);
    }

    public List<EntityHit> search(String kind, String text, int limit, Duration budget, AsOf asOf) {
        return list(kind, text, limit, budget, asOf, new SourceFailures()).hits();
    }

    /**
     * Searches as {@link #search} does and says which sources could not answer: one that failed, one still searching
     * after {@code budget}, and one whose listing of the kind is incomplete ({@link SourcePlugin#listingProblem}) are
     * added to {@code failures} with why, so a caller that lists a kind (a structured search) reports its answer as
     * partial, and why, instead of exact and empty.
     */
    public Listing list(String kind, String text, int limit, Duration budget, AsOf asOf, SourceFailures failures) {
        List<CompletableFuture<List<EntityHit>>> parts = new ArrayList<>();
        java.util.Set<String> asked = kind == null ? null : listed(kind, asOf, failures);
        for (SourcePlugin p : registry.plugins()) {
            if (p.manifest().capabilities().search() && (kind == null || p.manifest().serves(kind))) {
                boolean dated = p.manifest().capabilities().dated();
                String name = p.manifest().name();
                if (asked != null && !asked.contains(name)) {
                    continue;
                }
                parts.add(CompletableFuture.supplyAsync(() -> {
                    List<EntityHit> hits = dated ? p.search(kind, text, limit, asOf) : p.search(kind, text, limit);
                    if (kind != null) {
                        p.listingProblem(kind).ifPresent(why -> failures.add(name, why));
                    }
                    return hits;
                }, executor).orTimeout(budget.toMillis(), TimeUnit.MILLISECONDS).exceptionally(e -> {
                    failures.add(name, SourceFailure.reason(e, budget));
                    return List.of();
                }));
            }
        }
        return new Listing(interleave(parts.stream().map(CompletableFuture::join).toList(), limit), failures.asMap());
    }

    /**
     * As {@link #list}, and in a cluster the entities the peers hold too (listed there for the caller), merged as one list of at most
     * {@code limit}, this node's first: what a listing that reads documents through {@link #fetchAll} needs (a map panel's countries).
     * A structured search does not use it: its controller scatters the whole search (DRISHTI_NET.md section 8).
     */
    public Listing listAcross(String kind, int limit, Duration budget, AsOf asOf, SourceFailures failures) {
        Listing local = list(kind, "", limit, budget, asOf, failures);
        Fallback fb = fallback;
        if (fb == null) {
            return local;
        }
        List<EntityHit> theirs = fb.list(kind, limit, budget, asOf, failures);
        if (theirs.isEmpty()) {
            return local;
        }
        return new Listing(interleave(List.of(local.hits(), theirs), limit), failures.asMap());
    }

    /**
     * The hits of several sources as one list of at most {@code limit}: taken in turn, one from each source, so a union's source
     * systems all show in a short list (a type-ahead) instead of the first filling it; an entity two sources list counts once.
     */
    static List<EntityHit> interleave(List<List<EntityHit>> parts, int limit) {
        Map<EntityRef, EntityHit> merged = new LinkedHashMap<>();
        int longest = parts.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest && merged.size() < limit; i++) {
            for (List<EntityHit> part : parts) {
                if (i < part.size()) {
                    merged.putIfAbsent(part.get(i).ref(), part.get(i));
                }
            }
        }
        return merged.values().stream().limit(limit).toList();
    }

    /**
     * The sources whose listing of the kind counts for {@code asOf}, as a read would ask them: in read order, up to and
     * including the first that holds the date (what it does not list then is not held, so the stores behind it are not
     * listed: a search agrees with the views, DATA-12). For a read "as known at" an instant, a dated source without time
     * travel that may hold the date is left out and added to {@code failures} (DATA-15).
     */
    private java.util.Set<String> listed(String kind, AsOf asOf, SourceFailures failures) {
        List<SourcePlugin> ordered = candidates(kind);
        readOrder(ordered, asOf);
        java.util.Set<String> out = new java.util.HashSet<>();
        boolean unionHeld = false;
        for (SourcePlugin p : ordered) {
            DateCoverage coverage = coverage(p, kind, asOf);
            boolean union = registry.union(p.manifest().name());
            if (unionHeld && !union) {
                break;                                     // the union holds the date: what is behind it is not listed
            }
            if (refusesKnownAt(p, asOf, coverage)) {
                failures.add(p.manifest().name(), NO_VERSIONS);
            } else {
                out.add(p.manifest().name());
            }
            if (coverage == DateCoverage.HELD) {
                if (!union) {
                    break;
                }
                unionHeld = true;                          // every source system of the union is listed
            }
        }
        return out;
    }

    /**
     * Subscribes to live updates from the first live plugin that holds {@code ref}. Returns
     * {@link Subscription#NONE} when no source can push it (the view then stays static).
     */
    public Subscription subscribe(EntityRef ref, java.util.function.Consumer<EntityDocument> listener) {
        List<SourcePlugin> live = new ArrayList<>(candidates(ref.kind()).stream().filter(p -> p.manifest().capabilities().live()).toList());
        liveFirst(live);
        // ticks come from the source the view read: the first live source that holds the entity (a real stream before the
        // demo samples, as in fetch); one that holds nothing yet is used only when none does
        for (SourcePlugin p : live) {
            if (holds(p, ref)) {
                Subscription s = p.subscribe(ref, listener);
                if (s != Subscription.NONE) {
                    return s;
                }
            }
        }
        for (SourcePlugin p : live) {                 // a stream that keeps no state but ticks what a store serves
            if (pushes(p, ref)) {
                Subscription s = p.subscribe(ref, listener);
                if (s != Subscription.NONE) {
                    return s;
                }
            }
        }
        Fallback fb = fallback;
        if (fb != null) {                              // Drishti*net: held by no local source, so the peer that holds it streams it
            for (SourcePlugin p : fb.peers(ref, AsOf.LATEST)) {
                if (p.manifest().capabilities().live()) {
                    Subscription s = p.subscribe(ref, listener);
                    if (s != Subscription.NONE) {
                        return s;
                    }
                }
            }
        }
        for (SourcePlugin p : live) {
            Subscription s = p.subscribe(ref, listener);
            if (s != Subscription.NONE) {
                return s;
            }
        }
        return Subscription.NONE;
    }

    /**
     * The sources a whole-kind read takes the kind from: every union connector of the kind (each source system's own
     * entities), else the one a read would ask first.
     */
    private List<SourcePlugin> wholeKindSources(String kind, AsOf asOf) {
        List<SourcePlugin> candidates = candidates(kind);
        if (candidates.isEmpty()) {
            return List.of();
        }
        readOrder(candidates, asOf);
        List<SourcePlugin> union = candidates.stream().filter(p -> registry.union(p.manifest().name())).toList();
        return union.isEmpty() ? List.of(candidates.get(0)) : union;
    }

    /**
     * Every document of the kind on the date, each source read in one pass ({@link SourcePlugin#documents}), one source
     * after the other on the caller's thread (a background job: it takes none of the read slots of single reads). False,
     * having given nothing, when the first source cannot read so; a later one that cannot is an {@link IllegalStateException}
     * (some documents were given: the caller starts again entity by entity).
     */
    public boolean documents(String kind, AsOf asOf, java.util.function.Consumer<EntityDocument> sink) {
        List<SourcePlugin> sources = wholeKindSources(kind, asOf);
        if (sources.isEmpty()) {
            return false;
        }
        boolean given = false;
        for (SourcePlugin p : sources) {
            if (refusesKnownAt(p, asOf, coverage(p, kind, asOf))) {
                if (given) {
                    throw new IllegalStateException(p.manifest().name() + " " + NO_VERSIONS);
                }
                return false;
            }
            String name = p.manifest().name();
            boolean read;
            try {
                read = p.documents(kind, asOf, d -> sink.accept(named(d, name)));
            } catch (Exception e) {
                throw new SourceFailure(ErrorCode.SOURCE_FAILED, name, SourceFailure.reason(e, props.fetchTimeout()),
                        name + " failed reading every " + kind + detailOf(e), e);
            }
            if (!read) {
                if (given) {
                    throw new IllegalStateException(name + " cannot read every " + kind + " in one pass");
                }
                return false;
            }
            given = true;
        }
        return true;
    }

    private static String detailOf(Exception e) {
        return UnreadableData.in(e).map(u -> ": " + u.getMessage()).orElse("");
    }

    /**
     * The version of the data the kind reads on the date, across the sources a whole-kind read takes it from (a load makes a
     * new one); empty when one of them cannot say.
     */
    public Optional<String> dataVersion(String kind, AsOf asOf) {
        List<SourcePlugin> sources = wholeKindSources(kind, asOf);
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        for (SourcePlugin p : sources) {
            Optional<String> v;
            try {
                // a source that holds nothing of the kind (a table not created yet) has nothing to version: "none" until data arrives
                v = p.holdsNone(kind) ? Optional.of("none")
                        : asOf.live() || asOf.businessDate() == null ? p.dataVersion(kind) : p.dataVersion(kind, asOf.businessDate());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
            if (v.isEmpty()) {
                return Optional.empty();
            }
            out.append(out.isEmpty() ? "" : "|").append(p.manifest().name()).append('=').append(v.get());
        }
        return Optional.of(out.toString());
    }

    /**
     * The kind's entities with these paths as columns, from the source a read of the kind would ask first, when it keeps
     * them as columns; empty otherwise (the caller then reads documents). Within {@code budget}.
     */
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf, Duration budget) {
        return columns(kind, paths, asOf, budget, new SourceFailures());
    }

    /**
     * {@link #columns(String, java.util.Collection, AsOf, Duration)}, adding a source that failed to {@code failures} (one
     * that is only slow is not: documents are read instead, which may still answer exactly).
     */
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf, Duration budget,
            SourceFailures failures) {
        List<SourcePlugin> candidates = candidates(kind);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        readOrder(candidates, asOf);
        // sources that keep the paths as columns, in read order (a store of record such as a Delta table or an Aerospike
        // set); sources that only hold documents (samples, a stream of today's changes) are not asked. A source that does
        // not hold the date (recent history in Aerospike, years in Delta) answers empty and the next one is asked; one that
        // holds it is authoritative (DATA-12): without the paths as columns, documents are read rather than the columns of
        // a store behind it. A read "as known at" an instant is not put to a dated source without time travel (DATA-15).
        long deadline = System.nanoTime() + budget.toNanos();
        for (SourcePlugin p : candidates) {
            DateCoverage coverage = coverage(p, kind, asOf);
            if (refusesKnownAt(p, asOf, coverage)) {
                failures.add(p.manifest().name(), NO_VERSIONS);
                return Optional.empty();
            }
            if (!p.columnar(kind).containsAll(paths)) {
                if (coverage == DateCoverage.HELD) {
                    return Optional.empty();
                }
                continue;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return Optional.empty();
            }
            if (registry.union(p.manifest().name())) {
                return unionColumns(kind, paths, asOf, candidates, left, failures);
            }
            try {
                Optional<com.ash.drishti.api.ColumnSet> got = CompletableFuture.supplyAsync(() -> {
                    try {
                        return p.columns(kind, paths, asOf);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, executor).orTimeout(left, TimeUnit.NANOSECONDS).join();
                if (got.isPresent()) {
                    if (got.get().incomplete() != null) {
                        failures.add(p.manifest().name(), got.get().incomplete());   // some entities are missing: partial, and why
                    }
                    return got;
                }
            } catch (CompletionException e) {
                if (!(e.getCause() instanceof TimeoutException)) {
                    failures.add(p.manifest().name(), SourceFailure.reason(e, budget));
                }
                return Optional.empty();                       // too slow or failed: documents are read instead
            }
        }
        return Optional.empty();
    }

    /**
     * The columns of every union connector of the kind, read concurrently and put one after the other (each source system's rows,
     * ids unique across them). Empty, so documents are read instead, when one of them does not keep the paths as columns, fails or
     * is too slow; a failure is added to {@code failures}.
     */
    private Optional<com.ash.drishti.api.ColumnSet> unionColumns(String kind, java.util.Collection<String> paths, AsOf asOf, List<SourcePlugin> candidates,
            long leftNanos, SourceFailures failures) {
        List<SourcePlugin> union = candidates.stream().filter(p -> registry.union(p.manifest().name())).toList();
        if (union.stream().anyMatch(p -> !p.columnar(kind).containsAll(paths))) {
            return Optional.empty();
        }
        List<CompletableFuture<Optional<com.ash.drishti.api.ColumnSet>>> parts = union.stream().map(p -> CompletableFuture.supplyAsync(() -> {
            try {
                return p.columns(kind, paths, asOf);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor).orTimeout(leftNanos, TimeUnit.NANOSECONDS)).toList();
        List<com.ash.drishti.api.ColumnSet> sets = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            try {
                parts.get(i).join().ifPresent(sets::add);
            } catch (CompletionException e) {
                if (!(e.getCause() instanceof TimeoutException)) {
                    failures.add(union.get(i).manifest().name(), SourceFailure.reason(e, Duration.ofNanos(leftNanos)));
                }
                return Optional.empty();
            }
        }
        sets.stream().filter(c -> c.incomplete() != null).forEach(c -> failures.add(kind, c.incomplete()));
        return sets.isEmpty() ? Optional.empty() : Optional.of(com.ash.drishti.api.ColumnSet.concat(sets));
    }

    /** The newest business date any source of the kind holds (empty when none can say). */
    /**
     * The first and last business dates on which {@code ref} has data between {@code from} and {@code to}, from the local dated sources
     * that can list an entity's days cheaply ({@link SourcePlugin#days}); empty when none of them can say or none holds a day (QA1-11).
     */
    public Optional<java.time.LocalDate[]> daysHeld(EntityRef ref, java.time.LocalDate from, java.time.LocalDate to) {
        java.time.LocalDate first = null;
        java.time.LocalDate last = null;
        for (SourcePlugin p : candidates(ref.kind())) {
            if (!p.manifest().capabilities().dated()) {
                continue;
            }
            try {
                Optional<java.util.NavigableSet<java.time.LocalDate>> d = p.days(ref, from, to);
                if (d.isPresent() && !d.get().isEmpty()) {
                    first = first == null || d.get().first().isBefore(first) ? d.get().first() : first;
                    last = last == null || d.get().last().isAfter(last) ? d.get().last() : last;
                }
            } catch (RuntimeException e) {
                // a source that cannot say does not stop the others
            }
        }
        return first == null ? Optional.empty() : Optional.of(new java.time.LocalDate[] {first, last});
    }

    public Optional<java.time.LocalDate> latestDate(String kind) {
        Optional<java.time.LocalDate> out = Optional.empty();
        for (SourcePlugin p : candidates(kind)) {
            try {
                Optional<java.time.LocalDate> d = p.latestDate(kind);
                if (d.isPresent() && (out.isEmpty() || d.get().isAfter(out.get()))) {
                    out = d;
                }
            } catch (RuntimeException e) {
                // a source that cannot say does not stop the others
            }
        }
        return out;
    }

    /**
     * A date's vector column of the kind (a P&amp;L vector per trade, VAR_DATA_MODEL.md section 15) with some text columns, streamed to
     * {@code sink} by the first source in routing order that keeps the column as a vector; false when none does.
     */
    public boolean vectors(String kind, String column, List<String> texts, AsOf asOf, com.ash.drishti.api.VectorSink sink) throws Exception {
        for (SourcePlugin p : candidates(kind)) {
            if (p.vectorColumns(kind).contains(column) && p.vectors(kind, column, texts, asOf, sink)) {
                return true;
            }
        }
        return false;
    }

    /** Column batches of the kind over a date range from the first source in routing order that answers as columns (RUPAKA.md 6.1). */
    public Optional<com.ash.drishti.api.ArrowScan> scan(com.ash.drishti.api.ScanRequest request) throws Exception {
        for (SourcePlugin p : candidates(request.kind())) {
            Optional<com.ash.drishti.api.ArrowScan> s = p.scan(request);
            if (s.isPresent()) {
                return s;
            }
        }
        return Optional.empty();
    }

    /** The paths a source of the kind keeps as columns (the first that keeps any). */
    public java.util.Set<String> columnar(String kind) {
        return candidates(kind).stream().map(p -> p.columnar(kind)).filter(s -> !s.isEmpty()).findFirst().orElse(java.util.Set.of());
    }

    /** How fresh the named source's data is (see {@link SourceRegistry#freshness}). */
    public SourceRegistry.Freshness freshness(String source) {
        return registry.freshness(source);
    }

    /**
     * How fresh a document of this kind is: the source it names when that is a connector, else the connector the kind
     * is routed to first (documents may carry their system of record's name rather than the connector's).
     */
    public SourceRegistry.Freshness freshness(String kind, String source) {
        SourceRegistry.Freshness named = registry.freshness(source);
        if (named.lastUpdate() != null || named.staleAfter() != null) {
            return named;
        }
        List<SourcePlugin> routed = candidates(kind);
        liveFirst(routed);
        return routed.isEmpty() ? named : registry.freshness(routed.get(0).manifest().name());
    }

    /** The connector behind a document of this kind from this source: the one the source names, else the kind's first route. */
    public Optional<SourcePlugin> connectorOf(String kind, String source) {
        Optional<SourcePlugin> named = registry.connector(source);
        if (named.isPresent()) {
            return named;
        }
        List<SourcePlugin> routed = candidates(kind);
        liveFirst(routed);
        return routed.stream().findFirst();
    }

    /**
     * What some source that serves {@code kind} says of its field (a derived kind's formula), for callers that know no
     * provenance (pack lint): the first source in routing order that has a note.
     */
    public Optional<SourcePlugin.FieldNote> describeField(String kind, String field) {
        for (SourcePlugin p : candidates(kind)) {
            Optional<SourcePlugin.FieldNote> n = p.describeField(kind, field);
            if (n.isPresent()) {
                return n;
            }
        }
        return Optional.empty();
    }

    /** Live: sources that stream first, so the view ticks; among them a real stream (Kafka) before the default route. */
    private void liveFirst(List<SourcePlugin> candidates) {
        String fallback = props.defaultRoute();
        candidates.sort(java.util.Comparator.<SourcePlugin, Boolean>comparing(p -> !p.manifest().capabilities().live())
                .thenComparing(p -> p.manifest().capabilities().live() && p.manifest().name().equals(fallback)));
    }

    /**
     * True when some live source pushes updates for {@code ref} although another answers its reads (a Kafka connector
     * in ticks mode behind a lake): the view is live even though the document that answered is not.
     */
    public boolean pushes(EntityRef ref) {
        return candidates(ref.kind()).stream().anyMatch(p -> p.manifest().capabilities().live() && pushes(p, ref));
    }

    private static boolean pushes(SourcePlugin p, EntityRef ref) {
        try {
            return p.pushes(ref);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean holds(SourcePlugin p, EntityRef ref) {
        try {
            return p.fetch(ref).isPresent();          // live sources answer from memory or their own store
        } catch (Exception e) {
            return false;
        }
    }

    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        java.util.LinkedHashSet<EntityRef> out = new java.util.LinkedHashSet<>();     // tens of thousands of referrers: a set, not a list
        for (SourcePlugin p : registry.plugins()) {
            if (p.manifest().capabilities().reverseLookup()) {
                out.addAll(p.manifest().capabilities().dated() ? p.reverse(target, kind, asOf) : p.reverse(target, kind));
            }
        }
        return List.copyOf(out);
    }
}
