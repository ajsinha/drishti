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
package com.ash.drishti.net.config;

import com.ash.drishti.net.member.NodeIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The schema of every file of {@code config/net/}: each file's map is checked and turned into its settings, with every problem
 * named by file, setting and line. An error refuses the file (the previous settings stay in force); a warning (an unknown
 * setting) does not. Values written as {@code ${ENV}} or {@code ${ENV:default}} are read from the environment; the cluster
 * keys must be such a reference and nothing else, so a key is never written in a file in clear.
 */
public final class NetSchema {

    /** One thing wrong with a file. {@code level} is {@code error} (the file is refused) or {@code warning}. */
    public record Problem(String file, String level, String field, String message, int line) {
        public boolean error() {
            return "error".equals(level);
        }

        @Override
        public String toString() {
            return file + (line > 0 ? ":" + line : "") + (field == null || field.isEmpty() ? "" : " " + field) + ": " + message;
        }
    }

    /** A parsed file: its settings (null when refused) and its problems. */
    public record Parsed<T>(T value, List<Problem> problems) {
        public boolean ok() {
            return value != null && problems.stream().noneMatch(Problem::error);
        }
    }

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)(?::([^}]*))?}");
    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_.]*(:)?}");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h|d)");
    /** The least a cluster key may be, as the token secret (TokenVerifier). */
    public static final int MIN_SECRET_BYTES = 32;

    private final Function<String, String> env;

    /** {@code env} reads an environment variable (or a system property): null when unset. */
    public NetSchema(Function<String, String> env) {
        this.env = env;
    }

    // -- node.yaml ----------------------------------------------------------------------------------------------------

    public Parsed<NodeSettings> node(String file, Map<String, Object> m, String text) {
        Ctx c = new Ctx(file, text, m, Set.of("enabled", "node-id", "advertise-url", "tags", "peer-only-packs", "secret", "previous-secret", "tls"));
        boolean enabled = c.bool("enabled", false);
        String nodeId = c.string("node-id", "");
        if (!nodeId.isEmpty() && !NodeIdentity.ID.matcher(nodeId).matches()) {
            c.error("node-id", "'" + nodeId + "' is not a node id: lower-case letters, digits and dashes, at most 64");
        }
        String url = c.string("advertise-url", "");
        if (!url.isEmpty()) {
            if (HostAddress.loopbackUrl(url)) {                                // first: it names the address to use instead
                c.error("advertise-url", HostAddress.refusal("the address this node advertises", url)
                        + "; or leave advertise-url out and the node advertises that address with its port");
            }
            c.url("advertise-url", url);
        }
        List<String> tags = c.tags("tags");
        List<String> peerOnly = c.list("peer-only-packs");
        String secret = c.secret("secret");
        String previous = c.secret("previous-secret");
        Map<String, String> tls = c.flat("tls");
        if (enabled) {
            if (secret.isEmpty()) {
                c.error("secret", "enabled: true needs the cluster key: secret: ${DRISHTI_NET_SECRET} with the variable set (at least "
                        + MIN_SECRET_BYTES + " bytes), as the server refuses a missing token secret with security on");
            }
            if (url.isEmpty() && HostAddress.primary().isEmpty()) {
                c.error("advertise-url", "enabled: true needs advertise-url (the address peers and consoles call this node at, https://host:port): "
                        + "this machine has no address but loopback to advertise");
            }
        }
        if (!secret.isEmpty() && secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            c.error("secret", "the cluster key must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (!previous.isEmpty() && previous.equals(secret)) {
            c.warning("previous-secret", "is the same as secret: remove it once every node has the new key");
        }
        return c.done(new NodeSettings(enabled, nodeId, url, tags, peerOnly, secret, previous, tls));
    }

    // -- peers/<name>.yaml --------------------------------------------------------------------------------------------

    public Parsed<SeedPeer> peer(String file, String name, Map<String, Object> m, String text) {
        Ctx c = new Ctx(file, text, m, Set.of("url", "tags", "tls"));
        String url = c.string("url", "");
        if (url.isEmpty()) {
            c.error("url", "a peer file needs url: the peer's address (https://host:port)");
        } else {
            if (HostAddress.loopbackUrl(url)) {
                c.error("url", HostAddress.refusal("a peer's address", url));
            }
            c.url("url", url);
        }
        return c.done(new SeedPeer(name, url, c.tags("tags"), c.flat("tls")));
    }

    // -- gossip.yaml --------------------------------------------------------------------------------------------------

    public Parsed<GossipSettings> gossip(String file, Map<String, Object> m, String text) {
        GossipSettings d = GossipSettings.DEFAULTS;
        Ctx c = new Ctx(file, text, m, Set.of("interval", "fanout", "suspect-after", "indirect-probes", "dead-after", "forget-after", "persist-every"));
        GossipSettings g = new GossipSettings(c.duration("interval", d.interval(), Duration.ofMillis(50), Duration.ofMinutes(1)),
                c.integer("fanout", d.fanout(), 1, 16), c.duration("suspect-after", d.suspectAfter(), Duration.ofMillis(100), Duration.ofHours(1)),
                c.integer("indirect-probes", d.indirectProbes(), 0, 8), c.duration("dead-after", d.deadAfter(), Duration.ofMillis(100), Duration.ofDays(1)),
                c.duration("forget-after", d.forgetAfter(), Duration.ofSeconds(1), Duration.ofDays(30)),
                c.duration("persist-every", d.persistEvery(), Duration.ofSeconds(1), Duration.ofDays(1)));
        if (g.suspectAfter().compareTo(g.interval()) < 0) {
            c.error("suspect-after", "must be at least interval (" + text(g.interval()) + "): a member is suspected only after rounds that missed it");
        }
        return c.done(g);
    }

    // -- routing.yaml -------------------------------------------------------------------------------------------------

    public Parsed<RoutingSettings> routing(String file, Map<String, Object> m, String text) {
        RoutingSettings d = RoutingSettings.DEFAULTS;
        Ctx c = new Ctx(file, text, m, Set.of("forward-timeout", "scatter-budget", "suggest-budget", "max-hops", "weights", "user-state",
                "link-budget", "join-wait", "join-grace"));
        Map<String, Double> weights = new LinkedHashMap<>(d.weights());
        if (m.get("weights") instanceof Map<?, ?> w) {
            w.forEach((k, v) -> {
                try {
                    weights.put(String.valueOf(k), Double.parseDouble(String.valueOf(resolve(String.valueOf(v)))));
                } catch (NumberFormatException e) {
                    c.error("weights." + k, "'" + v + "' is not a number");
                }
            });
        } else if (m.containsKey("weights")) {
            c.error("weights", "weights is a mapping: { latency: 1.0 }");
        }
        String state = c.string("user-state", d.userState());
        if (!Set.of("replicate", "off").contains(state)) {
            c.error("user-state", "'" + state + "' is not replicate or off");
        }
        return c.done(new RoutingSettings(c.duration("forward-timeout", d.forwardTimeout(), Duration.ofMillis(50), Duration.ofMinutes(5)),
                c.duration("scatter-budget", d.scatterBudget(), Duration.ofMillis(50), Duration.ofMinutes(5)),
                c.duration("suggest-budget", d.suggestBudget(), Duration.ofMillis(10), Duration.ofSeconds(10)),
                c.integer("max-hops", d.maxHops(), 0, 1), weights, state,
                c.duration("link-budget", d.linkBudget(), Duration.ofMillis(10), Duration.ofSeconds(30)),
                c.duration("join-wait", d.joinWait(), Duration.ZERO, Duration.ofSeconds(30)),
                c.duration("join-grace", d.joinGrace(), Duration.ZERO, Duration.ofMinutes(10))));
    }

    // -- access.yaml --------------------------------------------------------------------------------------------------

    public Parsed<AccessSettings> access(String file, Map<String, Object> m, String text) {
        Ctx c = new Ctx(file, text, m, Set.of("roles"));
        Map<String, List<String>> roles = new LinkedHashMap<>();
        Object r = m.get("roles");
        if (r instanceof Map<?, ?> rm) {
            rm.forEach((role, spec) -> {
                if (spec == null) {
                    roles.put(String.valueOf(role), List.of());
                } else if (spec instanceof Map<?, ?> sm && (sm.get("nodes") == null || sm.get("nodes") instanceof List<?>)) {
                    List<String> nodes = new ArrayList<>();
                    if (sm.get("nodes") instanceof List<?> l) {
                        l.forEach(x -> nodes.add(String.valueOf(resolve(String.valueOf(x))).trim()));
                    }
                    sm.keySet().stream().filter(k -> !"nodes".equals(k)).forEach(k -> c.warning("roles." + role + "." + k, "unknown setting; ignored"));
                    roles.put(String.valueOf(role), List.copyOf(nodes));
                } else {
                    c.error("roles." + role, "a role is { nodes: [<node id or tag>, ...] }");
                }
            });
        } else if (r != null) {
            c.error("roles", "roles is a mapping: { <role>: { nodes: [<node id or tag>, ...] } }");
        }
        return c.done(new AccessSettings(roles));
    }

    // -- values -------------------------------------------------------------------------------------------------------

    /** {@code ${ENV}} and {@code ${ENV:default}} replaced by the environment's value (the default, or empty, when unset). */
    public String resolve(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher mt = ENV.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (mt.find()) {
            String v = env.apply(mt.group(1));
            mt.appendReplacement(out, Matcher.quoteReplacement(v != null ? v : mt.group(2) != null ? mt.group(2) : ""));
        }
        mt.appendTail(out);
        return out.toString();
    }

    /** {@code 150ms}, {@code 5s}, {@code 10m}, {@code 24h}, {@code 1d}, or an ISO-8601 duration ({@code PT5S}). */
    public static Duration duration(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        Matcher mt = DURATION.matcher(t);
        if (mt.matches()) {
            double n = Double.parseDouble(mt.group(1));
            long ms = switch (mt.group(2)) {
                case "ms" -> Math.round(n);
                case "s" -> Math.round(n * 1000);
                case "m" -> Math.round(n * 60_000);
                case "h" -> Math.round(n * 3_600_000);
                default -> Math.round(n * 86_400_000);
            };
            return Duration.ofMillis(ms);
        }
        return Duration.parse(text.trim().toUpperCase(Locale.ROOT));
    }

    /** A duration as a file would say it: {@code 150ms}, {@code 5s}, {@code 10m}, {@code 24h}. */
    public static String text(Duration d) {
        long ms = d.toMillis();
        if (ms % 3_600_000 == 0 && ms > 0) {
            return ms / 3_600_000 + "h";
        }
        if (ms % 60_000 == 0 && ms > 0) {
            return ms / 60_000 + "m";
        }
        if (ms % 1000 == 0) {
            return ms / 1000 + "s";
        }
        return ms + "ms";
    }

    /** One file being checked: its map, its text (for line numbers) and its problems. */
    private final class Ctx {
        private final String file;
        private final String text;
        private final Map<String, Object> m;
        private final List<Problem> problems = new ArrayList<>();

        Ctx(String file, String text, Map<String, Object> m, Set<String> known) {
            this.file = file;
            this.text = text == null ? "" : text;
            this.m = m;
            m.keySet().stream().filter(k -> !known.contains(k)).forEach(k -> warning(k, "unknown setting; ignored (known: " + String.join(", ", known.stream().sorted().toList()) + ")"));
        }

        <T> Parsed<T> done(T value) {
            boolean refused = problems.stream().anyMatch(Problem::error);
            return new Parsed<>(refused ? null : value, List.copyOf(problems));
        }

        void error(String field, String message) {
            problems.add(new Problem(file, "error", field, message, line(field)));
        }

        void warning(String field, String message) {
            problems.add(new Problem(file, "warning", field, message, line(field)));
        }

        /** The line the setting is on (its last dotted part, searched from the top), or 0. */
        private int line(String field) {
            if (field == null || field.isEmpty()) {
                return 0;
            }
            String key = field.substring(field.lastIndexOf('.') + 1);
            String[] lines = text.split("\n", -1);
            Pattern p = Pattern.compile("^\\s*-?\\s*" + Pattern.quote(key) + "\\s*:");
            for (int i = 0; i < lines.length; i++) {
                if (p.matcher(lines[i]).find()) {
                    return i + 1;
                }
            }
            return 0;
        }

        String string(String key, String dflt) {
            Object v = m.get(key);
            if (v == null) {
                return dflt;
            }
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                error(key, "is a single value, not a list or a mapping");
                return dflt;
            }
            return resolve(String.valueOf(v)).trim();
        }

        boolean bool(String key, boolean dflt) {
            String s = string(key, String.valueOf(dflt)).toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                return dflt;
            }
            if (!Set.of("true", "false").contains(s)) {
                error(key, "'" + s + "' is not true or false");
                return dflt;
            }
            return Boolean.parseBoolean(s);
        }

        int integer(String key, int dflt, int min, int max) {
            String s = string(key, String.valueOf(dflt));
            try {
                int n = Integer.parseInt(s);
                if (n < min || n > max) {
                    error(key, n + " is outside " + min + " to " + max);
                    return dflt;
                }
                return n;
            } catch (NumberFormatException e) {
                error(key, "'" + s + "' is not a whole number");
                return dflt;
            }
        }

        Duration duration(String key, Duration dflt, Duration min, Duration max) {
            if (!m.containsKey(key)) {
                return dflt;
            }
            String s = string(key, "");
            try {
                Duration d = NetSchema.duration(s);
                if (d.compareTo(min) < 0 || d.compareTo(max) > 0) {
                    error(key, s + " is outside " + text(min) + " to " + text(max));
                    return dflt;
                }
                return d;
            } catch (RuntimeException e) {
                error(key, "'" + s + "' is not a duration (150ms, 5s, 10m, 24h)");
                return dflt;
            }
        }

        List<String> list(String key) {
            Object v = m.get(key);
            if (v == null) {
                return List.of();
            }
            if (v instanceof List<?> l) {
                return l.stream().map(x -> resolve(String.valueOf(x)).trim()).filter(x -> !x.isEmpty()).toList();
            }
            String s = resolve(String.valueOf(v)).trim();
            return s.isEmpty() ? List.of() : java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        }

        List<String> tags(String key) {
            List<String> tags = list(key);
            for (String t : tags) {
                if (!t.matches("[A-Za-z0-9][A-Za-z0-9._-]*(:[A-Za-z0-9._-]+)?")) {
                    error(key, "'" + t + "' is not a tag: a word or key:value (region:emea)");
                }
            }
            return tags;
        }

        void url(String key, String url) {
            HostAddress.urlProblem(url).ifPresent(p -> error(key, p));
        }

        /** A cluster key: only an environment reference, read here; empty when absent or unset. */
        String secret(String key) {
            Object v = m.get(key);
            if (v == null) {
                return "";
            }
            String raw = String.valueOf(v).trim();
            if (raw.isEmpty()) {
                return "";
            }
            if (!SECRET_REF.matcher(raw).matches()) {
                error(key, "the cluster key is written only as an environment reference (" + key + ": ${DRISHTI_NET_"
                        + key.replace('-', '_').toUpperCase(Locale.ROOT) + "}), never in clear and with no default");
                return "";
            }
            return resolve(raw);
        }

        /** A nested mapping flattened to dotted keys ({@code tls: {keystore: {path: x}}} is {@code keystore.path}). */
        Map<String, String> flat(String key) {
            Map<String, String> out = new LinkedHashMap<>();
            Object v = m.get(key);
            if (v instanceof Map<?, ?> mm) {
                flatten("", mm, out);
            } else if (v != null) {
                error(key, "is a mapping");
            }
            return out;
        }

        private void flatten(String prefix, Map<?, ?> mm, Map<String, String> out) {
            mm.forEach((k, v) -> {
                String name = prefix + k;
                if (v instanceof Map<?, ?> inner) {
                    flatten(name + ".", inner, out);
                } else if (v != null) {
                    out.put(name, resolve(String.valueOf(v)));
                }
            });
        }
    }
}
