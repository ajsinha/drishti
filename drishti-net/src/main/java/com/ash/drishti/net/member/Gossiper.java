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
package com.ash.drishti.net.member;

import com.ash.drishti.net.config.GossipSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SWIM-style membership over HTTP (DRISHTI_NET.md section 5). Each {@link #round()} raises this node's heartbeat, does a
 * push-pull exchange with {@code fanout} random members, probes members whose heartbeat stopped rising (directly, then
 * through {@code indirect-probes} others), turns long suspects dead and forgets long-gone members. A node that knows no
 * live member contacts its join list (seeds, then the members its {@code peers.json} names) until one exchange succeeds.
 *
 * <p>One round at a time (the scheduler calls {@link #round()}); exchanges and probes within a round run in parallel on the
 * given executor and are cut at the round interval. Tests drive rounds by hand with a fake {@link NetClock}.
 */
public final class Gossiper {

    private static final Logger LOG = LoggerFactory.getLogger(Gossiper.class);

    /** What a round did, for the metrics and the tests. */
    public record Round(int exchanged, int failed, List<String> suspected, List<String> dead, List<String> forgotten, MemberTable.Changes changes) {}

    /** Told of joins and departures (to save peers.json at once and to pull digests). */
    public interface Listener {
        void changed(MemberTable.Changes changes);
    }

    private final MemberTable table;
    private final Supplier<GossipSettings> settings;
    private final GossipTransport transport;
    private final NetClock clock;
    private final Random random;
    private final Executor executor;
    private volatile Supplier<List<String>> joinList = List::of;
    private volatile Listener listener = c -> { };
    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong exchangesFailed = new AtomicLong();

    public Gossiper(MemberTable table, Supplier<GossipSettings> settings, GossipTransport transport, NetClock clock, Random random, Executor executor) {
        this.table = table;
        this.settings = settings;
        this.transport = transport;
        this.clock = clock;
        this.random = random;
        this.executor = executor;
    }

    /** Where a node that knows no live member looks: the seeds, then the members of peers.json (alive ones first). */
    public void joinList(Supplier<List<String>> urls) {
        this.joinList = urls == null ? List::of : urls;
    }

    public void listener(Listener l) {
        this.listener = l == null ? c -> { } : l;
    }

    public MemberTable table() {
        return table;
    }

    public long rounds() {
        return rounds.get();
    }

    public long exchangesFailed() {
        return exchangesFailed.get();
    }

    /** One gossip round. Never throws: a failing peer is counted, the round goes on. */
    public Round round() {
        GossipSettings s = settings.get();
        rounds.incrementAndGet();
        table.updateSelf(m -> m.withHeartbeat(m.heartbeat() + 1));
        List<MemberTable.Changes> changes = Collections.synchronizedList(new ArrayList<>());
        int[] counts = exchange(pickTargets(s.fanout()), s, changes);
        if (table.reachable().isEmpty()) {
            counts = add(counts, join(s, changes));
        } else if (rounds.get() % 30 == 0) {
            counts = add(counts, exchange(unknownSeeds(1), s, changes));   // now and then: a seed outside the table heals a split
        }
        List<String> suspected = detect(s);
        List<String> dead = new ArrayList<>();
        List<String> forgotten = new ArrayList<>();
        long now = clock.millis();
        for (MemberTable.Known k : table.others()) {
            Member m = k.member();
            if (m.state() == MemberState.SUSPECT && now - k.stateAt() >= s.deadAfter().toMillis() && table.mark(m.nodeId(), MemberState.DEAD)) {
                dead.add(m.nodeId());
                LOG.warn("net: member {} ({}) is dead: no answer for {} s", m.nodeId(), m.url(), (now - k.beatAt()) / 1000);
            } else if (m.state().gone() && now - k.stateAt() >= s.forgetAfter().toMillis() && table.remove(m.nodeId())) {
                forgotten.add(m.nodeId());
            }
        }
        MemberTable.Changes all = combine(changes, dead);
        if (all.changed()) {
            try {
                listener.changed(all);
            } catch (RuntimeException e) {
                LOG.warn("net: member listener failed: {}", e.toString());
            }
        }
        return new Round(counts[0], counts[1], suspected, List.copyOf(dead), List.copyOf(forgotten), all);
    }

    private static int[] add(int[] a, int[] b) {
        return new int[] {a[0] + b[0], a[1] + b[1]};
    }

    private static MemberTable.Changes combine(List<MemberTable.Changes> parts, List<String> dead) {
        Set<String> joined = new LinkedHashSet<>();
        Set<String> gone = new LinkedHashSet<>(dead);
        boolean refuted = false;
        boolean changed = !dead.isEmpty();
        synchronized (parts) {                          // no I/O: a copy of the parts collected by the exchanges
            for (MemberTable.Changes c : parts) {
                joined.addAll(c.joined());
                gone.addAll(c.gone());
                refuted |= c.refuted();
                changed |= c.changed();
            }
        }
        return changed ? new MemberTable.Changes(List.copyOf(joined), List.copyOf(gone), refuted, true) : MemberTable.Changes.NONE;
    }

    /** Up to {@code fanout} random members that are alive or suspect (a suspect one hears of its suspicion this way and refutes it). */
    private List<Member> pickTargets(int fanout) {
        List<Member> pool = new ArrayList<>(table.reachable());
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(Math.max(1, fanout), pool.size())).stream().toList();
    }

    /** Seeds and saved members whose url is not a member this node already reaches, at most {@code n}. */
    private List<Member> unknownSeeds(int n) {
        Set<String> known = new LinkedHashSet<>();
        table.reachable().forEach(m -> known.add(m.url()));
        known.add(table.self().url());
        List<Member> out = new ArrayList<>();
        for (String url : joinList.get()) {
            String u = url == null ? "" : url.replaceAll("/+$", "");
            if (!u.isBlank() && known.add(u)) {
                out.add(new Member("", u, 0, 0, MemberState.ALIVE, "", "", Member.PROTOCOL, List.of(), 0, false));
            }
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    /** A node alone tries its join list, a few at a time, until one exchange gives it the whole table. */
    private int[] join(GossipSettings s, List<MemberTable.Changes> changes) {
        List<Member> candidates = unknownSeeds(Integer.MAX_VALUE);
        int[] total = {0, 0};
        for (int i = 0; i < candidates.size(); i += 3) {
            int[] c = exchange(candidates.subList(i, Math.min(i + 3, candidates.size())), s, changes);
            total = add(total, c);
            if (c[0] > 0) {
                break;
            }
        }
        return total;
    }

    /** Push-pull with each target in parallel, cut at the round interval; returns {exchanged, failed}. */
    private int[] exchange(List<Member> targets, GossipSettings s, List<MemberTable.Changes> changes) {
        if (targets.isEmpty()) {
            return new int[] {0, 0};
        }
        List<Member> mine = table.entries();
        List<CompletableFuture<Boolean>> calls = new ArrayList<>();
        for (Member t : targets) {
            calls.add(CompletableFuture.supplyAsync(() -> exchangeWith(t, mine, changes), executor));
        }
        int ok = 0;
        long budget = Math.max(200, s.interval().toMillis() * 2);
        for (CompletableFuture<Boolean> c : calls) {
            try {
                if (c.get(budget, TimeUnit.MILLISECONDS)) {
                    ok++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // counted as failed below
            }
        }
        int failed = targets.size() - ok;
        exchangesFailed.addAndGet(failed);
        return new int[] {ok, failed};
    }

    private boolean exchangeWith(Member target, List<Member> mine, List<MemberTable.Changes> changes) {
        long t0 = System.nanoTime();
        try {
            List<Member> theirs = transport.exchange(target.url(), mine);
            double ms = (System.nanoTime() - t0) / 1e6;
            changes.add(table.merge(theirs));
            String id = target.nodeId().isBlank() ? idOf(theirs, target.url()) : target.nodeId();
            if (id != null) {
                table.heard(id, ms);
            }
            return true;
        } catch (Exception e) {
            LOG.debug("net: gossip with {} failed: {}", target.url(), e.toString());
            return false;
        }
    }

    /** The node id of the entry whose url is {@code url} (the first entry of a peer's table is the peer itself). */
    private static String idOf(List<Member> theirs, String url) {
        if (theirs.isEmpty()) {
            return null;
        }
        return theirs.stream().filter(m -> url.equals(m.url())).map(Member::nodeId).findFirst().orElse(theirs.get(0).nodeId());
    }

    /**
     * The failure detector: an alive member whose heartbeat has not risen for {@code suspect-after} is probed directly and, failing
     * that, through up to {@code indirect-probes} other alive members; still silent, it becomes suspect.
     */
    private List<String> detect(GossipSettings s) {
        long now = clock.millis();
        List<Member> stale = table.others().stream().filter(k -> k.member().state() == MemberState.ALIVE)
                .filter(k -> now - k.beatAt() >= s.suspectAfter().toMillis()).map(MemberTable.Known::member).toList();
        List<String> suspected = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> probes = new ArrayList<>();
        for (Member m : stale) {
            probes.add(CompletableFuture.runAsync(() -> {
                if (!answers(m, s)) {
                    if (table.mark(m.nodeId(), MemberState.SUSPECT)) {
                        suspected.add(m.nodeId());
                        LOG.warn("net: member {} ({}) is suspect: its heartbeat stopped and it does not answer probes", m.nodeId(), m.url());
                    }
                }
            }, executor));
        }
        for (CompletableFuture<Void> p : probes) {
            try {
                p.get(Math.max(200, s.interval().toMillis() * 2), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // a probe that did not finish in time leaves the member as it is until the next round
            }
        }
        synchronized (suspected) {                       // no I/O: a copy
            return List.copyOf(suspected);
        }
    }

    private boolean answers(Member m, GossipSettings s) {
        if (transport.ping(m.url())) {
            return true;
        }
        List<Member> helpers = new ArrayList<>(table.alive().stream().filter(x -> !x.nodeId().equals(m.nodeId())).toList());
        Collections.shuffle(helpers, random);
        for (Member h : helpers.subList(0, Math.min(s.indirectProbes(), helpers.size()))) {
            if (transport.pingVia(h.url(), m.url())) {
                return true;
            }
        }
        return false;
    }

    /** An administrator added a seed: one exchange now. True when it answered (its whole table is merged). */
    public boolean contact(String url) {
        List<MemberTable.Changes> changes = Collections.synchronizedList(new ArrayList<>());
        int[] c = exchange(List.of(new Member("", url, 0, 0, MemberState.ALIVE, "", "", Member.PROTOCOL, List.of(), 0, false)),
                settings.get(), changes);
        MemberTable.Changes all = combine(changes, List.of());
        if (all.changed()) {
            listener.changed(all);
        }
        return c[0] > 0;
    }

    /**
     * Leaving cleanly: this node's entry becomes {@code left} and is pushed to every member it reaches, {@code rounds} times, so
     * peers drop it at once instead of waiting for the failure detector.
     */
    public void leave(int pushes) {
        long now = clock.millis();
        table.updateSelf(m -> m.withState(MemberState.LEFT, now).withHeartbeat(m.heartbeat() + 1));
        GossipSettings s = settings.get();
        List<MemberTable.Changes> ignored = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < Math.max(1, pushes); i++) {
            exchange(table.reachable(), s, ignored);
            table.updateSelf(m -> m.withHeartbeat(m.heartbeat() + 1));
        }
    }

    /** Back after a leave: alive again with a higher incarnation, so the {@code left} peers remember is superseded. */
    public void rejoin() {
        long now = clock.millis();
        table.updateSelf(m -> m.withIncarnation(m.incarnation() + 1).withState(MemberState.ALIVE, now));
    }
}
