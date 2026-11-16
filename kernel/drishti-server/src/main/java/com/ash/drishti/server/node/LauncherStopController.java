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
package com.ash.drishti.server.node;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The launcher's stop (docs/DEPLOYMENT_AND_QA_GUIDE.md section 7.1): {@code python tools/drishti.py stop server} asks the server it started to
 * stop itself, the same graceful shutdown as SIGTERM (requests in flight finish, {@code server.shutdown: graceful}), on every operating
 * system: on Windows a background JVM has no console to signal and {@code taskkill} without {@code /F} cannot reach it.
 *
 * <p>Answered only when the server was started by the launcher: it hands a fresh random key in {@code DRISHTI_STOP_KEY}
 * ({@code drishti.launcher.stop-key}) and keeps it in the pid file under the data folder, so only who may read that file (who could kill the
 * process anyway) can stop it. The key must come in the {@value #HEADER} header, compared in constant time, and the call straight from this
 * machine: the socket's own peer address is loopback and the request carries no forwarding header ({@code Forwarded},
 * {@code X-Forwarded-*}, {@code X-Real-IP}), so a proxy on this machine can never relay it. Anything else is {@code 403}, the same answer
 * whether the server has a key or not; the key is never logged. Not part of the public API (it is the launcher's, not a user's).
 *
 * <pre>
 *   POST /launcher/stop     X-Drishti-Stop-Key: &lt;the key&gt;  ->  202 {"stopping": true}, then the server shuts down gracefully
 * </pre>
 */
@RestController
public class LauncherStopController {

    /** The header carrying the launcher's key. */
    public static final String HEADER = "X-Drishti-Stop-Key";

    private final byte[] key;
    private final Runnable exit;

    @Autowired
    public LauncherStopController(@Value("${drishti.launcher.stop-key:}") String key, ConfigurableApplicationContext context) {
        this(key, () -> System.exit(SpringApplication.exit(context, () -> 0)));
    }

    LauncherStopController(String key, Runnable exit) {
        this.key = key == null || key.isBlank() ? null : key.strip().getBytes(StandardCharsets.UTF_8);
        this.exit = exit;
    }

    @PostMapping("/launcher/stop")
    public ResponseEntity<Map<String, Object>> stop(HttpServletRequest request, @RequestHeader(value = HEADER, required = false) String given) {
        boolean ok = key != null && direct(request) && given != null && MessageDigest.isEqual(key, given.strip().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            return ResponseEntity.status(403).body(Map.of("title", "forbidden", "status", 403,
                    "detail", "the launcher's stop takes the key of the pid file, from this machine only"));
        }
        Thread t = new Thread(exit, "drishti-launcher-stop");          // after the answer is sent: the shutdown closes the web server
        t.setDaemon(false);
        t.start();
        return ResponseEntity.accepted().body(Map.of("stopping", true));
    }

    /** The headers a proxy adds: a request carrying any of them did not come straight from this machine's launcher. */
    static final java.util.List<String> FORWARDING = java.util.List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
            "X-Real-IP");

    /** Straight from this machine: the socket's peer is loopback (never a forwarded address) and no proxy header is present. */
    static boolean direct(HttpServletRequest request) {
        return loopback(request.getRemoteAddr()) && FORWARDING.stream().noneMatch(h -> request.getHeader(h) != null);
    }

    static boolean loopback(String address) {
        if (address == null) {
            return false;
        }
        String a = address.startsWith("[") && address.endsWith("]") ? address.substring(1, address.length() - 1) : address;
        return a.startsWith("127.") || a.equals("::1") || a.equals("0:0:0:0:0:0:0:1");
    }
}
