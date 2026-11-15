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
package com.ash.drishti.settingsfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFileTest {

    @AfterEach
    void clean() {
        for (String k : new String[] {SettingsFile.PROPERTY, "sf.test.a", "sf.test.b", "sf.test.c"}) System.clearProperty(k);
    }

    private Path write(Path dir, String text) throws Exception {
        Path f = dir.resolve("drishti.ini");
        Files.writeString(f, text);
        return f;
    }

    @Test
    void entriesBecomePropertiesAndSectionsAndCommentsAreIgnored(@TempDir Path dir) throws Exception {
        System.setProperty(SettingsFile.PROPERTY, write(dir, "# a comment\n; another\n[paths]\nsf.test.a = /x/y\n\n[more]\nsf.test.b=1=2\n").toString());
        assertThat(SettingsFile.applyFromSystemProperty()).containsExactly("sf.test.a", "sf.test.b");
        assertThat(System.getProperty("sf.test.a")).isEqualTo("/x/y");
        assertThat(System.getProperty("sf.test.b")).as("the first = splits; the value keeps the rest").isEqualTo("1=2");
    }

    @Test
    void anExplicitPropertyBeatsTheFile(@TempDir Path dir) throws Exception {
        System.setProperty("sf.test.a", "explicit");
        System.setProperty(SettingsFile.PROPERTY, write(dir, "sf.test.a=fromfile\nsf.test.c=3\n").toString());
        assertThat(SettingsFile.applyFromSystemProperty()).containsExactly("sf.test.c");
        assertThat(System.getProperty("sf.test.a")).isEqualTo("explicit");
        assertThat(System.getProperty("sf.test.c")).isEqualTo("3");
        assertThat(SettingsFile.applyFromSystemProperty()).as("again: nothing left to set").isEmpty();
    }

    @Test
    void noPropertyMeansNothingIsLookedFor() {
        assertThat(SettingsFile.applyFromSystemProperty()).isEmpty();
    }

    @Test
    void aRelativePathIsRefused() {
        System.setProperty(SettingsFile.PROPERTY, "drishti.ini");
        assertThatThrownBy(SettingsFile::applyFromSystemProperty).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
    }

    @Test
    void aMissingFileAndABadLineAreClearErrors(@TempDir Path dir) throws Exception {
        System.setProperty(SettingsFile.PROPERTY, dir.resolve("nope.ini").toString());
        assertThatThrownBy(SettingsFile::applyFromSystemProperty).isInstanceOf(IllegalStateException.class).hasMessageContaining("not a file");
        System.setProperty(SettingsFile.PROPERTY, write(dir, "no equals sign\n").toString());
        assertThatThrownBy(SettingsFile::applyFromSystemProperty).isInstanceOf(IllegalStateException.class).hasMessageContaining(":1:");
    }
}
