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
package com.ash.drishti.server.collab.bridge;

import com.ash.drishti.identity.collab.CollabProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The configured bridges, resolved: the URL and the signing secret are read from the environment variables the configuration
 * names (never from configuration itself), the URL must start with one of {@code bridges.allow}, and the routes say which events of
 * which packs and kinds go where. A bridge that cannot be used says why in {@link Bridge#status()} (the variable names, never their
 * values) and its posts become dead letters. A malformed configuration (a bad name, a duplicate, an unknown format or event) stops the
 * server at start, so a typo cannot silently drop messages.
 */
public final class BridgeRegistry {

    public static final String SHARE = "share";
    public static final String COMMENT = "comment";
    public static final String MENTION = "mention";
    public static final Set<String> EVENTS = Set.of(SHARE, COMMENT, MENTION);
    public static final Set<String> FORMATS = Set.of("json", "teams", "slack");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,39}");

    /** A usable (or not) bridge. {@code url} and {@code secret} never appear in {@link #toString()}. */
    public record Bridge(String name, String format, URI url, byte[] secret, String status, List<CollabProperties.Route> routes) {

        public static final String OK = "ok";

        public boolean usable() {
            return OK.equals(status);
        }

        /** The host of the URL (its path and query are secrets), or null. */
        public String host() {
            return url == null ? null : url.getHost();
        }

        @Override
        public String toString() {
            return "Bridge[" + name + ", " + format + ", " + status + "]";
        }
    }

    private final List<Bridge> bridges;

    public BridgeRegistry(CollabProperties props, Function<String, String> env) {
        List<Bridge> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        CollabProperties.Bridges cfg = props.bridges();
        for (CollabProperties.Webhook w : cfg.webhooks()) {
            if (!NAME.matcher(w.name()).matches()) {
                throw new IllegalStateException("drishti.collab.bridges.webhooks: '" + w.name() + "' is not a bridge name (letters, digits, - and _)");
            }
            if (!seen.add(w.name())) {
                throw new IllegalStateException("drishti.collab.bridges.webhooks: the name '" + w.name() + "' is used twice");
            }
            if (!FORMATS.contains(w.format())) {
                throw new IllegalStateException("drishti.collab.bridges.webhooks." + w.name() + ".format must be json, teams or slack, not " + w.format());
            }
            for (CollabProperties.Route r : w.routes()) {
                for (String e : r.events()) {
                    if (!EVENTS.contains(e)) {
                        throw new IllegalStateException("drishti.collab.bridges.webhooks." + w.name() + ": event '" + e + "' is not share, comment or mention");
                    }
                }
            }
            out.add(resolve(w, cfg, env));
        }
        this.bridges = List.copyOf(out);
    }

    private static Bridge resolve(CollabProperties.Webhook w, CollabProperties.Bridges cfg, Function<String, String> env) {
        String status = Bridge.OK;
        URI url = null;
        byte[] secret = null;
        if (w.urlEnv().isEmpty()) {
            status = "unconfigured: url-env is not set";
        } else {
            String raw = env.apply(w.urlEnv());
            if (raw == null || raw.isBlank()) {
                status = "unconfigured: environment variable " + w.urlEnv() + " is not set";
            } else {
                String u = raw.strip();
                try {
                    url = URI.create(u);
                    String scheme = url.getScheme();
                    if (url.getHost() == null || scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
                        status = "blocked: the URL in " + w.urlEnv() + " is not an http(s) URL";
                        url = null;
                    } else if (cfg.allow().stream().noneMatch(u::startsWith)) {
                        status = "blocked: the URL in " + w.urlEnv() + " does not start with any prefix in drishti.collab.bridges.allow";
                        url = null;
                    }
                } catch (IllegalArgumentException e) {
                    status = "blocked: the URL in " + w.urlEnv() + " is not a valid URL";
                    url = null;
                }
            }
        }
        if ("json".equals(w.format())) {
            String s = w.secretEnv().isEmpty() ? null : env.apply(w.secretEnv());
            if (s == null || s.isEmpty()) {
                if (Bridge.OK.equals(status)) {
                    status = w.secretEnv().isEmpty() ? "unconfigured: a json bridge needs secret-env"
                            : "unconfigured: environment variable " + w.secretEnv() + " is not set";
                }
            } else {
                secret = s.getBytes(StandardCharsets.UTF_8);
            }
        }
        return new Bridge(w.name(), w.format(), url, secret, status, w.routes());
    }

    public List<Bridge> all() {
        return bridges;
    }

    public Optional<Bridge> find(String name) {
        return bridges.stream().filter(b -> b.name().equals(name)).findFirst();
    }

    /**
     * The bridges that want this event: some route lists the event and covers the pack (or any pack) and the kind (or any kind).
     * An unknown pack matches only routes that name no pack.
     */
    public List<Bridge> matching(String event, String kind, String pack) {
        List<Bridge> out = new ArrayList<>();
        for (Bridge b : bridges) {
            for (CollabProperties.Route r : b.routes()) {
                if (r.events().contains(event) && (r.packs().isEmpty() || pack != null && r.packs().contains(pack))
                        && (r.kinds().isEmpty() || r.kinds().contains(kind))) {
                    out.add(b);
                    break;
                }
            }
        }
        return out;
    }
}
