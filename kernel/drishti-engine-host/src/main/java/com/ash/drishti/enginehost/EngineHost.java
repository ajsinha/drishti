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
package com.ash.drishti.enginehost;

import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.RequestClass;
import com.ash.engine.host.EngineFile;
import com.ash.engine.host.EngineLib;
import com.ash.engine.host.EngineTemplate;
import com.ash.engine.host.HealthBoard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engines of this node (ENGINE_INTEGRATION_DESIGN sections 0.4 and 11, the minimal host): at start the engine lib folder is read once
 * with {@code engine-host}'s {@link EngineLib} (a class loader per JAR, discovery by {@code ServiceLoader}, the version and licence checks),
 * each engine file of {@code config/engines} read strictly, every engine put in exactly one state with its words, and those switched on
 * started. No state of any engine stops the server, a module, a pack or another engine. Health and stats are polled onto a
 * {@link HealthBoard} (pages and metrics read memory, never an engine); the packs that use an engine are degraded while it is not serving.
 * Adding, replacing or removing a JAR takes effect at the next start (17.7); the switch, a restart of an engine and the lifecycle
 * transitions work live.
 */
public final class EngineHost implements AutoCloseable {

    /** Guards the blocking work below (a ReentrantLock: synchronized would pin a virtual thread). */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    private static final Logger LOG = LoggerFactory.getLogger(EngineHost.class);

    /** The next time a {@code cron} schedule fires after {@code now}, in {@code zone}; null when it never does. The server: Spring's CronExpression. */
    @FunctionalInterface
    public interface CronNext {
        Instant next(String cron, String zone, Instant now);
    }

    /** The ports a host is built with. */
    public record Ports(KindData data, KindContracts contracts, PackUses uses, WhereCompiler wheres, Outputs outputs, HostedEngine.Auditor audit,
            Set<String> visibilityRules, Function<String, String> placeholders, Predicate<String> inTreeEngine, CronNext cron) {}

    private final EngineHostSettings settings;
    private final ClassLoader platform;
    private final Ports ports;
    private final HealthBoard board = new HealthBoard();
    private final Map<String, HostedEngine> engines = new LinkedHashMap<>();
    private final Map<String, String> fileProblems = new TreeMap<>();
    private final List<String> packNotes = new ArrayList<>();
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private volatile EngineLib lib;
    private volatile FileLicence licence;
    private ScheduledExecutorService scheduler;

    /**
     * @param platform the loader of the platform classes the engines share (engine-api, tools, Lake, Rachana): the server's own
     */
    public EngineHost(EngineHostSettings settings, ClassLoader platform, Ports ports) {
        this.settings = settings;
        this.platform = platform;
        this.ports = ports;
    }

    /** Reads the folder and the files, puts every engine in its state, starts those switched on, and begins polling. Called once. */
    public void start() throws IOException {
        lock.lock();
        try {
            licence = FileLicence.read(settings.licenceFile());
            if (licence.problem() != null) {
                LOG.warn("engines: {}", licence.problem());
            }
            Map<String, EngineFile> files = new TreeMap<>();
            Map<String, Boolean> switches = new TreeMap<>();
            if (Files.isDirectory(settings.enginesDir())) {
                try (Stream<Path> s = Files.list(settings.enginesDir())) {
                    for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".yaml") && Files.isRegularFile(p)).sorted().toList()) {
                        String id = f.getFileName().toString().replaceFirst("\\.yaml$", "");
                        if (ports.inTreeEngine().test(id)) {
                            continue;                                            // a switch file of an engine built into this server
                        }
                        try {
                            EngineFile ef = EngineFile.read(id, ports.placeholders().apply(Files.readString(f, StandardCharsets.UTF_8)));
                            files.put(id, ef);
                            switches.put(id, ef.enabled());
                        } catch (EngineException e) {
                            fileProblems.put(id, e.getMessage());
                            switches.put(id, false);
                        }
                    }
                }
            }
            lib = EngineLib.scan(settings.libPath(), platform, settings.provided(), switches, licence);
            for (EngineLib.Entry e : lib.entries()) {
                HostedEngine h = new HostedEngine(e.id(), e, files.get(e.id()), fileProblems.get(e.id()), settings, ports.data(), ports.contracts(),
                        ports.wheres(), ports.outputs(), ports.audit(), ports.visibilityRules());
                engines.put(e.id(), h);
            }
            checkPackUses();
            for (HostedEngine h : engines.values()) {
                if (h.startable() && h.enabled()) {
                    try {
                        h.start("");
                        board.register(h.id(), h.instance());
                    } catch (EngineException ex) {
                        LOG.warn("engines: {} did not start: {} {}", h.id(), ex.code(), ex.getMessage());
                    }
                }
            }
            ports.uses().byPack().forEach(board::pack);
            LOG.info("engines: {}; {} in {}: {}", settings.provided().line(), lib.jars().size() == 1 ? "1 JAR" : lib.jars().size() + " JARs", settings.libPath(),
                    engines.values().stream().map(h -> h.id() + " " + h.state()).toList());
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("drishti-engine-host").factory());
            long poll = Math.max(1, settings.poll().toSeconds());
            timers.add(board.every(scheduler, poll, TimeUnit.SECONDS));
            timers.add(scheduler.scheduleWithFixedDelay(this::lookAtDataVersions, poll, poll, TimeUnit.SECONDS));
            engines.values().forEach(this::schedule);

        } finally {
            lock.unlock();
        }
    }

    /** A pack's use of an engine held against the installed descriptor (EN-41): the id is installed, the version within the range, the outputs exist. */
    private void checkPackUses() {
        for (PackUses.Use u : ports.uses().uses()) {
            HostedEngine h = engines.get(u.engine());
            Descriptor d = h == null ? null : h.descriptor();
            if (d == null) {
                packNotes.add("pack " + u.pack() + " uses " + u.engine() + ", which no JAR in " + settings.libPath() + " provides (ENG-5030)"
                        + (u.optional() ? "; the use is optional" : "; its uses are off"));
                continue;
            }
            if (!VersionRange.contains(u.version(), d.version())) {
                packNotes.add("pack " + u.pack() + " needs " + u.engine() + " " + u.version() + "; " + d.version() + " is installed: its uses are off (ENG-5032)");
            }
            for (String o : u.outputs()) {
                if (!d.outputs().containsKey(o)) {
                    packNotes.add("pack " + u.pack() + " uses " + u.engine() + "." + o + ", which " + u.engine() + " " + d.version()
                            + " does not answer; its outputs are " + d.outputs().keySet());
                }
            }
        }
    }

    /** The {@code schedule} triggers of a running engine: {@code every} on the host's scheduler, {@code cron} through the server's cron. */
    private void schedule(HostedEngine h) {
        if (h.file() == null || !h.startable()) {
            return;
        }
        for (EngineFile.Trigger t : h.file().triggers()) {
            if (!"schedule".equals(t.on())) {
                continue;
            }
            if (t.every() != null) {
                long ms = duration(t.every()).toMillis();
                timers.add(scheduler.scheduleWithFixedDelay(() -> fire(h, t.ask()), ms, ms, TimeUnit.MILLISECONDS));
            } else if (t.cron() != null && ports.cron() != null) {
                cron(h, t);
            }
        }
    }

    private void cron(HostedEngine h, EngineFile.Trigger t) {
        Instant next = ports.cron().next(t.cron(), t.zone() == null ? "UTC" : t.zone(), Instant.now());
        if (next == null || scheduler.isShutdown()) {
            return;
        }
        long delay = Math.max(0, Duration.between(Instant.now(), next).toMillis());
        timers.add(scheduler.schedule(() -> {
            fire(h, t.ask());
            cron(h, t);
        }, delay, TimeUnit.MILLISECONDS));
    }

    private void fire(HostedEngine h, String ask) {
        if (!"running".equals(h.state())) {
            return;
        }
        try {
            h.runAsk(ask, RequestClass.BATCH);
        } catch (EngineException | RuntimeException e) {
            LOG.warn("engines: {}'s scheduled ask {} did not answer: {}", h.id(), ask, e.getMessage());
        }
    }

    private void lookAtDataVersions() {
        for (HostedEngine h : engines.values()) {
            try {
                h.lookAtDataVersions();
            } catch (RuntimeException e) {
                LOG.warn("engines: looking at {}'s data versions failed: {}", h.id(), e.toString());
            }
        }
    }

    /** {@code 30s}, {@code 5m}, {@code 1h}, {@code 2d} or an ISO-8601 duration. */
    static Duration duration(String text) {
        String t = text.strip();
        if (t.matches("\\d+(ms|s|m|h|d)")) {
            long n = Long.parseLong(t.replaceAll("\\D", ""));
            return switch (t.replaceAll("\\d", "")) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
        }
        return Duration.parse(t);
    }

    // ------------------------------------------------------------------------------------------------------------- what others ask

    /** Every engine id known here (a JAR registers it, or an engine file names it), in name order. */
    public List<HostedEngine> engines() {
        return List.copyOf(engines.values());
    }

    public Optional<HostedEngine> engine(String id) {
        return Optional.ofNullable(engines.get(id));
    }

    /** The engine, or ENG-5030 in words when neither a JAR nor an engine file names it. */
    public HostedEngine require(String id) throws EngineException {
        HostedEngine h = engines.get(id);
        if (h == null) {
            throw new EngineException("ENG-5030", Kind.UNAVAILABLE, "no engine " + id + " here: no JAR in " + settings.libPath()
                    + " registers it and config/engines has no file for it");
        }
        return h;
    }

    /** Every JAR of the folder: file, SHA-384, engines and versions, publishers, certificate, notes (17.4 rule 5). */
    public List<EngineLib.JarLine> jars() {
        EngineLib l = lib;
        return l == null ? List.of() : l.jars();
    }

    /** "provides engine API 0.1, tools 1.1, Lake 0.9, Rachana 1.0" (17.5 rule 5). */
    public String platform() {
        return settings.provided().line();
    }

    public EngineHostSettings settings() {
        return settings;
    }

    /** The live and stored outputs. */
    public Outputs outputs() {
        return ports.outputs();
    }

    public FileLicence licence() {
        return licence;
    }

    /** What the running packs use of engines that does not hold, in words. */
    public List<String> packNotes() {
        return List.copyOf(packNotes);
    }

    /** The last poll of every running engine and the packs that use them: no engine is called. */
    public HealthBoard.Report report() {
        return board.report();
    }

    /** Polls every engine now (a test; Admin's refresh). */
    public void poll() {
        board.pollAll();
    }

    /** An engine file made from the engine's descriptor, for an engine that is installed but not configured. */
    public String template(String id) throws EngineException {
        Descriptor d = require(id).descriptor();
        if (d == null) {
            throw new EngineException("ENG-5030", Kind.UNAVAILABLE, id + " is not installed: no descriptor to make a template from");
        }
        return EngineTemplate.of(d);
    }

    /**
     * One lifecycle transition asked by a person or the switch: {@code init}, {@code start}, {@code suspend}, {@code resume},
     * {@code shutdown}. The health board follows the instance.
     */
    public void transition(String id, String action, String actor, String why) throws EngineException {
        lock.lock();
        try {
            HostedEngine h = require(id);
            switch (action) {
                case "init" -> h.init(actor);
                case "start" -> {
                    h.start(actor);
                    board.register(id, h.instance());
                }
                case "suspend" -> {
                    h.suspend(actor, why);
                    board.pollAll();
                }
                case "resume" -> {
                    h.resume(actor);
                    board.pollAll();
                }
                case "shutdown" -> {
                    h.shutdown(actor);
                    board.unregister(id);
                }
                default -> throw new EngineException("ENG-5020", Kind.REFUSED, action + " is not a transition; they are init, start, suspend, resume, shutdown");
            }

        } finally {
            lock.unlock();
        }
    }

    /** JARs in the site's shared folder ({@code lib/ext}) that register engines: never loaded from there (17.4 rule 1); Health says where they belong. */
    public static List<String> misplaced(Path sharedLib, Path engineLib) {
        List<String> out = new ArrayList<>();
        if (sharedLib == null || !Files.isDirectory(sharedLib)) {
            return out;
        }
        try (Stream<Path> s = Files.list(sharedLib)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                if (isEngineJar(p)) {
                    out.add(p.getFileName() + " in " + sharedLib + " registers engines: it is not loaded from there; move it to " + engineLib);
                }
            }
        } catch (IOException e) {
            out.add(sharedLib + " cannot be listed: " + e.getMessage());
        }
        return out;
    }

    /** True when the JAR registers engines ({@code META-INF/services/com.ash.engine.Engine}): the one test, the shared folder's. */
    public static boolean isEngineJar(Path jar) {
        return com.ash.drishti.common.SharedLib.isEngineJar(jar);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            timers.forEach(t -> t.cancel(false));
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            engines.values().forEach(HostedEngine::close);
            EngineLib l = lib;
            if (l != null) {
                l.close();
            }

        } finally {
            lock.unlock();
        }
    }
}
