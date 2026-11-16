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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A hidden item of an earlier build is renamed once to its visible name; when both exist nothing is lost. */
class LegacyNamesTest {

    @TempDir Path dir;

    @Test
    void renamesOnceAndKeepsContent() throws Exception {
        Files.createDirectories(dir.resolve(".history"));
        Files.writeString(dir.resolve(".history/a.yaml"), "x");
        Path to = LegacyNames.migrate(dir, ".history", "history");
        assertThat(to).isEqualTo(dir.resolve("history"));
        assertThat(dir.resolve(".history")).doesNotExist();
        assertThat(Files.readString(dir.resolve("history/a.yaml"))).isEqualTo("x");
    }

    @Test
    void mergesWhenBothExistAndNeverOverwrites() throws Exception {
        Files.createDirectories(dir.resolve(".history"));
        Files.createDirectories(dir.resolve("history"));
        Files.writeString(dir.resolve(".history/old.yaml"), "old");
        Files.writeString(dir.resolve(".history/same.yaml"), "legacy");
        Files.writeString(dir.resolve("history/same.yaml"), "new");
        LegacyNames.migrate(dir, ".history", "history");
        assertThat(Files.readString(dir.resolve("history/old.yaml"))).isEqualTo("old");
        assertThat(Files.readString(dir.resolve("history/same.yaml"))).isEqualTo("new");
        assertThat(Files.readString(dir.resolve(".history/same.yaml"))).isEqualTo("legacy");   // kept: nothing lost
    }

    @Test
    void movesAcrossFolders() throws Exception {
        Files.createDirectories(dir.resolve("config/.generated"));
        Files.writeString(dir.resolve("config/.generated/c.properties"), "p");
        LegacyNames.move(dir.resolve("config/.generated"), dir.resolve("data/connectors/generated"));
        assertThat(Files.readString(dir.resolve("data/connectors/generated/c.properties"))).isEqualTo("p");
    }
}
