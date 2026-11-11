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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SamplePolicyTest {

    @TempDir Path dir;

    private void pack(String name, boolean sample, String connector) throws Exception {
        Path p = Files.createDirectories(dir.resolve("packs").resolve(name));
        Files.writeString(p.resolve("pack.yaml"), "pack: " + name + "\n" + (sample ? "sample: true\n" : "") + "kinds: [" + name + "-k]\n"
                + "connector-templates:\n  " + connector + ":\n    plugin: file\n");
    }

    @Test
    void thePropertyDecidesUntilAnAdministratorSavesAChoiceAndTheChoiceSurvivesARestart() {
        Path file = dir.resolve("mode");
        SamplePolicy p = new SamplePolicy("developers", file);
        assertThat(p.mode()).isEqualTo("developers");
        assertThat(p.overridden()).isFalse();
        p.set("hidden");
        assertThat(p.mode()).isEqualTo("hidden");
        assertThat(new SamplePolicy("visible", file).mode()).isEqualTo("hidden");                // read again at the next start
        p.clear();
        assertThat(p.mode()).isEqualTo("developers");
        assertThat(new SamplePolicy("nonsense", file).mode()).isEqualTo("visible");              // an unknown property falls back to visible
        assertThatThrownBy(() -> p.set("sometimes")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hiddenLeavesSamplePacksAndTheirOwnConnectorsOut() throws Exception {
        pack("real", false, "shared");
        pack("demo", true, "shared");
        pack("toy", true, "toy-feed");
        List<Path> dirs = List.of(dir.resolve("packs"));
        PackLoader loader = new PackLoader();
        SamplePolicy visible = new SamplePolicy("visible", null);
        SamplePolicy hidden = new SamplePolicy("hidden", null);
        assertThat(visible.select(dirs, List.of("real", "demo", "toy"), loader)).containsExactly("real", "demo", "toy");
        assertThat(hidden.select(dirs, List.of("real", "demo", "toy"), loader)).containsExactly("real");
        assertThat(SamplePolicy.sampleOnlyConnectors(dirs, loader)).containsExactly("toy-feed");   // 'shared' is also a real pack's
        assertThat(loader.readOne(dirs, "demo").sample()).isTrue();
        assertThat(loader.readOne(dirs, "real").sample()).isFalse();
    }
}
