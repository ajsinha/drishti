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
package com.ash.drishti.server.console;

import com.ash.tools.codec.Hashes;
import com.ash.drishti.api.resolution.ConfigResolution;
import com.ash.drishti.api.resolution.PackResolver;
import com.ash.drishti.api.resolution.ResolutionSettings;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.edition.EditionContext;
import com.ash.drishti.server.engines.EngineCluster;
import com.ash.drishti.server.modules.ModuleConfiguration;
import com.ash.drishti.server.modules.ModuleNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The answers a console asks instead of reading the server's files (docs/architecture/THIN_CONSOLE_DESIGN.md section 3): the stamps of
 * {@code /public/about}, the installation's answer ({@code GET /api/v1/console/bootstrap}) and a pack's console contributions
 * ({@code GET /api/v1/packs/{pack}/console} and its files). "The server decides, the console draws": each answer is made here from the
 * server's own objects (the pack registry, the resolution, the module registry, the engine catalogue, the distribution), kept whole until
 * one of them changes, and never holds a folder, a connector setting, a secret or anything of a person.
 */
@Component
public class ConsoleAnswers {

    /** The shape of the documents: additive changes only; a change that removes or renames a field raises it. */
    public static final int SCHEMA = 1;

    /** One answer: its body (the digest inside) and the digest (the ETag). */
    public record Answer(Map<String, Object> body, String digest) {
    }

    /** One file a pack gives the console: its path inside the pack and the SHA-256 of its bytes. */
    public record PackFile(String path, String sha, Path file) {
    }

    /** A pack's contributions and the files it allows. */
    public record PackAnswer(Answer answer, Map<String, PackFile> files) {
    }

    private record Built(String key, Answer answer) {
    }

    private record BuiltPack(long registryVersion, PackAnswer answer) {
    }

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)|<img\\b[^>]*\\bsrc=\"([^\"]+)\"");

    private final PackRegistry packs;
    private final EditionContext edition;
    private final ObjectProvider<EngineCluster> engines;
    private final ObjectProvider<ModuleConfiguration.Started> started;
    private final ObjectProvider<ModuleNode> node;
    private final Environment env;
    private final ObjectProvider<com.ash.drishti.rachana.UnderConstruction> underConstruction;
    private final ObjectProvider<com.ash.drishti.engine.shape.ShapeService> shapes;
    private final ObjectMapper json = new ObjectMapper();
    private volatile Built bootstrap;
    private final Map<String, Built> namedAnswers = new ConcurrentHashMap<>();
    private final Map<String, BuiltPack> packAnswers = new ConcurrentHashMap<>();

    public ConsoleAnswers(PackRegistry packs, EditionContext edition, ObjectProvider<EngineCluster> engines,
            ObjectProvider<ModuleConfiguration.Started> started, ObjectProvider<ModuleNode> node, Environment env,
            ObjectProvider<com.ash.drishti.rachana.UnderConstruction> underConstruction,
            ObjectProvider<com.ash.drishti.engine.shape.ShapeService> shapes) {
        this.underConstruction = underConstruction;
        this.shapes = shapes;
        this.packs = packs;
        this.edition = edition;
        this.engines = engines;
        this.started = started;
        this.node = node;
        this.env = env;
    }

    // -- the stamps ---------------------------------------------------------------------------------------------------------------------

    /**
     * {@code stamps} of {@code /public/about}: a short digest per answer the console keeps, so one comparison decides whether to ask again.
     * {@code installation} is the bootstrap's digest; {@code resolution} the resolution's version; {@code modules} the module registry's
     * fingerprint (also {@code manifests} of about); {@code engines} a digest of what runs; {@code packs} the pack registry's version (a pack
     * loaded, unloaded or upgraded: every pack answer is asked again).
     */
    public Map<String, Object> stamps() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installation", bootstrap().digest());
        ConfigResolution r = packs.resolution();
        m.put("resolution", r == null ? null : Long.toHexString(r.version()));
        ModuleNode n = node.getIfAvailable();
        m.put("modules", n == null ? null : n.fingerprint());
        EngineCluster c = engines.getIfAvailable();
        m.put("engines", c == null ? null : digest(text(c.about())));
        m.put("packs", Long.toHexString(packs.version()));
        return m;
    }

    // -- the installation's answer ------------------------------------------------------------------------------------------------------

    /** The installation's answer: built when one of its inputs changed, else the same object (the console's {@code If-None-Match} meets it). */
    public Answer bootstrap() {
        String key = inputs();
        Built b = bootstrap;
        if (b != null && b.key().equals(key)) {
            return b.answer();
        }
        synchronized (this) {
            b = bootstrap;
            if (b == null || !b.key().equals(key)) {
                b = new Built(key, seal(buildBootstrap()));
                bootstrap = b;
            }
            return b.answer();
        }
    }

    /** What the bootstrap is made of, as one string: a change of any input builds it again. Cheap: every part is held in memory. */
    private String inputs() {
        ConfigResolution r = packs.resolution();
        ModuleNode n = node.getIfAvailable();
        EngineCluster c = engines.getIfAvailable();
        ModuleConfiguration.Started s = started.getIfAvailable();
        return (r == null ? "-" : Long.toString(r.version())) + "|" + packs.version() + "|" + (n == null ? "-" : n.fingerprint()) + "|"
                + edition.instance().about() + "|" + edition.lensNames() + "|" + (c == null ? "-" : c.about()) + "|"
                + (s == null ? "-" : new TreeMap<>(s.loaded()));
    }

    private Map<String, Object> buildBootstrap() {
        return buildBootstrap(null);
    }

    /** The installation's answer, its distribution the server's own ({@code named} null) or the one a console names (Q3). */
    private Map<String, Object> buildBootstrap(String named) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", SCHEMA);
        Map<String, Object> dist = new LinkedHashMap<>();
        Map<String, Object> about = edition.instance().about();       // null: Drishti All
        Map<String, com.ash.drishti.packs.distribution.ModuleManifests.Entry> known = known();
        com.ash.drishti.packs.distribution.Distribution d = distribution(named, known);
        dist.putAll(ConsoleDistribution.of(d, lensesDir(), edition.lensNames(),
                com.ash.drishti.packs.edition.EditionEnvironmentPostProcessor.featureMapping(
                        com.ash.drishti.packs.distribution.Distributions.dir(env), "console")));
        dist.put("about", named == null ? about : null);
        dist.put("own", named != null);
        doc.put("distribution", dist);
        doc.put("modules", modules(known));
        EngineCluster c = engines.getIfAvailable();
        doc.put("engines", c == null ? List.of() : c.console());   // the catalogue with what runs: the console keeps no copy (S3)
        doc.put("under-construction", underConstruction());
        doc.put("limits", limits());
        doc.put("commands", commands());
        return doc;
    }

    /**
     * The installation's answer for a distribution a console names itself (THIN_CONSOLE_DESIGN.md Q3: a Finance console over a Drishti All
     * server): compiled here from the server's files, with {@code installation} the server's own digest (the console follows that stamp).
     * Refused ({@link IllegalArgumentException}) for a name that is not a distribution of this installation or lists a module it does not hold.
     */
    public Answer bootstrap(String named) {
        if (named == null || named.isBlank()) {
            return bootstrap();
        }
        String n = named.strip();
        if (n.contains("/") || n.contains("\\") || n.endsWith(".yaml")) {
            throw new IllegalArgumentException("a console names a distribution by name (risk, finance, public-sector, genomics, all), never by a path");
        }
        String key = inputs() + "|" + n;
        Built b = namedAnswers.get(n);
        if (b != null && b.key().equals(key)) {
            return b.answer();
        }
        Map<String, Object> doc = buildBootstrap(n);
        doc.put("installation", bootstrap().digest());
        Built made = new Built(key, seal(doc));
        namedAnswers.put(n, made);
        return made.answer();
    }

    private com.ash.drishti.packs.distribution.Distribution distribution(String named,
            Map<String, com.ash.drishti.packs.distribution.ModuleManifests.Entry> known) {
        java.nio.file.Path dir = com.ash.drishti.packs.distribution.Distributions.dir(env);
        if (named == null) {
            try {
                return com.ash.drishti.packs.distribution.Distributions.of(env);
            } catch (RuntimeException e) {                          // the server started with it: a file changed since says so on Health
                return com.ash.drishti.packs.distribution.Distributions.builtInAll();
            }
        }
        java.nio.file.Path f;
        try {
            f = com.ash.drishti.packs.distribution.Distributions.file(dir, named);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (!Files.isRegularFile(f)) {
            throw new IllegalArgumentException("this installation has no distribution '" + named + "'");
        }
        try {
            return com.ash.drishti.packs.distribution.Distributions.read(f, dir.resolve(com.ash.drishti.packs.distribution.Distributions.KERNEL),
                    com.ash.drishti.packs.distribution.Distributions.homes(env),
                    Path.of(env.getProperty("drishti.packs.dir", com.ash.drishti.api.Roots.config("packs"))));
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * The numbers the console pre-checks with before it sends anything on (THIN_CONSOLE_DESIGN.md 3.2, step S6): the builder's upload
     * limits ({@code drishti.builder.max-samples}, {@code max-file-mb}, {@code max-total-mb}), which the server enforces the same way
     * (413 DRS-5005); the bounds of a request's {@code ctl.} parameters ({@code ControlValues}); FORM's largest row count
     * ({@code FormCompiler.MAX_ROWS}). The console keeps no copy of the numbers.
     */
    private Map<String, Object> limits() {
        com.ash.drishti.engine.shape.ShapeService s = shapes.getIfAvailable();
        return limits(s == null ? null : s.limits());
    }

    /** The limits of the installation's answer for these builder settings ({@code null}: no shape service, no builder limits). */
    static Map<String, Object> limits(com.ash.drishti.engine.shape.BuilderProperties builder) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (builder != null) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("maxSamples", builder.maxSamples());
            b.put("maxFileMb", builder.maxFileMb());
            b.put("maxTotalMb", builder.maxTotalMb());
            out.put("builder", b);
        }
        Map<String, Object> ctl = new LinkedHashMap<>();                     // the ctl.<id>=value parameters a request carries (ControlValues)
        ctl.put("maxControls", com.ash.rachana.control.ControlValues.MAX_CONTROLS);
        ctl.put("maxKey", com.ash.rachana.control.ControlValues.MAX_KEY);
        ctl.put("maxValue", com.ash.rachana.control.ControlValues.MAX_VALUE);
        out.put("controls", ctl);
        out.put("form", Map.of("maxRows", com.ash.drishti.engine.search.form.FormCompiler.MAX_ROWS));   // FORM's row choices stop here
        return out;
    }

    /**
     * The under-construction registry as the console draws it (docs/admin/UNDER_CONSTRUCTION.md; step S3): each item's id, its treatment
     * ({@code hidden} or {@code under-construction}) and the menu items and Help pages it covers. The console reads no registry file.
     */
    private List<Map<String, Object>> underConstruction() {
        com.ash.drishti.rachana.UnderConstruction uc = underConstruction.getIfAvailable();
        List<Map<String, Object>> out = new ArrayList<>();
        if (uc != null) {
            for (com.ash.drishti.rachana.UnderConstruction.Item i : uc.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", i.id());
                m.put("treatment", i.treatment());
                m.put("menus", i.menus() == null ? List.of() : i.menus());
                m.put("help", i.help() == null ? List.of() : i.help());
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Every distribution file of this installation compiled as the console draws it, with the files that do not compile (Admin -> White
     * label: the distributions side by side). For administrators: a problem names the file, never its folder.
     */
    public Map<String, Object> distributions() {
        java.nio.file.Path dir = com.ash.drishti.packs.distribution.Distributions.dir(env);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<java.nio.file.Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<java.nio.file.Path> s = Files.list(dir)) {
                s.filter(f -> f.getFileName().toString().matches("drishti\\.[a-z0-9-]+\\.yaml")).sorted().forEach(files::add);
            } catch (IOException e) {
                problems.add("the distribution files cannot be listed: " + e.getMessage());
            }
        }
        String running = edition.instance().active() ? edition.instance().name() : "drishti.all";
        for (java.nio.file.Path f : files) {
            String name = f.getFileName().toString().replaceAll("^drishti\\.|\\.yaml$", "");
            try {
                com.ash.drishti.packs.distribution.Distribution dist = distribution(name, known());
                Map<String, Object> d = new LinkedHashMap<>(ConsoleDistribution.of(dist, lensesDir(), dist.lenses(), Map.of()));
                d.put("running", running.equals(d.get("id")));
                d.put("start", name);
                rows.add(d);
            } catch (IllegalArgumentException e) {
                problems.add(f.getFileName() + ": " + e.getMessage());
            }
        }
        return Map.of("distributions", rows, "problems", problems);
    }

    /** The folder of the lens files ({@code drishti.lenses-dir}, else {@code config/lenses}). */
    private java.nio.file.Path lensesDir() {
        return com.ash.drishti.api.Roots.anchored(env.getProperty("drishti.lenses-dir", com.ash.drishti.api.Roots.config("lenses")));
    }

    private Map<String, com.ash.drishti.packs.distribution.ModuleManifests.Entry> known() {
        try {
            return com.ash.drishti.packs.distribution.Distributions.known(env);
        } catch (RuntimeException e) {                              // a manifest that does not read: the registry's half still answers
            return Map.of();
        }
    }

    /** Every module the registry knows with what a distribution takes from it, in requirement order: its state here and its words, packs and menus. */
    private List<Map<String, Object>> modules(Map<String, com.ash.drishti.packs.distribution.ModuleManifests.Entry> known) {
        ModuleConfiguration.Started s = started.getIfAvailable();
        Map<String, Boolean> loaded = s == null ? Map.of() : s.loaded();
        Map<String, ModuleNode.Entry> registry = new TreeMap<>();
        ModuleNode n = node.getIfAvailable();
        if (n != null) {
            n.entries().forEach(e -> registry.put(e.id(), e));
        }
        Set<String> ids = new LinkedHashSet<>(known.keySet());       // requirement order: a required module before those that need it
        new java.util.TreeSet<>(registry.keySet()).forEach(ids::add);
        new java.util.TreeSet<>(loaded.keySet()).forEach(ids::add);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            ModuleNode.Entry e = registry.get(id);
            out.add(moduleRow(id, known.get(id), e == null ? null : e.title(), e == null ? null : e.version(),
                    e == null ? null : e.state().name().toLowerCase(Locale.ROOT), Boolean.TRUE.equals(loaded.get(id))));
        }
        return out;
    }

    /**
     * One module of the installation's answer: the registry's title, version and state (null when the registry does not hold it), whether it
     * is loaded here, and what its manifest gives the console (its domain, packs, words, engines, menu ids and menus in the sitemap's form,
     * the Domains label, the modules it requires).
     */
    static Map<String, Object> moduleRow(String id, com.ash.drishti.packs.distribution.ModuleManifests.Entry k, String title, String version,
            String state, boolean loaded) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title != null ? title : k != null && !k.title().isBlank() ? k.title() : id);
        m.put("version", version);
        m.put("state", state);
        m.put("loaded", loaded);
        m.put("domain", k == null ? null : k.domain());
        m.put("packs", k == null ? List.of() : k.packs());
        m.put("words", k == null ? List.of() : k.words());
        m.put("features", k == null ? List.of() : k.features());
        m.put("menus", k == null ? List.of() : k.menus());
        m.put("docs", k == null ? List.of() : k.docs());
        m.put("requires", k == null ? List.of() : k.requires());
        m.put("console-menus", k == null ? List.of() : k.consoleMenus());   // its top menus in the sitemap's form
        m.put("domain-label", k == null ? "" : k.domainLabel());
        return m;
    }

    /**
     * Every command word of the installation (each pack's {@code code} and mnemonics, of every pack the resolution finds, loaded or not, and
     * of the packs of modules that are off) and what it needs: {@code {pack, module, loaded}}. A word several packs share is loaded while any
     * of them is. The console draws a menu item whose command is a pack's only while it is loaded (the US equities pack listed by its module
     * but not enabled takes its USEQ entry out of the menus, as the server then answers "cannot read command").
     */
    Map<String, Object> commands() {
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        for (Pack p : packs.packs()) {
            loaded.put(p.name(), p.manifest());
        }
        return commands(packs.resolution(), loaded);
    }

    /**
     * The command words of a resolution whose loaded packs are {@code loaded} (name to its manifest), as {@link #commands()} answers them:
     * the server's loaded registry, or a resolution's enabled packs read from their folders (the recorded answers of the console's tests).
     */
    static Map<String, Object> commands(ConfigResolution r, Map<String, Map<String, Object>> loaded) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        loaded.forEach((name, manifest) -> {
            PackResolver.Located at = r == null ? null : r.packs().packs().get(name);
            words(out, name, at == null ? null : at.module(), true, manifest);
        });
        if (r != null) {
            r.packs().packs().forEach((name, at) -> {
                if (!loaded.containsKey(name)) {
                    words(out, name, at.module(), false, read(at.dir().resolve("pack.yaml")));
                }
            });
            r.packs().off().forEach((name, module) -> {
                PackResolver.Module m = r.packs().modules().get(module);
                if (!loaded.containsKey(name) && m != null && m.folder() != null) {
                    words(out, name, module, false, read(m.folder().resolve(name).resolve("pack.yaml")));
                }
            });
        }
        return new LinkedHashMap<>(out);
    }

    private static void words(Map<String, Map<String, Object>> out, String pack, String module, boolean loaded, Map<String, Object> manifest) {
        List<String> words = new ArrayList<>();
        if (manifest.get("code") != null && !String.valueOf(manifest.get("code")).isBlank()) {
            words.add(String.valueOf(manifest.get("code")).strip().toUpperCase(Locale.ROOT));
        }
        if (manifest.get("mnemonics") instanceof Map<?, ?> mn) {
            mn.keySet().forEach(k -> words.add(String.valueOf(k).strip().toUpperCase(Locale.ROOT)));
        }
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            Map<String, Object> had = out.get(w);
            if (had == null || !Boolean.TRUE.equals(had.get("loaded")) && loaded) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pack", pack);
                m.put("module", module);
                m.put("loaded", loaded);
                out.put(w, m);
            }
        }
    }

    static Map<String, Object> read(Path file) {
        try {
            return Files.isRegularFile(file) ? ResolutionSettings.yaml(file) : Map.of();
        } catch (RuntimeException e) {                              // a broken pack.yaml: the resolution names it among its problems
            return Map.of();
        }
    }

    // -- a pack's console contributions -------------------------------------------------------------------------------------------------

    /** The loaded pack of that name, or empty. */
    public Optional<Pack> pack(String name) {
        return packs.packs().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /**
     * A pack's console contributions (section 3.4): its workspace starters, its Help guides and contextual help, its facts file and the
     * names of its README's {@code sources}, with every file the console may fetch ({@code files}: the guides and the pictures beside them,
     * each with its SHA-256). Shared by everyone the pack is assigned to; built again when the pack registry changes.
     */
    public PackAnswer packConsole(Pack p) {
        long v = packs.version();
        BuiltPack b = packAnswers.get(p.name());
        if (b != null && b.registryVersion() == v) {
            return b.answer();
        }
        PackAnswer a = buildPack(p);
        packAnswers.put(p.name(), new BuiltPack(v, a));
        return a;
    }

    private PackAnswer buildPack(Pack p) {
        Map<String, Object> console = p.manifest().get("console") instanceof Map<?, ?> c ? stringKeys(c) : Map.of();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", SCHEMA);
        doc.put("pack", p.name());
        doc.put("version", p.version());
        Map<String, Object> workspaces = Map.of();
        Map<String, PackFile> files = new TreeMap<>();
        if (console.get("workspaces") != null) {
            Path f = inside(p.dir(), String.valueOf(console.get("workspaces")));
            if (f != null && Files.isRegularFile(f) && read(f).get("templates") instanceof Map<?, ?> t) {
                workspaces = stringKeys(t);
            }
        }
        doc.put("workspaces", workspaces);
        List<Map<String, Object>> guides = new ArrayList<>();
        Map<String, Object> contextual = Map.of();
        if (console.get("help") != null) {
            Path f = inside(p.dir(), String.valueOf(console.get("help")));
            Map<String, Object> help = f != null && Files.isRegularFile(f) ? read(f) : Map.of();
            if (help.get("guides") instanceof List<?> gs) {
                for (Object g : gs) {
                    if (g instanceof Map<?, ?> gm && gm.get("file") != null && gm.get("slug") != null) {
                        Path file = inside(p.dir(), String.valueOf(gm.get("file")));
                        if (file == null || !Files.isRegularFile(file)) {
                            continue;
                        }
                        guides.add(stringKeys(gm));
                        allow(p.dir(), file, files);
                        for (Path picture : pictures(p.dir(), file)) {
                            allow(p.dir(), picture, files);
                        }
                    }
                }
            }
            if (help.get("contextual") instanceof Map<?, ?> cx) {
                contextual = stringKeys(cx);
            }
        }
        doc.put("help", Map.of("guides", guides, "contextual", contextual));
        Path facts = p.dir().resolve("config").resolve("facts.yaml");
        doc.put("facts", Files.isRegularFile(facts) ? safeRead(facts) : null);
        doc.put("sources", sources(p.dir().resolve("README.md")));
        doc.put("files", files.values().stream().map(f -> Map.of("path", f.path(), "sha", f.sha())).toList());
        return new PackAnswer(seal(doc), Map.copyOf(files));
    }

    /** The facts file as data; a broken file is {@code null} (the console's facts reader names what is wrong in a file it can parse). */
    private static Object safeRead(Path file) {
        try {
            return ResolutionSettings.yaml(file);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The names of a README's {@code sources} (the README standard's front matter): where the pack's data comes from, for DATAMAP. */
    private static List<String> sources(Path readme) {
        if (!Files.isRegularFile(readme)) {
            return List.of();
        }
        try {
            Map<String, Object> meta = com.ash.drishti.server.api.Readme.parse(Files.readString(readme, StandardCharsets.UTF_8)).meta();
            if (meta != null && meta.get("sources") instanceof List<?> l) {
                return l.stream().filter(s -> s instanceof Map<?, ?> m && m.get("name") != null)
                        .map(s -> String.valueOf(((Map<?, ?>) s).get("name"))).toList();
            }
        } catch (IOException | RuntimeException e) {
            // a README that does not read names no sources
        }
        return List.of();
    }

    /** The pictures a guide shows by a relative path ({@code ![x](images/a.png)}, {@code <img src="...">}) that are inside the pack. */
    private static List<Path> pictures(Path packDir, Path guide) {
        List<Path> out = new ArrayList<>();
        String text;
        try {
            text = Files.readString(guide, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        Matcher m = IMAGE.matcher(text);
        while (m.find()) {
            String ref = m.group(1) != null ? m.group(1) : m.group(2);
            if (ref == null || ref.isBlank() || ref.contains(":") || ref.startsWith("/") || ref.startsWith("#")) {
                continue;
            }
            Path base = guide.getParent();
            Path f = base == null ? null : base.resolve(ref.split("[?#]", 2)[0]).normalize();
            if (f != null && f.startsWith(packDir.toAbsolutePath().normalize()) && Files.isRegularFile(f) && !hidden(packDir, f)) {
                out.add(f);
            }
        }
        return out;
    }

    private void allow(Path packDir, Path file, Map<String, PackFile> files) {
        String rel = packDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (files.containsKey(rel)) {
            return;
        }
        try {
            files.put(rel, new PackFile(rel, sha(Files.readAllBytes(file)), file.toAbsolutePath().normalize()));
        } catch (IOException e) {
            // a file that cannot be read is not offered
        }
    }

    /** {@code relative} under the pack's folder, or null when it leaves it or names a hidden part. */
    private static Path inside(Path packDir, String relative) {
        Path base = packDir.toAbsolutePath().normalize();
        Path f = base.resolve(relative).normalize();
        return f.startsWith(base) && !hidden(base, f) ? f : null;
    }

    private static boolean hidden(Path base, Path f) {
        for (Path part : base.toAbsolutePath().normalize().relativize(f.toAbsolutePath().normalize())) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    // -- sealing ------------------------------------------------------------------------------------------------------------------------

    /** The document with its {@code digest} (SHA-256 of its JSON without the digest, the first 16 hex digits): also the ETag. */
    Answer seal(Map<String, Object> doc) {
        String d = digest(text(doc));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("digest", d);
        out.putAll(doc);
        return new Answer(java.util.Collections.unmodifiableMap(out), d);
    }

    /** A person's answer sealed the same way, with the installation's digest in it (it changes when the installation does). */
    public Answer person(Map<String, Object> doc) {
        Map<String, Object> d = new LinkedHashMap<>(doc);
        d.put("installation", bootstrap().digest());
        String stamp = "p-" + digest(text(d));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stamp", stamp);
        out.putAll(d);
        return new Answer(java.util.Collections.unmodifiableMap(out), stamp);
    }

    private String text(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    static String digest(String text) {
        return sha(text.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    static String sha(byte[] bytes) {
        return Hashes.sha256Hex(bytes);
    }

    /** Whether an {@code If-None-Match} header names this digest (quoted or not, weak or not, or {@code *}). */
    public static boolean matches(String ifNoneMatch, String digest) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        return java.util.Arrays.stream(ifNoneMatch.split(",")).map(String::trim).map(t -> t.startsWith("W/") ? t.substring(2) : t)
                .map(t -> t.replace("\"", "")).anyMatch(t -> t.equals("*") || t.equals(digest));
    }

    /** The names, sorted, of the packs a set of rows holds (for a test). */
    static Set<String> names(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> String.valueOf(r.get("name"))).collect(Collectors.toCollection(java.util.TreeSet::new));
    }
}
