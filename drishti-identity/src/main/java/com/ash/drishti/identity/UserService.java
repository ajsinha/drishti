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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User management: sign-in with lockout, self-service password change, and administration (create,
 * update, enable/disable, reset password, delete). Every change is audited. There is always at least one
 * enabled admin: the last one cannot be disabled, demoted or deleted.
 *
 * <p>Concurrency: every change is a read-modify-write of the <em>current</em> record under that user's lock, so a
 * sign-in finishing late can never write back a stale copy over an admin's change, and parallel wrong guesses are
 * all counted (lockout cannot be raced). Administrative changes also hold one admin lock, so the last-admin rule is
 * checked and applied atomically. Lock order is always admin, then user. Locks are {@link ReentrantLock}s, which
 * do not pin virtual threads.
 */
public final class UserService {

    public static final String ADMIN = "admin";
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");
    private static final Pattern EMAIL = Pattern.compile("^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * What an admin may set on create or update.
     *
     * @param displayName display name
     * @param email email, optional
     * @param desk desk
     * @param roles roles
     * @param enabled may sign in
     */
    public record Profile(String displayName, String email, String desk, Set<String> roles, Boolean enabled, Set<String> packs) {

        public Profile(String displayName, String email, String desk, Set<String> roles, Boolean enabled) {
            this(displayName, email, desk, roles, enabled, null);
        }
    }

    private final UserStore store;
    private final PasswordHasher hasher;
    private final AuditLog audit;
    private final IdentityProperties props;
    private final RoleNames knownRoles;
    private final String dummyHash;
    private final ReentrantLock adminLock = new ReentrantLock();
    private final ConcurrentHashMap<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public UserService(UserStore store, PasswordHasher hasher, AuditLog audit, IdentityProperties props, Set<String> knownRoles) {
        this(store, hasher, audit, props, RoleNames.of(knownRoles));
    }

    /** {@code knownRoles} is asked at every change, so roles administrators add are usable at once. */
    public UserService(UserStore store, PasswordHasher hasher, AuditLog audit, IdentityProperties props, RoleNames knownRoles) {
        this.store = store;
        this.hasher = hasher;
        this.audit = audit;
        this.props = props;
        this.knownRoles = knownRoles;
        this.dummyHash = hasher.hash("timing-equaliser");
    }

    /** Creates the development admin when there are no users at all. Returns true when it did. */
    public boolean seedIfEmpty() {
        if (!props.seedAdmin() || !store.all().isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        store.put(new User(props.seedUsername(), "Drishti dev admin", "", "Administration", new LinkedHashSet<>(props.seedRoles()),
                true, false, hasher.hash(props.seedPassword()), 0, null, now, now, null, now, null));
        audit.record("system", "user-seeded", props.seedUsername(), "development admin created because the user store was empty");
        LOG.warn("Created development admin '{}' with the default password. Change it before any shared use.", props.seedUsername());
        return true;
    }

    /** Whether the seeded admin still has the default password (the console shows a warning). */
    public boolean defaultAdminPasswordInUse() {
        return store.find(props.seedUsername()).map(u -> hasher.verify(props.seedPassword(), u.passwordHash())).orElse(false);
    }

    // ---- sign-in and self service --------------------------------------------------------------

    public User authenticate(String username, String password) {
        String name = norm(username);
        if (store.find(name).isEmpty()) {
            hasher.verify(password == null ? "" : password, dummyHash);
            audit.record(name, "login-failed", name, "unknown user");
            throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "unknown user or wrong password");
        }
        return asUser(name, () -> signIn(name, password));
    }

    private User signIn(String name, String password) {
        Instant now = Instant.now();
        User u = store.find(name).orElse(null);    // re-read under the lock: the current record, not a stale copy
        if (u == null) {
            throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "unknown user or wrong password");
        }
        if (u.locked(now)) {
            audit.record(name, "login-refused", name, "locked until " + u.lockedUntil());
            throw new DrishtiException(ErrorCode.ACCOUNT_LOCKED, "account locked after repeated failures; try again later");
        }
        if (!hasher.verify(password == null ? "" : password, u.passwordHash())) {
            int attempts = u.failedAttempts() + 1;
            Instant until = attempts >= props.maxFailedAttempts() ? now.plus(props.lockout()) : null;
            store.put(u.withFailure(until == null ? attempts : 0, until));
            audit.record(name, until == null ? "login-failed" : "locked", name, "attempt " + attempts);
            throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "unknown user or wrong password");
        }
        if (!u.enabled()) {
            audit.record(name, "login-refused", name, "disabled");
            throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "this account is disabled");
        }
        User signedIn = u.withLogin(now);
        store.put(signedIn);
        audit.record(name, "login", name, "");
        return signedIn;
    }

    public User changeOwnPassword(String username, String current, String next) {
        return asUser(norm(username), () -> changePassword(username, current, next));
    }

    private User changePassword(String username, String current, String next) {
        User u = require(username);
        if (!hasher.verify(current == null ? "" : current, u.passwordHash())) {
            audit.record(username, "password-change-failed", username, "wrong current password");
            throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "current password is wrong");
        }
        checkPassword(next, username);
        if (hasher.verify(next, u.passwordHash())) {
            throw new DrishtiException(ErrorCode.WEAK_PASSWORD, "the new password must differ from the current one");
        }
        User changed = u.withPassword(hasher.hash(next), false, Instant.now());
        store.put(changed);
        audit.record(username, "password-changed", username, "");
        return changed;
    }

    /**
     * A sign-in vouched for by a single sign-on provider (the server has verified its ID token). The user is created
     * on first sign-in (with no usable password); afterwards the provider's name, email and, when
     * {@code replaceRoles}, roles are applied. A user disabled here stays out whatever the provider says, and the
     * provider can never demote the last enabled admin.
     */
    public User federated(String providerUser, String display, String email, Set<String> roles, boolean replaceRoles, String provider) {
        String name = federatedName(providerUser);
        return asAdmin(name, () -> {
            Instant now = Instant.now();
            User u = store.find(name).orElse(null);
            if (u == null) {
                User created = new User(name, blank(display, name), blank(email, ""), "", new LinkedHashSet<>(roles), true, false,
                        hasher.hash(java.util.UUID.randomUUID() + ":" + System.nanoTime()), 0, null, now, now, null, now, null).withLogin(now);
                store.put(created);
                audit.record("system", "user-provisioned", name, "first single sign-on from " + provider + ", roles " + roles);
                audit.record(name, "login-sso", name, provider);
                return created;
            }
            if (!u.enabled()) {
                audit.record(name, "login-refused", name, "disabled (single sign-on from " + provider + ")");
                throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "this account is disabled");
            }
            User next = u.with(blank(display, u.displayName()), email == null || email.isBlank() ? u.email() : email, u.desk(),
                    replaceRoles ? new LinkedHashSet<>(roles) : u.roles(), true, now);
            try {
                guardLastAdmin(u, next);
            } catch (DrishtiException lastAdmin) {
                next = u.with(next.displayName(), next.email(), u.desk(), u.roles(), true, now);   // keep the last admin an admin
            }
            User signedIn = next.withLogin(now);
            store.put(signedIn);
            audit.record(name, "login-sso", name, provider + (replaceRoles && !u.roles().equals(signedIn.roles()) ? ", roles now " + signedIn.roles() : ""));
            return signedIn;
        });
    }

    /** A provider's user name as a Drishti user name: lower case, anything outside a-z 0-9 . _ - becomes '-'. */
    static String federatedName(String providerUser) {
        String n = norm(providerUser).replaceAll("[^a-z0-9._-]", "-").replaceAll("^[^a-z0-9]+", "");
        if (n.length() > 64) {
            n = n.substring(0, 64);
        }
        if (!NAME.matcher(n).matches()) {
            throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "the provider's user name cannot be used here");
        }
        return n;
    }

    // ---- administration ------------------------------------------------------------------------

    public List<User> list(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return store.all().stream().filter(u -> q.isEmpty() || u.username().contains(q)
                || (u.displayName() != null && u.displayName().toLowerCase(Locale.ROOT).contains(q))
                || (u.email() != null && u.email().toLowerCase(Locale.ROOT).contains(q))
                || u.roles().contains(q)).toList();
    }

    public java.util.Optional<User> find(String username) {
        return store.find(norm(username));
    }

    public User require(String username) {
        return store.find(norm(username)).orElseThrow(() -> new DrishtiException(ErrorCode.USER_NOT_FOUND, "no user '" + username + "'"));
    }

    /** Whether new users must change their password at first sign-in when the admin does not say. */
    public boolean forceChangeOnCreate() {
        return props.forcePasswordChangeOnCreate();
    }

    public User create(String actor, String username, Profile p, String password, boolean mustChange) {
        return asAdmin(norm(username), () -> create0(actor, username, p, password, mustChange));
    }

    private User create0(String actor, String username, Profile p, String password, boolean mustChange) {
        String name = norm(username);
        if (!NAME.matcher(name).matches()) {
            throw new DrishtiException(ErrorCode.INVALID_USER, "user name must be 3-64 of a-z 0-9 . _ - and start with a letter or digit");
        }
        if (store.find(name).isPresent()) {
            throw new DrishtiException(ErrorCode.USER_EXISTS, "user '" + name + "' already exists");
        }
        validate(p);
        checkPassword(password, name);
        Instant now = Instant.now();
        User u = new User(name, blank(p.displayName(), name), blank(p.email(), ""), blank(p.desk(), ""), p.roles(),
                p.enabled() == null || p.enabled(), mustChange, hasher.hash(password), 0, null, now, now, null, now, p.packs());
        store.put(u);
        audit.record(actor, "user-created", name, "roles " + u.roles());
        return u;
    }

    public User update(String actor, String username, Profile p) {
        return asAdmin(norm(username), () -> update0(actor, username, p));
    }

    private User update0(String actor, String username, Profile p) {
        User u = require(username);
        validate(p);
        boolean enabled = p.enabled() == null ? u.enabled() : p.enabled();
        User next = u.with(blank(p.displayName(), u.displayName()), p.email() == null ? u.email() : p.email().trim(),
                p.desk() == null ? u.desk() : p.desk().trim(), p.roles() == null ? u.roles() : p.roles(), enabled, Instant.now());
        if (p.packs() != null) {
            next = next.withPacks(p.packs(), Instant.now());
        }
        guardLastAdmin(u, next);
        store.put(next);
        audit.record(actor, "user-updated", u.username(), "roles " + next.roles() + ", enabled " + next.enabled());
        return next;
    }

    public User setEnabled(String actor, String username, boolean enabled) {
        return asAdmin(norm(username), () -> setEnabled0(actor, username, enabled));
    }

    private User setEnabled0(String actor, String username, boolean enabled) {
        User u = require(username);
        if (!enabled && u.username().equals(actor)) {
            throw new DrishtiException(ErrorCode.INVALID_USER, "you cannot disable your own account");
        }
        User next = u.with(u.displayName(), u.email(), u.desk(), u.roles(), enabled, Instant.now());
        guardLastAdmin(u, next);
        store.put(next);
        audit.record(actor, enabled ? "user-enabled" : "user-disabled", u.username(), "");
        return next;
    }

    /**
     * Sets a new password chosen by an admin and clears any lockout. The user is asked to change it at next
     * sign-in only when {@code force-password-change-on-reset} is configured.
     */
    public User resetPassword(String actor, String username, String password) {
        return asAdmin(norm(username), () -> resetPassword0(actor, username, password));
    }

    private User resetPassword0(String actor, String username, String password) {
        User u = require(username);
        checkPassword(password, u.username());
        boolean mustChange = props.forcePasswordChangeOnReset() && !u.username().equals(actor);
        User next = u.withPassword(hasher.hash(password), mustChange, Instant.now());
        store.put(next);
        audit.record(actor, "password-reset", u.username(), "");
        return next;
    }

    public void delete(String actor, String username) {
        asAdmin(norm(username), () -> {
            delete0(actor, username);
            return null;
        });
        userLocks.remove(norm(username));
    }

    private void delete0(String actor, String username) {
        User u = require(username);
        if (u.username().equals(actor)) {
            throw new DrishtiException(ErrorCode.INVALID_USER, "you cannot delete your own account");
        }
        guardLastAdmin(u, null);
        store.delete(u.username());
        audit.record(actor, "user-deleted", u.username(), "");
    }

    /** Records an administrative action that is not about a user (e.g. purging a cache). */
    public void recordAudit(String actor, String action, String subject, String detail) {
        audit.record(actor, action, subject, detail);
    }

    public List<AuditLog.Event> audit(int limit, String subject) {
        return audit.recent(Math.min(Math.max(limit, 1), 1000), subject);
    }

    public Set<String> knownRoles() {
        return knownRoles.get();
    }

    // ---- locking -------------------------------------------------------------------------------

    private <T> T asUser(String name, Supplier<T> body) {
        ReentrantLock lock = userLocks.computeIfAbsent(name, n -> new ReentrantLock());
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    private <T> T asAdmin(String name, Supplier<T> body) {
        adminLock.lock();
        try {
            return asUser(name, body);
        } finally {
            adminLock.unlock();
        }
    }

    // ---- rules ---------------------------------------------------------------------------------

    private void guardLastAdmin(User before, User after) {
        boolean wasActiveAdmin = before.enabled() && before.roles().contains(ADMIN);
        boolean staysActiveAdmin = after != null && after.enabled() && after.roles().contains(ADMIN);
        if (wasActiveAdmin && !staysActiveAdmin) {
            long others = store.all().stream()
                    .filter(u -> !u.username().equals(before.username()) && u.enabled() && u.roles().contains(ADMIN)).count();
            if (others == 0) {
                throw new DrishtiException(ErrorCode.LAST_ADMIN, "'" + before.username() + "' is the last enabled admin");
            }
        }
    }

    private void validate(Profile p) {
        if (p.roles() != null) {
            Set<String> known = knownRoles.get();
            for (String r : p.roles()) {
                if (!known.contains(r)) {
                    throw new DrishtiException(ErrorCode.INVALID_USER, "unknown role '" + r + "'; known: " + new java.util.TreeSet<>(known));
                }
            }
        }
        if (p.email() != null && !EMAIL.matcher(p.email().trim()).matches()) {
            throw new DrishtiException(ErrorCode.INVALID_USER, "email is not valid");
        }
    }

    void checkPassword(String password, String username) {
        if (password == null || password.length() < props.minPasswordLength()) {
            throw new DrishtiException(ErrorCode.WEAK_PASSWORD, "passwords need at least " + props.minPasswordLength() + " characters");
        }
        if (!password.chars().anyMatch(Character::isLetter) || !password.chars().anyMatch(Character::isDigit)) {
            throw new DrishtiException(ErrorCode.WEAK_PASSWORD, "passwords need letters and digits");
        }
        if (password.equalsIgnoreCase(username)) {
            throw new DrishtiException(ErrorCode.WEAK_PASSWORD, "the password must differ from the user name");
        }
    }

    private static String norm(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String blank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
