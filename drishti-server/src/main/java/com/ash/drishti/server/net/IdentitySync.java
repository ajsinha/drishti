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
package com.ash.drishti.server.net;

import com.ash.drishti.identity.ApiTokenStore;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.JpaUserStore;
import com.ash.drishti.identity.RoleDefinition;
import com.ash.drishti.identity.RoleStore;
import com.ash.drishti.identity.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The identity across nodes (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 11): users (their roles, packs, enabled flag and password hash, never a password),
 * the roles administrators define and personal API tokens (the SHA-256 of their secret, never the secret) are copied to every node over
 * {@link StateSync}'s signed batches, so a person known to one node is known to all: a console that moves to another server keeps them
 * signed in, and a script's token works on any node.
 *
 * <p>A change made here is sent as it is written; a peer's change is applied when it is the newest the node has seen of that user, role
 * or token. A deletion leaves a tombstone ({@code <data dir>/net/identity-deleted.json}, kept 90 days), so a node that was down when a
 * user or role was deleted deletes it when it comes back, and its stale copy never brings the user back elsewhere. A disabled user is a
 * user like any other: the change travels, and their sessions end on every node ({@link StateSync}'s sessions). Every change applied here
 * is audited with the node it came from ({@code net:<node>}); a user's sign-in times travel too but are not audited. Applied changes are
 * never sent on, so a change crosses each link once. With routing.yaml's {@code user-state: off} (one shared identity database) nothing
 * is sent.
 *
 * <p>The cluster never ends with no enabled administrator. The last-administrator rule is checked where a change is made, so two nodes
 * may each disable (or demote, or delete) one of the last two administrators at the same moment. A peer's change that would leave this
 * node with none is therefore repaired as it is applied: a disabled administrator is enabled again, the most recently enabled one (the
 * newest change, then the name, so every node picks the same one and their repairs agree); when no disabled administrator is left, the
 * administrator the change took away stays one (a deletion is refused and the user kept as a new user, so the deletion's tombstone
 * does not refuse them elsewhere). A repair is a change made here: it is sent to every peer, audited ({@code last-admin-kept}) and shown
 * on Admin → Health ({@link #warnings()}).
 */
public final class IdentitySync implements StateSync.Part, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IdentitySync.class);
    static final String USERS = "identity-users";
    static final String ROLES = "identity-roles";
    static final String TOKENS = "identity-tokens";
    private static final Set<String> SPACES = Set.of(USERS, ROLES, TOKENS);
    private static final Duration KEEP_TOMBSTONES = Duration.ofDays(90);

    private final StateSync sync;
    private final JpaUserStore users;
    private final RoleStore roles;
    private final ApiTokenStore tokens;
    private final AuditLog audit;
    private final Consumer<String> userChanged;
    private final Consumer<String> userGone;
    private final Path tombstoneFile;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    /** Applying a peer's change on this thread: the stores' listeners must not send it back. */
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    /** The newest change seen of each user, role or token (namespace and key), in epoch milliseconds. */
    private final Map<String, Long> last = new ConcurrentHashMap<>();
    /** Deleted users and roles (namespace and key) with when, kept for {@link #KEEP_TOMBSTONES}. */
    private final Map<String, Long> deleted = new ConcurrentHashMap<>();
    /** One peer's user change applied at a time: the last-administrator check and its repair must see one state. */
    private final java.util.concurrent.locks.ReentrantLock applyingUsers = new java.util.concurrent.locks.ReentrantLock();
    /** The repairs made here (newest last, at most {@link #KEEP_WARNINGS}), for Admin → Health. */
    private final java.util.Deque<Map<String, Object>> warnings = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int KEEP_WARNINGS = 20;
    static final String ADMIN = "admin";

    /**
     * @param userChanged told of a user written from a peer (to forget what was cached about them)
     * @param userGone told of a user deleted from a peer (to forget their tokens, settings and sessions here, as a deletion here does)
     */
    public IdentitySync(StateSync sync, JpaUserStore users, RoleStore roles, ApiTokenStore tokens, AuditLog audit, Path state,
            Consumer<String> userChanged, Consumer<String> userGone) {
        this.sync = sync;
        this.users = users;
        this.roles = roles;
        this.tokens = tokens;
        this.audit = audit;
        this.userChanged = userChanged;
        this.userGone = userGone;
        this.tombstoneFile = state.resolve("identity-deleted.json");
        loadTombstones();
        users.listen(new JpaUserStore.Listener() {
            public void written(User u) {
                send(USERS, u.username(), json.valueToTree(u), now());
            }

            public void deleted(String username) {
                if (applying.get()) {
                    return;
                }
                long at = now();
                tombstone(USERS, username, at);
                send(USERS, username, null, at);
            }
        });
        if (roles != null) {
            roles.listen(new RoleStore.Listener() {
                public void saved(RoleDefinition r) {
                    send(ROLES, r.name(), json.valueToTree(r), now());
                }

                public void deleted(String name, Instant at) {
                    if (applying.get()) {
                        return;
                    }
                    tombstone(ROLES, name, at.toEpochMilli());
                    send(ROLES, name, null, at.toEpochMilli());
                }
            });
        }
        if (tokens != null) {
            tokens.listen(t -> send(TOKENS, t.id(), json.valueToTree(t), now()));
        }
        sync.part(this);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String key(String ns, String key) {
        return ns + "\u001f" + key;
    }

    /** A change made here: sent to the peers, unless it is a peer's change being applied. */
    private void send(String ns, String key, JsonNode value, long at) {
        if (applying.get()) {
            return;
        }
        last.put(key(ns, key), at);
        sync.publish(new StateSync.Change("", ns, key, value, at, sync.nodeId(), false));
    }

    @Override
    public Set<String> namespaces() {
        return SPACES;
    }

    @Override
    public List<StateSync.Change> catchUp() {
        List<StateSync.Change> out = new ArrayList<>();
        String me = sync.nodeId();
        // deletions first: a user deleted and made again is deleted at the peer before the new one arrives
        deleted.forEach((k, at) -> {
            String[] p = k.split("\u001f", 2);
            out.add(new StateSync.Change("", p[0], p[1], null, at, me, true));
        });
        if (roles != null) {
            for (RoleDefinition r : roles.all()) {
                out.add(new StateSync.Change("", ROLES, r.name(), json.valueToTree(r), at(r), me, true));
            }
        }
        for (User u : users.all()) {
            out.add(new StateSync.Change("", USERS, u.username(), json.valueToTree(u), version(u), me, true));
        }
        if (tokens != null) {
            for (ApiTokenStore.Stored t : tokens.stored()) {
                out.add(new StateSync.Change("", TOKENS, t.id(), json.valueToTree(t), t.createdAt().toEpochMilli(), me, true));
            }
        }
        return out;
    }

    @Override
    public boolean apply(StateSync.Change c) {
        applying.set(true);
        try {
            return switch (c.namespace()) {
                case USERS -> userGuarded(c);
                case ROLES -> roles != null && role(c);
                case TOKENS -> tokens != null && token(c);
                default -> false;
            };
        } catch (IOException e) {
            LOG.warn("net: a {} change from {} could not be read: {}", c.namespace(), c.origin(), e.toString());
            return false;
        } finally {
            applying.set(false);
        }
    }

    private static boolean gone(StateSync.Change c) {
        return c.value() == null || c.value().isNull();
    }

    /** Whether a live change is the newest seen of its key (a newer one made or applied here wins). */
    private boolean newest(StateSync.Change c) {
        Long mine = last.get(key(c.namespace(), c.key()));
        return mine == null || mine <= c.at();
    }

    private boolean deletedAfter(String ns, String key, long at) {
        Long when = deleted.get(key(ns, key));
        return when != null && when >= at;
    }

    private static String actor(StateSync.Change c) {
        return "net:" + c.origin();
    }

    // -- users ----------------------------------------------------------------------------------------------------------------------

    /** The newest time a user record carries: a later record is a later state of the user. */
    static long version(User u) {
        long v = 0;
        for (Instant t : new Instant[] {u.createdAt(), u.updatedAt(), u.lastLoginAt(), u.passwordChangedAt()}) {
            if (t != null) {
                v = Math.max(v, t.toEpochMilli());
            }
        }
        return v;
    }

    private static long born(User u) {
        return u.createdAt() == null ? 0 : u.createdAt().toEpochMilli();
    }

    /**
     * A user's change from a peer. A user deleted at T is every record of that name created at T or before: such a record never comes
     * back (a node that had not heard of the deletion may still send one, a sign-in on it even later than T), while a user created again
     * after T is a new user. Two users of one name made apart (different creation times): the older stands. Two records of one user: the
     * newer change wins (a catch-up: the record with the later times).
     */
    private boolean user(StateSync.Change c) throws IOException {
        String name = c.key();
        User here = users.find(name).orElse(null);
        if (gone(c)) {
            tombstone(USERS, name, c.at());                 // remembered, so a stale copy from a third node is refused too
            if (here == null || born(here) > c.at()) {
                return false;                              // not here, or created again since: that user stays
            }
            last.put(key(USERS, name), c.at());
            if (users.delete(name)) {
                audit.record(actor(c), "user-deleted", name, "deleted on node " + c.origin());
            }
            userGone.accept(name);
            return true;
        }
        User u = json.treeToValue(c.value(), User.class);
        if (u == null || !name.equals(u.username()) || deletedAfter(USERS, name, born(u))) {
            return false;                                  // deleted after this record was created: it does not come back
        }
        if (here != null) {
            if (born(here) != born(u)) {
                if (born(here) < born(u)) {
                    return false;                          // two users of one name made apart: the older stands (a new node's seeded admin never
                }                                          // replaces the cluster's); a user deleted and made again has a tombstone, applied first
            } else if (c.catchUp() ? version(here) >= version(u) : !newest(c)) {
                return false;
            }
        }
        last.put(key(USERS, name), c.catchUp() ? version(u) : c.at());
        users.put(u);
        userChanged.accept(name);
        if (here == null || here.enabled() != u.enabled() || !here.roles().equals(u.roles()) || !Objects.equals(here.packs(), u.packs())
                || !Objects.equals(here.passwordHash(), u.passwordHash())) {
            audit.record(actor(c), here == null ? "user-created" : "user-updated", name, "from node " + c.origin() + ": roles " + u.roles()
                    + ", enabled " + u.enabled() + (here != null && !Objects.equals(here.passwordHash(), u.passwordHash()) ? ", password changed" : ""));
        }
        return true;
    }

    private static boolean activeAdmin(User u) {
        return u != null && u.enabled() && u.roles().contains(ADMIN);
    }

    private boolean anyActiveAdmin() {
        return users.all().stream().anyMatch(IdentitySync::activeAdmin);
    }

    /** A user's change from a peer, applied one at a time, and repaired when it would leave no enabled administrator here. */
    private boolean userGuarded(StateSync.Change c) throws IOException {
        applyingUsers.lock();
        try {
            User here = users.find(c.key()).orElse(null);
            if (!activeAdmin(here)) {
                return user(c);
            }
            if (gone(c) && born(here) <= c.at() && users.all().stream().noneMatch(u -> activeAdmin(u) && !u.username().equals(here.username()))) {
                tombstone(USERS, here.username(), c.at());
                Instant now = Instant.ofEpochMilli(Math.max(now(), c.at() + 1));
                User kept = new User(here.username(), here.displayName(), here.email(), here.desk(), here.roles(), true, here.mustChangePassword(),
                        here.passwordHash(), 0, null, now, now, here.lastLoginAt(), here.passwordChangedAt(), here.packs());
                keep(kept, c, "kept: its deletion on node " + c.origin() + " was refused, as it would have left no enabled administrator");
                return true;
            }
            boolean applied = user(c);
            if (applied && !anyActiveAdmin()) {
                repair(here, c);
            }
            return applied;
        } finally {
            applyingUsers.unlock();
        }
    }

    /**
     * No enabled administrator is left here after a peer's change: the most recently enabled administrator that is disabled is enabled
     * again (newest change first, then the name: every node that sees both changes picks the same one), else the administrator the
     * change took away is given back its role and enabled.
     */
    private void repair(User before, StateSync.Change c) {
        Instant now = Instant.now();
        User pick = users.all().stream().filter(u -> !u.enabled() && u.roles().contains(ADMIN))
                .max(java.util.Comparator.comparing((User u) -> u.updatedAt() == null ? Instant.EPOCH : u.updatedAt())
                        .thenComparing(User::username, java.util.Comparator.reverseOrder()))
                .orElse(null);
        if (pick != null) {
            keep(new User(pick.username(), pick.displayName(), pick.email(), pick.desk(), pick.roles(), true, pick.mustChangePassword(),
                    pick.passwordHash(), 0, null, pick.createdAt(), now, pick.lastLoginAt(), pick.passwordChangedAt(), pick.packs()), c,
                    "enabled again: node " + c.origin() + "'s change to '" + c.key() + "' would have left no enabled administrator");
            return;
        }
        User cur = users.find(before.username()).orElse(before);
        java.util.Set<String> roles = new java.util.TreeSet<>(cur.roles());
        roles.add(ADMIN);
        keep(new User(cur.username(), cur.displayName(), cur.email(), cur.desk(), roles, true, cur.mustChangePassword(), cur.passwordHash(),
                0, null, cur.createdAt(), now, cur.lastLoginAt(), cur.passwordChangedAt(), cur.packs()), c,
                "kept an enabled administrator: node " + c.origin() + "'s change would have left none");
    }

    /** Writes a repaired user here and sends it to every peer as a change made here, audited and shown on Admin → Health. */
    private void keep(User u, StateSync.Change c, String why) {
        users.put(u);
        userChanged.accept(u.username());
        long at = Math.max(version(u), c.at() + 1);
        last.put(key(USERS, u.username()), at);
        sync.publish(new StateSync.Change("", USERS, u.username(), json.valueToTree(u), at, sync.nodeId(), false));
        audit.record("net:" + sync.nodeId(), "last-admin-kept", u.username(), why);
        LOG.warn("net: '{}' {}", u.username(), why);
        Map<String, Object> w = new java.util.LinkedHashMap<>();
        w.put("at", Instant.now().toString());
        w.put("user", u.username());
        w.put("text", "'" + u.username() + "' " + why);
        warnings.addLast(w);
        while (warnings.size() > KEEP_WARNINGS) {
            warnings.pollFirst();
        }
    }

    /** The last-administrator repairs made on this node, oldest first (Admin → Health). */
    public List<Map<String, Object>> warnings() {
        return List.copyOf(warnings);
    }

    // -- roles ----------------------------------------------------------------------------------------------------------------------

    private static long at(RoleDefinition r) {
        return r.updatedAt() == null ? 0 : r.updatedAt().toEpochMilli();
    }

    private boolean role(StateSync.Change c) throws IOException {
        String name = c.key();
        RoleDefinition here = roles.find(name).filter(r -> !r.builtIn()).orElse(null);
        if (gone(c)) {
            if (c.catchUp() ? here == null || at(here) > c.at() : !newest(c)) {
                if (c.catchUp()) {
                    tombstone(ROLES, name, c.at());
                }
                return false;
            }
            last.put(key(ROLES, name), c.at());
            tombstone(ROLES, name, c.at());
            return roles.adoptDelete(name, actor(c));
        }
        RoleDefinition r = json.treeToValue(c.value(), RoleDefinition.class);
        if (r == null || !name.equals(r.name())) {
            return false;
        }
        long v = c.catchUp() ? at(r) : c.at();
        if (deletedAfter(ROLES, name, at(r))) {
            return false;
        }
        if (c.catchUp() ? here != null && at(here) >= v : !newest(c)) {
            return false;
        }
        last.put(key(ROLES, name), v);
        deleted.remove(key(ROLES, name));
        roles.adopt(r, actor(c));
        return true;
    }

    // -- API tokens -----------------------------------------------------------------------------------------------------------------

    private boolean token(StateSync.Change c) throws IOException {
        if (gone(c)) {
            return false;                                  // a token is revoked, never deleted; a deleted user's go with the user
        }
        ApiTokenStore.Stored t = json.treeToValue(c.value(), ApiTokenStore.Stored.class);
        if (t == null || !c.key().equals(t.id()) || t.createdAt() == null || deletedAfter(USERS, t.user(), t.createdAt().toEpochMilli())) {
            return false;                                  // a token of a user deleted since is not brought back
        }
        return tokens.adopt(t, actor(c));
    }

    // -- tombstones -----------------------------------------------------------------------------------------------------------------

    private void tombstone(String ns, String key, long at) {
        deleted.merge(key(ns, key), at, Math::max);
        saveTombstones();
    }

    private void loadTombstones() {
        if (!Files.isRegularFile(tombstoneFile)) {
            return;
        }
        try {
            Map<String, Long> read = json.readValue(tombstoneFile.toFile(), new TypeReference<TreeMap<String, Long>>() {});
            long oldest = now() - KEEP_TOMBSTONES.toMillis();
            read.forEach((k, at) -> {
                String[] p = k.split("/", 2);
                if (p.length == 2 && SPACES.contains(p[0]) && at != null && at > oldest) {
                    deleted.put(key(p[0], p[1]), at);
                }
            });
        } catch (IOException | RuntimeException e) {
            LOG.warn("net: {} could not be read ({}); deletions made while this node was away may come back from a node that missed them",
                    tombstoneFile, e.toString());
        }
    }

    private final java.util.concurrent.locks.ReentrantLock saving = new java.util.concurrent.locks.ReentrantLock();   // file work: never under synchronized

    private void saveTombstones() {
        saving.lock();
        try {
            writeTombstones();
        } finally {
            saving.unlock();
        }
    }

    private void writeTombstones() {
        long oldest = now() - KEEP_TOMBSTONES.toMillis();
        deleted.values().removeIf(at -> at < oldest);
        Map<String, Long> out = new TreeMap<>();
        deleted.forEach((k, at) -> out.put(k.replace('\u001f', '/'), at));
        try {
            Files.createDirectories(tombstoneFile.getParent());
            Path tmp = tombstoneFile.resolveSibling(tombstoneFile.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), out);
            try {
                Files.move(tmp, tombstoneFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, tombstoneFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("net: could not save {}: {}", tombstoneFile, e.toString());
        }
    }

    /** What Admin → Net shows beside the user state: how many deletions are remembered. */
    public int tombstones() {
        return deleted.size();
    }

    @Override
    public void close() {
        // the stores' listeners stay with the stores, which close with the server; nothing runs here
    }
}
