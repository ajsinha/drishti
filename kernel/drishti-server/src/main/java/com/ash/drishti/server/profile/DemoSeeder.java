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
package com.ash.drishti.server.profile;

import com.ash.drishti.identity.RoleDefinition;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.security.RoleCatalog;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Creates the three demo accounts beside the development administrator under the demo profile (and nowhere else): the same empty-store rule
 * as the administrator (only on the start that created it), each role opening every kind of its packs that this installation has, each user
 * given those packs. Production never runs it: the production profile refuses {@code drishti.identity.seed-demo-users}.
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoSeeder.class);

    private final UserService users;
    private final RoleCatalog roles;
    private final PackRegistry packs;
    private final Environment env;

    public DemoSeeder(UserService users, RoleCatalog roles, PackRegistry packs, Environment env) {
        this.users = users;
        this.roles = roles;
        this.packs = packs;
        this.env = env;
        if (wanted(env)) {
            DemoAccounts.ALL.forEach(a -> users.registerDemoDefault(a.user(), a.password()));
        }
    }

    /** Whether demo users are wanted: the demo profile (unless switched off), or switched on; never in production. */
    static boolean wanted(Environment env) {
        if (ProductionProfile.production(env)) {
            return false;
        }
        String v = env.getProperty("drishti.identity.seed-demo-users", "").strip();
        return v.isEmpty() ? ProductionProfile.DEMO.equals(ProductionProfile.name(env)) : Boolean.parseBoolean(v);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (wanted(env) && users.seededThisStart()) {
            seed();
        }
    }

    /** Creates the roles and users that are not there yet; returns the user names created. */
    public List<String> seed() {
        List<String> made = new java.util.ArrayList<>();
        for (DemoAccounts.Account a : DemoAccounts.ALL) {
            Set<String> have = new LinkedHashSet<>();
            TreeSet<String> kinds = new TreeSet<>();
            for (Pack p : packs.packs()) {
                if (a.packs().contains(p.name())) {
                    have.add(p.name());
                    kinds.addAll(p.kinds());
                }
            }
            if (kinds.isEmpty()) {
                LOG.info("Demo user {} not created: none of its packs {} is installed here", a.user(), a.packs());
                continue;
            }
            try {
                if (roles.find(a.role()).isEmpty()) {
                    roles.save(new RoleDefinition(a.role(), a.roleDescription(), List.copyOf(kinds), false, false, false, false, false, true, true,
                            false, false, null, "", Set.copyOf(a.bi())), "system");
                }
                if (users.find(a.user()).isEmpty()) {
                    users.create("system", a.user(), new UserService.Profile(a.display(), "", "Demo", Set.of(a.role()), true, have), a.password(), false);
                    made.add(a.user());
                }
            } catch (RuntimeException e) {
                LOG.warn("Demo user {} not created: {}", a.user(), e.getMessage());
            }
        }
        if (!made.isEmpty()) {
            LOG.warn("Created demo users {} with their published passwords (demo profile only). Change them before any shared use.", made);
        }
        return made;
    }
}
