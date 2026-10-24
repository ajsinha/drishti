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
package com.ash.drishti.server.connectors;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.api.Readme;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin &rarr; Connectors: a connector TYPE's README (the README standard): what the plugin reads, its settings, the shape of its
 * records, examples and troubleshooting. A plugin's README travels in its jar ({@code drishti/readme/<artifactId>/README.md}) and the
 * built-ins' in the server's ({@code drishti/readme/built-in/<type>/README.md}); a checkout's files ({@code plugins/*}/README.md,
 * {@code docs/connectors/types/*}/README.md under the working folder) win, so an edit shows without a build. Each is found by its
 * front matter's {@code connector}. Administrators only, like the rest of Admin &rarr; Connectors.
 */
@RestController
@RequestMapping("/api/v1/admin/connectors/types")
public class ConnectorTypeReadmeController {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorTypeReadmeController.class);

    private final Entitlements entitlements;
    private final Path base;

    public ConnectorTypeReadmeController(Entitlements entitlements) {
        this.entitlements = entitlements;
        this.base = Path.of("").toAbsolutePath();
    }

    @GetMapping("/{type}/readme")
    public Map<String, Object> readme(@PathVariable String type, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Found> all = all();
        Found f = all.get(type);
        if (f == null) {
            throw new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no README for connector type '" + type + "'; types with one: "
                    + String.join(", ", all.keySet()));
        }
        Map<String, Object> out = f.readme().json("connector", type);
        out.put("source", f.source());
        return out;
    }

    private record Found(Readme readme, String source) {}

    /** Every type README, by connector name: the classpath first, a checkout's files over it. */
    Map<String, Found> all() {
        Map<String, Found> out = new TreeMap<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver(getClass().getClassLoader()).getResources("classpath*:drishti/readme/**/README.md")) {
                try (InputStream in = r.getInputStream()) {
                    add(out, new String(in.readAllBytes(), StandardCharsets.UTF_8), "classpath");
                }
            }
        } catch (IOException e) {
            LOG.debug("connector READMEs on the classpath: {}", e.toString());
        }
        for (String dir : new String[] {"plugins", "docs/connectors/types"}) {
            Path d = base.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                for (Path sub : ds) {
                    Path f = sub.resolve("README.md");
                    if (Files.isRegularFile(f)) {
                        add(out, Files.readString(f, StandardCharsets.UTF_8), base.relativize(f).toString().replace('\\', '/'));
                    }
                }
            } catch (IOException e) {
                LOG.debug("connector READMEs under {}: {}", d, e.toString());
            }
        }
        return out;
    }

    private static void add(Map<String, Found> out, String text, String source) {
        Readme r = Readme.parse(text);
        Object name = r.meta().get("connector");
        if (name != null && !"template".equals(r.meta().get("category"))) {
            out.put(String.valueOf(name), new Found(r, source));
        }
    }
}
