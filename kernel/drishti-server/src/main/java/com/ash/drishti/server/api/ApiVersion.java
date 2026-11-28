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
package com.ash.drishti.server.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The version of the server's published API ({@code /api/v1/**}), the one number a client (the console, a script, a host application) checks
 * before it relies on a route. {@code major.minor}: a minor step only adds (a client written for the same major and an older minor still
 * works); a major step may remove or change, and ships beside the older major's routes for as long as {@link #MIN_SUPPORTED} says.
 * This is the API's version, not the product's: the product's is in {@code /api/v1/about}.
 */
public final class ApiVersion {
    /** The API this server speaks. */
    public static final String CURRENT = "1.0";
    /** The oldest API a client may have been written for and still be served. */
    public static final String MIN_SUPPORTED = "1.0";

    private ApiVersion() {
    }

    /** What {@code GET /api/v1/version} answers: the API now, and the range of clients' API versions served; one object, no secrets. */
    public static Map<String, Object> answer(String product, String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("api", CURRENT);
        m.put("minSupported", MIN_SUPPORTED);
        m.put("product", product);
        m.put("version", version);
        return m;
    }
}
