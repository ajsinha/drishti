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
package com.ash.drishti.server.nativelib;

import com.ash.drishti.nativ.core.RequiredLibraries;
import com.ash.drishti.nativ.linalg.Blas;
import com.ash.drishti.nativ.store.DiskStores;
import com.ash.drishti.server.net.DigestService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The server's native libraries (HIGH_PERFORMANCE_NATIVE.md section 6; OPERATIONS.md section 2.1): binds BLAS once at start from
 * {@code drishti.native.*} before any engine sums ({@code VarConfiguration} asks for this bean first), and says what it chose:
 *
 * <ul>
 *   <li>the start-up log: one INFO line on OpenBLAS or when Java is chosen; a WARN banner when {@code auto} fell back to Java (what failed,
 *       why, what it means, how to fix it, how to silence it), and again if the breaker switches OpenBLAS off during the run;
 *   <li>Admin -> Health and {@code GET /api/v1/admin/health} ({@code native.blas}, {@code DEGRADED}, never down), the Engines page, and
 *       {@code drishti.py server health};
 *   <li>the meters {@code drishti.native.blas} ({@code path}, {@code reason}; 1) and {@code drishti.native.blas.fallback} (1 on the Java
 *       path in auto), which the platform alert {@code native-blas-fallback} watches: the administrators are told in their inbox;
 *   <li>the cluster digest (service {@code native-blas}): each member's binding, shown per node.
 * </ul>
 *
 * It also sets the disk store's mode ({@code drishti.native.store.mode}: RocksDB, else H2's MVStore; DRISHTI_NATIVE.md section 2) before
 * any connector opens a disk cache, and reports which serves ({@link #store}); and it lists DuckDB and SQLite, required without a fallback
 * (DRISHTI_NATIVE.md sections 2.1 and 2.2; {@link RequiredLibraries}). Nothing here can stop the server.
 */
@Component
public class NativeLibraries {

    private static final Logger LOG = LoggerFactory.getLogger(NativeLibraries.class);

    /** The digest service's name for this node's BLAS binding. */
    public static final String SERVICE = "native-blas";

    private final String unknownMode;
    private final Duration canary;
    private MultiGauge path;
    private MultiGauge fallback;
    private volatile DigestService digests;

    public NativeLibraries(Environment env, ObjectProvider<MeterRegistry> registry) {
        DiskStores.configure(env.getProperty(DiskStores.MODE_PROPERTY, "auto"));
        DiskStores.Status store = DiskStores.status();
        LOG.info("Disk store: {}{}", store.impl() + (store.version() == null ? "" : " " + store.version()),
                store.reason() == null ? "" : " (" + store.reason() + ")");
        for (RequiredLibraries.Status r : RequiredLibraries.status()) {     // DuckDB and SQLite: required, no fallback
            if (r.loaded()) {
                LOG.info("{}", r.line());
            } else {
                LOG.error("{}. Use a supported platform (Linux, Windows or macOS) with the temporary folder writable.", r.line());
            }
        }
        String mode = env.getProperty("drishti.native.blas.mode", "auto");
        this.unknownMode = Blas.Mode.known(mode) ? null : mode;
        String dataDir = env.getProperty("drishti.data.dir", "./data");
        String cache = env.getProperty("drishti.native.cache-dir", "");
        this.canary = durationOr(env.getProperty("drishti.native.blas.canary-interval", "6h"), Duration.ofHours(6));
        Blas.Settings settings = new Blas.Settings(Blas.Mode.of(mode), cache.isBlank() ? Path.of(dataDir, "native-cache") : Path.of(cache),
                env.getProperty("drishti.native.blas.max-call-mb", Long.class, 256L) << 20, env.getProperty("drishti.native.max-bytes-mb", Long.class, 1024L) << 20);
        if (!env.getProperty("drishti.engines.openblas.enabled", Boolean.class, false)) {   // Admin -> Engines left it off: not even loaded
            String by = env.getProperty("drishti.engines.openblas.changed.by", "");
            String at = env.getProperty("drishti.engines.openblas.changed.at", "");
            Blas.switchToJava("switched off in Admin -> Engines" + (by.isBlank() ? "" : " by " + by) + (at.isBlank() ? "" : " at " + at)
                    + " (config/engines/openblas.yaml)");
        }
        Blas.Status s;
        try {
            s = Blas.bind(settings);
        } catch (RuntimeException | LinkageError e) {                   // Blas never throws; this is the last guard of "never stops the server"
            LOG.warn("BLAS: binding failed unexpectedly, the Java path serves: {}", e.toString());
            s = Blas.status();
        }
        if (unknownMode != null) {
            LOG.warn("BLAS: drishti.native.blas.mode '{}' is not auto or java; read as auto", unknownMode);
        }
        say(s);
        Blas.onTrip(reason -> {
            say(Blas.status());
            gauges();
            DigestService d = digests;
            if (d != null) {
                d.announce();
            }
        });
        Blas.scheduleCanary(canary);
        com.ash.drishti.compute.vector.MultiSums.configure(env.getProperty("drishti.native.blas.gemm-min-sets", Integer.class, 32),
                env.getProperty("drishti.native.blas.gemm-min-density", Double.class, 0.25));
        MeterRegistry r = registry.getIfAvailable();
        if (r != null) {
            path = MultiGauge.builder("drishti.native.blas").description("1 for the BLAS path in use (path native or java, and why java)").register(r);
            fallback = MultiGauge.builder("drishti.native.blas.fallback").description("1 while auto mode runs on the pure-Java path (0 on OpenBLAS or when java is chosen)")
                    .register(r);
            gauges();
        }
    }

    /** The log: a WARN banner for a fallback, else one INFO line. */
    static void say(Blas.Status s) {
        List<String> banner = s.banner();
        if (banner.isEmpty()) {
            LOG.info("{}", s.line());
            return;
        }
        for (String line : banner) {
            LOG.warn("{}", line);
        }
    }

    /** An administrator switched the path live (OpenBlasEngine): the log line, the meters and the digest follow. */
    void switched() {
        Blas.Status s = Blas.status();
        LOG.info("{}", s.line());
        gauges();
        DigestService d = digests;
        if (d != null) {
            d.announce();
        }
    }

    private void gauges() {
        if (path == null) {
            return;
        }
        Blas.Status s = Blas.status();
        String reason = reasonTag(s);
        path.register(List.of(MultiGauge.Row.of(Tags.of("path", s.isNative() ? "native" : "java", "reason", reason), 1)), true);
        fallback.register(List.of(MultiGauge.Row.of(Tags.of("reason", reason), s.degraded() ? 1 : 0)), true);
    }

    /** The reason as a tag: short, one line ({@code none} on OpenBLAS). */
    static String reasonTag(Blas.Status s) {
        String r = s.reason() == null ? "none" : s.reason().replaceAll("\\s+", " ");
        return r.length() > 120 ? r.substring(0, 117) + "..." : r;
    }

    /** This node's binding as the digest carries it: {@code native 0.3.26} or {@code java <reason>}. */
    static String wire(Blas.Status s) {
        return s.isNative() ? "native " + (s.version() == null ? "" : s.version()) : (s.degraded() ? "java-fallback " : "java ") + reasonTag(s);
        // an administrator's choice reads "java chosen by admin: switched off by ..." (the cluster agreement counts it as Java)
    }

    /** When the server is ready: the digest carries this node's binding, so Admin shows every member's. */
    @EventListener(ApplicationReadyEvent.class)
    public void publish(ApplicationReadyEvent e) {
        e.getApplicationContext().getBeanProvider(DigestService.class).ifAvailable(d -> {
            digests = d;
            d.service(SERVICE, () -> wire(Blas.status()));
            d.onChange(() -> agree(members()));                     // a member joined, left or changed its path: agree again
            d.announce();
        });
    }

    /** Each other member's binding as its digest says it (an older server, with no BLAS: {@code unknown}). */
    private Map<String, String> members() {
        Map<String, String> members = new LinkedHashMap<>();
        DigestService d = digests;
        if (d != null) {
            d.peers().forEach((node, digest) -> {
                String w = digest.services().get(SERVICE);
                members.put(node, w == null ? "unknown (an older server: Java)" : w);
            });
        }
        return members;
    }

    /**
     * The cluster agrees on one path for the sums members combine or compare (HIGH_PERFORMANCE_NATIVE.md section 6.6): when any member runs
     * on Java (or is an older server, which sums in Java), every member sums them on Java, so all give the same bits; never a refusal. Said
     * loudly in the log, naming the members, each time the agreement changes.
     *
     * @return the reason the cluster sums on Java, or null (OpenBLAS everywhere)
     */
    static String agree(Map<String, String> members) {
        List<String> onJava = members.entrySet().stream().filter(m -> m.getValue() == null || !m.getValue().startsWith("native"))
                .map(m -> m.getKey() + " (" + m.getValue() + ")").toList();
        String reason = onJava.isEmpty() ? null : "member" + (onJava.size() == 1 ? " " : "s ") + String.join(", ", onJava)
                + " run" + (onJava.size() == 1 ? "s" : "") + " BLAS on Java: every member sums on Java so the cluster's figures agree";
        String before = Blas.clusterJava();
        Blas.clusterOnJava(reason);
        if (reason != null && !reason.equals(before) && Blas.status().isNative()) {
            LOG.warn("************************************************************************************************");
            LOG.warn("CLUSTER BLAS ON JAVA: {}", reason);
            LOG.warn("This member keeps OpenBLAS loaded but sums many scopes on the pure-Java path (results identical, slower).");
            LOG.warn("Fix the member(s) named (Admin -> Health, Native libraries), or run every member with drishti.native.blas.mode: java.");
            LOG.warn("************************************************************************************************");
        } else if (reason == null && before != null) {
            LOG.info("BLAS: every member runs OpenBLAS again; many-scope sums use it");
        }
        return reason;
    }

    /** {@code status} ({@code OK}, {@code DEGRADED}), this node's binding, the settings read, and each other member's binding. */
    public Map<String, Object> describe() {
        Blas.Status s = Blas.status();
        Map<String, Object> m = new LinkedHashMap<>(s.toMap());
        m.put("banner", s.banner());
        m.put("unknownMode", unknownMode);
        m.put("canaryInterval", canary.toString());
        Map<String, String> members = members();
        m.put("members", members);
        m.put("clusterPath", Blas.clusterJava() == null ? (s.isNative() ? "native" : "java") : "java: " + Blas.clusterJava());
        return m;
    }

    /** The disk store of this node: {@code status} ({@code OK}, {@code DEGRADED} when auto fell back to MVStore), impl, mode, reason, version. */
    public Map<String, Object> store() {
        return DiskStores.status().toMap();
    }

    /** DuckDB and SQLite (required, no fallback): {@code status} {@code OK} or {@code DOWN}, version, reason, line. */
    public Map<String, Map<String, Object>> required() {
        return RequiredLibraries.toMap();
    }

    /** Everything Health and Admin -> Engines show under {@code native}: {@code blas}, {@code store}, {@code duckdb}, {@code sqlite}. */
    public Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blas", describe());
        m.put("store", store());
        m.putAll(required());
        return m;
    }

    /** True when auto mode runs on the Java path (health DEGRADED). */
    public boolean degraded() {
        return Blas.status().degraded();
    }

    private static Duration durationOr(String v, Duration d) {
        try {
            return org.springframework.boot.convert.DurationStyle.detectAndParse(v);
        } catch (RuntimeException e) {
            return d;
        }
    }
}
