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
 */
public final class SutraRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SutraRegistry.class);

    /** An immutable view of the registry at one moment. */
    private record Snapshot(Map<String, NavigableMap<Integer, Sutra>> byName, Map<String, List<SutraProblem>> problems) {}

    private final SutraParser parser = new SutraParser();
    private final RachanaProperties props;
    private final SutraExpressions expressions;
    private final Map<Path, Sutra> lastGood = new HashMap<>();
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    private volatile WatchService watcher;
    private Thread watchThread;

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

    /** Problems per file from the last reload; empty when every file is valid. */
    public Map<String, List<SutraProblem>> problems() {
        return snapshot.problems();
    }

    /** Called with the ids ({@code name@version}) that changed after each reload. */
    public void onChange(Consumer<Set<String>> listener) {
        listeners.add(listener);
    }

    /** Rescans every directory. Synchronized: reloads are rare, reads never wait for them. */
    public synchronized void reload() {
        Snapshot before = snapshot;
        Map<String, NavigableMap<Integer, Sutra>> byName = new TreeMap<>();
        Map<String, List<SutraProblem>> problems = new LinkedHashMap<>();
        Map<String, Path> origin = new HashMap<>();
        List<Path> files = files();
        lastGood.keySet().retainAll(files);
        for (Path f : files) {
            Sutra s;
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
            }
            if (s == null) {
                continue;
            }
            Path other = origin.putIfAbsent(s.id(), f);
            if (other != null && !other.equals(f)) {
                problems.computeIfAbsent(f.toString(), k -> new ArrayList<>()).add(new SutraProblem("DRS-2028",
                        s.id() + " is already defined in " + other, s.location()));
                continue;
            }
            byName.computeIfAbsent(s.name(), k -> new TreeMap<>()).put(s.version(), s);
        }
        Map<String, NavigableMap<Integer, Sutra>> frozen = new TreeMap<>();
        byName.forEach((k, v) -> frozen.put(k, Collections.unmodifiableNavigableMap(v)));
        snapshot = new Snapshot(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(problems));
        problems.forEach((f, ps) -> ps.forEach(p -> LOG.warn("sutra problem {}", p)));
        Set<String> changed = changedIds(before, snapshot);
        LOG.info("sutras loaded: {} names, {} problem file(s), {} changed", frozen.size(), problems.size(), changed.size());
        if (!changed.isEmpty()) {
            listeners.forEach(l -> l.accept(changed));
        }
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

    private List<Path> files() {
        List<Path> out = new ArrayList<>();
        for (String d : props.dirs()) {
            Path dir = Path.of(d).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                LOG.warn("sutra directory {} does not exist", dir);
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().forEach(out::add);
            } catch (IOException e) {
                LOG.warn("cannot scan {}", dir, e);
            }
        }
        return out;
    }

    private void startWatching() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
            for (String d : props.dirs()) {
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
            LOG.warn("sutra hot reload disabled", e);
            return;
        }
        watchThread = Thread.ofVirtual().name("drishti-rachana-watch").start(this::watchLoop);
    }

    private void watchLoop() {
        long debounce = props.reloadDebounce().toMillis();
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
                reload();
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            Thread.currentThread().interrupt();
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
