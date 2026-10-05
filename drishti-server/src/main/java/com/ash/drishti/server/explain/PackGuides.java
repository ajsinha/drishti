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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

/**
 * The text of a pack's guide for a kind, for Ask's prompt: the kind's {@code guide:} slug (about.yaml) looked up in the pack's
 * {@code config/help.yaml}, the markdown file read from the pack folder, and the section whose heading names the kind (else
 * the start of the guide). Files are read lazily and kept until they change. Never reads outside the pack's own folder.
 */
public final class PackGuides {

    private record Cached(long modified, String text) {}

    private final AboutCatalog catalog;
    private final AboutProperties props;
    private final ConcurrentHashMap<Path, Cached> files = new ConcurrentHashMap<>();

    public PackGuides(AboutCatalog catalog, AboutProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    /** The guide section for the kind of the pack, or empty when the pack has none. */
    public Optional<String> sectionFor(String pack, String kind, String mnemonic) {
        Optional<AboutText> about = catalog.forKind(kind);
        if (about.isEmpty() || about.get().guide() == null || pack == null) {
            return Optional.empty();
        }
        Path root = props.packs().stream().filter(p -> p.name().equals(pack) && p.file() != null).map(p -> packRoot(Path.of(p.file()))).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (root == null) {
            return Optional.empty();
        }
        String rel = guideFile(root, about.get().guide());
        if (rel == null) {
            return Optional.empty();
        }
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root)) {
            return Optional.empty();                      // a guide file never reaches outside its pack
        }
        String text = read(file);
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

    @SuppressWarnings("unchecked")
    private String guideFile(Path root, String slug) {
        Path help = root.resolve("config/help.yaml");
        String text = read(help);
        if (text == null) {
            return null;
        }
        try {
            Object doc = new Yaml().load(text);
            if (doc instanceof Map<?, ?> m && m.get("guides") instanceof List<?> gs) {
                for (Object g : gs) {
                    if (g instanceof Map<?, ?> gm && slug.equals(String.valueOf(gm.get("slug"))) && gm.get("file") != null) {
                        return String.valueOf(gm.get("file"));
                    }
                }
            }
        } catch (RuntimeException e) {
            return null;
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

    /** The markdown section whose heading names the kind (or its mnemonic), up to the next heading of the same or a higher level; else the start. */
    static String section(String md, String kind, String mnemonic) {
        String[] lines = md.split("\n", -1);
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        String m = mnemonic == null ? "" : mnemonic.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.length; i++) {
            int level = level(lines[i]);
            String h = lines[i].toLowerCase(Locale.ROOT);
            if (level > 0 && (!k.isEmpty() && h.matches(".*\\b" + java.util.regex.Pattern.quote(k) + "\\b.*") || !m.isEmpty() && h.matches(".*\\b" + java.util.regex.Pattern.quote(m) + "\\b.*"))) {
                StringBuilder out = new StringBuilder();
                for (int j = i; j < lines.length; j++) {
                    int l = level(lines[j]);
                    if (j > i && l > 0 && l <= level) {
                        break;
                    }
                    out.append(lines[j]).append('\n');
                }
                return out.toString();
            }
        }
        return md;
    }

    private static int level(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        return n > 0 && n < line.length() && line.charAt(n) == ' ' ? n : 0;
    }
}
