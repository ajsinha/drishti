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
package com.ash.drishti.net.member.jgroups;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jgroups.Message;
import org.jgroups.auth.AuthToken;

/**
 * The join token of the membership channel (CLUSTER_I5_DESIGN.md sections 3.1 and 4.2): the cluster key's HMAC over the cluster's name, a
 * time and a fresh nonce. The coordinator admits a joiner only when the cluster name is its own, the time is within a minute of its clock
 * (the same window as the signed HTTP calls) and the HMAC verifies under the cluster key or, during a rotation, the previous key. A node of
 * another cluster, or without the key, never enters the view. The key never travels.
 *
 * <p>JGroups writes the token's class name and re-creates the received token with the no-argument constructor; that instance carries only
 * what was received and is checked by the configured instance, which holds the keys.
 */
public final class ClusterKeyToken extends AuthToken {

    /** How far the joiner's time may be from the coordinator's. */
    static final long WINDOW_MS = 60_000;
    private static final int NONCE = 16;
    private static final int MAC = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String key;
    private final String previousKey;
    private String cluster;
    private long time;
    private byte[] nonce = new byte[NONCE];
    private byte[] mac = new byte[MAC];

    /** For JGroups: the received token, filled by {@link #readFrom}. */
    public ClusterKeyToken() {
        this.key = "";
        this.previousKey = "";
        this.cluster = "";
    }

    /** The configured token: signs what this node sends, verifies what joiners send. */
    public ClusterKeyToken(String cluster, String key, String previousKey) {
        this.cluster = cluster;
        this.key = key;
        this.previousKey = previousKey == null ? "" : previousKey;
    }

    @Override
    public String getName() {
        return "drishti-cluster-key";
    }

    @Override
    public int size() {
        return 2 + cluster.getBytes(StandardCharsets.UTF_8).length + Long.BYTES + NONCE + MAC;
    }

    /** A fresh time, nonce and HMAC every time it is written. */
    @Override
    public void writeTo(DataOutput out) throws IOException {
        long now = System.currentTimeMillis();
        byte[] n = new byte[NONCE];
        RANDOM.nextBytes(n);
        out.writeUTF(cluster);
        out.writeLong(now);
        out.write(n);
        out.write(hmac(key, cluster, now, n));
    }

    @Override
    public void readFrom(DataInput in) throws IOException {
        cluster = in.readUTF();
        time = in.readLong();
        nonce = new byte[NONCE];
        in.readFully(nonce);
        mac = new byte[MAC];
        in.readFully(mac);
    }

    @Override
    public boolean authenticate(AuthToken token, Message msg) {
        if (!(token instanceof ClusterKeyToken t) || key.isEmpty()) {
            return false;
        }
        if (!cluster.equals(t.cluster) || Math.abs(System.currentTimeMillis() - t.time) > WINDOW_MS) {
            return false;
        }
        if (MessageDigest.isEqual(hmac(key, t.cluster, t.time, t.nonce), t.mac)) {
            return true;
        }
        return !previousKey.isEmpty() && MessageDigest.isEqual(hmac(previousKey, t.cluster, t.time, t.nonce), t.mac);
    }

    private static byte[] hmac(String key, String cluster, long time, byte[] nonce) {
        if (key.isEmpty()) {
            return new byte[MAC];
        }
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            m.update(("drishti-join|" + cluster + "|" + time + "|").getBytes(StandardCharsets.UTF_8));
            m.update(nonce);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
    }
}
