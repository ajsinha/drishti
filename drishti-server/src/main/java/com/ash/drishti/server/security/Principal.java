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
 */
public record Principal(String user, List<String> roles) {

    public static final String ATTRIBUTE = "drishti.principal";

    /** Everyone, when security is off. */
    public static Principal anonymous(String user) {
        return new Principal(user == null || user.isBlank() ? "anonymous" : user, List.of("*"));
    }
}
