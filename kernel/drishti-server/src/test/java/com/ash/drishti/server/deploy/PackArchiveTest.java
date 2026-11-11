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
package com.ash.drishti.server.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Archive reading: layout, manifest, and everything that must be refused (slip, links, size, count, tampering, server version). */
class PackArchiveTest {

    @TempDir Path tmp;

    private PackArchive archive() {
        return new PackArchive(10 * 1024 * 1024, 100);
    }

    private Map<String, String> files() {
        Map<String, String> f = TestArchives.pack("widgets", "1.0.0", "kinds: [widget]\n");
        f.put("sutras/w.v1.sutra.yaml", "sutra: w\n");
        return f;
    }

    @Test
    void readsABundleAndItsManifestChecksOut() throws Exception {
        PackArchive.Extracted ex = archive().extract(TestArchives.bundle("widgets", "1.0.0", files()), tmp.resolve("a"));
        assertThat(ex.root().getFileName().toString()).isEqualTo("widgets");
        assertThat(ex.files()).isEqualTo(3);                                    // MANIFEST.json, pack.yaml, one Sutra
        assertThat(PackArchive.checkManifest(ex.root(), ex.manifest(), "widgets", "1.0.0")).isEmpty();
    }

    @Test
    void readsABundleMadeByPackbundlePy() throws Exception {
        // tools/packbundle.py output (pax headers for a path over 100 characters): the format both sides must agree on
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/deploy/helpdesk-0.1.0.tar.gz"));
        PackArchive.Extracted ex = archive().extract(bytes, tmp.resolve("b"));
        assertThat(ex.root().resolve("pack.yaml")).exists();
        assertThat(ex.manifest().path("pack").asText()).isEqualTo("helpdesk");
        assertThat(PackArchive.checkManifest(ex.root(), ex.manifest(), "helpdesk", "0.1.0")).isEmpty();
        try (var walk = Files.walk(ex.root())) {
            assertThat(walk.anyMatch(p -> p.getFileName().toString().startsWith("a-very-long-sample-file-name"))).isTrue();
        }
    }

    @Test
    void aTamperedFileIsReportedByName() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>(files());
        actual.put("sutras/w.v1.sutra.yaml", "sutra: w-changed-after-bundling\n");
        PackArchive.Extracted ex = archive().extract(TestArchives.tarGz("widgets", "1.0.0", null, files(), actual), tmp.resolve("c"));
        assertThat(PackArchive.checkManifest(ex.root(), ex.manifest(), "widgets", "1.0.0")).containsExactly("checksum mismatch: sutras/w.v1.sutra.yaml");
    }

    @Test
    void anAddedOrMissingFileAndAWrongVersionAreReported() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>(files());
        actual.put("extra.txt", "x");
        actual.remove("sutras/w.v1.sutra.yaml");
        PackArchive.Extracted ex = archive().extract(TestArchives.tarGz("widgets", "1.0.0", null, files(), actual), tmp.resolve("d"));
        assertThat(PackArchive.checkManifest(ex.root(), ex.manifest(), "widgets", "2.0.0")).containsExactlyInAnyOrder(
                "missing file: sutras/w.v1.sutra.yaml", "file not in the manifest: extra.txt",
                "pack.yaml says widgets 2.0.0 but the manifest says widgets 1.0.0");
    }

    @Test
    void pathsThatLeaveThePackFolderAreRefusedInTarAndZip() {
        for (String evil : new String[] {"../evil.txt", "widgets/../../evil.txt", "/etc/evil.txt", "widgets/a/../../../evil.txt"}) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put(evil, "x");
            assertThatThrownBy(() -> archive().extract(TestArchives.tarGz(e), tmp.resolve("t")))
                    .as("tar " + evil).isInstanceOf(PackArchive.Refused.class).hasMessageContaining("unsafe path");
            assertThatThrownBy(() -> archive().extract(TestArchives.zip(e), tmp.resolve("z")))
                    .as("zip " + evil).isInstanceOf(PackArchive.Refused.class).hasMessageContaining("unsafe path");
        }
        assertThat(tmp.resolve("evil.txt")).doesNotExist();
        assertThat(tmp.getParent().resolve("evil.txt")).doesNotExist();
    }

    @Test
    void linksAreRefused() {
        for (char type : new char[] {'2', '1'}) {
            assertThatThrownBy(() -> archive().extract(TestArchives.tarGzLink("widgets/pack.yaml", type, "/etc/passwd"), tmp.resolve("l")))
                    .isInstanceOf(PackArchive.Refused.class).hasMessageContaining("link");
        }
    }

    @Test
    void tooManyFilesAndTooManyBytesAreRefused() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            many.put("widgets/f" + i, "x");
        }
        many.put("widgets/pack.yaml", "pack: widgets\n");
        assertThatThrownBy(() -> new PackArchive(1 << 20, 10).extract(TestArchives.tarGz(many), tmp.resolve("n")))
                .isInstanceOf(PackArchive.Refused.class).hasMessageContaining("more than 10 files");
        Map<String, String> big = new LinkedHashMap<>();
        big.put("widgets/pack.yaml", "pack: widgets\n");
        big.put("widgets/big.bin", "x".repeat(2 * 1024 * 1024));
        assertThatThrownBy(() -> new PackArchive(1024 * 1024, 100).extract(TestArchives.tarGz(big), tmp.resolve("b")))
                .isInstanceOf(PackArchive.Refused.class).hasMessageContaining("unpacks to more than");
    }

    @Test
    void notAnArchiveAndNoPackYamlAreRefused() {
        assertThatThrownBy(() -> archive().extract("hello".getBytes(), tmp.resolve("x"))).isInstanceOf(PackArchive.Refused.class).hasMessageContaining("not a .tar.gz or .zip");
        Map<String, String> none = new LinkedHashMap<>();
        none.put("widgets/readme.txt", "x");
        assertThatThrownBy(() -> archive().extract(TestArchives.tarGz(none), tmp.resolve("y"))).isInstanceOf(PackArchive.Refused.class).hasMessageContaining("pack.yaml");
    }

    @Test
    void aZipWithPackYamlAtItsRootIsAccepted() throws Exception {
        PackArchive.Extracted ex = archive().extract(TestArchives.zip(files()), tmp.resolve("zz"));
        assertThat(ex.root()).isEqualTo(tmp.resolve("zz"));
        assertThat(ex.manifest()).isNull();
    }

    @Test
    void theRequiredServerVersionIsComparedByNumberNotText() throws Exception {
        var m = new ObjectMapper().readTree("{\"requiresServer\": \">=1.16.0\"}");
        assertThat(PackArchive.checkServerVersion(m, "1.16.0")).isNull();
        assertThat(PackArchive.checkServerVersion(m, "1.17.2-SNAPSHOT")).isNull();
        assertThat(PackArchive.checkServerVersion(m, "1.9.9")).contains("needs server >=1.16.0 but this server is 1.9.9");
        assertThat(PackArchive.checkServerVersion(m, "1.100.0")).isNull();                  // 100 > 16, not "1.100" < "1.16" as text
        assertThat(PackArchive.checkServerVersion(m, null)).isNull();                       // unknown running version: not guessed
        assertThat(PackArchive.checkServerVersion(new ObjectMapper().readTree("{}"), "0.1")).isNull();
    }
}
