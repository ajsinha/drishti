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
package com.ash.drishti.identity.collab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 2.9.4: seals are JSON Lines ({@code seals.jsonl}); the tab-separated {@code seals.log} of earlier releases is still read, never rewritten. */
class FileSealStoreTest {

    @TempDir
    Path dir;

    @Test
    void sealsAreJsonLinesAndTheOldLogIsStillRead() throws Exception {
        Files.writeString(dir.resolve("seals.log"), "thread:1\t3\told-hash\nthread:2\t1\tkept\n");
        FileSealStore s = new FileSealStore(dir);
        assertThat(s.get("thread:1")).contains(new Seal(3, "old-hash"));
        s.put("thread:1", new Seal(4, "new-hash"));
        assertThat(Files.readString(dir.resolve("seals.jsonl"))).isEqualTo("{\"key\":\"thread:1\",\"count\":4,\"hash\":\"new-hash\"}\n");
        assertThat(Files.readString(dir.resolve("seals.log"))).isEqualTo("thread:1\t3\told-hash\nthread:2\t1\tkept\n");   // never rewritten
        FileSealStore again = new FileSealStore(dir);                                   // the JSONL line wins over the old log's
        assertThat(again.get("thread:1")).contains(new Seal(4, "new-hash"));
        assertThat(again.get("thread:2")).contains(new Seal(1, "kept"));
        assertThat(again.all()).hasSize(2);
    }
}
