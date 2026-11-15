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

import com.ash.drishti.api.resolution.ConfigResolution;
import com.ash.drishti.api.resolution.PackResolver;
import com.ash.drishti.api.resolution.ResolutionSettings;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.edition.EditionContext;
import com.ash.drishti.server.engines.EngineCluster;
import com.ash.drishti.server.modules.ModuleConfiguration;
import com.ash.drishti.server.modules.deploy.ModuleNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private final ObjectMapper json = new ObjectMapper();
    private volatile Built bootstrap;
    private final Map<String, BuiltPack> packAnswers = new ConcurrentHashMap<>();

    public ConsoleAnswers(PackRegistry packs, EditionContext edition, ObjectProvider<EngineCluster> engines,
            ObjectProvider<ModuleConfiguration.Started> started, ObjectProvider<ModuleNode> node, Environment env) {
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
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", SCHEMA);
        Map<String, Object> dist = new LinkedHashMap<>();
        Map<String, Object> about = edition.instance().about();       // null: Drishti All
        dist.put("id", about == null ? "all" : String.valueOf(about.get("name")));
        dist.put("about", about);
        dist.put("lenses", edition.lensNames());
        doc.put("distribution", dist);
        doc.put("modules", modules());
        EngineCluster c = engines.getIfAvailable();
        doc.put("engines", c == null ? List.of() : c.about());
        doc.put("commands", commands());
        return doc;
    }

    /** Every module the registry knows with what a distribution takes from it, in id order: its state here and its words, packs and menus. */
    private List<Map<String, Object>> modules() {
        Map<String, com.ash.drishti.packs.distribution.ModuleManifests.Entry> known;
        try {
            known = com.ash.drishti.packs.distribution.Distributions.known(env);
        } catch (RuntimeException e) {                              // a manifest that does not read: the registry's half still answers
            known = Map.of();
        }
        ModuleConfiguration.Started s = started.getIfAvailable();
        Map<String, Boolean> loaded = s == null ? Map.of() : s.loaded();
        Map<String, ModuleNode.Entry> registry = new TreeMap<>();
        ModuleNode n = node.getIfAvailable();
        if (n != null) {
            n.entries().forEach(e -> registry.put(e.id(), e));
        }
        Set<String> ids = new java.util.TreeSet<>();
        ids.addAll(known.keySet());
        ids.addAll(registry.keySet());
        ids.addAll(loaded.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            ModuleNode.Entry e = registry.get(id);
            com.ash.drishti.packs.distribution.ModuleManifests.Entry k = known.get(id);
            m.put("id", id);
            m.put("title", e == null ? id : e.title());
            m.put("version", e == null ? null : e.version());
            m.put("state", e == null ? null : e.state().name().toLowerCase(Locale.ROOT));
            m.put("loaded", Boolean.TRUE.equals(loaded.get(id)));
            m.put("domain", k == null ? null : k.domain());
            m.put("packs", k == null ? List.of() : k.packs());
            m.put("words", k == null ? List.of() : k.words());
            m.put("features", k == null ? List.of() : k.features());
            m.put("menus", k == null ? List.of() : k.menus());
            m.put("requires", k == null ? List.of() : k.requires());
            out.add(m);
        }
        return out;
    }

    /**
     * Every command word of the installation (each pack's {@code code} and mnemonics, of every pack the resolution finds, loaded or not, and
     * of the packs of modules that are off) and what it needs: {@code {pack, module, loaded}}. A word several packs share is loaded while any
     * of them is. The console draws a menu item whose command is a pack's only while it is loaded (the US equities pack listed by its module
     * but not enabled takes its USEQ entry out of the menus, as the server then answers "cannot read command").
     */
    Map<String, Object> commands() {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        ConfigResolution r = packs.resolution();
        Set<String> loaded = new LinkedHashSet<>();
        for (Pack p : packs.packs()) {
            loaded.add(p.name());
            PackResolver.Located at = r == null ? null : r.packs().packs().get(p.name());
            words(out, p.name(), at == null ? null : at.module(), true, p.manifest());
        }
        if (r != null) {
            r.packs().packs().forEach((name, at) -> {
                if (!loaded.contains(name)) {
                    words(out, name, at.module(), false, read(at.dir().resolve("pack.yaml")));
                }
            });
            r.packs().off().forEach((name, module) -> {
                PackResolver.Module m = r.packs().modules().get(module);
                if (!loaded.contains(name) && m != null && m.folder() != null) {
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

    private static Map<String, Object> read(Path file) {
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
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
