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
package com.ash.drishti.server.bi;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.connectors.DirectoryWatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shipped content of the BI folder ({@code drishti.bi.dir}): {@code datasets/<id>.dataset.yaml}, {@code reports/<id>.report.yaml},
 * {@code boards/<id>.board.yaml}, {@code notebooks/<id>.notebook.yaml}. Each file is a published, read-only item of the virtual
 * workspace {@value #WORKSPACE}; its catalogue fields come from the top-level keys {@code title}, {@code description}, {@code owner},
 * {@code tags}, {@code endorsement} and {@code audience} ({@code roles}, {@code users}, {@code packs}). Phase 1 stores the body and does not run it.
 *
 * <p>The folder is watched ({@code drishti.bi.watch}: auto, poll or off; file events with a polling safety net, the pattern of the Sutra
 * registry and the connector files): a file added appears, a file changed becomes the next version, a file deleted is gone, and a file that
 * does not parse keeps its last good version and is listed in {@link #problems()}. Readers use an immutable snapshot and never wait on the disk.
 * With {@code watch: off} the folder is read when {@code folder-refresh} has passed since the last read, on the next request.
 */
public final class BiFolder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BiFolder.class);
    /** The id of the virtual workspace that holds the folder's items. */
    public static final String WORKSPACE = "shipped";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, String> KINDS = Map.of("datasets", "dataset", "reports", "report", "boards", "board", "notebooks", "notebook");

    /** One file as last read: its stamp, the last good item (null until one parses) and the problem with the latest content, if any. */
    private record Entry(long modified, long size, BiStore.Item item, String problem, Instant problemSince) {}

    /** A file that could not be read: shown in Admin, BI settings and on Health. */
    public record Problem(String file, String message, Instant since, boolean keptLastGood) {}

    private final Path dir;
    private final Duration refresh;
    private final String watchMode;
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Map<String, Entry> entries = Map.of();
    private volatile List<BiStore.Item> items = List.of();
    private volatile long loadedAt = Long.MIN_VALUE;
    private volatile DirectoryWatcher watcher;

    public BiFolder(String dir, Duration refresh, String watchMode) {
        this.dir = Path.of(dir);
        this.refresh = refresh;
        this.watchMode = watchMode == null ? "auto" : watchMode;
        if (!Set.of("auto", "poll", "off").contains(this.watchMode)) {
            throw new IllegalArgumentException("drishti.bi.watch must be auto, poll or off, not " + watchMode);
        }
    }

    /** Reads the folder once and starts watching it (unless {@code watch: off}). */
    public void start() {
        reload();
        if (!"off".equals(watchMode) && watcher == null) {
            DirectoryWatcher w = new DirectoryWatcher(dir, watchMode, Math.max(50, refresh.toMillis()), this::reload, "drishti-bi-watch");
            w.start();
            watcher = w;
        }
    }

    @Override
    public void close() {
        DirectoryWatcher w = watcher;
        watcher = null;
        if (w != null) {
            w.close();
        }
    }

    /** OFF, WATCHING or POLLING. */
    public String watchState() {
        DirectoryWatcher w = watcher;
        return w == null ? "OFF" : w.state();
    }

    /** The folder's items (all published); never null. A version number counts the content changes seen since the server started. */
    public List<BiStore.Item> items() {
        if (watcher == null && (loadedAt == Long.MIN_VALUE || System.nanoTime() - loadedAt > refresh.toNanos())) {
            reload();
        }
        return items;
    }

    public Optional<BiStore.Item> item(String id) {
        return items().stream().filter(i -> i.id().equals(id)).findFirst();
    }

    public List<Problem> problems() {
        List<Problem> out = new ArrayList<>();
        entries.forEach((file, e) -> {
            if (e.problem() != null) {
                out.add(new Problem(file, e.problem(), e.problemSince(), e.item() != null));
            }
        });
        out.sort(Comparator.comparing(Problem::file));
        return out;
    }

    public static boolean isShipped(String id) {
        return id != null && id.startsWith("file:");
    }

    /** Looks at the folder now: adds, changes and removes what the files say. One pass at a time; a pass already running makes this a no-op. */
    public void reload() {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, Entry> next = new TreeMap<>();
            for (Map.Entry<String, String> k : KINDS.entrySet()) {
                Path folder = dir.resolve(k.getKey());
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith("." + k.getValue() + ".yaml")).sorted().toList()) {
                        String key = k.getKey() + "/" + f.getFileName();
                        next.put(key, read(k.getValue(), f, entries.get(key)));
                    }
                } catch (IOException e) {
                    LOG.warn("BI folder: {} unreadable: {}", folder, e.getMessage());
                }
            }
            entries.keySet().stream().filter(x -> !next.containsKey(x)).forEach(x -> LOG.info("BI folder: {} removed", x));
            entries = Map.copyOf(next);
            items = next.values().stream().map(Entry::item).filter(Objects::nonNull).toList();
            loadedAt = System.nanoTime();
        } finally {
            loading.set(false);
        }
    }

    private Entry read(String kind, Path file, Entry before) {
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException e) {
            return before != null ? before : new Entry(0, 0, null, e.getMessage(), Instant.now());
        }
        if (before != null && before.modified() == modified && before.size() == size) {
            return before;                                                    // unchanged
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            BiStore.Item parsed = parse(kind, file, text, Instant.ofEpochMilli(modified));
            BiStore.Item old = before == null ? null : before.item();
            int version = old == null ? 1 : old.body().equals(text) ? old.publishedVersion() : old.publishedVersion() + 1;
            BiStore.Item item = new BiStore.Item(parsed.id(), parsed.workspace(), parsed.kind(), parsed.title(), parsed.description(), parsed.owner(),
                    parsed.tags(), parsed.body(), "published", version, parsed.endorsement(), parsed.endorsedBy(), parsed.audienceRoles(), parsed.audienceUsers(),
                    parsed.audiencePacks(), "", null, parsed.createdAt(), parsed.updatedAt(), "", parsed.publishedAt(), "");
            LOG.info("BI folder: {} {} (version {})", file.getFileName(), old == null ? "added" : "changed", version);
            return new Entry(modified, size, item, null, null);
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            LOG.warn("BI folder: {} not loaded{}: {}", file, before != null && before.item() != null ? " (keeping the last good version)" : "", why);
            return new Entry(modified, size, before == null ? null : before.item(), why,
                    before != null && before.problem() != null ? before.problemSince() : Instant.now());
        }
    }

    private static BiStore.Item parse(String kind, Path file, String text, Instant modified) throws IOException {
        JsonNode root = YAML.readTree(text);
        if (root == null || !root.isObject()) {
            throw new IOException("the file is YAML with a mapping at the top (key: value)");
        }
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ("." + kind + ".yaml").length());
        String endorsement = root.path("endorsement").asText("none");
        return new BiStore.Item("file:" + kind + ":" + name, WORKSPACE, kind, root.path("title").asText(name), root.path("description").asText(""),
                root.path("owner").asText("shipped"), strings(root.path("tags")), text, "published", 1,
                List.of("none", "promoted", "certified").contains(endorsement) ? endorsement : "none", root.path("endorsedBy").asText(""),
                strings(root.path("audience").path("roles")), strings(root.path("audience").path("users")), strings(root.path("audience").path("packs")),
                "", null, modified, modified, "", modified, "");
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        }
        return out;
    }
}
