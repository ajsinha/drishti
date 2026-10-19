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

import com.ash.drishti.identity.PreferenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The per-user store, telling Drishti*net of every change (DRISHTI_NET.md section 11, N7): a user's saved searches, layouts, pivots,
 * workspaces and settings written on one node are copied to the others, so they follow the user to another node after a failover. Reads
 * and writes go to the store as before; a change that came from a peer is applied without being sent on again. Without the cluster
 * (no {@link StateSync}, or the cluster off) it is the store exactly as before.
 */
public final class ReplicatedPreferences implements PreferenceStore {

    private final PreferenceStore delegate;
    private final Supplier<StateSync> sync;

    public ReplicatedPreferences(PreferenceStore delegate, Supplier<StateSync> sync) {
        this.delegate = delegate;
        this.sync = sync;
    }

    public PreferenceStore delegate() {
        return delegate;
    }

    @Override
    public List<String> keys(String user, String namespace) {
        return delegate.keys(user, namespace);
    }

    @Override
    public Optional<JsonNode> get(String user, String namespace, String key) {
        return delegate.get(user, namespace, key);
    }

    @Override
    public void put(String user, String namespace, String key, JsonNode value) {
        delegate.put(user, namespace, key, value);
        StateSync s = sync.get();
        if (s != null) {
            s.changed(user, namespace, key, value);
        }
    }

    @Override
    public boolean delete(String user, String namespace, String key) {
        boolean gone = delegate.delete(user, namespace, key);
        StateSync s = sync.get();
        if (s != null && gone) {
            s.changed(user, namespace, key, null);
        }
        return gone;
    }

    @Override
    public List<String> users() {
        return delegate.users();
    }

    @Override
    public void forget(String user) {
        delegate.forget(user);
    }
}
