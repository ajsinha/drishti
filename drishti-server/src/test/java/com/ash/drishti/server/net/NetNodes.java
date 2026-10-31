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
package com.ash.drishti.server.net;

import com.ash.drishti.server.DrishtiApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Several Drishti servers in one test JVM (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 17): each a full Spring Boot context on its own port with its
 * own data folder, identity database and {@code config/net/} folder, talking to the others over real HTTP. Ports are the scratch
 * ports 18783 to 18785, or the three named by the system property {@code drishti.net.it.ports} or the environment variable
 * {@code DRISHTI_NET_IT_PORTS} (comma-separated: the drill gives its Java 25 stage 19783 to 19785, beside the console stage; for a builder whose
 * scratch ports are others); one cluster at a time.
 */
public final class NetNodes implements AutoCloseable {

    public static final String KEY = "a-test-cluster-key-of-at-least-32-bytes";
    public static final int[] PORTS = ports(System.getProperty("drishti.net.it.ports", System.getenv().getOrDefault("DRISHTI_NET_IT_PORTS", "18783,18784,18785")));
    /** This machine's real address: members never advertise a loopback one, so nodes on one machine call each other by it too. */
    public static final String HOST = com.ash.drishti.net.config.HostAddress.primary().orElse(null);

    private static int[] ports(String list) {
        int[] p = java.util.Arrays.stream(list.split(",")).map(String::trim).filter(x -> !x.isEmpty()).mapToInt(Integer::parseInt).toArray();
        if (p.length < 3) {
            throw new IllegalArgumentException("drishti.net.it.ports names " + p.length + " ports; a cluster needs three");
        }
        return p;
    }

    /** One node: its context, port and folders. */
    public record Node(String name, int port, Path root, ConfigurableApplicationContext ctx) {
        public String url() {
            return "http://" + HOST + ":" + port;
        }

        public NetService net() {
            return ctx.getBean(NetService.class);
        }

        public <T> T bean(Class<T> type) {
            return ctx.getBean(type);
        }
    }

    private final Path root;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /** Held while a cluster runs: test forks share the scratch ports, so only one cluster at a time across every fork. */
    private final java.nio.channels.FileChannel lockFile;
    private final java.nio.channels.FileLock lock;

    public NetNodes(Path root) throws IOException {
        this.root = root;
        Path lockPath = Path.of("target", "net-it.lock").toAbsolutePath();
        Files.createDirectories(lockPath.getParent());
        this.lockFile = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        this.lock = lockFile.lock();
    }

    /** Writes a node's config/net/ folder: node.yaml (enabled, its address, tags, the key) and one peer file per seed. */
    public static void folder(Path netDir, int port, List<String> tags, List<String> peerOnly, List<Integer> seeds) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(HOST != null, "this machine has no address but loopback: a cluster member never advertises one");
        Files.createDirectories(netDir.resolve("peers"));
        Files.writeString(netDir.resolve("node.yaml"), "enabled: true\nadvertise-url: http://" + HOST + ":" + port + "\ntags: " + tags
                + "\npeer-only-packs: " + peerOnly + "\nsecret: ${DRISHTI_NET_SECRET}\n");
        Files.writeString(netDir.resolve("gossip.yaml"), "interval: 1s\nsuspect-after: 3s\ndead-after: 4s\npersist-every: 2s\n");
        for (int s : seeds) {
            Files.writeString(netDir.resolve("peers").resolve("node-" + s + ".yaml"), "url: http://" + HOST + ":" + s + "\n");
        }
    }

    /** Starts a node on {@code port}; {@code extra} are more {@code --key=value} arguments (packs, security). */
    public Node start(String name, int port, String... extra) {
        return start(name, port, List.of(), extra);
    }

    /** As {@link #start(String, int, String...)}, with more classes registered as beans of the node (a test's slow start-up). */
    public Node start(String name, int port, List<Class<?>> beans, String... extra) {
        Path dir = root.resolve(name);
        List<String> args = new ArrayList<>(List.of("--server.port=" + port, "--drishti.data.dir=" + dir.resolve("data"),
                "--drishti.net.dir=" + dir.resolve("net"), "--drishti.net.poll=200ms",
                "--drishti.identity.database-url=jdbc:sqlite:" + dir.resolve("identity").resolve("identity.db"),
                "--drishti.identity.users-file=" + dir.resolve("identity").resolve("users.json"),
                "--drishti.identity.audit-file=" + dir.resolve("identity").resolve("audit.jsonl"),
                "--drishti.identity.preferences-dir=" + dir.resolve("identity").resolve("preferences"),
                "--drishti.sources.connectors-dir=" + dir.resolve("connectors"), "--drishti.governance.dir=" + dir.resolve("governance"),
                "--drishti.rachana.hot-reload=false", "--drishti.sources.plugins.demo.settings.ticking=false",
                "--DRISHTI_NET_SECRET=" + KEY, "--spring.main.banner-mode=off"));
        // an extra argument replaces a default one of the same key (two values of one key would be read as "a,b")
        for (String e : extra) {
            String key = e.contains("=") ? e.substring(0, e.indexOf('=') + 1) : e;
            args.removeIf(a -> a.startsWith(key));
            args.add(e);
        }
        List<Class<?>> sources = new ArrayList<>(List.of(DrishtiApplication.class));
        sources.addAll(beans);
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new)).run(args.toArray(String[]::new));
        Node n = new Node(name, port, dir, ctx);
        nodes.put(name, n);
        return n;
    }

    public Node node(String name) {
        return nodes.get(name);
    }

    /** Stops only the node's web server: its gossip goes on, so peers still take it for alive, but its port refuses connections. */
    public void closePort(String name) {
        ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) nodes.get(name).ctx()).getWebServer().stop();
    }

    public void stop(String name) {
        Node n = nodes.remove(name);
        if (n != null) {
            n.ctx().close();
        }
    }

    /** Waits up to {@code ms} for a condition, polling every 100 ms; true when it held. */
    public static boolean await(long ms, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return ok.getAsBoolean();
    }

    /** A plain (unsigned) request as a browser or the console makes it. */
    public HttpResponse<String> call(String method, String url, String body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        try {
            for (String n : new ArrayList<>(nodes.keySet())) {
                stop(n);
            }
        } finally {
            try {
                lock.release();
                lockFile.close();
            } catch (IOException e) {
                // the lock goes with the JVM
            }
        }
    }
}
