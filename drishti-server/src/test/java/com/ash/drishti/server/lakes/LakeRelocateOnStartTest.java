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
package com.ash.drishti.server.lakes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * The move at start (docs/admin/OPERATIONS.md, "The lakes' folders"): the demo lake's folders found directly in {@code <data>/delta} are
 * moved into {@code <data>/delta/demo} once, with the server's own settings ({@code drishti.data.dir}, {@code drishti.lakes.dir}); with
 * {@code drishti.lakes.relocate-on-start: false} nothing moves. The moving itself is LakeRelocateTest's (drishti-lakeload).
 */
class LakeRelocateOnStartTest {

    @TempDir
    Path tmp;

    private MockEnvironment env(boolean on) throws Exception {
        Path lakes = Files.createDirectories(tmp.resolve("config/lakes"));
        Files.writeString(lakes.resolve("demo.yaml"), "lake: demo\nformat: delta\nroot: ${DRISHTI_DELTA_ROOT_FOR_THIS_TEST:${drishti.data.dir:./data}/delta/demo}\n"
                + "tables:\n  reference.book: {id: BOOK_ID}\n");
        Files.createDirectories(tmp.resolve("data/delta/reference"));
        Files.createDirectories(tmp.resolve("data/delta/_drishti-lake"));
        Path conns = Files.createDirectories(tmp.resolve("config/connectors"));
        Files.writeString(conns.resolve("reference-store.yaml"), "# Generated at first start from the connector template of pack 'core'.\n"
                + "plugin: delta\nkinds:\n- book\nsettings:\n  root: \"${DRISHTI_DELTA_ROOT:./data/delta}\"\n  domain: reference\n"
                + "  layout.book.columns: BOOK_ID,NAME\n  cache-minutes: 5\n");
        Files.writeString(conns.resolve("vendor-store.yaml"), "plugin: delta\nkinds:\n- book\nsettings:\n  root: s3://vendor-bucket/delta\n  domain: vendor\n");
        return new MockEnvironment().withProperty("drishti.data.dir", tmp.resolve("data").toString())
                .withProperty("drishti.sources.connectors-dir", conns.toString())
                .withProperty("drishti.lakes.dir", lakes.toString()).withProperty(LakeRelocateOnStart.SWITCH, String.valueOf(on));
    }

    @Test
    void theDemoLakeMovesIntoItsOwnFolderOnce() throws Exception {
        new LakeRelocateOnStart().postProcessEnvironment(env(true), null);
        assertThat(tmp.resolve("data/delta/demo/reference")).isDirectory();
        assertThat(tmp.resolve("data/delta/demo/_drishti-lake/relocated.json")).isRegularFile();
        assertThat(tmp.resolve("data/delta/reference")).doesNotExist();
        // the connector that read the demo lake by its old default root follows it by name now; a path or URI of its own stays
        String text = Files.readString(tmp.resolve("config/connectors/reference-store.yaml"));
        assertThat(text).startsWith("# Generated at first start").contains("lake: demo", "lake-tables: reference.book", "cache-minutes")
                .doesNotContain("root:", "domain:");
        try (var s = Files.list(tmp.resolve("config/connectors"))) {
            assertThat(s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("reference-store.yaml.before-lake-")).count()).isEqualTo(1);
        }
        assertThat(Files.readString(tmp.resolve("config/connectors/vendor-store.yaml"))).contains("root: s3://vendor-bucket/delta").doesNotContain("lake:");
        new LakeRelocateOnStart().postProcessEnvironment(env(true), null);          // again: done before, nothing more (the folder made again stays)
        assertThat(tmp.resolve("data/delta/demo/reference")).isDirectory();
    }

    @Test
    void switchedOffNothingMoves() throws Exception {
        new LakeRelocateOnStart().postProcessEnvironment(env(false), null);
        assertThat(tmp.resolve("data/delta/reference")).isDirectory();
        assertThat(tmp.resolve("data/delta/demo")).doesNotExist();
        assertThat(Files.readString(tmp.resolve("config/connectors/reference-store.yaml"))).contains("root:").doesNotContain("lake:");
    }
}
