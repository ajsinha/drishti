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
package com.ash.drishti.server.bi;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.RoleDefinition;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RoleCatalog;
import com.ash.drishti.server.security.SecurityProperties;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a principal may do in business intelligence (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 11): the powers {@code bi} (open BI and published content),
 * {@code bi-author} (workspaces' content, publish), {@code bi-steward} (certify) and {@code bi-admin} (BI settings, every workspace),
 * granted on roles; an administrator holds all four, and with security off everyone does. A higher power also opens BI, so a role
 * granted only {@code bi-author} still sees the catalogue. Workspace roles are ranked here too.
 */
public final class BiAccess {

    public static final String BI = "bi";
    public static final String AUTHOR = "bi-author";
    public static final String STEWARD = "bi-steward";
    public static final String ADMIN = "bi-admin";
    /** Workspace roles, lowest first. */
    public static final List<String> ROLES = List.of("viewer", "contributor", "member", "admin");

    private final SecurityProperties props;
    private final RoleCatalog roles;

    public BiAccess(SecurityProperties props, RoleCatalog roles) {
        this.props = props;
        this.roles = roles;
    }

    /** The powers the principal holds, sorted; {@code bi} is included whenever any is. */
    public Set<String> powers(Principal p) {
        if (!props.enabled() || p.roles().contains("*")) {
            return new TreeSet<>(RoleDefinition.BI_POWERS);
        }
        Set<String> out = new TreeSet<>();
        for (String r : p.roles()) {
            roles.find(r).ifPresent(d -> out.addAll(d.bi()));
        }
        if (!out.isEmpty()) {
            out.add(BI);
        }
        return out;
    }

    public boolean has(Principal p, String power) {
        Set<String> held = powers(p);
        return held.contains(ADMIN) || held.contains(power);
    }

    public void require(Principal p, String power) {
        if (!has(p, power)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, p.user() + " does not hold the " + power + " power (an administrator grants it on a role)");
        }
    }

    public static int rank(String role) {
        return role == null ? -1 : ROLES.indexOf(role);
    }
}
