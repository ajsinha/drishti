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
package com.ash.drishti.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DATA-03: a day's file that is there but cannot be read is a failure, not "not held": the read throws (the router
 * stops it with DRS-1003 naming the connector) instead of passing to the next store, which would answer with its own,
 * different data. A day the folder does not hold still passes.
 */
class FileReadFailureTest {

    @Test
    void anUnreadableDaysFileFailsTheReadInsteadOfPassingItOn() throws Exception {
        Path root = Files.createTempDirectory("recent");
        Path day = Files.createDirectories(root.resolve("trading/2026-09-30"));
        Path file = day.resolve("trade.jsonl");
        Files.writeString(file, "{\"id\":\"MX-1\",\"mtm\":-342639049}\n{\"id\":\"MX-2\",\"mtm\":5}\n");
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"), "POSIX permissions");
        FileSourcePlugin p = new FileSourcePlugin();
        AsOf d30 = AsOf.of(LocalDate.of(2026, 9, 30));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));   // before the day is indexed
        try {
            assumeTrue(!Files.isReadable(file), "not running as a user that reads every file");
            p.start(DatedSourceContract.context(Map.of("root", root.toString(), "domain", "trading", "source-name", "recent-files")));
            assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "MX-1"), d30)).isInstanceOf(java.io.IOException.class).hasMessageContaining("trade.jsonl");
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
        assertThat(p.fetch(EntityRef.of("trade", "MX-1"), d30)).isPresent();
        assertThat(p.fetch(EntityRef.of("trade", "MX-1"), AsOf.of(LocalDate.of(2019, 12, 5)))).as("a day it does not hold").isEmpty();
    }
}
