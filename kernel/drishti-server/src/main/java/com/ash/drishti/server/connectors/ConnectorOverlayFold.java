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
package com.ash.drishti.server.connectors;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.packs.ConnectorFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One upgrade at start: connector edits an earlier release kept in the content store ({@code config/connectors/<name>.yaml},
 * {@code <name>.hidden}, {@code config/connectors-history/<name>/}) are folded into this node's connector files. Each node folds once
 * (its own marker in {@code <data>/connectors}), and the store entries are left where they are, so every node of a shared store can fold
 * them. A file changed after the last edit in the store is not overwritten: the edit is kept beside it as a {@code .bak}, logged and
 * audited, for the administrator to compare.
 */
final class ConnectorOverlayFold {

    static final String PREFIX = "config/connectors/";
    static final String HISTORY = "config/connectors-history/";
    static final String MARKER = "overlay-folded.txt";
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorOverlayFold.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");

    private final ConnectorFiles files;
    private final ContentStore store;
    private final AuditLog audit;

    ConnectorOverlayFold(ConnectorFiles files, ContentStore store, AuditLog audit) {
        this.files = files;
        this.store = store;
        this.audit = audit;
    }

    /** Folds what the store holds, once; returns the connectors whose file changed. */
    List<String> run() {
        Path marker = files.stateDir().resolve(MARKER);
        if (Files.exists(marker)) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        boolean clean = true;
        for (String name : names()) {
            try {
                importHistory(name);
                if (fold(name)) {
                    changed.add(name);
                }
            } catch (IOException | RuntimeException e) {
                clean = false;
                LOG.warn("connector {}: the edit in the content store could not be folded into its file ({}); tried again at the next start", name, e.toString());
            }
        }
        if (clean) {
            try {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "folded at " + Instant.now() + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("cannot write {}: the connector upgrade runs again at the next start ({})", marker, e.toString());
            }
        }
        return changed;
    }

    private TreeSet<String> names() {
        TreeSet<String> out = new TreeSet<>();
        for (String k : store.list(PREFIX)) {
            String rest = k.substring(PREFIX.length());
            String n = rest.endsWith(".yaml") ? rest.substring(0, rest.length() - 5) : rest.endsWith(".hidden") ? rest.substring(0, rest.length() - 7) : null;
            if (ConnectorFiles.validName(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private void importHistory(String name) {
        String p = HISTORY + name + "/";
        for (String k : store.list(p)) {
            String id = k.substring(p.length());
            if (id.endsWith(".yaml")) {
                store.get(k).ifPresent(i -> files.importRevision(name, id.substring(0, id.length() - 5), i.text()));
            }
        }
    }

    /** The newest time an edit was saved in the store, from its history's ids; null when it has none. */
    private Instant lastEdit(String name) {
        String p = HISTORY + name + "/";
        Instant newest = null;
        for (String k : store.list(p)) {
            String id = k.substring(p.length());
            if (id.matches("\\d{8}T\\d{9}(-\\d+)?\\.yaml")) {
                Instant at = LocalDateTime.parse(id.substring(0, 18), STAMP).toInstant(ZoneOffset.UTC);
                newest = newest == null || at.isAfter(newest) ? at : newest;
            }
        }
        return newest;
    }

    private boolean fileIsNewer(String name) throws IOException {
        Instant edit = lastEdit(name);
        return edit == null || Files.getLastModifiedTime(files.file(name)).toInstant().isAfter(edit.plusSeconds(1));
    }

    private boolean fold(String name) throws IOException {
        Optional<ContentStore.Item> edit = store.get(PREFIX + name + ".yaml");
        boolean onDisk = files.fileExists(name);
        if (edit.isPresent()) {
            String text = edit.get().text();
            if (!onDisk) {
                files.write(name, text);
                say(name, "folded the edit from the content store into " + name + ".yaml");
                return true;
            }
            if (text.equals(files.fileText(name))) {
                return false;
            }
            if (fileIsNewer(name)) {
                Path kept = files.keepTextBeside(name, "content-store", text);
                say(name, "kept the file: it changed after the edit in the content store. Compare the edit, kept as " + kept.getFileName());
                return false;
            }
            Path kept = files.keepBeside(name, "before-fold");
            files.write(name, text);
            say(name, "folded the edit from the content store into " + name + ".yaml (the file before is kept as " + (kept == null ? "history" : kept.getFileName()) + ")");
            return true;
        }
        if (onDisk && store.get(PREFIX + name + ".hidden").isPresent()) {
            if (fileIsNewer(name)) {
                say(name, "kept the file: it changed after the connector was deleted through Admin");
                return false;
            }
            Path kept = files.keepBeside(name, "before-fold");
            files.delete(name);
            say(name, "removed " + name + ".yaml: it was deleted through Admin (the file before is kept as " + (kept == null ? "history" : kept.getFileName()) + ")");
            return true;
        }
        return false;
    }

    private void say(String name, String what) {
        LOG.info("connector {}: {}", name, what);
        audit.record("system", "connector-folded", name, what);
    }
}
