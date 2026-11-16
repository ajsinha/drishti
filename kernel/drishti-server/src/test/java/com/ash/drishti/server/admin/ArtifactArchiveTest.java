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
package com.ash.drishti.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The archive of uninstalled artifacts on temporary folders: move in, move back, clash, retention, permanent delete, hostile ids. */
class ArtifactArchiveTest {

    private Path root;
    private Path live;
    private MutableClock clock;
    private ArtifactArchive archive;

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        root = com.ash.drishti.server.TestRun.temp("artifact-archive-");
        live = Files.createDirectories(root.resolve("live"));
        clock = new MutableClock();
        archive = new ArtifactArchive(root.resolve("uninstalled"), clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private Path pack(String name) throws IOException {
        Path d = Files.createDirectories(live.resolve(name).resolve("samples"));
        Files.writeString(d.getParent().resolve("pack.yaml"), "pack: " + name + "\n");
        Files.writeString(d.resolve("a.json"), "{}");
        return d.getParent();
    }

    @Test
    void uninstallingMovesTheFolderIntoANamedArchiveAndNothingIsDeleted() throws IOException {
        Path p = pack("widgets");
        ArtifactArchive.Entry e = archive.archive("pack", "", "widgets", "packs", "widgets", p, "ravi", "nothing depends on it");
        assertThat(p).doesNotExist();
        assertThat(e.id()).isEqualTo("20261001T100000-pack-widgets");
        assertThat(archive.dir().resolve(e.id()).resolve("payload/widgets/pack.yaml")).exists();
        assertThat(archive.dir().resolve(e.id()).resolve("payload/widgets/samples/a.json")).exists();
        assertThat(archive.dir().resolve(e.id()).resolve("uninstalled.json")).exists();
        assertThat(e.by()).isEqualTo("ravi");
        assertThat(e.bytes()).isGreaterThan(0);
        assertThat(archive.list()).extracting(ArtifactArchive.Entry::name).containsExactly("widgets");
    }

    @Test
    void restoreMovesItBackAndRemovesTheArchive() throws IOException {
        Path p = pack("widgets");
        ArtifactArchive.Entry e = archive.archive("pack", "", "widgets", "packs", "widgets", p, "ravi", "s");
        archive.restoreTo(e, live.resolve("widgets"));
        assertThat(live.resolve("widgets/pack.yaml")).hasContent("pack: widgets\n");
        assertThat(live.resolve("widgets/samples/a.json")).exists();
        assertThat(archive.list()).isEmpty();
        assertThat(archive.dir().resolve(e.id())).doesNotExist();
    }

    @Test
    void aFileIsArchivedAndRestoredToo() throws IOException {
        Path f = Files.writeString(live.resolve("store.yaml"), "plugin: file\n");
        ArtifactArchive.Entry e = archive.archive("connector", "", "store", "connectors", "store.yaml", f, "ravi", "s");
        assertThat(f).doesNotExist();
        archive.restoreTo(e, live.resolve("store.yaml"));
        assertThat(live.resolve("store.yaml")).hasContent("plugin: file\n");
    }

    @Test
    void restoringOverANameThatIsThereAgainIsRefusedAndTheArchiveStays() throws IOException {
        Path p = pack("widgets");
        ArtifactArchive.Entry e = archive.archive("pack", "", "widgets", "packs", "widgets", p, "ravi", "s");
        pack("widgets");                                                             // someone installed it again
        assertThatThrownBy(() -> archive.restoreTo(e, live.resolve("widgets"))).isInstanceOfSatisfying(DrishtiException.class,
                x -> assertThat(x.errorCode()).isEqualTo(ErrorCode.ARTIFACT_CONFLICT));
        assertThat(archive.find(e.id())).isPresent();
    }

    @Test
    void twoUninstallsOfTheSameNameInOneSecondDoNotCollide() throws IOException {
        ArtifactArchive.Entry a = archive.archive("pack", "", "widgets", "packs", "widgets", pack("widgets"), "ravi", "s");
        ArtifactArchive.Entry b = archive.archive("pack", "", "widgets", "packs", "widgets", pack("widgets"), "ravi", "s");
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(archive.list()).hasSize(2);
    }

    @Test
    void pruningRemovesOnlyArchivesOlderThanTheRetention() throws IOException {
        ArtifactArchive.Entry old = archive.archive("pack", "", "old", "packs", "old", pack("old"), "ravi", "s");
        clock.advance(Duration.ofDays(20));
        ArtifactArchive.Entry recent = archive.archive("pack", "", "recent", "packs", "recent", pack("recent"), "ravi", "s");
        clock.advance(Duration.ofDays(15));                                          // old is 35 days, recent 15
        List<String> gone = archive.prune(Duration.ofDays(30));
        assertThat(gone).containsExactly(old.id());
        assertThat(archive.list()).extracting(ArtifactArchive.Entry::id).containsExactly(recent.id());
        assertThat(archive.prune(Duration.ZERO)).isEmpty();                          // zero keeps everything
    }

    @Test
    void purgeDeletesForGoodAndAnUnknownIdIsFalse() throws IOException {
        ArtifactArchive.Entry e = archive.archive("pack", "", "widgets", "packs", "widgets", pack("widgets"), "ravi", "s");
        assertThat(archive.purge(e.id())).isTrue();
        assertThat(archive.dir().resolve(e.id())).doesNotExist();
        assertThat(archive.purge(e.id())).isFalse();
    }

    @Test
    void anIdThatIsAPathIsNeverFollowed() throws IOException {
        pack("widgets");
        assertThat(archive.find("../live")).isEmpty();
        assertThat(archive.find("..")).isEmpty();
        assertThat(archive.purge("../live")).isFalse();
        assertThat(live.resolve("widgets/pack.yaml")).exists();
    }
}
