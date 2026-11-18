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

import com.ash.drishti.module.conformance.ModuleConformance;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.FirstParty;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.ModuleFolder;
import com.ash.drishti.testkit.ModuleFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module tool, {@link ModuleCli}: a module generated from the SDK's archetype, compiled, packed by the one packager
 * ({@code ModulePack}, as {@code module pack} runs it) and verified by the command, with exit codes and plain words for a good package, a changed file, a wrong
 * kernel range, a wrong kernel build, a module that breaks the rules and a first-party allowance.
 */
class ModuleCliTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
    private final PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);

    private int cli(String... args) {
        out.reset();
        err.reset();
        return ModuleCli.run(args, o, e);
    }

    private String said() {
        return out + "\n" + err;
    }

    private Path generate(String id) {
        return ModuleFixtures.generate(Path.of(System.getProperty("drishti.sdk.archetype")), id, tmp.resolve(id));
    }

    private Path jar(Path project, String id) {
        return ModuleFixtures.jarOf(project.resolve("java/" + id + "/src/main/java"), null, tmp.resolve(id + "-build"), tmp.resolve(id + "-build/" + id + "-0.1.0.jar"));
    }

    private Path pack(Path project, String id) {
        return ModuleFixtures.pack(project, List.of(jar(project, id)), tmp.resolve("dist-" + id));
    }

    @Test
    void aGoodPackagePassesTheFourteenChecksAndTheJsonAnswersBoth() throws Exception {
        Path project = generate("hello");
        Path dmod = pack(project, "hello");
        assertThat(dmod.getFileName()).hasToString("hello-0.1.0.dmod");
        assertThat(project.resolve("META-INF")).doesNotExist();                        // nothing written into the sources

        assertThat(cli("module", "verify", dmod.toString())).as(said()).isZero();
        assertThat(out.toString()).contains("verified hello 0.1.0");
        assertThat(cli("module", "verify", dmod.toString(), "--conformance", "--project", project.toString())).as(said()).isZero();
        assertThat(out.toString()).contains("C1 No kernel internals: passed", "C14 Absent everywhere: passed", "all 14 conformance checks passed")
                .doesNotContain("C15");

        assertThat(cli("module", "verify", dmod.toString(), "--conformance", "--json")).as(said()).isZero();
        JsonNode j = new ObjectMapper().readTree(out.toString());
        assertThat(j.path("verified").asBoolean()).isTrue();
        assertThat(j.path("module").asText()).isEqualTo("hello");
        assertThat(j.path("checks")).hasSize(14);

        assertThat(cli("module", "inspect", dmod.toString())).as(said()).isZero();
        assertThat(out.toString()).contains("hello 0.1.0", "every file matches META-INF/DMOD.MF");

        // a folder is verified for its manifest only; its project's id and version must be the manifest's
        assertThat(cli("module", "verify", project.toString(), "--manifest-only", "--id", "hello", "--version", "0.1.0")).as(said()).isZero();
        assertThat(cli("module", "verify", project.toString(), "--manifest-only", "--version", "0.2.0")).isEqualTo(1);
        assertThat(err.toString()).contains("module.yaml says version 0.1.0 but the project is 0.2.0: make them the same");
        assertThat(cli("module", "verify", project.toString())).isEqualTo(2);              // a folder is packed first
    }

    @Test
    void aChangedFileAWrongKernelRangeAndBadUsageAreRefusedInWords() throws Exception {
        Path project = generate("tampered");
        Path dmod = pack(project, "tampered");
        Path changed = tmp.resolve("changed.dmod");
        Files.copy(dmod, changed);
        try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + changed.toUri()), Map.of())) {
            Files.writeString(zip.getPath("docs/guide.md"), "# someone changed this after the package was made\n");
        }
        assertThat(cli("module", "verify", changed.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("refused", "docs/guide.md");

        Path other = generate("future");
        String manifest = Files.readString(other.resolve("module.yaml"));
        Files.writeString(other.resolve("module.yaml"), manifest.replaceAll("(?m)^kernel:.*$", "kernel: \">=9.0 <10\""));
        Path future = ModuleFixtures.pack(other, List.of(jar(other, "future")), tmp.resolve("dist-future"));
        assertThat(cli("module", "verify", future.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("V3", "built for kernel API >=9.0 <10");

        assertThat(cli("module")).isEqualTo(2);
        assertThat(cli("module", "verify", dmod.toString(), "--bogus")).isEqualTo(2);
        assertThat(cli("module", "verify", tmp.resolve("nothing.dmod").toString())).isEqualTo(2);
        assertThat(cli("module", "pack", dmod.toString())).as("pack takes the module's folder, not a package").isEqualTo(1);
        assertThat(err.toString()).contains("is not a folder: pack takes the module's folder");
    }

    @Test
    void theVisualCommandAddsAKindAndThePackageStillPasses() throws Exception {
        Path project = generate("tracks");
        assertThat(cli("module", "visual", project.toString(), "--id", "tracks", "--name", "gene-track", "--title", "Gene track")).as(said()).isZero();
        assertThat(Files.readString(project.resolve("console/plugin.yaml"))).contains("visuals:", "kind: tracks.gene-track", "library: drishti-tracks");
        assertThat(cli("module", "visual", project.toString(), "--id", "tracks", "--name", "gene-track")).isEqualTo(1);
        assertThat(err.toString()).contains("declared already");
        Path dmod = pack(project, "tracks");
        assertThat(cli("module", "verify", dmod.toString(), "--conformance", "--project", project.toString())).as(said()).isZero();
    }

    @Test
    void aModuleThatBreaksTheRulesFailsTheChecksInPlainWords() throws Exception {
        Path project = generate("rogue");
        Path java = project.resolve("java/rogue/src/main/java/com/example/rogue");
        Files.writeString(java.resolve("Leaky.java"), """
                /*
                 * Copyright (c) the publisher of rogue. All rights reserved.
                 */
                package com.example.rogue;
                public class Leaky {
                    public static java.util.Map<String, String> lastAnswer = new java.util.HashMap<>();
                    public String internals() { return com.ash.drishti.module.runtime.Dmod.fileName("a", "b"); }
                }
                """);
        Files.writeString(project.resolve("console/pages/hello.html"), "<table><tr><td onclick=\"go()\">x</td></tr></table><script>go()</script>\n");
        Files.writeString(project.resolve("console/static/drishti_module.css"), "/* Copyright x */\n.a { color: #ff0000; }\n");
        Files.writeString(project.resolve("console/static/rogue.js"), "import x from 'https://cdn.example.com/lib.js';\n");
        Files.writeString(project.resolve("data/source.yaml"), "connector: risklake\npassword: hunter2\n");
        // a pack's connector templates travel with it, but a host is the site's: a ${...} placeholder, never a value
        Files.writeString(project.resolve("packs/rogue-pack/pack.yaml"), "pack: rogue-pack\nconnectors:\n  rogue-store:\n    plugin: postgres\n    settings:\n"
                + "      host: db.internal.example\n");
        Files.delete(project.resolve("packs/rogue-pack/contracts/rogue-greeting.yaml"));
        Files.delete(project.resolve("docs/tutorial.md"));
        Path dmod = pack(project, "rogue");

        assertThat(cli("module", "verify", dmod.toString(), "--conformance", "--project", project.toString())).isEqualTo(1);
        assertThat(out.toString()).contains("checks failed");
        Map<String, ModuleConformance.Check> byId = new LinkedHashMap<>();
        ModuleConformance.run(dmod, project, tmp.resolve("rogue-conformance")).forEach(c -> byId.put(c.id(), c));
        assertThat(byId.keySet()).containsExactly("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11", "C12", "C13", "C14");
        assertThat(byId.get("C1").problems()).anyMatch(p -> p.contains("com.example.rogue.Leaky uses com.ash.drishti.module.runtime.Dmod, a kernel internal"));
        assertThat(byId.get("C2").problems()).anyMatch(p -> p.startsWith("console/pages/hello.html draws its own <table>"))
                .anyMatch(p -> p.contains("imports https://cdn.example.com/lib.js"));
        assertThat(byId.get("C3").problems()).anyMatch(p -> p.contains("#ff0000") && p.contains("theme tokens"));
        assertThat(byId.get("C4").problems()).anyMatch(p -> p.contains("console/static/rogue.js must be named drishti_rogue.js"));
        assertThat(byId.get("C5").problems()).anyMatch(p -> p.contains("inline <script>")).anyMatch(p -> p.contains("onclick="));
        assertThat(byId.get("C7").problems()).anyMatch(p -> p.contains("docs/tutorial.md, which is not in the package"));
        assertThat(byId.get("C8").problems()).anyMatch(p -> p.startsWith("kind rogue-greeting has no contract"));
        assertThat(byId.get("C9").problems()).anyMatch(p -> p.startsWith("data/source.yaml has \"connector:\""))
                .anyMatch(p -> p.equals("packs/rogue-pack/pack.yaml: `host` is the site's: write a ${...} placeholder (with a default if any), never a value"));
        assertThat(byId.get("C10").problems()).anyMatch(p -> p.startsWith("console/static/rogue.js has no licence header"));
        assertThat(byId.get("C12").problems()).anyMatch(p -> p.startsWith("com.example.rogue.Leaky.lastAnswer is a static field that is not final"));
        for (String passing : List.of("C6", "C11", "C13", "C14")) {
            assertThat(byId.get(passing).status()).as(byId.get(passing).line()).isEqualTo(ModuleConformance.Status.PASS);
        }
    }

    /** KERNEL_AND_MODULES.md section 3.3: an allowance is honoured only for a verified first-party module on its own kernel build. */
    @Test
    void anAllowanceIsHonouredOnlyForAVerifiedFirstPartyModuleOnItsOwnKernelBuild() throws Exception {
        Path project = generate("insider");
        Path java = project.resolve("java/insider/src/main/java/com/example/insider");
        Files.writeString(java.resolve("Inside.java"), """
                /*
                 * Copyright (c) the publisher of insider. All rights reserved.
                 */
                package com.example.insider;
                public final class Inside {
                    public String name() { return com.ash.drishti.module.runtime.Dmod.fileName("a", "1.0.0"); }
                }
                """);
        Files.writeString(project.resolve("kernel-internals.txt"), "# the kernel packages insider reaches\ncom.ash.drishti.module.runtime\n");
        String manifest = Files.readString(project.resolve("module.yaml"));
        Files.writeString(project.resolve("module.yaml"), manifest + "origin: first-party\nkernel-internals: kernel-internals.txt\n");
        Path jar = jar(project, "insider");
        Path dmod = ModuleFixtures.pack(project, List.of(jar), tmp.resolve("d1"), "9.9.9+build-1");
        Path unpacked = tmp.resolve("insider-unpacked");
        Dmod.unpack(dmod, unpacked);
        DmodManifest packed = ModuleFolder.manifest(unpacked);
        assertThat(packed.kernelInternals()).containsExactly("com.ash.drishti.module.runtime");
        assertThat(packed.kernelBuild()).isEqualTo("9.9.9+build-1");
        String row = "modules:\n  - {id: insider, version: 0.1.0, sha256: " + FirstParty.listHash(unpacked) + "}\n";
        Path list = tmp.resolve("first-party.yaml");
        Files.writeString(list, row);

        // verified first-party on its own build: honoured
        assertThat(cli("module", "verify", dmod.toString(), "--conformance", "--first-party", list.toString(), "--kernel-build", "9.9.9+build-1",
                "--project", project.toString())).as(said()).isZero();
        assertThat(out.toString()).contains("origin: first-party (verified", "all 14 conformance checks passed");

        // not in the list: the allowance is refused
        Path none = tmp.resolve("none.yaml");
        Files.writeString(none, "modules: []\n");
        assertThat(cli("module", "verify", dmod.toString(), "--first-party", none.toString(), "--kernel-build", "9.9.9+build-1")).isEqualTo(1);
        assertThat(err.toString()).contains("only a verified first-party module may");

        // the wrong kernel build: the words that say what to do
        String words = "insider 0.1.0: built for kernel 9.9.9+build-1, this is 9.9.9+build-2: install the matching module";
        assertThat(cli("module", "verify", dmod.toString(), "--first-party", list.toString(), "--kernel-build", "9.9.9+build-2")).isEqualTo(1);
        assertThat(err.toString()).contains("V3", words);
        assertThat(cli("module", "inspect", dmod.toString(), "--first-party", list.toString(), "--kernel-build", "9.9.9+build-2")).as(said()).isZero();
        assertThat(out.toString()).contains("origin: first-party (verified: its id and hash list are in first-party.yaml)",
                "allowance: 1 kernel packages, built for kernel 9.9.9+build-1 (refused: " + words + ")");
        KernelFacts right = KernelFacts.ofKernel().withBuild("9.9.9+build-1", FirstParty.read(list));
        assertThat(ModuleConformance.run(dmod, project, tmp.resolve("c-ok"), right)).allSatisfy(c -> assertThat(c.status()).as(c.line())
                .isEqualTo(ModuleConformance.Status.PASS));
    }
}
