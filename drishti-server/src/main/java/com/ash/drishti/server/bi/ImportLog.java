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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * What every import replaced, kept so the import can be rolled back (docs/guides/BI_GUIDE.md, "Rolling back an import"). One JSON file per import in
 * {@code drishti.bi.governance.imports-dir}: who ran it, from what (a bundle, or a pack deploy), and for each item it wrote the state it had before
 * (or that it was created) and the saved state and published version the import left it in, so a later edit is noticed. The newest {@code keep} imports are
 * kept. The files are written whole, one at a time, under one lock.
 */
public final class ImportLog {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final Path dir;
    private final int keep;
    private final ReentrantLock lock = new ReentrantLock();

    public ImportLog(Path dir, int keep) {
        this.dir = dir;
        this.keep = Math.max(1, keep);
    }

    /** A new record, not yet saved: {@code source} says where the content came from ({@code bundle}, {@code pack <name> <version>}). */
    ObjectNode begin(String by, String source, String madeBy) {
        ObjectNode r = JSON.createObjectNode();
        r.put("id", Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6));
        r.put("at", Instant.now().toString());
        r.put("by", by);
        r.put("source", source);
        r.put("madeBy", madeBy == null ? "" : madeBy);
        r.putArray("entries");
        return r;
    }

    void save(ObjectNode record) {
        lock.lock();
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(record.path("id").asText() + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record), StandardCharsets.UTF_8);
            prune();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Newest first. */
    List<ObjectNode> all() {
        lock.lock();
        try {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            List<ObjectNode> out = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        out.add((ObjectNode) JSON.readTree(Files.readString(f, StandardCharsets.UTF_8)));
                    } catch (IOException | ClassCastException e) {
                        // a file that is not ours or is half written is skipped
                    }
                }
            }
            out.sort(Comparator.comparing((ObjectNode r) -> r.path("at").asText()).reversed());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    ObjectNode get(String id) {
        if (id == null || !id.matches("[a-z0-9-]{3,40}")) {
            return null;
        }
        return all().stream().filter(r -> id.equals(r.path("id").asText())).findFirst().orElse(null);
    }

    private void prune() throws IOException {
        List<ObjectNode> all = all();
        for (int i = keep; i < all.size(); i++) {
            Files.deleteIfExists(dir.resolve(all.get(i).path("id").asText() + ".json"));
        }
    }

    static ArrayNode entries(ObjectNode record) {
        return (ArrayNode) record.get("entries");
    }

    static JsonNode summary(ObjectNode r) {
        ObjectNode s = JSON.createObjectNode();
        s.put("id", r.path("id").asText());
        s.put("at", r.path("at").asText());
        s.put("by", r.path("by").asText());
        s.put("source", r.path("source").asText());
        s.put("madeBy", r.path("madeBy").asText());
        s.put("items", r.path("entries").size());
        s.put("created", (int) java.util.stream.StreamSupport.stream(r.path("entries").spliterator(), false).filter(e -> e.path("created").asBoolean()).count());
        s.put("replaced", r.path("entries").size() - s.path("created").asInt());
        s.put("rolledBack", r.path("rolledBackAt").isTextual());
        s.put("rolledBackAt", r.path("rolledBackAt").asText(""));
        s.put("rolledBackBy", r.path("rolledBackBy").asText(""));
        return s;
    }
}
