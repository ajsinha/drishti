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
package com.ash.drishti.compute.env;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Python lock file (docs/architecture/PYTHON_COMPUTE.md section 4): every package, transitive ones included, pinned to one version with at
 * least one sha256 hash, in pip's requirements syntax ({@code name==version --hash=sha256:<64 hex>}, continuation lines with a backslash,
 * {@code #} comments). Anything looser (a range, no hash, an URL, an option line) is refused: the lock is installed with
 * {@code pip --require-hashes --no-deps}, never resolved.
 */
public record LockFile(List<Pin> pins, String text, String sha256) {

    /** One pinned package: its normalised name, version and accepted hashes. */
    public record Pin(String name, String version, List<String> hashes) {}

    private static final Pattern REQ = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)\\s*==\\s*([A-Za-z0-9][A-Za-z0-9.+!_-]*)\\s*(.*)$");
    private static final Pattern HASH = Pattern.compile("--hash[= ]sha256:([0-9a-fA-F]{64})");

    public LockFile {
        pins = List.copyOf(pins);
    }

    public static LockFile parse(String text, String where) {
        List<Pin> pins = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        String[] lines = text.replace("\r\n", "\n").split("\n");
        StringBuilder logical = new StringBuilder();
        int lineNo = 0;
        int startLine = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw;
            int hash = line.indexOf(" #");
            if (line.stripLeading().startsWith("#")) {
                line = "";
            } else if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.strip();
            if (logical.isEmpty()) {
                startLine = lineNo;
            }
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            logical.append(line);
            String req = logical.toString().strip();
            logical.setLength(0);
            if (req.isEmpty()) {
                continue;
            }
            if (req.startsWith("-")) {
                throw new IllegalArgumentException(where + " line " + startLine + ": option lines are not allowed in a lock ('" + first(req) + "')");
            }
            Matcher m = REQ.matcher(req);
            if (!m.matches()) {
                throw new IllegalArgumentException(where + " line " + startLine + ": '" + first(req) + "' is not name==version with hashes");
            }
            String rest = m.group(3);
            List<String> hashes = new ArrayList<>();
            Matcher h = HASH.matcher(rest);
            while (h.find()) {
                hashes.add(h.group(1).toLowerCase(Locale.ROOT));
            }
            if (hashes.isEmpty()) {
                throw new IllegalArgumentException(where + " line " + startLine + ": " + m.group(1) + " has no --hash=sha256:... (every package is pinned by hash)");
            }
            String leftover = HASH.matcher(rest).replaceAll("").strip();
            if (!leftover.isEmpty()) {
                throw new IllegalArgumentException(where + " line " + startLine + ": unexpected '" + first(leftover) + "' after " + m.group(1));
            }
            String name = normalise(m.group(1));
            if (!names.add(name)) {
                throw new IllegalArgumentException(where + ": " + name + " is pinned twice");
            }
            pins.add(new Pin(name, m.group(2), hashes));
        }
        if (pins.isEmpty()) {
            throw new IllegalArgumentException(where + " pins no package");
        }
        return new LockFile(pins, text, sha256(text));
    }

    /** PEP 503's normalised name: lower case, runs of {@code -_.} as one hyphen. */
    public static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    private static String first(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
