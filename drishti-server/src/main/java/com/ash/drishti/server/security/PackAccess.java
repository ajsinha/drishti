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
package com.ash.drishti.server.security;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which domain packs a user may use and has chosen. <b>Installed</b> packs are what the server runs;
 * <b>assigned</b> packs are those an admin gives a user (or the configured default); <b>active</b> packs are the
 * subset the user chose to see (all assigned, until they choose). A kind owned by a pack that is not active for
 * the user cannot be opened; kinds no pack owns are unaffected.
 */
public final class PackAccess {

    static final String NS = "settings";
    static final String KEY = "packs";

    private final PackRegistry registry;
    private final UserService users;
    private final PreferenceStore prefs;
    private final List<String> defaults;
    private final Map<String, String> kindOwner = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public PackAccess(PackRegistry registry, UserService users, PreferenceStore prefs, List<String> defaults) {
        this.registry = registry;
        this.users = users;
        this.prefs = prefs;
        this.defaults = defaults == null || defaults.isEmpty() ? installed() : List.copyOf(defaults);
        for (Pack p : registry.packs()) {
            p.kinds().forEach(k -> kindOwner.put(k, p.name()));
        }
    }

    public List<String> installed() {
        return registry.packs().stream().map(Pack::name).toList();
    }

    /** Packs an admin made available to the user, limited to what is installed. */
    public List<String> assigned(String user) {
        Set<String> chosen = users.find(user).map(User::packs).orElse(null);
        List<String> base = chosen == null ? defaults : List.copyOf(chosen);
        return installed().stream().filter(base::contains).toList();
    }

    /** Packs the user chose to see: the saved choice within what is assigned, or everything assigned. */
    public List<String> active(String user) {
        List<String> assigned = assigned(user);
        return prefs.get(user, NS, KEY).map(n -> {
            List<String> out = new ArrayList<>();
            n.path("active").forEach(x -> {
                if (assigned.contains(x.asText())) {
                    out.add(x.asText());
                }
            });
            return out.isEmpty() ? assigned : out;
        }).orElse(assigned);
    }

    public List<String> choose(String user, List<String> active) {
        List<String> assigned = assigned(user);
        Set<String> want = new LinkedHashSet<>(active == null ? List.of() : active);
        if (want.isEmpty() || !assigned.containsAll(want)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "choose one or more of your packs: " + assigned);
        }
        var node = json.createObjectNode();
        var arr = node.putArray("active");
        installed().stream().filter(want::contains).forEach(arr::add);
        prefs.put(user, NS, KEY, node);
        return active(user);
    }

    /** False only when the kind belongs to a pack that is not active for the user. */
    public boolean kindAllowed(String user, String kind) {
        String owner = kindOwner.get(kind);
        return owner == null || active(user).contains(owner);
    }

    public void validate(Set<String> packs) {
        if (packs != null && !installed().containsAll(packs)) {
            throw new DrishtiException(ErrorCode.INVALID_USER, "unknown pack; installed: " + installed());
        }
    }
}
