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
package com.ash.drishti.module.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one packager is Python ({@code tools/modulepack.py}); the one verifier is Java ({@link Dmod}). A package the packager makes from a
 * fixture folder is unpacked and verified by {@link Dmod#verify}, and the manifest reads: the two sides agree on the format. Skipped
 * where no Python is on the path or the checkout has no tools folder (a test of the runtime alone).
 */
class DmodPythonPackTest {

    @TempDir
    Path tmp;

    @Test
    void aPackageThePythonPackagerMakesIsAcceptedByDmodVerify() throws Exception {
        Path packager = findPackager();
        assumeTrue(packager != null, "tools/modulepack.py is not above the working folder");
        String python = python();
        assumeTrue(python != null, "no python on the path");

        Path module = tmp.resolve("modules/greet");
        Dmod.write(module.resolve("module.yaml"), "format: 1\nid: greet\nversion: 0.1.0\ntitle: Greet\n");
        Dmod.write(module.resolve("packs/a.yaml"), "a: 1\n");
        Dmod.write(module.resolve("docs/z.md"), "# z\n");
        Path out = tmp.resolve("out");

        Process p = new ProcessBuilder(python, packager.toString(), "pack", module.toString(), "--out", out.toString())
                .redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(said).isZero();

        Path dmod = out.resolve(Dmod.fileName("greet", "0.1.0"));
        assertThat(dmod).exists();
        Path unpacked = tmp.resolve("unpacked");
        Files.createDirectories(unpacked);
        Dmod.unpack(dmod, unpacked);
        List<String> notes = Dmod.verify(unpacked, dmod, "greet");          // V2: a refusal would throw
        assertThat(notes).isNotEmpty();
        assertThat(Dmod.files(unpacked)).containsExactly("docs/z.md", "module.yaml", "packs/a.yaml");
        assertThat(DmodManifest.read(Files.newInputStream(unpacked.resolve("module.yaml")), "greet").id()).isEqualTo("greet");
    }

    private static Path findPackager() {
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            Path f = d.resolve("tools/modulepack.py");
            if (Files.isRegularFile(f)) {
                return f;
            }
        }
        return null;
    }

    private static String python() {
        for (String name : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(name, "-c", "import yaml").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) {
                    return name;
                }
            } catch (IOException | InterruptedException e) {
                // try the next name
            }
        }
        return null;
    }
}
