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
import com.ash.drishti.identity.RoleDefinition;
import com.ash.drishti.identity.RoleNames;
import com.ash.drishti.identity.RoleStore;
import com.ash.drishti.identity.UserService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every role there is: the built-in ones (configuration and packs: {@code drishti.security.roles}, read-only) and those
 * administrators define in Admin → Roles (the identity database). A built-in name always means the built-in role.
 * Lookups are lock-free reads of immutable maps.
 */
public final class RoleCatalog implements RoleNames {

    private final Map<String, SecurityProperties.Role> builtIn;
    private final RoleStore store;

    public RoleCatalog(SecurityProperties props, RoleStore store) {
        Map<String, SecurityProperties.Role> roles = new java.util.HashMap<>(props.roles());
        roles.putIfAbsent("admin", new SecurityProperties.Role(List.of("*"), true, true, true, false, true, true));
        this.builtIn = Map.copyOf(roles);
        this.store = store;
    }

    public Optional<RoleDefinition> find(String name) {
        SecurityProperties.Role r = builtIn.get(name);
        if (r != null) {
            return Optional.of(new RoleDefinition(name, "", r.kinds(), r.raw(), r.author(), r.approve(), r.admin(), r.calc(), r.layout(), true, null, ""));
        }
        return store.find(name);
    }

    public boolean exists(String name) {
        return find(name).isPresent();
    }

    /** Built-in roles first, then administrators' roles, each by name. */
    public List<RoleDefinition> all() {
        List<RoleDefinition> out = new ArrayList<>();
        builtIn.keySet().stream().sorted().forEach(n -> out.add(find(n).orElseThrow()));
        store.all().stream().filter(r -> !builtIn.containsKey(r.name())).sorted(Comparator.comparing(RoleDefinition::name)).forEach(out::add);
        return out;
    }

    @Override
    public Set<String> get() {
        Set<String> names = new TreeSet<>(builtIn.keySet());
        store.all().forEach(r -> names.add(r.name()));
        return Set.copyOf(names);
    }

    public RoleDefinition save(RoleDefinition role, String actor) {
        return store.save(role, actor, builtIn.keySet());
    }

    /** Refused while any user holds the role: take it away from them first. */
    public boolean delete(String name, String actor, UserService users) {
        if (builtIn.containsKey(name)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + name + "' is a built-in role (configuration or a pack); it cannot be deleted here");
        }
        List<String> holders = users.list(null).stream().filter(u -> u.roles().contains(name)).map(u -> u.username()).toList();
        if (!holders.isEmpty()) {
            throw new DrishtiException(ErrorCode.ROLE_IN_USE, "'" + name + "' is held by " + holders + "; remove it from them first");
        }
        return store.delete(name, actor);
    }
}
