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

import com.ash.drishti.api.resolution.ModuleHomes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ModuleBoot}: discovery of {@code .dmod} files in the modules folder, the hash-addressed cache, the sweep, the refusals in words,
 * the development switch and source mode, the leftovers of the removed deployment store, the content hash, the in-use markers and two
 * boots racing on one cache. Guards G3, G4, G6 and G8 of the i7a build plan; I2's cases (in use, race, content hash, source mode).
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
        assertThat(m.origin()).isEqualTo(ModuleHomes.Origin.THIRD_PARTY);
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
        Dmod.write(data.resolve("modules").resolve("installed").resolve("anything.txt"), "demo 0.9.0\n");
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

        assertThat(r.module("banking").orElseThrow().origin()).isEqualTo(ModuleHomes.Origin.FIRST_PARTY);
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

    /**
     * Decision D1, the flat modules folder: {@code .dmod} files live flat in the modules folder; a package in a folder below it, or a file that only looks like one, is never read.
     */
    @Test
    void onlyPackagesDirectlyInTheModulesFolderAreRead() throws IOException {
        Path real = pack("demo", "1.0.0", ">=0.4");
        Path below = Files.createDirectories(home.resolve("lib").resolve("modules"));
        Path other = Files.createDirectories(home.resolve("more"));
        Dmod.pack(source("hidden", "1.0.0", ">=0.4"), below.resolve(Dmod.fileName("hidden", "1.0.0")));
        Dmod.pack(source("deeper", "1.0.0", ">=0.4"), other.resolve(Dmod.fileName("deeper", "1.0.0")));
        Files.copy(real, home.resolve("demo-1.0.0.dmod.part"));
        Files.writeString(home.resolve("notes.txt"), "not a package");

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.modules().keySet()).containsExactly("demo");
        assertThat(r.refused()).isEmpty();
    }

    // ------------------------------------------------------------------ I2: the plain value, the content hash, in use, the race, source mode

    /** Decision 3: the content hash is the SHA-256 of DMOD.MF; the same files in another zip (other entry times) keep it. */
    @Test
    void theContentHashIsTheHashListsNotTheFiles() throws IOException {
        Path dmod = pack("demo", "1.0.0", ">=0.4", "packs/demo/kind.yaml");
        ModuleBoot.Module first = ModuleBoot.boot(home, cache, true, facts, List.of()).module("demo").orElseThrow();
        byte[] mf = Files.readAllBytes(first.dir().resolve(Dmod.HASHES));
        try (InputStream in = new java.io.ByteArrayInputStream(mf)) {
            assertThat(first.contentHash()).isEqualTo(Dmod.sha256(in)).isNotEqualTo(first.sha256());
        }
        Path rezipped = tmp.resolve("rezipped.dmod");
        rezip(dmod, rezipped, Instant.parse("2001-02-03T04:05:06Z"));
        Files.move(rezipped, dmod, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(dmod, FileTime.from(Instant.parse("2011-01-01T00:00:00Z")));

        ModuleBoot.Module second = ModuleBoot.boot(home, cache, true, facts, List.of()).module("demo").orElseThrow();

        assertThat(second.sha256()).isNotEqualTo(first.sha256());                        // other bytes: another cache folder
        assertThat(second.dir()).isNotEqualTo(first.dir());
        assertThat(second.contentHash()).isEqualTo(first.contentHash());                 // same content: same identity
        assertThat(second.home().identity()).isEqualTo(first.home().identity());
        assertThat(ModuleHomes.of(List.of(second.home())).fingerprint()).isEqualTo(ModuleHomes.of(List.of(first.home())).fingerprint());
    }

    @Test
    void otherContentIsAnotherContentHash() throws IOException {
        Path a = pack("demo", "1.0.0", ">=0.4", "packs/demo/kind.yaml");
        String before = ModuleBoot.boot(home, cache, true, facts, List.of()).module("demo").orElseThrow().contentHash();
        Path src = source("demo", "1.0.0", ">=0.4", "packs/demo/kind.yaml");
        Dmod.write(src.resolve("packs/demo/kind.yaml"), "kind: changed\n");
        Dmod.pack(src, a);

        String after = ModuleBoot.boot(home, cache, true, facts, List.of()).module("demo").orElseThrow().contentHash();

        assertThat(after).isNotEqualTo(before);
    }

    /** The plain value: id → folder, version, content hash, origin; remember publishes it for the readers below this module. */
    @Test
    void theResultsPlainValueIsPublishedWithIt() {
        pack("demo", "1.0.0", ">=0.4");
        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        ModuleBoot.remember(r);

        ModuleHomes homes = ModuleHomes.current().orElseThrow();
        assertThat(homes).isEqualTo(r.homes());
        ModuleHomes.Home h = homes.home("demo").orElseThrow();
        ModuleBoot.Module m = r.module("demo").orElseThrow();
        assertThat(h).isEqualTo(new ModuleHomes.Home("demo", "1.0.0", m.dir(), m.contentHash(), ModuleHomes.Origin.THIRD_PARTY));
        assertThat(homes.dirProperties()).containsExactly(Map.entry("drishti.module.dir.demo", m.dir().toAbsolutePath().toString()));
    }

    /** S24: a cache folder no .dmod here owns, but another node marked in use, survives the sweep; a stale marker does not keep it. */
    @Test
    void aFolderInUseByAnotherNodeIsNotSwept() throws IOException {
        pack("demo", "1.0.0", ">=0.4");
        ModuleBoot.Result old = ModuleBoot.boot(home, cache, true, facts, List.of());
        Path oldDir = old.module("demo").orElseThrow().dir();
        assertThat(ModuleBoot.markInUse(old, "node-a")).isEmpty();
        Path marker = ModuleBoot.inUseMarker(oldDir, "node-a");
        assertThat(marker).isRegularFile().hasFileName(oldDir.getFileName() + ".in-use-node-a");
        Files.delete(home.resolve("demo-1.0.0.dmod"));
        pack("demo", "1.1.0", ">=0.4");                                                   // node b upgraded the shared modules folder

        ModuleBoot.Result upgraded = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(upgraded.module("demo").orElseThrow().version()).isEqualTo("1.1.0");
        assertThat(oldDir).isDirectory();
        assertThat(marker).isRegularFile();
        assertThat(upgraded.notes()).anySatisfy(n -> assertThat(n).contains("kept " + oldDir.toAbsolutePath().normalize())
                .contains("in use by node-a"));

        Files.setLastModifiedTime(marker, FileTime.from(Instant.now().minus(ModuleBoot.IN_USE_FRESH).minusSeconds(60)));
        ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(oldDir).doesNotExist();
        assertThat(marker).doesNotExist();
    }

    @Test
    void markingRefreshesAndReleaseRemovesTheMarkers() throws IOException {
        pack("demo", "1.0.0", ">=0.4");
        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());
        ModuleBoot.markInUse(r, "host/1:18480");
        Path marker = ModuleBoot.inUseMarker(r.module("demo").orElseThrow().dir(), "host/1:18480");
        assertThat(marker.getFileName().toString()).endsWith(".in-use-host_1_18480");
        FileTime old = FileTime.from(Instant.now().minusSeconds(600));
        Files.setLastModifiedTime(marker, old);

        ModuleBoot.markInUse(r, "host/1:18480");

        assertThat(Files.getLastModifiedTime(marker).toInstant()).isAfter(old.toInstant());
        assertThat(ModuleBoot.releaseInUse(r, "host/1:18480")).isEmpty();
        assertThat(marker).doesNotExist();
    }

    /** Two boots (two nodes or two scratch servers) unpacking one package into one cache at once: both accept it from one folder. */
    @Test
    void twoBootsRacingOnOneCacheShareOneFolder() throws Exception {
        pack("demo", "1.0.0", ">=0.4", "packs/demo/a.yaml", "packs/demo/b.yaml", "packs/demo/c.yaml");
        int racers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int round = 0; round < 5; round++) {
                ModuleFiles.delete(cache, new java.util.ArrayList<>());
                CyclicBarrier start = new CyclicBarrier(racers);
                List<Future<ModuleBoot.Result>> runs = new java.util.ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    runs.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return ModuleBoot.boot(home, cache, true, facts, List.of());
                    }));
                }
                Path dir = null;
                for (Future<ModuleBoot.Result> f : runs) {
                    ModuleBoot.Result r = f.get(60, TimeUnit.SECONDS);
                    assertThat(r.refused()).as("round " + round).isEmpty();
                    Path d = r.module("demo").orElseThrow().dir();
                    assertThat(dir == null || dir.equals(d)).isTrue();
                    dir = d;
                }
                assertThat(ModuleFiles.list(cache.resolve("demo"))).as("round " + round).containsExactly(dir);   // no .part left
                assertThat(dir.resolve("packs/demo/c.yaml")).isRegularFile();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Decision 2, source mode: a checkout's module folders through the development path; nothing packed, unpacked or swept. */
    @Test
    void sourceModeBootsTheCheckoutsModuleFolders() throws IOException {
        Path checkout = Files.createDirectories(tmp.resolve("checkout"));
        Path a = checkout.resolve("modules").resolve("alpha");
        Path b = checkout.resolve("modules").resolve("beta");
        Dmod.write(a.resolve("module.yaml"), manifest("alpha", "1.0.0", ">=0.4"));
        Dmod.write(b.resolve("module.yaml"), manifest("beta", "2.0.0", ">=0.4"));
        Files.createDirectories(checkout.resolve("modules").resolve("not-a-module").resolve("docs"));
        Files.writeString(checkout.resolve("modules").resolve("README.md"), "modules\n");

        ModuleBoot.Result r = ModuleBoot.source(checkout, facts);

        assertThat(ModuleBoot.sourceFolders(checkout)).containsExactly(a.toAbsolutePath(), b.toAbsolutePath());
        assertThat(r.refused()).isEmpty();
        assertThat(r.modules().keySet()).containsExactly("alpha", "beta");
        ModuleHomes homes = r.homes();
        assertThat(homes.home("beta").orElseThrow()).isEqualTo(new ModuleHomes.Home("beta", "2.0.0", b.toAbsolutePath(), null,
                ModuleHomes.Origin.DEVELOPMENT));
        assertThat(homes.home("alpha").orElseThrow().identity()).isEqualTo("alpha 1.0.0 development");
        assertThat(r.leftovers()).isEmpty();
        assertThat(a.resolve(Dmod.HASHES)).doesNotExist();
        assertThat(ModuleBoot.source(tmp.resolve("no-checkout"), facts).modules()).isEmpty();
    }

    /** The old store's registry is a leftover: named as safe to delete, never read. */
    @Test
    void theOldRegistryIsALeftoverNeverRead() throws IOException {
        Dmod.write(data.resolve("modules").resolve(ModuleBoot.OLD_REGISTRY), """
                [{"id": "banking", "state": "OFF"}]
                """);

        ModuleBoot.Result r = ModuleBoot.boot(home, cache, true, facts, List.of());

        assertThat(r.notes()).noneSatisfy(n -> assertThat(n).contains("old store"));
        assertThat(r.leftovers()).containsEntry(data.resolve("modules").resolve(ModuleBoot.OLD_REGISTRY).toAbsolutePath().normalize(),
                ModuleBoot.SAFE_TO_DELETE);
    }

    // ------------------------------------------------------------------ fixtures

    /** The same entries in the same order, each stamped with another time: other zip bytes, the same content. */
    private static void rezip(Path from, Path to, Instant time) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(from));
                OutputStream o = Files.newOutputStream(to);
                ZipOutputStream out = new ZipOutputStream(o)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] bytes = in.readAllBytes();
                ZipEntry n = new ZipEntry(e.getName());
                n.setTime(time.toEpochMilli());
                if (e.getMethod() == ZipEntry.STORED) {
                    n.setMethod(ZipEntry.STORED);
                    n.setSize(bytes.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(bytes);
                    n.setCrc(crc.getValue());
                }
                out.putNextEntry(n);
                out.write(bytes);
                out.closeEntry();
            }
        }
    }

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
