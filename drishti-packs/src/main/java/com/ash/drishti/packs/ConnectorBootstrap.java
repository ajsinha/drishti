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
package com.ash.drishti.packs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What happens to connector files at start, before anything reads them:
 * <ol>
 *   <li><b>Migration</b>, once: the data-source overrides an administrator saved from Admin &rarr; Packs &rarr; Data source
 *   ({@code data/packs/settings/<pack>.yaml}) become edits of the connector files of the same names. The override file is
 *   moved to {@code .migrated/} (a backup), and the connector file's earlier text is kept in its {@code .history/}.</li>
 *   <li><b>Generation</b>: a pack's connector template with no file of that name gets one written from it, once. An existing
 *   file is never touched.</li>
 * </ol>
 * It reports what it did as messages, for the log and the audit trail.
 */
public final class ConnectorBootstrap {

    /** What was done: connector names written from templates, names migrated, and a line for each step or problem. */
    public record Result(List<String> generated, List<String> migrated, List<String> messages) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private ConnectorBootstrap() {}

    /**
     * Runs migration then generation.
     *
     * @param packs the loaded packs
     * @param files the connector folder
     * @param overrides the old per-pack override folder; null to skip migration
     * @param generate whether to write files from templates
     */
    public static Result run(List<Pack> packs, ConnectorFiles files, PackSettings overrides, boolean generate) {
        List<String> generated = new ArrayList<>();
        List<String> migrated = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        Map<String, Pack> templateOwner = new LinkedHashMap<>();
        Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
        PackLineage lineage = PackLoader.lineage(packs);
        for (String packName : lineage.order()) {          // most specific first: its template is the one used
            packs.stream().filter(p -> p.name().equals(packName)).findFirst().ifPresent(p -> p.connectorTemplates().forEach((n, t) -> {
                if (!templates.containsKey(n)) {
                    templates.put(n, t);
                    templateOwner.put(n, p);
                }
            }));
        }
        if (overrides != null) {
            migrate(overrides, files, templates, templateOwner, migrated, messages);
        }
        if (generate) {
            for (Map.Entry<String, Map<String, Object>> e : templates.entrySet()) {
                String name = e.getKey();
                if (!ConnectorFiles.validName(name)) {
                    messages.add("pack '" + templateOwner.get(name).name() + "' names a connector '" + name
                            + "', which is not a valid connector name (lower case letters, digits, hyphens); no file was generated");
                    continue;
                }
                if (files.exists(name)) {
                    continue;
                }
                try {
                    files.write(name, fromTemplate(files, name, e.getValue(), "Generated at first start from the connector template of pack '"
                            + templateOwner.get(name).name() + "'. Edit it freely: it is never overwritten."));
                    generated.add(name);
                    messages.add("connector file " + name + ".yaml generated from the template of pack '" + templateOwner.get(name).name() + "'");
                } catch (RuntimeException x) {
                    messages.add("cannot write " + files.dir().resolve(name + ".yaml") + " (" + x.getMessage()
                            + "); the pack's template for '" + name + "' is used as it stands");
                }
            }
        }
        return new Result(generated, migrated, messages);
    }

    @SuppressWarnings("unchecked")
    private static String fromTemplate(ConnectorFiles files, String name, Map<String, Object> t, String header) {
        Map<String, Object> flat = new LinkedHashMap<>();
        PackLoader.flatten("", t.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(), flat);
        List<String> kinds = new ArrayList<>();
        if (t.get("kinds") instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x)));
        }
        return files.render("# " + header, String.valueOf(t.get("plugin")), t.get("enabled"), kinds,
                t.get("description") == null ? "" : String.valueOf(t.get("description")), flat);
    }

    @SuppressWarnings("unchecked")
    private static void migrate(PackSettings overrides, ConnectorFiles files, Map<String, Map<String, Object>> templates,
            Map<String, Pack> owners, List<String> migrated, List<String> messages) {
        Path dir = overrides.dir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> docs;
        try (Stream<Path> s = Files.list(dir)) {
            docs = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            messages.add("cannot list " + dir + " to migrate data-source overrides: " + e.getMessage());
            return;
        }
        for (Path doc : docs) {
            String pack = doc.getFileName().toString().replaceFirst("\\.yaml$", "");
            Map<String, Object> over;
            try {
                over = overrides.read(pack);
            } catch (RuntimeException e) {
                messages.add("data-source override " + doc + " cannot be read, left in place: " + e.getMessage());
                continue;
            }
            Object cs = over.get("connectors");
            if (!(cs instanceof Map<?, ?> conns) || conns.isEmpty()) {
                continue;
            }
            Map<String, Object> left = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : conns.entrySet()) {
                String name = String.valueOf(e.getKey());
                Map<String, Object> o = e.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                try {
                    migrateOne(files, name, pack, o, templates.get(name));
                    migrated.add(name);
                    messages.add("data-source override of pack '" + pack + "' for connector '" + name + "' moved into " + name + ".yaml");
                } catch (RuntimeException x) {
                    left.put(name, e.getValue());
                    messages.add("data-source override of pack '" + pack + "' for connector '" + name + "' kept in " + doc + " (" + x.getMessage() + ")");
                }
            }
            try {
                Path backup = dir.resolve(".migrated");
                Files.createDirectories(backup);
                Files.copy(doc, backup.resolve(pack + ".yaml." + STAMP.format(Instant.now())));
                if (left.isEmpty()) {
                    Files.delete(doc);
                } else {
                    overrides.write(pack, Map.of("connectors", left));
                }
                messages.add("original override file of pack '" + pack + "' kept in " + backup);
            } catch (IOException x) {
                messages.add("could not move " + doc + " to its backup (" + x.getMessage() + "); the connector files already carry its content");
            }
        }
    }

    /** The override applied on the file if there is one, else on the template; refuses when there is neither (the plugin is unknown). */
    private static void migrateOne(ConnectorFiles files, String name, String pack, Map<String, Object> over, Map<String, Object> template) {
        if (!ConnectorFiles.validName(name)) {
            throw new IllegalArgumentException("not a valid connector name");
        }
        ConnectorFiles.Definition base = files.exists(name) ? files.read(name) : null;
        if (base == null && template == null) {
            throw new IllegalArgumentException("no pack template and no file says which plugin to use");
        }
        String plugin;
        Object enabled;
        List<String> kinds = new ArrayList<>();
        String description;
        Map<String, Object> settings = new LinkedHashMap<>();
        if (base != null) {
            plugin = base.plugin();
            enabled = base.enabled();
            kinds.addAll(base.kinds());
            description = base.description();
            settings.putAll(base.settings());
        } else {
            plugin = String.valueOf(template.get("plugin"));
            enabled = template.get("enabled");
            if (template.get("kinds") instanceof List<?> l) {
                l.forEach(x -> kinds.add(String.valueOf(x)));
            }
            description = template.get("description") == null ? "" : String.valueOf(template.get("description"));
            PackLoader.flatten("", template.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(), settings);
        }
        if (over.get("enabled") != null) {
            enabled = over.get("enabled");
        }
        if (over.get("settings") instanceof Map<?, ?> st) {
            st.forEach((k, v) -> settings.put(String.valueOf(k), String.valueOf(v)));
        }
        files.write(name, files.render("# Migrated from the data-source override of pack '" + pack + "' (Admin -> Packs -> Data source).", plugin, enabled, kinds,
                description, settings));
    }
}
