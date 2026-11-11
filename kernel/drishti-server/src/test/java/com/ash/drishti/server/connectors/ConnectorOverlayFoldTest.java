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

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.packs.ConnectorFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The upgrade that folds an earlier release's connector edits into the files, once per node, and the warning when nodes' files differ. */
class ConnectorOverlayFoldTest {

    private static final String BASE = "plugin: file\nsettings:\n  root: /base\n";
    private static final String EDIT = "plugin: file\nsettings:\n  root: /edited\n";
    private final List<String> audited = new ArrayList<>();
    private final AuditLog audit = new AuditLog() {
        @Override
        public void record(String actor, String action, String subject, String detail) {
            audited.add(action + " " + subject);
        }

        @Override
        public List<Event> recent(int limit, String subject) {
            return List.of();
        }
    };

    private static void put(ContentStore s, String key, String text) {
        s.putLatest(key, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String stamp(Instant at) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC).format(at);
    }

    private static ConnectorFiles node(Path dir, String name, String base, Instant modified) throws Exception {
        Path conf = Files.createDirectories(dir.resolve(name + "/connectors"));
        if (base != null) {
            Files.writeString(conf.resolve("lake.yaml"), base);
            Files.setLastModifiedTime(conf.resolve("lake.yaml"), FileTime.from(modified));
        }
        return new ConnectorFiles(conf, dir.resolve(name + "/state"));
    }

    @Test
    void twoNodesEachFoldTheEditIntoTheirOwnFileOnce(@TempDir Path dir) throws Exception {
        Instant edit = Instant.now().minusSeconds(60);
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        put(store, ConnectorOverlayFold.PREFIX + "lake.yaml", EDIT);
        put(store, ConnectorOverlayFold.HISTORY + "lake/" + stamp(edit) + ".yaml", BASE);
        ConnectorFiles a = node(dir, "a", BASE, edit.minusSeconds(60));
        ConnectorFiles b = node(dir, "b", null, edit);

        assertThat(new ConnectorOverlayFold(a, store, audit).run()).containsExactly("lake");
        assertThat(new ConnectorOverlayFold(b, store, audit).run()).containsExactly("lake");
        assertThat(a.text("lake")).isEqualTo(EDIT);
        assertThat(b.text("lake")).isEqualTo(EDIT);
        assertThat(a.history("lake")).isNotEmpty();
        try (var s = Files.list(a.dir())) {
            assertThat(s.map(f -> f.getFileName().toString())).anyMatch(f -> f.startsWith("lake.yaml.before-fold-"));
        }
        assertThat(audited).containsExactly("connector-folded lake", "connector-folded lake");

        Files.writeString(a.file("lake"), BASE);                                    // the second start does nothing
        assertThat(new ConnectorOverlayFold(a, store, audit).run()).isEmpty();
        assertThat(a.text("lake")).isEqualTo(BASE);
    }

    @Test
    void aFileEditedAfterTheEditIsKeptAndTheEditSavedBesideIt(@TempDir Path dir) throws Exception {
        Instant edit = Instant.now().minusSeconds(3600);
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        put(store, ConnectorOverlayFold.PREFIX + "lake.yaml", EDIT);
        put(store, ConnectorOverlayFold.HISTORY + "lake/" + stamp(edit) + ".yaml", BASE);
        ConnectorFiles a = node(dir, "a", "plugin: file\nsettings:\n  root: /mine\n", Instant.now());

        assertThat(new ConnectorOverlayFold(a, store, audit).run()).isEmpty();
        assertThat(a.text("lake")).contains("/mine");
        try (var s = Files.list(a.dir())) {
            assertThat(s.map(f -> f.getFileName().toString())).anyMatch(f -> f.startsWith("lake.yaml.content-store-"));
        }
    }

    @Test
    void aConnectorDeletedThroughAdminLosesItsFile(@TempDir Path dir) throws Exception {
        Instant edit = Instant.now().minusSeconds(60);
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        put(store, ConnectorOverlayFold.PREFIX + "lake.hidden", "hidden through Admin\n");
        put(store, ConnectorOverlayFold.HISTORY + "lake/" + stamp(edit) + ".yaml", BASE);
        ConnectorFiles a = node(dir, "a", BASE, edit.minusSeconds(60));

        assertThat(new ConnectorOverlayFold(a, store, audit).run()).containsExactly("lake");
        assertThat(a.fileExists("lake")).isFalse();
    }

    @Test
    void theFilesOfTwoNodesAreCompared() {
        var out = ConnectorFileDrift.compare(Map.of("lake", "aaaa", "own", "bbbb"), Map.of("drishti-b", Map.of("lake", "cccc", "other", "dddd"), "drishti-c", Map.of("lake", "aaaa")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("name", "lake").containsEntry("nodes", List.of("drishti-b"));
    }
}
