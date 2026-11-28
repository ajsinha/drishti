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
package com.ash.drishti.server.cli;

import com.ash.drishti.api.resolution.ConfigResolution;
import com.ash.drishti.api.resolution.ModuleTemplates;
import com.ash.drishti.api.resolution.PackResolver;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.ConnectorLayers;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * {@code resolve --json} (THIN_CONSOLE_DESIGN.md 3.5, step S5): the one resolver's answer for a checkout or a site, printed as JSON with no
 * server running, so the Python tools read where everything is instead of working it out a second time ({@code tools/resolved.py} reads
 * the document, with no rule of its own). The same Java code the server resolves with: {@link ConfigResolution}, {@link PackLoader},
 * {@link ConnectorLayers}.
 *
 * <pre>
 *   java -jar drishti-server-…-exec.jar resolve --json [--root DIR] [--connectors DIR]
 * </pre>
 * {@code --site DIR}: a folder of packs read as a site of its own, with no modules. {@code --root DIR}: a checkout (every module on, the {@code all} distribution), as the generators want it; without it, this process's
 * own settings ({@code drishti.*} system properties, else their environment variables, the switch files for what is on). {@code
 * --connectors DIR}: the site's connector files (default {@code drishti.sources.connectors-dir}, {@code DRISHTI_CONNECTORS_DIR}, else
 * {@code config/connectors} under the root). Secrets stay as their {@code ${…}} references: no value is resolved.
 *
 * <p>The document: {@code version}, {@code layout} (site, kernel, modules, store, installed, extra), {@code packs} (name → {dir, manifest,
 * layer, module, origin}), {@code off} (pack → module), {@code modules} (id → {home, folder, packs, source, connectors, dataload,
 * requires}), {@code connectorsDir}, {@code templates} (name → {owner, definition}), {@code connectors} (name → {plugin, enabled, kinds,
 * description, settings, layer, origins, superseded, file}), {@code unused} (name → why), {@code rules}, {@code problems}.
 */
public final class ResolveCli {

    private ResolveCli() {
    }

    public static boolean isCli(String[] args) {
        return args.length > 0 && "resolve".equals(args[0]);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Path root = null;
        Path site = null;
        Path connectors = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--json" -> {
                    // the only format
                }
                case "--root" -> {
                    if (++i >= args.length) {
                        err.println("resolve: --root needs a folder");
                        return 2;
                    }
                    root = Path.of(args[i]).toAbsolutePath().normalize();
                }
                case "--site" -> {
                    if (++i >= args.length) {
                        err.println("resolve: --site needs a folder");
                        return 2;
                    }
                    site = Path.of(args[i]).toAbsolutePath().normalize();
                }
                case "--connectors" -> {
                    if (++i >= args.length) {
                        err.println("resolve: --connectors needs a folder");
                        return 2;
                    }
                    connectors = Path.of(args[i]).toAbsolutePath().normalize();
                }
                default -> {
                    err.println("resolve: unknown argument " + args[i] + " (resolve --json [--root DIR | --site DIR] [--connectors DIR])");
                    return 2;
                }
            }
        }
        ConfigResolution r;
        try {
            r = root != null ? ConfigResolution.repository(root) : site != null ? siteOnly(site) : withOverrides();
        } catch (RuntimeException e) {
            err.println("resolve: " + e.getMessage());
            return 1;
        }
        if (connectors == null) {
            String given = System.getProperty("drishti.sources.connectors-dir", System.getenv().getOrDefault("DRISHTI_CONNECTORS_DIR", ""));
            connectors = !given.isBlank() ? com.ash.drishti.api.Roots.anchored(given)
                    : root != null ? root.resolve("config/connectors") : com.ash.drishti.api.Roots.anchored(com.ash.drishti.api.Roots.site("connectors"));
        }
        try {
            ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            out.println(json.writeValueAsString(document(r, connectors)));
            return 0;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            err.println("resolve: " + e.getMessage());
            return 1;
        }
    }

    /**
     * This process's resolution: its modules from the module boot (KERNEL_AND_MODULES.md section 4.2.1, {@code LaunchRoots.bootModules}
     * with no sweep), with the site's overrides of module files laid over by the one rule the server applies at its start (section 18.10,
     * {@code drishti.overrides.dir} or {@code DRISHTI_OVERRIDES_DIR}), in views kept apart from a running server's
     * ({@code <data>/tmp/resolve/overrides/modules}). A module's own folder is never written. A value a parent process published is taken as
     * it is.
     */
    static ConfigResolution withOverrides() {
        java.util.function.Function<String, String> process = k -> {
            String v = System.getProperty(k);
            return v != null ? v : System.getenv(k.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'));
        };
        if (com.ash.drishti.api.resolution.ModuleHomes.of(process).isPresent()) {
            return ConfigResolution.ofProcess();
        }
        Path folder = com.ash.drishti.module.runtime.ModuleBoot.folderOf(process);
        String setting = process.apply(com.ash.drishti.api.resolution.ModuleOverrides.SETTING);
        boolean sources = Boolean.parseBoolean(String.valueOf(process.apply(com.ash.drishti.packs.LaunchRoots.DEV)).strip())
                || com.ash.drishti.module.runtime.ModuleBoot.sourcesAsked(process.apply(com.ash.drishti.module.runtime.ModuleBoot.SOURCES_SETTING));
        String dataSetting = process.apply("drishti.data.dir");
        Path data = Path.of(dataSetting == null || dataSetting.isBlank() ? com.ash.drishti.api.Roots.data() : dataSetting.strip()).toAbsolutePath().normalize();
        java.util.List<Path> dirs = com.ash.drishti.api.resolution.ModuleOverrides.dirs(setting, com.ash.drishti.api.Roots.home());
        com.ash.drishti.packs.LaunchRoots.Booted b = com.ash.drishti.packs.LaunchRoots.bootModules(folder, data, sources, dirs,
                data.resolve("tmp").resolve("resolve").resolve("overrides").resolve("modules"), false);
        Map<String, String> published = new java.util.HashMap<>(b.properties());
        return ConfigResolution.offline(k -> published.containsKey(k) ? published.get(k) : process.apply(k));
    }

    /** A folder of packs read as a site of its own (its packs and group folders, its {@code core} as the kernel's), with no modules. */
    static ConfigResolution siteOnly(Path site) {
        return ConfigResolution.build(new com.ash.drishti.api.resolution.ResolutionSettings(site, site.resolve("no-installed-packs"),
                site.resolve("core"), site.resolve("no-modules"), java.util.List.of(),
                com.ash.drishti.api.resolution.ModuleHomes.none()), id -> false);
    }

    /** The document (above). */
    public static Map<String, Object> document(ConfigResolution r, Path connectorsDir) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", r.version());
        PackResolver.Layout l = r.settings().layout();
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("site", text(l.site()));
        layout.put("kernel", text(l.kernel()));
        layout.put("modules", text(l.modules()));
        layout.put("installed", text(l.installed()));
        layout.put("extra", l.extra().stream().map(ResolveCli::text).toList());
        doc.put("layout", layout);
        Map<String, Object> packs = new TreeMap<>();
        r.packs().packs().forEach((name, p) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dir", text(p.dir()));
            row.put("manifest", text(p.dir().resolve("pack.yaml")));
            row.put("layer", p.layer().name().toLowerCase(java.util.Locale.ROOT));
            row.put("module", p.module());
            row.put("origin", p.origin());
            packs.put(name, row);
        });
        doc.put("packs", packs);
        doc.put("off", new TreeMap<>(r.packs().off()));
        Map<String, Object> modules = new TreeMap<>();
        r.packs().modules().forEach((id, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("home", text(m.home()));
            row.put("folder", text(m.folder()));
            row.put("packs", m.packs());
            row.put("source", m.source());
            row.put("connectors", text(m.connectors()));
            row.put("dataload", text(m.dataload()));
            row.put("requires", m.requires());
            row.put("on", r.active().test(id));
            modules.put(id, row);
        });
        doc.put("modules", modules);
        doc.put("connectorsDir", text(connectorsDir));
        ConnectorFiles files = new ConnectorFiles(connectorsDir);
        List<String> problems = new ArrayList<>(r.problems());
        List<Pack> loaded;
        try {
            loaded = new PackLoader(files).load(r, r.packs().names());
        } catch (RuntimeException e) {
            loaded = List.of();
            problems.add("the packs could not be loaded for their connector templates: " + e.getMessage());
        }
        Map<String, ConnectorLayers.Template> templates = ConnectorLayers.templates(loaded, UnaryOperator.identity());
        Map<String, Object> tdoc = new TreeMap<>();
        templates.forEach((n, t) -> tdoc.put(n, Map.of("owner", t.owner(), "definition", t.definition())));
        doc.put("templates", tdoc);
        Map<String, Object> conns = new TreeMap<>();
        Map<String, Object> unused = new TreeMap<>();
        TreeSet<String> names = new TreeSet<>(templates.keySet());
        names.addAll(files.names());
        for (String n : names) {
            ConnectorLayers.Template t = templates.get(n);
            try {
                String fileText = files.names().contains(n) ? files.text(n) : null;
                ConnectorLayers.Effective e;
                if (fileText == null) {
                    ConnectorFiles.Definition d = files.parse(n, ConnectorLayers.templateText(files, n, t.definition(), ""));
                    Map<String, String> origins = new LinkedHashMap<>();
                    d.settings().keySet().forEach(k -> origins.put(k, ConnectorLayers.TEMPLATE));
                    e = new ConnectorLayers.Effective(d, ConnectorLayers.TEMPLATE, origins, false);
                } else {
                    e = ConnectorLayers.effective(files, n, fileText, t == null ? null : t.definition());
                }
                ConnectorFiles.Definition d = e.definition();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("plugin", d.plugin());
                row.put("enabled", d.enabled());
                row.put("kinds", d.kinds());
                row.put("description", d.description());
                row.put("settings", d.settings());
                row.put("layer", e.layer());
                row.put("origins", e.origins());
                row.put("superseded", e.superseded());
                row.put("template", t == null ? null : t.owner());
                row.put("file", fileText == null ? null : text(connectorsDir.resolve(n + ".yaml")));
                row.put("writes", d.writes());
                conns.put(n, row);
            } catch (ConnectorFiles.InvalidFile | java.io.UncheckedIOException e) {
                unused.put(n, e.getMessage());
            }
        }
        doc.put("connectors", conns);
        doc.put("unused", unused);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (ModuleTemplates.Rule rule : r.rules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", rule.module());
            row.put("file", text(rule.file()));
            row.put("rule", rule.rule());
            rules.add(row);
        }
        doc.put("rules", rules);
        doc.put("problems", problems);
        return doc;
    }

    private static String text(Path p) {
        return p == null ? null : p.toAbsolutePath().normalize().toString();
    }
}
