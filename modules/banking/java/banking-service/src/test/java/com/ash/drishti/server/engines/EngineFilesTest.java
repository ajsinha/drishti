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
package com.ash.drishti.server.engines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The engine files and the switches (ADMIN_GUIDE.md sections 3, 4 and 8; tests EngineFilesTest and EngineFileWriteTest): what wins
 * (the local file over the engine file over the shipped copy), each old switch name as an alias with its DRS-1024 warning, the in-memory
 * lake on when its stores are in the old place; the page's write keeps every comment and key and the switch's variable, writes
 * {@code changed}, is atomic, creates the file from the jar's copy, and refuses a file changed on disk (DRS-1023).
 */
class EngineFilesTest {

    @TempDir Path dir;

    static final String SHIPPED = "Config resource 'class path resource [engines/var.yaml]' via location 'classpath:engines/var.yaml'";
    static final String LOCAL = "Config resource 'file [./application.local.yaml]' via location 'optional:file:./application.local.yaml'";
    static final String FILE = "Config resource 'file [config/engines/var.yaml]' via location 'optional:file:./config/engines/var.yaml'";

    static StandardEnvironment env(Object[]... layers) {
        StandardEnvironment env = new StandardEnvironment();
        for (Object[] l : layers) {                                     // the first given is the strongest
            env.getPropertySources().addBefore("systemEnvironment", new MapPropertySource((String) l[0], (Map<String, Object>) l[1]));
        }
        return env;
    }

    @Test
    void theLocalFileWinsOverTheEngineFileWhichWinsOverTheShippedCopy() {
        EngineCatalogue c = EngineCatalogue.load();
        var s = new EngineSwitches(c, env(new Object[] {FILE, Map.of("drishti.engines.var.enabled", "false")},
                new Object[] {SHIPPED, Map.of("drishti.engines.var.enabled", "true")}));
        assertThat(s.of("var")).isEqualTo(new EngineSwitches.Switch(false, "engine file", null, false));
        assertThat(s.warnings()).isEmpty();
        s = new EngineSwitches(c, env(new Object[] {LOCAL, Map.of("drishti.engines.var.enabled", "true")},
                new Object[] {FILE, Map.of("drishti.engines.var.enabled", "false")}));
        assertThat(s.of("var")).isEqualTo(new EngineSwitches.Switch(true, "local file", null, true));
        s = new EngineSwitches(c, env(new Object[] {SHIPPED, Map.of("drishti.engines.var.enabled", "true")}));
        assertThat(s.of("var").source()).isEqualTo("shipped copy");
        assertThat(s.of("var").overridden()).isFalse();
    }

    @Test
    void anOldSwitchNameStillWorksWithOneWarning() {
        EngineCatalogue c = EngineCatalogue.load();
        var s = new EngineSwitches(c, env(new Object[] {LOCAL, Map.of("drishti.var.enabled", "false", "drishti.frtb.enabled", "false")},
                new Object[] {FILE, Map.of("drishti.engines.var.enabled", "true")}));
        assertThat(s.of("var")).isEqualTo(new EngineSwitches.Switch(false, "local file (old name drishti.var.enabled)", "drishti.var.enabled", true));
        assertThat(s.of("frtb-sa").on()).isFalse();
        assertThat(s.warnings()).containsExactly(
                "drishti.var.enabled is the old name of drishti.engines.var.enabled (set in local file); it still works. Move it to config/engines/var.yaml, or use Admin -> Engines (DRS-1024).",
                "drishti.frtb.enabled is the old name of drishti.engines.frtb-sa.enabled (set in local file); it still works. Move it to config/engines/frtb-sa.yaml, or use Admin -> Engines (DRS-1024).");
    }

    @Test
    void theInMemoryLakeIsOnWhenItsStoresAreConfiguredInTheOldPlace() {
        EngineCatalogue c = EngineCatalogue.load();
        var s = new EngineSwitches(c, env(new Object[] {LOCAL, Map.of("drishti.lake.memory.stores.news.max-mb", "512")},
                new Object[] {SHIPPED, Map.of("drishti.engines.memory-lake.enabled", "false")}));
        assertThat(s.of("memory-lake").on()).isTrue();
        assertThat(s.of("memory-lake").source()).isEqualTo("stores configured in local file (the old place)");
        s = new EngineSwitches(c, env(new Object[] {SHIPPED, Map.of("drishti.engines.memory-lake.enabled", "false")}));
        assertThat(s.of("memory-lake").on()).isFalse();
    }

    @Test
    void theWriteKeepsEveryCommentAndKeyAndTheVariableAndWritesChanged() throws Exception {
        EngineCatalogue.Entry frtb = EngineCatalogue.load().get("frtb-sa").orElseThrow();
        EngineFiles files = new EngineFiles(dir, n -> null);
        String[] sums = files.write(frtb, false, "r.okafor", "2026-11-02T09:12:00Z", "market-risk \"capital\" moves to node var-1", null);
        assertThat(sums[0]).as("no file before").isEmpty();
        List<String> shipped = EngineFiles.shippedLines(frtb);
        List<String> written = Files.readAllLines(dir.resolve("frtb-sa.yaml"), StandardCharsets.UTF_8);
        assertThat(written).hasSameSizeAs(shipped);
        for (int i = 0; i < shipped.size(); i++) {
            if (shipped.get(i).startsWith("      enabled:")) {
                assertThat(written.get(i)).startsWith("      enabled: ${DRISHTI_FRTB_ENABLED:false}   # run FRTB-SA on this node");
            } else if (shipped.get(i).startsWith("      changed:")) {
                assertThat(written.get(i)).isEqualTo("      changed: { by: \"r.okafor\", at: \"2026-11-02T09:12:00Z\", reason: \"market-risk \\\"capital\\\" moves to node var-1\" }"
                        + "   # the last change made from Admin -> Engines or drishti.py (written by the server)");
            } else {
                assertThat(written.get(i)).as("line %d kept", i).isEqualTo(shipped.get(i));
            }
        }
        assertThat(files.enabledInFile(frtb)).isFalse();
        assertThat(new EngineFiles(dir, n -> "DRISHTI_FRTB_ENABLED".equals(n) ? "true" : null).enabledInFile(frtb)).as("the variable wins").isTrue();
        try (var left = Files.list(dir)) {
            assertThat(left.map(p -> p.getFileName().toString()).toList()).as("no temporary file left").containsExactly("frtb-sa.yaml");
        }
        String now = files.checksum(frtb);
        assertThat(now).isEqualTo(sums[1]);
        files.write(frtb, true, "ann", "t", "back", now);
        Files.writeString(dir.resolve("frtb-sa.yaml"), Files.readString(dir.resolve("frtb-sa.yaml")) + "# a note by hand\n");
        assertThatThrownBy(() -> files.write(frtb, false, "ann", "t", "again", now)).isInstanceOf(DrishtiException.class)
                .extracting(e -> ((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.ENGINE_FILE_CONFLICT);
    }

    @Test
    void aFolderThatCannotBeWrittenIsRefusedNamingIt() throws Exception {
        Path file = dir.resolve("not-a-folder");
        Files.writeString(file, "x");
        EngineFiles files = new EngineFiles(file, n -> null);
        assertThatThrownBy(() -> files.write(EngineCatalogue.load().get("var").orElseThrow(), false, "a", "t", "r", null))
                .hasMessageContaining("DRS-1023").hasMessageContaining("DRISHTI_ENGINES_DIR");
    }
}
