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
package com.ash.drishti.identity;

import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.SwitchEntity;
import com.ash.drishti.identity.db.SwitchLogEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The administrators' switches (docs/admin/COMMANDS.md): which commands and packs are on or off, kept in the Drishti database, which is
 * the system of record ({@code drishti_switch}, every change in {@code drishti_switch_log}). Drishti*net only spreads it: each node
 * gossips {@link #hash()}, and a node whose hash differs from a peer's pulls the peer's {@link #entries()} and {@link #merge}s them.
 *
 * <p>Each entry carries a hybrid-logical-clock {@code stamp} and the {@code node} that made it; the newer by (stamp, node) wins, so every
 * node reaches the same state whatever order it hears the changes in. A change, made here or learned, is written to this node's database
 * before the snapshot every check reads moves; then the listeners are told (caches, open consoles). Reads are lock-free; writes are
 * serialised by a {@link ReentrantLock} (they do I/O).
 *
 * <p>Ready for grants per role: the key is (scope, name, grantee), {@code *} being everyone, so a command × role grant is another row.
 */
public final class SwitchLedger {

    public static final String COMMAND = "command";
    public static final String PACK = "pack";
    /** A connector's {@code writes:} switched on by an administrator (WRITE_BACK.md section 12): off unless an entry says on. */
    public static final String WRITES = "connector-writes";
    public static final String EVERYONE = "*";

    /**
     * One switch's newest state.
     *
     * @param scope {@link #COMMAND} or {@link #PACK}
     * @param name the command's mnemonic ({@code FRTB}) or the pack's name ({@code market-risk})
     * @param grantee {@link #EVERYONE}, or a role once grants per role exist
     * @param on true when it is on
     * @param reason why it was changed (the audit log keeps it)
     * @param actor who changed it
     * @param stamp the hybrid-logical-clock stamp of the change
     * @param node the node it was made on
     * @param at when it was made, on that node's clock
     */
    public record Entry(String scope, String name, String grantee, boolean on, String reason, String actor, long stamp, String node, Instant at) {

        public Entry {
            grantee = grantee == null || grantee.isBlank() ? EVERYONE : grantee;
            reason = reason == null ? "" : reason;
            actor = actor == null ? "" : actor;
            node = node == null ? "" : node;
        }

        public String key() {
            return key(scope, name, grantee);
        }

        public static String key(String scope, String name, String grantee) {
            return scope + ":" + name + ":" + (grantee == null || grantee.isBlank() ? EVERYONE : grantee);
        }

        /** The merge rule: the higher stamp wins; at the same stamp the greater node id, so every node picks the same entry. */
        public boolean newerThan(Entry o) {
            return o == null || stamp > o.stamp || stamp == o.stamp && node.compareTo(o.node) > 0;
        }
    }

    /** One change as asked for: {@code scope}, {@code name}, on or off. */
    public record Change(String scope, String name, boolean on) {}

    private final IdentityRepositories.Switches rows;
    private final IdentityRepositories.SwitchLog log;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ReentrantLock writes = new ReentrantLock();
    private final AtomicLong hlc = new AtomicLong();
    private final AtomicLong version = new AtomicLong();
    private final List<Consumer<List<Entry>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Supplier<String> node;
    private volatile Map<String, Entry> snapshot = Map.of();
    private volatile String hash = "";

    public SwitchLedger(IdentityRepositories.Switches rows, IdentityRepositories.SwitchLog log, TransactionTemplate tx, String node, Clock clock) {
        this.rows = rows;
        this.log = log;
        this.tx = tx;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        String n = node == null ? "" : node;
        this.node = () -> n;
        reload();
    }

    /** The id this node stamps its changes with (the Drishti*net node id once the server knows it). */
    public void node(Supplier<String> id) {
        Supplier<String> fallback = node;
        this.node = () -> {
            String s = id == null ? null : id.get();
            return s == null || s.isBlank() ? fallback.get() : s;
        };
    }

    public String node() {
        return node.get();
    }

    /** Told, after the database and the snapshot moved, of the entries that changed (made here or learned). */
    public void onChange(Consumer<List<Entry>> l) {
        listeners.add(l);
    }

    /** Re-reads the database (another server sharing it may have written): what is newer is told to the listeners. */
    public void refresh() {
        List<Entry> moved;
        writes.lock();
        try {
            Map<String, Entry> before = snapshot;
            Map<String, Entry> now = read();
            moved = now.values().stream().filter(e -> {
                Entry b = before.get(e.key());
                return b == null || b.stamp() != e.stamp() || !b.node().equals(e.node()) || b.on() != e.on();
            }).toList();
            if (!moved.isEmpty() || now.size() != before.size()) {
                install(now);
            }
        } finally {
            writes.unlock();
        }
        tell(moved);
    }

    private void reload() {
        install(read());
    }

    private Map<String, Entry> read() {
        Map<String, Entry> m = new TreeMap<>();
        for (SwitchEntity e : tx.execute(s -> rows.findAll())) {
            Entry x = new Entry(e.scope, e.name, e.grantee, e.enabled, e.reason, e.actor, e.stamp, e.node, e.updatedAt);
            m.put(x.key(), x);
            observe(x.stamp());
        }
        return m;
    }

    private void install(Map<String, Entry> m) {
        snapshot = Map.copyOf(m);
        hash = hashOf(m.values());
        version.incrementAndGet();
    }

    /** Moves whenever the snapshot is replaced: callers that cache what follows from the switches compare it. */
    public long version() {
        return version.get();
    }

    /** Every entry, sorted by key: what a peer pulls. */
    public List<Entry> entries() {
        return snapshot.values().stream().sorted(Comparator.comparing(Entry::key)).toList();
    }

    /** A short hash of every entry's key, state, stamp and node: equal on two nodes exactly when they hold the same switches. */
    public String hash() {
        return hash;
    }

    /** The newest stamp held, or 0. */
    public long newest() {
        return snapshot.values().stream().mapToLong(Entry::stamp).max().orElse(0);
    }

    public Optional<Entry> find(String scope, String name) {
        return Optional.ofNullable(snapshot.get(Entry.key(scope, name, EVERYONE)));
    }

    /** True unless an administrator switched it off for everyone. */
    public boolean on(String scope, String name) {
        Entry e = snapshot.get(Entry.key(scope, name, EVERYONE));
        return e == null || e.on();
    }

    /** Makes the changes on this node, one stamp each, with the reason; the entries written. */
    public List<Entry> set(Collection<Change> changes, String reason, String actor) {
        List<Entry> made = new ArrayList<>();
        writes.lock();
        try {
            Instant now = clock.instant();
            for (Change c : changes) {
                made.add(new Entry(c.scope(), c.name(), EVERYONE, c.on(), reason, actor, next(), node.get(), now));
            }
            write(made, now);
            Map<String, Entry> m = new TreeMap<>(snapshot);
            made.forEach(e -> m.put(e.key(), e));
            install(m);
        } finally {
            writes.unlock();
        }
        tell(made);
        return made;
    }

    /**
     * Applies the entries newer than the ones held (a peer's, or ones imported): written to the database first, then the snapshot; the
     * entries that were applied. The clock moves past every stamp seen, so a change made here afterwards is newer.
     */
    public List<Entry> merge(Collection<Entry> theirs) {
        List<Entry> applied = new ArrayList<>();
        writes.lock();
        try {
            Map<String, Entry> m = new TreeMap<>(snapshot);
            for (Entry e : theirs) {
                observe(e.stamp());
                if (e.scope() == null || e.name() == null || !(COMMAND.equals(e.scope()) || PACK.equals(e.scope()) || WRITES.equals(e.scope()))) {
                    continue;
                }
                if (e.newerThan(m.get(e.key()))) {
                    m.put(e.key(), e);
                    applied.add(e);
                }
            }
            if (applied.isEmpty()) {
                return applied;
            }
            write(applied, clock.instant());
            install(m);
        } finally {
            writes.unlock();
        }
        tell(applied);
        return applied;
    }

    private void write(List<Entry> entries, Instant appliedAt) {
        tx.executeWithoutResult(s -> {
            for (Entry x : entries) {
                SwitchEntity e = rows.findById(x.key()).orElseGet(SwitchEntity::new);
                e.id = x.key();
                e.scope = x.scope();
                e.name = x.name();
                e.grantee = x.grantee();
                e.enabled = x.on();
                e.reason = x.reason();
                e.actor = x.actor();
                e.stamp = x.stamp();
                e.node = x.node();
                e.updatedAt = x.at() == null ? appliedAt : x.at();
                rows.save(e);
                if (!log.existsByScopeAndNameAndGranteeAndStampAndNode(x.scope(), x.name(), x.grantee(), x.stamp(), x.node())) {
                    SwitchLogEntity l = new SwitchLogEntity();
                    l.scope = x.scope();
                    l.name = x.name();
                    l.grantee = x.grantee();
                    l.enabled = x.on();
                    l.reason = x.reason();
                    l.actor = x.actor();
                    l.stamp = x.stamp();
                    l.node = x.node();
                    l.at = e.updatedAt;
                    l.appliedAt = appliedAt;
                    log.save(l);
                }
            }
        });
    }

    /** The changes, newest first: every one, or one switch's ({@code scope} and {@code name} both given). */
    public List<Entry> history(String scope, String name, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 1000)));
        List<SwitchLogEntity> got = tx.execute(s -> scope != null && name != null ? log.findByScopeAndNameOrderByIdDesc(scope, name, page)
                : log.findAllByOrderByIdDesc(page));
        return got.stream().map(l -> new Entry(l.scope, l.name, l.grantee, l.enabled, l.reason, l.actor, l.stamp, l.node, l.at)).toList();
    }

    /** The next stamp: the wall clock in the high bits, a counter in the low 16, never below a stamp already seen. */
    long next() {
        long wall = clock.millis() << 16;
        return hlc.updateAndGet(last -> Math.max(last + 1, wall));
    }

    private void observe(long stamp) {
        hlc.accumulateAndGet(stamp, Math::max);
    }

    private void tell(List<Entry> moved) {
        if (moved.isEmpty()) {
            return;
        }
        for (Consumer<List<Entry>> l : listeners) {
            try {
                l.accept(moved);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(SwitchLedger.class).warn("switches: a listener failed: {}", e.toString());
            }
        }
    }

    /** The wall-clock time of a stamp. */
    public static Instant timeOf(long stamp) {
        return Instant.ofEpochMilli(stamp >>> 16);
    }

    static String hashOf(Collection<Entry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            entries.stream().sorted(Comparator.comparing(Entry::key)).forEach(e -> md.update(
                    (e.key() + "|" + e.on() + "|" + e.stamp() + "|" + e.node() + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(md.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
