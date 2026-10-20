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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.ConnectorSecrets;
import com.ash.drishti.engine.source.PluginDiscovery;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.packs.ConnectorFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;

/**
 * Every example under config/connectors.examples is a valid connector file: it parses, names a shipped plugin, holds no literal credential. And (QA2-04) it
 * starts as copied: every {@code ${ENV}} resolves with no environment variable set, as the server resolves a file at start, except in the examples
 * for stores that always ask for a secret ({@code postgres/}, {@code other/}); its promoted columns are the production UPPER_SNAKE names.
 */
class ConnectorExamplesTest {

    @Test
    void everyExampleIsValidAndHoldsNoSecret() throws IOException {
        Path dir = Path.of("../config/connectors.examples");
        ConnectorFiles files = new ConnectorFiles(dir);
        SettingCatalogue catalogue = new SettingCatalogue(new PluginDiscovery().discover(null));
        List<Path> all;
        try (Stream<Path> s = Files.walk(dir)) {
            all = s.filter(p -> p.toString().endsWith(".yaml")).toList();
        }
        assertThat(all.size()).isGreaterThan(40);
        for (Path p : all) {
            String name = p.getFileName().toString().replace(".yaml", "");
            ConnectorFiles.Definition d = files.parse(name, Files.readString(p));
            assertThat(catalogue.of(d.plugin())).as(p + " plugin " + d.plugin()).isNotNull();
            d.settings().forEach((k, v) -> {
                if (ConnectorSecrets.secretKey(k) && !v.isBlank()) {
                    assertThat(ConnectorSecrets.reference(v)).as(p + " " + k).isTrue();
                }
                var spec = catalogue.of(d.plugin()).find(k);
                if (catalogue.of(d.plugin()).declared()) {
                    assertThat(spec).as(p + " setting " + k + " is known to the " + d.plugin() + " plugin").isNotNull();
                }
            });
            assertThat(d.settings().get("password")).as(p + " password has no default but an empty one").satisfiesAnyOf(
                    v -> assertThat(v).isNull(), v -> assertThat(v).doesNotContain(":"), v -> assertThat(v).matches("\\$\\{[A-Z0-9_]+:}"));
        }
    }

    /** The families whose walk-through starts on a store with no sign-in: their examples must start with no environment variable set. */
    static final Set<String> SIGN_IN = Set.of("postgres", "other");

    @Test
    void everyExampleStartsAsCopiedWithNoEnvironmentAndPromotesProductionColumns() throws IOException {
        Path dir = Path.of("../config/connectors.examples");
        ConnectorFiles files = new ConnectorFiles(dir);
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("server", Map.of("drishti.data.dir", "./data")));      // what the server itself defines; no ${DRISHTI_*}
        PropertySourcesPropertyResolver env = new PropertySourcesPropertyResolver(sources);
        List<String> refused = new ArrayList<>();
        List<Path> all;
        try (Stream<Path> s = Files.walk(dir)) {
            all = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path p : all) {
            String family = dir.relativize(p).getName(0).toString();
            ConnectorFiles.Definition d = files.parse(p.getFileName().toString().replace(".yaml", ""), Files.readString(p));
            d.settings().forEach((k, v) -> {
                try {
                    env.resolveRequiredPlaceholders(v);
                } catch (IllegalArgumentException e) {
                    if (!SIGN_IN.contains(family)) {                         // their stores always ask for a secret: the site sets it
                        refused.add(dir.relativize(p) + " " + k + ": " + v);
                    }
                }
                if (k.matches("layout\\.[a-z-]+\\.columns")) {
                    for (String c : v.split(",")) {
                        assertThat(c.trim()).as(p + " " + k + " promotes production names").matches("[A-Z][A-Z0-9_]*(\\.[A-Z][A-Z0-9_]*)*");
                    }
                }
            });
        }
        assertThat(refused).as("refused at start: a ${VAR} with no default (give it one, ${VAR:} for none)").isEmpty();
        assertThat(all).as("the trades' examples are named for the connector kind trade routes to").noneMatch(p -> p.endsWith("iceberg/trading-store.yaml")
                || p.endsWith("cassandra/trading-store.yaml"));
    }
}
