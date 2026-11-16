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
package com.ash.drishti.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drishti writes no hidden files or folders. Older builds did (a leading dot); this renames such an item once to its visible name, logs it, and never
 * loses data: when both names exist the contents of the old folder are moved into the new one (a file already there wins and the old copy is left in place).
 */
public final class LegacyNames {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyNames.class);

    private LegacyNames() {}

    /** Moves {@code parent/oldName} to {@code parent/newName} when the old one exists. Returns the new path. A failed move is logged and the old item kept. */
    public static Path migrate(Path parent, String oldName, String newName) {
        return move(parent.resolve(oldName), parent.resolve(newName));
    }

    /** As {@link #migrate} for two full paths (the new one may be in another folder; its parent is made when needed). */
    public static Path move(Path from, Path to) {
        try {
            if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                return to;
            }
            if (!Files.exists(to)) {
                Files.createDirectories(to.toAbsolutePath().getParent());
                Files.move(from, to);
                LOG.info("renamed {} to {} (Drishti no longer uses hidden names)", from, to);
            } else if (Files.isDirectory(from) && Files.isDirectory(to)) {
                int moved = 0;
                try (Stream<Path> s = Files.list(from)) {
                    for (Path p : s.toList()) {
                        Path target = to.resolve(p.getFileName().toString());
                        if (!Files.exists(target)) {
                            Files.move(p, target);
                            moved++;
                        }
                    }
                }
                try (Stream<Path> rest = Files.list(from)) {
                    if (rest.findAny().isEmpty()) {
                        Files.delete(from);
                    }
                }
                LOG.info("merged {} into {} ({} items moved; Drishti no longer uses hidden names)", from, to, moved);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not rename {} to {}: {}; the old one is kept", from, to, e.getMessage());
        }
        return to;
    }
}
