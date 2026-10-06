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
import com.ash.drishti.identity.db.EmbedAppEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The host applications registered to show Drishti views in their own pages (Drishti Elements, docs/architecture/ELEMENTS.md).
 * An application has exact origins, a client secret (shown once, stored as a hash) and/or public keys, the kinds and scopes
 * it may use, a token lifetime and rates. Every embed call reads an immutable snapshot (lock-free), so disabling an application
 * takes effect on the next call of this server and within {@code drishti.identity.refresh-seconds} on the others sharing the
 * database. Changes are serialised and audited; only administrators make them (the controller checks).
 */
public final class EmbedAppStore {

    /** Subject token kinds an application may present: the user's OIDC ID token, or a JWT the application signs itself. */
    public static final List<String> SUBJECT_TYPES = List.of("id_token", "jwt");

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern ORIGIN = Pattern.compile("https?://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\\d{1,5})?");
    private static final Pattern WILDCARD = Pattern.compile("https?://\\*\\.[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\\d{1,5})?");

    /** What callers see about an application: never a secret. */
    public record App(String id, String name, String contact, List<String> origins, List<String> kinds, List<String> scopes,
            List<String> subjectTypes, List<String> subjectAudiences, String jwks, boolean hasSecret, int tokenSeconds, int callsPerMinute,
            int userCallsPerMinute, boolean enabled, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, Instant lastUsedAt,
            boolean fromConfig) {

        /** Whether the request's {@code Origin} is one of this application's origins (exact, or a registered {@code https://*.suffix}). */
        public boolean allowsOrigin(String origin) {
            String o = normalise(origin);
            if (o.isEmpty()) {
                return false;
            }
            for (String a : origins) {
                if (a.equals(o)) {
                    return true;
                }
                int star = a.indexOf("://*.");
                if (star > 0 && o.startsWith(a.substring(0, star + 3)) && hostPart(o).endsWith(hostPart(a).substring(1))
                        && portPart(o).equals(portPart(a))) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the application may show the kind: no kind list means every kind its users' roles open. */
        public boolean allowsKind(String kind) {
            return kinds.isEmpty() || kinds.contains(kind);
        }
    }

    /** A registration or a change: {@code null} keeps the current value on an update. */
    public record Draft(String id, String name, String contact, List<String> origins, List<String> kinds, List<String> scopes,
            List<String> subjectTypes, List<String> subjectAudiences, String jwks, Boolean secret, Integer tokenSeconds, Integer callsPerMinute,
            Integer userCallsPerMinute, Boolean enabled) {}

    /** An application declared in configuration ({@code drishti.embed.apps}): read-only here; {@code secretSha256} is the hex SHA-256 of its secret. */
    public record Configured(Draft draft, String secretSha256) {}

    /** A new application, and the client secret (null when it was registered with a key only): shown once. */
    public record Created(App app, String secret) {}

    private record Row(App app, String secretHash, String prevHash, Instant prevUntil) {}

    private final IdentityRepositories.EmbedApps apps;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final boolean wildcards;
    private final int maxSeconds;
    private final int defaultCalls;
    private final int defaultUserCalls;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock writes = new ReentrantLock();
    private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();
    private volatile Map<String, Row> snapshot = Map.of();
    private volatile Map<String, Row> configured = Map.of();

    public EmbedAppStore(IdentityRepositories.EmbedApps apps, TransactionTemplate tx, AuditLog audit, boolean wildcards, int maxSeconds,
            int defaultCalls, int defaultUserCalls) {
        this.apps = apps;
        this.tx = tx;
        this.audit = audit;
        this.wildcards = wildcards;
        this.maxSeconds = maxSeconds;
        this.defaultCalls = defaultCalls;
        this.defaultUserCalls = defaultUserCalls;
        reload();
    }

    /** Re-reads the table: another server sharing the database may have changed it. */
    public void refresh() {
        writes.lock();
        try {
            reload();
        } finally {
            writes.unlock();
        }
    }

    private void reload() {
        Map<String, Row> next = new LinkedHashMap<>();
        tx.execute(s -> apps.findAll()).stream().sorted(java.util.Comparator.comparing(e -> e.id))
                .forEach(e -> next.put(e.id, new Row(view(e), e.secretHash, e.prevSecretHash, e.prevSecretUntil)));
        next.putAll(configured);                      // an application declared in configuration wins over a registered one of the same id
        snapshot = Map.copyOf(next);
    }

    /**
     * Installs the applications declared in configuration (GitOps): validated like a registration, read-only afterwards, and not stored.
     * Throws {@link IllegalStateException} naming the application when one is invalid, so a bad file stops the start.
     */
    public void setConfigured(List<Configured> declared) {
        writes.lock();
        try {
            Map<String, Row> next = new LinkedHashMap<>();
            Instant at = Instant.now();
            for (Configured c : declared == null ? List.<Configured>of() : declared) {
                Draft d = c.draft();
                String id = d.id() == null ? "" : d.id().trim();
                try {
                    if (!ID.matcher(id).matches()) {
                        throw bad("the id is 2-32 characters: lower-case letters, digits and hyphens, starting with a letter");
                    }
                    if (next.containsKey(id)) {
                        throw bad("declared twice");
                    }
                    EmbedAppEntity e = new EmbedAppEntity();
                    e.id = id;
                    e.createdAt = at;
                    e.createdBy = "config";
                    e.updatedAt = at;
                    e.updatedBy = "config";
                    apply(e, d, true);
                    String hash = c.secretSha256() == null ? "" : c.secretSha256().trim().toLowerCase(java.util.Locale.ROOT);
                    if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) {
                        throw bad("secret-sha256 is the 64 hex characters of the SHA-256 of the client secret");
                    }
                    e.secretHash = hash.isEmpty() ? null : hash;
                    if (e.secretHash == null && (e.jwks == null || e.jwks.isBlank())) {
                        throw bad("give the application a secret-sha256 or a jwks to authenticate with");
                    }
                    App base = view(e);
                    next.put(id, new Row(new App(base.id(), base.name(), base.contact(), base.origins(), base.kinds(), base.scopes(), base.subjectTypes(),
                            base.subjectAudiences(), base.jwks(), base.hasSecret(), base.tokenSeconds(), base.callsPerMinute(), base.userCallsPerMinute(),
                            base.enabled(), base.createdAt(), base.createdBy(), base.updatedAt(), base.updatedBy(), null, true), e.secretHash, null, null));
                } catch (DrishtiException ex) {
                    throw new IllegalStateException("drishti.embed.apps '" + id + "': " + ex.getMessage(), ex);
                }
            }
            configured = Map.copyOf(next);
            reload();
        } finally {
            writes.unlock();
        }
    }

    public Optional<App> find(String id) {
        Row r = id == null ? null : snapshot.get(id);
        return r == null ? Optional.empty() : Optional.of(seen(r.app()));
    }

    public List<App> all() {
        return snapshot.values().stream().map(Row::app).map(this::seen).sorted(java.util.Comparator.comparing(App::id)).toList();
    }

    /** A configured application is not in the table, so its last use is kept in memory. */
    private App seen(App a) {
        Instant t = a.fromConfig() ? lastTouched.get(a.id()) : null;
        return t == null ? a : new App(a.id(), a.name(), a.contact(), a.origins(), a.kinds(), a.scopes(), a.subjectTypes(), a.subjectAudiences(),
                a.jwks(), a.hasSecret(), a.tokenSeconds(), a.callsPerMinute(), a.userCallsPerMinute(), a.enabled(), a.createdAt(), a.createdBy(),
                a.updatedAt(), a.updatedBy(), t, true);
    }

    private void notConfigured(String id) {
        if (configured.containsKey(id)) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "'" + id + "' is declared in configuration (drishti.embed.apps): change it there and restart");
        }
    }

    /** Disables or enables a registered application (audited): it gets no token and its tokens stop at the next call. Keeps everything else. */
    public App setEnabled(String id, boolean enabled, String actor) {
        return update(id, new Draft(null, null, null, null, null, null, null, null, null, null, null, null, null, enabled), actor);
    }

    /** The origins of every enabled application: what the console's CORS allow-list is made of. */
    public List<String> enabledOrigins() {
        return snapshot.values().stream().map(Row::app).filter(App::enabled).flatMap(a -> a.origins().stream()).distinct().sorted().toList();
    }

    /** Whether the secret is the application's current one, or its previous one within the rotation grace. Constant time. */
    public boolean secretMatches(String id, String secret) {
        Row r = id == null ? null : snapshot.get(id);
        if (r == null || secret == null || secret.isEmpty()) {
            return false;
        }
        String given = sha256(secret);
        boolean ok = r.secretHash() != null && equal(r.secretHash(), given);
        if (r.prevHash() != null && r.prevUntil() != null && Instant.now().isBefore(r.prevUntil())) {
            ok |= equal(r.prevHash(), given);
        }
        return ok;
    }

    public Created create(Draft d, String actor) {
        writes.lock();
        try {
            String id = d.id() == null ? "" : d.id().trim();
            if (!ID.matcher(id).matches()) {
                throw bad("the id is 2-32 characters: lower-case letters, digits and hyphens, starting with a letter");
            }
            notConfigured(id);
            if (snapshot.containsKey(id)) {
                throw bad("a host application '" + id + "' already exists");
            }
            EmbedAppEntity e = new EmbedAppEntity();
            e.id = id;
            e.createdAt = Instant.now();
            e.createdBy = actor;
            String secret = null;
            if (d.secret() == null || d.secret()) {
                secret = randomText(32);
                e.secretHash = sha256(secret);
            }
            apply(e, d, true);
            if (e.secretHash == null && (e.jwks == null || e.jwks.isBlank())) {
                throw bad("give the application a client secret or a public key (jwks) to authenticate with");
            }
            e.updatedAt = e.createdAt;
            e.updatedBy = actor;
            tx.executeWithoutResult(s -> apps.save(e));
            reload();
            audit.record(actor, "embed-app-created", id, e.name + " origins " + e.origins.replace('\n', ' '));
            return new Created(snapshot.get(id).app(), secret);
        } finally {
            writes.unlock();
        }
    }

    public App update(String id, Draft d, String actor) {
        writes.lock();
        try {
            notConfigured(id);
            App changed = tx.execute(s -> {
                EmbedAppEntity e = apps.findById(id).orElseThrow(() -> notFound(id));
                boolean was = e.enabled;
                apply(e, d, false);
                e.updatedAt = Instant.now();
                e.updatedBy = actor;
                apps.save(e);
                if (e.secretHash == null && (e.jwks == null || e.jwks.isBlank())) {
                    throw bad("an application needs a client secret or a public key to authenticate with");
                }
                audit.record(actor, was != e.enabled ? (e.enabled ? "embed-app-enabled" : "embed-app-disabled") : "embed-app-changed", id, e.name);
                return view(e);
            });
            reload();
            return changed;
        } finally {
            writes.unlock();
        }
    }

    /** Makes a new client secret (shown once); the old one keeps working for {@code graceSeconds} so the host can switch over. */
    public Created rotateSecret(String id, long graceSeconds, String actor) {
        writes.lock();
        try {
            notConfigured(id);
            String secret = randomText(32);
            tx.executeWithoutResult(s -> {
                EmbedAppEntity e = apps.findById(id).orElseThrow(() -> notFound(id));
                e.prevSecretHash = e.secretHash;
                e.prevSecretUntil = e.secretHash == null || graceSeconds <= 0 ? null : Instant.now().plus(Duration.ofSeconds(graceSeconds));
                e.secretHash = sha256(secret);
                e.updatedAt = Instant.now();
                e.updatedBy = actor;
                apps.save(e);
            });
            reload();
            audit.record(actor, "embed-app-secret-rotated", id, "grace " + graceSeconds + "s");
            return new Created(snapshot.get(id).app(), secret);
        } finally {
            writes.unlock();
        }
    }

    public void delete(String id, String actor) {
        writes.lock();
        try {
            notConfigured(id);
            boolean gone = Boolean.TRUE.equals(tx.execute(s -> {
                if (!apps.existsById(id)) {
                    return false;
                }
                apps.deleteById(id);
                return true;
            }));
            if (!gone) {
                throw notFound(id);
            }
            reload();
            audit.record(actor, "embed-app-deleted", id, "");
        } finally {
            writes.unlock();
        }
    }

    /** Records the last use at most once a minute per application, so a call stays a read. */
    public void touch(String id) {
        Instant now = Instant.now();
        Instant before = lastTouched.get(id);
        if (before != null && Duration.between(before, now).toSeconds() < 60) {
            return;
        }
        lastTouched.put(id, now);
        if (configured.containsKey(id)) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> apps.findById(id).ifPresent(e -> {
                e.lastUsedAt = now;
                apps.save(e);
            }));
        } catch (RuntimeException ignored) {
            // the use is still allowed; only the "last used" time is late
        }
    }

    private void apply(EmbedAppEntity e, Draft d, boolean creating) {
        if (d.name() != null || creating) {
            String n = d.name() == null ? "" : d.name().trim();
            if (n.isEmpty() || n.length() > 100) {
                throw bad("give the application a name of 1-100 characters");
            }
            e.name = n;
        }
        if (d.contact() != null) {
            e.contact = d.contact().trim();
        }
        if (d.origins() != null || creating) {
            List<String> origins = normalised(d.origins());
            if (origins.isEmpty()) {
                throw bad("an application needs at least one origin, such as https://crm.bank.example");
            }
            for (String o : origins) {
                boolean ok = ORIGIN.matcher(o).matches() || wildcards && WILDCARD.matcher(o).matches();
                if (!ok) {
                    throw bad("'" + o + "' is not an exact origin (scheme, host and optional port, no path"
                            + (wildcards ? "" : "; wildcards are off: drishti.embed.allow-wildcard-origins") + ")");
                }
            }
            e.origins = String.join("\n", origins);
        }
        if (d.kinds() != null) {
            e.kinds = String.join("\n", d.kinds().stream().map(String::trim).filter(s -> !s.isEmpty() && !"*".equals(s)).distinct().toList());
        }
        if (d.scopes() != null || creating) {
            List<String> scopes = d.scopes() == null ? List.of() : d.scopes().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
            if (scopes.isEmpty()) {
                throw bad("choose at least one scope (embed:view, embed:about)");
            }
            e.scopes = String.join(",", scopes);
        }
        if (d.subjectTypes() != null || creating) {
            List<String> types = d.subjectTypes() == null || d.subjectTypes().isEmpty() ? SUBJECT_TYPES : d.subjectTypes().stream().map(String::trim).distinct().toList();
            for (String t : types) {
                if (!SUBJECT_TYPES.contains(t)) {
                    throw bad("unknown subject token type '" + t + "'; choose from " + SUBJECT_TYPES);
                }
            }
            e.subjectTypes = String.join(",", types);
        }
        if (d.subjectAudiences() != null) {
            e.subjectAudiences = String.join("\n", d.subjectAudiences().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
        }
        if (d.jwks() != null) {
            e.jwks = d.jwks().isBlank() ? null : d.jwks();
        }
        if (!creating && d.secret() != null && !d.secret() && e.secretHash != null) {
            e.secretHash = null;                      // the application now authenticates with its key only
            e.prevSecretHash = null;
            e.prevSecretUntil = null;
        }
        if (d.tokenSeconds() != null || creating) {
            int s = d.tokenSeconds() == null ? Math.min(300, maxSeconds) : d.tokenSeconds();
            if (s < 30 || s > maxSeconds) {
                throw bad("the token lifetime is 30-" + maxSeconds + " seconds (drishti.embed.token-max-seconds)");
            }
            e.tokenSeconds = s;
        }
        if (d.callsPerMinute() != null || creating) {
            e.callsPerMinute = positive(d.callsPerMinute(), defaultCalls, "calls per minute");
        }
        if (d.userCallsPerMinute() != null || creating) {
            e.userCallsPerMinute = positive(d.userCallsPerMinute(), defaultUserCalls, "calls per user per minute");
        }
        if (d.enabled() != null || creating) {
            e.enabled = d.enabled() == null || d.enabled();
        }
    }

    private static int positive(Integer v, int fallback, String what) {
        int n = v == null ? fallback : v;
        if (n < 1) {
            throw bad(what + " must be at least 1");
        }
        return n;
    }

    private static List<String> normalised(List<String> origins) {
        if (origins == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String o : origins) {
            String n = normalise(o);
            if (!n.isEmpty() && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** An origin as compared: trimmed, lower case, no trailing slash. */
    public static String normalise(String origin) {
        return origin == null ? "" : origin.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("/+$", "");
    }

    private static String hostPart(String origin) {
        String rest = origin.substring(origin.indexOf("://") + 3);
        int c = rest.indexOf(':');
        return c < 0 ? rest : rest.substring(0, c);
    }

    private static String portPart(String origin) {
        String rest = origin.substring(origin.indexOf("://") + 3);
        int c = rest.indexOf(':');
        return c < 0 ? "" : rest.substring(c);
    }

    private static App view(EmbedAppEntity e) {
        return new App(e.id, e.name, e.contact, lines(e.origins), lines(e.kinds), csv(e.scopes), csv(e.subjectTypes), lines(e.subjectAudiences),
                e.jwks, e.secretHash != null, e.tokenSeconds, e.callsPerMinute, e.userCallsPerMinute, e.enabled, e.createdAt, e.createdBy,
                e.updatedAt, e.updatedBy, e.lastUsedAt, false);
    }

    private static List<String> lines(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.split("\n"));
    }

    private static List<String> csv(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.split(","));
    }

    private static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, why);
    }

    private static DrishtiException notFound(String id) {
        return new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no host application '" + id + "'");
    }

    private String randomText(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
