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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shipped content of the BI folder ({@code drishti.bi.dir}): {@code datasets/<id>.dataset.yaml}, {@code reports/<id>.report.yaml},
 * {@code boards/<id>.board.yaml}, {@code notebooks/<id>.notebook.yaml}. Each file is a published, read-only item of the virtual
 * workspace {@value #WORKSPACE}; its catalogue fields come from the top-level keys {@code title}, {@code description}, {@code owner},
 * {@code tags}, {@code endorsement} and {@code audience} ({@code roles}, {@code users}, {@code packs}). Phase 1 stores the body and does not run it.
 * The folder is read again at most every {@code folder-refresh}; a reader never waits on the disk (it uses the last snapshot while one thread reloads).
 */
public final class BiFolder {

    private static final Logger LOG = LoggerFactory.getLogger(BiFolder.class);
    /** The id of the virtual workspace that holds the folder's items. */
    public static final String WORKSPACE = "shipped";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, String> KINDS = Map.of("datasets", "dataset", "reports", "report", "boards", "board", "notebooks", "notebook");

    private final Path dir;
    private final Duration refresh;
    private final java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile List<BiStore.Item> items = List.of();
    private volatile long loadedAt = Long.MIN_VALUE;

    public BiFolder(String dir, Duration refresh) {
        this.dir = Path.of(dir);
        this.refresh = refresh;
    }

    /** The folder's items (all published, version 1); never null. */
    public List<BiStore.Item> items() {
        long now = System.nanoTime();
        if (loadedAt == Long.MIN_VALUE || now - loadedAt > refresh.toNanos()) {
            if (loading.compareAndSet(false, true)) {
                try {
                    items = read();
                    loadedAt = System.nanoTime();
                } finally {
                    loading.set(false);
                }
            }
        }
        return items;
    }

    public java.util.Optional<BiStore.Item> item(String id) {
        return items().stream().filter(i -> i.id().equals(id)).findFirst();
    }

    public static boolean isShipped(String id) {
        return id != null && id.startsWith("file:");
    }

    private List<BiStore.Item> read() {
        List<BiStore.Item> out = new ArrayList<>();
        for (Map.Entry<String, String> k : KINDS.entrySet()) {
            Path folder = dir.resolve(k.getKey());
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.list(folder)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith("." + k.getValue() + ".yaml")).sorted().toList()) {
                    try {
                        out.add(parse(k.getValue(), f));
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("BI folder: {} skipped: {}", f, e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.warn("BI folder: {} unreadable: {}", folder, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private static BiStore.Item parse(String kind, Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode root = YAML.readTree(text);
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ("." + kind + ".yaml").length());
        Instant modified = Files.getLastModifiedTime(file).toInstant();
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
