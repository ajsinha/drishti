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

import com.ash.lake.config.ConnectorLake;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The effective connector, computed in memory from its layers and never written to disk (docs/guides/DATA_GUIDE.md, "Connector layers"):
 * <ol>
 *   <li><b>Template</b>: a pack's {@code connectors:} entry or a module's {@code connectors/} folder ({@link #templates}), read-only and
 *   versioned with the code;</li>
 *   <li><b>Site override</b>: {@code config/connectors/<name>.yaml} with only what the site changes, deep-merged over the template (a
 *   setting's dotted path is its key: {@code set.SOURCE_SYSTEM}); a file that names another plugin is the whole connector, and a file
 *   with no template is a complete <b>site-only connector</b>;</li>
 *   <li><b>Secrets</b>: {@code ${...}} resolved from the environment and the secrets file when the connector starts, as before.</li>
 * </ol>
 * A file an earlier release generated from a template is read so no site needs a migration: one its stamp (or marker) says was never
 * edited equals its template and is <b>superseded</b> (ignored, the template wins; Admin lists it as safe to delete, it is never deleted
 * automatically); an edited one is a full override. The server writes no connector file at start.
 */
public final class ConnectorLayers {

    /** Where a value came from. */
    public static final String TEMPLATE = "template";
    /** The site's override file. */
    public static final String OVERRIDE = "override";
    /** A complete site file: an edited generated file, a file that names another plugin, or a connector with no template. */
    public static final String SITE = "site";

    /**
     * The connector as the server runs it.
     *
     * @param definition the merged definition; its text and etag are the site file's when there is one
     * @param layer {@link #TEMPLATE} (no usable file, or a superseded generated one), {@link #OVERRIDE} or {@link #SITE}
     * @param origins each setting's layer ({@link #TEMPLATE} or {@link #OVERRIDE}; every setting of a {@link #SITE} file is its own)
     * @param superseded true when a generated file stands for the connector and is ignored: it was never edited, the template wins
     */
    public record Effective(ConnectorFiles.Definition definition, String layer, Map<String, String> origins, boolean superseded) {}

    /** A template and where it comes from ({@code pack trading}, {@code module source-endur}). */
    public record Template(String name, String owner, Map<String, Object> definition) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern GENERATED = Pattern.compile("^# Generated at first start from the connector template of pack '([^']+)'");
    private static final Pattern STAMP = Pattern.compile("^# drishti-template: pack=(\\S*) template=(\\S*) text=(\\S*)\\s*$");
    private static final String STAMP_PREFIX = "# drishti-template: ";

    private ConnectorLayers() {}

    /**
     * Every template of the loaded packs, the most specific pack's first (a pack's module connectors are among its templates, see
     * {@link PackLoader}), each seen through {@code adjust} (the server's view: {@link OfficialReaders}).
     */
    public static Map<String, Template> templates(List<Pack> packs, UnaryOperator<Map<String, Object>> adjust) {
        Map<String, Template> out = new LinkedHashMap<>();
        for (String packName : PackLoader.lineage(packs).order()) {
            packs.stream().filter(p -> p.name().equals(packName)).findFirst().ifPresent(p -> p.connectorTemplates().forEach((n, t) -> {
                if (!out.containsKey(n)) {
                    out.put(n, new Template(n, p.moduleOfConnector(n).map(m -> "module " + m).orElse("pack " + p.name()),
                            adjust.apply(packBound(p.name(), t))));
                }
            }));
        }
        return out;
    }

    /**
     * A pack's template with its lake bound by name (docs/admin/CONFIGURATION.md, "Named lakes, databases and brokers"): a template that
     * reads the shared demo lake reads {@code pack.<pack>.lake} instead when that is set, so moving one pack's data never moves another's;
     * the template as given otherwise.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> packBound(String pack, Map<String, Object> t) {
        if (t == null || !(t.get("settings") instanceof Map<?, ?> s) || !(s.get(ConnectorLake.LAKE) instanceof String lake)) {
            return t;
        }
        String bound = com.ash.drishti.common.NamedResources.packLake(pack, lake);
        if (bound.equals(lake)) {
            return t;
        }
        Map<String, Object> out = new LinkedHashMap<>(t);
        Map<String, Object> settings = new LinkedHashMap<>((Map<String, Object>) s);
        settings.put(ConnectorLake.LAKE, bound);
        out.put("settings", settings);
        return out;
    }

    /**
     * The connectors the site's files define, merged over their templates: every file but the superseded ones; the files that cannot be
     * used are left out and their problems put in {@code problems}.
     */
    public static Map<String, ConnectorFiles.Definition> resolve(Map<String, Template> templates, ConnectorFiles files, Map<String, String> problems) {
        Map<String, ConnectorFiles.Definition> out = new LinkedHashMap<>();
        for (String n : files.names()) {
            try {
                String text = files.text(n);
                if (text == null) {
                    continue;
                }
                Template t = templates.get(n);
                Effective e = effective(files, n, text, t == null ? null : t.definition());
                if (!e.superseded()) {
                    out.put(n, e.definition());
                }
            } catch (ConnectorFiles.InvalidFile | java.io.UncheckedIOException e) {
                problems.put(n, e.getMessage());
            }
        }
        return out;
    }

    /** The names of the generated files that are ignored: never edited, their template is in force. */
    public static List<String> superseded(Map<String, Template> templates, ConnectorFiles files) {
        List<String> out = new ArrayList<>();
        for (String n : files.names()) {
            if (templates.containsKey(n) && supersededFile(files, n)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * The connector a site file and its template make.
     *
     * @param template the template, or null for a site-only connector
     * @throws ConnectorFiles.InvalidFile when the file cannot be used (a site-only connector with no {@code plugin:}, bad YAML ...)
     */
    @SuppressWarnings("unchecked")
    public static Effective effective(ConnectorFiles files, String name, String text, Map<String, Object> template) {
        if (template == null) {
            // a file that says it overrides a template, or that an earlier release generated from one, is never run on its own (DRS-1046)
            java.util.Optional<String> owner = templateKey(text);
            if (owner.isPresent() || generated(text)) {
                throw new ConnectorFiles.InvalidFile(name + ".yaml overrides the template of " + owner.orElse(generatedFrom(text).map(x -> "pack " + x).orElse("an earlier release"))
                        + ", which is not here (its module is off or removed): it is not used (DRS-1046)");
            }
            ConnectorFiles.Definition d = files.parse(name, text);
            return new Effective(d, SITE, own(d, SITE), false);
        }
        ConnectorFiles.Definition t = files.parse(name, templateText(files, name, template, ""));
        if (generated(text)) {
            if (unedited(files, name, text)) {
                return new Effective(t, TEMPLATE, own(t, TEMPLATE), true);
            }
            ConnectorFiles.Definition d = files.parse(name, text);       // an edited generated file: the whole connector, as it was
            return new Effective(d, SITE, own(d, SITE), false);
        }
        Map<String, Object> raw;
        try {
            raw = text.isBlank() ? Map.of() : YAML.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (IOException | RuntimeException e) {
            throw new ConnectorFiles.InvalidFile(name + ".yaml is not valid YAML: " + e.getMessage().lines().findFirst().orElse(""));
        }
        raw = raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
        raw.remove(TEMPLATE_KEY);                                         // the owner the override names: not a setting
        // an override may change any key, the plugin included, and still inherits every other one (KERNEL_AND_MODULES.md section 18.5)
        Object plugin = raw.get("plugin");
        String effectivePlugin = plugin != null && !String.valueOf(plugin).isBlank() ? String.valueOf(plugin).trim() : t.plugin();
        // a key set to null removes the inherited one
        java.util.Set<String> removed = new java.util.LinkedHashSet<>();
        if (raw.get("settings") instanceof Map<?, ?> rs) {
            Map<String, Object> kept = new LinkedHashMap<>();
            rs.forEach((k, v) -> {
                if (v == null) {
                    removed.add(String.valueOf(k));
                } else {
                    kept.put(String.valueOf(k), v);
                }
            });
            raw.put("settings", kept);
        }
        Map<String, Object> withPlugin = new LinkedHashMap<>(raw);
        withPlugin.put("plugin", effectivePlugin);
        if (raw.get("kinds") == null && t.plugin().equals("reactive") && template.get("kinds") instanceof Map<?, ?> km) {
            withPlugin.put("kinds", km);                                  // a reactive connector's computation reads its kinds' parameters
        }
        ConnectorFiles.Definition o;
        try {
            o = files.parse(name, YAML.writeValueAsString(withPlugin));
        } catch (IOException e) {
            throw new ConnectorFiles.InvalidFile(name + ".yaml cannot be read: " + e.getMessage());
        }
        Map<String, String> settings = new LinkedHashMap<>(t.settings());
        Map<String, String> origins = new LinkedHashMap<>();
        t.settings().keySet().forEach(k -> origins.put(k, TEMPLATE));
        // a file naming a lake does not keep the template's explicit-path keys; a file that gives only root over a template naming a
        // lake moves that lake's root (drishti.py lake demo --lake): it keeps the lake, and its root wins in ConnectorLake.expand
        if (o.settings().containsKey(ConnectorLake.LAKE)) {
            settings.keySet().removeIf(k -> k.equals("root") || k.startsWith("table.") || k.startsWith("layout."));
            origins.keySet().retainAll(settings.keySet());
        }
        for (String gone : removed) {
            settings.keySet().removeIf(k -> k.equals(gone) || k.startsWith(gone + "."));
            origins.keySet().removeIf(k -> k.equals(gone) || k.startsWith(gone + "."));
        }
        o.settings().forEach((k, v) -> {
            if (!k.equals(ConnectorFiles.COMPUTE_SETTING) || !o.compute().isEmpty()) {
                settings.put(k, v);
                origins.put(k, OVERRIDE);
            }
        });
        if (!effectivePlugin.equals(t.plugin())) {
            origins.put("plugin", OVERRIDE);
        }
        Map<String, Object> compute = t.compute();
        if (!o.compute().isEmpty()) {
            Map<String, Object> c = new LinkedHashMap<>(t.compute());
            c.putAll(o.compute());
            compute = c;
        }
        ConnectorFiles.Definition d = new ConnectorFiles.Definition(name, effectivePlugin, raw.get("enabled") != null ? o.enabled() : t.enabled(),
                raw.get("kinds") != null ? o.kinds() : t.kinds(), raw.get("description") != null ? o.description() : t.description(), settings,
                text, ConnectorFiles.etag(text), o.modified(), raw.get("requires") != null ? o.requires() : t.requires(), compute,
                raw.get("writes") != null ? o.writes() : t.writes());
        return new Effective(d, OVERRIDE, origins, false);
    }

    /** True when the site's file is a generated file nobody edited: it is ignored, the template is in force. */
    /** The key of an override that names the template it changes ({@code template: module source-endur}); Admin and the tools write it. */
    public static final String TEMPLATE_KEY = "template";

    private static final Pattern TEMPLATE_LINE = Pattern.compile("(?m)^template:\\s*['\"]?([^'\"#\\n]+?)['\"]?\\s*(#.*)?$");

    /** The owner an override names ({@code template:}), if it names one. */
    public static Optional<String> templateKey(String text) {
        Matcher m = text == null ? null : TEMPLATE_LINE.matcher(text);
        return m != null && m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }

    public static boolean supersededFile(ConnectorFiles files, String name) {
        String text = files.exists(name) ? files.text(name) : null;
        return text != null && generated(text) && unedited(files, name, text);
    }

    /** Whether a file was written by an earlier release from a pack's template (its first line says so). */
    public static boolean generated(String text) {
        return text != null && GENERATED.matcher(text).find();
    }

    /** The pack an earlier release generated the file from, or empty. */
    public static Optional<String> generatedFrom(String text) {
        Matcher m = text == null ? null : GENERATED.matcher(text);
        return m != null && m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Whether a generated file is as it was written: its stamp's text fingerprint matches the text after it (2.9.5 on), or, for a file
     * from before the stamp, the marker the server kept matches the file.
     */
    static boolean unedited(ConnectorFiles files, String name, String text) {
        String normal = text.replace("\r\n", "\n");
        int at = 0;
        while (at < normal.length() && normal.charAt(at) == '#') {
            int nl = normal.indexOf('\n', at);
            int end = nl < 0 ? normal.length() : nl + 1;
            String line = normal.substring(at, end).strip();
            at = end;
            if (line.startsWith(STAMP_PREFIX)) {
                Matcher m = STAMP.matcher(line);
                return m.matches() && m.group(3).equals(ConnectorFiles.etag(normal.substring(at)));
            }
        }
        ConnectorFiles.Marker mk = files.marker(name);
        return mk != null && ConnectorFiles.etag(text).equals(mk.fileEtag());
    }

    /** A template as connector-file text (the form Admin shows and the parser reads). */
    @SuppressWarnings("unchecked")
    public static String templateText(ConnectorFiles files, String name, Map<String, Object> t, String header) {
        Map<String, Object> flat = new LinkedHashMap<>();
        PackLoader.flatten("", t.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(), flat);
        List<String> kinds = ConnectorFiles.kindNames(t.get("kinds"));
        return files.render(header.isEmpty() ? "" : "# " + header, String.valueOf(t.get("plugin")), t.get("enabled"), kinds,
                t.get("description") == null ? "" : String.valueOf(t.get("description")), flat, Map.of(), ConnectorFiles.computeOf(t),
                t.get("writes") instanceof Map<?, ?> w ? (Map<String, Object>) w : Map.of());   // a template's write commands are part of it (18.6)
    }

    /**
     * The override text that turns {@code template} into {@code wanted}: only the keys that differ (what Admin and the connector designer
     * write). A setting the template has that {@code wanted} lacks cannot be removed by an override: the caller writes a complete file then.
     */
    public static Map<String, Object> overrideOf(ConnectorFiles files, String name, Map<String, Object> template, ConnectorFiles.Definition wanted) {
        ConnectorFiles.Definition t = files.parse(name, templateText(files, name, template, ""));
        Map<String, Object> out = new LinkedHashMap<>();
        if (!wanted.plugin().equals(t.plugin())) {
            return null;
        }
        if (!wanted.enabled().equals(t.enabled())) {
            out.put("enabled", wanted.enabled().equals("true") || wanted.enabled().equals("false") ? Boolean.valueOf(wanted.enabled()) : wanted.enabled());
        }
        if (!wanted.description().equals(t.description())) {
            out.put("description", wanted.description());
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : wanted.settings().entrySet()) {
            if (!e.getKey().equals(ConnectorFiles.COMPUTE_SETTING) && !e.getValue().equals(t.settings().get(e.getKey()))) {
                settings.put(e.getKey(), e.getValue());
            }
        }
        if (t.settings().keySet().stream().anyMatch(k -> !k.equals(ConnectorFiles.COMPUTE_SETTING) && !wanted.settings().containsKey(k))) {
            return null;                                                   // a template setting dropped: only a complete file says so
        }
        if (!settings.isEmpty()) {
            out.put("settings", settings);
        }
        return out;
    }

    private static Map<String, String> own(ConnectorFiles.Definition d, String layer) {
        Map<String, String> out = new LinkedHashMap<>();
        d.settings().keySet().forEach(k -> out.put(k, layer));
        return out;
    }
}
