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
package com.ash.drishti.server.admin;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.packs.PackLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/** Where each kind of artifact lives on disk, and the lookups that turn a name into a path. One place, so every action agrees. */
public final class ArtifactPaths {

    /** BI file kinds and the folder each lives in under {@code drishti.bi.dir}. */
    public static final Map<String, String> BI_FOLDERS = Map.of("dataset", "datasets", "report", "reports", "board", "boards", "notebook", "notebooks", "python", "python");
    /** A BI file's name: what the file name and the item's own key agree on. */
    public static final Pattern BI_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,80}");

    /** A pack folder: where it is, which area it is in ({@code packs} or {@code installed}) and what its {@code pack.yaml} says. */
    public record PackFolder(Path dir, String area, String name, String title, String version, String problem) {}

    private final Path packs;
    private final Path installed;
    private final Path connectors;
    private final Path bi;

    public ArtifactPaths(Path packs, Path installed, Path connectors, Path bi) {
        this.packs = packs.toAbsolutePath().normalize();
        this.installed = installed.toAbsolutePath().normalize();
        this.connectors = connectors.toAbsolutePath().normalize();
        this.bi = bi.toAbsolutePath().normalize();
    }

    public Path packs() {
        return packs;
    }

    public Path installed() {
        return installed;
    }

    public Path connectors() {
        return connectors;
    }

    public Path bi() {
        return bi;
    }

    /** Every pack folder on disk, installed ones first (they win, as in the loader). */
    public List<PackFolder> packFolders() {
        List<PackFolder> out = new ArrayList<>();
        for (Path root : PackLoader.dirs(packs.toString(), installed.toString())) {
            String area = root.equals(installed) ? "installed" : "packs";
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var s = Files.list(root)) {
                for (Path d : s.filter(x -> Files.isRegularFile(x.resolve("pack.yaml"))).sorted().toList()) {
                    out.add(read(d, area));
                }
            } catch (IOException e) {
                // an unreadable folder lists nothing
            }
        }
        return out;
    }

    public Optional<PackFolder> packFolder(String name) {
        return packFolders().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static PackFolder read(Path d, String area) {
        String folder = d.getFileName().toString();
        try (InputStream in = Files.newInputStream(d.resolve("pack.yaml"))) {
            Object y = new Yaml().load(in);
            Map<String, Object> m = y instanceof Map<?, ?> ? (Map<String, Object>) y : Map.of();
            String name = String.valueOf(m.getOrDefault("pack", folder));
            return new PackFolder(d, area, name, String.valueOf(m.getOrDefault("title", name)), String.valueOf(m.getOrDefault("version", "")), "");
        } catch (IOException | RuntimeException e) {
            return new PackFolder(d, area, folder, folder, "", "pack.yaml cannot be read: " + e.getMessage());
        }
    }

    /** The path of a shipped BI file; the name and kind are checked so nothing outside the BI folder can be named. */
    public Path biFile(String subtype, String name) {
        String dir = BI_FOLDERS.get(subtype);
        if (dir == null) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "a BI file is a dataset, report, board, notebook or python, not '" + subtype + "'");
        }
        if (name == null || !BI_NAME.matcher(name).matches()) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "'" + name + "' is not a BI file name (letters, digits, hyphen, underscore)");
        }
        return bi.resolve(dir).resolve(name + "." + subtype + ".yaml");
    }

    /** The BI-relative path ({@code datasets/x.dataset.yaml}) recorded in an archive. */
    public String biRel(String subtype, String name) {
        return BI_FOLDERS.get(subtype) + "/" + name + "." + subtype + ".yaml";
    }

    /** Resolves a path recorded in an archive back under its area, refusing anything that would leave it. */
    public Path resolve(String area, String rel) {
        Path root = switch (area) {
            case "packs" -> packs;
            case "installed" -> installed;
            case "connectors" -> connectors;
            case "bi" -> bi;
            default -> throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "unknown area '" + area + "' in the archive record");
        };
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "the archive record points outside " + area + ": " + rel);
        }
        return p;
    }
}
