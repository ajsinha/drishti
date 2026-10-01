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
import com.ash.drishti.identity.db.ApiTokenEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Personal API tokens: {@code drk_<id>_<secret>}, created by a user for scripts, notebooks and spreadsheets. The secret
 * is shown once and only its SHA-256 is stored; verification compares in constant time. A token acts as its user (their
 * roles and packs at the time of each call) and may only read. Verified tokens are cached for a few seconds, so a busy
 * script does not query the database on every call; revoking clears the cache at once.
 */
public final class ApiTokenStore {

    /** What a caller sees about a token: never its secret. */
    public record TokenView(String id, String user, String name, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
            Instant revokedAt, boolean active) {}

    /** A new token: the full text, shown once. */
    public record Created(TokenView token, String secret) {}

    private static final Pattern FORMAT = Pattern.compile("drk_([A-Za-z0-9]{12})_([A-Za-z0-9_-]{43})");
    private static final int MAX_PER_USER = 20;
    private static final Duration CACHE = Duration.ofSeconds(30);
    private final IdentityRepositories.ApiTokens tokens;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastWritten = new ConcurrentHashMap<>();

    private record Cached(String user, String hash, Instant expiresAt, Instant until) {}

    public ApiTokenStore(IdentityRepositories.ApiTokens tokens, TransactionTemplate tx, AuditLog audit) {
        this.tokens = tokens;
        this.tx = tx;
        this.audit = audit;
    }

    public static boolean looksLikeToken(String bearer) {
        return bearer != null && bearer.startsWith("drk_");
    }

    public Created create(String user, String name, Integer days) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give the token a name of 1-100 characters (what uses it)");
        }
        if (days != null && (days < 1 || days > 366)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a token lasts 1-366 days (or leave it blank for no expiry)");
        }
        if (tokens.findByUsernameOrderByCreatedAtDesc(user).stream().filter(t -> t.revokedAt == null).count() >= MAX_PER_USER) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + MAX_PER_USER + " active tokens; revoke one first");
        }
        String id = randomText(9).replace("-", "a").replace("_", "b").substring(0, 12);
        String secret = randomText(32);
        ApiTokenEntity e = new ApiTokenEntity();
        e.id = id;
        e.username = user;
        e.name = n;
        e.secretHash = sha256(secret);
        e.createdAt = Instant.now();
        e.expiresAt = days == null ? null : e.createdAt.plus(Duration.ofDays(days));
        tx.executeWithoutResult(s -> tokens.save(e));
        audit.record(user, "token-created", user, id + " " + n + (days == null ? "" : " for " + days + " days"));
        return new Created(view(e), "drk_" + id + "_" + secret);
    }

    /** The user a token stands for, or empty when it is unknown, wrong, revoked or expired. */
    public Optional<String> verify(String bearer) {
        Matcher m = FORMAT.matcher(bearer == null ? "" : bearer.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String id = m.group(1);
        Instant now = Instant.now();
        Cached c = cache.get(id);
        if (c == null || now.isAfter(c.until())) {
            ApiTokenEntity e = tokens.findById(id).orElse(null);
            if (e == null || e.revokedAt != null) {
                cache.remove(id);
                return Optional.empty();
            }
            c = new Cached(e.username, e.secretHash, e.expiresAt, now.plus(CACHE));
            cache.put(id, c);
        }
        if (c.expiresAt() != null && now.isAfter(c.expiresAt())) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(c.hash().getBytes(StandardCharsets.US_ASCII), sha256(m.group(2)).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        touch(id, now);
        return Optional.of(c.user());
    }

    /** Records the last use at most once a minute per token, so verification stays a read. */
    private void touch(String id, Instant now) {
        Instant before = lastWritten.get(id);
        if (before != null && Duration.between(before, now).toSeconds() < 60) {
            return;
        }
        lastWritten.put(id, now);
        try {
            tx.executeWithoutResult(s -> tokens.findById(id).ifPresent(e -> {
                e.lastUsedAt = now;
                tokens.save(e);
            }));
        } catch (RuntimeException ignored) {
            // the use is still allowed; only the "last used" time is late
        }
    }

    public List<TokenView> of(String user) {
        return tokens.findByUsernameOrderByCreatedAtDesc(user).stream().map(ApiTokenStore::view).toList();
    }

    public List<TokenView> all() {
        return tokens.findAllByOrderByCreatedAtDesc().stream().map(ApiTokenStore::view).toList();
    }

    /** Revokes a token; {@code owner} null lets an administrator revoke anyone's. */
    public boolean revoke(String id, String owner, String actor) {
        boolean done = Boolean.TRUE.equals(tx.execute(s -> tokens.findById(id)
                .filter(e -> owner == null || e.username.equals(owner))
                .filter(e -> e.revokedAt == null)
                .map(e -> {
                    e.revokedAt = Instant.now();
                    tokens.save(e);
                    return true;
                }).orElse(false)));
        cache.remove(id);
        if (done) {
            audit.record(actor, "token-revoked", owner == null ? "" : owner, id);
        }
        return done;
    }

    /** Removes a user's tokens (called when the user is deleted). */
    public void forget(String user) {
        tx.executeWithoutResult(s -> tokens.deleteByUsername(user));
        cache.clear();
    }

    private static TokenView view(ApiTokenEntity e) {
        boolean active = e.revokedAt == null && (e.expiresAt == null || Instant.now().isBefore(e.expiresAt));
        return new TokenView(e.id, e.username, e.name, e.createdAt, e.expiresAt, e.lastUsedAt, e.revokedAt, active);
    }

    private String randomText(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
