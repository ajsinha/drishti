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
package com.ash.drishti.rachana;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.SourceLocation;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds every loaded Sutra by {@code name@version}. Readers see an immutable snapshot swapped atomically,
 * so lookups never lock. With hot reload on, a watcher thread reloads on file changes; a file that
 * becomes invalid keeps its last good Sutras and reports its problems.
 *
 * <p>One file never takes the registry down: whatever goes wrong while loading it (a problem in the file, an I/O
 * error, a bug or a stack overflow in a parser) becomes that file's problem, at start-up and on every hot reload.
 * Only an {@link OutOfMemoryError} or other {@link VirtualMachineError} propagates, logged, since the JVM itself is
 * then unwell. {@link #hotReload()} says whether the watcher is still running.
 */
public final class SutraRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SutraRegistry.class);

    /** An immutable view of the registry at one moment. */
    private record Snapshot(Map<String, NavigableMap<Integer, Sutra>> byName, Map<String, List<SutraProblem>> problems,
            Map<String, String> sources, Map<String, Path> fileOf) {}

    private final SutraParser parser = new SutraParser();
    private volatile RachanaProperties props;
    private final SutraExpressions expressions;
    private final Map<Path, Sutra> lastGood = new HashMap<>();
    /** Serialises reloads and saves (never taken by readers). A ReentrantLock: both do file I/O, which would pin under synchronized. */
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();
    /** Everything readers see, published in one write: a reader never pairs new Sutras with old sources. */
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());
    private volatile WatchService watcher;
    private Thread watchThread;
    /** {@code OFF}, {@code WATCHING}, or {@code STOPPED: <reason>} once the watcher has ended unexpectedly. */
    private volatile String hotReload = "OFF";

    public SutraRegistry(RachanaProperties props, ElCompiler compiler) {
        this.props = props;
        this.expressions = new SutraExpressions(compiler);
        reload();
        if (props.hotReload()) {
            startWatching();
        }
    }

    public Optional<Sutra> get(String name, int version) {
        NavigableMap<Integer, Sutra> versions = snapshot.byName().get(name);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    public Optional<Sutra> latest(String name) {
        NavigableMap<Integer, Sutra> versions = snapshot.byName().get(name);
        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
    }

    public Sutra require(String name, int version) {
        return get(name, version).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, name + "@" + version));
    }

    /** The latest version of every Sutra. */
    public List<Sutra> all() {
        return snapshot.byName().values().stream().map(v -> v.lastEntry().getValue()).toList();
    }

    /** Latest versions whose match kind is {@code kind}, highest priority first. */
    public List<Sutra> forKind(String kind) {
        return all().stream()
                .filter(s -> s.match().kind().equals(kind))
                .sorted(Comparator.comparingInt((Sutra s) -> -s.match().priority()).thenComparing(Sutra::name))
                .toList();
    }

    public List<Integer> versions(String name) {
        NavigableMap<Integer, Sutra> v = snapshot.byName().get(name);
        return v == null ? List.of() : List.copyOf(v.keySet());
    }

    /** The text of a loaded Sutra (Markdown or YAML), as last read from its file. */
    public Optional<String> source(String name, int version) {
        return Optional.ofNullable(snapshot.sources().get(name + "@" + version));
    }

    /**
     * Validates and writes a Sutra into the first Sutra directory ({@code <domain>/<name>.v<N>.sutra.yaml}), then
     * reloads. Refuses a {@code name@version} that another file already defines.
     *
     * @return the written Sutra
     */
    public Sutra save(String yaml) throws IOException {
        writeLock.lock();
        try {
            return save0(yaml);
        } finally {
            writeLock.unlock();
        }
    }

    private Sutra save0(String yaml) throws IOException {
        Sutra s = parser.parse(yaml, SutraParser.STUDIO, "studio");
        List<SutraProblem> problems = expressions.check(s);
        if (!problems.isEmpty()) {
            throw new SutraException(problems);
        }
        if (props.dirs().isEmpty()) {
            throw new SutraException(List.of(new SutraProblem("DRS-2020", "no site Sutra directory to save into (drishti.rachana.dirs)", s.location())));
        }
        Path dir = Path.of(props.dirs().get(0)).toAbsolutePath().normalize();
        Path target = dir.resolve(s.domain()).resolve(s.name() + ".v" + s.version() + ".sutra.yaml").normalize();
        if (!target.startsWith(dir)) {
            throw new SutraException(List.of(new SutraProblem("DRS-2020", "bad domain or name", s.location())));
        }
        Path existing = snapshot.fileOf().get(s.id());
        if (existing != null && !existing.equals(target)) {
            throw new SutraException(List.of(new SutraProblem("DRS-2028", s.id() + " is already defined in " + existing, s.location())));
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, yaml, StandardCharsets.UTF_8);
        reload();
        return s;
    }

    /** The file a Sutra id ({@code name@version}) is defined in, if it is loaded. */
    public Optional<Path> fileOf(String id) {
        return Optional.ofNullable(snapshot.fileOf().get(id));
    }

    /** Parses and checks {@code yaml} without saving it, for Studio previews. */
    public Sutra check(String yaml) {
        Sutra s = parser.parse(yaml, SutraParser.STUDIO, "studio");
        List<SutraProblem> problems = expressions.check(s);
        if (!problems.isEmpty()) {
            throw new SutraException(problems);
        }
        return s;
    }

    /**
     * {@code text} with every Sutra root directory taken off the front of the paths in it, so a problem shown to a
     * client names {@code config/packs/banking/sutras/x.sutra.yaml}'s place under its root and not the server's disk layout
     * (SEC-11). The log keeps the absolute paths.
     */
    public String relative(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String d : props.allDirs()) {
            String root = Path.of(d).toAbsolutePath().normalize().toString();
            if (!root.isEmpty() && !root.equals("/")) {
                out = out.replace(root + java.io.File.separator, "").replace(root, ".");
            }
        }
        return out;
    }

    /** The problems with their file names made relative (see {@link #relative(String)}). */
    public List<SutraProblem> relative(List<SutraProblem> in) {
        return in.stream().map(x -> new SutraProblem(x.code(), relative(x.message()),
                new com.ash.drishti.rachana.model.SourceLocation(relative(x.location().file()), x.location().line(), x.location().column()))).toList();
    }

    /** Problems per file from the last reload; empty when every file is valid. */
    public Map<String, List<SutraProblem>> problems() {
        return snapshot.problems();
    }

    /**
     * The state of hot reload, for health checks: {@code OFF} (turned off, or no watchable directory), {@code WATCHING},
     * or {@code STOPPED: <reason>} when the watcher thread has ended unexpectedly (edits are then not picked up until
     * a restart).
     */
    public String hotReload() {
        return hotReload;
    }

    /** Reads the Sutras of a new set of pack directories (most general first) and reloads; listeners hear what changed. */
    public void setPackDirs(List<String> packDirs) {
        writeLock.lock();
        try {
            props = props.withPackDirs(packDirs);
            reload0();
        } finally {
            writeLock.unlock();
        }
    }

    /** Called with the ids ({@code name@version}) that changed after each reload. */
    public void onChange(Consumer<Set<String>> listener) {
        listeners.add(listener);
    }

    /** Rescans every directory. Reloads are serialised and rare; reads never wait for them. */
    public void reload() {
        writeLock.lock();
        try {
            reload0();
        } finally {
            writeLock.unlock();
        }
    }

    private void reload0() {
        Snapshot before = snapshot;
        Map<String, NavigableMap<Integer, Sutra>> byName = new TreeMap<>();
        Map<String, List<SutraProblem>> problems = new LinkedHashMap<>();
        Map<String, Path> origin = new HashMap<>();
        Map<String, String> texts = new HashMap<>();
        List<Path> files = files();
        lastGood.keySet().retainAll(files);
        for (Path f : files) {
            Sutra s;
            String fname = f.getFileName().toString();
            if (!fname.endsWith(".sutra.yaml")) {                     // a file in a Sutra folder that is not a Sutra file
                problems.put(f.toString(), List.of(new SutraProblem("DRS-2004", fname.endsWith(".sutra.md")
                        ? "Markdown Sutras are no longer read (Sutras are YAML since 1.11): convert it with "
                                + "python3 tools/rachana/md_to_yaml.py " + f + " --delete"
                        : "a Sutra file is named <name>.v<N>.sutra.yaml; rename " + fname, new SourceLocation(f.toString(), 1, 1))));
                continue;
            }
            try {
                String domain = f.getParent() == null ? "" : f.getParent().getFileName().toString();
                s = parser.parse(Files.readString(f, StandardCharsets.UTF_8), f.getFileName().toString(), domain);
                List<SutraProblem> exprProblems = expressions.check(s);
                if (!exprProblems.isEmpty()) {
                    throw new SutraException(exprProblems);
                }
                lastGood.put(f, s);
            } catch (SutraException e) {
                problems.put(f.toString(), e.problems());
                s = lastGood.get(f);
            } catch (IOException e) {
                problems.put(f.toString(), List.of(new SutraProblem("DRS-2001", e.getMessage(), new SourceLocation(f.toString(), 0, 0))));
                s = lastGood.get(f);
            } catch (RuntimeException | StackOverflowError e) {
                // anything else while loading one file is that file's problem: never a failed start or a dead watcher
                LOG.error("sutra file {} could not be loaded", f, e);
                problems.put(f.toString(), List.of(new SutraProblem("DRS-2032", "the file could not be loaded: " + describe(e),
                        new SourceLocation(f.toString(), 1, 1))));
                s = lastGood.get(f);
            }
            if (s == null) {
                continue;
            }
            Path other = origin.putIfAbsent(s.id(), f);
            if (other != null && !other.equals(f)) {
                // pack directories come most general first: a more specific pack may redefine a parent's Sutra
                int was = packIndex(other);
                int now = packIndex(f);
                if (was >= 0 && now > was) {
                    origin.put(s.id(), f);
                    LOG.info("sutra {} from {} overrides the one in {}", s.id(), f, other);
                } else {
                    problems.computeIfAbsent(f.toString(), k -> new ArrayList<>()).add(new SutraProblem("DRS-2028",
                            s.id() + " is already defined in " + other, s.location()));
                    continue;
                }
            }
            byName.computeIfAbsent(s.name(), k -> new TreeMap<>()).put(s.version(), s);
            try {
                texts.put(s.id(), Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                texts.put(s.id(), "");
            }
        }
        Map<String, NavigableMap<Integer, Sutra>> frozen = new TreeMap<>();
        byName.forEach((k, v) -> frozen.put(k, Collections.unmodifiableNavigableMap(v)));
        snapshot = new Snapshot(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(problems), Map.copyOf(texts), Map.copyOf(origin));
        problems.forEach((f, ps) -> ps.forEach(p -> LOG.warn("sutra problem {}", p)));
        Set<String> changed = changedIds(before, snapshot);
        LOG.info("sutras loaded: {} names, {} problem file(s), {} changed", frozen.size(), problems.size(), changed.size());
        if (!changed.isEmpty()) {
            listeners.forEach(l -> l.accept(changed));
        }
    }

    private static String describe(Throwable e) {
        return e instanceof StackOverflowError ? "it is nested too deeply to read (" + e.getClass().getSimpleName() + ")"
                : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static Set<String> changedIds(Snapshot a, Snapshot b) {
        Map<String, Sutra> x = flatten(a);
        Map<String, Sutra> y = flatten(b);
        Set<String> changed = new java.util.TreeSet<>();
        x.forEach((id, s) -> {
            if (!s.equals(y.get(id))) {
                changed.add(id);
            }
        });
        y.keySet().stream().filter(id -> !x.containsKey(id)).forEach(changed::add);
        return changed;
    }

    private static Map<String, Sutra> flatten(Snapshot s) {
        Map<String, Sutra> out = new HashMap<>();
        s.byName().values().forEach(v -> v.values().forEach(x -> out.put(x.id(), x)));
        return out;
    }

    /** The position of the pack Sutra directory holding {@code f} (most general first), or -1 for a site directory. */
    private int packIndex(Path f) {
        List<String> dirs = props.packDirs();
        for (int i = dirs.size() - 1; i >= 0; i--) {
            if (f.startsWith(Path.of(dirs.get(i)).toAbsolutePath().normalize())) {
                return i;
            }
        }
        return -1;
    }

    private List<Path> files() {
        List<Path> out = new ArrayList<>();
        for (String d : props.allDirs()) {
            Path dir = Path.of(d).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                LOG.warn("sutra directory {} does not exist", dir);
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                // *.sutra.yaml are Sutras; *.sutra.md and other YAML files are listed only to be reported (convert or rename)
                s.filter(p -> p.toString().endsWith(".sutra.yaml") || p.toString().endsWith(".sutra.md") || p.toString().endsWith(".yaml")
                        || p.toString().endsWith(".yml")).sorted().forEach(out::add);
            } catch (IOException e) {
                LOG.warn("cannot scan {}", dir, e);
            }
        }
        return out;
    }

    private void startWatching() {
        if ("poll".equals(props.watch())) {
            startPolling("drishti.rachana.watch is poll");
            return;
        }
        try {
            watcher = FileSystems.getDefault().newWatchService();
            for (String d : props.allDirs()) {
                Path dir = Path.of(d).toAbsolutePath().normalize();
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> s = Files.walk(dir)) {
                        for (Path p : s.filter(Files::isDirectory).toList()) {
                            p.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                                    StandardWatchEventKinds.ENTRY_DELETE);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // e.g. "User limit of inotify watches reached": edits must still be picked up, so look at the files instead
            try {
                if (watcher != null) {
                    watcher.close();
                }
            } catch (IOException ignored) {
                // the watcher was never usable
            }
            watcher = null;
            startPolling("file events unavailable: " + e.getMessage());
            return;
        }
        hotReload = "WATCHING";
        watchThread = Thread.ofVirtual().name("drishti-rachana-watch").start(this::watchLoop);
    }

    /** Hot reload by looking at the files every {@code pollInterval}: their paths, sizes and modification times. */
    private void startPolling(String why) {
        LOG.warn("sutra hot reload polls the Sutra files every {} ({})", props.pollInterval(), why);
        hotReload = "POLLING";
        long every = props.pollInterval().toMillis();
        String first = fingerprint();                  // taken now, as the files were loaded: an edit made before the loop runs still counts
        watchThread = Thread.ofVirtual().name("drishti-rachana-poll").start(() -> {
            String last = first;
            boolean closing = false;
            Throwable cause = null;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(every);
                    String now = fingerprint();
                    if (!now.equals(last)) {
                        last = now;
                        try {
                            reload();
                        } catch (RuntimeException | StackOverflowError e) {
                            LOG.error("sutra reload failed; the last good Sutras stay live", e);
                        }
                    }
                }
                closing = true;
            } catch (InterruptedException e) {
                closing = true;
                Thread.currentThread().interrupt();
            } catch (RuntimeException | Error e) {
                cause = e;
                throw e;
            } finally {
                hotReload = closing ? "OFF" : "STOPPED: " + (cause == null ? "unknown" : describe(cause));
                if (!closing) {
                    LOG.error("sutra hot reload stopped: edits to Sutra files are not picked up until a restart", cause);
                }
            }
        });
    }

    /** Every file under the Sutra directories with its size and modification time: equal fingerprints, nothing changed. */
    private String fingerprint() {
        StringBuilder out = new StringBuilder();
        for (String d : props.allDirs()) {
            Path dir = Path.of(d).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(Files::isRegularFile).sorted().forEach(p -> {
                    try {
                        out.append(p).append('|').append(Files.size(p)).append('|').append(Files.getLastModifiedTime(p).toMillis()).append('\n');
                    } catch (IOException e) {
                        out.append(p).append("|gone\n");                         // deleted while walking: counts as a change
                    }
                });
            } catch (IOException | java.io.UncheckedIOException e) {
                out.append(dir).append("|unreadable\n");
            }
        }
        return out.toString();
    }

    private void watchLoop() {
        long debounce = props.reloadDebounce().toMillis();
        boolean closing = false;
        Throwable cause = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watcher.take();
                key.pollEvents();
                key.reset();
                WatchKey more;
                while ((more = watcher.poll(debounce, TimeUnit.MILLISECONDS)) != null) {
                    more.pollEvents();
                    more.reset();
                }
                try {
                    reload();
                } catch (RuntimeException | StackOverflowError e) {
                    // keep watching: one bad reload (or a failing listener) must not end hot reload silently
                    LOG.error("sutra reload failed; the last good Sutras stay live", e);
                }
            }
            closing = true;
        } catch (InterruptedException | ClosedWatchServiceException e) {
            closing = true;
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            cause = e;
            throw e;
        } finally {
            hotReload = closing ? "OFF" : "STOPPED: " + (cause == null ? "unknown" : describe(cause));
            if (!closing) {
                LOG.error("sutra hot reload stopped: edits to Sutra files are not picked up until a restart", cause);
            }
        }
    }

    @Override
    public void close() {
        try {
            if (watcher != null) {
                watcher.close();
            }
        } catch (IOException ignored) {
            // closing on shutdown
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }
}
