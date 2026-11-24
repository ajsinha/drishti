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
package com.ash.drishti.server.explain;

import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.AboutProperties;
import com.ash.drishti.rachana.about.AboutText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The text about a kind for Ask's prompt, from its pack's README (the README standard, generated from pack.yaml, the samples and the
 * contracts: since i7k a pack has no guide of its own; its facts are its README and contracts, its story a section of a feature guide):
 * the section whose heading is the kind ({@code ### `kind`} under Data structures), else one whose heading names the kind or its
 * mnemonic, else the start of the README. Files are read lazily and kept until they change. Never reads outside the pack's own folder.
 */
public final class PackGuides {

    /** The pack's own documentation, at its root beside pack.yaml. */
    static final String README = "README.md";

    private record Cached(long modified, String text) {}

    private final AboutCatalog catalog;
    private final AboutProperties props;
    private final ConcurrentHashMap<Path, Cached> files = new ConcurrentHashMap<>();

    public PackGuides(AboutCatalog catalog, AboutProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    /** The README section for the kind of the pack, or empty when the kind has no about entry or the pack no README. */
    public Optional<String> sectionFor(String pack, String kind, String mnemonic) {
        Optional<AboutText> about = catalog.forKind(kind);
        if (about.isEmpty() || pack == null) {
            return Optional.empty();
        }
        Path root = catalog.sources().stream().filter(p -> p.name().equals(pack) && p.file() != null).map(p -> packRoot(Path.of(p.file()))).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (root == null) {
            return Optional.empty();
        }
        String text = read(root.resolve(README));
        return text == null ? Optional.empty() : Optional.of(section(text, kind, mnemonic));
    }

    /** The folder holding pack.yaml, found upward from the about file. */
    private static Path packRoot(Path aboutFile) {
        for (Path p = aboutFile.toAbsolutePath().normalize().getParent(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("pack.yaml"))) {
                return p;
            }
        }
        return null;
    }

    private String read(Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Cached c = files.get(file);
            if (c != null && c.modified() == modified) {
                return c.text();
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            files.put(file, new Cached(modified, text));
            return text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The markdown section of the kind, up to the next heading of the same or a higher level: the heading that is the kind in backticks
     * (a README's Data structures), else the first heading naming the kind or its mnemonic as a word; else the whole text.
     */
    static String section(String md, String kind, String mnemonic) {
        String[] lines = md.split("\n", -1);
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        String m = mnemonic == null ? "" : mnemonic.toLowerCase(Locale.ROOT);
        int at = -1;
        for (int i = 0; i < lines.length && at < 0 && !k.isEmpty(); i++) {
            if (level(lines[i]) > 0 && lines[i].substring(level(lines[i]) + 1).trim().toLowerCase(Locale.ROOT).equals("`" + k + "`")) {
                at = i;
            }
        }
        for (int i = 0; i < lines.length && at < 0; i++) {
            String h = lines[i].toLowerCase(Locale.ROOT);
            if (level(lines[i]) > 0 && (!k.isEmpty() && h.matches(".*\\b" + java.util.regex.Pattern.quote(k) + "\\b.*") || !m.isEmpty() && h.matches(".*\\b" + java.util.regex.Pattern.quote(m) + "\\b.*"))) {
                at = i;
            }
        }
        if (at < 0) {
            return md;
        }
        int level = level(lines[at]);
        StringBuilder out = new StringBuilder();
        for (int j = at; j < lines.length; j++) {
            int l = level(lines[j]);
            if (j > at && l > 0 && l <= level) {
                break;
            }
            out.append(lines[j]).append('\n');
        }
        return out.toString();
    }

    private static int level(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        return n > 0 && n < line.length() && line.charAt(n) == ' ' ? n : 0;
    }
}
