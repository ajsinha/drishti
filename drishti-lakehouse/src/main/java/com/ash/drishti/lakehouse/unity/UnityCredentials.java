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
package com.ash.drishti.lakehouse.unity;

import com.ash.drishti.lakeio.CredentialSource;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * One table's vended S3 credentials, renewed before they expire: every S3 request asks {@link #current()}, which answers from memory and,
 * once the keys are within {@code renewBeforeMs} of their expiry, asks Unity Catalog for new ones (one caller renews, the others keep
 * using the old keys meanwhile). When Unity Catalog cannot be reached the old keys serve until they really expire; after that a read fails
 * with Unity's reason in words. Thread-safe.
 */
public final class UnityCredentials implements CredentialSource {

    private final UnityClient client;
    private final UnityClient.TableInfo table;
    private final long renewBeforeMs;
    private final LongSupplier clock;
    private final ReentrantLock renewing = new ReentrantLock();
    private volatile UnityClient.Vended vended;
    private volatile Key key;
    private volatile String lastError;

    /** With the first credentials already fetched ({@code first}). */
    UnityCredentials(UnityClient client, UnityClient.TableInfo table, UnityClient.Vended first, long renewBeforeMs, LongSupplier clock) {
        this.client = client;
        this.table = table;
        this.renewBeforeMs = renewBeforeMs;
        this.clock = clock;
        set(first);
    }

    private void set(UnityClient.Vended v) {
        vended = v;
        key = new Key(v.values().getOrDefault("access_key_id", ""), v.values().getOrDefault("secret_access_key", ""),
                v.values().getOrDefault("session_token", ""));
    }

    /** When the current keys expire (epoch milliseconds). */
    public long expiresAtMs() {
        return vended.expiresAtMs();
    }

    /** Why the last renewal failed, or null. */
    public String lastError() {
        return lastError;
    }

    @Override
    public Key current() {
        long now = clock.getAsLong();
        UnityClient.Vended v = vended;
        if (now < v.expiresAtMs() - renewBeforeMs) {
            return key;
        }
        boolean expired = now >= v.expiresAtMs();
        if (expired) {
            renewing.lock();                                     // nothing valid: wait for the renewal
        } else if (!renewing.tryLock()) {
            return key;                                          // another request is renewing; these keys still serve
        }
        try {
            if (vended != v) {
                return key;
            }
            try {
                set(client.credentials(table));
                lastError = null;
            } catch (IOException | RuntimeException e) {
                lastError = e.getMessage();
                if (expired) {
                    throw new IllegalStateException("the temporary credentials of " + table.fullName() + " expired and Unity Catalog gave no new ones: "
                            + e.getMessage(), e);
                }
            }
            return key;
        } finally {
            renewing.unlock();
        }
    }
}
