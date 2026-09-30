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
package com.ash.drishti.plugin.file;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Serves feed files from a directory laid out as {@code <root>/<kind>/<id>.json} or {@code .csv}. The
 * generation is the file's modification time in milliseconds, so a rewritten file is newer data.
 *
 * <p>Settings: {@code root} (required), {@code source-name} (default {@code file}), {@code rescan-seconds}
 * (default 30) for the search index.
 */
public final class FileSourcePlugin implements SourcePlugin {

    private final HitIndex index = new HitIndex();
    private SourceContext context;
    private Path root;
    private String sourceName;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("file", "1.0", Set.of(), new SourceCapabilities(false, false, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.root = Path.of(ctx.setting("root", "data/feeds")).toAbsolutePath().normalize();
        this.sourceName = ctx.setting("source-name", "file");
        long rescan = Long.parseLong(ctx.setting("rescan-seconds", "30"));
        rescan();
        ctx.scheduler().scheduleWithFixedDelay(this::rescan, rescan, rescan, TimeUnit.SECONDS);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
        for (String ext : List.of(".json", ".csv")) {
            Path p = resolve(ref, ext);
            if (p != null && Files.isRegularFile(p)) {
                return Optional.of(new EntityDocument(ref, parse(p), new Provenance(sourceName,
                        Files.getLastModifiedTime(p).toMillis(), Instant.now(), false)));
            }
        }
        return Optional.empty();
    }

    /** Resolves the file for {@code ref}, refusing identifiers that would escape the root directory. */
    Path resolve(EntityRef ref, String ext) {
        Path p = root.resolve(ref.kind()).resolve(ref.id() + ext).normalize();
        return p.startsWith(root) ? p : null;
    }

    private DataNode parse(Path p) throws IOException {
        if (p.toString().endsWith(".csv")) {
            try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                return CsvReader.read(r);
            }
        }
        try (InputStream in = Files.newInputStream(p)) {
            return context.parseJson(in);
        }
    }

    void rescan() {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<EntityHit> hits = new ArrayList<>();
        try (Stream<Path> kinds = Files.list(root)) {
            for (Path kindDir : kinds.filter(Files::isDirectory).toList()) {
                String kind = kindDir.getFileName().toString();
                try (Stream<Path> files = Files.list(kindDir)) {
                    files.map(f -> f.getFileName().toString())
                            .filter(n -> n.endsWith(".json") || n.endsWith(".csv"))
                            .forEach(n -> {
                                String id = n.substring(0, n.lastIndexOf('.'));
                                hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName));
                            });
                }
            }
        } catch (IOException e) {
            return;
        }
        index.replaceAll(hits);
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public String health() {
        return Files.isDirectory(root) ? "UP" : "DOWN: no directory " + root;
    }
}
