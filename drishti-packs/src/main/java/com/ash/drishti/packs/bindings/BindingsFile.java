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
package com.ash.drishti.packs.bindings;

import com.ash.drishti.packs.ConnectorFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/bindings.yaml}, the one home of the site's data bindings (docs/architecture/KERNEL_AND_MODULES.md section 5.4, decision Q2).
 * Read whole, written whole and atomically (the previous file kept as {@code bindings.yaml.bak}). Where it lives: {@code drishti.bindings.file},
 * else {@code bindings.yaml} beside the connectors' folder.
 */
public final class BindingsFile {

    /** The setting naming the file. */
    public static final String PROPERTY = "drishti.bindings.file";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private static final String HEADER = """
            # The site's data bindings (docs/guides/DATA_LAKE_GUIDE.md, "Data bindings"; docs/architecture/KERNEL_AND_MODULES.md section 5.4).
            # One binding per kind, shared by every module that needs it: the connectors (config/connectors/<name>.yaml) that serve it, in read
            # order; union: one store across them; live: the connector that pushes changes; write: where a produced kind is written; demo: the
            # demo lake's samples. Never a host, a URL or a credential. Changed from Admin -> Data bindings (four eyes) or this file; applied
            # without a restart.
            """;

    private final Path file;

    public BindingsFile(Path file) {
        this.file = file;
    }

    /**
     * The file beside a connectors' folder: {@code config/connectors} → {@code config/bindings.yaml}; a folder named otherwise ({@code site-a})
     * → {@code site-a-bindings.yaml} beside it, so two sites' folders in one parent never share a file.
     */
    public static BindingsFile besideConnectors(Path connectorsDir) {
        Path dir = connectorsDir.toAbsolutePath().normalize();
        String name = dir.getFileName() == null ? "connectors" : dir.getFileName().toString();
        return new BindingsFile(dir.resolveSibling("connectors".equals(name) ? "bindings.yaml" : name + "-bindings.yaml"));
    }

    public Path path() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /** The file's bindings; {@link Bindings#NONE} when there is no file. */
    public Bindings read() {
        String text = text();
        if (text == null || text.isBlank()) {
            return Bindings.NONE;
        }
        try {
            Map<String, Object> m = YAML.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            return Bindings.parse(m);
        } catch (IOException e) {
            throw new Bindings.Invalid("bindings.yaml is not YAML: " + e.getMessage().lines().findFirst().orElse(""));
        }
    }

    /** The file's text, or null when there is none. */
    public String text() {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file's version: its text's hash ({@code ""} when there is no file), so a change made meanwhile is not overwritten. */
    public String etag() {
        String t = text();
        return t == null ? "" : ConnectorFiles.etag(t);
    }

    /** The YAML text of {@code b}, with the file's header. */
    public static String render(Bindings b) {
        try {
            return HEADER + YAML.writeValueAsString(b.toMap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code b}, keeping the previous file as {@code bindings.yaml.bak}. */
    public synchronized void write(Bindings b) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, render(b), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The one-time upgrade (section 5.4): when there is no {@code bindings.yaml} yet, writes one from the {@code kinds:} lists of today's
     * connector files, each kind bound to the connectors that list it, in file order ({@code union: true} when they are a union), so a site
     * keeps reading what it read. Reactive connectors compute their kinds and are left out. Returns the bindings written, or empty when a
     * file already exists or no connector file lists kinds.
     */
    public java.util.Optional<Bindings> upgradeFrom(Map<String, ConnectorFiles.Definition> connectorFiles) {
        if (exists()) {
            return java.util.Optional.empty();
        }
        Bindings b = fromConnectorKinds(connectorFiles);
        if (b.byKind().isEmpty()) {
            return java.util.Optional.empty();
        }
        write(b);
        return java.util.Optional.of(b);
    }

    /** The bindings today's connector files imply by their {@code kinds:} lists. */
    public static Bindings fromConnectorKinds(Map<String, ConnectorFiles.Definition> connectorFiles) {
        Map<String, List<String>> readers = new LinkedHashMap<>();
        Map<String, Boolean> unions = new LinkedHashMap<>();
        new java.util.TreeMap<>(connectorFiles).forEach((name, d) -> {
            if ("reactive".equals(d.plugin()) || "demo".equals(d.plugin())) {
                return;                                                 // computed by the connector, or the samples: not a site binding
            }
            boolean union = "true".equalsIgnoreCase(String.valueOf(d.settings().get("union")));
            for (String k : d.kinds()) {
                if (k.contains("${") || !Bindings.NAME.matcher(k).matches()) {
                    continue;
                }
                readers.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(name);
                unions.merge(k, union, Boolean::logicalAnd);
            }
        });
        Map<String, Bindings.Binding> out = new LinkedHashMap<>();
        readers.forEach((k, r) -> out.put(k, new Bindings.Binding(k, r, r.size() > 1 && unions.get(k), null, null, null, false,
                "written from the connectors' kinds: lists by the upgrade")));
        return new Bindings(out);
    }
}
