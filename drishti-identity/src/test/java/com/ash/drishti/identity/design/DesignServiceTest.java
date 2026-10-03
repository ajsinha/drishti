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
package com.ash.drishti.identity.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.design.DesignService.NewSample;
import com.ash.drishti.identity.design.DesignService.Patch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesignServiceTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private DesignService service(Path root, DesignProperties p) {
        return new DesignService(new FileDesignStore(root), p, now::get);
    }

    private static DesignProperties props(int perUser, int samples, int mb, int userMb) {
        return new DesignProperties("file", null, perUser, samples, mb, userMb, Duration.ofDays(1), Duration.ofDays(90), Duration.ofDays(75),
                Duration.ofHours(1), null);
    }

    private static NewSample doc(String name, String json) {
        return new NewSample(name, StoredDesign.DOCUMENT, null, null, json);
    }

    @Test
    void theFileStoreRoundTrips() {
        DesignStoreChecks.roundTrip(new FileDesignStore(dir));
    }

    @Test
    void theFileStoreKeepsFilesReadableByTheServerOnly() throws Exception {
        DesignService s = service(dir, props(5, 5, 1, 5));
        StoredDesign d = s.create("ann", "Mine", "trade", null, "rachana: 1\n", "");
        s.addSamples("ann", d.id, List.of(doc("a.json", "{\"a\":1}")));
        try (var all = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
                assertThat(mode).as(p.toString()).matches(Files.isDirectory(p) ? "rwx------" : "rw-------");
            }
        }
    }

    @Test
    void userNamesAndIdsCannotEscapeTheStore() {
        DesignService s = service(dir.resolve("designs"), props(5, 5, 1, 5));
        StoredDesign d = s.create("..", "x", "trade", null, "", "");
        assertThat(Files.exists(dir.resolve("designs").resolve("_2e.").resolve(d.id).resolve("design.json"))
                || Files.exists(dir.resolve("designs").resolve("_2e_2e").resolve(d.id).resolve("design.json"))).isTrue();
        assertThatThrownBy(() -> s.get("ann", "../../etc")).isInstanceOf(DrishtiException.class);
        assertThat(new FileDesignStore(dir.resolve("designs")).users()).containsExactly("..");
        try (var top = Files.list(dir)) {
            assertThat(top.map(p -> p.getFileName().toString())).containsExactly("designs");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aDesignBelongsToItsOwnerAlone() {
        DesignService s = service(dir, props(5, 5, 1, 5));
        StoredDesign d = s.create("ann", "Mine", "trade", null, "", "");
        s.addSamples("ann", d.id, List.of(doc("a.json", "{}")));
        for (Runnable other : List.<Runnable>of(() -> s.get("bob", d.id), () -> s.open("bob", d.id), () -> s.delete("bob", d.id),
                () -> s.update("bob", d.id, new Patch("x", null, null, null, null)), () -> s.sample("bob", d.id, "a.json"),
                () -> s.addSamples("bob", d.id, List.of(doc("b.json", "{}"))), () -> s.duplicate("bob", d.id, "c"),
                () -> s.removeSample("bob", d.id, "a.json"))) {
            assertThatThrownBy(other::run).isInstanceOf(DrishtiException.class).extracting(e -> ((DrishtiException) e).errorCode())
                    .isEqualTo(ErrorCode.DESIGN_NOT_FOUND);
        }
        assertThat(s.list("bob")).isEmpty();
        assertThat(s.sample("ann", d.id, "a.json")).isEqualTo("{}");
    }

    @Test
    void limitsAnswerWithDrs5005() {
        DesignService s = service(dir, props(2, 3, 1, 1));
        StoredDesign a = s.create("ann", "A", "trade", null, "", "");
        s.create("ann", "B", "trade", null, "", "");
        assertThatThrownBy(() -> s.create("ann", "C", "trade", null, "", "")).hasMessageContaining("max-per-user")
                .extracting(e -> ((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        s.addSamples("ann", a.id, List.of(doc("1.json", "{}"), doc("2.json", "{}"), doc("3.json", "{}")));
        assertThatThrownBy(() -> s.addSamples("ann", a.id, List.of(doc("4.json", "{}")))).hasMessageContaining("max-samples");
        s.addSamples("ann", a.id, List.of(doc("2.json", "{\"replaced\":true}")));      // same name replaces, not a third sample
        assertThat(s.get("ann", a.id).samples).hasSize(3);
        String big = "\"" + "x".repeat(700_000) + "\"";
        StoredDesign b = s.list("ann").stream().map(x -> x.design()).filter(x -> !x.id.equals(a.id)).findFirst().orElseThrow();
        s.addSamples("ann", b.id, List.of(doc("big.json", big)));
        assertThatThrownBy(() -> s.addSamples("ann", b.id, List.of(doc("big2.json", big)))).hasMessageContaining("max-mb");
        assertThatThrownBy(() -> s.addSamples("ann", a.id, List.of(doc("3.json", big)))).hasMessageContaining("max-user-mb");
        assertThat(s.get("ann", b.id).samples).hasSize(1);      // nothing of a refused request was kept
        s.delete("ann", b.id);
        assertThat(s.list("ann")).hasSize(1);
    }

    @Test
    void scratchDesignsExpireAfterADayNamedOnesAfterNinetyWithAWarning() {
        DesignService s = service(dir, props(5, 5, 1, 5));
        StoredDesign scratch = s.create("ann", "", "trade", null, "", "");
        StoredDesign named = s.create("ann", "Keep", "trade", null, "", "");
        s.addSamples("ann", scratch.id, List.of(doc("a.json", "{}")));
        assertThat(scratch.scratch).isTrue();
        now.addAndGet(Duration.ofHours(23).toMillis());
        assertThat(s.sweep()).isZero();
        now.addAndGet(Duration.ofHours(2).toMillis());
        assertThat(s.sweep()).isEqualTo(1);
        assertThatThrownBy(() -> s.get("ann", scratch.id)).isInstanceOf(DrishtiException.class);
        assertThat(s.list("ann")).hasSize(1);

        now.addAndGet(Duration.ofDays(73).toMillis());       // 74 days untouched: no warning yet
        assertThat(s.list("ann").get(0).expiryWarning()).isFalse();
        now.addAndGet(Duration.ofDays(2).toMillis());        // 76
        assertThat(s.list("ann").get(0).expiryWarning()).isTrue();
        s.update("ann", named.id, new Patch(null, null, "touched", null, null));
        assertThat(s.list("ann").get(0).expiryWarning()).isFalse();
        now.addAndGet(Duration.ofDays(91).toMillis());
        assertThat(s.sweep()).isEqualTo(1);
        assertThat(s.list("ann")).isEmpty();
        assertThat(new FileDesignStore(dir).list("ann")).isEmpty();      // gone from disk, samples with it
    }

    @Test
    void aChangedSutraIsANewRevisionAndDuplicatesCarrySamples() {
        DesignService s = service(dir, props(5, 5, 1, 5));
        StoredDesign d = s.create("ann", "Mine", "trade", "t@1", "rachana: 1\n", "n");
        assertThat(d.rev).isEqualTo(1);
        assertThat(s.update("ann", d.id, new Patch(null, null, null, "rachana: 1\n", null)).rev).isEqualTo(1);
        assertThat(s.update("ann", d.id, new Patch(null, null, null, "rachana: 1\n# x\n", null)).rev).isEqualTo(2);
        s.addSamples("ann", d.id, List.of(doc("a.json", "{\"a\":1}"),
                new NewSample("trade T1", StoredDesign.REF, "trade", "T1", null)));
        StoredDesign c = s.duplicate("ann", d.id, null);
        assertThat(c.name).isEqualTo("Mine copy");
        assertThat(c.samples).hasSize(2);
        assertThat(s.sample("ann", c.id, "a.json")).isEqualTo("{\"a\":1}");
        s.delete("ann", d.id);
        assertThat(s.sample("ann", c.id, "a.json")).isEqualTo("{\"a\":1}");     // the copy owns its samples
    }
}
