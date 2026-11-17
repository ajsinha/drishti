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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guard G11 (the child-context design, docs/architecture/KERNEL_AND_MODULES.md sections 4.4 and 4.5): every module's REST controller is
 * mounted in its own namespace. Each module gets a child Spring context and the kernel mounts its controllers; nothing else keeps two
 * modules (or a module and the kernel) from answering the same path. So, reading the module sources: a module without {@code api.prefix}
 * has no controller; a controller's every path is under {@code /api/v1/<prefix>} (or under a path the module's {@code api.paths} declares),
 * and a {@code @ModuleApi("x")} names the module's own prefix.
 */
class ModuleControllerPathsGuardTest {

    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Request|Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:(?:value|path)\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"");
    private static final Pattern MODULE_API = Pattern.compile("^@ModuleApi\\(\\s*\"([^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern CLASS_DECLARATION = Pattern.compile("^(?:public\\s+)?(?:final\\s+|abstract\\s+)*class\\s", Pattern.MULTILINE);
    private static final Pattern CONTROLLER = Pattern.compile("^@(?:Rest)?Controller\\b", Pattern.MULTILINE);

    @Test
    void everyControllerOfAModuleIsInItsOwnNamespace() throws IOException {
        Path modules = RepositoryFiles.root().resolve("modules");
        List<String> wrong = new ArrayList<>();
        int controllers = 0;
        try (var ms = Files.list(modules)) {
            for (Path m : ms.filter(d -> Files.isRegularFile(d.resolve("module.yaml"))).sorted().toList()) {
                Map<?, ?> yaml = new ObjectMapper(new YAMLFactory()).readValue(m.resolve("module.yaml").toFile(), Map.class);
                Object api = yaml.get("api");
                String prefix = api instanceof Map<?, ?> a && a.get("prefix") != null ? String.valueOf(a.get("prefix")) : null;
                List<String> declared = new ArrayList<>();
                if (api instanceof Map<?, ?> a && a.get("paths") instanceof List<?> l) {
                    l.forEach(p -> declared.add(String.valueOf(p)));
                }
                controllers += check(m, prefix, declared, wrong);
            }
        }
        assertThat(wrong).as("controllers outside their module's /api/v1/<prefix> (or its declared api.paths)").isEmpty();
        assertThat(controllers).as("the guard reads the modules' controllers").isGreaterThan(10);
    }

    private static int check(Path module, String prefix, List<String> declared, List<String> wrong) throws IOException {
        int n = 0;
        List<Path> files;
        try (var s = Files.walk(module)) {
            files = s.filter(f -> f.toString().endsWith(".java") && f.toString().contains("/src/main/java/")).sorted().toList();
        }
        for (Path f : files) {
            String text = Files.readString(f);
            Matcher api = MODULE_API.matcher(text);
            boolean annotated = api.find();
            if (!annotated && !CONTROLLER.matcher(text).find()) {
                continue;
            }
            n++;
            String where = module.getFileName() + "/" + f.getFileName();
            if (prefix == null) {
                wrong.add(where + ": the module declares no api.prefix, so it may have no controller");
                continue;
            }
            if (annotated && !api.group(1).equals(prefix)) {
                wrong.add(where + ": @ModuleApi(\"" + api.group(1) + "\") is not the module's prefix \"" + prefix + "\"");
            }
            Matcher decl = CLASS_DECLARATION.matcher(text);
            int classAt = decl.find() ? decl.start() : text.length();
            Matcher m = MAPPING.matcher(text);
            boolean classLevel = false;
            while (m.find()) {
                String path = m.group(1);
                boolean onClass = m.start() < classAt;
                classLevel |= onClass;
                boolean inside = under(path, "/api/v1/" + prefix) || declared.stream().anyMatch(d -> under(path, d));
                // a method's path is relative to its class's namespace (a class-level mapping, checked itself, or @ModuleApi's prefix)
                boolean relative = (annotated || (!onClass && classLevel)) && !path.startsWith("/api/");
                if (!inside && !relative) {
                    wrong.add(where + ": " + path + " is not under /api/v1/" + prefix);
                }
            }
        }
        return n;
    }

    private static boolean under(String path, String base) {
        return path.equals(base) || path.startsWith(base) && Set.of('/', '.', '?').contains(path.charAt(base.length()));
    }
}
