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
    public record Result(List<String> generated, List<String> migrated, List<String> messages, List<String> regenerated, List<TemplateChange> changed) {
        public Result(List<String> generated, List<String> migrated, List<String> messages) {
            this(generated, migrated, messages, List.of(), List.of());
        }
    }

    /** A pack's template has moved on from a file the site edited: the file is kept and flagged. */
    public record TemplateChange(String name, String pack, List<String> oldKinds, List<String> newKinds) {
        public String message() {
            return "the pack's template changed (kinds: " + String.join(", ", oldKinds) + " \u2192 " + String.join(", ", newKinds)
                    + "); this file was edited and is kept. Reset to pack default to use the new template";
        }
    }

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
        List<String> regenerated = new ArrayList<>();
        List<TemplateChange> changed = new ArrayList<>();
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
                String packName = templateOwner.get(name).name();
                String header = "Generated at first start from the connector template of pack '" + packName
                        + "'. Edit it freely: it is never overwritten.";
                if (files.exists(name)) {
                    try {
                        revisit(files, name, packName, e.getValue(), header, regenerated, changed, messages);
                    } catch (RuntimeException x) {
                        messages.add("connector file " + name + ".yaml could not be compared with the template of pack '" + packName + "': " + x.getMessage());
                    }
                    continue;
                }
                try {
                    String text = fromTemplate(files, name, e.getValue(), header);
                    files.write(name, text);
                    files.writeMarker(name, text, fingerprint(files, name, e.getValue()), kindsOf(e.getValue()));
                    generated.add(name);
                    messages.add("connector file " + name + ".yaml generated from the template of pack '" + templateOwner.get(name).name() + "'");
                } catch (RuntimeException x) {
                    messages.add("cannot write " + files.dir().resolve(name + ".yaml") + " (" + x.getMessage()
                            + "); the pack's template for '" + name + "' is used as it stands");
                }
            }
        }
        return new Result(generated, migrated, messages, regenerated, changed);
    }

    /**
     * A file already stands for a template. Written by us and not edited since: rewritten when the template changed. Edited:
     * kept, and reported when the template changed. A file from before markers existed is adopted when it still carries our
     * header and differs from the template only in kinds or description.
     */
    private static void revisit(ConnectorFiles files, String name, String pack, Map<String, Object> t, String header, List<String> regenerated,
            List<TemplateChange> changed, List<String> messages) {
        String fp = fingerprint(files, name, t);
        String text = files.text(name);
        ConnectorFiles.Marker mk = files.marker(name);
        List<String> newKinds = kindsOf(t);
        if (mk == null) {
            if (text == null || !text.startsWith("# Generated at first start")) {
                return;                                                  // the site's own file
            }
            String fresh = fromTemplate(files, name, t, header);
            if (text.equals(fresh)) {
                files.writeMarker(name, text, fp, newKinds);
                return;
            }
            ConnectorFiles.Definition d = files.parse(name, text);
            ConnectorFiles.Definition f = files.parse(name, fresh);
            if (d.plugin().equals(f.plugin()) && d.settings().equals(f.settings()) && d.enabled().equals(f.enabled())) {
                mk = new ConnectorFiles.Marker(ConnectorFiles.etag(text), "", d.kinds());          // ours, never edited: same as a marked file
            } else {
                TemplateChange c = new TemplateChange(name, pack, d.kinds(), newKinds);
                changed.add(c);
                messages.add("connector file " + name + ".yaml was edited and the template of pack '" + pack + "' differs; the file is kept. " + c.message());
                return;
            }
        }
        if (fp.equals(mk.templateFingerprint())) {
            return;
        }
        if (ConnectorFiles.etag(text).equals(mk.fileEtag())) {
            String fresh = fromTemplate(files, name, t, header);
            files.write(name, fresh);
            files.writeMarker(name, fresh, fp, newKinds);
            regenerated.add(name);
            messages.add("connector file " + name + ".yaml regenerated: the template of pack '" + pack + "' changed (kinds: "
                    + String.join(", ", mk.kinds()) + " -> " + String.join(", ", newKinds) + ") and the file had not been edited");
        } else {
            TemplateChange c = new TemplateChange(name, pack, mk.kinds(), newKinds);
            changed.add(c);
            messages.add("connector file " + name + ".yaml was edited and the template of pack '" + pack + "' changed; the file is kept. " + c.message());
        }
    }

    /** What a template says, apart from the header: equal templates have equal fingerprints. */
    public static String fingerprint(ConnectorFiles files, String name, Map<String, Object> t) {
        return ConnectorFiles.etag(fromTemplate(files, name, t, ""));
    }

    /** The kinds a template names. */
    public static List<String> kindsOf(Map<String, Object> t) {
        List<String> kinds = new ArrayList<>();
        if (t.get("kinds") instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x)));
        }
        return kinds;
    }

    /** The flag for an edited file whose generating template changed, or null. */
    public static TemplateChange pending(ConnectorFiles files, String name, String pack, Map<String, Object> t) {
        ConnectorFiles.Marker mk = files.marker(name);
        String text = files.text(name);
        if (mk == null || text == null || ConnectorFiles.etag(text).equals(mk.fileEtag()) || fingerprint(files, name, t).equals(mk.templateFingerprint())) {
            return null;
        }
        return new TemplateChange(name, pack, mk.kinds(), kindsOf(t));
    }

    @SuppressWarnings("unchecked")
    private static String fromTemplate(ConnectorFiles files, String name, Map<String, Object> t, String header) {
        Map<String, Object> flat = new LinkedHashMap<>();
        PackLoader.flatten("", t.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(), flat);
        List<String> kinds = new ArrayList<>();
        if (t.get("kinds") instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x)));
        }
        return files.render(header.isEmpty() ? "" : "# " + header, String.valueOf(t.get("plugin")), t.get("enabled"), kinds,
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
                Path backup = dir.resolve("migrated");
                com.ash.drishti.common.LegacyNames.migrate(dir, ".migrated", "migrated");
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
