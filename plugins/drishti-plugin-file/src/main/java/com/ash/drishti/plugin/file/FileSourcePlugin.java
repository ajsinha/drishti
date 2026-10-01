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

import com.ash.drishti.api.AsOf;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Serves feed files from a directory laid out as {@code <root>/<kind>/<id>.json} or {@code .csv}, and, for dated
 * data, {@code <root>/<yyyy-MM-dd>/<kind>/<id>.json}. A read for a business date takes the file from the latest
 * dated folder on or before that date (within {@code lookback-days}), then the undated folder. The generation is
 * the file's modification time in milliseconds, so a rewritten file is newer data.
 *
 * <p>Settings: {@code root} (required), {@code source-name} (default {@code file}), {@code rescan-seconds}
 * (default 30) for the search index and the list of dated folders, {@code lookback-days} (default 10).
 */
public final class FileSourcePlugin implements SourcePlugin {

    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final HitIndex index = new HitIndex();
    private volatile List<LocalDate> dates = List.of();   // dated folders, newest first
    private SourceContext context;
    private Path root;
    private String sourceName;
    private int lookbackDays;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("file", "1.0", Set.of(), new SourceCapabilities(false, false, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.root = Path.of(ctx.setting("root", "data/feeds")).toAbsolutePath().normalize();
        this.sourceName = ctx.setting("source-name", "file");
        long rescan = Long.parseLong(ctx.setting("rescan-seconds", "30"));
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        rescan();
        ctx.scheduler().scheduleWithFixedDelay(this::rescan, rescan, rescan, TimeUnit.SECONDS);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws IOException {
        LocalDate want = asOf.businessDate();
        for (LocalDate d : dates) {
            if (want != null && (d.isAfter(want) || d.isBefore(want.minusDays(lookbackDays)))) {
                continue;
            }
            Optional<EntityDocument> hit = read(ref, root.resolve(d.toString()), d);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return read(ref, root, null);
    }

    private Optional<EntityDocument> read(EntityRef ref, Path base, LocalDate date) throws IOException {
        for (String ext : List.of(".json", ".csv")) {
            Path p = resolve(base, ref, ext);
            if (p != null && Files.isRegularFile(p)) {
                return Optional.of(new EntityDocument(ref, parse(p), new Provenance(sourceName,
                        Files.getLastModifiedTime(p).toMillis(), Instant.now(), false, date)));
            }
        }
        return Optional.empty();
    }

    /** Resolves the file for {@code ref}, refusing identifiers that would escape the root directory. */
    Path resolve(EntityRef ref, String ext) {
        return resolve(root, ref, ext);
    }

    private Path resolve(Path base, EntityRef ref, String ext) {
        Path p = base.resolve(ref.kind()).resolve(ref.id() + ext).normalize();
        return p.startsWith(base) && p.startsWith(root) ? p : null;
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

    /** The newest file's modification time: when the folder last received new data. */
    private volatile java.time.Instant lastUpdate;

    @Override
    public java.time.Instant lastUpdate() {
        return lastUpdate;
    }

    void rescan() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            all.filter(Files::isRegularFile).map(f -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant();
                } catch (IOException e) {
                    return null;
                }
            }).filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).ifPresent(t -> lastUpdate = t);
        } catch (IOException | java.io.UncheckedIOException e) {
            // the next rescan tries again
        }
        List<EntityHit> hits = new ArrayList<>();
        List<LocalDate> found = new ArrayList<>();
        try (Stream<Path> kinds = Files.list(root)) {
            for (Path kindDir : kinds.filter(Files::isDirectory).toList()) {
                String kind = kindDir.getFileName().toString();
                if (DATE.matcher(kind).matches()) {
                    found.add(LocalDate.parse(kind));
                    continue;
                }
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
        found.sort(java.util.Comparator.reverseOrder());
        dates = List.copyOf(found);
        if (!found.isEmpty()) {
            indexDated(root.resolve(found.get(0).toString()), hits);
        }
        index.replaceAll(hits);
    }

    /** Adds the newest dated folder's entities to the search index (ids are stable across dates). */
    private void indexDated(Path dir, List<EntityHit> hits) throws java.io.UncheckedIOException {
        try (Stream<Path> kinds = Files.list(dir)) {
            for (Path kindDir : kinds.filter(Files::isDirectory).toList()) {
                String kind = kindDir.getFileName().toString();
                try (Stream<Path> files = Files.list(kindDir)) {
                    files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json") || n.endsWith(".csv")).forEach(n -> {
                        String id = n.substring(0, n.lastIndexOf('.'));
                        EntityRef r = EntityRef.of(kind, id);
                        if (hits.stream().noneMatch(h -> h.ref().equals(r))) {
                            hits.add(new EntityHit(r, id, kind + " · " + sourceName));
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // a folder that vanished mid-scan is picked up next time
        }
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
