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
package com.ash.drishti.net.member;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * This node's stable id: {@code node-id} from {@code node.yaml} when set, else the one kept in {@code <data dir>/net/node-id},
 * generated once ({@code n-} and ten hex digits) the first time the cluster is switched on.
 */
public final class NodeIdentity {

    /** What a node id may be: lower-case letters, digits and dashes, starting with a letter or digit, at most 64. */
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private NodeIdentity() {
    }

    public static String resolve(String configured, Path file) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            if (Files.isRegularFile(file)) {
                String kept = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (ID.matcher(kept).matches()) {
                    return kept;
                }
            }
            byte[] b = new byte[5];
            new SecureRandom().nextBytes(b);
            String id = "n-" + HexFormat.of().formatHex(b);
            Files.createDirectories(file.getParent());
            Files.writeString(file, id + "\n", StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot keep the node id in " + file, e);
        }
    }
}
