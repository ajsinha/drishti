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
package com.ash.drishti.module.tool;

import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.module.runtime.RouteTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The conformance suite of section 8.6: fifteen checks, C1 to C15, run on a packed {@code .dmod} (and, for C10, the project's sources).
 * C1-C10 and C12 read the files; C11 and C13-C15 load the module into the module runtime (load, route, unload). Every failure is a sentence
 * that names the file and says what to do. Signing (and the publisher key's refusal of a module that has not passed) comes in release j.
 */
public final class ModuleConformance {

    /** A check's outcome. */
    public enum Status { PASS, FAIL }

    /** One check: its id (C1-C15), its title, its outcome and the problems in plain words. */
    public record Check(String id, String title, Status status, List<String> problems) {

        /** One line for the build log. */
        public String line() {
            return id + " " + title + ": " + (status == Status.PASS ? "passed" : "FAILED\n    - " + String.join("\n    - ", problems));
        }
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String KERNEL = "com/ash/drishti/";
    private static final String KERNEL_API = "com/ash/drishti/kernel/api/";
    private static final Set<String> TEXT = Set.of("yaml", "yml", "md", "html", "js", "css", "json", "txt", "py", "jsonl", "csv", "properties");
    private static final Pattern BESPOKE = Pattern.compile("<(table|canvas|svg)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_IMPORT = Pattern.compile("(?:import\\s[^'\"]*?from\\s*|import\\s*\\(?\\s*)['\"]([^'\"]+)['\"]");
    private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|\\b(?:rgba?|hsla?)\\s*\\(|!important|"
            + ":\\s*[^;{}]*\\b(?:red|green|blue|black|white|gray|grey|orange|yellow|purple|pink|brown|navy|teal|silver)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>\\s*\\S", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE = Pattern.compile("\\son[a-z]+\\s*=|javascript:|\\beval\\s*\\(|new\\s+Function\\s*\\(|"
            + "(?:src|href)\\s*=\\s*['\"]?https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bAKIA[0-9A-Z]{16}\\b|\\bjdbc:");
    private static final Pattern CONNECTOR_KEY = Pattern.compile(
            "(?:^|[\\s{,])(?:plugin|connector|connectors|bootstrap-servers|password|secret|token|api-key)\\s*:", Pattern.MULTILINE);
    private static final Pattern MD_LINK = Pattern.compile("!?\\[[^\\]]*\\]\\(([^)\\s#]+)(?:#[^)]*)?\\)");
    private static final Set<String> MUTABLE = Set.of("Ljava/util/Map;", "Ljava/util/HashMap;", "Ljava/util/List;", "Ljava/util/ArrayList;",
            "Ljava/util/Set;", "Ljava/util/HashSet;", "Ljava/util/concurrent/ConcurrentHashMap;", "Ljava/util/concurrent/ConcurrentMap;",
            "Ljava/util/LinkedHashMap;", "Ljava/util/TreeMap;", "Ljava/util/Deque;", "Ljava/util/ArrayDeque;", "Ljava/util/Queue;");

    private ModuleConformance() {
    }

    /**
     * Runs C1-C15 on a package. {@code project} is the module project's folder (C10 reads its Java sources; null: only the package's
     * scripts and styles are read); {@code work} an empty folder for the runtime's checks; {@code cycles} the load/unload cycles of C15
     * (50 in the design).
     */
    public static List<Check> run(Path dmod, Path project, Path work, int cycles) {
        Path dir;
        DmodManifest m;
        try {
            dir = work.resolve("unpacked");
            ModuleAssembler.delete(dir);
            Dmod.unpack(dmod, dir);
            m = ModuleAssembler.manifest(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> files = files(dir);
        List<Check> out = new ArrayList<>();
        out.add(check("C1", "No kernel internals", noKernelInternals(dir, m)));
        out.add(check("C2", "No bespoke UI", noBespokeUi(dir, files)));
        out.add(check("C3", "Theme tokens only", themeTokens(dir, files)));
        out.add(check("C4", "drishti_ asset prefix", assetPrefix(files)));
        out.add(check("C5", "CSP-clean", cspClean(dir, files)));
        out.add(check("C6", "Routes namespaced", routesNamespaced(dir, m, files)));
        out.add(check("C7", "Help, guide, tutorial present", docsPresent(dir, m, files)));
        out.add(check("C8", "Data contracts present", contractsPresent(dir, m, files)));
        out.add(check("C9", "No secrets, no connectors", noSecrets(dir, files)));
        out.add(check("C10", "Licence headers", licenceHeaders(dir, files, project)));
        out.addAll(runtimeChecks(dmod, m, work, cycles));
        out.add(11, check("C12", "Two people at once", twoPeople(dir, m)));
        return out;
    }

    /** True when every check passed. */
    public static boolean passed(List<Check> checks) {
        return checks.stream().allMatch(c -> c.status() == Status.PASS);
    }

    private static Check check(String id, String title, List<String> problems) {
        return new Check(id, title, problems.isEmpty() ? Status.PASS : Status.FAIL, List.copyOf(problems));
    }

    // ---- C1 and C12: the classes ----------------------------------------------------------------------------------------------------

    private static List<ClassFiles.Info> classes(Path dir, DmodManifest m) {
        List<ClassFiles.Info> out = new ArrayList<>();
        if (m.java() == null) {
            return out;
        }
        for (String jar : m.java().jars()) {
            try (ZipFile z = new ZipFile(dir.resolve("java").resolve(jar).toFile())) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
                        try (InputStream in = z.getInputStream(e)) {
                            out.add(ClassFiles.read(in.readAllBytes()));
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("java/" + jar + " does not read", e);
            }
        }
        return out;
    }

    private static List<String> noKernelInternals(Path dir, DmodManifest m) {
        List<ClassFiles.Info> classes = classes(dir, m);
        Set<String> own = new TreeSet<>();
        classes.forEach(c -> own.add(pkg(c.name())));
        List<String> out = new ArrayList<>();
        for (ClassFiles.Info c : classes) {
            for (String t : c.types()) {
                if (t.startsWith(KERNEL) && !t.startsWith(KERNEL_API) && !own.contains(pkg(t))) {
                    out.add(c.name().replace('/', '.') + " uses " + t.replace('/', '.') + ", a kernel internal: a module may use only "
                            + "drishti-kernel-api (com.ash.drishti.kernel.api) and its own classes");
                }
            }
        }
        return out;
    }

    private static List<String> twoPeople(Path dir, DmodManifest m) {
        List<String> out = new ArrayList<>();
        for (ClassFiles.Info c : classes(dir, m)) {
            for (ClassFiles.StaticField f : c.statics()) {
                if (!f.isFinal() || MUTABLE.contains(f.descriptor())) {
                    out.add(c.name().replace('/', '.') + "." + f.name() + " is " + (f.isFinal() ? "a static collection" : "a static field that is "
                            + "not final") + ": every person using the module at once would share it; keep a person's data in the request "
                            + "or in a bean the kernel scopes, and make constants final and immutable");
                }
            }
        }
        return out;
    }

    private static String pkg(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i < 0 ? "" : internalName.substring(0, i);
    }

    // ---- C2 to C10: the files --------------------------------------------------------------------------------------------------------

    private static List<String> noBespokeUi(Path dir, List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (!f.startsWith("console/") && !f.startsWith("elements/")) {
                continue;
            }
            String text = text(dir, f);
            if (f.endsWith(".html") && BESPOKE.matcher(text).find()) {
                out.add(f + " draws its own " + group(BESPOKE, text) + ": use the kernel's layouts and the Elements macros (grid, chart) "
                        + "instead of hand-made widgets");
            }
            if (f.endsWith(".js")) {
                Matcher i = JS_IMPORT.matcher(text);
                while (i.find()) {
                    String spec = i.group(1);
                    if (!spec.startsWith("@drishti/elements-") && !spec.startsWith("./") && !spec.startsWith("../")) {
                        out.add(f + " imports " + spec + ": a module's scripts import only the Elements public entry points "
                                + "(@drishti/elements-*) and its own files");
                    }
                }
            }
        }
        return out;
    }

    private static List<String> themeTokens(Path dir, List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (f.endsWith(".css")) {
                String css = text(dir, f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("var\\([^)]*\\)", "var()");
                Matcher c = COLOUR.matcher(css);
                if (c.find()) {
                    out.add(f + " has " + c.group().trim() + ": colours, spacing, fonts and radii come from the theme tokens (var(--...)), "
                            + "and nothing overrides the kernel's classes with !important");
                }
            }
        }
        return out;
    }

    private static List<String> assetPrefix(List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            boolean asset = f.startsWith("console/static/") || f.startsWith("elements/");
            if (!asset) {
                continue;
            }
            String name = f.substring(f.lastIndexOf('/') + 1);
            if (f.contains("/vendor/")) {
                out.add(f + " is a vendored library: a module brings none (the console's vendored set serves every module)");
            } else if (!name.startsWith("drishti_") && !"README.md".equals(name)) {
                out.add(f + " must be named drishti_" + name + " (every static file and Elements file of ours starts with drishti_)");
            }
        }
        return out;
    }

    private static List<String> cspClean(Path dir, List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (f.endsWith(".html") || f.endsWith(".js")) {
                String text = text(dir, f);
                if (f.endsWith(".html") && INLINE_SCRIPT.matcher(text).find()) {
                    out.add(f + " has an inline <script>: put the script in a drishti_ file under console/static/ (the console's CSP "
                            + "refuses inline scripts)");
                }
                Matcher u = UNSAFE.matcher(text);
                if (u.find()) {
                    out.add(f + " has " + u.group().trim() + ": no on*= handlers, javascript: URLs, eval, new Function or remote origins "
                            + "(the console's CSP refuses them)");
                }
            }
        }
        return out;
    }

    private static List<String> routesNamespaced(Path dir, DmodManifest m, List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (f.startsWith("console/") && (f.endsWith(".jar") || f.endsWith(".class"))) {
                out.add(f + ": a module's console part holds no code that registers routes; declare every route in plugin.yaml");
            }
        }
        String plugin = str(m.console() == null ? null : m.console().get("plugin"));
        if (plugin == null) {
            return out;
        }
        Map<String, Object> p = yaml(dir, plugin);
        if (!"2".equals(String.valueOf(p.get("format")))) {
            out.add(plugin + " must be format: 2 (routes declared, under /m/" + m.id() + "/)");
        }
        Set<String> ids = new TreeSet<>();
        if (p.get("routes") instanceof List<?> routes) {
            for (Object o : routes) {
                Map<String, Object> r = map(o);
                String id = str(r.get("id"));
                String path = str(r.get("path"));
                if (id == null || !ids.add(id)) {
                    out.add(plugin + ": every route needs an id of its own (" + id + ")");
                }
                if (path == null || !path.startsWith("/") || path.contains("..") || path.startsWith("/m/")) {
                    out.add(plugin + ": route " + id + " has path " + path + "; give the path inside the module (it is served under /m/"
                            + m.id() + "/)");
                }
                String page = str(r.get("page"));
                if (page != null && !Files.isRegularFile(dir.resolve(Path.of(plugin).getParent() == null ? "" : Path.of(plugin).getParent().toString())
                        .resolve("pages").resolve(page))) {
                    out.add(plugin + ": route " + id + " shows page " + page + ", which is not in console/pages/");
                }
            }
        }
        return out;
    }

    private static List<String> docsPresent(Path dir, DmodManifest m, List<String> files) {
        List<String> out = new ArrayList<>();
        Map<String, Object> docs = map(m.section("docs"));
        String readme = str(docs.getOrDefault("readme", "docs/README.md"));
        if (!files.contains(readme)) {
            out.add(readme + " is missing: every module has a README with front matter (the README standard)");
        } else if (!text(dir, readme).startsWith("---")) {
            out.add(readme + " has no front matter (start it with --- and the module's title, domain and summary)");
        }
        String help = str(docs.get("help"));
        if (help == null || !files.contains(help)) {
            out.add("module.yaml docs.help names no Help file in the package (" + help + "): give the module's Help section");
        }
        for (String kind : List.of("guides", "tutorials")) {
            List<String> named = strings(docs.get(kind));
            if (named.isEmpty()) {
                out.add("module.yaml docs." + kind + " is empty: one guide and one tutorial per feature (the docs rule)");
            }
            for (String f : named) {
                if (!files.contains(f)) {
                    out.add("docs." + kind + " names " + f + ", which is not in the package");
                }
            }
        }
        for (String f : files) {
            if (f.startsWith("docs/") && f.endsWith(".md")) {
                Matcher l = MD_LINK.matcher(text(dir, f));
                while (l.find()) {
                    String target = l.group(1);
                    if (!target.contains("://") && !target.startsWith("mailto:")
                            && !Files.exists(dir.resolve(f).getParent().resolve(target).normalize())) {
                        out.add(f + " links to " + target + ", which is not in the package");
                    }
                }
            }
        }
        return out;
    }

    private static List<String> contractsPresent(Path dir, DmodManifest m, List<String> files) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : m.kinds().entrySet()) {
            for (String kind : e.getValue()) {
                String f = "packs/" + e.getKey() + "/contracts/" + kind + ".yaml";
                String named = "packs/" + e.getKey() + "/contracts/" + kind + ".contract.yaml";   // a pack's own contract file (docs/contracts)
                if (!files.contains(f) && files.contains(named)) {
                    f = named;
                }
                if (!files.contains(f)) {
                    out.add("kind " + kind + " has no contract: write " + f + " (contract-format: 1, a binding-hint, no storage)");
                    continue;
                }
                Map<String, Object> c = yaml(dir, f);
                boolean packContract = kind.equals(str(c.get("kind"))) && c.get("version") != null;   // the pack's contract format, kind and version
                if (!"1".equals(String.valueOf(c.get("contract-format"))) && !packContract) {
                    out.add(f + " must say contract-format: 1 (or be the pack's contract of kind " + kind + " with its version)");
                }
                if (c.get("binding-hint") == null) {
                    out.add(f + " has no binding-hint: (tell the data administrator what usually serves this kind)");
                }
                if (c.containsKey("storage")) {
                    out.add(f + " names storage: a module declares what it needs, the site binds where it lives (config/bindings.yaml)");
                }
            }
        }
        String needs = str(map(m.section("data")).get("needs"));
        if (needs != null && files.contains(needs) && yaml(dir, needs).get("needs") instanceof List<?> list) {
            for (Object o : list) {
                if (str(map(o).get("contract")) == null) {
                    out.add(needs + ": need " + map(o).get("kind") + " names no contract range (e.g. contract: \">=1.0 <2\")");
                }
            }
        }
        return out;
    }

    private static List<String> noSecrets(Path dir, List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (!isText(f) || f.startsWith("META-INF/")) {
                continue;
            }
            String text = text(dir, f);
            Matcher s = SECRET.matcher(text);
            // the connector keys are read in YAML only, and not in module.yaml (its console.plugin names the module's plugin file): as keys of
            // the parsed document, so a sentence that says "the prices connector: off as shipped" is not one
            boolean yaml = !DmodManifest.FILE.equals(f) && (f.endsWith(".yaml") || f.endsWith(".yml"));
            String found = s.find() ? s.group() : yaml ? connectorKey(dir, f, text) : null;
            if (found != null) {
                out.add(f + " has \"" + found.trim() + "\": a module carries no connector, host, user name, password, key or token; the "
                        + "site binds the module's kinds to its own connectors and keeps secrets in the kernel's secret store");
            }
        }
        return out;
    }

    /** The first connector key of a YAML file ({@code connector:}), read as a key of the document; unreadable YAML is read as text. */
    private static String connectorKey(Path dir, String f, String text) {
        Object doc;
        try {
            doc = YAML.readValue(dir.resolve(f).toFile(), Object.class);
        } catch (IOException e) {
            Matcher k = CONNECTOR_KEY.matcher(text);
            return k.find() ? k.group() : null;
        }
        java.util.ArrayDeque<Object> todo = new java.util.ArrayDeque<>();
        if (doc != null) {
            todo.add(doc);
        }
        while (!todo.isEmpty()) {
            Object o = todo.pop();
            if (o instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Matcher k = CONNECTOR_KEY.matcher(" " + e.getKey() + ":");
                    if (k.find()) {
                        return k.group();
                    }
                    if (e.getValue() != null) {
                        todo.add(e.getValue());
                    }
                }
            } else if (o instanceof List<?> l) {
                l.stream().filter(java.util.Objects::nonNull).forEach(todo::add);
            }
        }
        return null;
    }

    private static List<String> licenceHeaders(Path dir, List<String> files, Path project) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (f.endsWith(".js") || f.endsWith(".css") || f.endsWith(".py")) {
                if (!head(text(dir, f)).contains("Copyright")) {
                    out.add(f + " has no licence header: start it with the publisher's copyright comment");
                }
            }
        }
        if (project != null) {
            for (String src : List.of("src/main/java", "src/test/java")) {
                Path root = project.resolve(src);
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> s = Files.walk(root)) {
                    for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                        if (!head(Files.readString(p, StandardCharsets.UTF_8)).contains("Copyright")) {
                            out.add(project.relativize(p) + " has no licence header: start it with the publisher's copyright comment");
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return out;
    }

    // ---- C11, C13, C14, C15: the module in the runtime ------------------------------------------------------------------------------

    private static List<Check> runtimeChecks(Path dmod, DmodManifest m, Path work, int cycles) {
        List<String> answer = new ArrayList<>();
        List<String> off = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        List<String> unloads = new ArrayList<>();
        try {
            // C11 and C13: load, every word answers through a route; switched off, every route answers 404
            Path modules = install(dmod, m, work.resolve("run-off"));
            try (ModuleRuntime rt = runtime(work.resolve("run-off"))) {
                if (load(rt, modules, m, answer)) {
                    Map<String, RouteTable.Route> byWord = new LinkedHashMap<>();
                    rt.routes().snapshot().of(m.id()).forEach(r -> {
                        if (r.function() != null) {
                            byWord.put(r.function().toUpperCase(Locale.ROOT), r);
                        }
                    });
                    for (String w : words(m)) {
                        RouteTable.Route r = byWord.get(w);
                        if (r == null) {
                            answer.add("function " + w + " has no route: give one route in plugin.yaml `function: " + w + "`");
                        } else if (status(rt, r) != 200) {
                            answer.add("function " + w + " answers " + status(rt, r) + " at " + r.path());
                        }
                    }
                    ModuleRuntime.UnloadReport rep = rt.unload(m.id(), ModuleRuntime.Leave.DISABLE, true);
                    off.addAll(rep.failures());
                    for (RouteTable.Route r : rt.routes().snapshot().of(m.id())) {
                        if (status(rt, r) != 404) {
                            off.add("switched off, " + r.path() + " answers " + status(rt, r) + " (it must answer 404)");
                        }
                    }
                    if (rt.loaded(m.id()).isPresent()) {
                        off.add("switched off, the module is still loaded");
                    }
                } else {
                    off.add("the module did not load (see C11)");
                }
            }
            // C14: removed, every surface gone, nothing left in the registry, its saved work marked
            modules = install(dmod, m, work.resolve("run-remove"));
            try (ModuleRuntime rt = runtime(work.resolve("run-remove"))) {
                if (load(rt, modules, m, absent)) {
                    ModuleRuntime.UnloadReport rep = rt.unload(m.id(), ModuleRuntime.Leave.REMOVE, true);
                    absent.addAll(rep.failures());
                    for (RouteTable.Route r : rt.routes().snapshot().of(m.id())) {
                        if (status(rt, r) != 410) {
                            absent.add("removed, " + r.path() + " answers " + status(rt, r) + " (it must answer 410 Gone)");
                        }
                    }
                    if (!rt.contributions().of(m.id()).isEmpty()) {
                        absent.add("removed, these contributions are left: " + rt.contributions().of(m.id()));
                    }
                    if (rt.health(m.id()).map(ModuleHealth::state).orElse(null) != ModuleHealth.State.REMOVED) {
                        absent.add("removed, Health does not say REMOVED");
                    }
                }
            }
            // C15: the class loader goes, cycle after cycle
            for (int i = 1; i <= cycles && unloads.isEmpty(); i++) {
                Path run = work.resolve("run-cycle");
                ModuleAssembler.delete(run);
                modules = install(dmod, m, run);
                try (ModuleRuntime rt = runtime(run)) {
                    if (!load(rt, modules, m, unloads)) {
                        break;
                    }
                    ModuleRuntime.UnloadReport rep = rt.unload(m.id(), ModuleRuntime.Leave.DISABLE, true);
                    if (!Boolean.TRUE.equals(rep.leakFree()) && m.java() != null) {
                        unloads.add("cycle " + i + " of " + cycles + ": the module's class loader was not collected after unload; something "
                                + "outside the module (a thread, a static registry, a ThreadLocal) still holds one of its classes");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Check> out = new ArrayList<>();
        out.add(check("C11", "Functions answer", answer));
        out.add(check("C13", "Off means gone", off));
        out.add(check("C14", "Absent everywhere", absent));
        out.add(check("C15", "Class loader unloads", unloads));
        return out;
    }

    private static Path install(Path dmod, DmodManifest m, Path run) throws IOException {
        ModuleAssembler.delete(run);
        Path modules = run.resolve("modules");
        Path at = modules.resolve(m.id()).resolve(m.version());
        Dmod.unpack(dmod, at);
        Files.copy(dmod, modules.resolve(m.id()).resolve(dmod.getFileName()));
        return modules;
    }

    private static ModuleRuntime runtime(Path run) {
        return new ModuleRuntime(KernelFacts.ofKernel(), null, ModuleConformance.class.getClassLoader(), null, run.resolve("tombstones"));
    }

    private static boolean load(ModuleRuntime rt, Path modules, DmodManifest m, List<String> problems) {
        List<ModuleHealth> rows = rt.start(List.of(modules), Map.of(), id -> true);
        ModuleHealth h = rows.stream().filter(r -> r.id().equals(m.id())).findFirst().orElse(null);
        if (h == null || h.state() != ModuleHealth.State.LOADED) {
            problems.add("the module runtime did not load it: " + (h == null ? "not found" : h.line()));
            return false;
        }
        return true;
    }

    private static int status(ModuleRuntime rt, RouteTable.Route r) {
        String path = r.path().replaceAll("\\{[^}]+}", "x");
        return rt.routes().lookup(path).map(RouteTable.Match::status).orElse(404);
    }

    private static List<String> words(DmodManifest m) {
        List<String> out = new ArrayList<>();
        Object f = m.section("functions");
        if (f instanceof List<?> l) {
            for (Object x : l) {
                String w = x instanceof Map<?, ?> mm ? str(mm.get("word")) : str(x);
                if (w != null) {
                    out.add(w.toUpperCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------------------

    private static List<String> files(Path dir) {
        try {
            return Dmod.files(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isText(String f) {
        int dot = f.lastIndexOf('.');
        return dot >= 0 && TEXT.contains(f.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String text(Path dir, String f) {
        try {
            return Files.readString(dir.resolve(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String head(String text) {
        return text.lines().limit(20).reduce("", (a, b) -> a + b + "\n");
    }

    private static String group(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() + ">" : "";
    }

    private static Map<String, Object> yaml(Path dir, String f) {
        try {
            Object o = YAML.readValue(dir.resolve(f).toFile(), Object.class);
            return map(o);
        } catch (IOException e) {
            throw new IllegalArgumentException(f + " does not read as YAML: " + e.getMessage().lines().findFirst().orElse(""), e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }
}
