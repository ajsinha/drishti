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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/** Where each kind of artifact lives on disk, and the lookups that turn a name into a path. One place, so every action agrees. */
public final class ArtifactPaths {

    /** BI file kinds and the folder each lives in under {@code drishti.bi.dir}. */
    public static final Map<String, String> BI_FOLDERS = Map.of("report", "reports", "board", "boards", "notebook", "notebooks", "python", "python", "theme", "themes");
    /** The table layer's kinds, whose files live under {@code drishti.tables.root} in a folder per domain ({@code <domain>/<name>.table.yaml}). */
    public static final Set<String> LAYER_KINDS = Set.of("table", "model");
    /** A BI file's name: what the file name and the item's own key agree on. */
    public static final Pattern BI_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,80}");
    /** A table's name, {@code <domain>.<name>}. */
    public static final Pattern TABLE_NAME = Pattern.compile("([a-z][a-z0-9_]{0,63})\\.([a-z][a-z0-9_]{0,79})");
    /** A model's name, {@code <name>} or {@code <domain>.<name>}. */
    public static final Pattern MODEL_NAME = Pattern.compile("(?:([a-z][a-z0-9_]{0,63})\\.)?([a-z0-9][a-z0-9-]{0,79})");
    /** The domain folder a model without a domain in its name is written to. */
    public static final String DEFAULT_DOMAIN = "bi";

    /** A pack folder: where it is, which area it is in ({@code packs} or {@code installed}) and what its {@code pack.yaml} says. */
    public record PackFolder(Path dir, String area, String name, String title, String version, String problem) {}

    private final Path packs;
    private final Path installed;
    private final Path connectors;
    private final Path bi;
    private final Path tables;

    public ArtifactPaths(Path packs, Path installed, Path connectors, Path bi, Path tables) {
        this.packs = packs.toAbsolutePath().normalize();
        this.installed = installed.toAbsolutePath().normalize();
        this.connectors = connectors.toAbsolutePath().normalize();
        this.bi = bi.toAbsolutePath().normalize();
        this.tables = tables.toAbsolutePath().normalize();
    }

    public Path tables() {
        return tables;
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
            String area = root.startsWith(installed) ? "installed" : "packs";   // the roots include the domain folders (PackPaths.roots)
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

    /**
     * The path of a shipped BI file; the name and kind are checked so nothing outside the BI folder or the table layer's folder can be named.
     * A table {@code <domain>.<name>} is {@code <domain>/<name>.table.yaml} under the table layer's folder; a model is found in any domain
     * folder, and a new one without a domain goes to {@value #DEFAULT_DOMAIN}.
     */
    public Path biFile(String subtype, String name) {
        if (LAYER_KINDS.contains(subtype)) {
            return tables.resolve(layerRel(subtype, name));
        }
        String dir = BI_FOLDERS.get(subtype);
        if (dir == null) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "a BI file is a table, model, report, board, notebook or python, not '" + subtype + "'");
        }
        if (name == null || !BI_NAME.matcher(name).matches()) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "'" + name + "' is not a BI file name (letters, digits, hyphen, underscore)");
        }
        return bi.resolve(dir).resolve(name + "." + subtype + ".yaml");
    }

    /** The path an archive records: relative to the BI folder ({@code reports/x.report.yaml}) or to the table layer's ({@code bi/x.table.yaml}). */
    public String biRel(String subtype, String name) {
        return LAYER_KINDS.contains(subtype) ? layerRel(subtype, name) : BI_FOLDERS.get(subtype) + "/" + name + "." + subtype + ".yaml";
    }

    /** The area an archive records a BI file under: {@code tables} for the table layer's kinds, {@code bi} for the rest. */
    public static String biArea(String subtype) {
        return LAYER_KINDS.contains(subtype) ? "tables" : "bi";
    }

    /** The key the BI folder reads a file under ({@code reports/x.report.yaml}, {@code tables/bi/x.table.yaml}), which its problems carry. */
    public String biKey(String subtype, String name) {
        return LAYER_KINDS.contains(subtype) ? "tables/" + layerRel(subtype, name) : biRel(subtype, name);
    }

    private String layerRel(String subtype, String name) {
        if ("table".equals(subtype)) {
            Matcher m = name == null ? null : TABLE_NAME.matcher(name);
            if (m == null || !m.matches()) {
                throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "'" + name + "' is not a table name (<domain>.<name>: lower case, letters, digits and underscores)");
            }
            return m.group(1) + "/" + m.group(2) + ".table.yaml";
        }
        Matcher m = name == null ? null : MODEL_NAME.matcher(name);
        if (m == null || !m.matches()) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "'" + name + "' is not a model name (lower case, letters, digits, hyphens; a domain before a dot)");
        }
        String file = m.group(2) + ".model.yaml";
        if (m.group(1) != null) {
            return m.group(1) + "/" + file;
        }
        if (Files.isDirectory(tables)) {
            try (var s = Files.list(tables)) {
                for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                    if (Files.isRegularFile(d.resolve(file))) {
                        return d.getFileName() + "/" + file;
                    }
                }
            } catch (IOException e) {
                // an unreadable folder holds nothing: the default domain is used
            }
        }
        return DEFAULT_DOMAIN + "/" + file;
    }

    /** Resolves a path recorded in an archive back under its area, refusing anything that would leave it. */
    public Path resolve(String area, String rel) {
        Path root = switch (area) {
            case "packs" -> packs;
            case "installed" -> installed;
            case "connectors" -> connectors;
            case "bi" -> bi;
            case "tables" -> tables;
            default -> throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "unknown area '" + area + "' in the archive record");
        };
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "the archive record points outside " + area + ": " + rel);
        }
        return p;
    }
}
