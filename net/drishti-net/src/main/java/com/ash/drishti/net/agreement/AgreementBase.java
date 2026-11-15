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
package com.ash.drishti.net.agreement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What every replicated provider shares (CLUSTER_I5_DESIGN.md section 3): one node's {@link Leases} and {@link Register} over a
 * {@link Referee} that applies {@link AgreementState} commands in one agreed order. A keeper thread, every {@code lease-renew}, asks for each
 * lease this node is a candidate of and refreshes this node's view (one {@code dump} when anything changed), telling the listeners of every
 * change of holder, term or register entry; so listeners behave the same in every mode, voter or not.
 *
 * <p>Fencing is local and conservative: this node takes a lease as still held only until {@code lease-ttl - lease-renew} after the ask
 * that granted or renewed it was sent, so it stops before the referee could have given the lease to another node.
 */
public abstract class AgreementBase implements Agreement, Leases, Register {

    private static final Logger LOG = LoggerFactory.getLogger(AgreementBase.class);

    /** The provider's referee: applies a command or answers a query in the agreed order, or refuses when it cannot decide. */
    public interface Referee {
        /** @throws AgreementUnavailableException when the cluster cannot decide now */
        byte[] apply(byte[] command);

        /** @throws AgreementUnavailableException when the cluster cannot answer now */
        byte[] query(byte[] query);
    }

    /** A lease this node holds: the term, and when the ask that granted or renewed it was sent (this node's clock). */
    private record Held(long term, long sentAt) {}

    private final String self;
    private final Duration ttl;
    private final Duration renew;
    private final LongSupplier clock;
    private final Map<String, List<String>> wanted = new ConcurrentHashMap<>();
    private final Map<String, Boolean> named = new ConcurrentHashMap<>();
    private final Map<String, Held> held = new ConcurrentHashMap<>();
    /** Leases this node gave up ({@link #release}): it stops asking for them until it names their candidates again. */
    private final java.util.Set<String> givenUp = ConcurrentHashMap.newKeySet();
    private final ReentrantLock viewLock = new ReentrantLock();
    private Map<String, Leases.Lease> leaseView = Map.of();
    private Map<String, Register.Entry> entryView = Map.of();
    private long revision;
    private final List<Consumer<String>> leaseListeners = new CopyOnWriteArrayList<>();
    private final List<Map.Entry<String, Consumer<Register.Entry>>> entryListeners = new CopyOnWriteArrayList<>();
    private volatile Thread keeper;
    private volatile boolean closed;

    /**
     * @param self this node's id
     * @param ttl how long a lease lasts without a renewal ({@code [net.agreement] lease-ttl}, 15 s)
     * @param renew how often a holder renews and a candidate asks ({@code lease-renew}, 5 s); less than half of {@code ttl}
     * @param clock this node's clock in epoch milliseconds (only for the local fencing margin)
     */
    protected AgreementBase(String self, Duration ttl, Duration renew, LongSupplier clock) {
        this.self = Objects.requireNonNull(self, "self");
        this.ttl = ttl;
        this.renew = renew;
        this.clock = clock;
        if (renew.multipliedBy(2).compareTo(ttl) > 0) {
            throw new IllegalArgumentException("lease-renew (" + renew + ") must be at most half of lease-ttl (" + ttl + ")");
        }
    }

    /** The referee commands go to. */
    protected abstract Referee referee();

    /** This node's id. */
    public String self() {
        return self;
    }

    /** Starts the keeper (a daemon platform thread of its own). Called once by the provider when its referee can be reached. */
    protected final void startKeeper() {
        Thread t = Thread.ofPlatform().daemon().name("drishti-agreement-" + self).unstarted(this::keep);
        keeper = t;
        t.start();
    }

    private void keep() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                round();
            } catch (AgreementUnavailableException e) {
                LOG.debug("agreement: {} cannot decide now: {}", self, e.getMessage());
            } catch (RuntimeException e) {
                LOG.warn("agreement: a keeper round of {} failed: {}", self, e.toString());
            }
            try {
                Thread.sleep(renew.toMillis());
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One keeper round: name what was not named yet, ask for every lease this node is a candidate of, refresh the view. */
    protected void round() {
        for (Map.Entry<String, List<String>> w : wanted.entrySet()) {
            if (!Boolean.TRUE.equals(named.get(w.getKey()))) {
                referee().apply(AgreementState.candidates(w.getKey(), w.getValue()));
                named.put(w.getKey(), true);
            }
            if (w.getValue().contains(self) && !givenUp.contains(w.getKey())) {
                ask(w.getKey());
            }
        }
        refresh();
    }

    private void ask(String lease) {
        long sentAt = clock.getAsLong();
        Optional<Leases.Lease> l = AgreementState.readLease(referee().apply(AgreementState.ask(lease, self, ttl.toMillis())));
        if (l.isPresent() && l.get().node().equals(self)) {
            held.put(lease, new Held(l.get().term(), sentAt));
        } else {
            held.remove(lease);
        }
        note(lease, l.orElse(null));
    }

    /** Takes one lease's answer into the view; tells the listeners when its holder or term moved. */
    private void note(String lease, Leases.Lease l) {
        boolean moved;
        viewLock.lock();
        try {
            Leases.Lease before = leaseView.get(lease);
            moved = !sameHolder(before, l);
            Map<String, Leases.Lease> next = new LinkedHashMap<>(leaseView);
            if (l == null) {
                next.remove(lease);
            } else {
                next.put(lease, l);
            }
            leaseView = Map.copyOf(next);
        } finally {
            viewLock.unlock();
        }
        if (moved) {
            tellLease(lease);
        }
    }

    private static boolean sameHolder(Leases.Lease a, Leases.Lease b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.node().equals(b.node()) && a.term() == b.term();
    }

    /** Refreshes this node's view from the referee (when anything changed there) and tells the listeners what moved. */
    protected void refresh() {
        long since;
        viewLock.lock();
        try {
            since = revision;
        } finally {
            viewLock.unlock();
        }
        Optional<AgreementState.Dump> d = AgreementState.readDump(referee().query(AgreementState.dump(since)));
        if (d.isEmpty()) {
            return;
        }
        List<String> moved = new ArrayList<>();
        List<Register.Entry> written = new ArrayList<>();
        viewLock.lock();
        try {
            AgreementState.Dump dump = d.get();
            if (dump.revision() < revision) {
                return;                                     // an older answer (another replica behind this one): keep the newer view
            }
            for (String name : union(leaseView.keySet(), dump.leases().keySet())) {
                if (!sameHolder(leaseView.get(name), dump.leases().get(name))) {
                    moved.add(name);
                }
            }
            for (Register.Entry e : dump.entries().values()) {
                Register.Entry before = entryView.get(e.key());
                if (before == null || before.version() != e.version()) {
                    written.add(e);
                }
            }
            leaseView = Map.copyOf(dump.leases());
            entryView = Map.copyOf(dump.entries());
            revision = dump.revision();
        } finally {
            viewLock.unlock();
        }
        moved.forEach(this::tellLease);
        written.forEach(this::tellEntry);
    }

    private static List<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> all = new java.util.TreeSet<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    private void tellLease(String lease) {
        for (Consumer<String> l : leaseListeners) {
            try {
                l.accept(lease);
            } catch (RuntimeException e) {
                LOG.warn("agreement: a lease listener failed: {}", e.toString());
            }
        }
    }

    private void tellEntry(Register.Entry e) {
        for (Map.Entry<String, Consumer<Register.Entry>> l : entryListeners) {
            if (e.key().startsWith(l.getKey())) {
                try {
                    l.getValue().accept(e);
                } catch (RuntimeException x) {
                    LOG.warn("agreement: a register listener failed: {}", x.toString());
                }
            }
        }
    }

    // -- Agreement ------------------------------------------------------------------------------------------------------

    @Override
    public final Leases leases() {
        return this;
    }

    @Override
    public final Register register() {
        return this;
    }

    /**
     * Stops the keeper and releases every lease this node holds (best effort: when the cluster cannot decide, they expire after their
     * time-to-live), then closes the provider ({@link #closeProvider()}).
     */
    @Override
    public final void close() {
        if (closed) {
            return;
        }
        closed = true;
        Thread t = keeper;
        if (t != null) {
            t.interrupt();
        }
        for (String lease : List.copyOf(held.keySet())) {
            try {
                release(lease);
            } catch (RuntimeException e) {
                LOG.debug("agreement: releasing {} at close: {}", lease, e.toString());
            }
        }
        closeProvider();
    }

    /** Stops at once without releasing anything, as a node that crashed does (tests: the others must take its leases after expiry). */
    public final void halt() {
        closed = true;
        Thread t = keeper;
        if (t != null) {
            t.interrupt();
        }
        held.clear();
        closeProvider();
    }

    /** Closes what the provider holds (its client, its server, its threads). */
    protected abstract void closeProvider();

    /** True once closed or halted. */
    protected final boolean closed() {
        return closed;
    }

    // -- Leases ---------------------------------------------------------------------------------------------------------

    @Override
    public void candidates(String lease, List<String> nodesInOrder) {
        List<String> nodes = nodesInOrder == null ? List.of() : List.copyOf(nodesInOrder);
        List<String> before = nodes.isEmpty() ? wanted.remove(lease) : wanted.put(lease, nodes);
        givenUp.remove(lease);
        if (!nodes.equals(before)) {
            named.put(lease, false);
        }
        if (!nodes.contains(self) && held.containsKey(lease)) {
            release(lease);
        }
        try {
            referee().apply(AgreementState.candidates(lease, nodes));
            named.put(lease, true);
            if (nodes.isEmpty()) {
                named.remove(lease);
            } else if (nodes.contains(self)) {
                ask(lease);
            }
        } catch (AgreementUnavailableException e) {
            LOG.debug("agreement: naming the candidates of {} waits for the cluster to decide: {}", lease, e.getMessage());
        }
    }

    @Override
    public Optional<Leases.Lease> holder(String lease) {
        viewLock.lock();
        try {
            return Optional.ofNullable(leaseView.get(lease));
        } finally {
            viewLock.unlock();
        }
    }

    @Override
    public boolean stillHeld(String lease, long term) {
        Held h = held.get(lease);
        return h != null && h.term() == term && !closed && clock.getAsLong() < h.sentAt() + ttl.toMillis() - renew.toMillis();
    }

    /** Gives the lease up now and stops asking for it until {@link #candidates} names it again. */
    @Override
    public void release(String lease) {
        givenUp.add(lease);
        Held h = held.remove(lease);
        if (h == null) {
            return;
        }
        Optional<Leases.Lease> l = AgreementState.readLease(referee().apply(AgreementState.release(lease, self, h.term())));
        note(lease, l.orElse(null));
    }

    @Override
    public void onChange(Consumer<String> listener) {
        leaseListeners.add(Objects.requireNonNull(listener));
    }

    // -- Register -------------------------------------------------------------------------------------------------------

    /** Reads through to the referee; when it cannot answer, the entry as last known here. */
    @Override
    public Optional<Register.Entry> get(String key) {
        try {
            return AgreementState.readEntry(referee().query(AgreementState.get(key)));
        } catch (AgreementUnavailableException e) {
            viewLock.lock();
            try {
                return Optional.ofNullable(entryView.get(key));
            } finally {
                viewLock.unlock();
            }
        }
    }

    @Override
    public boolean compareAndSet(String key, long expected, byte[] value, String by) {
        boolean ok = AgreementState.readBoolean(referee().apply(AgreementState.compareAndSet(key, expected, value, by)));
        if (ok) {
            try {
                refresh();                                  // this node's listeners hear of its own write at once
            } catch (AgreementUnavailableException e) {
                LOG.debug("agreement: refreshing after a write: {}", e.getMessage());
            }
        }
        return ok;
    }

    @Override
    public void onChange(String prefix, Consumer<Register.Entry> listener) {
        entryListeners.add(Map.entry(prefix == null ? "" : prefix, Objects.requireNonNull(listener)));
    }

    /** Every lease and entry as known here, for Admin → Cluster → Agreement. */
    public void forEachKnown(BiConsumer<String, Leases.Lease> leases, Consumer<Register.Entry> entries) {
        Map<String, Leases.Lease> l;
        Map<String, Register.Entry> e;
        viewLock.lock();
        try {
            l = leaseView;
            e = entryView;
        } finally {
            viewLock.unlock();
        }
        l.forEach(leases);
        e.values().forEach(entries);
    }
}
