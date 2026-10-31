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
package com.ash.drishti.net.security;

import com.ash.drishti.net.member.NetClock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cluster credential (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 9): every call between nodes carries an HMAC-SHA256 over its method, path
 * and query, time, calling node and body, made with the cluster key; it is refused outside a 60 s window (replay) or with a
 * signature neither key made. {@code secret} signs; {@code previous-secret}, while set, is also accepted, so the key rotates
 * with rolling restarts and no downtime. Each use of the previous key is remembered per calling node, so Admin → Net can say
 * when the rollover is complete. Immutable keys; the remembered uses are concurrent.
 */
public final class ClusterKey {

    /** How far a call's time may be from this node's clock. */
    public static final long WINDOW_MS = 60_000;

    /** The outcome of a check: {@code ok}, whether the previous key made it, and why it was refused. */
    public record Verdict(boolean ok, boolean previous, String reason) {
        static final Verdict CURRENT = new Verdict(true, false, "");
        static final Verdict PREVIOUS = new Verdict(true, true, "");

        static Verdict refused(String why) {
            return new Verdict(false, false, why);
        }
    }

    private final byte[] current;
    private final byte[] previous;
    private final NetClock clock;
    private final Map<String, Instant> previousUse;

    public ClusterKey(String secret, String previousSecret, NetClock clock) {
        this(secret, previousSecret, clock, new ConcurrentHashMap<>());
    }

    private ClusterKey(String secret, String previousSecret, NetClock clock, Map<String, Instant> previousUse) {
        this.current = secret == null || secret.isEmpty() ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.previous = previousSecret == null || previousSecret.isEmpty() ? null : previousSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.previousUse = previousUse;
    }

    /** The same remembered uses with new keys (a reload of node.yaml), so a rollover's progress survives the change. */
    public ClusterKey withKeys(String secret, String previousSecret) {
        Map<String, Instant> kept = previousSecret == null || previousSecret.isEmpty() ? new ConcurrentHashMap<>() : previousUse;
        return new ClusterKey(secret, previousSecret, clock, kept);
    }

    public boolean set() {
        return current != null;
    }

    public boolean previousSet() {
        return previous != null;
    }

    /** When each calling node last signed with the previous key (empty when none has, or no previous key is set). */
    public Map<String, Instant> previousUses() {
        return Map.copyOf(previousUse);
    }

    public NetClock clock() {
        return clock;
    }

    /** The hex SHA-256 of a body ({@code ""} for none). */
    public static String bodyHash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonical(String method, String pathAndQuery, long time, String node, String bodyHash) {
        return method.toUpperCase(java.util.Locale.ROOT) + "\n" + pathAndQuery + "\n" + time + "\n" + node + "\n" + bodyHash;
    }

    /** The signature header for a call made now. */
    public String sign(String method, String pathAndQuery, long time, String node, String bodyHash) {
        if (current == null) {
            throw new IllegalStateException("no cluster key: set secret: ${DRISHTI_NET_SECRET} in config/net/node.yaml");
        }
        return mac(current, canonical(method, pathAndQuery, time, node, bodyHash));
    }

    /** Checks a received call's signature, time and key; a use of the previous key is remembered for {@code node}. */
    public Verdict verify(String method, String pathAndQuery, String timeHeader, String node, String bodyHash, String signature) {
        if (current == null) {
            return Verdict.refused("this node has no cluster key");
        }
        if (signature == null || signature.isBlank() || node == null || node.isBlank()) {
            return Verdict.refused("no cluster credential (signature and node headers)");
        }
        long time;
        try {
            time = Long.parseLong(timeHeader == null ? "" : timeHeader.trim());
        } catch (NumberFormatException e) {
            return Verdict.refused("no signing time");
        }
        if (Math.abs(clock.millis() - time) > WINDOW_MS) {
            return Verdict.refused("signed " + Math.abs(clock.millis() - time) / 1000 + " s away from this node's clock (more than 60 s: a replay, or clocks apart)");
        }
        String c = canonical(method, pathAndQuery, time, node, bodyHash);
        if (equal(mac(current, c), signature)) {
            return Verdict.CURRENT;
        }
        if (previous != null && equal(mac(previous, c), signature)) {
            previousUse.put(node, Instant.ofEpochMilli(clock.millis()));
            return Verdict.PREVIOUS;
        }
        return Verdict.refused("bad signature: the caller does not have this cluster's key");
    }

    /** An HMAC of {@code purpose} and {@code text} with the current key, for tokens signed with the cluster key. */
    String macCurrent(String text) {
        if (current == null) {
            throw new IllegalStateException("no cluster key");
        }
        return mac(current, text);
    }

    /** 1 when the current key made {@code sig} over {@code text}, 2 when the previous one did, 0 when neither. */
    int which(String text, String sig) {
        if (current != null && equal(mac(current, text), sig)) {
            return 1;
        }
        if (previous != null && equal(mac(previous, text), sig)) {
            return 2;
        }
        return 0;
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.trim().getBytes(StandardCharsets.US_ASCII));
    }

    private static String mac(byte[] key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
