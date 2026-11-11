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
package com.ash.drishti.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.yaml.snakeyaml.Yaml;

/**
 * The packs an administrator loaded from Admin → Packs: {@code drishti.packs.added} in a small configuration file the
 * server imports at start ({@code DRISHTI_PACKS_OVERLAY}, default {@code ./data/packs/added.yaml}, under {@code drishti.data.dir}). Written atomically;
 * changes are serialised.
 */
public final class PackOverlay {

    private static final String HEADER = """
            # Packs loaded by an administrator from Admin → Packs. Written by the Drishti server: change them there.
            # Read at every start, together with drishti.packs.enabled.
            """;
    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();

    public PackOverlay(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    public List<String> added() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object root = new Yaml().load(in);
            Object drishti = root instanceof Map<?, ?> m ? m.get("drishti") : null;
            Object packs = drishti instanceof Map<?, ?> m ? m.get("packs") : null;
            Object added = packs instanceof Map<?, ?> m ? m.get("added") : null;
            List<String> out = new ArrayList<>();
            if (added instanceof List<?> l) {
                l.forEach(x -> out.add(String.valueOf(x)));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces the list; returns the previous one (to put back if the server cannot start with the new one). */
    public List<String> write(List<String> packs) {
        lock.lock();
        try {
            List<String> before = added();
            StringBuilder y = new StringBuilder(HEADER).append("drishti:\n  packs:\n    added:");
            LinkedHashSet<String> unique = new LinkedHashSet<>(packs);
            if (unique.isEmpty()) {
                y.append(" []\n");
            } else {
                y.append('\n');
                unique.forEach(p -> y.append("      - ").append(p).append('\n'));
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, y, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return before;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    public Path file() {
        return file;
    }
}
