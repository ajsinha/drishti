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
package com.ash.drishti.identity.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code config/store/store.yaml} and its examples load; a missing file is the filesystem default; the cluster check warns on different roots. */
class ContentStoreSettingsTest {

    private static final Path DATA = Path.of("target", "content-tests", "data");
    private static final UnaryOperator<String> RESOLVE = s -> s.replace("${drishti.data.dir:./data}", DATA.toString())
            .replaceAll("\\$\\{[A-Z_]+:([^}]*)}", "$1");

    @Test
    void theShippedFileAndEveryExampleLoad() throws Exception {
        ContentStoreSettings shipped = ContentStoreSettings.load(Path.of("..", "config", "store", "store.yaml"), DATA, RESOLVE);
        assertThat(shipped.backend()).isEqualTo("filesystem");
        assertThat(shipped.fsRoot()).isEqualTo(DATA.resolve("fstore").toAbsolutePath().normalize());
        List<String> backends;
        try (Stream<Path> s = Files.list(Path.of("..", "config", "store.examples"))) {
            backends = s.sorted().map(p -> ContentStoreSettings.load(p, DATA, RESOLVE).backend()).toList();
        }
        assertThat(backends).containsExactlyInAnyOrder("filesystem", "filesystem", "postgres", "s3");
    }

    @Test
    void aMissingFileIsTheFilesystemUnderTheDataFolder() {
        ContentStoreSettings s = ContentStoreSettings.load(Path.of("target", "no-such-store.yaml"), DATA, RESOLVE);
        assertThat(s.backend()).isEqualTo("filesystem");
        assertThat(s.origin()).isEqualTo("default");
        assertThatThrownBy(() -> ContentStoreSettings.from(Map.of("backend", "delta"), DATA, RESOLVE, "t")).hasMessageContaining("filesystem, sqlite, postgres or s3");
        assertThatThrownBy(() -> ContentStoreSettings.from(Map.of("backend", "postgres"), DATA, RESOLVE, "t")).hasMessageContaining("postgres.url");
    }

    @Test
    void nodesOnDifferentRootsAreReported(@TempDir Path tmp) {
        Path root = tmp.resolve("nodes");
        ContentStore a = new FileContentStore(root);
        assertThat(ContentStoreNodes.announce(a, "node-a")).isEmpty();
        assertThat(ContentStoreNodes.announce(new FileContentStore(root), "node-b")).isEmpty();
        a.putLatest("nodes/node-c.json", "{\"node\":\"node-c\",\"root\":\"filesystem /elsewhere\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(ContentStoreNodes.announce(a, "node-a")).containsExactly("node-c filesystem /elsewhere");
    }
}
