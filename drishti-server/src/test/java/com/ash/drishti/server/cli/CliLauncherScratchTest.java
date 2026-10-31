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
package com.ash.drishti.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code sutra} command line writes nothing to the site it runs in: started in a working folder that looks like a site
 * (data/, config/), it leaves both as it found them, and its scratch folder is gone when it ends.
 */
class CliLauncherScratchTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void theSettingsPutEveryWrittenFolderInScratchAndKeepTheSitesLakesForReading(@TempDir Path scratch) {
        Map<String, String> p = CliLauncher.scratchProperties(scratch, Map.of(), Map.of("DRISHTI_FILES_ROOT", "/lakes/files"));
        assertThat(p.get("drishti.data.dir")).isEqualTo(scratch.resolve("data").toString());
        assertThat(p.get("drishti.identity.database-url")).isEqualTo("jdbc:sqlite:" + scratch.resolve("data/identity/drishti.db"));
        assertThat(p.get("drishti.store.root")).startsWith(scratch.toString());
        assertThat(p.get("DRISHTI_CACHE_ROOT")).startsWith(scratch.toString());
        assertThat(p).containsEntry("drishti.var.official.enabled", "false").containsEntry("drishti.ccr.exposure.official.enabled", "false")
                .containsEntry("drishti.sources.plugins.demo.settings.ticking", "false").containsEntry("drishti.sources.connectors-generate", "false");
        Path site = Path.of(com.ash.drishti.api.DataDir.root()).toAbsolutePath().normalize();
        assertThat(p).as("the site's lakes are read where they are").containsEntry("DRISHTI_DELTA_ROOT", site.resolve("delta").toString());
        assertThat(p).as("a lake the environment names is left to it").doesNotContainKey("DRISHTI_FILES_ROOT");
        assertThat(CliLauncher.scratchProperties(scratch, Map.of("DRISHTI_PACKS_OVERLAY", "x", "drishti.packs.installed-dir", "y"), Map.of()))
                .as("the command line's own pack settings win").doesNotContainKeys("DRISHTI_PACKS_OVERLAY", "DRISHTI_PACKS_INSTALLED");
    }

    @Test
    void aCommandLeavesTheWorkingFolderAsItFoundIt(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve("data/identity"));
        Files.writeString(work.resolve("data/README.txt"), "the site's data\n");
        Files.createDirectories(work.resolve("config/connectors"));
        Path sutra = Files.createDirectories(work.resolve("mine/sutras")).resolve("widget.v1.sutra.yaml");
        Files.writeString(sutra, """
                rachana: 1
                sutra: widget
                version: 1
                match: { kind: widget }
                title: { pill: Widget, id: $.id }
                panels:
                  - { id: facts, kind: kv, title: Facts, columns: [ { label: Size, bind: $.size } ] }
                """);
        Path jvmTmp = Files.createDirectories(work.resolve("jvm-tmp"));
        Map<String, Long> before = files(work, jvmTmp);

        // the server's own class path: the shipped application.yaml, not the tests' configuration
        String classPath = Stream.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(e -> !e.endsWith("test-classes") && !e.endsWith("test-classes/")).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx768m",
                "-Djava.io.tmpdir=" + jvmTmp, "-cp", classPath, "com.ash.drishti.server.DrishtiApplication",
                "sutra", "lint", sutra.toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("DRISHTI_"));
        pb.environment().putAll(Map.of("DRISHTI_PACKS_DIR", REPO.resolve("config/packs").toString(), "DRISHTI_PACKS", "trading",
                "DRISHTI_EDITIONS_DIR", REPO.resolve("config/editions").toString()));
        Path log = jvmTmp.resolve("run.log");
        pb.redirectOutput(log.toFile());
        Process proc = pb.start();
        boolean ended = proc.waitFor(5, TimeUnit.MINUTES);
        if (!ended) {
            proc.destroyForcibly().waitFor();
        }
        assertThat(ended).as("the command ends").isTrue();
        String said = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(proc.exitValue()).as(said).isZero();

        assertThat(files(work, jvmTmp)).as("nothing written to data/ or config/ (or anywhere in the working folder)").isEqualTo(before);
        try (Stream<Path> s = Files.walk(jvmTmp)) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("sutra-cli")).toList()).as("the scratch folder is removed").isEmpty();
        }
    }

    /** Every file under {@code root} but {@code skip}, with its modification time. */
    private static Map<String, Long> files(Path root, Path skip) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(p -> !p.startsWith(skip)).toList()) {
                out.put(root.relativize(p).toString(), Files.isDirectory(p) ? 0L : Files.getLastModifiedTime(p).toMillis());
            }
        }
        return out;
    }
}
