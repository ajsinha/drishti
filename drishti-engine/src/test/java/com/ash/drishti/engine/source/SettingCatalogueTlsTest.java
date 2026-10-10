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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SettingSpec;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.tls.TlsSettings;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The TLS part of the connector settings catalogue is generated from {@link TlsSettings}, so the Admin form cannot name a key
 * the code does not read or miss one it does. These tests fail when the two differ.
 */
class SettingCatalogueTlsTest {

    private static final class Named implements SourcePlugin {
        private final String name;

        Named(String name) {
            this.name = name;
        }

        @Override
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("x"), SourceCapabilities.FETCH_ONLY);
        }

        @Override
        public void start(SourceContext context) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }
    }

    /** {@code truststorePassword} to {@code truststore-password}. */
    private static String kebab(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void theSpecsNameExactlyTheKeysTheSettingsClassReads() {
        Set<String> read = new TreeSet<>();
        for (RecordComponent c : TlsSettings.class.getRecordComponents()) {
            if (!c.getName().equals("prefix")) {
                read.add("tls." + kebab(c.getName()));
            }
        }
        Set<String> declared = TlsSettings.settingSpecs("tls.").stream().map(SettingSpec::name)
                .filter(n -> !n.endsWith("-file") || n.endsWith("cert-file") || n.endsWith("key-file") || n.endsWith("ca-file"))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(declared).as("declared keys against the keys TlsSettings reads").isEqualTo(read);
    }

    @Test
    void everyPasswordIsASecretWithAFileTwinAndNothingElseIsASecret() {
        List<SettingSpec> specs = TlsSettings.settingSpecs("tls.");
        for (SettingSpec s : specs) {
            boolean password = s.name().endsWith("-password");
            assertThat(s.secret()).as(s.name()).isEqualTo(password);
            if (password) {
                assertThat(specs).as(s.name() + " has a -file twin").anyMatch(t -> t.name().equals(s.name() + "-file") && "path".equals(t.type()));
            }
        }
    }

    @Test
    void theCatalogueOffersTheGeneratedSectionToTlsPluginsOnly() {
        SettingCatalogue cat = new SettingCatalogue(List.of(new Named("rest"), new Named("file"), new Named("kafka")));
        List<String> rest = cat.of("rest").settings().stream().map(SettingSpec::name).toList();
        assertThat(rest).containsAll(TlsSettings.settingSpecs("tls.").stream().map(SettingSpec::name).toList());
        assertThat(rest).doesNotContain("tls.truststore.path", "tls.keystore.path");     // the names that were guessed before the module
        assertThat(cat.of("file").settings().stream().map(SettingSpec::name)).noneMatch(n -> n.startsWith("tls."));
        List<SettingSpec> kafka = new ArrayList<>(cat.of("kafka").settings());
        assertThat(kafka.stream().map(SettingSpec::name)).contains("flavour", "security.protocol", "sasl.mechanism", "sasl.username",
                "sasl.password", "schema-registry.url", "schema-registry.basic-auth", "schema-registry.tls.ca-file",
                "schema-registry.tls.keystore-password", "tls.cert-file");
        assertThat(kafka.stream().filter(s -> s.name().equals("sasl.password") || s.name().equals("schema-registry.tls.key-password"))
                .allMatch(SettingSpec::secret)).isTrue();
    }
}
