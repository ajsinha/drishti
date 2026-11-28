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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.rachana.SutraException;
import com.ash.rachana.lib.LibraryLock;
import com.ash.rachana.model.Sutra;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rachana's fragment libraries in Drishti's one Rachana instance: a Sutra that declares {@code libraries: [core@1]} uses
 * {@code core/record-card@1} and it draws; an unknown library, a library not in the lock and another major are refused in words with
 * Drishti's {@code DRS-} codes; a library folder named in {@code drishti.rachana.libraries.dirs} resolves; the palette lists the
 * library fragments with their doc summaries.
 */
class DrishtiLibrariesTest {

    @TempDir Path dir;

    @AfterEach
    void back() {
        DrishtiRachana.libraries(null);
    }

    private static String sutra(String libraries, String use) {
        return "rachana: 1.2\nsutra: probe\nversion: 1\n" + libraries + "match: { kind: trade }\ntitle: { id: $.ID }\npanels:\n" + use;
    }

    private static final String CARD = "  - use: core/record-card@1\n    with:\n      record: \"{name: $.NAME, subtitle: $.SECTOR, status: $.STATE}\"\n"
            + "      facts: [ { label: Staff, bind: \"@.STAFF\" } ]\n";

    private static Sutra parse(String yaml) {
        return DrishtiRachana.parser().parse(yaml, "probe.v1.sutra.yaml", "x");
    }

    private static List<String> codes(SutraException e) {
        return DrishtiRachana.drs(e.problems()).stream().map(com.ash.rachana.SutraProblem::code).toList();
    }

    @Test
    void aSutraUsingACoreLibraryFragmentBindsAndDraws() {
        Sutra s = parse(sutra("libraries: [core@1]\n", CARD));
        assertThat(s.panels()).isNotEmpty();
        assertThat(s.panels()).extracting(p -> p.id()).contains("card");
        assertThat(s.libraries()).extracting(l -> l.toString()).anyMatch(t -> t.contains("core"));
    }

    /** Core 1.1: the fragments take the panel keys a Drishti screen writes (key, code, span, when), so such a panel can be a use. */
    @Test
    void theCoreFragmentsTakeThePanelKeysAScreenNeeds() {
        String use = "  - use: core/data-table@1\n    with:\n      rows: $.ORDERS\n      columns: [ { label: Order, bind: \"@.ID\" } ]\n"
                + "      key: F2\n      code: ORD\n      span: 6\n      empty: No orders\n      when: \"$.SHOW\"\n"
                + "  - use: core/record-card@1\n    with:\n      record: \"$\"\n      title: Facts\n      facts: [ { label: Staff, bind: \"@.STAFF\" } ]\n"
                + "      code: FACTS\n";
        Sutra s = parse(sutra("libraries: [core@1]\n", use));
        assertThat(s.panels()).extracting(p -> p.id()).contains("table", "card");
        assertThat(s.panels().stream().filter(p -> p.id().equals("table")).findFirst().orElseThrow().key()).isEqualTo("F2");
        assertThat(s.panels().stream().filter(p -> p.id().equals("card")).findFirst().orElseThrow().code()).isEqualTo("FACTS");
    }

    @Test
    void theLongFormNeedsNoDeclaration() {
        Sutra s = parse(sutra("", CARD.replace("core/record-card@1", "core@1/record-card@1")));
        assertThat(s.panels()).extracting(p -> p.id()).contains("card");
    }

    @Test
    void anUnknownLibraryIsRefusedInWordsWithADrsCode() {
        assertThatThrownBy(() -> parse(sutra("libraries: [nolib@1]\n", CARD.replace("core/", "nolib/")))).isInstanceOf(SutraException.class)
                .satisfies(e -> {
                    SutraException x = (SutraException) e;
                    assertThat(codes(x)).contains("DRS-2110");
                    assertThat(x.getMessage() + x.problems()).contains("no library nolib@1");
                });
    }

    @Test
    void aWrongMajorIsRefused() {
        assertThatThrownBy(() -> parse(sutra("libraries: [core@9]\n", CARD))).isInstanceOf(SutraException.class)
                .satisfies(e -> assertThat(codes((SutraException) e)).contains("DRS-2110"));
    }

    @Test
    void withoutTheBundledLibrariesCoreIsNotFound() {
        DrishtiRachana.libraries(DrishtiLibraries.of(new LibraryProperties(false, null, null)));
        assertThatThrownBy(() -> parse(sutra("libraries: [core@1]\n", CARD))).isInstanceOf(SutraException.class)
                .satisfies(e -> assertThat(codes((SutraException) e)).contains("DRS-2110"));
    }

    @Test
    void aLibraryNotInTheLockFileIsRefused() {
        DrishtiRachana.libraries(DrishtiLibraries.of(null).locked(LibraryLock.of(List.of(
                new LibraryLock.Entry("finance", "1.0.0", "00", null, null, null)))));
        assertThatThrownBy(() -> parse(sutra("libraries: [core@1]\n", CARD))).isInstanceOf(SutraException.class)
                .satisfies(e -> assertThat(codes((SutraException) e)).contains("DRS-2111"));
    }

    @Test
    void aLibraryFolderNamedInTheSettingsResolves() throws Exception {
        Path libs = dir.resolve("libs");
        copyBundled("core", libs.resolve("core"));
        DrishtiRachana.libraries(DrishtiLibraries.of(new LibraryProperties(false, List.of(libs.toString()), null)));
        assertThat(parse(sutra("libraries: [core@1]\n", CARD)).panels()).extracting(p -> p.id()).contains("card");
        assertThat(DrishtiLibraries.fragments(DrishtiRachana.libraries())).extracting(m -> m.get("origin")).contains("library:core");
    }

    @Test
    void theSameLibraryWithOtherContentsInTwoPlacesIsRefused() throws Exception {
        Path libs = dir.resolve("libs");
        copyBundled("core", libs.resolve("core"));
        Files.writeString(libs.resolve("core/README.md"), "changed\n", StandardCharsets.UTF_8);       // not the bundled bytes
        Path manifest = libs.resolve("core/core.rachana-lib.yaml");
        Files.writeString(manifest, Files.readString(manifest) + "\n# changed\n");
        DrishtiRachana.libraries(DrishtiLibraries.of(new LibraryProperties(true, List.of(libs.toString()), null)));
        assertThatThrownBy(() -> parse(sutra("libraries: [core@1]\n", CARD))).isInstanceOf(SutraException.class);
    }

    @Test
    void thePaletteListsLibraryFragmentsWithTheirSummaries() {
        List<java.util.Map<String, Object>> all = DrishtiLibraries.fragments(DrishtiRachana.libraries());
        assertThat(all).extracting(m -> m.get("ref")).contains("core/record-card@1");
        java.util.Map<String, Object> card = all.stream().filter(m -> "core/record-card@1".equals(m.get("ref"))).findFirst().orElseThrow();
        assertThat(String.valueOf(card.get("summary"))).contains("record as a card");
        assertThat(all).extracting(m -> m.get("library")).contains("finance");
    }

    @Test
    void aFolderSettingIsAnchoredToANamedRoot() {
        assertThat(DrishtiLibraries.anchored(dir.toString())).isEqualTo(dir);
        assertThat(DrishtiLibraries.clashes(DrishtiRachana.libraries(), List.of("core/legs", "trading/legs", "legs"))).containsExactly("core");
    }

    /** Writes the files a bundled library's MANIFEST.sha384 lists (and the manifest) into {@code to}: what a vendored folder holds. */
    private static void copyBundled(String name, Path to) throws Exception {
        String root = "META-INF/rachana/libs/" + name + "/";
        ClassLoader cl = DrishtiLibraries.class.getClassLoader();
        byte[] manifest;
        try (InputStream in = cl.getResourceAsStream(root + "MANIFEST.sha384")) {
            manifest = in.readAllBytes();
        }
        Files.createDirectories(to);
        Files.write(to.resolve("MANIFEST.sha384"), manifest);
        for (String line : new String(manifest, StandardCharsets.UTF_8).split("\n")) {
            int at = line.indexOf("  ");
            if (at > 0) {
                String path = line.substring(at + 2).trim();
                try (InputStream in = cl.getResourceAsStream(root + path)) {
                    Files.createDirectories(to.resolve(path).getParent());
                    Files.write(to.resolve(path), in.readAllBytes());
                }
            }
        }
    }
}
