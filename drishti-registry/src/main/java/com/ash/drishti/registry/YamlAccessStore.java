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
package com.ash.drishti.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The access model in YAML files (ACCESS_MODEL.md section 4):
 *
 * <ul>
 *   <li>each module's manifest, shipped in its jar as {@value #MANIFEST} (found on the class path: installed exactly when present);</li>
 *   <li>the lenses the jars ship, {@value #LENSES} (each module ships the lenses that compose it);</li>
 *   <li>the site's {@code config/modules/<id>.yaml}: the administrator's switch ({@code enabled}), or the whole manifest of a content-only
 *       module ({@code code: false});</li>
 *   <li>the site's {@code config/access/licence.yaml}, {@code lenses.yaml}, {@code roles.yaml} and {@code assignments.yaml}: none needed
 *       (no licence file is the development licence; no roles: every offered lens open to everyone).</li>
 * </ul>
 *
 * A file that cannot be read is skipped with a warning (the model's {@code warnings}), never fatal: the server starts on the rest.
 */
public final class YamlAccessStore implements AccessStore {

    /** The class-path name of a module's manifest, the same in every module's jar. */
    public static final String MANIFEST = "META-INF/drishti/module.yaml";
    /** The class-path name of the lenses a jar ships. */
    public static final String LENSES = "META-INF/drishti/lenses.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path modulesDir;
    private final Path accessDir;
    private final ClassLoader loader;
    private final Path store;

    /**
     * @param modulesDir the site's module files ({@code config/modules})
     * @param accessDir  the site's access files ({@code config/access})
     * @param loader     where the shipped manifests and lenses are looked for
     */
    public YamlAccessStore(Path modulesDir, Path accessDir, ClassLoader loader) {
        this(modulesDir, accessDir, loader, null);
    }

    /**
     * @param store the module store ({@code <data folder>/modules}): the {@code .dmod} modules installed there, and the distribution's own
     *              ({@code drishti/modules/*.dmod} beside {@code first-party.yaml} in the server's jar) unless an administrator removed
     *              them, are modules of this server like the manifests on the class path; null for none
     */
    public YamlAccessStore(Path modulesDir, Path accessDir, ClassLoader loader, Path store) {
        this.store = store;
        this.modulesDir = modulesDir;
        this.accessDir = accessDir;
        this.loader = loader == null ? YamlAccessStore.class.getClassLoader() : loader;
    }

    @Override
    public AccessModel read() {
        List<String> warnings = new ArrayList<>();
        Map<String, ModuleManifest> modules = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : shipped(MANIFEST, warnings).entrySet()) {
            // one module ({module: {...}}) or several ({modules: [...]}: a jar that ships content modules beside its code)
            List<Map<String, Object>> each = new ArrayList<>();
            if (e.getValue().get("modules") instanceof List<?> l) {
                l.stream().filter(Map.class::isInstance).forEach(x -> each.add(cast((Map<?, ?>) x)));
            } else {
                each.add(e.getValue());
            }
            for (Map<String, Object> one : each) {
                try {
                    ModuleManifest m = ModuleManifest.of(one, true, "the jar " + e.getKey());
                    if (ModuleManifest.CORE.equals(m.id())) {
                        warnings.add(e.getKey() + ": 'core' is the core's id, not a module's; skipped");
                    } else if (modules.putIfAbsent(m.id(), m) != null) {
                        warnings.add(e.getKey() + ": a second manifest of the module '" + m.id() + "'; the first is kept");
                    }
                } catch (RuntimeException ex) {
                    warnings.add(e.getKey() + ": " + ex.getMessage());
                }
            }
        }
        packaged(modules, warnings);
        Map<String, Boolean> switches = new LinkedHashMap<>();
        for (Path f : yamlFiles(modulesDir)) {
            String id = ModuleManifest.norm(f.getFileName().toString().replaceFirst("\\.ya?ml$", ""));
            try {
                Map<String, Object> m = readMap(f);
                Boolean on = ModuleManifest.bool(m.get("enabled"));
                if (on != null) {
                    switches.put(id, on);
                }
                if (!modules.containsKey(id) && !ModuleManifest.CORE.equals(id) && (m.containsKey("id") || m.containsKey("module"))) {
                    ModuleManifest declared = ModuleManifest.of(m, false, "config/modules/" + f.getFileName());
                    // a content-only module is present by its file; a code module named here without its jar is not installed
                    modules.put(declared.id(), ModuleManifest.of(m, !declared.code(), "config/modules/" + f.getFileName()));
                }
            } catch (RuntimeException ex) {
                warnings.add("config/modules/" + f.getFileName() + ": " + ex.getMessage());
            }
        }
        Licence licence = licence(warnings);
        Map<String, Lens> lenses = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : shipped(LENSES, warnings).entrySet()) {
            for (Lens l : lenses(e.getValue(), "the jar " + e.getKey(), warnings)) {
                lenses.putIfAbsent(l.id(), l);
            }
        }
        Path siteLenses = accessDir.resolve("lenses.yaml");
        if (Files.isRegularFile(siteLenses)) {
            for (Lens l : lenses(safeRead(siteLenses, warnings), "config/access/lenses.yaml", warnings)) {
                lenses.put(l.id(), l);
            }
        }
        return new AccessModel(List.copyOf(modules.values()), switches, licence, List.copyOf(lenses.values()), roles(warnings), assignments(warnings),
                warnings);
    }

    @Override
    public void setEnabled(String module, boolean on) {
        String id = ModuleManifest.norm(module);
        if (ModuleManifest.CORE.equals(id)) {
            throw new IllegalArgumentException("the core is always on");
        }
        if (read().modules().stream().noneMatch(m -> m.id().equals(id))) {
            throw new IllegalArgumentException("no module '" + id + "' here");
        }
        Path f = modulesDir.resolve(id + ".yaml");
        try {
            Files.createDirectories(modulesDir);
            List<String> lines = Files.isRegularFile(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : new ArrayList<>(List.of(
                    "# The module '" + id + "' at this site (docs/architecture/ACCESS_MODEL.md): the administrator's switch. POST /api/v1/admin/modules",
                    "# writes it; a change takes effect when the server restarts (a module's beans, engines, jobs and API are loaded at start)."));
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).matches("^enabled\\s*:.*")) {
                    lines.set(i, "enabled: " + on);
                    replaced = true;
                }
            }
            if (!replaced) {
                lines.add("enabled: " + on);
            }
            Path next = f.resolveSibling(f.getFileName() + ".next");
            Files.write(next, lines, StandardCharsets.UTF_8);
            Files.move(next, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + f, e);
        }
    }

    @Override
    public long stamp() {
        long h = 17;
        for (Path dir : List.of(modulesDir, accessDir)) {
            for (Path f : yamlFiles(dir)) {
                try {
                    h = 31 * h + f.getFileName().toString().hashCode();
                    h = 31 * h + Files.getLastModifiedTime(f).toMillis();
                    h = 31 * h + Files.size(f);
                } catch (IOException e) {
                    h = 31 * h + 1;
                }
            }
        }
        return h;
    }

    @Override
    public String describe() {
        return "YAML: the modules' jars, " + modulesDir + " and " + accessDir;
    }

    private Licence licence(List<String> warnings) {
        Path f = accessDir.resolve("licence.yaml");
        if (!Files.isRegularFile(f)) {
            return Licence.developmentLicence();
        }
        Map<String, Object> raw = safeRead(f, warnings);
        Map<String, Object> m = raw.get("licence") instanceof Map<?, ?> inner ? cast(inner) : raw;
        if (m.isEmpty()) {
            warnings.add("config/access/licence.yaml: empty or unreadable; no module is licensed until it is fixed");
            return new Licence("", java.util.Set.of(), Map.of(), null, null, false, false, "config/access/licence.yaml (unreadable)");
        }
        try {
            return new Licence(ModuleManifest.str(m.get("customer")), new LinkedHashSet<>(ModuleManifest.list(m.get("modules"))),
                    m.get("limits") instanceof Map<?, ?> l ? cast(l) : Map.of(), date(m.get("valid-from")), date(m.get("valid-to")), false, false,
                    "config/access/licence.yaml (unsigned)");
        } catch (RuntimeException e) {
            warnings.add("config/access/licence.yaml: " + e.getMessage() + "; no module is licensed until it is fixed");
            return new Licence("", java.util.Set.of(), Map.of(), null, null, false, false, "config/access/licence.yaml (unreadable)");
        }
    }

    private static List<Lens> lenses(Map<String, Object> raw, String source, List<String> warnings) {
        List<Lens> out = new ArrayList<>();
        if (raw.get("lenses") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m0) {
                    Map<String, Object> m = cast(m0);
                    if (m.get("id") == null) {
                        warnings.add(source + ": a lens without an id; skipped");
                        continue;
                    }
                    out.add(new Lens(String.valueOf(m.get("id")), ModuleManifest.str(m.get("title")), ModuleManifest.list(m.get("modules")),
                            ModuleManifest.list(m.get("functions")), ModuleManifest.str(m.get("home")), ModuleManifest.list(m.get("menus")), ModuleManifest.list(m.get("help")), cards(m.get("cards")),
                            ModuleManifest.list(m.get("chips")), source));
                }
            }
        }
        return out;
    }

    /** A lens's Home cards: a list of {title, cmd, icon}, each value a string; anything else is left out. */
    private static List<Map<String, String>> cards(Object o) {
        List<Map<String, String>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object c : l) {
                if (c instanceof Map<?, ?> m && m.get("cmd") != null) {
                    Map<String, String> card = new java.util.LinkedHashMap<>();
                    m.forEach((k, v) -> { if (k != null && v != null) card.put(String.valueOf(k), String.valueOf(v)); });
                    out.add(card);
                }
            }
        }
        return out;
    }

    private List<Role> roles(List<String> warnings) {
        Path f = accessDir.resolve("roles.yaml");
        List<Role> out = new ArrayList<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        if (safeRead(f, warnings).get("roles") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m0) {
                    Map<String, Object> m = cast(m0);
                    Map<String, List<String>> actions = new LinkedHashMap<>();
                    if (m.get("actions") instanceof Map<?, ?> a) {
                        a.forEach((k, v) -> actions.put(ModuleManifest.norm(String.valueOf(k)), ModuleManifest.list(v)));
                    }
                    out.add(new Role(ModuleManifest.str(m.get("id")), ModuleManifest.list(m.get("lenses")), actions));
                }
            }
        }
        return out;
    }

    private List<Assignment> assignments(List<String> warnings) {
        Path f = accessDir.resolve("assignments.yaml");
        List<Assignment> out = new ArrayList<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        if (safeRead(f, warnings).get("assignments") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m0) {
                    Map<String, Object> m = cast(m0);
                    Map<String, String> attrs = new LinkedHashMap<>();
                    if (m.get("attributes") instanceof Map<?, ?> a) {
                        a.forEach((k, v) -> attrs.put(String.valueOf(k), String.valueOf(v)));
                    }
                    out.add(new Assignment(ModuleManifest.str(m.get("subject")), ModuleManifest.list(m.get("roles")), attrs));
                }
            }
        }
        return out;
    }

    /**
     * The modules that come as packages (KERNEL_AND_MODULES.md section 3.3): the distribution's first-party ones (the ids of its
     * {@code first-party.yaml}, each read from its {@code drishti/modules/<id>-<version>.dmod}), installed unless the module store says an
     * administrator removed or switched it off; then every other active module of the store ({@code installed/active.yaml}).
     */
    @SuppressWarnings("unchecked")
    private void packaged(Map<String, ModuleManifest> modules, List<String> warnings) {
        Map<String, String> states = new LinkedHashMap<>();
        Path registry = store == null ? null : store.resolve("registry.json");
        if (registry != null && Files.isRegularFile(registry)) {
            try {
                for (Object o : new ObjectMapper().readValue(registry.toFile(), List.class)) {
                    if (o instanceof Map<?, ?> e && e.get("id") != null) {
                        states.put(String.valueOf(e.get("id")), String.valueOf(e.get("state")));
                    }
                }
            } catch (IOException | RuntimeException e) {
                warnings.add("the module store's registry.json: " + e.getMessage());
            }
        }
        try (InputStream in = loader.getResourceAsStream("META-INF/drishti/first-party.yaml")) {
            Map<String, Object> fp = in == null ? Map.of() : YAML.readValue(in, Map.class);
            if (fp != null && fp.get("modules") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> e && e.get("id") != null && !modules.containsKey(String.valueOf(e.get("id")))) {
                        String id = String.valueOf(e.get("id"));
                        String state = states.getOrDefault(id, "ACTIVE");
                        Map<String, Object> m = shippedManifest("drishti/modules/" + id + "-" + e.get("version") + ".dmod");
                        if (m != null) {
                            modules.put(id, ModuleManifest.ofDmod(m, "ACTIVE".equals(state), "the distribution's " + id + ".dmod"));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            warnings.add("first-party.yaml: " + e.getMessage());
        }
        if (store == null) {
            return;
        }
        Path active = store.resolve("installed").resolve("active.yaml");
        if (!Files.isRegularFile(active)) {
            return;
        }
        for (Map.Entry<String, Object> e : safeRead(active, warnings).entrySet()) {
            String id = e.getKey();
            Path manifest = store.resolve("installed").resolve(id).resolve(String.valueOf(e.getValue())).resolve("module.yaml");
            if (modules.containsKey(id) || !Files.isRegularFile(manifest) || !"ACTIVE".equals(states.getOrDefault(id, "ACTIVE"))) {
                continue;
            }
            try {
                modules.put(id, ModuleManifest.ofDmod(readMap(manifest), true, "the module store's " + id));
            } catch (RuntimeException ex) {
                warnings.add("the module store's " + id + ": " + ex.getMessage());
            }
        }
    }

    /** The manifest inside a package on the class path, or null when the package is not there. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> shippedManifest(String resource) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(in);
            for (java.util.zip.ZipEntry en; (en = z.getNextEntry()) != null; ) {
                if (en.getName().equals("module.yaml")) {
                    return YAML.readValue(z.readAllBytes(), Map.class);
                }
            }
            return null;
        }
    }

    /** Every class-path resource of the name, by where it came from (the jar's file name), in class-path order. */
    private Map<String, Map<String, Object>> shipped(String name, List<String> warnings) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try {
            for (URL u : Collections.list(loader.getResources(name))) {
                String where = where(u);
                try (InputStream in = u.openStream()) {
                    Map<String, Object> m = YAML.readValue(in, YAML.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                    out.put(out.containsKey(where) ? where + " (" + out.size() + ")" : where, m == null ? Map.of() : m);
                } catch (IOException | RuntimeException e) {
                    warnings.add(where + " " + name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            warnings.add("the class path's " + name + ": " + e.getMessage());
        }
        return out;
    }

    private static String where(URL u) {
        String s = u.toString();
        int bang = s.indexOf("!/");
        String jar = bang > 0 ? s.substring(0, bang) : s;
        int slash = jar.lastIndexOf('/', jar.endsWith("/") ? jar.length() - 2 : jar.length());
        if (bang < 0) {
            // an exploded class folder (a build's target/classes): name the module folder
            int meta = s.indexOf("/META-INF/");
            String folder = meta > 0 ? s.substring(0, meta) : s;
            String[] parts = folder.split("/");
            return parts.length >= 3 ? parts[parts.length - 3] : folder;
        }
        return slash >= 0 ? jar.substring(slash + 1) : jar;
    }

    private static List<Path> yamlFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().matches("[^.].*\\.ya?ml")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Map<String, Object> safeRead(Path f, List<String> warnings) {
        try {
            return readMap(f);
        } catch (RuntimeException e) {
            warnings.add(f.getFileName() + ": " + e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Object> readMap(Path f) {
        try {
            Map<String, Object> m = YAML.readValue(f.toFile(), YAML.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            return m == null ? Map.of() : m;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read: " + e.getMessage(), e);
        }
    }

    private static LocalDate date(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : LocalDate.parse(String.valueOf(o).strip());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
