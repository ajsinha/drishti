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

import java.util.List;

/**
 * The caller of a request.
 *
 * @param user user id
 * @param roles role names
 * @param embedApp the host application whose embed token this call carries, or null: an embedded call always sees masked fields
 */
public record Principal(String user, List<String> roles, String embedApp) {

    public Principal(String user, List<String> roles) {
        this(user, roles, null);
    }

    public static final String ATTRIBUTE = "drishti.principal";

    /**
     * What decides how every node masks and routes for this caller: the roles (sorted) and the embedding app. Two callers with the same
     * signature see the same fields masked and reach the same nodes, so what a peer answered for one may be shared with the other (the
     * live relay's topic partition, the history cache of days read through a peer); never with a caller of another signature.
     */
    public String securitySignature() {
        return String.join(",", new java.util.TreeSet<>(roles == null ? List.of() : roles)) + "|" + (embedApp == null ? "" : embedApp);
    }

    /** Everyone, when security is off. */
    public static Principal anonymous(String user) {
        return new Principal(user == null || user.isBlank() ? "anonymous" : user, List.of("*"));
    }
}
