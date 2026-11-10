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
package com.ash.drishti.packs.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;

/**
 * Reads distribution files (docs/architecture/DISTRIBUTIONS.md): {@code config/distributions/drishti.<name>.yaml}, chosen by
 * {@code --distribution <name|path>} ({@code distribution}), {@code DRISHTI_DISTRIBUTION} or {@code drishti.distribution}. Nothing named:
 * Drishti All, so behaviour is unchanged. The console reads the same files the same way (drishti-console/core/distribution.py).
 *
 * <p>The modules a file lists are read from their own manifests ({@link ModuleManifests}: {@code modules/<id>/module.yaml}, a module
 * jar's {@code META-INF/drishti/module.yaml}), the kernel's own packs, words and menus from {@code kernel.yaml} beside the files; a
 * distribution comes to a visibility filter in the edition file's shape: the kernel's and its modules' commands (with the modules they
 * require), the words of the modules it leaves out switched off, those modules' engines off, its menus, Help and Home.
 */
public final class Distributions {

    /** The setting that names the distribution (environment {@code DRISHTI_DISTRIBUTION}). */
    public static final String NAME = "drishti.distribution";
    /** The command-line form: {@code --distribution=finance}. */
    public static final String ARG = "distribution";
    /** The folder of the distribution files. */
    public static final String DIR = "drishti.distributions-dir";
    public static final String DEFAULT_DIR = "./config/distributions";
    public static final String DEFAULT = "all";
    /** The kernel's own packs, words and menus, beside the distribution files. */
    public static final String KERNEL = "kernel.yaml";
    /** The folder of the module manifests (DRISHTI_MODULE_MANIFESTS_DIR). */
    public static final String MODULES_DIR = "drishti.module-manifests-dir";
    public static final String DEFAULT_MODULES_DIR = "./modules";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern SHORT = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private Distributions() {
    }

    /** The name or path the environment selects ({@code --distribution}, then {@code drishti.distribution}); blank: {@value #DEFAULT}. */
    public static String selected(Environment env) {
        String v = env.getProperty(ARG, "");
        if (v.isBlank()) {
            v = env.getProperty(NAME, "");
        }
        return v.isBlank() ? DEFAULT : v.strip();
    }

    /** True when the environment names a distribution itself (not the default). */
    public static boolean named(Environment env) {
        return !env.getProperty(ARG, "").isBlank() || !env.getProperty(NAME, "").isBlank();
    }

    /** The folder of the distribution files. */
    public static Path dir(Environment env) {
        return Path.of(env.getProperty(DIR, DEFAULT_DIR));
    }

    /**
     * The file a name or path stands for: {@code finance} or {@code drishti.finance} is {@code <dir>/drishti.finance.yaml}; anything with a
     * slash or ending {@code .yaml} is a path (a site's own distribution).
     */
    public static Path file(Path dir, String nameOrPath) {
        String n = nameOrPath == null || nameOrPath.isBlank() ? DEFAULT : nameOrPath.strip();
        if (n.contains("/") || n.contains("\\") || n.endsWith(".yaml") || n.endsWith(".yml")) {
            return Path.of(n);
        }
        String shortName = n.startsWith("drishti.") ? n.substring("drishti.".length()) : n;
        if (!SHORT.matcher(shortName).matches()) {
            throw new IllegalStateException("distribution '" + n + "' is not a distribution name (risk, finance, genomics, all) or a file path");
        }
        return dir.resolve("drishti." + shortName + ".yaml");
    }

    /** The distribution the environment selects; a named one whose file is missing stops the start with a plain message. */
    public static Distribution of(Environment env) {
        Path dir = dir(env);
        Path f = file(dir, selected(env));
        if (!Files.isRegularFile(f) && !named(env)) {
            return builtInAll();                                       // a server with no config/distributions folder: today's product
        }
        String packsDir = env.getProperty("drishti.packs.dir", "./config/packs");
        return read(f, dir.resolve(KERNEL), Path.of(env.getProperty(MODULES_DIR, DEFAULT_MODULES_DIR)), Path.of(packsDir));
    }

    /** Drishti All with no file: every module, no filter, the product's own brand. */
    public static Distribution builtInAll() {
        ObjectNode spec = YAML.createObjectNode();
        spec.put("distribution", "drishti.all");
        return new Distribution("drishti.all", "", null, true, List.of(), Map.of(), List.of(), Set.of(), Set.of(), Set.of(), List.of(),
                Map.of(), spec, null);
    }

    /** Reads a distribution file and compiles it against the kernel's file, the module manifests and the packs folder. */
    public static Distribution read(Path file, Path kernelFile, Path modulesDir, Path packsDir) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no distribution file " + file.toAbsolutePath().normalize() + " (set " + DIR
                    + " / DRISHTI_DISTRIBUTIONS_DIR to the folder of the distribution files, or name one of: risk, finance, genomics, all)");
        }
        JsonNode spec = tree(file);
        JsonNode kernel = Files.isRegularFile(kernelFile) ? tree(kernelFile).path("kernel") : YAML.createObjectNode();
        Map<String, ModuleManifests.Entry> known = ModuleManifests.read(modulesDir);
        String id = spec.path("distribution").asText("").strip();
        if (id.isEmpty()) {
            throw new IllegalStateException("distribution file " + file + " has no 'distribution:' id (drishti.<name>)");
        }
        Map<String, String> brand = new LinkedHashMap<>();
        spec.path("brand").fields().forEachRemaining(e -> {
            if (e.getValue().isValueNode() && !e.getValue().asText().isBlank()) {
                brand.put(e.getKey(), e.getValue().asText().strip());
            }
        });
        String title = brand.getOrDefault("product", id);
        List<String> lenses = texts(spec.path("lenses").path("offered"));
        boolean all = "all".equals(spec.path("modules").asText(""));
        if (all) {
            return new Distribution(id, title, file, true, List.of(), Map.of(), List.of(), Set.of(), Set.of(), Set.of(), lenses, brand, spec, null);
        }
        List<String> listed = new ArrayList<>();
        for (JsonNode m : spec.path("modules")) {
            String mid = (m.isObject() ? m.path("id") : m).asText("").strip();
            if (!mid.isEmpty() && (!m.isObject() || m.path("enabled").asBoolean(true))) {
                listed.add(mid);
                if (!known.containsKey(mid) && m.path("required").asBoolean(true)) {
                    throw new IllegalStateException("distribution " + id + " requires the module '" + mid + "', which has no manifest under "
                            + modulesDir.toAbsolutePath().normalize() + " (modules/<id>/module.yaml)");
                }
            }
        }
        List<String> modules = new ArrayList<>(ModuleManifests.withRequired(listed, known));   // a listed module brings the ones it requires
        Set<String> packs = new LinkedHashSet<>(texts(kernel.path("packs")));
        Map<String, String> domains = new LinkedHashMap<>();
        Set<String> menus = new LinkedHashSet<>(texts(kernel.path("menus")));
        Set<String> wordsOff = new LinkedHashSet<>();
        Set<String> featuresOff = new LinkedHashSet<>();
        Set<String> wordsOn = new LinkedHashSet<>(texts(kernel.path("words")));
        for (ModuleManifests.Entry mod : known.values()) {                 // requirement order: market-data before the packs that extend it
            if (modules.contains(mod.id())) {
                packs.addAll(mod.packs());
                domains.put(mod.id(), mod.domain());
                menus.addAll(mod.menus());
                wordsOn.addAll(mod.words());
            }
        }
        for (ModuleManifests.Entry mod : known.values()) {
            if (!modules.contains(mod.id())) {
                mod.words().stream().filter(w -> !wordsOn.contains(w)).forEach(wordsOff::add);
                featuresOff.addAll(mod.features());
            }
        }
        Set<String> commands = commands(packsDir, packs);
        ObjectNode ed = YAML.createObjectNode();
        ed.put("edition", id);
        ed.put("title", title);
        ed.put("distribution", true);
        ArrayNode pe = ed.putObject("packs").putArray("enabled");
        packs.forEach(pe::add);
        ObjectNode cmd = ed.putObject("commands");
        ArrayNode allow = cmd.putArray("allow");
        commands.forEach(allow::add);
        ObjectNode words = cmd.putObject("words");
        wordsOff.forEach(w -> words.put(w, "off"));
        spec.path("commands").path("words").fields().forEachRemaining(e -> words.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue().asText()));
        ObjectNode features = ed.putObject("features");
        featuresOff.forEach(f -> features.put(f, "off"));
        spec.path("features").fields().forEachRemaining(e -> features.put(e.getKey(), e.getValue().asText()));
        ObjectNode menu = ed.putObject("menus");
        List<String> top = texts(spec.path("menus").path("top"));
        ArrayNode topNode = menu.putArray("top");
        (top.isEmpty() ? new ArrayList<>(menus) : top.stream().filter(menus::contains).toList()).forEach(topNode::add);
        if (spec.path("menus").has("placeholder")) {
            menu.put("placeholder", spec.path("menus").path("placeholder").asText());
        }
        if (spec.path("home").isObject()) {
            ed.set("home", spec.path("home").deepCopy());
        }
        ObjectNode help = ed.putObject("help");
        ArrayNode knowledge = help.putArray("knowledge");
        packs.forEach(knowledge::add);
        ArrayNode dom = help.putArray("domains");
        modules.stream().filter(known::containsKey).flatMap(m -> known.get(m).docs().stream()).distinct().forEach(dom::add);   // the Help each owns
        if (spec.path("docs").has("title")) {
            help.put("title", spec.path("docs").path("title").asText());
        }
        if (spec.path("docs").path("categories").isArray()) {
            help.set("categories", spec.path("docs").path("categories").deepCopy());
        }
        ObjectNode branding = ed.putObject("branding");
        for (String k : List.of("product", "tagline")) {
            if (brand.containsKey(k)) {
                branding.put(k, brand.get(k));
            }
        }
        return new Distribution(id, title, file, false, List.copyOf(modules), Map.copyOf(domains), List.copyOf(packs), commands,
                Set.copyOf(wordsOff), Set.copyOf(featuresOff), lenses, brand, spec, ed);
    }

    /** Every command of the packs: each pack's mnemonics and its code ({@code MKT}), from its {@code pack.yaml} under the packs folder. */
    static Set<String> commands(Path packsDir, Set<String> packs) {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isDirectory(packsDir)) {
            return out;
        }
        Map<String, Path> found = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(packsDir, 3)) {
            s.filter(p -> p.getFileName().toString().equals("pack.yaml")).forEach(p -> found.putIfAbsent(p.getParent().getFileName().toString(), p));
        } catch (IOException e) {
            return out;
        }
        for (String p : packs) {
            Path f = found.get(p);
            if (f == null) {
                continue;
            }
            JsonNode n = tree(f);
            if (!n.path("code").asText("").isBlank()) {
                out.add(n.path("code").asText().strip().toUpperCase(Locale.ROOT));
            }
            n.path("mnemonics").fieldNames().forEachRemaining(c -> out.add(c.strip().toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> {
                if (!x.asText("").isBlank()) {
                    out.add(x.asText().strip());
                }
            });
        }
        return out;
    }

    private static JsonNode tree(Path file) {
        try {
            JsonNode n = YAML.readTree(file.toFile());
            if (n == null || !n.isObject()) {
                throw new IllegalStateException("distribution file " + file + " is not a YAML mapping");
            }
            return n;
        } catch (IOException e) {
            throw new IllegalStateException("distribution file " + file + " cannot be read: " + e.getMessage(), e);
        }
    }
}
