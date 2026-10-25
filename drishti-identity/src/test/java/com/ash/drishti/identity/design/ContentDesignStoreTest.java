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
import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Designs in the content store (docs/architecture/CONTENT_STORE.md section 5): two in-process nodes sharing one filesystem store root
 * (what a cluster on a shared mount does), and the one-time import from the old stores.
 */
class ContentDesignStoreTest {

    @TempDir
    Path tmp;

    private Path dir;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.toAbsolutePath();
        root = dir.resolve("fstore");
    }

    private ContentDesignStore node() {
        return new ContentDesignStore(new FileContentStore(root));          // each node opens the same root on its own
    }

    private static DesignService service(DesignStore s) {
        return new DesignService(s, DesignProperties.defaults());
    }

    @Test
    void theContentStoreRoundTrips() {
        DesignStoreChecks.roundTrip(node());
    }

    @Test
    void aDesignSavedThroughOneNodeIsReadAndUpdatedThroughTheOther() {
        DesignService a = service(node());
        DesignService b = service(node());
        StoredDesign d = a.create("ann", "Rates screen", "trade", null, "rachana: 1\n", "notes");
        a.addSamples("ann", d.id, List.of(new DesignService.NewSample("t1.json", StoredDesign.DOCUMENT, null, null, "{\"tradeId\":\"T1\"}")));

        StoredDesign seen = b.get("ann", d.id);                             // the failover case: no DRS-5006 on the other node
        assertThat(seen.name).isEqualTo("Rates screen");
        assertThat(b.sample("ann", d.id, "t1.json")).isEqualTo("{\"tradeId\":\"T1\"}");
        b.update("ann", d.id, new DesignService.Patch(null, null, "edited on b", null, null));
        assertThat(a.get("ann", d.id).notes).isEqualTo("edited on b");
        assertThat(a.list("ann")).extracting(s -> s.design().id).containsExactly(d.id);
    }

    @Test
    void twoConcurrentSavesOfOneDesignGiveOneConflict() throws Exception {
        ContentDesignStore na = node();
        ContentDesignStore nb = node();
        StoredDesign d = service(na).create("ann", "Shared", "trade", null, "", "");
        StoredDesign fromA = na.get("ann", d.id).orElseThrow();
        StoredDesign fromB = nb.get("ann", d.id).orElseThrow();
        fromA.notes = "a";
        fromB.notes = "b";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ErrorCode>> results = Stream.of(new Object[] {na, fromA}, new Object[] {nb, fromB}).map(p -> pool.submit(() -> {
            go.await();
            try {
                ((ContentDesignStore) p[0]).save((StoredDesign) p[1]);
                return (ErrorCode) null;
            } catch (DrishtiException e) {
                return e.errorCode();
            }
        })).toList();
        go.countDown();
        int conflicts = 0;
        for (Future<ErrorCode> f : results) {
            ErrorCode c = f.get(30, TimeUnit.SECONDS);
            if (c != null) {
                assertThat(c).isEqualTo(ErrorCode.CONTENT_CONFLICT);
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(conflicts).isEqualTo(1);
        assertThat(na.get("ann", d.id).orElseThrow().notes).isIn("a", "b");
    }

    @Test
    void ownersStayIsolatedAcrossNodes() {
        DesignService a = service(node());
        DesignService b = service(node());
        StoredDesign d = a.create("ann", "Mine", "trade", null, "", "");
        assertThatThrownBy(() -> b.get("bob", d.id)).isInstanceOf(DrishtiException.class).hasMessageContaining("DRS-5006");
        assertThat(b.list("bob")).isEmpty();
        b.delete("ann", d.id);
        assertThatThrownBy(() -> a.get("ann", d.id)).hasMessageContaining("DRS-5006");
    }

    @Test
    void theOldFolderIsImportedOnceAndKept() throws Exception {
        Path old = dir.resolve("designs");
        FileDesignStore legacy = new FileDesignStore(old);
        StoredDesign d = DesignStoreChecks.design("ann", "a1b2c3d4e5f6", "Old one");
        d.samples.add(new StoredDesign.SampleInfo("t1.json", StoredDesign.DOCUMENT, null, null, 14));
        legacy.save(d);
        legacy.putSample("ann", d.id, "t1.json", "{\"tradeId\":\"T1\"}");
        legacy.save(DesignStoreChecks.design("bob", "ffffffffffff", "Bob's"));

        ContentDesignStore to = node();
        DesignImport.Result r = DesignImport.fromFiles(old, to);
        assertThat(r).isEqualTo(new DesignImport.Result(2, 0, 0));
        assertThat(to.get("ann", d.id).orElseThrow().name).isEqualTo("Old one");
        assertThat(to.sample("ann", d.id, "t1.json")).contains("{\"tradeId\":\"T1\"}");
        assertThat(Files.exists(old)).isFalse();
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("designs-imported-"));
        }
        assertThat(DesignImport.fromFiles(old, to)).isEqualTo(new DesignImport.Result(0, 0, 0));      // a second start: nothing to do

        // the same Design brought back in an old folder is never overwritten: skipped
        StoredDesign edited = to.get("ann", d.id).orElseThrow();
        edited.notes = "edited after the import";
        to.save(edited);
        new FileDesignStore(old).save(d);
        assertThat(DesignImport.fromFiles(old, to)).isEqualTo(new DesignImport.Result(0, 1, 0));
        assertThat(to.get("ann", d.id).orElseThrow().notes).isEqualTo("edited after the import");
    }

    @Test
    void theDatabaseIsImportedOnceThenMarked() {
        FileDesignStore rows = new FileDesignStore(dir.resolve("as-if-jpa"));
        rows.save(DesignStoreChecks.design("ann", "a1b2c3d4e5f6", "From the database"));
        ContentDesignStore to = node();
        assertThat(DesignImport.fromDatabase(rows, to)).isEqualTo(new DesignImport.Result(1, 0, 0));
        assertThat(to.content().get(DesignImport.JPA_MARKER)).isPresent();
        to.delete("ann", "a1b2c3d4e5f6");
        assertThat(DesignImport.fromDatabase(rows, to)).isEqualTo(new DesignImport.Result(0, 0, 0));   // a deleted Design does not come back
        assertThat(to.get("ann", "a1b2c3d4e5f6")).isEmpty();
        assertThat(rows.get("ann", "a1b2c3d4e5f6")).isPresent();                                       // the rows are left in place
    }
}
