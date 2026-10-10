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
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("lake.yaml", ".history");     // no temp files
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
}
