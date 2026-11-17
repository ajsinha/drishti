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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ModuleBoot}: discovery of {@code .dmod} files in the modules folder, the hash-addressed cache, the sweep, the refusals in words,
 * the development switch and the leftovers of the removed deployment store. Guards G3, G4, G6 and G8 of the i7a build plan.
 */
class ModuleBootTest {

    @TempDir
    Path tmp;

    private Path home;
    private Path data;
    private Path cache;
    private final KernelFacts facts = new KernelFacts(Map.of("kernel", "0.5"), Set.of(), Set.of(), null, f -> true, 0, false, true, List.of(),
            null, null);

    @BeforeEach
    void folders() throws IOException {
        home = Files.createDirectories(tmp.resolve("home").resolve("modules"));
        data = Files.createDirectories(tmp.resolve("data"));
        cache = data.resolve("cache").resolve("modules");
    }

    @Test
    void aPackageIsUnpackedIntoItsHashAddressedFolder() {
        Path dmod = pack("demo", "1.0.0", ">=0.4", "packs/demo/kind.yaml");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.refused()).isEmpty();
        ModuleBoot.Module m = r.module("demo").orElseThrow();
        String sha = ModuleFiles.sha256(dmod);
        assertThat(m.sha256()).isEqualTo(sha);
        assertThat(m.dir()).isEqualTo(cache.resolve("demo").resolve("1.0.0-" + sha.substring(0, 16)));
        assertThat(m.dir().resolve("packs/demo/kind.yaml")).isRegularFile();
        assertThat(m.file()).isEqualTo(dmod);
        assertThat(m.origin()).isEqualTo(ModuleBoot.Origin.THIRD_PARTY);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("no first-party.yaml"));
        assertThat(ModuleFiles.list(cache.resolve("demo"))).containsExactly(m.dir());     // no .part left behind
    }

    @Test
    void aSecondStartUsesTheSameFolderUntouched() throws IOException {
        pack("demo", "1.0.0", ">=0.4");
        Path dir = ModuleBoot.boot(home, cache, true, facts, List.of()).module("demo").orElseThrow().dir();
        FileTime before = Files.getLastModifiedTime(dir);

        ModuleBoot.Result again = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(again.module("demo").orElseThrow().dir()).isEqualTo(dir);
        assertThat(Files.getLastModifiedTime(dir)).isEqualTo(before);
        assertThat(again.notes()).noneSatisfy(n -> assertThat(n).contains("unpacked again"));
    }

    /** G4: two files of one id: neither loads, and the words say which and what to do. */
    @Test
    void twoVersionsOfOneIdAreBothRefused() {
        pack("banking", "1.0.0", ">=0.4");
        pack("banking", "1.1.0", ">=0.4");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.modules()).isEmpty();
        assertThat(r.refused().get("banking")).isEqualTo("banking: two packages in " + home.toAbsolutePath()
                + " (1.0.0, 1.1.0); remove one and restart");
        assertThat(cache.resolve("banking")).doesNotExist();
    }

    @Test
    void aFileNotNamedAfterItsManifestIsRefused() throws IOException {
        Path dmod = pack("demo", "1.0.0", ">=0.4");
        Files.move(dmod, home.resolve("demo-latest.dmod"));

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.modules()).isEmpty();
        assertThat(r.refused().get("demo")).contains("demo-latest.dmod").contains("must be named demo-1.0.0.dmod");
    }

    @Test
    void aFileThatIsNotAPackageIsRefusedByItsName() throws IOException {
        Files.writeString(home.resolve("broken-1.0.0.dmod"), "not a zip");
        pack("demo", "1.0.0", ">=0.4");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.modules()).containsOnlyKeys("demo");
        assertThat(r.refused()).containsKey("broken-1.0.0.dmod");
    }

    /** G6: a module built for another kernel API is refused before it unpacks; the words name the range and this kernel's number. */
    @Test
    void aRangeTheKernelDoesNotMeetIsRefusedInWords() {
        pack("demo", "1.0.0", ">=9.0");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.modules()).isEmpty();
        assertThat(r.refused().get("demo")).isEqualTo("demo 1.0.0: built for kernel API >=9.0; this kernel has 0.5");
        assertThat(cache.resolve("demo")).doesNotExist();
    }

    /** G3: the old store and a stale cache folder change nothing; the stale folder is swept; the leftover is named, never deleted. */
    @Test
    void theOldStoreIsNeverReadAndAStaleCacheFolderIsSwept() throws IOException {
        Path old = data.resolve("modules").resolve("installed").resolve("demo").resolve("0.9.0");
        Dmod.write(old.resolve("module.yaml"), manifest("demo", "0.9.0", ">=0.4"));
        Dmod.write(data.resolve("modules").resolve("installed").resolve("active.yaml"), "modules: {demo: 0.9.0}\n");
        Path stale = Files.createDirectories(cache.resolve("demo").resolve("0.9.0-0123456789abcdef"));
        Dmod.write(stale.resolve("module.yaml"), manifest("demo", "0.9.0", ">=0.4"));
        Path gone = Files.createDirectories(cache.resolve("retired").resolve("2.0.0-fedcba9876543210"));
        pack("demo", "1.0.0", ">=0.4");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.module("demo").orElseThrow().version()).isEqualTo("1.0.0");
        assertThat(stale).doesNotExist();
        assertThat(gone).doesNotExist();
        assertThat(cache.resolve("retired")).doesNotExist();
        assertThat(old).isDirectory();
        assertThat(r.leftovers()).containsEntry(data.resolve("modules").resolve("installed").toAbsolutePath().normalize(),
                ModuleBoot.SAFE_TO_DELETE);
    }

    @Test
    void withoutTheSweepNothingIsDeleted() throws IOException {
        Path stale = Files.createDirectories(cache.resolve("demo").resolve("0.9.0-0123456789abcdef"));
        pack("demo", "1.0.0", ">=0.4");

        ModuleBoot.boot(home, cache, false, facts, List.of());

        assertThat(stale).isDirectory();
    }

    @Test
    void theSweepKeepsAFreshPartFolderAndRemovesAnOldOne() throws IOException {
        Path fresh = Files.createDirectories(cache.resolve("demo").resolve("1.0.0-0123456789abcdef.part-1"));
        Path old = Files.createDirectories(cache.resolve("demo").resolve("1.0.0-0123456789abcdef.part-2"));
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(ModuleFiles.PART_GRACE).minusSeconds(60)));

        ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(fresh).isDirectory();
        assertThat(old).doesNotExist();
    }

    /** G8: a file changed inside the cache folder is noticed and the module unpacked again from its package. */
    @Test
    void aChangedCacheFileIsUnpackedAgain() throws IOException {
        pack("demo", "1.0.0", ">=0.4", "packs/demo/kind.yaml");
        Path dir = ModuleBoot.boot(home, cache, true, facts, List.of()).module("demo").orElseThrow().dir();
        Files.writeString(dir.resolve("packs/demo/kind.yaml"), "changed: true\n");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.refused()).isEmpty();
        assertThat(Files.readString(dir.resolve("packs/demo/kind.yaml"))).isEqualTo("kind: demo\n");
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("demo 1.0.0").contains("unpacked again from demo-1.0.0.dmod"));
    }

    @Test
    void aModuleInTheFirstPartyListIsFirstParty() {
        Path src = source("banking", "1.0.0", ">=0.4");
        Dmod.write(src.resolve("module.yaml"), manifest("banking", "1.0.0", ">=0.4") + "origin: first-party\n");
        Dmod.pack(src, home.resolve("banking-1.0.0.dmod"));
        Dmod.write(home.resolve(ModuleBoot.FIRST_PARTY_FILE), FirstParty.write(List.of(src)));

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.module("banking").orElseThrow().origin()).isEqualTo(ModuleBoot.Origin.FIRST_PARTY);
        assertThat(r.firstParty().ids()).containsExactly("banking");
        assertThat(r.facts().firstParty()).isSameAs(r.firstParty());
        assertThat(r.notes()).noneSatisfy(n -> assertThat(n).contains("first-party.yaml"));
    }

    @Test
    void theDevelopmentSwitchReadsSourceFoldersOnly() {
        pack("demo", "1.0.0", ">=0.4");
        Path src = source("demo", "1.2.0", ">=0.4");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of(src));

        ModuleBoot.Module m = r.module("demo").orElseThrow();
        assertThat(m.version()).isEqualTo("1.2.0");
        assertThat(m.dir()).isEqualTo(src);
        assertThat(m.file()).isNull();
        assertThat(m.hash16()).isNull();
        assertThat(m.origin().word()).isEqualTo("development (unverified)");
        assertThat(cache).doesNotExist();
    }

    @Test
    void anEmptyOrAbsentModulesFolderGivesNoModules() {
        ModuleBoot.Result r = ModuleBoot.boot(tmp.resolve("nowhere"), cache, true, facts, List.of());

        assertThat(r.modules()).isEmpty();
        assertThat(r.refused()).isEmpty();
        assertThat(r.report()).startsWith("Modules: none");
    }

    @Test
    void theProcessKeepsTheResultItWasGiven() {
        ModuleBoot.Result r = ModuleBoot.boot(home, cache, false, facts, List.of());

        assertThat(ModuleBoot.remember(r)).isSameAs(r);
        assertThat(ModuleBoot.current()).containsSame(r);
    }

    // ------------------------------------------------------------------ fixtures

    private Path pack(String id, String version, String kernel, String... files) {
        Path src = source(id, version, kernel, files);
        return Dmod.pack(src, home.resolve(Dmod.fileName(id, version)));
    }

    private Path source(String id, String version, String kernel, String... files) {
        Path src = tmp.resolve("src").resolve(id + "-" + version);
        Dmod.write(src.resolve("module.yaml"), manifest(id, version, kernel));
        for (String f : files) {
            Dmod.write(src.resolve(f), "kind: " + id + "\n");
        }
        return src;
    }

    private static String manifest(String id, String version, String kernel) {
        return """
                format: 1
                id: %s
                version: %s
                title: Test %s
                about: A module built in tests
                domain: testing
                publisher: {name: Drishti tests, id: drishti-tests}
                licence: {feature: %s}
                kernel: "%s"
                rachana: ">=1.0"
                contracts: ">=1.0"
                plugin-api: ">=2.0"
                elements: ">=2.4 <3"
                """.formatted(id, version, id, id, kernel);
    }
}
