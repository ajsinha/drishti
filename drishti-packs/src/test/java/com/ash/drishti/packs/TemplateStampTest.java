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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fingerprint in a generated connector file's own text: written after the header, read back, an untouched text pristine, any edit
 * (not a CRLF save) not; restamping replaces the line; and at start an unedited stamped file follows its pack's changed template while an
 * edited one is kept and flagged.
 */
class TemplateStampTest {

    private static final String TEXT = "# Generated at first start from the connector template of pack 'risk'.\nplugin: delta\nkinds:\n- trade\n";

    @Test
    void aStampedTextIsPristineUntilEdited() {
        String s = TemplateStamp.stamp(TEXT, "risk", "abc123");
        assertThat(s).startsWith("# Generated at first start").contains("\n# drishti-template: pack=risk template=abc123 text=");
        assertThat(TemplateStamp.of(s)).hasValueSatisfying(st -> {
            assertThat(st.pack()).isEqualTo("risk");
            assertThat(st.template()).isEqualTo("abc123");
        });
        assertThat(TemplateStamp.pristine(s)).isTrue();
        assertThat(TemplateStamp.pristine(s.replace("\n", "\r\n"))).as("a CRLF save is no edit").isTrue();
        assertThat(TemplateStamp.pristine(s.replace("- trade", "- trade\n- valuation"))).isFalse();
        assertThat(TemplateStamp.pristine(TEXT)).as("no stamp: not known to be ours").isFalse();
        assertThat(TemplateStamp.withoutStamp(s)).isEqualTo(TEXT);
        String again = TemplateStamp.stamp(s, "risk", "def456");
        assertThat(again.split("drishti-template", -1)).hasSize(2);
        assertThat(TemplateStamp.of(again).orElseThrow().template()).isEqualTo("def456");
        assertThat(TemplateStamp.stamp("plugin: delta\n", "p", "f")).startsWith("# drishti-template: pack=p template=f text=");
    }

    private static List<Pack> packs(Path dir, String yaml) throws Exception {
        Path p = Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(p.resolve("pack.yaml"), yaml);
        return new PackLoader().load(dir.resolve("packs"), List.of("risk"));
    }

    private static final String V1 = "pack: risk\nkinds: [trade, valuation]\nroutes:\n  trade: pristine\n  valuation: edited\nconnectors:\n"
            + "  pristine:\n    plugin: delta\n    kinds: [trade]\n    settings:\n      url: a\n"
            + "  edited:\n    plugin: delta\n    kinds: [valuation]\n    settings:\n      url: a\n";

    @Test
    void atStartAnUneditedFileFollowsItsPackAndAnEditedOneIsKept(@TempDir Path dir) throws Exception {
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"), dir.resolve("state"));
        ConnectorBootstrap.run(packs(dir, V1), files, null, true);
        assertThat(TemplateStamp.pristine(files.text("pristine"))).isTrue();
        files.write("edited", files.text("edited").replace("url: a", "url: mine"));
        List<Pack> v2 = packs(dir, V1.replace("      url: a\n", "      url: a\n      pool: 4\n"));
        ConnectorBootstrap.Result r = ConnectorBootstrap.run(v2, files, null, true);
        assertThat(r.regenerated()).containsExactly("pristine");
        Map<String, Object> t = v2.get(0).connectorTemplates().get("pristine");
        assertThat(files.text("pristine")).isEqualTo(ConnectorBootstrap.generate(files, "pristine", "risk", t)).contains("pool");
        assertThat(files.text("edited")).contains("url: mine").doesNotContain("pool");     // the server's template updates add it
        assertThat(r.changed()).extracting(ConnectorBootstrap.TemplateChange::name).containsExactly("edited");
        try (var s = Files.list(dir.resolve("connectors"))) {
            assertThat(s.map(x -> x.getFileName().toString())).anyMatch(n -> n.startsWith("pristine.yaml.before-upgrade-") && n.endsWith(".bak"));
        }
    }
}
