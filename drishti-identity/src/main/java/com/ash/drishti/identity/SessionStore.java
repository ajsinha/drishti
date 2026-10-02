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
import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.SessionEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Console sign-in sessions. The console opens one when the server has verified a sign-in and puts its id in the
 * (signed) session cookie; on each request it asks whether the session still stands. A session ends when the user
 * signs out, when an administrator disables, deletes or resets the password of the user, or when it expires. Only a
 * SHA-256 of the id is stored, and the rows live in the identity database, so every server sharing it agrees.
 */
public final class SessionStore {

    /** An open session: its id is known only to the console that opened it (and is in the answer to open only). */
    public record Session(String id, String user, Instant createdAt, Instant expiresAt) {}

    private static final Duration SHORTEST = Duration.ofMinutes(1);
    private static final Duration LONGEST = Duration.ofDays(7);
    private static final Duration SWEEP_EVERY = Duration.ofHours(1);
    private final IdentityRepositories.Sessions sessions;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.EPOCH);

    public SessionStore(IdentityRepositories.Sessions sessions, TransactionTemplate tx, AuditLog audit) {
        this.sessions = sessions;
        this.tx = tx;
        this.audit = audit;
    }

    /** Opens a session for a user the caller has just verified, lasting {@code ttl} (1 minute to 7 days). */
    public Session open(String user, Duration ttl) {
        if (ttl == null || ttl.compareTo(SHORTEST) < 0 || ttl.compareTo(LONGEST) > 0) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a session lasts 60 seconds to 7 days");
        }
        sweep();
        byte[] b = new byte[24];
        random.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        SessionEntity e = new SessionEntity();
        e.idHash = sha256(id);
        e.username = user;
        e.createdAt = Instant.now();
        e.expiresAt = e.createdAt.plus(ttl);
        tx.executeWithoutResult(s -> sessions.save(e));
        return new Session(id, user, e.createdAt, e.expiresAt);
    }

    /** The session with this id, if it still stands (not ended, not expired). */
    public Optional<Session> find(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return sessions.findById(sha256(id)).filter(e -> now.isBefore(e.expiresAt))
                .map(e -> new Session(id, e.username, e.createdAt, e.expiresAt));
    }

    /** Ends one session (sign-out); true when it was open. */
    public boolean end(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            return false;
        }
        String key = sha256(id);
        String user = tx.execute(s -> sessions.findById(key).map(e -> {
            sessions.delete(e);
            return e.username;
        }).orElse(null));
        if (user != null) {
            audit.record(user, "signed-out", user, "");
        }
        return user != null;
    }

    /** Ends every session of a user (disabled, deleted or password reset by an administrator); how many were open. */
    public long endAll(String user, String actor, String why) {
        Long n = tx.execute(s -> sessions.deleteByUsername(user));
        long ended = n == null ? 0 : n;
        if (ended > 0) {
            audit.record(actor, "sessions-ended", user, ended + " session(s): " + why);
        }
        return ended;
    }

    /** Removes expired rows, at most once an hour (from {@link #open}, so a quiet server does no work). */
    void sweep() {
        Instant now = Instant.now();
        Instant before = lastSweep.get();
        if (Duration.between(before, now).compareTo(SWEEP_EVERY) < 0 || !lastSweep.compareAndSet(before, now)) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> sessions.deleteByExpiresAtBefore(now));
        } catch (RuntimeException ignored) {
            // expired rows are refused anyway; the next sweep removes them
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
