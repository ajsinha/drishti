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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The connector folder: names, the file format, flattening, atomic writes with history. */
class ConnectorFilesTest {

    @Test
    void theFileNameIsTheNameAndFollowsTheRule() {
        for (String ok : new String[] {"trading-lake", "a", "0x", "risk-store-2"}) {
            assertThat(ConnectorFiles.validName(ok)).as(ok).isTrue();
        }
        for (String bad : new String[] {"Trading", "-lake", "lake_x", "lake.x", "../x", "a b", "", "plugins", "x".repeat(65)}) {
            assertThat(ConnectorFiles.validName(bad)).as(bad).isFalse();
        }
    }

    @Test
    void nestedSettingsFlattenToTheDottedKeysPluginsRead(@TempDir Path dir) {
        ConnectorFiles files = new ConnectorFiles(dir);
        String text = "plugin: kafka\nkinds: [trade, order]\nsettings:\n  bootstrap-servers: broker:9093\n  tls:\n    truststore:\n      path: /etc/ssl/ca.p12\n"
                + "      password: ${CA_PASSWORD}\n  topics: [a, b]\n  layout:\n    trade:\n      columns: [x, y]\n";
        ConnectorFiles.Definition d = files.parse("trading-stream", text);
        assertThat(d.plugin()).isEqualTo("kafka");
        assertThat(d.kinds()).containsExactly("trade", "order");
        assertThat(d.settings()).containsEntry("tls.truststore.path", "/etc/ssl/ca.p12").containsEntry("tls.truststore.password", "${CA_PASSWORD}")
                .containsEntry("topics", "a,b").containsEntry("layout.trade.columns", "x,y").containsEntry("bootstrap-servers", "broker:9093");
        assertThat(d.isEnabled()).isTrue();
    }

    @Test
    void aNameInsideThatDisagreesWithTheFileNameIsAnError(@TempDir Path dir) {
        ConnectorFiles files = new ConnectorFiles(dir);
        assertThat(files.parse("a", "name: a\nplugin: file\n").name()).isEqualTo("a");
        assertThatThrownBy(() -> files.parse("a", "name: b\nplugin: file\n")).isInstanceOf(ConnectorFiles.InvalidFile.class).hasMessageContaining("file name is the connector's name");
    }

    @Test
    void shapeErrorsAreReadable(@TempDir Path dir) {
        ConnectorFiles files = new ConnectorFiles(dir);
        assertThatThrownBy(() -> files.parse("a", "settings: {x: 1}\n")).hasMessageContaining("'plugin' is required");
        assertThatThrownBy(() -> files.parse("a", "plugin: file\nfoo: 1\n")).hasMessageContaining("unknown key 'foo'");
        assertThatThrownBy(() -> files.parse("a", "plugin: file\nenabled: maybe\n")).hasMessageContaining("'enabled'");
        assertThatThrownBy(() -> files.parse("a", "plugin: file\nsettings: [1]\n")).hasMessageContaining("'settings' is a mapping");
        assertThatThrownBy(() -> files.parse("a", "plugin: [\n")).hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> files.parse("A", "plugin: file\n")).hasMessageContaining("not a connector name");
        assertThat(files.parse("a", "plugin: file\nenabled: ${LAKE_ON:true}\n").isEnabled()).isTrue();
        assertThat(files.parse("a", "plugin: file\nenabled: false\n").isEnabled()).isFalse();
    }

    @Test
    void aQuotedTrueOrFalseIsTheSameSwitch(@TempDir Path dir) {
        // SOAK2-02: "${X:false}" resolves to the string "false", so the string written out is accepted too, as a boolean
        ConnectorFiles files = new ConnectorFiles(dir);
        assertThat(files.parse("a", "plugin: file\nenabled: \"false\"\n").isEnabled()).isFalse();
        assertThat(files.parse("a", "plugin: file\nenabled: 'FALSE'\n").isEnabled()).isFalse();
        assertThat(files.parse("a", "plugin: file\nenabled: \"true\"\n").isEnabled()).isTrue();
        assertThat(files.parse("a", "plugin: file\nenabled: \"false\"\n").enabled()).isEqualTo("false");
        assertThatThrownBy(() -> files.parse("a", "plugin: file\nenabled: \"off\"\n"))
                .hasMessageContaining("'enabled' is true or false").hasMessageContaining("not 'off'");
    }

    @Test
    void aPlaceholderThatResolvesToNeitherIsRefusedWithTheSameWords(@TempDir Path dir) throws Exception {
        // QA4-04: "${X:nope}" switched the connector off silently while a literal "nope" was refused
        ConnectorFiles files = new ConnectorFiles(dir);
        var d = files.parse("a", "plugin: file\nenabled: \"${QA4_UNSET:nope}\"\n");
        assertThat(d.enabledRefusal("nope")).hasValueSatisfying(m -> assertThat(m).contains("'enabled' is true or false").contains("not 'nope'")
                .contains("${QA4_UNSET:nope} resolves to it"));
        assertThat(d.enabledRefusal("FaLsE")).isEmpty();
        assertThat(d.enabledRefusal(" true ")).isEmpty();
        // at start-up too: the file is refused (a file problem), not bound as a boolean
        java.nio.file.Files.writeString(dir.resolve("a.yaml"), "plugin: file\nenabled: \"${QA4_UNSET:nope}\"\n");
        java.nio.file.Files.writeString(dir.resolve("b.yaml"), "plugin: file\nenabled: \"${QA4_UNSET:false}\"\n");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", Map.of("drishti.sources.connectors-dir", dir.toString(),
                "drishti.packs.dir", dir.resolve("no-packs").toString(), "drishti.data.dir", dir.resolve("data").toString(), "drishti.packs.enabled", " ")));
        String dataDir = System.getProperty(com.ash.drishti.api.DataDir.PROPERTY);
        try {
            new PackEnvironmentPostProcessor().postProcessEnvironment(env, null);
        } finally {
            if (dataDir == null) {
                System.clearProperty(com.ash.drishti.api.DataDir.PROPERTY);    // the post-processor hands plugins the data folder: not this test's
            }
        }
        assertThat(env.getProperty("drishti.sources.connector-file-problems.a")).contains("'enabled' is true or false").contains("not 'nope'");
        assertThat(env.getProperty("drishti.sources.connectors.a.plugin")).isNull();
        assertThat(env.getProperty("drishti.sources.connectors.b.enabled", Boolean.class)).isFalse();
    }

    @Test
    void writesAreAtomicAndKeepEveryEarlierTextInHistory(@TempDir Path dir) throws Exception {
        ConnectorFiles files = new ConnectorFiles(dir.resolve("config/connectors"));          // created on first write
        assertThat(files.names()).isEmpty();
        files.write("lake", "plugin: file\nsettings:\n  root: /a\n");
        assertThat(files.history("lake")).isEmpty();
        files.write("lake", "plugin: file\nsettings:\n  root: /b\n");
        files.write("lake", "plugin: file\nsettings:\n  root: /c\n");
        List<ConnectorFiles.Revision> h = files.history("lake");
        assertThat(h).hasSize(2);
        assertThat(files.revisionText("lake", h.get(1).id())).contains("/a");                  // oldest
        assertThat(files.revisionText("lake", h.get(0).id())).contains("/b");
        assertThat(files.read("lake").settings()).containsEntry("root", "/c");
        try (var s = Files.list(files.dir())) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("lake.yaml", "history");     // no temp files
        }
        assertThat(files.delete("lake")).isTrue();
        assertThat(files.history("lake")).hasSize(3);                                          // a delete keeps the text too
        assertThat(files.revisionText("lake", "../../x")).isNull();
    }

    @Test
    void etagsFollowTheText(@TempDir Path dir) {
        assertThat(ConnectorFiles.etag("a")).isEqualTo(ConnectorFiles.etag("a")).isNotEqualTo(ConnectorFiles.etag("b"));
    }

    @Test
    void badFilesAreReportedAndTheOthersStillLoad(@TempDir Path dir) throws Exception {
        ConnectorFiles files = new ConnectorFiles(dir);
        files.write("good", "plugin: file\n");
        files.write("broken", "plugin: file\nbogus: 1\n");
        Files.writeString(dir.resolve("Upper.yaml"), "plugin: file\n");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Map<String, String> problems = new HashMap<>();
        assertThat(files.readAll(problems)).containsOnlyKeys("good");
        assertThat(problems).containsOnlyKeys("broken");
        assertThat(files.misnamed()).containsExactly("Upper.yaml", "notes.txt");
    }

    @Test
    void renderNestsTlsAndRoundTrips(@TempDir Path dir) {
        ConnectorFiles files = new ConnectorFiles(dir);
        Map<String, Object> flat = new java.util.LinkedHashMap<>();
        flat.put("url", "jdbc:postgresql://db/x");
        flat.put("tls.truststore.path", "/ca.p12");
        flat.put("tls.enabled", "true");
        String text = files.render("# header", "jdbc", Boolean.FALSE, List.of("trade"), "A db", flat);
        assertThat(text).startsWith("# header").contains("enabled: false").contains("tls:").contains("truststore:");
        ConnectorFiles.Definition d = files.parse("db", text);
        assertThat(d.settings()).containsEntry("tls.truststore.path", "/ca.p12").containsEntry("tls.enabled", "true").containsEntry("url", "jdbc:postgresql://db/x");
        assertThat(d.kinds()).containsExactly("trade");
        assertThat(d.isEnabled()).isFalse();
        assertThat(d.description()).isEqualTo("A db");
    }

    @Test
    void aRequiresBlockIsKeptAsWrittenAndThroughAFormEdit(@TempDir Path dir) {
        ConnectorFiles files = new ConnectorFiles(dir);
        ConnectorFiles.Definition d = files.parse("var-engine", """
                plugin: reactive
                requires:
                  python: { version: "3.12", lock: requires/var-engine.lock }
                  plugins: [ { name: drishti-plugin-delta } ]
                """);
        assertThat(d.requires()).containsKeys("python", "plugins");
        String rendered = files.render("# form", "reactive", true, java.util.List.of(), "", java.util.Map.of(), d.requires());
        assertThat(files.parse("var-engine", rendered).requires()).isEqualTo(d.requires());
        assertThatThrownBy(() -> files.parse("x", "plugin: file\nrequires: [numpy]\n")).hasMessageContaining("'requires' is a mapping");
    }

    @Test
    void aReactiveConnectorsComputationReachesItsPluginAndSurvivesTheForm(@TempDir Path dir) {
        ConnectorFiles files = new ConnectorFiles(dir);
        String text = "plugin: reactive\nmode: reactive\nkinds:\n  sum-vectors:\n    params:\n      delay-ms: { type: int, default: 0 }\n"
                + "reads:\n  - { kind: strip, as: values, all: true }\nengine: { type: python, entry: 'a.b:C', pool: sample }\n"
                + "triggers:\n  - { on: schedule, every: 1m }\nsettings:\n  stale-after: 5m\n";
        ConnectorFiles.Definition d = files.parse("demo-compute", text);
        assertThat(d.kinds()).containsExactly("sum-vectors");
        assertThat(d.compute()).containsKeys("mode", "kinds", "reads", "engine", "triggers");
        assertThat(d.settings()).containsKey(ConnectorFiles.COMPUTE_SETTING).containsEntry("stale-after", "5m");
        assertThat(d.settings().get(ConnectorFiles.COMPUTE_SETTING)).contains("\"pool\":\"sample\"");
        String rendered = files.render("# form", "reactive", true, d.kinds(), "", d.settings(), Map.of(), d.compute());
        assertThat(rendered).doesNotContain(ConnectorFiles.COMPUTE_SETTING).contains("engine:");
        assertThat(files.parse("demo-compute", rendered).compute()).isEqualTo(d.compute());
        assertThatThrownBy(() -> files.parse("x", "plugin: jdbc\nengine: { type: java }\n")).hasMessageContaining("only a connector of plugin reactive");
        assertThatThrownBy(() -> files.parse("x", "plugin: jdbc\nkinds: { a: {} }\n")).hasMessageContaining("kinds with parameters");
        assertThatThrownBy(() -> files.parse("x", "plugin: jdbc\nmode: magic\n")).hasMessageContaining("'mode' is static, live, on-demand or reactive");
    }
}
