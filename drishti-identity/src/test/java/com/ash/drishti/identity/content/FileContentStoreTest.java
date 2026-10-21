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
package com.ash.drishti.identity.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The filesystem backend: the contract, plus its own rules (visible names only, two stores on one root, stale locks). */
class FileContentStoreTest extends ContentStoreContract {

    private Path root;

    @Override
    protected ContentStore open() {
        root = Path.of("target", "content-tests", "fs-" + UUID.randomUUID());
        return new FileContentStore(root);
    }

    @Override
    protected ContentStore reopen() {
        return new FileContentStore(root);
    }

    @Test
    void namesAreVisibleAndStayUnderTheRoot() throws Exception {
        store.put("designs/.ann/.x.json", "x".getBytes(StandardCharsets.UTF_8), null);
        store.put("a/CON", "x".getBytes(StandardCharsets.UTF_8), null);
        try (Stream<Path> s = Files.walk(root)) {
            s.forEach(p -> assertThat(p.getFileName().toString()).as(p.toString()).doesNotStartWith("."));
        }
        assertThat(store.list("designs/")).containsExactly("designs/.ann/.x.json");
    }

    @Test
    void twoStoresOnOneRootSeeEachOtherAndConflict() {
        ContentStore b = new FileContentStore(root);
        String v = store.put("shared/a", "from a".getBytes(StandardCharsets.UTF_8), null);
        assertThat(b.get("shared/a").orElseThrow().text()).isEqualTo("from a");
        b.put("shared/a", "from b".getBytes(StandardCharsets.UTF_8), v);
        try {
            store.put("shared/a", "late".getBytes(StandardCharsets.UTF_8), v);
            throw new AssertionError("no conflict");
        } catch (DrishtiException e) {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.CONTENT_CONFLICT);
        }
    }

    @Test
    void aStaleLockIsBroken() throws Exception {
        String v = store.put("locks/a", "1".getBytes(StandardCharsets.UTF_8), null);
        Path lock = root.resolve("items").resolve("locks").resolve("a~lock");
        Files.writeString(lock, "crashed writer");
        Files.setLastModifiedTime(lock, FileTime.fromMillis(System.currentTimeMillis() - FileContentStore.STALE_LOCK_MS - 5_000));
        store.put("locks/a", "2".getBytes(StandardCharsets.UTF_8), v);
        assertThat(Files.exists(lock)).isFalse();
    }

    @Test
    void anItemCannotAlsoBeAFolder() {
        store.put("p/a", "x".getBytes(StandardCharsets.UTF_8), null);
        try {
            store.put("p/a/b", "y".getBytes(StandardCharsets.UTF_8), null);
            throw new AssertionError("accepted");
        } catch (DrishtiException e) {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.CONTENT_BAD_KEY);
        }
    }
}
