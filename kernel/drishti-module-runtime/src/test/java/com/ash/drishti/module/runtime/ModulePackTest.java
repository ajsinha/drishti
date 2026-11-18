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
package com.ash.drishti.module.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The one packager, {@link ModulePack}: the same input twice gives the same bytes; only the layout's names are packed and a stray is said;
 * module.yaml first and stored, the hash list last; the jars by the names module.yaml gives; the allowance stamped with the kernel build;
 * content.yaml's copy, trim and its guard on what a pack serves; first-party.yaml; the freshness rule (R2: by content, never by time; a
 * stamp for another kernel); the install into a modules folder (a .part file and one replace, an older version removed, no other module);
 * and the verifier ({@link Dmod#verify}) accepts what the packer makes.
 */
class ModulePackTest {

    private static final String MODULE_YAML = "format: 1\nid: greet\nversion: 0.1.0\ntitle: Greet\n";

    @TempDir
    Path tmp;

    private Path module(String extra) throws IOException {
        return module(tmp.resolve("modules"), "greet", "0.1.0", extra);
    }

    private static Path module(Path modules, String id, String version, String extra) throws IOException {
        Path m = modules.resolve(id);
        Files.createDirectories(m.resolve("packs"));
        Files.createDirectories(m.resolve("docs"));
        Files.writeString(m.resolve("module.yaml"), "format: 1\nid: " + id + "\nversion: " + version + "\ntitle: Greet\n" + extra);
        Files.writeString(m.resolve("packs/a.yaml"), "a: 1\n");
        Files.writeString(m.resolve("docs/z.md"), "# z\n");
        return m;
    }

    private static List<String> names(Path dmod) throws IOException {
        try (ZipFile z = new ZipFile(dmod.toFile())) {
            return z.stream().map(ZipEntry::getName).toList();
        }
    }

    private static String read(Path dmod, String name) throws IOException {
        try (ZipFile z = new ZipFile(dmod.toFile()); InputStream in = z.getInputStream(z.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path pack(Path m) {
        return ModulePack.pack(m, List.of(), tmp.resolve("o"), null).dmod();
    }

    @Test
    void theSameInputTwiceGivesTheSameBytesAndEveryEntryTheSameTime() throws IOException {
        Path m = module("");
        Path a = ModulePack.pack(m, List.of(), tmp.resolve("o1"), null).dmod();
        Path b = ModulePack.pack(m, List.of(), tmp.resolve("o2"), null).dmod();
        assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
        assertThat(a.getFileName().toString()).isEqualTo("greet-0.1.0.dmod");
        assertThat(Files.readString(a.resolveSibling("greet-0.1.0.dmod.sha256"))).isEqualTo(ModulePack.sha256(Files.readAllBytes(a)) + "  greet-0.1.0.dmod\n");
        try (ZipFile z = new ZipFile(a.toFile())) {
            assertThat(z.stream().map(e -> e.getTimeLocal().toString()).distinct().toList()).containsExactly("1980-01-01T00:00");
        }
        assertThat(tmp.resolve("o1")).isDirectoryNotContaining("glob:**.part");
    }

    @Test
    void moduleYamlIsFirstAndStoredAndTheHashListIsLast() throws IOException {
        Path dmod = pack(module(""));
        assertThat(names(dmod)).containsExactly("module.yaml", "docs/z.md", "packs/a.yaml", "META-INF/DMOD.MF");
        try (ZipFile z = new ZipFile(dmod.toFile())) {
            assertThat(z.getEntry("module.yaml").getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(z.getEntry("docs/z.md").getMethod()).isEqualTo(ZipEntry.DEFLATED);
        }
    }

    @Test
    void theHashListHoldsEveryEntryButItself() throws IOException {
        Path dmod = pack(module(""));
        List<String> lines = read(dmod, Dmod.HASHES).lines().toList();
        assertThat(lines.get(0)).startsWith("# DMOD.MF format 1");
        List<String> listed = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] p = l.split(" {2}", 2);
            listed.add(p[1]);
            try (ZipFile z = new ZipFile(dmod.toFile()); InputStream in = z.getInputStream(z.getEntry(p[1]))) {
                assertThat(Dmod.sha256(in)).isEqualTo(p[0]);
            }
        }
        List<String> all = new ArrayList<>(names(dmod));
        all.remove(Dmod.HASHES);
        assertThat(listed).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void aNameOutsideTheLayoutIsLeftOutAndSaid() throws IOException {
        Path m = module("");
        Files.createDirectories(m.resolve("old-build"));
        Files.writeString(m.resolve("old-build/x.txt"), "x");
        Files.writeString(m.resolve("pom.xml"), "<project/>");                  // builds the module: allowed, never packed
        ModulePack.Packed p = ModulePack.pack(m, List.of(), tmp.resolve("o"), null);
        assertThat(names(p.dmod())).noneMatch(n -> n.startsWith("old-build") || n.startsWith("pom.xml"));
        assertThat(p.notes()).hasSize(1);
        assertThat(p.notes().get(0)).contains("old-build", "not part of the module layout");
    }

    @Test
    void onlyJarsDirectlyInJavaArePackedAndPycacheNever() throws IOException {
        Path m = module("");
        Files.createDirectories(m.resolve("java/proj/src"));
        Files.writeString(m.resolve("java/proj/src/A.java"), "class A {}");
        Files.writeString(m.resolve("java/greet.jar"), "PK-not-really");
        Files.createDirectories(m.resolve("python/__pycache__"));
        Files.write(m.resolve("python/__pycache__/x.pyc"), new byte[] {0});
        Files.writeString(m.resolve("python/x.py"), "x = 1\n");
        Files.write(m.resolve("python/y.pyc"), new byte[] {0});
        List<String> names = names(pack(m));
        assertThat(names).contains("java/greet.jar", "python/x.py");
        assertThat(names).noneMatch(n -> n.contains("A.java") || n.contains("__pycache__") || n.endsWith(".pyc"));
    }

    @Test
    void aGivenJarIsPlacedByTheNameModuleYamlGives() throws IOException {
        Path m = module("java:\n  jars:\n  - greet.jar\n  - more.jar\n  lib:\n  - dep.jar\n");
        List<Path> jars = new ArrayList<>();
        for (String n : List.of("built-1.0.jar", "more.jar", "dep.jar")) {
            jars.add(Files.writeString(tmp.resolve(n), n));
        }
        assertThat(names(ModulePack.pack(m, jars, tmp.resolve("o"), null).dmod())).contains("java/greet.jar", "java/more.jar", "lib/dep.jar");
    }

    @Test
    void aJarModuleYamlDoesNotNameIsRefusedAndANamedOneMustBeThere() throws IOException {
        Path m = module("java:\n  jars:\n  - greet.jar\n");
        Path own = Files.writeString(tmp.resolve("own.jar"), "1");
        Path other = Files.writeString(tmp.resolve("other.jar"), "2");
        assertThatThrownBy(() -> ModulePack.pack(m, List.of(own, other), tmp.resolve("o"), null)).isInstanceOf(ModulePack.PackException.class)
                .hasMessageContaining("in neither java.jars nor java.lib");
        assertThatThrownBy(() -> pack(m)).isInstanceOf(ModulePack.PackException.class).hasMessageContaining("neither in the folder nor given");
        Path plain = module(tmp.resolve("plain"), "plain", "1.0.0", "");
        assertThatThrownBy(() -> ModulePack.pack(plain, List.of(own), tmp.resolve("o"), null)).hasMessageContaining("has no java: section");
    }

    @Test
    void theAllowanceIsStampedWithTheKernelBuildAndRefusedWithoutOne() throws IOException {
        Path m = module("kernel-internals: java/kernel-internals.txt\n");
        Files.createDirectories(m.resolve("java"));
        Files.writeString(m.resolve("java/kernel-internals.txt"), "# the allowance\ncom.ash.one  # why\ncom.ash.two\n");
        Path dmod = ModulePack.pack(m, List.of(), tmp.resolve("o"), "2.9.11.i9+20261116").dmod();
        String text = read(dmod, "module.yaml");
        assertThat(text).contains("kernel-internals:\n- com.ash.one\n- com.ash.two\n").endsWith("kernel-build: '2.9.11.i9+20261116'\n");
        assertThat(ModulePack.stampOf(dmod)).isEqualTo("2.9.11.i9+20261116");
        // this runtime alone has no server build on its class path: there is no build to stamp
        assertThat(KernelFacts.thisBuild()).isNull();
        assertThatThrownBy(() -> ModulePack.pack(m, List.of(), tmp.resolve("o2"), null)).hasMessageContaining("--kernel-build");
    }

    @Test
    void contentYamlBringsContentInAndTrimsAndIsNotPacked() throws IOException {
        Path m = module("");
        Files.createDirectories(tmp.resolve("shared/keep"));
        Files.writeString(tmp.resolve("shared/keep/k.yaml"), "k: 1\n");
        Files.createDirectories(tmp.resolve("shared/skip"));
        Files.writeString(tmp.resolve("shared/skip/s.yaml"), "s: 1\n");
        Files.writeString(m.resolve("content.yaml"), "copy:\n- {from: ../../shared, to: data/shared, leave-out: [skip]}\ntrim:\n- {path: packs, leave-out: [a.yaml]}\n");
        List<String> names = names(pack(m));
        assertThat(names).contains("data/shared/keep/k.yaml");
        assertThat(names).doesNotContain("content.yaml", "packs/a.yaml").noneMatch(n -> n.contains("skip"));
    }

    @Test
    void aRestrictedSampleIsLeftOutAndItsKindTrimmedFromTheCatalogue() throws IOException {
        Path m = module("");
        Path samples = Files.createDirectories(m.resolve("packs/p/samples"));
        Files.writeString(samples.resolve("x.jsonl"), "{\"id\": 1}\n");
        Files.writeString(samples.resolve("y.jsonl"), "{\"id\": 2}\n");
        Files.writeString(samples.resolve("catalog.jsonl"), "{\"kind\": \"x\", \"id\": 1}\n{\"kind\": \"y\", \"id\": 2}\n\n");
        Files.writeString(m.resolve("content.yaml"), "restricted:\n- {path: packs/p/samples/x.jsonl, reason: a licence}\n");
        Path dmod = pack(m);
        assertThat(names(dmod)).contains("packs/p/samples/y.jsonl").doesNotContain("packs/p/samples/x.jsonl");
        assertThat(read(dmod, "packs/p/samples/catalog.jsonl")).isEqualTo("{\"kind\": \"y\", \"id\": 2}\n");
        assertThat(ModulePack.restrictedPaths(m)).containsExactly("packs/p/samples/x.jsonl");
        Files.writeString(m.resolve("content.yaml"), "restricted:\n- {path: packs/p/samples/nope.jsonl, reason: a licence}\n");
        assertThatThrownBy(() -> pack(m)).hasMessageContaining("is not a file of the package's layout");
        Files.writeString(m.resolve("content.yaml"), "restricted:\n- {path: packs/p/samples/x.jsonl}\n");
        assertThatThrownBy(() -> pack(m)).hasMessageContaining("needs a `path` of a file in the module and a `reason`");
    }

    static Stream<String> served() {
        return ModulePack.SERVED.stream().sorted();
    }

    @ParameterizedTest
    @MethodSource("served")
    void contentYamlNeverLeavesOutWhatAPackServes(String served) throws IOException {
        Path m = module("");
        Files.writeString(m.resolve("content.yaml"), "trim:\n- {path: packs, leave-out: [tests, " + served + "]}\n");
        assertThatThrownBy(() -> pack(m)).isInstanceOf(ModulePack.PackException.class).hasMessageContaining("serves at run time");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noShippedModuleLeavesOutWhatAPackServes() throws IOException {
        String root = System.getProperty("drishti.repo.root");
        assumeTrue(root != null && Files.isDirectory(Path.of(root, "modules")), "the repository's modules/ (drishti.repo.root)");
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (Stream<Path> s = Files.list(Path.of(root, "modules"))) {
            for (Path f : s.map(d -> d.resolve("content.yaml")).filter(Files::isRegularFile).sorted().toList()) {
                Map<String, Object> spec = yaml.readValue(f.toFile(), Map.class);
                List<Map<String, Object>> all = new ArrayList<>();
                for (String k : List.of("trim", "copy")) {
                    all.addAll((List<Map<String, Object>>) spec.getOrDefault(k, List.of()));
                }
                for (Map<String, Object> e : all) {
                    for (Object x : (List<Object>) e.getOrDefault("leave-out", List.of())) {
                        String[] p = String.valueOf(x).replaceAll("^/+|/+$", "").split("/");
                        assertThat(ModulePack.SERVED).as(f + ": " + e).doesNotContain(p[p.length - 1]);
                    }
                }
            }
        }
    }

    @Test
    void firstPartyListsTheSha256OfEachHashListUnderTheLicenceHeader() throws IOException {
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/license-header.txt"), "Project X\n\nAll rights reserved.\n");
        Path d = ModulePack.pack(module(""), List.of(), tmp.resolve("mods"), null).dmod();
        Path out = ModulePack.firstParty(tmp.resolve("mods"), null);
        String sha;
        try (ZipFile z = new ZipFile(d.toFile()); InputStream in = z.getInputStream(z.getEntry(Dmod.HASHES))) {
            sha = Dmod.sha256(in);
        }
        assertThat(out.getFileName().toString()).isEqualTo("first-party.yaml");
        assertThat(Files.readString(out)).startsWith("# Project X\n#\n# All rights reserved.\n\n# The product's own modules")
                .endsWith("modules:\n  - {id: greet, version: 0.1.0, sha256: " + sha + "}\n");
    }

    @Test
    void aFolderWithoutModuleYamlIsRefused() throws IOException {
        Files.createDirectories(tmp.resolve("empty"));
        assertThatThrownBy(() -> ModulePack.pack(tmp.resolve("empty"), List.of(), tmp.resolve("o"), null)).hasMessageContaining("module.yaml");
        assertThatThrownBy(() -> ModulePack.pack(tmp.resolve("nothing"), List.of(), tmp.resolve("o"), null)).hasMessageContaining("is not a folder");
    }

    @Test
    void aPackageThePackerMakesIsAcceptedByTheVerifier() throws IOException {
        Path m = tmp.resolve("modules/greet");
        Dmod.write(m.resolve("module.yaml"), """
                format: 1
                id: greet
                version: 0.1.0
                title: Test greet
                about: A module built in tests
                domain: testing
                publisher: {name: Drishti tests, id: drishti-tests}
                licence: {feature: greet}
                kernel: ">=0.4"
                rachana: ">=1.0"
                contracts: ">=1.0"
                plugin-api: ">=2.0"
                elements: ">=2.4 <3"
                """);
        Dmod.write(m.resolve("packs/a.yaml"), "a: 1\n");
        Dmod.write(m.resolve("docs/z.md"), "# z\n");
        Path dmod = pack(m);
        Path dir = unpacked(dmod);
        assertThat(Dmod.verify(dir, dmod, "greet")).isNotEmpty();          // V2: a refusal would throw
        assertThat(Dmod.files(dir)).containsExactly("docs/z.md", "module.yaml", "packs/a.yaml");
        assertThat(ModuleFolder.manifest(dir).id()).isEqualTo("greet");
    }

    private Path unpacked(Path dmod) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "unpacked");
        Dmod.unpack(dmod, dir);
        return dir;
    }

    // ------------------------------------------------------------------------------------------------------------------ R2: stale packages

    @Test
    void theDigestIsTheHashListOfThePackage() throws IOException {
        Path m = module("");
        Path dmod = pack(m);
        assertThat(ModulePack.digest(m, null)).isEqualTo(read(dmod, Dmod.HASHES));
        assertThat(ModulePack.stale(m, dmod)).isEmpty();
    }

    @Test
    void aSourceEditMakesThePackageStaleByContentAndNeverByTime() throws IOException {
        Path m = module("");
        Path dmod = pack(m);
        Path f = m.resolve("packs/a.yaml");
        Files.setLastModifiedTime(f, FileTime.fromMillis(1000));                 // a time alone is not a change
        assertThat(ModulePack.stale(m, dmod)).isEmpty();
        Files.writeString(f, "a: 2\n");
        assertThat(ModulePack.stale(m, dmod)).containsExactly("packs/a.yaml");
        Files.writeString(m.resolve("packs/new.yaml"), "n: 1\n");
        assertThat(ModulePack.stale(m, dmod)).containsExactly("packs/a.yaml", "packs/new.yaml");
        Files.delete(f);
        assertThat(ModulePack.stale(m, dmod)).containsExactly("packs/a.yaml", "packs/new.yaml");
    }

    @Test
    void theDigestAndTheFirstPartyListHoldNoTime() throws IOException {
        Path m = module("");
        ModulePack.pack(m, List.of(), tmp.resolve("out"), null);
        String text = ModulePack.digest(m, null) + Files.readString(ModulePack.firstParty(tmp.resolve("out"), null));
        assertThat(text).doesNotContainPattern("\\b20\\d\\d-\\d\\d-\\d\\d|\\b\\d\\d:\\d\\d:\\d\\d");
    }

    @Test
    void theKernelBuildOfAServerJarIsReadAndAForeignJarRefused() throws IOException {
        Path jar = serverJar(tmp.resolve("drishti-server-1.0-exec.jar"), "1.0", "2026-01-01T00\\:00\\:00Z");
        assertThat(ModulePack.kernelBuildOfJar(jar)).isEqualTo("1.0+2026-01-01T00:00:00Z");
        Path other = tmp.resolve("other.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(other))) {
            z.putNextEntry(new ZipEntry("x"));
            z.write('y');
        }
        assertThatThrownBy(() -> ModulePack.kernelBuildOfJar(other)).hasMessageContaining("holds no build-info.properties");
        assertThatThrownBy(() -> ModulePack.kernelBuildOfJar(tmp.resolve("none.jar"))).hasMessageContaining("is not a file");
    }

    private static Path serverJar(Path jar, String version, String time) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/build-info.properties"));
            z.write(("build.artifact=drishti-server\nbuild.version=" + version + "\nbuild.time=" + time + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    @Test
    void freshNamesAChangedFolderAFolderWithNoPackageAndAStampForAnotherKernel() throws IOException {
        Path modules = tmp.resolve("modules");
        Path m = module(modules, "greet", "0.1.0", "");
        assertThat(ModulePack.fresh(modules, null)).containsExactly("modules/greet has no package (greet-0.1.0.dmod): python tools/drishti.py build modules greet");
        install(m, modules);
        assertThat(ModulePack.fresh(modules, null)).isEmpty();
        Files.writeString(m.resolve("packs/a.yaml"), "a: 2\n");
        assertThat(ModulePack.fresh(modules, null)).containsExactly(
                "modules/greet changed since greet-0.1.0.dmod was built (packs/a.yaml): python tools/drishti.py build modules greet");

        Path k = module(tmp.resolve("k"), "insider", "1.0.0", "kernel-internals: [com.ash.one]\n");
        Files.move(k, modules.resolve("insider"));
        ModulePack.install(ModulePack.pack(modules.resolve("insider"), List.of(), tmp.resolve("kd"), "1.0+t1").dmod().getParent(), modules, Set.of("insider"));
        Path jar = serverJar(tmp.resolve("drishti-server-1.0-exec.jar"), "1.0", "t2");
        assertThat(ModulePack.fresh(modules, jar)).contains("insider-1.0.0.dmod was built for the kernel 1.0+t1 and the server jar is 1.0+t2: python tools/drishti.py build modules insider");
        assertThat(ModulePack.fresh(modules, serverJar(tmp.resolve("same.jar"), "1.0", "t1"))).noneMatch(l -> l.contains("insider"));
    }

    @Test
    void anInstallHoldsPackagesAndNoSourcesAndIsNeverStale() throws IOException {
        Path modules = Files.createDirectories(tmp.resolve("install/modules"));
        install(module(tmp.resolve("src"), "greet", "0.1.0", ""), modules);
        assertThat(ModulePack.fresh(modules, null)).isEmpty();
        assertThat(modules.resolve("first-party.yaml")).exists();
    }

    @Test
    void aNewerVersionReplacesTheOlderFileOfTheSameModuleAndNoOther() throws IOException {
        Path modules = tmp.resolve("modules");
        install(module(tmp.resolve("a"), "greet-more", "1.0.0", ""), modules);
        install(module(tmp.resolve("b"), "greet", "0.1.0", ""), modules);
        install(module(tmp.resolve("c"), "greet", "0.2.0", ""), modules);
        List<String> held;
        try (Stream<Path> s = Files.list(modules)) {
            held = new ArrayList<>(s.map(p -> p.getFileName().toString()).toList());
        }
        Collections.sort(held);
        assertThat(held).containsExactly("first-party.yaml", "greet-0.2.0.dmod", "greet-more-1.0.0.dmod");
        assertThat(Files.readString(modules.resolve("first-party.yaml"))).contains("{id: greet, version: 0.2.0", "{id: greet-more, version: 1.0.0");
    }

    @Test
    void installTakesOnlyTheNamedIdsAndRefusesAnUnknownOne() throws IOException {
        Path dmods = tmp.resolve("dmods");
        ModulePack.pack(module(tmp.resolve("a"), "one", "1.0.0", ""), List.of(), dmods, null);
        ModulePack.pack(module(tmp.resolve("b"), "two", "1.0.0", ""), List.of(), dmods, null);
        Path modules = tmp.resolve("modules");
        assertThat(ModulePack.install(dmods, modules, Set.of("two"))).containsExactly("two-1.0.0.dmod");
        assertThat(modules.resolve("one-1.0.0.dmod")).doesNotExist();
        assertThatThrownBy(() -> ModulePack.install(dmods, modules, Set.of("three"))).hasMessageContaining("no package of three");
        assertThat(ModulePack.install(dmods, modules, Set.of())).containsExactly("one-1.0.0.dmod", "two-1.0.0.dmod");
    }

    @Test
    void theServerJarGoesIntoLibAloneAndLibExtIsNeverTouched() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("home/lib/ext"));
        Files.writeString(lib.resolve("driver.jar"), "site");
        Files.writeString(lib.getParent().resolve("drishti-server-0.9-exec.jar"), "old");
        Path built = Files.writeString(Files.createDirectories(tmp.resolve("target")).resolve("drishti-server-1.0-exec.jar"), "new");
        Path in = ModulePack.installJar(built, lib.getParent());
        assertThat(in).hasContent("new");
        assertThat(lib.getParent().resolve("drishti-server-0.9-exec.jar")).doesNotExist();
        assertThat(lib.resolve("driver.jar")).hasContent("site");
        assertThat(ModulePack.installJar(in, lib.getParent())).isEqualTo(in);           // the jar already in lib: nothing to do
        assertThat(lib.getParent()).isDirectoryNotContaining("glob:**.part");
        // the version-free name IntelliJ's JAR Application launchers run: the same file as the installed jar, renewed with every install
        Path ide = lib.getParent().resolve(ModulePack.IDE_JAR);
        assertThat(Files.isSameFile(ide, in) || Files.readString(ide).equals("new")).isTrue();
        Path next = Files.writeString(tmp.resolve("target").resolve("drishti-server-1.1-exec.jar"), "newer");
        Path in2 = ModulePack.installJar(next, lib.getParent());
        assertThat(in).doesNotExist();
        assertThat(ide).hasContent("newer");
        assertThat(lib.getParent()).isDirectoryNotContaining("glob:**.part");
        try (var names = Files.list(lib.getParent())) {     // the tools' pattern (drishti-server-*-exec.jar) still finds exactly one jar
            assertThat(names.map(f -> f.getFileName().toString()).filter(n -> n.matches("drishti-server-.+-exec\\.jar")))
                    .containsExactly(in2.getFileName().toString());
        }
    }

    @Test
    void aStampedModuleOnAServerWithNoBuildSaysToStartTheJar() {
        // a server started from compiled classes (no build-info.properties) can never match a stamp: the words say what to start
        assertThat(FirstParty.otherBuild("banking 1.0.0", "9.9+t", null)).startsWith("banking 1.0.0: built for kernel 9.9+t, this is an unknown build")
                .contains("start the server from the jar").contains("drishti.py start server").doesNotContain("install the matching module");
        assertThat(FirstParty.otherBuild("banking 1.0.0", "9.9+t", "9.9+u"))
                .isEqualTo("banking 1.0.0: built for kernel 9.9+t, this is 9.9+u: install the matching module");
    }

    private void install(Path folder, Path modules) throws IOException {
        Path out = Files.createTempDirectory(tmp, "built");
        Path dmod = ModulePack.pack(folder, List.of(), out, null).dmod();
        Files.delete(dmod.resolveSibling(dmod.getFileName() + ".sha256"));
        ModulePack.install(out, modules, Set.of());
        assertThat(modules).isDirectoryNotContaining("glob:**.part");
    }
}
