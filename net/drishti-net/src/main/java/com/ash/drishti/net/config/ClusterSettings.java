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

import com.ash.drishti.api.Roots;
import com.ash.drishti.settingsfile.Placeholders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The cluster's settings of the settings file (CLUSTER_I5_DESIGN.md section 4.4): the sections {@code [net.cluster]}, {@code [net.node]},
 * {@code [net.membership]}, {@code [net.agreement]}, {@code [net.tls]} and {@code [net.engines]}, read key by key through the one
 * placeholder resolver ({@link Placeholders}: a {@code -D}, the settings files, the environment, then the default here). Every path is
 * absolute (a relative one is refused, never taken under the working folder). The per-node file {@code config/net/node.yaml} may name
 * these keys as placeholders; a key node.yaml does not set is read here.
 *
 * <p>Multicast is never used (owner rule): the only discoveries are a static seed list over TCP ({@code seeds}, the default), a shared
 * folder ({@code file}), a database table ({@code jdbc}) and an S3 bucket ({@code s3}); any multicast form is refused by name.
 *
 * @param cluster {@code [net.cluster]}
 * @param node {@code [net.node]}
 * @param membership {@code [net.membership]}
 * @param agreement {@code [net.agreement]}
 * @param tls {@code [net.tls]}
 * @param engines {@code [net.engines]}
 */
public record ClusterSettings(Cluster cluster, Node node, Timings membership, AgreementSettings agreement, Tls tls, EngineCalls engines) {

    /** The default cluster port (TCP, the membership) and agreement port (TCP, ratis mode only). */
    public static final int CLUSTER_PORT = 7946;
    public static final int AGREEMENT_PORT = 7947;
    /** The failure detector's socket listens this far above the cluster port (JGroups FD_SOCK2's offset): open it between the nodes too. */
    public static final int FAILURE_PORT_OFFSET = 100;
    /** Discoveries that are never offered, each refused by name. */
    private static final Set<String> MULTICAST = Set.of("udp", "multicast", "mcast", "mping", "ping", "bping", "broadcast");

    /**
     * {@code [net.cluster]}.
     *
     * @param name a node of another cluster name never joins
     * @param environment dev, uat, prod or any word (promotion between clusters)
     * @param secretFile an absolute file holding the cluster key (instead of node.yaml's {@code secret: ${DRISHTI_NET_SECRET}}), or empty
     * @param seeds {@code host:port} of cluster ports to dial when joining (real addresses, never loopback)
     * @param discovery {@code seeds} (TCP to the seed list, the default), {@code file}, {@code jdbc} or {@code s3}
     * @param discoveryOptions the discovery's own keys ({@code discovery.dir}, {@code discovery.url}, {@code discovery.bucket}, ...)
     */
    public record Cluster(String name, String environment, String secretFile, List<String> seeds, String discovery, Map<String, String> discoveryOptions) {
        public Cluster {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            discoveryOptions = discoveryOptions == null ? Map.of() : Map.copyOf(discoveryOptions);
        }
    }

    /**
     * {@code [net.node]}.
     *
     * @param id the node's id (node.yaml's {@code node-id} wins), or empty
     * @param clusterPort the membership's TCP port (a number: seeds are dialled)
     * @param agreementPort the agreement's TCP port (ratis mode only)
     * @param role {@code server} or {@code witness}
     */
    public record Node(String id, int clusterPort, int agreementPort, String role) {}

    /**
     * {@code [net.membership]}: the membership's timings.
     *
     * @param heartbeat how often a member says it is alive (2 s)
     * @param suspectAfter a member silent this long is suspected (10 s)
     * @param verify how long a suspicion is verified before the member leaves the view (1.5 s)
     * @param joinTimeout how long a join waits for the coordinator before the node forms a view alone (5 s)
     * @param mergeEvery how often partitions look for each other (10 s)
     * @param vitalsEvery how often a member's vitals are published when nothing changed (10 s)
     * @param forgetAfter how long a member out of the view is remembered for display and discovery (24 h)
     */
    public record Timings(Duration heartbeat, Duration suspectAfter, Duration verify, Duration joinTimeout, Duration mergeEvery,
            Duration vitalsEvery, Duration forgetAfter) {}

    /**
     * {@code [net.agreement]}.
     *
     * @param mode {@code auto}, {@code local}, {@code database}, {@code ratis} or {@code first-live}
     * @param voters the voters' node ids (three or five), in order
     * @param dir the ratis log and snapshots folder (absolute), or empty for {@code <data dir>/net/agreement}
     * @param leaseTtl how long a lease lasts without a renewal (15 s)
     * @param leaseRenew how often a holder renews (5 s), at most half of {@code leaseTtl}
     * @param electionTimeout the Raft election time-out (2 s)
     * @param snapshotEvery a snapshot every this many log entries (10,000)
     */
    public record AgreementSettings(String mode, List<String> voters, String dir, Duration leaseTtl, Duration leaseRenew, Duration electionTimeout,
            long snapshotEvery) {
        public AgreementSettings {
            voters = voters == null ? List.of() : List.copyOf(voters);
        }
    }

    /**
     * {@code [net.tls]}: one certificate for the server port, the cluster port and the agreement port (PEM files, absolute).
     *
     * @param caFile the cluster's certificate authority
     * @param certFile this node's certificate (chain)
     * @param keyFile its private key
     * @param keyPassword the key's password when it is encrypted (from secrets.ini), or empty
     * @param clientAuth {@code need} (the default: mutual TLS) or {@code want}
     * @param reload a replaced file is picked up without a restart
     */
    public record Tls(String caFile, String certFile, String keyFile, String keyPassword, String clientAuth, boolean reload) {
        /** True when the three files are named. */
        public boolean configured() {
            return !caFile.isEmpty() && !certFile.isEmpty() && !keyFile.isEmpty();
        }

        /** Never prints the password. */
        @Override
        public String toString() {
            return "Tls[caFile=" + caFile + ", certFile=" + certFile + ", keyFile=" + keyFile + ", keyPassword=" + (keyPassword.isEmpty() ? "unset" : "set")
                    + ", clientAuth=" + clientAuth + ", reload=" + reload + "]";
        }
    }

    /**
     * {@code [net.engines]}: remote engine calls (section 5.4; used from step C5).
     *
     * @param callTimeout the default deadline of a remote engine call (30 s); an engine's own value wins
     * @param connectTimeout how long a connection to another node may take (2 s)
     * @param usesTimeout how long a {@code uses} extra is waited for before it is left out (5 s)
     * @param failover an idempotent call goes to the next placed node when one fails
     * @param breakerFailures failures in a row that open a node's circuit (5)
     * @param breakerOpenFor how long an open circuit skips the node (15 s)
     */
    public record EngineCalls(Duration callTimeout, Duration connectTimeout, Duration usesTimeout, boolean failover, int breakerFailures,
            Duration breakerOpenFor) {}

    /** What reading found: the settings (defaults where a key is unset or refused) and every problem, each naming its key. */
    public record Read(ClusterSettings settings, List<String> problems) {
        public Read {
            problems = List.copyOf(problems);
        }
    }

    /** Every default, as the design states them. */
    public static final ClusterSettings DEFAULTS = new ClusterSettings(
            new Cluster("drishti", "", "", List.of(), "seeds", Map.of()),
            new Node("", CLUSTER_PORT, AGREEMENT_PORT, "server"),
            new Timings(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(1500), Duration.ofSeconds(5), Duration.ofSeconds(10),
                    Duration.ofSeconds(10), Duration.ofHours(24)),
            new AgreementSettings("auto", List.of(), "", Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(2), 10_000),
            new Tls("", "", "", "", "need", true),
            new EngineCalls(Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(5), true, 5, Duration.ofSeconds(15)));

    /** Keys of the old gossip membership, each refused with its replacement (no server reads both). */
    private static final Map<String, String> GONE = Map.of(
            "net.membership.interval", "net.membership.heartbeat",
            "net.membership.fanout", "nothing: the membership sends to every member",
            "net.membership.indirect-probes", "nothing: a suspicion is verified by the membership (net.membership.verify)",
            "net.membership.dead-after", "net.membership.suspect-after and net.membership.verify",
            "net.membership.persist-every", "nothing: <data dir>/net/members.json is written on every view change and at shutdown",
            "net.node.gossip-port", "net.node.cluster-port");

    /** Reads the six sections through {@code view} (null: the process: {@code -D}, the settings files, the environment). */
    public static Read read(Function<String, String> view) {
        Reader r = new Reader(view);
        GONE.forEach((k, v) -> {
            if (!r.raw(k).isEmpty()) {
                r.problem(k, "is gone with the gossip membership: use " + v);
            }
        });
        ClusterSettings d = DEFAULTS;
        String discovery = r.string("net.cluster.discovery", d.cluster().discovery()).toLowerCase(Locale.ROOT);
        Map<String, String> options = new LinkedHashMap<>();
        if (MULTICAST.contains(discovery)) {
            r.problem("net.cluster.discovery", "'" + discovery + "' is multicast, which Drishti never uses: seeds (TCP to the seed list), file, jdbc or s3");
            discovery = "seeds";
        } else if (!Set.of("seeds", "file", "jdbc", "s3").contains(discovery)) {
            r.problem("net.cluster.discovery", "'" + discovery + "' is not seeds, file, jdbc or s3");
            discovery = "seeds";
        }
        switch (discovery) {
            case "file" -> options.put("dir", r.path("net.cluster.discovery.dir", true));
            case "jdbc" -> {
                options.put("url", r.required("net.cluster.discovery.url"));
                options.put("user", r.string("net.cluster.discovery.user", ""));
                options.put("password", r.string("net.cluster.discovery.password", ""));
            }
            case "s3" -> {
                options.put("bucket", r.required("net.cluster.discovery.bucket"));
                options.put("region", r.string("net.cluster.discovery.region", ""));
                options.put("prefix", r.string("net.cluster.discovery.prefix", ""));
            }
            default -> {
                // seeds: the list below
            }
        }
        Cluster cluster = new Cluster(r.string("net.cluster.name", d.cluster().name()), r.string("net.cluster.environment", ""),
                r.path("net.cluster.secret-file", false), r.seeds("net.cluster.seeds"), discovery, options);
        String role = r.string("net.node.role", "server").toLowerCase(Locale.ROOT);
        if (!Set.of("server", "witness").contains(role)) {
            r.problem("net.node.role", "'" + role + "' is not server or witness");
            role = "server";
        }
        Node node = new Node(r.string("net.node.id", ""), r.port("net.node.cluster-port", CLUSTER_PORT), r.port("net.node.agreement-port", AGREEMENT_PORT), role);
        if (node.clusterPort() == node.agreementPort()) {
            r.problem("net.node.agreement-port", "is the cluster port (" + node.clusterPort() + "): the agreement needs a port of its own");
        }
        if (node.agreementPort() == node.clusterPort() + FAILURE_PORT_OFFSET) {
            r.problem("net.node.agreement-port", "is the failure detector's port (cluster-port + " + FAILURE_PORT_OFFSET + ")");
        }
        Timings t = d.membership();
        Timings membership = new Timings(r.duration("net.membership.heartbeat", t.heartbeat(), Duration.ofMillis(100), Duration.ofMinutes(1)),
                r.duration("net.membership.suspect-after", t.suspectAfter(), Duration.ofMillis(500), Duration.ofMinutes(10)),
                r.duration("net.membership.verify", t.verify(), Duration.ofMillis(100), Duration.ofMinutes(1)),
                r.duration("net.membership.join-timeout", t.joinTimeout(), Duration.ofMillis(200), Duration.ofMinutes(5)),
                r.duration("net.membership.merge-every", t.mergeEvery(), Duration.ofSeconds(1), Duration.ofMinutes(10)),
                r.duration("net.membership.vitals-every", t.vitalsEvery(), Duration.ofMillis(200), Duration.ofMinutes(10)),
                r.duration("net.membership.forget-after", t.forgetAfter(), Duration.ofSeconds(1), Duration.ofDays(30)));
        if (membership.suspectAfter().compareTo(membership.heartbeat().multipliedBy(2)) < 0) {
            r.problem("net.membership.suspect-after", "must be at least two heartbeats (" + NetSchema.text(membership.heartbeat().multipliedBy(2)) + ")");
        }
        AgreementSettings a = d.agreement();
        String mode = r.string("net.agreement.mode", a.mode()).toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "local", "database", "ratis", "first-live").contains(mode)) {
            r.problem("net.agreement.mode", "'" + mode + "' is not auto, local, database, ratis or first-live");
            mode = "auto";
        }
        List<String> voters = r.list("net.agreement.voters");
        if (!voters.isEmpty() && voters.size() != 2 && voters.size() != 3 && voters.size() != 5) {
            r.problem("net.agreement.voters", "names " + voters.size() + " voters: name three or five (two only for first-live or a witness)");
        }
        AgreementSettings agreement = new AgreementSettings(mode, voters, r.path("net.agreement.dir", false),
                r.duration("net.agreement.lease-ttl", a.leaseTtl(), Duration.ofMillis(500), Duration.ofMinutes(10)),
                r.duration("net.agreement.lease-renew", a.leaseRenew(), Duration.ofMillis(100), Duration.ofMinutes(5)),
                r.duration("net.agreement.election-timeout", a.electionTimeout(), Duration.ofMillis(100), Duration.ofMinutes(1)),
                r.integer("net.agreement.snapshot-every", a.snapshotEvery(), 100, 100_000_000));
        if (agreement.leaseRenew().multipliedBy(2).compareTo(agreement.leaseTtl()) > 0) {
            r.problem("net.agreement.lease-renew", "must be at most half of lease-ttl (" + NetSchema.text(agreement.leaseTtl()) + ")");
        }
        String auth = r.string("net.tls.client-auth", "need").toLowerCase(Locale.ROOT);
        if (!Set.of("need", "want").contains(auth)) {
            r.problem("net.tls.client-auth", "'" + auth + "' is not need or want");
            auth = "need";
        }
        Tls tls = new Tls(r.path("net.tls.ca-file", false), r.path("net.tls.cert-file", false), r.path("net.tls.key-file", false),
                r.string("net.tls.key-password", ""), auth, r.bool("net.tls.reload", true));
        int named = (tls.caFile().isEmpty() ? 0 : 1) + (tls.certFile().isEmpty() ? 0 : 1) + (tls.keyFile().isEmpty() ? 0 : 1);
        if (named != 0 && named != 3) {
            r.problem("net.tls", "names " + named + " of ca-file, cert-file and key-file: name all three, or none");
        }
        EngineCalls e = d.engines();
        EngineCalls engines = new EngineCalls(r.duration("net.engines.call-timeout", e.callTimeout(), Duration.ofMillis(100), Duration.ofHours(1)),
                r.duration("net.engines.connect-timeout", e.connectTimeout(), Duration.ofMillis(50), Duration.ofMinutes(1)),
                r.duration("net.engines.uses-timeout", e.usesTimeout(), Duration.ofMillis(50), Duration.ofMinutes(5)),
                r.bool("net.engines.failover", e.failover()),
                (int) r.integer("net.engines.breaker.failures", e.breakerFailures(), 1, 1000),
                r.duration("net.engines.breaker.open-for", e.breakerOpenFor(), Duration.ofMillis(100), Duration.ofHours(1)));
        return new Read(new ClusterSettings(cluster, node, membership, agreement, tls, engines), r.problems);
    }

    /** Reads keys through the view and collects the problems. */
    private static final class Reader {
        private final Function<String, String> view;
        private final List<String> problems = new ArrayList<>();

        Reader(Function<String, String> view) {
            this.view = view;
        }

        void problem(String key, String message) {
            problems.add(key + ": " + message);
        }

        /** The key's value with its own placeholders resolved, or empty when nothing names it. */
        String raw(String key) {
            String v = Placeholders.lookup(key, view);
            return v == null ? "" : Placeholders.resolve(v, view).strip();
        }

        String string(String key, String dflt) {
            String v = raw(key);
            return v.isEmpty() ? dflt : v;
        }

        String required(String key) {
            String v = raw(key);
            if (v.isEmpty()) {
                problem(key, "is needed by this discovery");
            }
            return v;
        }

        /** An absolute path, or empty; a relative one is refused (never taken under the working folder). */
        String path(String key, boolean needed) {
            String v = raw(key);
            if (v.isEmpty()) {
                if (needed) {
                    problem(key, "is needed: an absolute folder");
                }
                return "";
            }
            if (!Roots.isAbsolute(v)) {
                problem(key, "'" + v + "' is a relative path: every path is absolute (/etc/drishti/..., D:\\drishti\\...)");
                return "";
            }
            return v;
        }

        List<String> list(String key) {
            String v = raw(key);
            return v.isEmpty() ? List.of() : Arrays.stream(v.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
        }

        /** {@code host:port} entries on real addresses; a loopback, wildcard or malformed one is refused by name. */
        List<String> seeds(String key) {
            List<String> out = new ArrayList<>();
            for (String s : list(key)) {
                java.util.Optional<String> bad = seedProblem(s);
                if (bad.isPresent()) {
                    problem(key, bad.get());
                } else {
                    out.add(s);
                }
            }
            return List.copyOf(out);
        }

        int port(String key, int dflt) {
            return (int) integer(key, dflt, 1, 65535);
        }

        long integer(String key, long dflt, long min, long max) {
            String v = raw(key);
            if (v.isEmpty()) {
                return dflt;
            }
            try {
                long n = Long.parseLong(v.replace("_", ""));
                if (n < min || n > max) {
                    problem(key, n + " is outside " + min + " to " + max);
                    return dflt;
                }
                return n;
            } catch (NumberFormatException e) {
                problem(key, "'" + v + "' is not a whole number");
                return dflt;
            }
        }

        boolean bool(String key, boolean dflt) {
            String v = raw(key).toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                return dflt;
            }
            if (Set.of("on", "true", "yes").contains(v)) {
                return true;
            }
            if (Set.of("off", "false", "no").contains(v)) {
                return false;
            }
            problem(key, "'" + v + "' is not on or off");
            return dflt;
        }

        Duration duration(String key, Duration dflt, Duration min, Duration max) {
            String v = raw(key);
            if (v.isEmpty()) {
                return dflt;
            }
            try {
                Duration d = NetSchema.duration(v);
                if (d.compareTo(min) < 0 || d.compareTo(max) > 0) {
                    problem(key, v + " is outside " + NetSchema.text(min) + " to " + NetSchema.text(max));
                    return dflt;
                }
                return d;
            } catch (RuntimeException e) {
                problem(key, "'" + v + "' is not a duration (150ms, 5s, 10m, 24h)");
                return dflt;
            }
        }
    }

    /** What makes {@code host:port} no seed (malformed, no port, a loopback or wildcard host), or empty when it is one. */
    public static java.util.Optional<String> seedProblem(String hostPort) {
        String s = hostPort == null ? "" : hostPort.strip();
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return java.util.Optional.of("'" + s + "' is not host:port (10.20.1.31:" + CLUSTER_PORT + ")");
        }
        String url = "http://" + s;
        java.util.Optional<String> p = HostAddress.urlProblem(url);
        if (p.isPresent()) {
            return java.util.Optional.of("'" + s + "' is not host:port: " + p.get());
        }
        if (HostAddress.loopbackUrl(url)) {
            return java.util.Optional.of(HostAddress.refusal("a seed", url).replace("http://", ""));
        }
        return java.util.Optional.empty();
    }
}
