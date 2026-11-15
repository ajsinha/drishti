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
package com.ash.drishti.api;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The calm state of a stream connector whose broker is not there. A connector that is enabled but cannot reach its broker is not
 * failing: the lake snapshot is served, and only the live updates are paused. Its health text starts with {@code WAITING}
 * ({@link #is}), which health, readiness and the Admin pages read as OK; it logs one line when it starts waiting and one when it
 * connects ({@link Watch}); and it looks at the broker's port ({@link #reachable}) before it opens a client, so the client's own
 * retry noise never starts. A broker that appears later is found by the next look, with no restart. Part of the plugin API
 * (beside {@link SourcePlugin}): the kernel's stream connectors and a module's own stream connectors share it.
 */
public final class BrokerWaiting {

    private static final Pattern HOST_PORT = Pattern.compile("(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?(?:[^@/,()\\s]*@)?(\\[[0-9a-fA-F:]+\\]|[A-Za-z0-9._-]+):(\\d{2,5})");

    private BrokerWaiting() {
    }

    /** The health text of a connector waiting for its broker at {@code endpoint} ({@code host:port}, or the list). */
    public static String message(String endpoint) {
        return "WAITING: broker not reachable at " + endpoint + " - live updates paused; retrying";
    }

    /** The health text of a connector that had its broker and lost it. */
    public static String lost(String endpoint) {
        return "WAITING: connection to the broker at " + endpoint + " lost - live updates paused; reconnecting";
    }

    /** True for a health text that says the connector is waiting for its broker. */
    public static boolean is(String health) {
        return health != null && health.startsWith("WAITING");
    }

    /** The {@code host:port} pairs in a bootstrap list ({@code a:9092,b:9092}) or a broker URL ({@code failover:(tcp://a:61616,tcp://b:61616)}, {@code amqp://u:p@h:5672}). */
    public static List<String> endpoints(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            Matcher m = HOST_PORT.matcher(text);
            while (m.find()) {
                String e = m.group(1) + ":" + m.group(2);
                if (!out.contains(e)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** True when any endpoint accepts a connection within the timeout; true as well when no endpoint can be read (the client then decides). */
    public static boolean reachable(List<String> endpoints, int timeoutMillis) {
        if (endpoints.isEmpty()) {
            return true;
        }
        for (String e : endpoints) {
            int at = e.lastIndexOf(':');
            String host = e.substring(0, at);
            if (host.startsWith("[")) {
                host = host.substring(1, host.length() - 1);
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, Integer.parseInt(e.substring(at + 1))), timeoutMillis);
                return true;
            } catch (java.io.IOException | RuntimeException ignored) {
                // this one is not there; try the next
            }
        }
        return false;
    }

    /** The doubled back-off, capped: 1 s, 2 s, 4 s ... 30 s. */
    public static long next(long backoffMillis) {
        return Math.min(30_000, backoffMillis * 2);
    }

    /** One connector's transitions: one INFO line when it starts waiting, one when it connects, nothing in between. */
    public static final class Watch {
        private final String name;
        private final Consumer<String> log;
        private Boolean up;                // null until the first look

        /** {@code log}: where the two lines go, e.g. {@code LOG::info} of the connector's own logger. */
        public Watch(String name, Consumer<String> log) {
            this.name = name;
            this.log = log;
        }

        /** Called on every failed look: logs only the first of a run. */
        public synchronized void waiting(String endpoint) {
            if (up == null || up) {
                up = false;
                log.accept(name + ": broker not reachable at " + endpoint + " - live updates paused; retrying until it is");
            }
        }

        /** Called on every connection: logs only when it was waiting. */
        public synchronized void connected(String endpoint) {
            if (up == null || !up) {
                up = true;
                log.accept(name + ": connected to the broker at " + endpoint + " - live updates resumed");
            }
        }
    }
}
