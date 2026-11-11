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
package com.ash.drishti.server.bi.dataset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a row rule may know about the reader: {@code user.name} (the user id), {@code user.roles}, {@code user.desk} (the desk on the user's
 * profile), {@code user.desks} (the profile's desk split at commas and semicolons, so a head of two desks lists both) and {@code user.email}.
 * A list attribute is used with {@code in}: {@code trades.desk in user.desks}. Values are bound as parameters, never written into SQL.
 */
public final class UserAttributes {

    public static final Set<String> KNOWN = Set.of("name", "roles", "desk", "desks", "email");

    private UserAttributes() {}

    /** The attributes of one reader; each is a list (a scalar is a list of one). */
    public static Map<String, List<Object>> of(String user, Collection<String> roles, String desk, String email) {
        Map<String, List<Object>> m = new LinkedHashMap<>();
        m.put("name", List.of(user == null ? "" : user));
        m.put("roles", new ArrayList<>(roles));
        String d = desk == null ? "" : desk.strip();
        m.put("desk", List.of(d));
        List<Object> desks = new ArrayList<>();
        for (String part : d.split("[,;]")) {
            if (!part.isBlank()) {
                desks.add(part.strip());
            }
        }
        m.put("desks", desks);
        m.put("email", List.of(email == null ? "" : email));
        return m;
    }
}
