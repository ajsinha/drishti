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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.ScenarioFx;
import com.ash.drishti.compute.var.ShardAssembly;
import com.ash.drishti.compute.shard.ShardHandover;
import com.ash.drishti.compute.shard.ShardMap;
import com.ash.drishti.compute.shard.ShardOwners;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.shard.ShardMembership;
import com.ash.drishti.server.shard.ShardPeers;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VaR sharding across Drishti*net (VAR_SHARDING.md): each business date cut into shards along legal entity and desk ({@link ShardMap}),
 * each shard owned by {@code replicas} members chosen by rendezvous hashing over the members that may own shards ({@link ShardOwners}: a
 * pure function of shared inputs, no consensus protocol), each owner building its shards from the lake alone; every member puts the date
 * together from the shards it holds and the others' skeletons ({@link ShardAssembly}: every node's aggregate on every member, to the bit),
 * and a scope's rows are summed by the shards' owners ({@link VarShardRows}). Off unless the market-risk pack's {@code sharding:} block (or
 * {@code drishti.var.sharding.enabled}) switches it on; then it replaces the whole-date load of {@link VarLoader}.
 *
 * <p>The members' view: those alive, sharding on and owning shards; owners are computed from the view that has been the same for
 * {@code stable-seconds} (a member joining or leaving moves shards only once the membership settles); a draining member leaves it at once.
 * Every {@code rebalance-seconds} this member builds the shards it owns of the dates in use and holds no version of (announcing each ready
 * in its digest), releases what {@link ShardHandover} says it may, and puts together again a date of its own whose shards' owners have
 * moved on (a new owner, newer data, a new cut), so it never reads a shard nobody serves any more.
 *
 * <p>Putting a date together again never drops the date loaded: the replacement is put together beside it and swapped in, in one step, once
 * complete ({@link #rebuild}). Until then the date loaded answers (every node's aggregate, the shards held here, the others' rows at the
 * data it was put together from); when a shard has no live owner the replacement cannot be completed and only the scopes that need that
 * shard's rows are refused ({@code DRS-5215}). A book the date loaded reads is released only once a replacement no longer reads it.
 */
final class VarShards implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VarShards.class);

    /** A date put together here: its shard map, and per shard the data it was put together from and whether its rows are here. */
    private record Assembled(LocalDate date, String ccy, ShardMap map, String[] digests, boolean[] held) {}

    private final VarShardingProperties own;
    private final int hotDates;
    private final Supplier<VarSharding> settings;
    private final VarLoader loader;
    private final VarShardBooks books;
    private final ShardPeers peers;
    private final VarAccess access;
    private final ParallelSums sums;
    private final VarShardRows rows;
    private final Map<String, ShardMap> maps = new ConcurrentHashMap<>();
    private final Map<String, Assembled> assembled = new ConcurrentHashMap<>();
    private final Map<VarShardBooks.Key, Long> successorSince = new ConcurrentHashMap<>();
    private final Map<String, Long> missingSince = new ConcurrentHashMap<>();
    /** What each date put together here was put together from, by its book: a conversion reads its own USD date's. */
    private final Map<VarBook, Assembled> byBook = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Books handed over that a date loaded here still reads: released once a replacement put together without them is swapped in. */
    private final Set<VarShardBooks.Key> leaving = ConcurrentHashMap.newKeySet();
    private final Map<LocalDate, CompletableFuture<Boolean>> rebuilding = new ConcurrentHashMap<>();
    private volatile boolean draining;
    private final ShardMembership membership;
    private volatile Swap swap = (d, build) -> false;
    private volatile Supplier<Set<LocalDate>> openDates = Set::of;
    /** When each date was last seen open on any member (epoch ms): a date stays in use for {@code release-after} after that. */
    private final Map<LocalDate, Long> lastOpen = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Thread loop;
    private final java.util.concurrent.locks.ReentrantLock tickLock = new java.util.concurrent.locks.ReentrantLock();

    VarShards(VarProperties props, VarShardingProperties own, Supplier<VarSharding> settings, VarLoader loader, ShardPeers peers,
            VarAccess access, ParallelSums sums) {
        this.own = own;
        this.hotDates = props.hotDates();
        this.settings = settings;
        this.loader = loader;
        this.books = new VarShardBooks(loader, props, sums);
        this.peers = peers;
        this.access = access;
        this.sums = sums;
        this.draining = own.drain();
        this.rows = new VarShardRows(this);
        this.membership = new ShardMembership(peers, this::state, own::stableSeconds, own.zoneTag(), own.pins());
        peers.publish(() -> on() ? state().json() : "");
    }

    /** How the service replaces a date loaded with one put together again ({@link VarService#swap}). */
    interface Swap {
        /** Builds the replacement ({@code build}) and swaps it in when the date loaded is still the one it replaces; false when not. */
        boolean swap(LocalDate date, Supplier<VarDate> build);
    }

    /** How dates put together here are replaced and listed (wired by {@link VarService}). */
    void wire(Swap swap, Supplier<Set<LocalDate>> openDates) {
        this.swap = swap;
        this.openDates = openDates;
    }

    /** True when sharding is on now. */
    boolean on() {
        return settings.get().enabled();
    }

    VarSharding settings() {
        return settings.get();
    }

    VarShardingProperties own() {
        return own;
    }

    ShardPeers peers() {
        return peers;
    }

    VarShardBooks books() {
        return books;
    }

    ParallelSums sums() {
        return sums;
    }

    VarLoader loader() {
        return loader;
    }

    VarShardRows rows() {
        return rows;
    }

    /** This member may own shards now: sharding on, not coordinator-only, not draining. */
    boolean ownerCapable() {
        return on() && !own.coordinatorOnly() && !draining;
    }

    // ------------------------------------------------------------------------------------------------------------ the shard map

    /** The date's shard map at its current data version: cut from its rows per key, the same on every member reading that version. */
    ShardMap map(LocalDate date) {
        VarSharding cfg = settings.get();
        String version = loader.version(date);
        String key = date + "|" + version + "|" + cfg.key() + "|" + cfg.targetBytes() + "|" + cfg.kinds();
        ShardMap m = maps.get(key);
        if (m != null) {
            return m;
        }
        int width = Math.max(1, loader.width(date));
        ShardMap made = loader.keyCounts(date, cfg.key(), cfg.kinds()).map(c -> ShardMap.cut(date, version, cfg.key(), c,
                VarSharding.MEASURE.bytesPerRow(width), cfg.targetBytes()))
                .orElseGet(() -> ShardMap.whole(date, version, cfg.key(), cfg.targetBytes(), "the trades' source cannot read " + cfg.key()
                        + " as columns, so the date is one shard (owned by " + cfg.replicas() + " members)"));
        if (maps.size() > 64) {
            maps.clear();
        }
        maps.put(key, made);
        return made;
    }

    // ------------------------------------------------------------------------------------------------------------ the members

    /** This member's state as it publishes it: on, owner, draining, and per date the shards ready here with their data. */
    ShardPeers.State state() {
        Map<LocalDate, List<ShardPeers.Ready>> dates = new TreeMap<>();
        books.readyUsd().forEach((date, byMap) -> byMap.forEach((map, ready) -> dates.computeIfAbsent(date, x -> new ArrayList<>())
                .add(new ShardPeers.Ready(map, ready))));
        return new ShardPeers.State(on(), !own.coordinatorOnly(), draining, dates, Set.copyOf(openDates.get()));
    }

    List<ShardPeers.Peer> members() {
        return membership.members();
    }

    /** The members that may own shards now ({@link ShardMembership}). */
    List<ShardOwners.Candidate> observe() {
        return membership.view();
    }

    /** Forgets the view looked at last (a drain; a test that moves members). */
    void refresh() {
        membership.refresh();
    }

    /** The owners of a shard now, first owner first. */
    List<String> owners(ShardMap.Shard s) {
        return membership.owners(s, settings.get().replicas());
    }

    // ------------------------------------------------------------------------------------------------------------ putting a date together

    /** A sharded date in USD: the shards held here and the others' skeletons put together; the day's updates applied as one server does. */
    VarDate assemble(LocalDate date) {
        VarDate vd = build(date);
        adopt(vd);
        return vd;
    }

    /** The date loaded here is now {@code usd}: its other currencies are put together again from it when asked. */
    void adopt(VarDate usd) {
        Assembled a = byBook.get(usd.book().morning());
        assembled.keySet().removeIf(k -> k.startsWith(usd.date() + "|"));
        missingSince.keySet().removeIf(k -> k.startsWith(usd.date() + "|"));
        if (a != null) {
            assembled.put(usd.date() + "|" + VarDate.USD, a);
        }
    }

    /** {@link #assemble} without making it the date loaded here: a replacement put together beside it. */
    private VarDate build(LocalDate date) {
        long t0 = System.nanoTime();
        ShardMap map = map(date);
        List<ShardAssembly.Part> parts = parts(map, VarDate.USD);
        AsOf asOf = AsOf.of(date);
        Hierarchies hs = loader.effective(date, loader.hierarchies());
        ShardAssembly.Nodes nodes = loader.nodeRows(asOf, hs, loader.limits(asOf, hs));
        VarBook book = book(date, map, VarDate.USD, parts, nodes, t0);
        List<VarIntake.Summary> intakes = new ArrayList<>();
        Map<String, Object> fx = new LinkedHashMap<>();
        for (ShardAssembly.Part p : parts) {
            VarShardIntake.Meta m = VarShardIntake.read(p.meta());
            intakes.add(m.intake());
            fx.putAll(m.fx());
        }
        VarIntake.Summary sum = VarShardIntake.merge(date, book.scenarioSet(), intakes);
        Map<String, String> versions = new LinkedHashMap<>(loader.versions(date));
        versions.put("scenarioSet", book.scenarioSet());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        VarDate vd = new VarDate(date, book, loader.portfolios(date), loader.version(date), Instant.now(), ms, 0, sum, versions, List.of(),
                VarDate.USD, fx);
        try {                                                          // the day's updates read and applied here, as one server does
            return loader.withIntraday(vd, loader.readIntraday(date, book, Set.of(), sum), book.scenarioDates());
        } catch (RuntimeException e) {
            LOG.warn("VaR: the intraday updates of {} could not be read: {}", date, e.getMessage());
            return vd.warned(VarLoader.intradayUnread(date, e));       // said on every answer, never a silent morning run
        }
    }

    /** The date in a reporting currency: each shard's book in it (built by its owners from theirs) put together; the morning run. */
    VarDate convert(VarDate usd, String ccy) {
        long t0 = System.nanoTime();
        LocalDate date = usd.date();
        VarBook ub = usd.book().morning();
        ScenarioFx fxs = loader.fx(date, ub.scenarioDates());
        ScenarioFx.Rates rep = fxs.rates(ccy).orElseThrow(() -> new DrishtiException(ErrorCode.VAR_NO_FX, "no " + ccy + " FX for the scenarios of "
                + date + "; the currencies with FX on the date: " + String.join(", ", fxs.currencies())));
        Assembled a = byBook.get(ub);                                  // the data this USD date was put together from, swapped in or not
        ShardMap map = a != null ? a.map() : map(date);
        List<ShardAssembly.Part> parts = parts(map, ccy, a == null ? null : a.digests());
        VarBook book = book(date, map, ccy, parts, ShardAssembly.Nodes.of(ub, rep.base()), t0);
        if (a != null && assembled.get(date + "|" + VarDate.USD) == a) {
            assembled.put(date + "|" + ccy, byBook.get(book));           // a currency of the date loaded (not of one replaced meanwhile)
        }
        int withoutPv = 0;
        for (ShardAssembly.Part p : parts) {
            withoutPv += VarShardIntake.read(p.meta()).withoutPv();
        }
        Map<String, VarDate.Portfolio> portfolios = new LinkedHashMap<>();
        usd.portfolios().forEach((id, p) -> portfolios.put(id, new VarDate.Portfolio(p.id(), p.name(), p.type(), p.owner(), p.scope(),
                Double.isNaN(p.limit()) ? Double.NaN : p.limit() / rep.base())));
        Map<String, Object> fxUsed = new LinkedHashMap<>(usd.fx());
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("source", rep.source());
        mine.put("base", rep.base());
        mine.put("reporting", true);
        if (withoutPv > 0) {
            mine.put("tradesWithoutPv", withoutPv);
        }
        fxUsed.put(ccy, mine);
        Map<String, String> versions = new LinkedHashMap<>(usd.versions());
        versions.put("fx", loader.fxVersions(date));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new VarDate(date, book, portfolios, usd.version(), Instant.now(), ms, 0, usd.intake(), versions, List.of(), ccy, fxUsed);
    }

    private VarBook book(LocalDate date, ShardMap map, String ccy, List<ShardAssembly.Part> parts, ShardAssembly.Nodes nodes, long t0) {
        String[] digests = new String[parts.size()];
        boolean[] held = new boolean[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            digests[i] = VarShardScope.data(parts.get(i).range(), parts.get(i).digest());
            held[i] = parts.get(i).rows() != null;
        }
        VarBook b;
        try {
            b = ShardAssembly.assemble(date, parts, nodes, rows.of(date, ccy, map, digests), (System.nanoTime() - t0) / 1_000_000);
        } catch (ShardAssembly.NotShardable e) {
            throw new DrishtiException(ErrorCode.VAR_NOT_SHARDABLE, "the VaR inputs of " + date + " cannot be sharded: " + e.getMessage());
        } catch (VarBook.DuplicateTrade e) {
            throw new DrishtiException(ErrorCode.VAR_DUPLICATE_TRADE, "the P&L vectors of " + date + " are not loaded: " + e.getMessage());
        }
        byBook.put(b, new Assembled(date, ccy, map, digests, held));
        return b;
    }

    private List<ShardAssembly.Part> parts(ShardMap map, String ccy) {
        return parts(map, ccy, null);
    }

    /**
     * Every shard of the map as a part of the date: built (or opened) here when this member owns it or holds it, else its skeleton from a
     * member that does; {@code digests}: the data each must be (a currency of a date put together: its USD shards'), else the data now.
     */
    private List<ShardAssembly.Part> parts(ShardMap map, String ccy, String[] digests) {
        List<CompletableFuture<ShardAssembly.Part>> fs = new ArrayList<>();
        for (ShardMap.Shard s : map.shards()) {
            VarShardScope scope = new VarShardScope(map, s.index());
            String want = digests != null ? digests[s.index()] : loader.shardDigest(map.date(), scope);
            fs.add(CompletableFuture.supplyAsync(() -> part(scope, ccy, want), rows.ask()));
        }
        List<ShardAssembly.Part> out = new ArrayList<>();
        for (CompletableFuture<ShardAssembly.Part> f : fs) {
            try {
                out.add(f.join());
            } catch (java.util.concurrent.CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
            }
        }
        return out;
    }

    private ShardAssembly.Part part(VarShardScope scope, String ccy, String digest) {
        VarShardBooks.Key k = new VarShardBooks.Key(scope.map().date(), ccy, scope.map().digest(), scope.index(), digest);
        if (!leaving.contains(k.usd()) && (books.held(k).isPresent() || ownerCapable() && owners(scope.shard()).contains(peers.self()))) {
            return ShardAssembly.Part.of(scope.index(), served(scope.map().date(), ccy, scope.index(), scope.map().digest(), digest));
        }
        return rows.skeleton(scope, ccy, digest);
    }

    // ------------------------------------------------------------------------------------------------------------ an owner's side

    /**
     * A shard's book on this member for a request (a peer's partial sum or skeleton, or this member's own): held with exactly that data, or
     * built now when this member may own shards and the lake holds that data now; refused ({@code DRS-5216}) when it holds another shard map
     * or another data version of the shard than asked.
     */
    VarBook served(LocalDate date, String ccy, int index, String mapDigest, String digest) {
        if (!on()) {
            throw new DrishtiException(ErrorCode.VAR_SHARD_STALE, "VaR sharding is off on node " + peers.self());
        }
        VarShardBooks.Key k = new VarShardBooks.Key(date, ccy, mapDigest, index, digest);
        java.util.Optional<VarBook> held = books.held(k);
        if (held.isPresent()) {
            return held.get();
        }
        ShardMap map = map(date);
        if (!map.digest().equals(mapDigest)) {
            throw new DrishtiException(ErrorCode.VAR_SHARD_STALE, "node " + peers.self() + " cuts " + date + " into another shard map (data version "
                    + map.version() + "): the asking member reads the date again");
        }
        if (index < 0 || index >= map.size()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "shard " + index + " is not in the map of " + date + " (" + map.size() + " shards)");
        }
        VarShardScope scope = new VarShardScope(map, index);
        String now = loader.shardDigest(date, scope);
        if (!now.equals(digest)) {
            throw new DrishtiException(ErrorCode.VAR_SHARD_STALE, "node " + peers.self() + " reads S" + index + " of " + date + " at another data "
                    + "version than asked: the older one is not held here any more, or the asking member reads the date again");
        }
        if (!ownerCapable()) {
            throw new DrishtiException(ErrorCode.VAR_SHARD_STALE, "node " + peers.self() + " does not hold S" + index + " of " + date
                    + (own.coordinatorOnly() ? " and owns no shard (coordinator-only)" : draining ? " and is draining" : ""));
        }
        boolean had = books.ready(k.usd());
        VarBook b = books.book(scope, ccy, digest);
        if (!had) {
            peers.announce();                                          // ready for this shard: the peers hear of it in the next round
        }
        return b;
    }

    /** The view a person has of a shard book (the owner applies their rights to the rows asked, VAR_GUIDE.md section 9). */
    VarAccess.View view(Principal p, VarBook shard, String ccy) {
        VarDate d = new VarDate(shard.businessDate(), shard, Map.of(), "shard:" + VarShardRows.digestOf(shard), Instant.now(), 0, 0, null, Map.of(),
                List.of(), ccy, Map.of());
        return access.view(p, d);
    }

    // ------------------------------------------------------------------------------------------------------------ handover

    /** Starts the rebalancing loop (every {@code rebalance-seconds}). */
    VarShards start() {
        Thread t = Thread.ofVirtual().name("drishti-var-shards").unstarted(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(own.rebalanceSeconds() * 1000L);
                    if (on()) {
                        tick();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOG.warn("VaR shards: a rebalancing round failed: {}", e.toString());
                }
            }
        });
        loop = t;
        t.start();
        return this;
    }

    /**
     * The dates in use: open here or on any member now (a member older than 2.9.7 says only what it holds, which then counts as open), or
     * open somewhere within {@code release-after}. A date only held, which nobody has opened for that long, is not in use: its books are
     * released for age ({@link #releaseIdle}), so two owners never keep each other's dates for ever (soak audit H9).
     */
    Set<LocalDate> activeDates() {
        long now = System.currentTimeMillis();
        Set<LocalDate> open = new TreeSet<>(openDates.get());
        for (ShardPeers.Peer p : members()) {
            open.addAll(p.state().openOrHeld());
        }
        open.forEach(d -> lastOpen.put(d, now));
        long keep = own.releaseAfterSeconds() * 1000L;
        lastOpen.entrySet().removeIf(e -> now - e.getValue() > keep && !open.contains(e.getKey()));
        return new TreeSet<>(lastOpen.keySet());
    }

    /**
     * Releases the books of dates no longer in use ({@link #activeDates}) and not among the lake's newest {@code hot-dates}, unused here for
     * {@code release-after}: unmapped, their hot files kept on disk for the disk budget to trim (a later open maps them again).
     */
    int releaseIdle(Set<LocalDate> active) {
        Set<LocalDate> newest = new TreeSet<>();
        LocalDate d = loader.latest();
        while (d != null && newest.size() < hotDates) {
            newest.add(d);
            d = loader.previous(d);
        }
        return releaseIdle(active, newest);
    }

    /** {@link #releaseIdle(Set)} with the newest dates given. */
    int releaseIdle(Set<LocalDate> active, Set<LocalDate> newest) {
        long now = System.currentTimeMillis();
        long keep = own.releaseAfterSeconds() * 1000L;
        int n = 0;
        for (VarShardBooks.Key k : books.keys()) {
            if (k.ccy().equals(VarDate.USD) && !active.contains(k.date()) && !newest.contains(k.date()) && now - books.lastUsed(k) > keep
                    && !readHere(k)) {
                books.release(k, false);
                LOG.info("VaR shards: {} released S{} of {} (no member has had the date open for {} s)", peers.self(), k.index(), k.date(),
                        own.releaseAfterSeconds());
                n++;
            }
        }
        return n;
    }

    /**
     * One rebalancing round: build what this member owns and holds no version of, release what is handed over, refresh its own dates. Nothing
     * moves until the membership has been stable for {@code stable-seconds} once.
     */
    void tick() {
        if (!tickLock.tryLock()) {
            return;
        }
        try {
            List<ShardOwners.Candidate> view = observe();
            if (!membership.settled()) {
                refreshOwnDates();
                return;
            }
            boolean changed = false;
            Set<LocalDate> active = activeDates();
            for (LocalDate date : active) {
                ShardMap map;
                try {
                    map = map(date);
                } catch (RuntimeException e) {
                    continue;
                }
                for (ShardMap.Shard s : map.shards()) {
                    if (ownerCapable() && !view.isEmpty() && owners(s).contains(peers.self()) && !holdsAny(date, map.digest(), s.index())) {
                        VarShardScope scope = new VarShardScope(map, s.index());
                        books.book(scope, VarDate.USD, loader.shardDigest(date, scope));   // newly owned: built from the lake, then announced
                        changed = true;
                    }
                }
            }
            if (changed) {
                peers.announce();
            }
            if (release() | releaseIdle(active) > 0) {
                peers.announce();
            }
            refreshOwnDates();
        } finally {
            tickLock.unlock();
        }
    }

    private boolean holdsAny(LocalDate date, String map, int index) {
        for (VarShardBooks.Key k : books.keys()) {
            if (k.ccy().equals(VarDate.USD) && k.date().equals(date) && k.map().equals(map) && k.index() == index) {
                return true;
            }
        }
        return false;
    }

    /** Releases the USD books {@link ShardHandover} says this member may (a date of its own that reads one is dropped first). */
    private boolean release() {
        boolean any = false;
        long now = System.currentTimeMillis();
        leaving.removeIf(k -> !books.ready(k));                         // gone another way: built again here only when owned again
        Map<String, ShardPeers.Peer> byNode = new TreeMap<>();
        members().forEach(p -> byNode.put(p.node(), p));
        for (VarShardBooks.Key k : books.keys()) {
            if (!k.ccy().equals(VarDate.USD) || !books.ready(k)) {
                continue;
            }
            ShardMap map;
            try {
                map = map(k.date());
            } catch (RuntimeException e) {
                continue;
            }
            boolean staleMap = !map.digest().equals(k.map());
            List<String> owners = staleMap ? List.of() : owners(map.shard(k.index()));
            boolean owner = !staleMap && ownerCapable() && owners.contains(peers.self());
            VarShardBooks.Key newest = newest(k);
            boolean current = newest.equals(k);
            boolean successor = !current && books.ready(newest)
                    || !owner && !owners.isEmpty() && owners.stream().allMatch(n -> {
                        ShardPeers.Peer p = byNode.get(n);
                        return p != null && !p.self() && p.routable() && p.state().ready(k.date(), k.map(), k.index(), null);
                    });
            ShardHandover.Facts f = new ShardHandover.Facts(owner, current, staleMap, successor);
            if (owner && current && !staleMap || !staleMap && !successor) {
                successorSince.remove(k);
                leaving.remove(k);
                continue;
            }
            long since = successorSince.computeIfAbsent(k, x -> now);
            if (ShardHandover.decide(f, since, now, own.graceSeconds() * 1000L) == ShardHandover.Verdict.RELEASE) {
                if (readHere(k)) {                                      // the date loaded reads it: put together again without it and
                    leaving.add(k);                                     // swapped in, released in a later round (the date is never dropped)
                    rebuild(k.date());
                    continue;
                }
                books.release(k, true);
                leaving.remove(k);
                successorSince.remove(k);
                any = true;
                LOG.info("VaR shards: {} released S{} of {} ({})", peers.self(), k.index(), k.date(), staleMap ? "a shard map since replaced"
                        : !current ? "newer data of it is ready here" : "its owners have had it ready for " + own.graceSeconds() + " s");
            }
        }
        return any;
    }

    /** True when a date loaded here reads the book {@code k} (that shard at that data version, held here). */
    private boolean readHere(VarShardBooks.Key k) {
        for (Assembled a : assembled.values()) {
            if (a.date().equals(k.date()) && a.map().digest().equals(k.map()) && a.held()[k.index()] && k.digest().equals(a.digests()[k.index()])) {
                return true;
            }
        }
        return false;
    }

    /** True when the book {@code k} is handed over: no date is put together here from it any more. */
    boolean leaving(VarShardBooks.Key k) {
        return leaving.contains(k.usd());
    }

    /** The newest USD book held here of the same date, map and shard as {@code k}. */
    private VarShardBooks.Key newest(VarShardBooks.Key k) {
        VarShardBooks.Key best = k;
        for (VarShardBooks.Key o : books.keys()) {
            if (o.ccy().equals(VarDate.USD) && o.date().equals(k.date()) && o.map().equals(k.map()) && o.index() == k.index()
                    && books.builtAt(o) > books.builtAt(best)) {
                best = o;
            }
        }
        return best;
    }

    /**
     * A date put together here is put together again when a shard it reads from others is no longer served at the data it was put together
     * from (its owners announce newer data, or nobody announces it) or the date's shard map changed: before the old holder releases it.
     */
    private void refreshOwnDates() {
        Set<LocalDate> open = openDates.get();
        long now = System.currentTimeMillis();
        List<ShardPeers.Peer> ps = members();
        for (Assembled a : new ArrayList<>(assembled.values())) {
            String key = a.date() + "|" + a.ccy();
            if (!open.contains(a.date())) {
                assembled.remove(key);
                missingSince.remove(key);
                continue;
            }
            boolean move = false;
            try {
                move = !map(a.date()).digest().equals(a.map().digest());
            } catch (RuntimeException e) {
                // the map cannot be read now: looked at again next round
            }
            for (int i = 0; i < a.digests().length && !move; i++) {
                if (a.held()[i]) {
                    continue;
                }
                int index = i;
                String d = a.digests()[i];
                boolean served = ps.stream().anyMatch(p -> p.routable() && p.state().ready(a.date(), a.map().digest(), index, d));
                List<String> owners = owners(a.map().shard(i));
                boolean ownersMoved = !owners.isEmpty() && owners.stream().allMatch(n -> ps.stream().anyMatch(p -> p.node().equals(n) && p.routable()
                        && p.state().held(a.date(), a.map().digest(), index) != null && !p.state().ready(a.date(), a.map().digest(), index, d)));
                move = !served || ownersMoved;
            }
            if (!move) {
                missingSince.remove(key);
                continue;
            }
            long since = missingSince.computeIfAbsent(key, x -> now);
            if (now - since >= Math.min(5_000L, own.graceSeconds() * 1000L / 2)) {
                LOG.info("VaR shards: {} puts {} together again: its shards' owners serve newer data or another cut, or none is served",
                        peers.self(), a.date());
                rebuild(a.date());                                      // the date loaded answers until the replacement is complete
                missingSince.remove(key);
            }
        }
    }

    /**
     * Puts a date loaded here together again on another thread and swaps it in once complete; the date loaded answers meanwhile and stays
     * when the replacement cannot be completed (a shard without a live owner: tried again in a later round). One at a time per date; the
     * future says whether it was swapped in (false: the date was not loaded, or reloaded meanwhile), or why it could not be completed.
     */
    CompletableFuture<Boolean> rebuild(LocalDate date) {
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        CompletableFuture<Boolean> running = rebuilding.putIfAbsent(date, mine);
        if (running != null) {
            return running;
        }
        CompletableFuture.runAsync(() -> {
            boolean swapped = false;
            RuntimeException failed = null;
            try {
                swapped = swap.swap(date, () -> build(date));
            } catch (RuntimeException e) {
                failed = e;
                LOG.info("VaR shards: {} keeps {} as put together: its replacement is not complete ({})", peers.self(), date, e.getMessage());
            } finally {
                rebuilding.remove(date, mine);
            }
            if (failed != null) {
                mine.completeExceptionally(failed);
            } else {
                mine.complete(swapped);
            }
        }, rows.ask());
        return mine;
    }

    /** Puts a date loaded here together again, soon (its shards are held at another data version than it was put together from). */
    void dropLater(LocalDate date) {
        rebuild(date);
    }

    /** A date dropped by its service (a reload, an eviction): forgotten here too. */
    void forget(LocalDate date) {
        assembled.keySet().removeIf(k -> k.startsWith(date + "|"));
    }

    /** Drains this member (it hands its shards over and takes none) or takes it back; the state now. */
    Map<String, Object> drain(boolean on) {
        draining = on;
        refresh();
        peers.announce();
        LOG.info("VaR shards: {} {}", peers.self(), on ? "draining: its shards go to other owners" : "takes shards again");
        return describe(null);
    }

    boolean draining() {
        return draining;
    }

    /** True when this member holds no shard: a draining member may stop. */
    boolean empty() {
        return books.keys().isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------------ status

    /** The sharding state for {@code GET /api/v1/var/shards}: the settings, the members, the books held, and a date's map and owners. */
    Map<String, Object> describe(LocalDate date) {
        Map<String, Object> m = new LinkedHashMap<>(settings.get().describe());
        m.put("self", peers.self());
        m.put("clustered", peers.clustered());
        m.put("coordinatorOnly", own.coordinatorOnly());
        m.put("draining", draining);
        m.put("safeToStop", draining && empty());
        m.put("stableSeconds", own.stableSeconds());
        m.put("graceSeconds", own.graceSeconds());
        if (!own.zoneTag().isBlank()) {
            m.put("zoneTag", own.zoneTag());
        }
        if (!own.pins().isEmpty()) {
            m.put("pins", own.pins());
        }
        List<ShardOwners.Candidate> view = on() ? observe() : List.of();
        List<ShardPeers.Peer> ps = on() ? members() : List.of();
        List<Map<String, Object>> ms = new ArrayList<>();
        for (ShardPeers.Peer p : ps) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("node", p.node());
            x.put("self", p.self());
            x.put("routable", p.routable());
            x.put("on", p.state().on());
            x.put("owner", p.state().owner());
            x.put("draining", p.state().draining());
            x.put("inView", view.stream().anyMatch(c -> c.node().equals(p.node())));
            if (!p.zone().isEmpty()) {
                x.put("zone", p.zone());
            }
            Map<String, Object> ready = new TreeMap<>();
            p.state().dates().forEach((d, rs) -> ready.put(d.toString(), rs.stream().map(r -> Map.of("map", r.map().substring(0,
                    Math.min(16, r.map().length())), "ready", r.ready())).toList()));
            x.put("ready", ready);
            ms.add(x);
        }
        m.put("members", ms);
        m.put("held", books.describe());
        if (date != null && on()) {
            ShardMap map = map(date);
            Map<String, Object> d = map.describe();
            List<Map<String, Object>> shards = new ArrayList<>();
            for (ShardMap.Shard s : map.shards()) {
                Map<String, Object> x = s.describe();
                x.put("owners", owners(s));
                List<String> ready = new ArrayList<>();
                for (ShardPeers.Peer p : ps) {
                    if (p.state().ready(date, map.digest(), s.index(), null)) {
                        ready.add(p.node());
                    }
                }
                x.put("ready", ready);
                shards.add(x);
            }
            d.put("shards", shards);
            m.put("map", d);
        }
        return m;
    }

    @Override
    public void close() {
        Thread t = loop;
        if (t != null) {
            t.interrupt();
        }
        peers.publish(null);
        books.close();
    }
}
