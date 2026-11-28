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

import com.ash.drishti.common.Branding;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The server's own address ({@code GET /}), open to anyone who opens it in a browser: what this is and where to go next. It names the
 * product and the version and points at the API's own about answer, the health probe and the console; it holds nothing a caller has
 * not a right to see (no node, user, path, address or setting).
 */
@RestController
public class RootController {
    private final String version;

    public RootController(ObjectProvider<BuildProperties> build) {
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    /** The API's version ({@link ApiVersion}), open like the root: a client checks it before it signs in; no node, user, path or setting. */
    @GetMapping(value = "/api/v1/version", produces = "application/json")
    public Map<String, Object> apiVersion() {
        return ApiVersion.answer(Branding.name(""), version);
    }

    /** What this address is. */
    @GetMapping(value = "/", produces = "application/json")
    public Map<String, Object> root() {
        String product = Branding.name("");
        String named = product.isBlank() ? "" : product + " ";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", product);
        m.put("kind", "server");
        m.put("version", version);
        m.put("message", "This is the " + named + "server: its API, not a web page. The user interface is the console, a separate program "
                + "(python tools/drishti.py start console) that is given this server's address. Open the console's address in the browser.");
        m.put("api", "/api/v1/about");
        m.put("apiVersion", "/api/v1/version");
        m.put("health", "/actuator/health");
        return m;
    }
}
